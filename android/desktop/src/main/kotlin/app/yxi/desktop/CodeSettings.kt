package app.yxi.desktop

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Code 风格的设置弹窗（M4q，规格表 §3.7）。外框（1024×800 居中、圆角 12、40% 黑遮罩）在 App.kt；这里是框里的两栏：
 * 左导航 192（页面底、右缘细线、内边距 12；顶上是搜索框，下面是分区，选中 #E3E3E2、高 32、步距 33），
 * 右边白底内容区（内边距 24、右上角关闭 ×、自己的滚动条）：分组标题 16 半粗，下面一行一项——左边标题（主色）+ 说明（弱色），右边控件，行间 #F3F3F3 细线。
 * 分区、各项的取值和动作同经典（[SettingsDialog]），「关于」里仍是经典的 [VersionSettings]。
 */

/** 经典里放在 [SettingsDialog] 局部变量上的状态和动作，Code 弹窗原样拿来用（不重读注册表、不另写一套保存逻辑）。 */
internal class CodeSettingsActions(
    val autostart: Boolean,
    val autostartAvailable: Boolean,
    val setAutostart: (Boolean) -> Unit,
    val retentionError: String,
    val setRetention: (String) -> Unit,
)

/**
 * 搜索用的索引：分区名之外，每个分区里各项的标题和选项名（以及几个常见说法）。导航按它筛分区（[settingsMatches]）；
 * 加了设置项要把标题补进来，否则搜不到（单测 [CodeSettingsTest] 核了外观页的几项）。
 */
internal val SETTINGS_KEYWORDS: Map<String, List<String>> = mapOf(
    "常规" to listOf("用户数据", "数据目录", "复制路径", "关闭窗口时留在托盘", "托盘", "开机自启", "启动时重连上次主机", "重连"),
    "外观" to listOf("界面风格", "经典", "Code 风格", "明暗", "主题", "跟随系统", "浅色", "深色", "对话默认视图", "普通", "思考", "详细",
        "对话字号", "字号", "字体大小", "对话宽度", "宽度", "窄", "宽"),
    "通知" to listOf("轮次完成", "需要你处理", "提醒", "只提醒置顶任务", "置顶"),
    "输入历史" to listOf("已结束输入正文保留", "保留", "清理", "一直保留"),
    "账号" to listOf("个人资料", "登录", "退出登录"),
    "模型与线路" to listOf("模型", "线路", "API Key"),
    "服务器配置与插件" to listOf("服务器", "配置", "插件", "MCP"),
    "快捷键" to listOf("键盘", "快捷方式"),
    "关于" to listOf("版本", "检查更新", "更新日志", "官网下载"),
)

/** 搜索框里是 [query] 时导航里留下的分区（顺序同 [SETTINGS_SECTIONS]）：分区名或它的任一关键词含 query（不分大小写，去掉首尾空白）；空串全留。 */
internal fun settingsMatches(query: String): List<String> {
    val q = query.trim()
    if (q.isEmpty()) return SETTINGS_SECTIONS
    return SETTINGS_SECTIONS.filter { section -> section.contains(q, ignoreCase = true) || SETTINGS_KEYWORDS[section].orEmpty().any { it.contains(q, ignoreCase = true) } }
}

/** 导航的分组：第一组不写组名（顶在搜索框下）；后两组组名弱色。 */
private val SETTINGS_GROUPS = listOf(
    null to listOf("常规", "外观", "通知", "输入历史"),
    "账号与连接" to listOf("账号", "模型与线路", "服务器配置与插件"),
    "其他" to listOf("快捷键", "关于"),
)

/**
 * 模态弹窗的键盘焦点（M4q 交叉审查）：打开时把焦点拿进来（先给 [initial]，拿不到给外框），Esc 关（[onClose]），
 * 焦点不许移出（Tab 走到头不会落回遮罩底下的输入框）。底下的输入框因此收不到按键——不然打字落进看不见的输入框，
 * 有待审批时 Esc 还会被它当成「拒绝」（ChatPane 的 Composer）。窗口层的全局快捷键先于这里，模态时由 [shortcut] 自己挡（[codeSettingsModal]）。
 */
