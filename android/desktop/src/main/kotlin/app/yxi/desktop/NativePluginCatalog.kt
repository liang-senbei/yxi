package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A separate home-scoped RPC connection: browsing never starts or changes an Agent thread. */
internal class PluginRpc private constructor(output: InputStream,
    private val write: suspend (String) -> Boolean, private val stop: () -> Unit) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    init {
        scope.launch {
            try {
                output.bufferedReader().use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        // Curated catalogs can exceed 10 MB; keep the bound separate from conversation events.
                        check(line.length <= 32 * 1024 * 1024) { "插件目录过大" }
                        val value = JSONObject(line)
                        val id = value.opt("id") as? String ?: continue
                        pending.remove(id)?.complete(value)
                    }
                }
            } catch (_: Exception) { /* Fail pending requests below without leaking runtime diagnostics. */ }
            finally { closed.set(true); pending.values.forEach { it.completeExceptionally(IllegalStateException("Codex 未启动或连接已关闭，请检查目标机器的安装和配置目录")) }; pending.clear() }
        }
    }
    suspend fun request(method: String, params: JSONObject = JSONObject()): JSONObject {
        val id = UUID.randomUUID().toString()
        val response = CompletableDeferred<JSONObject>()
        pending[id] = response
        try {
            check(!closed.get()) { "Codex 未启动或连接已关闭，请检查目标机器的安装和配置目录" }
            return withTimeout(60000) {
                check(write(JSONObject().put("id", id).put("method", method).put("params", params).toString() + "\n")) { "插件请求未能写入" }
                val value = response.await()
                check(!value.has("error")) { "Codex 未完成插件请求，请检查此机器的登录、版本和市场权限" }
                value.getJSONObject("result")
            }
        } finally { pending.remove(id) }
    }
    override fun close() { closed.set(true); stop(); scope.cancel(); pending.values.forEach { it.cancel() }; pending.clear() }
    companion object {
        suspend fun connect(conn: Conn?): PluginRpc {
            val client = if (conn != null) {
                check(conn.status == Conn.Status.Connected) { "服务器未连接" }
                val shell = conn.ssh.openExecStream("""
                    cd "${'$'}HOME" || exit 1
                    bin=${'$'}(command -v codex || true)
                    if [ -z "${'$'}bin" ] && [ -x "${'$'}HOME/.local/bin/codex" ]; then bin="${'$'}HOME/.local/bin/codex"; fi
                    [ -n "${'$'}bin" ] || exit 127
                    exec "${'$'}bin" app-server
                """.trimIndent())
                PluginRpc(shell.output, { shell.write(it) }, { shell.close() })
            } else withContext(Dispatchers.IO) {
                val home = File(System.getProperty("user.home"))
                val codexHome = System.getenv("CODEX_HOME")?.let(::File) ?: File(home, ".codex")
                val names = if (System.getProperty("os.name").startsWith("Windows")) listOf("codex.exe") else listOf("codex")
                val candidates = listOf(File(codexHome, "plugins/.plugin-appserver/codex.exe"), File(home, ".local/bin/codex")) +
                    System.getenv("PATH").orEmpty().split(File.pathSeparator).flatMap { dir -> names.map { File(dir, it) } }
                val binary = candidates.firstOrNull { it.isFile && it.canExecute() } ?: error("此电脑未找到 Codex，请先安装 Codex 并登录后刷新")
                val process = ProcessBuilder(binary.absolutePath, "app-server").directory(home)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                PluginRpc(process.inputStream, { text -> withContext(Dispatchers.IO) {
                    process.outputStream.write(text.toByteArray(Charsets.UTF_8)); process.outputStream.flush(); true
                } }, { process.destroy(); process.inputStream.close() })
            }
            try {
                client.request("initialize", JSONObject().put("clientInfo", JSONObject().put("name", "yxi_plugins").put("version", "1")))
                check(client.write("{\"method\":\"initialized\",\"params\":{}}\n"))
                return client
            } catch (e: Exception) { client.close(); throw e }
        }
    }
}

