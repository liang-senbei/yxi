package app.yxi.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import app.yxi.agent.Session
import app.yxi.agent.SessionProbe
import app.yxi.agent.SessionState
import app.yxi.ssh.HostConfig
import app.yxi.ssh.HostKeys
import app.yxi.ssh.SshSession
import app.yxi.ssh.catching
import com.jcraft.jsch.JSchChangedHostKeyException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.ui.unit.dp
import java.io.File

/** 桌面主机记录保存私钥引用；Windows持久化由HostConfigFile使用DPAPI保护。 */
data class Host(
    val id: String,
    val alias: String,
    val hostname: String,
    val port: Int = 22,
    val username: String = "root",
    val keyPath: String = "",
    val password: String = "",
    /** 连接颜色（Codex 的「连接颜色…」）：`#RRGGBB`；空 = 按侧栏顺序从 [HostColors] 里轮着分 */
    val color: String = "",
    val region: String = "",
) {
    val label get() = alias.ifBlank { hostname }
    fun toConfig(): HostConfig = HostConfig(
        alias = alias, hostname = hostname, port = port, username = username,
        auth = if (keyPath.isNotBlank()) HostConfig.Auth.PrivateKey(File(keyPath).readText()) else HostConfig.Auth.Password(password),
    )
}

/** 6 个柔和色，浅深主题都看得清 */
val HostColors = listOf(Color(0xFF7C9CBF), Color(0xFF8FBF9F), Color(0xFFD9A066), Color(0xFFC48FB8), Color(0xFFBFB07C), Color(0xFF7FB8C4))

/** 这台主机的颜色：自己选过的优先，没选按它在侧栏的序号轮着用 */
fun Host.tint(index: Int): Color =
    color.removePrefix("#").toLongOrNull(16)?.let { Color(0xFF000000L or it) } ?: HostColors[index % HostColors.size]

/** 本地存储：Windows `%LOCALAPPDATA%\Yxi`，其它 `~/.config/yxi`。hosts.json + known_hosts。 */
object Store {
    var warning by mutableStateOf("")
        private set
    val dir: File = run {
        val win = System.getProperty("os.name").startsWith("Windows")
        // ⚠️⚠️ **不能放 %APPDATA%**（审查 P2）：那是**漫游**目录 —— 域账户登录别的机器、
        //    或者 OneDrive 的「已知文件夹」备份开着的时候，它会被**同步到别处去**。
        //    旧版 hosts.json 含明文密码；Windows 当前通过 HostConfigFile 迁移为用户级 DPAPI 保护。
        //    %LOCALAPPDATA% 不漫游；单实例锁（Shell.kt）本来用的就是它，顺带统一到一个目录。
        val local = System.getenv("LOCALAPPDATA")?.takeIf { win }?.let { File(it, "Yxi") }
        val base = local ?: File(System.getProperty("user.home"), ".config/yxi")
        base.apply { mkdirs() }
        // 1.0.0 的 MSI 装的那版写在 %APPDATA%\Yxi —— 搬过来一次，别让人重新加一遍主机。
        if (local != null) migrateRoaming(File(System.getenv("APPDATA").orEmpty(), "Yxi"), local)
        base
    }

    /**
     * 主机记录先在本地保护并验证，再清空旧明文；其他文件验证后移动。
     * ⚠️ 删源不是洁癖：留着的那份 hosts.json 里有明文密码，还在继续跟着漫游同步 —— 不删等于没修。
     * 目标已存在时仅在内容一致且校验通过后清理源；不同内容保留双方。
     */
    private fun migrateRoaming(from: File, to: File) = runCatching {
        if (!from.isDirectory || from.canonicalFile == to.canonicalFile) return@runCatching
        val hostImport = HostConfigFile(File(to, "hosts.json"), WindowsCredentialProtector.forPlatform("Yxi/host-credentials/v1"), ::validateHosts)
        hostImport.importLegacy(File(from, "hosts.json"))
        if (hostImport.recovered) warning = "旧服务器配置已从备份恢复，请核对服务器列表。"
        listOf("known_hosts", "prefs.json", "window.json").forEach { name ->
            val src = File(from, name)
            if (!src.isFile) return@forEach
            val dst = File(to, name)
            DurableFile.migrate(src, dst) { text ->
                when (name) {
                    "prefs.json", "window.json" -> JSONObject(text)
                }
            }
        }
        runCatching { from.delete() }   // 空了才删得掉，没空就留着，无所谓
    }.onFailure { warning = "旧配置迁移未完成：${it.message}" }
    private val hostsFile = File(dir, "hosts.json")
    private val hostData = HostConfigFile(hostsFile, WindowsCredentialProtector.forPlatform("Yxi/host-credentials/v1"), ::validateHosts)
    private fun validateHosts(text: String) {
        val a = JSONArray(text)
        val ids = mutableSetOf<String>()
        for (i in 0 until a.length()) {
            val host = a.getJSONObject(i).toHost()
            require(host.id.isNotBlank() && ids.add(host.id)) { "服务器 ID 为空或重复" }
            require(host.hostname.isNotBlank() && host.port in 1..65535) { "服务器地址或端口无效" }
        }
    }
    val knownHostsFile = File(dir, "known_hosts")
    internal fun hostRecoveryNeeded() = runCatching { hostData.needsRecovery() }.getOrDefault(false)
    internal fun hostRecoveryCopies() = hostData.recoveryCopies()
    internal fun recoverHosts(copy: HostRecoveryCopy): List<Host> {
        val restored = JSONArray(hostData.restoreMissing(copy.name, copy.fingerprint))
        warning = "已从所选副本恢复服务器，请核对列表后手动连接。"
        return (0 until restored.length()).map { restored.getJSONObject(it).toHost() }
    }

