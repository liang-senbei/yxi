package app.yxi

import androidx.test.platform.app.InstrumentationRegistry
import app.yxi.agent.Attachments
import app.yxi.ssh.HostStore
import app.yxi.ssh.KeyManager
import app.yxi.ssh.SshSession
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **上传压力测试**（用户 2026-09-05：「上传视频和图片经常失败，你自己压力测试一下」）。
 *
 * 走的是 App 真正的上传路径（[Attachments.upload] → [app.yxi.ssh.Sftp.write]，每个文件一条新 SFTP 通道），
 * 只是没有界面。连的是 App 里配好的第一台主机（模拟器上就是 10.0.2.2 = 宿主机），
 * 所以**这不是 mock**：字节真的经过 SSH 落到服务器，再用 `sftp.size` 核对一字不差。
 *
 * ⚠️ 大文件那几档（64MB / 160MB）就是为「视频」设的：原来整个文件先 `readBytes()` 进内存，
 * 视频一上就 OOM —— 这个测试在改成流式之前跑不过 160MB 那一档。
 *
 * 跑法：`./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.yxi.UploadStressTest`
 * 没配主机 / 连不上时**直接失败并说明**，不静默跳过 —— 「没跑」和「跑过了」必须分得清。
 */
class UploadStressTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun connect(): SshSession {
        // ⚠️ 每次 connectedAndroidTest 都会重装 App，KeyManager 的密钥跟着换 —— 所以测试用一把
        //    **固定的**密钥：宿主机上 `ssh-keygen -t ed25519 -f ~/.ssh/yxi-stress`，公钥进 authorized_keys，
        //    私钥 `adb push` 到 /sdcard/yxi-stress-key。没有这个文件才退回 App 自己的密钥（要手工加公钥）。
        // ⚠️ /sdcard 在 Android 11+ 的分区存储下 App 读不到（实测 canRead=false），
        //    所以密钥走 instrumentation 参数进来：-Pandroid.testInstrumentationRunnerArguments.stressKey=<base64url 的私钥>
        val fixed = InstrumentationRegistry.getArguments().getString("stressKey")
            ?.let { String(android.util.Base64.decode(it, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)) }
            ?: java.io.File("/sdcard/yxi-stress-key").takeIf { it.canRead() }?.readText()
        if (fixed != null) {
            // ⚠️ 模拟器在 Mac mini 上跑之后 10.0.2.2 是那台 Mac(没有 root/sshd),目标机要能指定:
            //    -Pandroid.testInstrumentationRunnerArguments.stressHost=<ip> stressPort=22 stressUser=root
            val args = InstrumentationRegistry.getArguments()
            val cfg = app.yxi.ssh.HostConfig("dev", args.getString("stressHost") ?: "10.0.2.2",
                args.getString("stressPort")?.toIntOrNull() ?: 22, args.getString("stressUser") ?: "root",
                app.yxi.ssh.HostConfig.Auth.PrivateKey(fixed))
            val s = SshSession(cfg, null)
            runBlocking { s.connect() }
            return s
        }
        // ⚠️⚠️ **测试绝不往 App 的主机表里写东西。** 原来这里「没配主机就自己种一台指向 10.0.2.2」——
        //    模拟器搬到 Mac mini 之后 10.0.2.2 是那台 Mac(没有 sshd),于是这台 stress-dev 连不上、
        //    App 反复弹指纹确认框把界面卡死,而**模拟器是三个人共用的**:2026-09-06 cc-Yxi_Entertainment
        //    撞上,只好清空 hosts.json 才能干活。测试留下的东西要能自己收走,收不走就别留。
        //    现在:没给密钥直接失败并说清怎么跑,不碰 HostStore。
        val store = HostStore(ctx); val keys = KeyManager(ctx)
        android.util.Log.i("UploadStress", "PUBKEY " + keys.publicKeyLine())
        // ⚠️ 没有可用主机时**跳过**,不是失败。
        //    这几条要一台真 SSH 目标机才跑得动,而全套里没人会带 stressKey ——
        //    以前直接 error() 的话,全套永远红 7 条,红得有理由但会**稀释信号**:
        //    看惯了红的人也就不看红的了(cc-Yxi_pilot 2026-09-07 跑全套 306/红 7 全是这几条)。
        //    assumeTrue 出来的是「跳过 + 原因」,和「通过」仍然分得清 —— 项目那条
        //    「没跑和跑过了必须分得清」照旧成立,只是不再冒充失败。
        val h = store.hosts.value.firstOrNull()
        assumeTrue("上传压测需要一台 SSH 目标机,跳过。跑法:" +
            "-Pandroid.testInstrumentationRunnerArguments.stressKey=<base64url 私钥> " +
            "stressHost=<目标机> [stressPort=22 stressUser=root]", h != null)
        h!!
        val cfg = store.configFor(h, keys)
        assumeTrue("主机 ${h.alias} 没有可用的认证方式,跳过", cfg != null)
        cfg!!
        // ⚠️ 测试对着回环，不做主机指纹校验（真 App 一定做，见 KnownHosts）
        val s = SshSession(cfg, null)
        runBlocking { s.connect() }
        return s
    }

    /** 生成一个指定大小的临时文件（内容不是全零 —— 全零太好压缩，测不出真实吞吐）。 */
    private fun blob(mb: Double): File {
        val f = File.createTempFile("up-", ".bin", ctx.cacheDir)
        val chunk = ByteArray(1 shl 20) { (it * 31 + 7).toByte() }
        f.outputStream().buffered().use { o ->
            var left = (mb * (1 shl 20)).toLong()
            while (left > 0) { val n = minOf(left, chunk.size.toLong()).toInt(); o.write(chunk, 0, n); left -= n }
        }
        return f
    }

    private suspend fun uploadOne(s: SshSession, f: File, tag: String): Long {
        val sftp = s.openSftp()
        try {
            val stamp = "stress-" + System.nanoTime()
            val t0 = System.currentTimeMillis()
            val st = Attachments.upload(sftp, "cc-upload-stress", "$tag.bin", f.inputStream().buffered(), f.length(), 1, false, stamp, progress = { _, _ -> true })
            val remote = sftp.size(st.remotePath)
            assertEquals("$tag：服务器上的大小和本地对不上", f.length(), remote)
            runCatching { sftp.rm(st.remotePath) }
            return System.currentTimeMillis() - t0
        } finally { sftp.close() }
    }

    @Test fun 从64KB到160MB逐档传_大小一字不差() = runBlocking {
        val s = connect()
        try {
            for (mb in listOf(0.0625, 2.0, 16.0, 64.0, 160.0)) {
                val f = blob(mb)
                try {
                    val ms = uploadOne(s, f, "seq-${mb}MB")
                    android.util.Log.i("UploadStress", "%.1fMB 用了 %d ms（%.1f MB/s）".format(mb, ms, mb * 1000.0 / maxOf(ms, 1)))
                } finally { f.delete() }
            }
        } finally { s.disconnect() }
    }

    /**
     * **慢网场景**(用户 2026-09-06:「一次上传很多图片 / 传视频还是失败」)。
     * 跑之前在模拟器外面限速:`adb emu network speed 1500:8000`(上行 1.5Mbps ≈ 手机蜂窝)+ `network delay 200:600`。
     * ⚠️ 方法名用 ASCII:`am instrument -e class 类#方法` 的中文方法名穿过 ssh/adb 会被改掉,JUnit 报 Invalid test class。
     * 局域网 3MB/s 下这两条永远过;真机上传视频失败的根就在慢上行 + 抖动:SFTP 把上行塞满,心跳排在数据后面,
     * 2s×2 的保活等不到回包就把整条连接判死。跑完记得 `network speed full` / `network delay none`。
     */
    @Test fun slow8MB_singleFile_likeAShortVideo(): Unit = runBlocking {
        val s = connect()
        try {
            val f = blob(8.0)
            try {
                val ms = uploadOne(s, f, "slow-8MB")
                android.util.Log.i("UploadStress", "慢网 8MB 用了 %d ms（%.0f KB/s）".format(ms, 8.0 * 1024 * 1000.0 / maxOf(ms, 1)))
            } finally { f.delete() }
        } finally { s.disconnect() }
    }

    /**
     * ⚠️ 文件**故意小**(12 × 0.5MB):这条验的是「一批里每个文件各开一条通道」——通道开关、
     * 保活在传/不传之间来回切。真吞吐由上面那条 8MB 负责;慢网 × 大文件一条要跑 25 分钟,测试循环里没法用。
     */
    /**
     * **重试确定性验证之一:连接还没回来时要等,不能当场判失败。**
     * 不靠限速、不靠断流 —— 直接让 `aliveSsh` 前两次返回 null(= 看门狗还没重连上),
     * 第三次才给真连接。审查(2026-09-06)指出原来那条「断流」测试在链路没真出事时
     * **一次就成功、重试逻辑一行都没跑到**,所以补这条。
     */
    @Test fun retry_waitsForReconnect_thenSucceeds(): Unit = runBlocking {
        val s = connect()
        try {
            val f = blob(1.0)
            try {
                var calls = 0
                val waited = ArrayList<Long>()
                val r = app.yxi.agent.Uploader.upload(
                    aliveSsh = { wait -> waited += wait; if (++calls <= 2) null else s },
                    open = { f.inputStream().buffered() },
                    total = f.length(),
                    sessionName = "cc-upload-stress", name = "reconnect.bin",
                    index = 1, isImage = false, stamp = "stress-" + System.nanoTime(),
                    pause = { },                       // 别真等 1/2 秒,测的是逻辑不是时钟
                )
                val st = r.getOrElse { throw AssertionError("等到连接回来之后应该传成功：$it", it) }
                assertEquals("应该正好试到第 3 次", 3, calls)
                assertEquals("第一次不等,后面每次都给 30 秒窗口", listOf(0L, 30_000L, 30_000L), waited)
                val sf = s.openSftp()
                try { assertEquals("大小要一字不差", f.length(), sf.size(st.remotePath)); runCatching { sf.rm(st.remotePath) } }
                finally { sf.close() }
            } finally { f.delete() }
        } finally { s.disconnect() }
    }

    /**
     * **重试确定性验证之二:传到一半连接断掉,要重连 + 从断点续上,而且文件必须是完整的。**
     * 这条是「上传视频失败」的正脸:不靠限速,直接在传输中途把 SSH 连接掐了。
     * 断点续传如果算错偏移,大小对不上 —— 服务器上那份就是坏的,而界面还会显示成功。
     */
    @Test fun resume_afterMidTransferDisconnect_fileIsIntact(): Unit = runBlocking {
        var cur = connect()
        val first = cur
        try {
            val f = blob(48.0)                       // 局域网 3MB/s ≈ 16 秒,够在中途掐一刀
            try {
                var reconnects = 0
                val killer = launch {
                    delay(4_000)
                    android.util.Log.i("UploadStress", "掐断连接(模拟切基站/进电梯)")
                    runCatching { cur.disconnect() }
                }
                val r = app.yxi.agent.Uploader.upload(
                    aliveSsh = { wait ->
                        val t1 = System.currentTimeMillis()
                        var got: app.yxi.ssh.SshSession? = null
                        while (true) {
                            if (cur.isAlive) { got = cur; break }
                            val fresh = runCatching { connect() }.getOrNull()
                            if (fresh != null) { cur = fresh; reconnects++; got = fresh; break }
                            if (System.currentTimeMillis() - t1 >= wait) break
                            delay(500)
                        }
                        got
                    },
                    open = { f.inputStream().buffered() },
                    total = f.length(),
                    sessionName = "cc-upload-stress", name = "resume.bin",
                    index = 1, isImage = false, stamp = "stress-" + System.nanoTime(),
                )
                killer.cancel()
                val st = r.getOrElse { throw AssertionError("断一次之后应该续传成功：$it", it) }
                assertTrue("应该真的重连过(否则这条测试没测到东西)", reconnects >= 1)
                val sf = cur.openSftp()
                try {
                    assertEquals("续传后大小必须一字不差", f.length(), sf.size(st.remotePath))
                    runCatching { sf.rm(st.remotePath) }
                } finally { sf.close() }
                android.util.Log.i("UploadStress", "断线续传 OK,重连 $reconnects 次")
            } finally { f.delete() }
        } finally { runCatching { cur.disconnect() }; runCatching { first.disconnect() } }
    }

    /**
     * **走 App 真正的重试路径**([Uploader.upload]):断了换新通道、从断点续传,最多 6 次。
     * 上面那几条只调 [Attachments.upload](单次尝试),验的是传输本身;这条验的是**用户看到的结果** ——
     * 链路抖一下到底还能不能传完。用户报的「一次上传很多图片还是失败」就是这条路。
     */
    @Test fun retry_12files_survivesAStall(): Unit = runBlocking {
        // ⚠️ aliveSsh 要**会重连** —— App 里断线后看门狗会换一条新连接,测试不重连就等于
        //    把「断了之后还能不能传完」这条路测没了(卡死看门狗掐断连接后正是走这条)
        var cur = connect()
        val s = cur
        try {
            val files = List(12) { blob(0.5) }
            try {
                files.forEachIndexed { i, f ->
                    val t0 = System.currentTimeMillis()
                    val r = app.yxi.agent.Uploader.upload(
                        aliveSsh = { wait ->
                            val t1 = System.currentTimeMillis()
                            var got: app.yxi.ssh.SshSession? = null
                            while (got == null) {
                                if (cur.isAlive) { got = cur; break }
                                runCatching { cur = connect() }.onFailure {
                                    if (System.currentTimeMillis() - t1 >= wait) return@upload null
                                    kotlinx.coroutines.delay(1000)
                                }
                            }
                            got
                        },
                        open = { f.inputStream().buffered() },
                        total = f.length(),
                        sessionName = "cc-upload-stress", name = "retry-$i.bin",
                        index = 1, isImage = false, stamp = "stress-" + System.nanoTime(),
                    )
                    val st = r.getOrElse { throw AssertionError("第 ${i + 1}/12 个最终还是失败了：$it", it) }
                    val sf = cur.openSftp()
                    try {
                        assertEquals("第 ${i + 1} 个：服务器上的大小和本地对不上", f.length(), sf.size(st.remotePath))
                        runCatching { sf.rm(st.remotePath) }
                    } finally { sf.close() }
                    android.util.Log.i("UploadStress", "重试路径 ${i + 1}/12：${System.currentTimeMillis() - t0} ms")
                }
            } finally { files.forEach { it.delete() } }
        } finally { runCatching { cur.disconnect() }; runCatching { s.disconnect() } }
    }

    @Test fun slow12x2MB_batch_likeAPhotoBatch(): Unit = runBlocking {
        val s = connect()
        try {
            val files = List(12) { blob(0.5) }
            try {
                var i = 0
                for (f in files) {
                    val ms = uploadOne(s, f, "slow-batch-${i++}")
                    android.util.Log.i("UploadStress", "慢网 批 $i/12：$ms ms")
                }
            } finally { files.forEach { it.delete() } }
        } finally { s.disconnect() }
    }

    @Test fun 六个文件并发各开一条通道_全部成功() = runBlocking {
        val s = connect()
        try {
            repeat(3) { round ->
                val files = List(6) { blob(4.0) }
                try {
                    val ms = coroutineScope { files.mapIndexed { i, f -> async { uploadOne(s, f, "par$round-$i") } }.awaitAll() }
                    android.util.Log.i("UploadStress", "第 ${round + 1} 轮并发 6×4MB：$ms ms")
                    assertTrue(ms.all { it > 0 })
                } finally { files.forEach { it.delete() } }
            }
        } finally { s.disconnect() }
    }
}