@Composable
internal fun CodeModalFocus(onClose: () -> Unit, initial: FocusRequester?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val root = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (initial == null || runCatching { initial.requestFocus() }.isFailure) runCatching { root.requestFocus() }
    }
    Box(
        modifier.onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onClose(); true } else false }
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusRequester(root).focusTarget(),
    ) { content() }
}

@Composable
internal fun CodeSettingsDialog(state: AppState, select: (String) -> Unit, actions: CodeSettingsActions) {
    val t = Tokens.current
    var query by remember { mutableStateOf("") }
    val shown = settingsMatches(query)
    val search = remember { FocusRequester() }
    // 搜索命中但当前分区被筛掉了：换到第一个命中的、在弹窗里看的分区（跳页面的不自动点）
    LaunchedEffect(query) {
        if (query.isNotBlank() && state.settingsSection !in shown) shown.firstOrNull { it !in SETTINGS_JUMPS }?.let { state.settingsSection = it }
    }
    CodeModalFocus({ state.showSettings = false }, search, Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            val line = hairline()
            Column(
                Modifier.width(192.dp).fillMaxHeight().background(t.surface0)
                    .drawBehind { val x = size.width - line.toPx() / 2; drawLine(t.border, Offset(x, 0f), Offset(x, size.height), line.toPx()) }
                    .padding(12.dp),
            ) {
                CodeSettingsSearch(query, search) { query = it }
                Spacer(Modifier.height(19.8.dp))   // 规格 §3.7：搜索框 164–196，第二项 248.8–280.8、步距 33 → 第一项顶 215.8
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    SETTINGS_GROUPS.forEach { (group, items) ->
                        val visible = items.filter { it in shown }
                        if (visible.isEmpty()) return@forEach
                        if (group != null) Text(group, Modifier.padding(start = 10.dp, top = 12.dp, bottom = 4.dp), color = t.textMuted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
                        visible.forEach { CodeSettingsNavItem(it, it == state.settingsSection, jump = it in SETTINGS_JUMPS) { select(it) } }
                    }
                    if (shown.isEmpty()) Text("没有匹配的设置", Modifier.padding(start = 10.dp, top = 6.dp), color = t.textMuted, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().background(t.dialog)) {
                val scroll = rememberScrollState()
                // 控件右缘在 x 1501.6（规格 §3.7）= 弹窗右缘 1536 − 34.4：内边距 24 之外再给滚动条让出 10.4
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 24.dp, top = 24.dp, end = 34.4.dp, bottom = 24.dp)) {
                    CodeSettingsContent(state, actions)
                }
                CompositionLocalProvider(LocalScrollbarStyle provides ScrollbarStyle(
                    minimalHeight = 16.dp, thickness = 5.6.dp, shape = RoundedCornerShape(2.8.dp), hoverDurationMillis = 300,
                    unhoverColor = CodePalette.current.scrollbar, hoverColor = CodePalette.current.scrollbar,
                )) {
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 3.2.dp, top = 4.dp, bottom = 4.dp))
                }
                // 关闭 ×：中心距弹窗右、上各 28（规格 §3.7 中心 ≈(1508, 180)）
                CodeIconButton(Icons.Filled.Close, "关闭设置", Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp), iconSize = 16.dp,
                    tint = CodePalette.current.dropArrow) { state.showSettings = false }
            }
        }
    }
}