    fun hosts(): List<Host> = runCatching {
        val text = hostData.read() ?: return@runCatching emptyList()
        if (hostData.recovered) warning = "服务器配置已从备份恢复；损坏的原文件已保留。"
        JSONArray(text).let { a -> (0 until a.length()).map { a.getJSONObject(it).toHost() } }
    }.onFailure { warning = "无法读取服务器配置，原文件已保留：${it.message}" }.getOrDefault(emptyList())

    fun save(hosts: List<Host>) {
        hostData.write(JSONArray(hosts.map { it.toJson() }).toString(2))
        runCatching { hostsFile.setReadable(false, false); hostsFile.setReadable(true, true) }
    }

    /** 简单偏好（主题 / 通知档 / 关窗行为…），prefs.json；Compose 里读要能重组，所以放在 state 里。 */
    private val prefsFile = File(dir, "prefs.json")
    private val prefData = DurableFile(prefsFile) { JSONObject(it) }
    private val prefs = androidx.compose.runtime.mutableStateMapOf<String, String>().apply {
        runCatching { JSONObject(prefData.read() ?: "{}").let { j -> j.keys().forEach { put(it, j.getString(it)) } } }
            .onFailure { warning = "无法读取桌面偏好：${it.message}" }
    }
    fun pref(key: String, default: String): String = prefs[key] ?: default
    fun setPref(key: String, value: String) {
        runCatching { prefData.write(JSONObject(prefs.toMap() + (key to value)).toString()); prefs[key] = value }
            .onFailure { warning = "偏好未保存：${it.message}" }
    }

    /** 窗口大小 / 位置：关窗时存，下次开窗恢复（Claude Desktop 那样记住上次的样子）。 */
    private val windowFile = File(dir, "window.json")
    fun loadWindow(st: androidx.compose.ui.window.WindowState) = runCatching {
        val j = JSONObject(windowFile.readText())
        st.size = androidx.compose.ui.unit.DpSize(j.getDouble("w").dp, j.getDouble("h").dp)
        if (j.has("x")) st.position = androidx.compose.ui.window.WindowPosition(j.getDouble("x").dp, j.getDouble("y").dp)
    }.getOrNull()
    fun saveWindow(st: androidx.compose.ui.window.WindowState) = runCatching {
        val p = st.position
        val j = JSONObject().put("w", st.size.width.value.toDouble()).put("h", st.size.height.value.toDouble())
        if (p is androidx.compose.ui.window.WindowPosition.Absolute) j.put("x", p.x.value.toDouble()).put("y", p.y.value.toDouble())
        windowFile.writeText(j.toString())
    }.getOrNull()

    private fun JSONObject.toHost() = Host(
        id = getString("id"), alias = optString("alias"), hostname = getString("hostname"), port = optInt("port", 22),
        username = optString("username", "root"), keyPath = optString("keyPath"), password = optString("password"), color = optString("color"), region = optString("region"),
    )
    private fun Host.toJson() = JSONObject().put("id", id).put("alias", alias).put("hostname", hostname).put("port", port)
        .put("username", username).put("keyPath", keyPath).put("password", password).put("color", color).put("region", region)
}

/**
 * 一台主机的活连接：一条 [SshSession] + 上面的会话列表 + 常驻的刷新/重连循环。
 * 对话 / 终端面板都从这里拿 `ssh`，别自己再开连接（jsch 一条连接多通道，开通道已经在 SshSession 里排队）。
 * [start] 之后它自己每 5 秒刷一次会话；掉线就退避重连（1s→2s→4s→…≤10s），直到 [close]。
 */
class Conn(val host: Host, hostKeys: HostKeys) {
    enum class Status(val label: String) { Idle(""), Connecting("正在连接"), Connected("已连接"), Reconnecting("正在重新连接…"), Failed("连接失败") }
    val ssh = SshSession(host.toConfig(), hostKeys, aliveIntervalMs = 10_000)
    var status by mutableStateOf(Status.Idle)
    var error by mutableStateOf("")
    /** 指纹变了：不重连，等用户在侧栏点「我确认过了，删除旧指纹」 */
    var keyChanged by mutableStateOf(false)
    var sessions by mutableStateOf<List<Session>>(emptyList())
    // 自己一个 scope，不挂在 Composable 的 LaunchedEffect 上：侧栏收起（Ctrl+B）时 Sidebar 整个不在组合里，
    // 挂那儿的循环会跟着停 —— 收着侧栏就既不刷新也不重连。Swing 线程上写状态，跟界面同一条线。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private var completionCursor: Double? = null

