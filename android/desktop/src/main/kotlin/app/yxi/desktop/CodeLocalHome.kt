package app.yxi.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 本机首页的草稿（Code 风格）：放在 [AppState] 上，切风格、翻页都不丢（PRD §4.1）。
 * [directory] / [runtimeId] 为空时取默认；组合时不 stat 目录，创建时再校验。
 */
internal class LocalHomeDraft {
    var text by mutableStateOf(TextFieldValue())
    var directory by mutableStateOf("")
    var runtimeId by mutableStateOf("")
    var focusTick by mutableStateOf(0)
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    var job: Job? = null
    fun directoryOr(w: LocalWorkspace): String = directory.ifBlank { w.projects.firstOrNull() ?: System.getProperty("user.home") }
    /** 可选运行器：就绪的安装；Codex 只列工作台选中的那个（经典新建对话框固定用它），按 [LocalRuntimeDiscovery.engines] 排。 */
    fun choices(w: LocalWorkspace): List<LocalRuntimeInstallation> = w.installations
        .filter { it.ready && (it.engine != "codex" || it.id == w.selectedRuntime?.id) }
        .sortedBy { LocalRuntimeDiscovery.engines.indexOf(it.engine) }
    fun runtimeOr(w: LocalWorkspace): LocalRuntimeInstallation? = choices(w).let { all ->
        all.firstOrNull { it.id == runtimeId } ?: all.firstOrNull { it.engine == "claude" } ?: all.firstOrNull()
    }
    fun reset() { text = TextFieldValue(); error = "" }
}

/**
 * Code 风格的本机首页（规格 §3.8）：和 SSH 的 [CodeHome] 共用 [CodeHomeFrame]，环境 chip 显示「本机」。
 * 按 Enter：Claude Code 直接建（先核对身份和官方端点，同经典「新建官方对话」）；Codex / OpenCode / ACP 要选模型、连接或认证，
 * 打开经典的新建对话框，文件夹带过去。两条路都不自动发送：提示词放进新对话的输入框，由你确认（同经典「创建不会自动发送消息」）。
 */
@Composable
internal fun CodeLocalHome(state: AppState, actions: HostActions, scope: CoroutineScope) {
    val w = state.localWorkspace
    val d = state.localHomeDraft
    val runtime = d.runtimeOr(w)
    var dialog by remember { mutableStateOf<LocalRuntimeInstallation?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(d.focusTick) { runCatching { focus.requestFocus() } }
    val canSend = runtime != null && !d.busy && dialog == null
    CodeHomeFrame(
        state,
        notes = { CodeNoteLines(localHomeNotes(w, d, runtime)) },
        chips = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.6.dp), verticalAlignment = Alignment.CenterVertically) {
                CodeEnvChip(state, actions, null, local = true)
                CodeLocalFolderChip(w, d)
            }
        },
        footer = { CodeLocalHomeFooter(state, w, d, runtime) },
    ) {
        CodeComposerCard(
            d.text, { d.text = it }, "描述要做的事，按 Enter 新建对话（不会自动发送）", focus, canSend,
            onSend = {
                if (runtime != null && canSend) {
                    if (runtime.engine == "claude") createLocalClaude(state, scope, runtime) else dialog = runtime
                }
            },
            modifier = Modifier.fillMaxWidth(), readOnly = d.busy,
        )
    }
    dialog?.let { r -> LocalHomeDialog(state, r, { dialog = null }) { record -> dialog = null; openLocalHomeTask(state, record) } }
}

/** 经典的新建对话框（和经典工作台的运行器卡是同一套：Codex 用工作台选中的运行器，Gemini / Grok / Hermes 走 ACP）。 */
@Composable
private fun LocalHomeDialog(state: AppState, runtime: LocalRuntimeInstallation, close: () -> Unit, created: (LocalCodexTaskRecord) -> Unit) {
    val directory = state.localHomeDraft.directoryOr(state.localWorkspace)
    when (runtime.engine) {
        "codex" -> NewLocalConversationDialog(state, close, initialDirectory = directory, created = created)
        "opencode" -> NewOpenCodeConversationDialog(state, runtime, close, initialDirectory = directory, created = created)
        else -> NewAcpConversationDialog(state, runtime, close, initialDirectory = directory, created = created)
    }
}