/** 分区的内容。标题 = 分区名；各项同经典的同名分区（「关于」原样用经典组件）。 */
@Composable
private fun CodeSettingsContent(state: AppState, actions: CodeSettingsActions) {
    val t = Tokens.current
    val section = state.settingsSection
    CodeSettingsTitle(section)
    // 设置弹窗盖住了侧栏底部，偏好没存上等提示要在这里说出来；常规页由「用户数据」那一项显示（同经典）
    if (section != "常规" && Store.warning.isNotBlank()) CodeSettingsNote(Store.warning, t.danger)
    when (section) {
        "常规" -> {
            CodeUserDataRow()
            CodeSettingRow("关闭窗口时留在托盘") {
                CodeSwitch(Store.pref("closeToTray", "1") == "1") { Store.setPref("closeToTray", if (it) "1" else "0") }
            }
            CodeSettingRow("开机自启", if (actions.autostartAvailable) null else "只在 Windows 上可用。", enabled = actions.autostartAvailable) {
                CodeSwitch(actions.autostart, enabled = actions.autostartAvailable, onChange = actions.setAutostart)
            }
            CodeSettingRow("启动时重连上次主机") {
                CodeSwitch(Store.pref("reconnectOnStart", "1") == "1") { Store.setPref("reconnectOnStart", if (it) "1" else "0") }
            }
        }
        "外观" -> {
            CodeSettingRow("界面风格", "经典是原来的工作台外观；Code 风格还在预览。", first = true) {
                CodeSegmented(listOf(UiStyle.Classic.key to UiStyle.Classic.label, UiStyle.Code.key to "${UiStyle.Code.label}（预览）"),
                    UiStyle.from(Store.pref(UiStyle.PREF, UiStyle.Classic.key)).key) { Store.setPref(UiStyle.PREF, it, "界面风格没存上") }
            }
            CodeSettingRow("明暗", "聊天背景使用固定状态色，无循环波纹或闪烁。") {
                CodeSegmented(listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"), Store.pref("theme", "system")) { Store.setPref("theme", it, "明暗没存上") }
            }
            // M3h：没单独切过的会话马上跟着变；切过的（Ctrl+O / 标题菜单）留在自己那档
            CodeSettingRow("对话默认视图", MacChrome.label("普通：不显示思考、工具只有一行摘要；思考：再显示思考；详细：工具调用全部展开。单个会话里用 Ctrl+O 临时切换。")) {
                CodeSegmented(TranscriptView.entries.map { it.key to it.label }, TranscriptView.of(Store.pref(TranscriptView.PREF, TranscriptView.Normal.key)).key) {
                    Store.setPref(TranscriptView.PREF, it, "对话视图没存上")
                }
            }
            // M4q：对话字号、对话宽度（[ChatFontSize] / [ChatWidth]）；只有 Code 风格有，经典的设置页不出现
            CodeSettingRow("对话字号", "对话里的文字大小，「中」是 14。输入框和侧栏不变。") {
                CodeSegmented(ChatFontSize.entries.map { it.key to it.label }, ChatFontSize.current.key) { Store.setPref(ChatFontSize.PREF, it, "对话字号没存上") }
            }
            CodeSettingRow("对话宽度", "同时管对话列和输入框；窗口放不下时自动收窄。") {
                CodeSegmented(ChatWidth.entries.map { it.key to it.label }, ChatWidth.current.key) { Store.setPref(ChatWidth.PREF, it, "对话宽度没存上") }
            }
        }
        "通知" -> {
            CodeSettingRow("轮次完成 / 需要你处理", "什么时候弹系统通知。", first = true) {
                CodeDropdown(listOf("always" to "始终", "unfocused" to "仅在未聚焦时", "never" to "从不"), Store.pref("notify", "unfocused")) { Store.setPref("notify", it) }
            }
            CodeSettingRow("只提醒置顶任务", "没有置顶时提醒全部。") {
                CodeSwitch(Store.pref("notifyOnlyPinned", "0") == "1") { Store.setPref("notifyOnlyPinned", if (it) "1" else "0") }
            }
        }
        "输入历史" -> {
            CodeSettingRow("已结束输入正文保留", "仅清理已结束的本机输入正文，保留去重记录；服务器对话不受影响。", first = true) {
                CodeDropdown(RETENTION_OPTIONS, Store.pref("inputRetentionDays", "0"), actions.setRetention)
            }
            if (actions.retentionError.isNotBlank()) CodeSettingsNote(actions.retentionError, t.danger)
        }
        else -> Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { VersionSettings() }
    }
}

/** 分组标题：16 半粗主色（规格 §3.7）；和右上角的关闭 × 同一条中线附近，右边留出 × 的位置。 */
@Composable
private fun CodeSettingsTitle(text: String) {
    Text(text, Modifier.padding(end = 32.dp, bottom = 8.dp), color = Tokens.current.textPrimary, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

/** 行外的提示（偏好没存上、清理失败）：13 / 18。 */
@Composable
private fun CodeSettingsNote(text: String, color: Color) {
    Text(text, Modifier.padding(vertical = 6.dp), color = color, fontSize = 13.sp, lineHeight = 18.sp)
}

/**
 * 一行设置：左边标题（14 / 20 主色）+ 说明（14 / 20 弱色，标题到说明步距 24），右边 [control]，上方 #F3F3F3 细线（[first] 不画）。
 * 上下内边距 12.4：标题 + 一行说明正好 68.8；只有标题时撑到 56（规格 §3.7）。[enabled] 为 false 时标题也变弱色。
 */
@Composable
private fun CodeSettingRow(title: String, detail: String? = null, first: Boolean = false, enabled: Boolean = true, control: @Composable () -> Unit) {
    val t = Tokens.current
    val sep = CodePalette.current.menuHover
    val line = hairline()
    Row(
        Modifier.fillMaxWidth().then(if (first) Modifier else Modifier.drawBehind { drawLine(sep, Offset(0f, line.toPx() / 2), Offset(size.width, line.toPx() / 2), line.toPx()) })
            .heightIn(min = 56.dp).padding(vertical = 12.4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = if (enabled) t.textPrimary else t.textMuted, fontSize = 14.sp, lineHeight = 20.sp)
            if (detail != null) Text(detail, color = t.textMuted, fontSize = 14.sp, lineHeight = 20.sp)
        }
        control()
    }
}

/** 常规页的「用户数据」：说明 + 目录路径，右边「打开数据目录」「复制路径」（动作同经典的 [UserDataSettings]），出错和偏好警告写在行下。 */
@Composable
private fun CodeUserDataRow() {
    val t = Tokens.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    CodeSettingRow("用户数据", "保存登录凭据、服务器记录、会话整理和偏好。Windows 凭据由当前系统账号加密保护。\n" + Store.dir.absolutePath, first = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CodeSettingsButton("打开数据目录") {
                scope.launch {
                    try { withContext(Dispatchers.IO) { java.awt.Desktop.getDesktop().open(Store.dir) }; error = "" }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = "无法打开目录：${e.message.orEmpty()}" }
                }
            }
            CodeSettingsButton("复制路径") { clipboard.setText(AnnotatedString(Store.dir.absolutePath)) }
        }
    }
    if (Store.warning.isNotBlank()) CodeSettingsNote(Store.warning, t.warning)
    if (error.isNotBlank()) CodeSettingsNote(error, t.danger)
}

