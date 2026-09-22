package app.yxi.desktop

import androidx.compose.runtime.*
import app.yxi.ssh.Shell
import app.yxi.ssh.SshSession
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Socket
import java.net.InetSocketAddress
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class DeviceLink(val hostKey: String, val reversePort: Int, val localPort: Int,
    val username: String = "", val hostPublicKey: String = "", val ready: Boolean = false)
internal data class TailPeer(val id: String, val name: String, val addresses: String, val online: Boolean, val active: Boolean, val route: String)
internal object LinkProtocol {
    fun peers(raw: String): List<TailPeer> {
        val json = JSONObject(raw)
        check(json.optString("BackendState") == "Running") { "这台服务器的 Tailscale 尚未连接" }
        val peers = json.optJSONObject("Peer") ?: return emptyList()
        return peers.keySet().map { id -> val p = peers.getJSONObject(id)
            TailPeer(id, p.optString("HostName").ifBlank { p.optString("DNSName") },
                p.optJSONArray("TailscaleIPs")?.let { a -> (0 until a.length()).joinToString(" · ") { a.getString(it) } }.orEmpty(),
                p.optBoolean("Online"), p.optBoolean("Active"), when {
                    p.optString("CurAddr").isNotBlank() -> "直连"; p.optString("Relay").isNotBlank() -> "中继"; else -> "未建立链路"
                })
        }.sortedWith(compareByDescending<TailPeer> { it.active }.thenByDescending { it.online }.thenBy { it.name })
    }
    fun allocate(key: String, existing: List<DeviceLink>): DeviceLink {
        existing.firstOrNull { it.hostKey == key }?.let { return it }
        val n = (0..50000).first { offset -> existing.none { it.reversePort == 2222 + offset || it.localPort == 5901 + offset } }
        return DeviceLink(key, 2222 + n, 5901 + n)
    }
    fun remoteCommand(request: JSONObject): String {
        val code = LinkProtocol::class.java.getResource("/app/yxi/desktop/link-remote.py")!!.readText()
        val payload = Base64.getEncoder().encodeToString(request.toString().toByteArray())
        return "python3 -c ${Shell.q(code)} ${Shell.q(payload)}"
    }
    fun result(raw: String): JSONObject {
        val text = raw.lineSequence().lastOrNull { it.startsWith("__YXI_LINK__:") } ?: error("服务器未返回连接配置结果")
        val value = JSONObject(text.removePrefix("__YXI_LINK__:"))
        check(value.optBoolean("ok")) { value.optString("error", "配置未完成") }; return value
    }
}

/** Direct child processes only. No visible terminal, no shell interpolation of user input. */
internal object LocalProcess {
    suspend fun run(args: List<String>, timeoutSeconds: Long = 30): String = withContext(Dispatchers.IO) {
        val output = File.createTempFile("yxi-local-", ".log")
        val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output).start()
        try {
            process.outputStream.close()
            check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "本机命令超时，请检查服务和权限" }
            val text = output.inputStream().use { it.readNBytes(1024 * 1024).toString(Charsets.UTF_8) }
            check(process.exitValue() == 0) { text.takeLast(1200).ifBlank { "本机命令失败 (${process.exitValue()})" } }
            text
        } finally { if (process.isAlive) process.destroyForcibly(); output.delete() }
    }
    fun powershell(code: String) = listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand",
        Base64.getEncoder().encodeToString(("\$ErrorActionPreference = 'Stop'; \$ProgressPreference = 'SilentlyContinue'; [Console]::OutputEncoding = New-Object Text.UTF8Encoding(\$false); " + code).toByteArray(Charsets.UTF_16LE)))
    fun psQuote(value: String) = "'" + value.replace("'", "''") + "'"
}

