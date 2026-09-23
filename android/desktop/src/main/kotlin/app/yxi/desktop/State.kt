package app.yxi.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.yxi.agent.Session
import app.yxi.agent.SessionState

/**
 * 全局状态（一份，`App()` 里 remember）。
 * 左栏 = 主机分组 → cc-* 会话行（Codex 的「项目 → 线程」），所以可以同时连着多台主机（[conns]）；
 * [conn] / [session] 是右边正在看的那一个。
 */
/**
 * 右边整页显示哪一块 —— 对齐手机底部四栏。
 *
 * ⚠️ 手机那四栏里「会话」和「主机」在桌面上**合成了左栏**（Claude Desktop 的形态：
 * 主机分组 → 会话行都在侧栏里），所以这里只剩另外两个整页入口。
 * 它跟 [AppState.tab] 是两层：`page` 决定右边整块是什么，`tab` 只在工作区里选 对话/终端/文件。
 */
enum class Page { Workspace, Config, Me, Routes, Codex, Plugins, ConfigFiles, Connections, LocalAgents, LocalWorkspace, ScheduledTasks }

internal class CodexConversationView {
    val scroll = androidx.compose.foundation.lazy.LazyListState()
    val followLatest = mutableStateOf(true)
}

class AppState {
    private val localWorkspaceDelegate = lazy { LocalWorkspace() }
    private val localCodexTasksDelegate = lazy { LocalCodexTasks(instructions, java.io.File(Store.dir, "local-codex-tasks.json")) }
    internal val localCodexTasks get() = localCodexTasksDelegate.value
    internal val localWorkspace get() = localWorkspaceDelegate.value
    internal val isLocal get() = hostScope == LOCAL_HOST_SCOPE
    internal fun selectLocal() {
        rememberTaskView(); conn = null; session = null; restoreSession = null; restoreRuntime = null
        configurationHostId = ""; pluginLocation = "本地"; scopeHost(LOCAL_HOST_SCOPE); page = Page.LocalWorkspace
    }
    internal val scheduledTasks by lazy { ScheduledTasks(java.io.File(Store.dir, "scheduled-tasks.json")) }
    private val linksDelegate = lazy { DeviceLinks() }
    internal val deviceLinks get() = linksDelegate.value
    private val localAgentsDelegate = lazy { LocalAgents() }
    internal val localAgents get() = localAgentsDelegate.value
    var localAgentPrompt by mutableStateOf("")
    internal val localOperations get() = (if (linksDelegate.isInitialized()) deviceLinks.busy.size else 0) +
        (if (localAgentsDelegate.isInitialized()) localAgents.jobs.count { it.running } else 0) +
        (if (localCodexTasksDelegate.isInitialized()) (if (localCodexTasks.busy) 1 else 0) +
            localCodexTasks.controllers.values.count { it.sending || it.activeTurnId != null || it.pendingRequests.isNotEmpty() } else 0)
    internal fun closeLocalFeatures() {
        if (localCodexTasksDelegate.isInitialized()) localCodexTasks.close()
        if (localWorkspaceDelegate.isInitialized()) localWorkspace.close()
        if (linksDelegate.isInitialized()) deviceLinks.close()
        if (localAgentsDelegate.isInitialized()) localAgents.close()
    }
    var configurationHostId by mutableStateOf("")
    internal fun configurationConnection() = if (isLocal) null else if (configurationHostId.isBlank()) conn
        else conns.firstOrNull { it.host.id == configurationHostId }
    var pluginLocation by mutableStateOf("本地")
    var pluginMarketplace by mutableStateOf(true)
    var pluginMarketQuery by mutableStateOf("")
    var pluginCatalogRuntime by mutableStateOf("codex")
    private val nativePluginStores = mutableMapOf<String, NativePluginStore>()
    internal fun nativePlugins(target: Conn?): NativePluginStore {
        val key = target?.let { projectKey(it.host, "/") } ?: "local"
        return nativePluginStores.getOrPut(key) {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
            NativePluginStore(target, java.io.File(Store.dir, "plugin-installs/codex-$hash.json"))
        }.also { it.attach(target) }
    }
    internal val nativePluginBusy get() = nativePluginStores.values.count { it.installing }
    internal fun openServerPlugins(target: Conn) {
        if (conn !== target) select(target, null)
        pluginLocation = "服务器"
        pluginMarketplace = false
        page = Page.Plugins
    }
    var showAndroidEmulator by mutableStateOf(false)
    internal val codexConversationViews = mutableMapOf<String, CodexConversationView>()
    internal var codexSelectedTaskKey by mutableStateOf<String?>(null)
    internal var codexCreateRequest by mutableStateOf<Pair<String, String>?>(null)
    internal var codexCreateGroup by mutableStateOf("")
    internal var routesOrigin by mutableStateOf(Page.Workspace)
    internal fun openRoutes() {
        routesOrigin = if (page == Page.Routes) routesOrigin else page
        if (page != Page.ConfigFiles) configurationHostId = conn?.host?.id.orEmpty()
        page = Page.Routes
    }
    internal fun prepareCodexTask(c: Conn, directory: String, prompt: String = "", group: String = "") {
        codexCreateGroup = group
        select(c, null)
        codexSelectedTaskKey = null
        codexCreateRequest = directory to prompt
        page = Page.Codex
    }
    internal fun codexRouteBusy(c: Conn): Boolean {
        val keys = codexWorkspace.tasks(c.host).map { it.key }.toSet()
        return codexWorkspace.controllers.any { (key, controller) -> key in keys &&
            (controller.activeTurnId != null || controller.sending || controller.pendingRequests.isNotEmpty()) } ||
            instructions.entries.any { it.taskKey in keys && (it.status in setOf(InstructionStatus.Delivering, InstructionStatus.Unknown) || it.runtimeTurnState == RuntimeTurnState.InProgress) }
    }
    internal fun appendCodexQuote(task: CodexTaskRecord, quote: String) {
        val draft = chatDrafts.getOrPut(task.key) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue()) }
        val text = draft.value.text.let { it + if (it.isBlank()) "" else "\n\n" } + quote + "\n"
        draft.value = androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))
    }
    internal suspend fun openCodexDocument(c: Conn, task: CodexTaskRecord, path: String) {
        check(task.hostKey == projectKey(c.host, "/")) { "任务不属于当前服务器" }
        val sftp = c.ssh.openSftp()
        val canonical = try { sftp.realpath(if (path.startsWith('/')) path else task.directory.trimEnd('/') + "/" + path) } finally { sftp.close() }
        if (documents.none { it.hostId == c.host.id && it.task == task.key && it.runtimeId == task.key && it.path == canonical })
            documents += FileDocument(c.host.id, task.key, canonical, DocumentEndpoint.of(c.host), task.key)
        documentSelection[task.key] = canonical
    }
    val browsers = mutableMapOf<String, BrowserPreview>()
    var browserPanelOpen by mutableStateOf(false)
    val navigation = WorkspaceNavigation(java.io.File(Store.dir, "workspace.json"))
    internal val projectPreviews = ProjectPreviews(java.io.File(Store.dir, "project-previews.json"))
    internal val projectServices = ProjectServices(java.io.File(Store.dir, "project-services.json"))
    internal val serviceControllers = mutableMapOf<String, PreviewServiceController>()
    internal val serviceEditors = mutableMapOf<String, ServiceEditor>()
    internal val pluginOperations = PluginOperations(java.io.File(Store.dir, "plugin-operations.json"))
    internal val instructions = InstructionQueue(java.io.File(Store.dir, "instructions.json"))
    internal val modelSwitches = ModelChangeStore(java.io.File(Store.dir, "model-changes.json"))
    internal val codexWorkspace = CodexWorkspace(instructions, java.io.File(Store.dir, "codex-tasks.json")) { task, title ->
        Notify.notify(title, "任务：" + (navigation.title(task.key) ?: task.title), taskKey = task.key)
    }
    internal val support = SupportWorkspace(java.io.File(Store.dir, "support-drafts.json"))
    internal val shopPurchases = ShopPurchaseStore(java.io.File(Store.dir, "shop-purchases.json"))
    val documents = mutableStateListOf<FileDocument>()
    val documentSelection = androidx.compose.runtime.mutableStateMapOf<String, String>()
    val chatDrafts = mutableMapOf<String, androidx.compose.runtime.MutableState<androidx.compose.ui.text.input.TextFieldValue>>()
    fun appendDocumentQuote(host: Host, task: Session, quote: String) {
        val holder = chatDrafts.getOrPut(taskNavigationKey(host, task)) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue()) }
        val old = holder.value.text
        val text = old + (if (old.isBlank()) "" else "\n\n") + quote + "\n"
        holder.value = androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))
    }
    var filePanelOpen by mutableStateOf(false)
    var filePanelWidth by mutableStateOf(Store.pref("previewPanelWidth", "480").toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(340f, 900f) ?: 480f)
    var previewExpanded by mutableStateOf(false)
    fun savePreviewWidth() {
        runCatching { Store.setPref("previewPanelWidth", filePanelWidth.toInt().toString()) }
            .onFailure { workspaceError = "预览宽度未保存：${it.message}" }
    }
    var workspaceError by mutableStateOf("")

    suspend fun openDocument(c: Conn, task: Session, path: String) {
        val sftp = c.ssh.openSftp()
        val canonical = try { sftp.realpath(if (path.startsWith('/')) path else task.cwd.trimEnd('/') + "/" + path) } finally { sftp.close() }
        if (documents.none { it.hostId == c.host.id && it.matchesTask(task) && it.path == canonical }) documents += FileDocument(c.host.id, task.name, canonical, DocumentEndpoint.of(c.host), task.runtimeId)
        documentSelection[taskNavigationKey(c.host, task)] = canonical
        if (conn === c && session?.name == task.name) { filePanelOpen = true; browserPanelOpen = false; tab = 0 }
    }
    var hostScope by mutableStateOf(Store.pref("hostScope", if (Store.pref("lastHost", "").isBlank()) LOCAL_HOST_SCOPE else ""))
    internal var startupRestored = false
    internal var restoreSession by mutableStateOf<String?>(null)
    internal var restoreRuntime by mutableStateOf<String?>(null)
    val conns = mutableStateListOf<Conn>()                 // 连着的主机（顺序 = 侧栏分组顺序）
    var conn by mutableStateOf<Conn?>(null)                // 当前会话所在的主机
    var session by mutableStateOf<Session?>(null)          // 当前会话
    var tab by mutableStateOf(0)                           // 0 对话 1 终端 2 文件 3 改动
    private data class TaskView(val tab: Int = 0, val files: Boolean = false, val browser: Boolean = false, val expanded: Boolean = false)
    private val taskViews = mutableMapOf<String, TaskView>()
    fun rememberTaskView() {
        val host = conn?.host ?: return
        val task = session ?: return
        taskViews[taskNavigationKey(host, task)] = TaskView(tab, filePanelOpen, browserPanelOpen, previewExpanded)
    }
    var page by mutableStateOf(if (hostScope == LOCAL_HOST_SCOPE) Page.LocalWorkspace else Page.Workspace)
    var sidebarOpen by mutableStateOf(true)                // Ctrl+B；窗口 < 700 时自动收起
    var newSessionRequest by mutableStateOf(0)             // Ctrl+N：+1 一次，侧栏看到就弹「新建会话」
    /** 侧栏上一次处理过的 [newSessionRequest] 值。放 AppState 里而不是 Sidebar 的 remember：
     * Sidebar 收起时整个不在组合里，remember 跟着丢——重新展开时 remember 重新初始化成当前值，
     * 恰好等于刚才 Ctrl+N 加到的那个值，`== seen` 成立，弹窗就不弹了。 */
    internal var newSessionSeen by mutableStateOf(0)
    var showSettings by mutableStateOf(false)              // Ctrl+,
    var settingsSection by mutableStateOf("常规")
    var meSection by mutableStateOf("个人资料")
    var accountLoginRequest by mutableStateOf(0)
    var accountLoginHandled by mutableStateOf(0)
    var accountForceLogin by mutableStateOf(false)
    fun requestAccountLogin(force: Boolean) {
        accountForceLogin = force; accountLoginRequest++
        showSettings = false; meSection = "个人资料"; page = Page.Me
    }
    var showShortcuts by mutableStateOf(false)             // Ctrl+/
    var showTaskSwitcher by mutableStateOf(false)          // Ctrl+K
    var showCollaboration by mutableStateOf(false)
    internal var deferredRoute by mutableStateOf<DeferredRoute?>(null)
    var deferredRouteNotice by mutableStateOf("")

    /** ⚠️ 选会话顺带回工作区：人在「配置」页点了侧栏的会话，意思显然是「我要去看那个会话」。 */
    fun select(c: Conn, s: Session?) {
        val oldKey = conn?.let { old -> session?.let { taskNavigationKey(old.host, it) } }
        val nextKey = s?.let { taskNavigationKey(c.host, it) }
        if (oldKey != nextKey) {
            rememberTaskView()
            val view = nextKey?.let { taskViews[it] } ?: TaskView()
            tab = view.tab.coerceIn(0, 3)
            filePanelOpen = view.files
            browserPanelOpen = view.browser
            previewExpanded = view.expanded
        }
        conn = c; session = s; page = Page.Workspace; restoreSession = null; restoreRuntime = null
        if (s != null) {
            if (navigation.archived(taskNavigationKey(c.host, s))) navigation.setMode("归档")
            else if (navigation.mode == "归档") navigation.setMode("全部")
            navigation.setCollapsed(projectKey(c.host, s.cwd), false)
        }
        Store.setPref("lastHost", c.host.id)
        Store.setPref("lastSession", s?.name.orEmpty())
        Store.setPref("lastRuntime", s?.runtimeId.orEmpty())
        if (hostScope.isNotEmpty()) scopeHost(c.host.id)
    }

    fun scopeHost(id: String) { hostScope = id; Store.setPref("hostScope", id) }

    /** 所有主机的会话按侧栏顺序摊平（Ctrl+Tab / Ctrl+1…9 用）。 */
    fun allSessions(): List<Pair<Conn, Session>> = conns.flatMap { c -> c.sessions.filter { navigation.visible(taskNavigationKey(c.host, it), it.state) }.map { c to it } }

    fun selectByOffset(delta: Int) {
        val all = allSessions(); if (all.isEmpty()) return
        val i = all.indexOfFirst { it.first === conn && it.second.name == session?.name }
        val (c, s) = all[((if (i < 0) 0 else i + delta) % all.size + all.size) % all.size]
        select(c, s)
    }

    fun selectIndex(i: Int) { allSessions().getOrNull(i)?.let { (c, s) -> select(c, s) } }

    /** Ctrl+Alt+A：跳到下一个「等待批准 / 需要输入」的会话；没有就不动。侧栏实现里按 SessionState 判。 */
    fun jumpToAttention() {
        val all = allSessions(); if (all.isEmpty()) return
        val i = all.indexOfFirst { it.first === conn && it.second.name == session?.name }
        for (k in 1..all.size) {
            val (c, s) = all[(i + k) % all.size]
            if (s.needsAttention()) { select(c, s); return }
        }
    }
}

/** 等人处理 = 在等审批或等输入。core 的 SessionProbe 把 Claude Code 的 `waiting` 解成 NeedsYou，批准还是输入都在里面（细分见 SessionsPane 的 badge）。 */
fun Session.needsAttention(): Boolean = state == SessionState.NeedsYou
