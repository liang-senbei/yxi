package app.yxi.ssh

import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
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
class SshSession(private val cfg: HostConfig) {

    companion object {
        /**
         * Android 的 JCA **不提供 `Ed25519` 签名**（jsch 日志原文：
         * `Signature algorithms unavailable for non-agent identities = [ssh-ed25519, ssh-ed448]`）。
         * 注册 BouncyCastle 补上——它正好用 `Ed25519` 这个算法名，jsch 查得到。
         * 不做这一步就只能退到 ECDSA/RSA 密钥，而 ed25519 才是现在的默认。
         */
        private fun registerBouncyCastle() {
            if (java.security.Security.getProvider("BC") != null) return
            // 安卓自带一个阉割版的 BC，要先摘掉再插完整版，否则算法查找会命中旧的
            java.security.Security.removeProvider("BC")
            java.security.Security.insertProviderAt(
                org.bouncycastle.jce.provider.BouncyCastleProvider(), 1
            )
        }
    }

    private val jsch = JSch()

    init {
        registerBouncyCastle()
        // 把 jsch 自己的日志接到 logcat —— 认证失败时光看异常消息什么也看不出来
        JSch.setLogger(object : com.jcraft.jsch.Logger {
            override fun isEnabled(level: Int) = true
            override fun log(level: Int, message: String) { Log.i("YxiSSH", "[$level] $message") }
        })
    }
    private var session: Session? = null

    class Shell(
        private val channel: ChannelShell,
        val output: InputStream,
        private val input: OutputStream,
    ) {
        // ⚠️ 必须 suspend + IO：安卓禁止主线程网络操作，
        // 直接在 Compose 的 LaunchedEffect 里调会抛 NetworkOnMainThreadException
        suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
            input.write(bytes); input.flush()
        }
        suspend fun write(text: String) = write(text.toByteArray())
        /** 横竖屏切换、软键盘弹出都要重发，不然远端还按老尺寸折行 */
        suspend fun resize(cols: Int, rows: Int, widthPx: Int = 0, heightPx: Int = 0) =
            withContext(Dispatchers.IO) { channel.setPtySize(cols, rows, widthPx, heightPx) }
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

        // ⚠️ G2 的临时口子：先不校验主机指纹，把 SSH 链路本身跑通。
        // G3 必须换成真正的 known_hosts 校验 —— 首次显式确认、之后变了就拒。
        // 图省事 accept-any 等于把 SSH 的中间人防护关掉（PRD §8 第 2 条）。
        s.setConfig("StrictHostKeyChecking", "no")
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

    fun disconnect() { runCatching { session?.disconnect() }; session = null }
    val isConnected: Boolean get() = session?.isConnected == true
}