/** 「新会话」在本机时（Code 侧栏的行和组标题「+」、Code 下的 Ctrl+N）：回到首页（离开正打开的本机对话和原生历史）并聚焦输入卡。经典照旧只切页。 */
internal fun openLocalHome(state: AppState) {
    state.localSelectedTaskKey = null
    state.localWorkspace.backToList()
    state.page = Page.LocalWorkspace
    state.localHomeDraft.focusTick++
}

/** 建好之后：提示词原样放进新对话的输入框（不发送），清掉首页草稿，切到那个对话（[LocalWorkspacePane] 按 key 找到它）。 */
private fun openLocalHomeTask(state: AppState, record: LocalCodexTaskRecord) {
    val d = state.localHomeDraft
    if (d.text.text.isNotBlank()) state.chatDrafts[record.key] = mutableStateOf(d.text)
    d.reset()
    state.localSelectedTaskKey = record.key
}

/**
 * Claude Code：同经典「新建官方对话」，先核对身份和有效官方端点再建；标题取提示词第一行（空就用「新对话」）。
 * 在 App 级 scope 里跑，切风格不打断；「取消创建」取消这个 job（经典对话框的「取消创建」同理）。
 */
private fun createLocalClaude(state: AppState, scope: CoroutineScope, runtime: LocalRuntimeInstallation) {
    val d = state.localHomeDraft
    val directory = d.directoryOr(state.localWorkspace)
    val title = d.text.text.lineSequence().map { line -> line.filter { it >= ' ' }.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(40)
    d.busy = true; d.error = ""
    d.job = scope.launch {
        try {
            val folder = File(directory)
            check(folder.isAbsolute && folder.isDirectory) { "文件夹不可用：$directory" }
            openLocalHomeTask(state, state.localClaudeTasks.create(runtime, directory, title))
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { d.error = e.message.orEmpty().ifBlank { "创建没成功，请重试" } }
        finally { d.busy = false; d.job = null }
    }
}

/** 输入卡上方的说明：检测状态、没有可用运行器时去哪里设、工作台的错误、创建进度和失败原因。 */
private fun localHomeNotes(w: LocalWorkspace, d: LocalHomeDraft, runtime: LocalRuntimeInstallation?): List<Pair<String, Boolean>> {
    val notes = mutableListOf<Pair<String, Boolean>>()
    if (runtime == null) notes += (if (w.detecting || !w.scanned) "正在检测本机运行器…" else "没有找到可用的本机运行器：在右下角「管理运行器…」里选择路径，或重新检测") to false
    if (w.error.isNotBlank()) notes += w.error to true
    if (d.busy) notes += "正在核对身份和官方端点，并创建对话…" to false
    if (d.error.isNotBlank()) notes += d.error to true
    return notes
}

/** 页脚：左边创建中的「取消创建」；右边运行器和上下文环（新对话还没有用量，整环灰）。本机创建不带权限参数，不放权限 chip。 */
@Composable
private fun CodeLocalHomeFooter(state: AppState, w: LocalWorkspace, d: LocalHomeDraft, runtime: LocalRuntimeInstallation?) {
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        if (d.busy) CodeFooterChip("取消创建", { d.job?.cancel() })
        Spacer(Modifier.weight(1f))
        CodeLocalRunnerChip(state, w, d, runtime)
        Spacer(Modifier.width(9.6.dp))
        CodeContextRing(null)
        Spacer(Modifier.width(4.dp))
    }
}

/** 运行器 chip：就绪的本机运行器；浮层右缘对齐 chip、向上展开，末尾「重新检测」和「管理运行器…」（本机配置页：选择路径、读取历史、账号）。 */
@Composable
private fun CodeLocalRunnerChip(state: AppState, w: LocalWorkspace, d: LocalHomeDraft, runtime: LocalRuntimeInstallation?) {
    var open by remember { mutableStateOf(false) }
    NativeOverlay(open)
    val close = { open = false }
    val choices = d.choices(w)
    val label = runtime?.let { LocalRuntimeDiscovery.title(it.engine) } ?: if (w.detecting || !w.scanned) "检测中…" else "没有运行器"
    Box {
        CodeHint("沿用本机运行器自己的登录与配置") {
            CodeFooterChip(label, { open = true }, held = open, enabled = !d.busy)
        }
        if (open) CodePopup(close, side = PopupSide.Above, alignEnd = true, gap = 2.4.dp, width = 240.dp) {
            choices.forEach { r ->
                val twin = choices.count { it.engine == r.engine } > 1
                val trailing = listOfNotNull(r.source.takeIf { twin }, "预览".takeIf { r.engine in LocalPreviewEngines }).joinToString(" · ").ifBlank { null }
                CodeMenuItem(LocalRuntimeDiscovery.title(r.engine), { close(); d.runtimeId = r.id }, trailing = trailing, checked = r.id == runtime?.id)
            }
            if (choices.isNotEmpty()) CodeMenuDivider()
            CodeMenuItem(if (w.detecting) "检测中…" else "重新检测", { close(); w.refresh() }, enabled = !w.detecting)
            CodeMenuItem("管理运行器…", { close(); state.page = Page.Config })
        }
    }
}

/** 经典运行器卡上标「预览」的：Claude 官方对话、Gemini / Grok / Hermes 的 ACP 接入。 */
private val LocalPreviewEngines = setOf("claude", "gemini", "grok", "hermes")

/**
 * 文件夹 chip：本地项目和原生历史里用过的目录（最多 5 个，当前项打勾；目录不在了标「不可用」、不能选，同经典「目录已不可用」），
 * 外加「打开文件夹…」（规格 §3.1）。只在浮层打开时才看目录在不在。
 */
@Composable
private fun CodeLocalFolderChip(w: LocalWorkspace, d: LocalHomeDraft) {
    var open by remember { mutableStateOf(false) }
    NativeOverlay(open)
    val close = { open = false }
    val current = File(d.directoryOr(w)).path
    val recent = (w.projects + w.threads.map { it.directory }).filter { it.isNotBlank() }.map { File(it).path }.distinct().take(5)
    Box {
        CodeHint(current) {
            CodeChip(localFolderName(current), Icons.Outlined.Folder, if (d.busy) null else ({ open = true }), Modifier.widthIn(max = 220.dp), held = open)
        }
        if (open) CodePopup(close, side = PopupSide.Above, gap = 5.6.dp, width = 191.2.dp) {
            if (recent.isNotEmpty()) {
                CodeMenuCaption("最近")
                val names = recent.groupingBy(::localFolderName).eachCount()
                recent.forEach { path ->
                    val name = localFolderName(path)
                    val parent = File(path).parentFile?.let { localFolderName(it.path) }
                    val missing = !File(path).isDirectory
                    CodeMenuItem(name, { close(); d.directory = path; d.error = "" }, enabled = !missing,
                        trailing = if (missing) "不可用" else parent.takeIf { (names[name] ?: 0) > 1 }, checked = path == current)
                }
                CodeMenuDivider()
            }
            CodeMenuItem("打开文件夹…", { close(); SwingUtilities.invokeLater { chooseLocalFolder(w, d) } })
        }
    }
}

/** 本机路径的最后一段（`\` 和 `/` 都认）；盘符根目录（`C:\`）就用它本身。 */
internal fun localFolderName(path: String): String = File(path).name.ifBlank { path }

/** 「打开文件夹…」：系统的选目录对话框，选中的同时加进本地项目（同经典「添加项目」，所以也出现在「最近」和经典的项目列表里）。 */
private fun chooseLocalFolder(w: LocalWorkspace, d: LocalHomeDraft) {
    runCatching {
        val chooser = JFileChooser(d.directoryOr(w)).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = "打开文件夹" }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            val folder = chooser.selectedFile
            w.addProject(folder)
            d.directory = folder.canonicalPath; d.error = ""
        }
    }.onFailure { d.error = it.message.orEmpty().ifBlank { "无法打开这个文件夹" } }
}