internal class DeviceLinks(private val directory: File = File(Store.dir, "connections")) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val localSetupLock = Mutex()
    private val disk = DurableFile(File(directory, "links.json")) { decode(it) }
    private var device = UUID.randomUUID().toString().replace("-", "")
    var records by mutableStateOf<List<DeviceLink>>(emptyList()); private set
    val status = mutableStateMapOf<String, String>()
    val busy = mutableStateListOf<String>()
    private val transports = mutableMapOf<String, Pair<SshSession, AutoCloseable>>()
    private var writable = true
    init {
        try { disk.read()?.let { text -> val json = JSONObject(text); device = json.getString("device"); records = decode(text) } }
        catch (_: Exception) { writable = false; status["storage"] = "连接记录损坏，已保留文件，请先恢复配置" }
        scope.launch { while (isActive) { delay(2000); transports.toMap().forEach { (key, value) ->
            if (!value.first.isConnected) { disconnect(key); status[key] = "连接已断开，可重新连接" }
        } } }
    }
    fun connected(conn: Conn) = transports[projectKey(conn.host, "/")]?.first?.isConnected == true
    fun record(conn: Conn) = records.firstOrNull { it.hostKey == projectKey(conn.host, "/") }
    private fun save(record: DeviceLink) {
        check(writable) { "连接配置无法保存" }
        val next = records.filterNot { it.hostKey == record.hostKey } + record
        disk.write(JSONObject().put("version", 1).put("device", device).put("records", JSONArray(next.map {
            JSONObject().put("host", it.hostKey).put("reverse", it.reversePort).put("local", it.localPort)
                .put("username", it.username).put("hostPublicKey", it.hostPublicKey).put("ready", it.ready)
        })).toString())
        records = next
    }
    fun disconnect(key: String) {
        transports.remove(key)?.let { (ssh, lease) -> runCatching { lease.close() }; ssh.disconnect() }
        status[key] = "已断开"
    }
    fun disconnect(conn: Conn) = disconnect(projectKey(conn.host, "/"))
    fun deploy(conn: Conn) = work(conn) { key ->
        check(conn.status == Conn.Status.Connected) { "请先连接此服务器" }
        status[key] = "准备双方公钥…"
        val (_, publicKey) = withContext(Dispatchers.IO) { DesktopKey.ensure() }
        var record = LinkProtocol.allocate(key, records)
        save(record)
        val prepared = LinkProtocol.result(conn.ssh.exec(LinkProtocol.remoteCommand(JSONObject().put("action", "prepare").put("device", device).put("publicKey", publicKey))))
        val local = localSetupLock.withLock { setupLocal(prepared.getString("publicKey"), key) }
        record = record.copy(username = local.getString("username"), hostPublicKey = local.getString("hostKey"), ready = true)
        save(record)
        open(conn, record)
    }
    fun connect(conn: Conn) = work(conn) { _ -> open(conn, record(conn)?.takeIf { it.ready } ?: error("请先完成一键配置")) }
    private fun work(conn: Conn, block: suspend (String) -> Unit) {
        val key = projectKey(conn.host, "/")
        if (key in busy) return
        busy.add(key)
        scope.launch {
            try { check(writable); block(key) }
            catch (e: Exception) { status[key] = e.message ?: "连接操作失败" }
            finally { busy.remove(key) }
        }
    }
    private suspend fun open(conn: Conn, saved: DeviceLink) {
        val key = saved.hostKey
        if (transports.containsKey(key)) disconnect(key)
        status[key] = "建立后台隧道…"
        val ssh = conn.ssh.independentLink(withContext(Dispatchers.IO) { DesktopKey.privFile.readText() })
        var lease: AutoCloseable? = null
        try {
            ssh.connect()
            // Bind requests, rather than a racy port scan, decide whether each port is available.
            var record = saved
            var last: Exception? = null
            repeat(20) { attempt ->
                if (lease == null) {
                    val offset = attempt
                    val candidate = saved.copy(reversePort = saved.reversePort + offset, localPort = saved.localPort + offset)
                    if (records.none { it.hostKey != key && (it.reversePort == candidate.reversePort || it.localPort == candidate.localPort) }) {
                        try { lease = withContext(Dispatchers.IO) { ssh.forwardLink(candidate.reversePort, candidate.localPort) }; record = candidate }
                        catch (e: Exception) { last = e }
                    }
                }
            }
            check(lease != null) { "端口转发未建立：${last?.message ?: "端口被占用"}" }
            LinkProtocol.result(ssh.exec(LinkProtocol.remoteCommand(JSONObject().put("action", "verify").put("device", device)
                .put("reversePort", record.reversePort).put("username", record.username).put("hostKey", record.hostPublicKey))))
            save(record)
            transports[key] = ssh to lease!!
            status[key] = "已连接 · 服务器访问本机 SSH 已验证"
        } catch (e: Exception) { runCatching { lease?.close() }; ssh.disconnect(); throw e }
    }
    private suspend fun setupLocal(serverKey: String, key: String): JSONObject {
        if (System.getProperty("os.name").startsWith("Windows")) {
            val staging = File(directory, "setup-${UUID.randomUUID()}").apply { mkdirs() }
            val script = File(staging, "setup.ps1").apply { writeText(DeviceLinks::class.java.getResource("/app/yxi/desktop/link-local.ps1")!!.readText()) }
            val inspect = LocalProcess.run(LocalProcess.powershell("& ${LocalProcess.psQuote(script.path)} -Mode inspect"))
            val req = JSONObject(inspect.trim().removePrefix("\uFEFF")).put("publicKey", serverKey)
            val request = File(staging, "request.json").apply { writeText(req.toString()) }
            status[key] = "等待 Windows 管理员授权并配置 OpenSSH…"
            val inner = "& ${LocalProcess.psQuote(script.path)} -Mode setup -RequestPath ${LocalProcess.psQuote(request.path)}; exit \$LASTEXITCODE"
            val encoded = Base64.getEncoder().encodeToString(inner.toByteArray(Charsets.UTF_16LE))
            val launcher = "\$p = Start-Process powershell.exe -Verb RunAs -WindowStyle Hidden -Wait -PassThru -ArgumentList '-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $encoded'; exit \$p.ExitCode"
            try { LocalProcess.run(LocalProcess.powershell(launcher), 600) }
            catch (e: Exception) { if (!File(staging, "result.json").exists()) throw e }
            val result = JSONObject(File(staging, "result.json").readText())
            check(result.optBoolean("ok")) { result.optString("error", "本机配置失败") }
            return result
        }
        check(System.getProperty("os.name").contains("Mac")) { "一键本机配置目前支持 Windows 和 macOS" }
        withContext(Dispatchers.IO) { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 22), 2000) } }
        val sshDir = File(System.getProperty("user.home"), ".ssh")
        val keys = File(sshDir, "authorized_keys")
        check(!java.nio.file.Files.isSymbolicLink(sshDir.toPath()) && !java.nio.file.Files.isSymbolicLink(keys.toPath()))
        sshDir.mkdirs()
        val old = if (keys.exists()) keys.readText() else ""
        if (old.lineSequence().none { it == serverKey || it.startsWith("$serverKey ") }) keys.appendText((if (old.isNotEmpty() && !old.endsWith('\n')) "\n" else "") + "$serverKey yxi-link\n")
        LocalProcess.run(listOf("chmod", "700", sshDir.path)); LocalProcess.run(listOf("chmod", "600", keys.path))
        val hostKey = File("/etc/ssh/ssh_host_ed25519_key.pub").readText().trim()
        return JSONObject().put("username", System.getProperty("user.name")).put("hostKey", hostKey)
    }
    override fun close() { transports.keys.toList().forEach(::disconnect); scope.cancel() }
    companion object {
        private fun decode(raw: String): List<DeviceLink> {
            val value = JSONObject(raw); require(value.getInt("version") == 1 && value.getString("device").matches(Regex("[a-f0-9]{32}")))
            val entries = value.getJSONArray("records")
            return (0 until entries.length()).map { val p = entries.getJSONObject(it)
                DeviceLink(p.getString("host"), p.getInt("reverse"), p.getInt("local"), p.getString("username"), p.getString("hostPublicKey"), p.getBoolean("ready")).also {
                    require(it.reversePort in 1024..65535 && it.localPort in 1024..65535)
                }
            }.also { require(it.map(DeviceLink::hostKey).distinct().size == it.size) }
        }
    }
}