/**
 * 导航顶上的搜索框：#FEFEFD 底、控件边框细线、高 32、圆角 8；放大镜 + 占位「搜索」弱色。按分区名和各项标题筛导航（[settingsMatches]）。
 * 弹窗打开时焦点落在这里（[focus]，见 [CodeModalFocus]）。
 */
@Composable
private fun CodeSettingsSearch(query: String, focus: FocusRequester, onChange: (String) -> Unit) {
    val t = Tokens.current
    val p = CodePalette.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().height(32.dp).background(p.searchFill, shape).border(hairline(), p.controlBorder, shape).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, Modifier.size(14.dp), tint = t.textMuted)
        Spacer(Modifier.width(8.dp))
        val style = TextStyle(color = t.textPrimary, fontSize = 14.sp, lineHeight = 20.sp)
        BasicTextField(query, onChange, Modifier.weight(1f).focusRequester(focus), singleLine = true, textStyle = style, cursorBrush = SolidColor(t.textPrimary),
            decorationBox = { inner -> Box { if (query.isEmpty()) Text("搜索", color = t.textMuted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1); inner() } })
    }
}

/**
 * 导航项：高 32、圆角 9.5、字 14；选中 #E3E3E2 底、主色字，其余次色，hover 用浮层项的浅底。[jump]：点了去别的页面，右端一个弱色「›」。
 * 语义：在弹窗里换内容的是 Tab，带选中态（selectable）；跳页面的是 Button。
 */