internal data class NativePlugin(val id: String, val name: String, val title: String, val description: String,
    val category: String, val marketplace: String, val marketplacePath: String?, val version: String,
    val installed: Boolean, val enabled: Boolean, val installable: Boolean, val iconUrl: String?,
    val source: String, val authPolicy: String, val fingerprint: String) {
    fun installParams() = JSONObject().put("pluginName", name).put("installAttemptId", UUID.randomUUID().toString()).apply {
        if (marketplacePath != null) put("marketplacePath", marketplacePath) else put("remoteMarketplaceName", marketplace)
    }
}
internal data class NativePluginSnapshot(val entries: List<NativePlugin>, val errors: Int) {
    companion object {
        fun parse(value: JSONObject): NativePluginSnapshot {
            val markets = value.getJSONArray("marketplaces")
            val entries = buildList {
                for (i in 0 until markets.length()) {
                    val market = markets.getJSONObject(i)
                    val plugins = market.getJSONArray("plugins")
                    for (j in 0 until plugins.length()) {
                        val p = plugins.getJSONObject(j)
                        val ui = p.optJSONObject("interface") ?: JSONObject()
                        val name = p.getString("name")
                        val source = p.getJSONObject("source").toString()
                        val version = p.text("version") ?: p.text("localVersion").orEmpty()
                        add(NativePlugin(p.getString("id"), name, ui.text("displayName") ?: name,
                            ui.text("shortDescription").orEmpty(), ui.text("category") ?: "其他",
                            market.getString("name"), market.text("path"), version,
                            p.getBoolean("installed"), p.getBoolean("enabled"),
                            p.optString("availability", "AVAILABLE") == "AVAILABLE" &&
                                p.optString("installPolicy") in setOf("AVAILABLE", "INSTALLED_BY_DEFAULT") &&
                                !p.optBoolean("mustShowInstallationInterstitial"),
                            ui.text("logoUrl") ?: ui.text("composerIconUrl"), source, p.optString("authPolicy"),
                            listOf(name, market.getString("name"), market.text("path"), source, version).joinToString("\n")))
                    }
                }
            }
            return NativePluginSnapshot(entries.distinctBy { it.id }, value.optJSONArray("marketplaceLoadErrors")?.length() ?: 0)
        }
        private fun JSONObject.text(key: String) = opt(key).let { it as? String }?.takeIf { it.isNotBlank() }
    }
}

/** One controller per machine, retained across navigation. Unknown writes are never retried implicitly. */
internal class NativePluginStore(private var conn: Conn?, file: File) {
    private val targetKey = conn?.let { projectKey(it.host, "/") }
    internal fun attach(next: Conn?) {
        check(next?.let { projectKey(it.host, "/") } == targetKey)
        conn = next
    }
    private suspend fun connect() = PluginRpc.connect(conn)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val ledger = DurableFile(file) { check(JSONObject(it).getInt("version") == 1) }
    private val reviewMarker = File(file.parentFile, file.name + ".needs-review")
    var entries by mutableStateOf<List<NativePlugin>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var installing by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var error by mutableStateOf(""); private set
    var pendingId by mutableStateOf<String?>(null); private set
    var authLinks by mutableStateOf<List<Pair<String, String>>>(emptyList()); private set
    private var readable = true
    init {
        try {
            ledger.read()?.let { pendingId = JSONObject(it).optString("pendingId").takeIf(String::isNotBlank) }
            if (ledger.recovered) DurableFile.replace(reviewMarker, "Plugin installation ledger recovered; reconcile before new writes")
            check(!reviewMarker.exists()) { "插件安装记录已恢复，请先人工核对" }
        } catch (_: Exception) { readable = false; error = "插件安装记录无法确认，已禁止重复安装" }
    }
    private fun savePending(id: String?) {
        ledger.write(JSONObject().put("version", 1).put("pendingId", id.orEmpty()).toString()); pendingId = id
    }
    fun refresh() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val snapshot = connect().use { NativePluginSnapshot.parse(it.request("plugin/list")) }
                entries = snapshot.entries
                if (readable) error = if (snapshot.errors > 0) "部分市场读取失败，可刷新重试" else ""
                if (readable && pendingId != null && entries.any { it.id == pendingId && it.installed }) {
                    savePending(null); message = "已确认运行器记录中存在此插件；服务连接状态请在运行器中核对"
                }
            } catch (e: Exception) { error = e.message ?: "插件目录读取失败" }
            finally { busy = false }
        }
    }
    fun install(plugin: NativePlugin) {
        if (busy || !readable || pendingId != null || !plugin.installable || plugin.installed) return
        busy = true; installing = true; error = ""; message = ""; authLinks = emptyList()
        scope.launch {
            try {
                connect().use { rpc ->
                    val fresh = NativePluginSnapshot.parse(rpc.request("plugin/list")).entries.singleOrNull { it.id == plugin.id }
                    check(fresh != null && fresh.fingerprint == plugin.fingerprint && fresh.installable && !fresh.installed) { "插件目录已变化，请刷新后重新选择" }
                    savePending(plugin.id) // Durable before the non-idempotent RPC write.
                    val result = rpc.request("plugin/install", fresh.installParams())
                    val apps = result.getJSONArray("appsNeedingAuth")
                    authLinks = (0 until apps.length()).mapNotNull { index ->
                        val app = apps.getJSONObject(index)
                        val url = app.optString("installUrl")
                        val uri = runCatching { java.net.URI(url) }.getOrNull()
                        if (uri?.scheme == "https" && uri.host != null && uri.userInfo == null) app.getString("name") to url else null
                    }
                    entries = NativePluginSnapshot.parse(rpc.request("plugin/list")).entries
                    check(entries.any { it.id == plugin.id && it.installed }) { "安装结果尚未确认，请刷新核对，勿重复安装" }
                    savePending(null)
                    message = if (apps.length() > 0) "插件已安装，仍有 ${apps.length()} 个服务需要授权；请在目标运行器完成连接" else "插件已安装，请在目标运行器的新对话中使用；服务可能在首次使用时要求授权"
                }
            } catch (e: Exception) {
                error = if (pendingId != null) "安装结果待确认，请刷新核对；不会自动重复安装" else e.message.orEmpty()
            } finally { busy = false; installing = false }
        }
    }
}
