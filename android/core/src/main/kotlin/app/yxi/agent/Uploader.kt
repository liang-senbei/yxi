package app.yxi.agent

import app.yxi.ssh.Sftp
import app.yxi.ssh.SshSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 带重试的附件上传。**从 ChatScreen 里抽出来的**,理由只有一个:那段逻辑在 Composable 里,
 * 压力测试碰不到它 —— 于是「传视频失败」修没修全靠读代码判断。抽出来之后
 * [app.yxi.UploadStressTest] 跑的就是 App 真正在跑的这段。
 *
 * 三条规矩,每条都是踩出来的:
 * · **每次尝试重新取当前连接**([aliveSsh])。上传协程活得比一次重连长,断线后看门狗会换一个
 *   新的 SshSession 对象;把启动那一刻的对象钉死,一批图片里剩下的每张都在死连接上重试。
 *   (用户 2026-09-06「一次上传很多图片还是失败」)
 * · **每次尝试开一条新 SFTP 通道**。复用的通道空闲久了会被服务器关掉、或上次出错后进了坏状态
 *   (TROUBLESHOOTING #115)。
 * · **失败不删半个文件,下一次续传**。手机网络一抖就从头来的话,大文件在抖动的链路上永远传不完。
 * · **没进度就掐**(见 [STALL_MS])。这条是 2026-09-06 用线程栈挖出来的,下面详说。
 */
object Uploader {

    /** 最多试几次。间隔 1/2/4/8/8 秒 —— 重连本身要几秒,间隔太密等于在死连接上空转。 */
    const val ATTEMPTS = 6
    /** 每次尝试前最多等多久让连接回来(第一次不等) */
    const val WAIT_RECONNECT_MS = 30_000L

    /**
     * **多久没有任何字节动静就判定卡死。**
     *
     * ⚠️⚠️ 这是「上传失败」里最难看的一半:**它根本不失败,它永远转圈。**
     * 2026-09-06 在 Mac mini 上限速 1.5Mbps + 中途断流 8 秒,线程栈抓到:
     * ```
     * DefaultDispatcher-worker-1  TimedWaiting
     *   java.io.PipedInputStream.read      ← 没有超时,只会 wait(1000) 循环等下去
     *   com.jcraft.jsch.ChannelSftp.fill
     *   com.jcraft.jsch.ChannelSftp.header
     *   com.jcraft.jsch.ChannelSftp.checkStatus   ← 在等一个永远不会来的 SFTP 应答
     * ```
     * 而 jsch 自己的保活**这时候是失效的**:会话线程卡在原生 socket 写上(TCP 发送缓冲被塞满),
     * 心跳包根本发不出去,`serverAliveCountMax` 那套判死逻辑压根轮不到执行。
     * 于是:附件卡片永远停在「传输中」,发送键永远是灰的,一个字的解释都没有 ——
     * 用户报的「上传失败」有一部分其实是这个。
     *
     * 所以**不能指望 SSH 层报错**,得在这一层自己看着字节数:超过这个时间没动静,
     * 先关通道(jsch 关掉管道流,阻塞的 read 立刻抛),还不醒就断整条连接交给看门狗重连;
     * 下一次尝试从断点续传,传过的字节不白费。
     *
     * 取 60 秒:一块 SFTP 数据是 32KB,哪怕链路慢到 1KB/s 也就 32 秒,不会误杀「慢但活着」的传输。
     */
    const val STALL_MS = 60_000L
    /** 看门狗多久查一次 */
    const val STALL_CHECK_MS = 5_000L

    /**
     * @param aliveSsh 取一条**活着**的连接,最多等给定毫秒;等不到返回 null。
     * @param open 打开文件流。**每次尝试都会重新调用** —— 续传时 jsch 自己 skip 掉远端已有的那段。
     * @param total 文件字节数,只为算进度。
     * @param cancelled 用户按了 ✕ 就返回 true:正在传的会停,半个文件会被删掉。
     */
    suspend fun upload(
        aliveSsh: suspend (Long) -> SshSession?,
        open: () -> java.io.InputStream,
        total: Long,
        sessionName: String, name: String, index: Int, isImage: Boolean, stamp: String,
        cancelled: () -> Boolean = { false },
        progress: (Long, Long) -> Unit = { _, _ -> },
        attempts: Int = ATTEMPTS,
        pause: suspend (Long) -> Unit = { delay(it) },
    ): Result<Attachments.Staged> = kotlinx.coroutines.coroutineScope {
        val path = Attachments.remotePath(sessionName, name, stamp)
        var last: Throwable? = null
        for (attempt in 0 until attempts) {
            if (cancelled()) return@coroutineScope Result.failure(CancelledException)
            if (attempt > 0) pause(minOf(1000L shl (attempt - 1), 8000L))
            val ssh = aliveSsh(if (attempt == 0) 0L else WAIT_RECONNECT_MS)
            if (ssh == null) { last = IllegalStateException(Tr.t("连接断了,等了 30 秒没接上")); continue }
            val sftp = app.yxi.ssh.catching { ssh.openSftp() }.getOrNull()
            if (sftp == null) { last = IllegalStateException(Tr.t("开不了 SFTP 通道")); continue }
            // 卡死看门狗:字节一停就掐(见 [STALL_MS])。jsch 那边是无超时的阻塞读,只能从外面弄醒
            val lastMove = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            val watchdog = launch {
                while (true) {
                    delay(STALL_CHECK_MS)
                    if (System.currentTimeMillis() - lastMove.get() < STALL_MS) continue
                    // ⚠️ **必须扔到独立线程,不能 launch 成子协程。** 关通道/断连接自己也可能卡住
                    //    (要往僵住的连接里写),而 coroutineScope 会**等所有子协程结束才返回** ——
                    //    那就等于把刚修好的「永远转圈」原样换个地方又造一遍。
                    detach { sftp.close() }
                    delay(3_000)
                    if (System.currentTimeMillis() - lastMove.get() >= STALL_MS) {
                        // 关通道没弄醒 = 整条连接是僵的。断掉,让重连看门狗换一条新的,下次续传
                        detach { ssh.disconnect() }
                    }
                    break
                }
            }
            try {
                return@coroutineScope Result.success(
                    Attachments.upload(
                        sftp, sessionName, name, open(), total, index, isImage, stamp,
                        progress = { done, all -> lastMove.set(System.currentTimeMillis()); progress(done, all); !cancelled() },
                        resume = attempt > 0,
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                if (cancelled()) { runCatching { sftp.rm(path) }; return@coroutineScope Result.failure(CancelledException) }
            } finally {
                watchdog.cancel()
                runCatching { sftp.close() }
            }
        }
        // 都没成:半个文件别留在服务器上。连接不活就删不掉,交给暂存区 3 天的自动清理
        runCatching { aliveSsh(0L)?.openSftp()?.let { f -> try { f.rm(path) } finally { f.close() } } }
        Result.failure(last ?: RuntimeException(Tr.t("传输中断了")))
    }

    /** 扔到独立守护线程去做,做不完也不拖住任何人。收拾僵死连接专用。 */
    private fun detach(what: () -> Unit) {
        Thread { runCatching(what) }.apply { isDaemon = true }.start()
    }

    /** 用户按 ✕ 取消 —— 跟「失败」分开,界面上不该报错 */
    object CancelledException : RuntimeException("cancelled")

    /** 失败原因转成给人看的一句话 */
    fun explain(e: Throwable): String = Sftp.explain(e)
}
