package app.yxi

import androidx.test.platform.app.InstrumentationRegistry
import app.yxi.agent.Attachments
import app.yxi.ssh.HostStore
import app.yxi.ssh.KeyManager
import app.yxi.ssh.SshSession
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
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
        val store = HostStore(ctx); val keys = KeyManager(ctx)
        // 没配主机就自己种一台指向宿主机（模拟器里 10.0.2.2 = 跑模拟器的那台）。
        // 公钥打到 logcat 里 —— 第一次跑要把它贴进服务器的 authorized_keys。
        val h = store.hosts.value.firstOrNull()
            ?: app.yxi.ssh.Host(id = "stress-dev", alias = "dev", hostname = "10.0.2.2", username = "root").also { store.upsert(it) }
        android.util.Log.i("UploadStress", "PUBKEY " + keys.publicKeyLine())
        val cfg = store.configFor(h, keys) ?: error("主机 ${h.alias} 没有可用的认证方式")
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

    @Test fun slow12x2MB_batch_likeAPhotoBatch(): Unit = runBlocking {
        val s = connect()
        try {
            val files = List(12) { blob(2.0) }
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