    // finally 里再断一次：close() 撞上正在握手的 connect() 时，jsch 会在 IO 线程上把握手做完再把 session 装上，
    // 那条连接就没人管了（心跳线程一直活到进程退出）。协程被取消后从这里补一刀。
    fun start() { status = Status.Connecting; scope.launch { try { loop() } finally { ssh.disconnect() } } }

    private suspend fun loop() {
        var backoff = 1_000L
        while (true) {
            if (!ssh.isConnected) {
                if (status == Status.Connected) status = Status.Reconnecting
                ssh.disconnect()   // 清掉死掉的 jsch session；connect() 每次都新建一条
                val e = catching { ssh.connect() }.exceptionOrNull()
                if (e != null) {
                    keyChanged = e is JSchChangedHostKeyException
                    error = explain(e, keyChanged)
                    // 首次就连不上 / 指纹变了 / 认证被拒：重试没意义（反复认证失败还会被 fail2ban 封 IP），停下来等人处理
                    if (status != Status.Reconnecting || keyChanged || "Auth" in e.message.orEmpty()) { status = Status.Failed; return }
                    delay(backoff); backoff = minOf(backoff * 2, 10_000); continue
                }
                backoff = 1_000; error = ""; status = Status.Connected
            }
            refresh()
            // 每秒看一眼还活着没（jsch 心跳判死后 isConnected 立刻变 false），断了马上进重连，不用等满 5 秒
            for (i in 1..5) { delay(1_000); if (!ssh.isConnected) break }
        }
    }

    /** 抓一次会话列表。抓不全 = 连接八成半死（exec 在 core 里不抛只返回空），掐掉让 [loop] 走重连。 */
    suspend fun refresh() {
        if (!ssh.isConnected) return
        val snapshot = catching { SessionProbe.snapshotFull(ssh) }.getOrElse { ssh.disconnect(); return }
        val fresh = snapshot.sessions
        val cursor = completionCursor
        if (cursor != null) {
            snapshot.completions.filter { it.timestamp > cursor }.sortedBy { it.timestamp }.forEach { event ->
                val task = fresh.firstOrNull { it.name == event.session } ?: return@forEach
                val createdAt = task.runtimeId.substringAfterLast(':').toDoubleOrNull() ?: return@forEach
                if (event.timestamp < createdAt) return@forEach
                val preview = event.preview.replace(Regex("\\s+"), " ").trim().take(100)
                Notify.notify("本轮处理结束", "任务: ${task.short}" + if (preview.isBlank()) "" else " · $preview", host.id, task.name)
            }
        }
        // Establish an initial baseline silently; reconnects retain this connection's cursor.
        completionCursor = maxOf(cursor ?: 0.0, snapshot.completions.maxOfOrNull { it.timestamp } ?: 0.0)
        // 刚变成「等你」的会话发一条桌面通知，只在变化那一刻发一次。看的是「之前不是等你」而不是「之前在运行」：
        // 5 秒一轮询，发完话它 3 秒内就来问权限的话，中间那个「运行中」根本抓不到。
        val was = sessions.associateBy { it.name }
        for (s in fresh) {
            if (s.state == SessionState.NeedsYou && was[s.name]?.let { it.state != SessionState.NeedsYou } == true)
                // 措辞照 ZCode（老板 09-12 截图）：标题=徽标文案（等待批准/需要用户输入），正文带任务名，可点跳会话。
                // detail 可能带换行/emoji，压成一行再截（take 别把代理对截一半成乱码）
                Notify.notify(s.badge().label, "任务: ${s.short}" +
                    s.detail.replace(Regex("\\s+"), " ").trim().take(60).let { d -> if (d.isBlank()) "" else " · $d" }, host.id, s.name)
        }
        sessions = fresh
    }

    fun close() { scope.cancel(); ssh.disconnect(); status = Status.Idle }
}

/** 把 jsch 的报错翻成人话。指纹那条照 Claude Desktop 的措辞（design/desktop-reference.md §4「必须有」6）。 */
private fun explain(e: Throwable, keyChanged: Boolean): String {
    val m = e.message.orEmpty()
    return when {
        keyChanged -> "主机的 SSH 密钥变了，已拒绝连接。如果这台机器没有重装或换过密钥，不要信任新密钥。"
        "reject HostKey" in m -> "你取消了指纹确认，所以没连。"
        "Auth" in m -> "认证被拒：密码不对，或服务器的 authorized_keys 里没有这把公钥。"
        else -> "连不上：${m.ifBlank { e.toString() }}"
    }
}
