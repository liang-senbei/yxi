package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal const val LOCAL_HOST_SCOPE = "@local"

internal class LocalWorkspace : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private var scan: Job? = null
    private var listing: Job? = null
    private var reading: Job? = null
    private var client: LocalCodexHistory? = null
    private var generation = 0
    private var readGeneration = 0
    var installations by mutableStateOf<List<LocalRuntimeInstallation>>(emptyList()); private set
    var selectedRuntime by mutableStateOf<LocalRuntimeInstallation?>(null); private set
    var detecting by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var readingHistory by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    var readError by mutableStateOf(""); private set
    var account by mutableStateOf<NativeAccountStatus?>(null); private set
    var threads by mutableStateOf<List<NativeHistoryThread>>(emptyList()); private set
    var next by mutableStateOf<String?>(null); private set
    var query by mutableStateOf(""); private set
    var archived by mutableStateOf(false); private set
    var selectedThread by mutableStateOf<NativeHistoryThread?>(null); private set
    var turns by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var nextTurns by mutableStateOf<String?>(null); private set
    var paginated by mutableStateOf(true); private set
    var scanned by mutableStateOf(false); private set
    var projects by mutableStateOf(runCatching { JSONArray(Store.pref("localProjects", "[]")).let { a ->
        (0 until a.length()).map { a.getString(it) }.distinct() } }.getOrDefault(emptyList())); private set

    fun addProject(directory: File) {
        require(directory.isDirectory) { "请选择存在的本机目录" }
        projects = (projects + directory.canonicalPath).distinct()
        Store.setPref("localProjects", JSONArray(projects).toString())
    }
    fun chooseExecutable(engine: String, file: File) {
        if (detecting) return
        scan = scope.launch {
            detecting = true; error = ""
            try {
                val candidate = LocalRuntimeDiscovery.manualCandidate(engine, file)
                val verified = LocalRuntimeDiscovery.verify(candidate)
                installations = (installations.filterNot { it.id == verified.id } + verified)
                if (verified.ready) {
                    val saved = runCatching { JSONObject(Store.pref("localRuntimePaths", "{}")) }.getOrDefault(JSONObject())
                    Store.setPref("localRuntimePaths", saved.put(engine, file.canonicalPath).toString())
                    if (engine == "codex") selectRuntime(verified)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "入口检测失败" }
            finally { detecting = false }
        }
    }
    fun refresh() {
        if (detecting) return
        scan = scope.launch {
            detecting = true; error = ""
            try {
                val found = LocalRuntimeDiscovery.discover()
                installations = found; scanned = true
                val wanted = selectedRuntime?.id ?: Store.pref("localHistoryRuntime", "")
                val chosen = found.firstOrNull { it.id == wanted && it.ready && it.engine == "codex" }
                    ?: found.firstOrNull { it.ready && it.engine == "codex" && it.source == "桌面附带" }
                    ?: found.firstOrNull { it.ready && it.engine == "codex" }
                if (chosen != null) selectRuntime(chosen) else {
                    disconnect(); selectedRuntime = null; threads = emptyList(); account = null
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "本机检测失败" }
            finally { detecting = false }
        }
    }
    fun selectRuntime(installation: LocalRuntimeInstallation) {
        require(installation.ready && installation.engine == "codex")
        disconnect(); selectedRuntime = installation; selectedThread = null; turns = emptyList(); threads = emptyList(); account = null
        val gen = generation
        Store.setPref("localHistoryRuntime", installation.id)
        listing = scope.launch {
            loading = true; error = ""
            var connected: LocalCodexHistory? = null
            try {
                connected = LocalCodexHistory.connect(installation)
                client = connected
                account = connected.account()
                val page = connected.list(archived, query)
                threads = page.threads; next = page.next
            } catch (e: CancellationException) { connected?.close(); throw e }
            catch (e: Exception) { connected?.close(); if (client === connected) client = null; error = e.message ?: "原生历史连接失败" }
            finally { if (generation == gen) loading = false }
        }
    }
    fun loadThreads(search: String = query, includeArchived: Boolean = archived, more: Boolean = false) {
        if (loading) return
        val connected = client ?: run { selectedRuntime?.let(::selectRuntime); return }
        if (!more) { query = search; archived = includeArchived }
        val cursor = if (more) next ?: return else null
        listing = scope.launch {
            loading = true; error = ""
            try {
                val page = connected.list(archived, query, cursor)
                if (more && page.next == cursor) error("运行器返回重复分页标识，请刷新")
                threads = (if (more) threads + page.threads else page.threads).distinctBy { it.id }
                next = page.next
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "历史列表读取失败" }
            finally { if (client === connected) loading = false }
        }
    }
    fun openThread(thread: NativeHistoryThread, more: Boolean = false) {
        val connected = client ?: return
        reading?.cancel()
        val gen = ++readGeneration
        if (!more) { selectedThread = thread; turns = emptyList(); nextTurns = null }
        val cursor = if (more) nextTurns ?: return else null
        reading = scope.launch {
            readingHistory = true; readError = ""
            try {
                val page = connected.turns(thread.id, cursor)
                if (selectedThread?.id == thread.id && client === connected) {
                    if (more && page.next == cursor) error("运行器返回重复历史分页标识")
                    turns = (if (more) page.turns + turns else page.turns).distinctBy { it.optString("id") }
                    nextTurns = page.next; paginated = page.paginated
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (gen == readGeneration) readError = e.message ?: "对话读取失败" }
            finally { if (gen == readGeneration && selectedThread?.id == thread.id && client === connected) readingHistory = false }
        }
    }
    fun backToList() { reading?.cancel(); selectedThread = null; readingHistory = false; readError = "" }
    private fun disconnect() { generation++; readGeneration++; listing?.cancel(); reading?.cancel(); client?.close(); client = null; loading = false; readingHistory = false; next = null; nextTurns = null }
    override fun close() { scan?.cancel(); disconnect(); scope.cancel() }
}