@Composable
internal fun CodeSettingsNavItem(label: String, selected: Boolean, jump: Boolean, onClick: () -> Unit) {
    val t = Tokens.current
    val p = CodePalette.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(9.5.dp))
            .background(if (selected) p.navSelected else if (hovered) p.menuHover else Color.Transparent)
            .hoverable(source)
            .then(if (jump) Modifier.clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
                else Modifier.selectable(selected, interactionSource = source, indication = null, role = Role.Tab, onClick = onClick))
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = if (selected) t.textPrimary else t.textSecondary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (jump) Icon(Icons.Filled.ChevronRight, null, Modifier.size(14.dp), tint = p.dropArrow)
    }
}

/**
 * 分段控件（规格 §3.7）：轨道 #F3F3F3、高 32、圆角 8.7；滑块白、控件边框细线、圆角 5，内缩 2，宽度跟着文字；
 * 字 14，选中主色、其余弱色。[options] = 存的值 to 显示名。语义是一组单选（selectableGroup + 每格 RadioButton，带选中态）。
 */
@Composable
internal fun CodeSegmented(options: List<Pair<String, String>>, value: String, onSelect: (String) -> Unit) {
    val t = Tokens.current
    val p = CodePalette.current
    val thumb = RoundedCornerShape(5.dp)
    Row(Modifier.height(32.dp).clip(RoundedCornerShape(8.7.dp)).background(p.menuHover).selectableGroup().padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
        options.forEach { (key, label) ->
            val selected = key == value
            Box(
                Modifier.fillMaxHeight().then(if (selected) Modifier.background(p.segmentThumb, thumb).border(hairline(), p.controlBorder, thumb) else Modifier)
                    .selectable(selected, remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { onSelect(key) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (selected) t.textPrimary else t.textMuted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1)
            }
        }
    }
}

/** 带框的下拉（规格 §3.7）：高 32、控件边框细线、圆角 6.5，当前值 14 主色 + 「∨」；点开是 Code 浮层，当前项打勾。 */
@Composable
internal fun CodeDropdown(options: List<Pair<String, String>>, value: String, onSelect: (String) -> Unit) {
    val t = Tokens.current
    val p = CodePalette.current
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.5.dp)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box {
        Row(
            Modifier.height(32.dp).clip(shape).background(if (hovered || open) p.menuHover else p.popup).border(hairline(), p.controlBorder, shape)
                .hoverable(source).clickable(interactionSource = source, indication = null, role = Role.DropdownList) { open = true }
                .padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.firstOrNull { it.first == value }?.second ?: value, color = t.textPrimary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(14.dp), tint = p.dropArrow)
        }
        if (open) CodePopup({ open = false }, alignEnd = true, width = 160.dp, maxWidth = 240.dp) {
            options.forEach { (key, label) -> CodeMenuItem(label, { open = false; onSelect(key) }, checked = key == value) }
        }
    }
}

/**
 * 开关：35.6 × 20，胶囊轨道，白钮 16、内缩 2。关：轨道 #CBCBCB（规格 §3.7 实测 #C8C8C8～#CDCDCD 取中），钮在左。
 * 开：参考端没拍到（规格 §6），Yxi 定为强调蓝 #2A78D6 轨道、钮在右——同参照里「勾选」的颜色（分支下拉的对勾、worktree 勾选框的填充，§1.4），PRD §14.1 记为偏差。
 * 不可用时整体 50% 透明。
 */
@Composable
internal fun CodeSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val p = CodePalette.current
    Box(
        Modifier.size(35.6.dp, 20.dp).alpha(if (enabled) 1f else 0.5f).clip(CircleShape).background(if (checked) p.focusBlue else p.switchOff)
            .toggleable(checked, remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    ) {
        Box(Modifier.offset(x = if (checked) 17.6.dp else 2.dp, y = 2.dp).size(16.dp).background(Color.White, CircleShape))
    }
}

/** 设置里的次按钮：高 32、控件边框细线、圆角 6.5、左右 12，字 14 主色；hover 浅底。 */
@Composable
private fun CodeSettingsButton(label: String, onClick: () -> Unit) {
    val t = Tokens.current
    val p = CodePalette.current
    val shape = RoundedCornerShape(6.5.dp)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier.height(32.dp).clip(shape).background(if (hovered) p.menuHover else p.popup).border(hairline(), p.controlBorder, shape)
            .hoverable(source).clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = t.textPrimary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1)
    }
}
