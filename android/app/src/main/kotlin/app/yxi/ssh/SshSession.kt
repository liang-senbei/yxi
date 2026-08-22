package app.yxi.ssh

import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * 一条 SSH 连接。分层参考 ConnectBot 的 `transport/`（PRD 附录 B.2）：
 * 连接本身与「在上面开什么通道」分开——
 *   · shell channel  → 终端（本类的 [openShell]）
 *   · exec channel   → 数据/事件（G4 起）
 *   · sftp channel   → 文件模式（G7）
 * 三者共用同一条连接，不重复握手。
 */
class SshSession(
    private val cfg: HostConfig,
    private val knownHosts: KnownHosts? = null,
) {

    private val jsch = JSch()
    private var session: Session? = null

    init {
        Crypto.ensureProviders()
        // 把 jsch 自己的日志接到 logcat —— 认证失败时光看异常消息什么也看不出来
        JSch.setLogger(object : com.jcraft.jsch.Logger {
            override fun isEnabled(level: Int) = true
            override fun log(level: Int, message: String) { Log.i("YxiSSH", "[$level] $message") }
        })
    }

    class Shell(
        private val channel: com.jcraft.jsch.Channel,
        val output: InputStream,
        private val input: OutputStream,
    ) {
        /**
         * ⚠️ **jsch 的 Session 写包路径不是线程安全的。**
         * 终端控件的 `onResize` 会从它自己的线程调 [resize]，而 [write] 从另一个协程来，
         * 两者并发写同一条 SSH 连接就会把包流写坏 —— 表现有两种，都极难定位：
         *   · 服务器报 `ssh_dispatch_run_fatal: message authentication code incorrect` 后杀连接
         *   · 通道无声无息地关闭（`ch.connected=false`），客户端什么错都没有
         * 实测：控件一发 `onResize -> 55x42`，通道立刻死。加锁串行化后正常。
         */
        private val ioLock = kotlinx.coroutines.sync.Mutex()
        // ⚠️ 两条都必须 suspend + IO：安卓禁止主线程网络操作，
        // 直接在 Compose 的回调里调会抛 NetworkOnMainThreadException。
        //
        // ⚠️ 而且必须吞掉 Broken pipe：终端控件的 resize 回调和连接建立/断开之间有竞态，
        // 往已关闭的通道写会抛 IOException —— 从协程里逸出就是整个 app 崩掉。
        // 通道断了不是异常情况，是常态（切网、远端退出、会话关闭），按「写失败」处理即可。
        suspend fun write(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
            ioLock.withLock { runCatching { input.write(bytes); input.flush() } }
                .onFailure { Log.w("YxiSSH", "写入失败（通道多半已关）: ${it.message}") }
                .isSuccess
        }
        suspend fun write(text: String): Boolean = write(text.toByteArray())
        /** 横竖屏切换、软键盘弹出都要重发，不然远端还按老尺寸折行 */
        suspend fun resize(cols: Int, rows: Int, widthPx: Int = 0, heightPx: Int = 0): Boolean =
            withContext(Dispatchers.IO) {
                // ⚠️ 非法尺寸会让远端的 tmux 直接退出（实测：控件首次测量可能给出 0）。
                // 宁可不发也不能发 0 —— 断开一个 attach 比少一次 resize 贵得多。
                if (cols <= 0 || rows <= 0) {
                    Log.w("YxiSSH", "跳过非法 resize: ${cols}x${rows}")
                    return@withContext false
                }
                Log.i("YxiSSH", "resize -> ${cols}x${rows}")
                ioLock.withLock {
                  runCatching {
                    when (val c = channel) {
                        is ChannelShell -> c.setPtySize(cols, rows, widthPx, heightPx)
                        is com.jcraft.jsch.ChannelExec -> c.setPtySize(cols, rows, widthPx, heightPx)
                        else -> Unit
                    }
                  }.isSuccess
                }
            }
        fun close() { runCatching { channel.disconnect() } }
        val isConnected: Boolean get() = channel.isConnected
    }

    suspend fun connect(timeoutMs: Int = 15_000): Unit = withContext(Dispatchers.IO) {
        when (val a = cfg.auth) {
            is HostConfig.Auth.PrivateKey ->
                jsch.addIdentity(cfg.alias, a.pem.toByteArray(), null, a.passphrase?.toByteArray())
            is HostConfig.Auth.Password -> Unit
        }
        val s = jsch.getSession(cfg.username, cfg.hostname, cfg.port)
        (cfg.auth as? HostConfig.Auth.Password)?.let { s.setPassword(it.password) }

        // ⚠️ 必须避开 AES-GCM。jsch 的 `aes*-gcm@openssh.com` 和 strict-KEX 一起用时
        // 序列号会失步 —— 表现是握手认证全过、数据流几百字节后连接被服务器杀掉，
        // sshd 日志里是 `ssh_dispatch_run_fatal: ... message authentication code incorrect`。
        // 客户端这边只看到通道莫名关闭，完全定位不到（见 TROUBLESHOOTING #16）。
        // CTR + HMAC 是最稳的组合。
        s.setConfig("cipher.s2c", "aes256-ctr,aes192-ctr,aes128-ctr")
        s.setConfig("cipher.c2s", "aes256-ctr,aes192-ctr,aes128-ctr")
        s.setConfig("mac.s2c", "hmac-sha2-256-etm@openssh.com,hmac-sha2-256")
        s.setConfig("mac.c2s", "hmac-sha2-256-etm@openssh.com,hmac-sha2-256")

        // 主机指纹校验 —— SSH 抵御中间人的唯一防线（PRD §8 第 2 条）
        if (knownHosts != null) {
            s.hostKeyRepository = knownHosts
            s.userInfo = knownHosts.userInfo()
            // ask：没见过就问用户；**指纹变了 jsch 直接拒，不会问**
            s.setConfig("StrictHostKeyChecking", "ask")
        } else {
            // 没传校验器 = 调用方明确不要校验（只应出现在测试里）
            s.setConfig("StrictHostKeyChecking", "no")
        }
        s.connect(timeoutMs)
        session = s
    }

    suspend fun openShell(cols: Int = 80, rows: Int = 24): Shell = withContext(Dispatchers.IO) {
        val s = requireNotNull(session) { "还没 connect()" }
        val ch = s.openChannel("shell") as ChannelShell
        ch.setPtyType("xterm-256color")   // 要彩色输出就得是 256color，不能是 dumb
        ch.setPtySize(cols, rows, 0, 0)
        // 让主机侧知道这是手机在开 —— 抄 Moshi 的 MOSHI_CLIENT=1（PRD 附录 C.2）
        ch.setEnv("YXI_CLIENT", "1")
        val out = ch.inputStream
        val inp = ch.outputStream
        ch.connect(10_000)
        Shell(ch, out, inp)
    }

    /**
     * 带 PTY 直接跑一条命令，返回可交互的 [Shell]。
     *
     * **比「开登录 shell 再打命令」干净得多**：
     *   · 没有登录横幅（motd 一大堆，还得等它打完）
     *   · **没有时序竞态** —— 往刚开的 shell 里写命令，如果 shell 还没开始读，
     *     字节会被 tty 回显然后冲掉（实测踩过：命令回显了但没执行）
     *   · 远端进程就是 tmux 本身，退出即通道结束，语义清楚
     */
    suspend fun openPtyCommand(command: String, cols: Int = 80, rows: Int = 24): Shell =
        withContext(Dispatchers.IO) {
            val s = requireNotNull(session) { "还没 connect()" }
            val ch = s.openChannel("exec") as com.jcraft.jsch.ChannelExec
            ch.setPty(true)
            ch.setPtyType("xterm-256color")   // 要彩色就不能是 dumb
            ch.setPtySize(cols, rows, 0, 0)
            ch.setEnv("YXI_CLIENT", "1")      // 让主机侧知道是手机在开（抄 Moshi，附录 C.2）
            ch.setCommand(command)
            val out = ch.inputStream
            val inp = ch.outputStream
            // ⚠️ exec channel 的 stderr 是独立的一条流。不读它，远端进程的报错就
            // 凭空消失 —— 实测踩过：tmux 的 `open terminal failed: not a terminal`
            // 走 stderr，通道静默关闭，从表现上完全看不出原因。
            val err = ch.errStream
            ch.connect(10_000)
            Thread {
                runCatching {
                    val b = ByteArray(4096)
                    while (true) {
                        val n = err.read(b); if (n < 0) break
                        Log.w("YxiSSH", "远端 stderr: " + String(b, 0, n).trim())
                    }
                }
            }.apply { isDaemon = true }.start()
            Shell(ch, out, inp)
        }

    /**
     * 开一条**不带 PTY** 的 exec 流，用来跟随长期输出（`tail -f` 之类）。
     *
     * ⚠️ 别给它 `setPty(true)`：带 PTY 的 exec 输入流在**没有数据可读时会提前返回 EOF**
     * （实测 `sleep 25` 只读到 13 字节，而通道还 connected）——见 TROUBLESHOOTING #17。
     * 不带 PTY 就没这问题，而且 `tail -f` 本来也不需要终端。
     */
    suspend fun openExecStream(command: String): Shell = withContext(Dispatchers.IO) {
        val s = requireNotNull(session) { "还没 connect()" }
        val ch = s.openChannel("exec") as com.jcraft.jsch.ChannelExec
        ch.setCommand(command)
        val out = ch.inputStream
        val inp = ch.outputStream
        val err = ch.errStream
        ch.connect(10_000)
        Thread {
            runCatching {
                val b = ByteArray(2048)
                while (true) { val n = err.read(b); if (n < 0) break
                    Log.w("YxiSSH", "远端 stderr: " + String(b, 0, n).trim()) }
            }
        }.apply { isDaemon = true }.start()
        Shell(ch, out, inp)
    }

    /** 跑一条命令拿输出就退（会话枚举、探测都走它）。G4 起大量使用。 */
    suspend fun exec(command: String): String = withContext(Dispatchers.IO) {
        val s = requireNotNull(session) { "还没 connect()" }
        val ch = s.openChannel("exec") as com.jcraft.jsch.ChannelExec
        ch.setCommand(command)
        val out = ch.inputStream
        ch.connect(10_000)
        val text = out.readBytes().decodeToString()
        ch.disconnect()
        text
    }

    /**
     * 把一行公钥装进远端的 `~/.ssh/authorized_keys`，相当于 `ssh-copy-id`。
     *
     * 幂等：已经有同一行就不重复追加。权限也一并修对——
     * **`.ssh` 必须 700、`authorized_keys` 必须 600，否则 sshd 会拒绝使用它**
     * （这是新手最常见的「装了公钥还是要密码」的原因）。
     */
    suspend fun installPublicKey(line: String): String {
        val safe = line.trim().replace("'", "")   // 公钥里本不该有单引号，去掉防注入
        return exec(
            "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                "touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && " +
                "grep -qxF '$safe' ~/.ssh/authorized_keys || echo '$safe' >> ~/.ssh/authorized_keys; " +
                "grep -c -F 'yxi@android' ~/.ssh/authorized_keys"
        )
    }

    fun disconnect() { runCatching { session?.disconnect() }; session = null }
    val isConnected: Boolean get() = session?.isConnected == true
}
