package app.yxi.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.yxi.agent.Session
import app.yxi.agent.SessionProbe
import app.yxi.ssh.HostConfig
import app.yxi.ssh.HostKeys
import app.yxi.ssh.SshSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 桌面版存的一台主机。私钥存**路径**（用户自己的 ~/.ssh/id_ed25519 之类），密码明文存文件（ponytail：先这样，之后接 DPAPI）。 */
data class Host(
    val id: String,
    val alias: String,
    val hostname: String,
    val port: Int = 22,
    val username: String = "root",
    val keyPath: String = "",
    val password: String = "",
) {
    fun toConfig(): HostConfig = HostConfig(
        alias = alias, hostname = hostname, port = port, username = username,
        auth = if (keyPath.isNotBlank()) HostConfig.Auth.PrivateKey(File(keyPath).readText()) else HostConfig.Auth.Password(password),
    )
}

/** 本地存储：Windows `%APPDATA%\Yxi`，其它 `~/.config/yxi`。hosts.json + known_hosts。 */
object Store {
    val dir: File = run {
        val appData = System.getenv("APPDATA")
        val base = if (appData != null && System.getProperty("os.name").startsWith("Windows")) File(appData, "Yxi") else File(System.getProperty("user.home"), ".config/yxi")
        base.apply { mkdirs() }
    }
    private val hostsFile = File(dir, "hosts.json")
    val knownHostsFile = File(dir, "known_hosts")

    fun hosts(): List<Host> = runCatching {
        JSONArray(hostsFile.readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it).toHost() } }
    }.getOrDefault(emptyList())

    fun save(hosts: List<Host>) {
        hostsFile.writeText(JSONArray(hosts.map { it.toJson() }).toString(2))
        runCatching { hostsFile.setReadable(false, false); hostsFile.setReadable(true, true) }
    }

    private fun JSONObject.toHost() = Host(
        id = getString("id"), alias = optString("alias"), hostname = getString("hostname"), port = optInt("port", 22),
        username = optString("username", "root"), keyPath = optString("keyPath"), password = optString("password"),
    )
    private fun Host.toJson() = JSONObject().put("id", id).put("alias", alias).put("hostname", hostname).put("port", port)
        .put("username", username).put("keyPath", keyPath).put("password", password)
}

/**
 * 一台主机的活连接：一条 [SshSession] + 上面的会话列表。
 * 对话 / 终端面板都从这里拿 `ssh`，别自己再开连接（jsch 一条连接多通道，开通道已经在 SshSession 里排队）。
 */
class Conn(val host: Host, hostKeys: HostKeys) {
    enum class Status { Idle, Connecting, Connected, Failed }
    val ssh = SshSession(host.toConfig(), hostKeys, aliveIntervalMs = 10_000)
    var status by mutableStateOf(Status.Idle)
    var error by mutableStateOf("")
    var sessions by mutableStateOf<List<Session>>(emptyList())

    suspend fun connect() {
        status = Status.Connecting
        runCatching { ssh.connect() }
            .onSuccess { status = Status.Connected; refresh() }
            .onFailure { status = Status.Failed; error = it.message ?: it.toString() }
    }

    suspend fun refresh() {
        if (!ssh.isConnected) return
        runCatching { sessions = SessionProbe.snapshot(ssh) }
    }

    fun close() { ssh.disconnect(); status = Status.Idle }
}
