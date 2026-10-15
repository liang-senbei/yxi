package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.yxi.agent.ConfigRemote
import app.yxi.agent.Slash
import org.json.JSONObject

/*
 * Code 风格输入框的斜杠命令菜单（M4k，PRD 输入卡「斜杠命令」）：草稿整条是「/」加一个还没打完的词时，在输入卡上方列出能补的命令。
 * 只提示、只补全，不执行：补进草稿的「/命令 」照常发送，由运行器自己认（SSH 会话原样打进 CLI，本机 Claude / ACP 当提示词发）。
 * 有真实来源才列：SSH 上的 Claude 会话 = 内置 + 这台主机的命令和技能；本机 Claude = initialize 回包里的 commands（没有时用内置）；
 * ACP = 运行器发的 available_commands_update。Codex（SSH / 本机 / 服务器）和 OpenCode 没有来源，不弹。经典外观不弹。
 */

/** 菜单里的一条：[name] 不带「/」（插件带来的写成「插件:名字」）；[hint] 是一句说明，可空。 */
internal data class SlashCommand(val name: String, val hint: String = "")

internal object SlashCommands {
    /** 一份清单最多收这么多条（运行器 / 服务器给的数据只信到这里）。 */
    private const val MAX = 500
    private val warning = Regex("\\s*⚠️\\s*")
    private val spaces = Regex("\\s+")

    /** Claude Code 的内置命令：core 的 [Slash.ALL]（顺序 = 常用度）。说明去掉手机上的 ⚠️（Code 外观的菜单里不放 emoji）。 */
    fun builtIns(): List<SlashCommand> = Slash.ALL.map { SlashCommand(it.name, it.hint.replace(warning, "")) }

    /** 命令名能用：非空、不长于 100、不带空白和控制字符。 */
    private fun valid(name: String) = name.isNotEmpty() && name.length <= 100 && name.none { it.isWhitespace() || it < ' ' }

    /** 说明压成一行、最长 200。 */
    private fun line(text: String) = text.replace(spaces, " ").trim().take(200)

    /** [base] 在前，[extra] 里和前面同名（不分大小写）的不再列；名字不合规的丢掉。 */
    fun merge(base: List<SlashCommand>, extra: List<SlashCommand>): List<SlashCommand> {
        val seen = HashSet<String>()
        return (base + extra).filter { valid(it.name) && seen.add(it.name.lowercase()) }.take(MAX)
    }

    /**
     * 运行器自己报了命令表（本机 Claude 的 initialize）时以它为准：SDK 模式只报能在非交互下跑的命令，内置里它没报的（/config、/doctor…）发了也跑不了。
     * 报了的内置命令排前面、用内置的中文说明，其余按运行器的顺序接在后面。没报（[reported] 为 null，老版本运行器或还没连上）就用内置。
     */
    fun forRunner(builtIns: List<SlashCommand>, reported: List<SlashCommand>?): List<SlashCommand> {
        if (reported == null) return builtIns
        val names = reported.map { it.name.lowercase() }.toSet()
        return merge(builtIns.filter { it.name.lowercase() in names }, reported)
    }

    /** 草稿整条是「/词」（还没打空格、没换行）时返回那个词（可以是空的），否则 null。 */
    fun word(draft: String): String? {
        if (!draft.startsWith("/")) return null
        return draft.substring(1).takeIf { word -> word.none { it.isWhitespace() } }
    }

    /**
     * [draft] 下要列的命令：按前缀筛，不分大小写；「插件:名字」冒号后那段也算前缀。顺序照 [commands]，只把和词一模一样的那条提到最前。
     * 打全了、又只剩它一条时不列（没东西可补，回车直接发）；还有更长的同前缀命令时照列。
     */
    fun suggest(commands: List<SlashCommand>, draft: String): List<SlashCommand> {
        val word = word(draft) ?: return emptyList()
        val hits = commands.filter { it.name.startsWith(word, true) || it.name.substringAfterLast(':', "").startsWith(word, true) }
        val exact = hits.filter { it.name.equals(word, true) }
        if (exact.isNotEmpty() && hits.size == exact.size) return emptyList()
        return exact + (hits - exact.toSet())
    }

    /**
     * 服务器配置树（[ConfigRemote.load]）里 Claude Code 的斜杠命令和技能（技能也能用 /名字 调）。用户自己的写原名；
     * 插件带来的写成「插件:名字」—— Claude Code 认这种带命名空间的写法，重名时也不会调错。
     */
    fun fromConfig(tools: List<ConfigRemote.Tool>): List<SlashCommand> {
        val claude = tools.firstOrNull { it.key == "claude" } ?: return emptyList()
        val rows = listOf("commands" to "命令", "skills" to "技能").flatMap { (key, kind) ->
            claude.cats.firstOrNull { it.key == key }?.items.orEmpty().map { item ->
                val plugin = item.from.substringBefore('@')
                if (plugin.isBlank()) SlashCommand(item.title, "自定义$kind") else SlashCommand("$plugin:${item.title}", "插件 $plugin 的$kind")
            }
        }
        return merge(emptyList(), rows)
    }

    /** ACP 的 available_commands_update（availableCommands[]：name、description；input.hint 不用）。不是这个更新或没有列表时返回 null。 */
    fun fromAcp(update: JSONObject): List<SlashCommand>? {
        if (update.optString("sessionUpdate") != "available_commands_update") return null
        val rows = update.optJSONArray("availableCommands") ?: return null
        return merge(emptyList(), (0 until minOf(rows.length(), MAX)).mapNotNull { rows.optJSONObject(it) }
            .map { SlashCommand(it.optString("name").trim().removePrefix("/"), line(it.optString("description"))) })
    }

    /**
     * 本机 Claude 控制协议 initialize 回包里的 commands（Claude Code 2.1 的 SDK 回包：name、description、argumentHint、aliases…，只取前两项）。
     * 没有这个字段时返回 null（老版本运行器），交给 [forRunner] 用内置。
     */
    fun fromClaudeInitialize(response: JSONObject): List<SlashCommand>? {
        val rows = response.optJSONArray("commands") ?: return null
        return merge(emptyList(), (0 until minOf(rows.length(), MAX)).mapNotNull { rows.optJSONObject(it) }
            .map { SlashCommand(it.optString("name").trim().removePrefix("/"), line(it.optString("description"))) })
    }
}

/**
 * SSH 主机上的命令和技能（[SlashCommands.fromConfig]），按主机记在进程里：第一次在 Claude 会话的输入框里打「/」时读一次
 * （和配置页同一条 [ConfigRemote.load] 命令），配置页读过的也顺带记下。打字不会多出往返；读空（没连上、没 python3）一分钟内不重试。
 * 只在界面线程上用。
 */
internal object RemoteSlashCommands {
    private val byHost = mutableStateMapOf<String, List<SlashCommand>>()
    private val loading = HashSet<String>()
    private val emptyAt = HashMap<String, Long>()

    fun get(hostId: String): List<SlashCommand> = byHost[hostId].orEmpty()

    /** 记下一次读取的结果；读空不覆盖已有的，只记时间。 */
    fun put(hostId: String, tools: List<ConfigRemote.Tool>) {
        if (tools.isEmpty()) { emptyAt[hostId] = System.currentTimeMillis(); return }
        byHost[hostId] = SlashCommands.fromConfig(tools)
    }

    suspend fun ensure(hostId: String, load: suspend () -> List<ConfigRemote.Tool>) {
        if (hostId in byHost || hostId in loading) return
        if (System.currentTimeMillis() - (emptyAt[hostId] ?: 0L) < 60_000) return
        loading += hostId
        try { put(hostId, load()) } finally { loading -= hostId }
    }
}

/** 斜杠菜单的状态：[choices] 为空时不画、[handle] 不接键。[selected] 是高亮行，[choose] 把那条补进草稿，[dismiss] 收起（同 Esc）。 */
internal class SlashMenu(
    val choices: List<SlashCommand>, val selected: Int,
    val choose: (Int) -> Unit, val select: (Int) -> Unit, val dismiss: () -> Unit, val handle: (KeyEvent) -> Boolean,
)

/** 菜单键盘语义（纯函数，JVM 可单测）：Tab 和 Enter 一样接受候选（Shift+Tab 放行），其余同 [handleMentionKey]（↑↓ 环绕、Esc 收起、组字时全部放行）。 */
internal fun handleSlashKey(
    type: KeyEventType, key: Key, isShiftPressed: Boolean,
    count: Int, compositionActive: Boolean, current: Int,
    move: (Int) -> Unit, accept: () -> Unit, dismiss: () -> Unit,
): Boolean {
    if (type == KeyEventType.KeyDown && !compositionActive && count > 0 && key == Key.Tab && !isShiftPressed) { accept(); return true }
    return handleMentionKey(type, key, isShiftPressed, count, compositionActive, current, move, accept, dismiss)
}

/**
 * 草稿跟着 [commands] 筛出的菜单；Esc / 点外面收起后，草稿再变才重新弹（草稿不再以「/」开头时忘掉收起过的那次）。输入法组字时不弹。
 * 选中一条 = 草稿换成「/命令 」、光标放末尾。
 */
@Composable
internal fun rememberSlashMenu(commands: List<SlashCommand>, draft: TextFieldValue, update: (TextFieldValue) -> Unit): SlashMenu {
    var dismissed by remember { mutableStateOf<String?>(null) }
    val typing = SlashCommands.word(draft.text) != null
    LaunchedEffect(typing) { if (!typing) dismissed = null }
    val composing = draft.composition != null
    val choices = if (!composing && dismissed != draft.text) SlashCommands.suggest(commands, draft.text) else emptyList()
    var selected by remember(choices) { mutableStateOf(0) }
    fun choose(index: Int) {
        val command = choices.getOrNull(index) ?: return
        val text = "/${command.name} "
        update(TextFieldValue(text, TextRange(text.length)))
    }
    val dismiss = { dismissed = draft.text }
    return SlashMenu(choices, selected, ::choose, { selected = it }, dismiss) { event ->
        handleSlashKey(event.type, event.key, event.isShiftPressed, choices.size, composing, selected,
            move = { selected = it }, accept = { choose(selected) }, dismiss = dismiss)
    }
}

/** 菜单最多露的行数：8 × 24 + 上下内边距 8 = 200，多了在里面滚。 */
private const val SlashMenuRows = 8

/**
 * 斜杠菜单的浮层（规格 §3.1 的浮层和单行项；参照端的这个菜单没量到，尺寸按通用规则）：贴着输入卡上方、左右和卡片对齐，
 * 底边离卡片顶 6（浮层离触发控件 ≈5.6～6.4）；白底 r10、内边距 4、外环 + 两层阴影，同 [CodePopup]。
 * 行高 24、圆角 6，字墨起于填充左 +12.8（外层 4 + 自身 8.8）：「/命令」14 主色，说明 13 弱色、隔 12 跟在后面，放不下省略。
 * 高亮行铺菜单 hover 底（#F3F3F3），鼠标移上去就换高亮；最多露 [SlashMenuRows] 行，高亮行跟着滚进来。
 * 浮层不拿焦点：按键留在输入框、由 [SlashMenu.handle] 接；点一条补进草稿后焦点还给 [focus]，点外面收起。
 * 不占 [NativeOverlay]：浮层只在输入卡正上方，盖不到右侧的网页预览 / 终端，打「/」时不必把它们藏起来。
 * 要放在 [CodeComposerCard] 的 top 槽最前面：锚点是卡片，0 高、和卡片同宽。
 */
@Composable
internal fun CodeSlashMenu(menu: SlashMenu, focus: FocusRequester) {
    if (menu.choices.isEmpty()) return
    val p = CodePalette.current
    val t = Tokens.current
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    val shape = RoundedCornerShape(10.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth
        Popup(
            popupPositionProvider = remember(gap) { CodePopupPosition(PopupSide.Above, false, gap) },
            onDismissRequest = menu.dismiss,
            properties = PopupProperties(focusable = false),
        ) {
            val list = rememberLazyListState()
            LaunchedEffect(menu.selected, menu.choices) {
                val first = list.firstVisibleItemIndex
                if (menu.selected < first) list.scrollToItem(menu.selected)
                else if (menu.selected >= first + SlashMenuRows) list.scrollToItem(menu.selected - SlashMenuRows + 1)
            }
            LazyColumn(
                Modifier.width(width).heightIn(max = (SlashMenuRows * 24 + 8).dp).codeShadow(10.dp, PopupShadow)
                    .background(p.popup, shape).border(1.dp, p.popupRing, shape).padding(4.dp),
                state = list,
            ) {
                items(menu.choices.size) { index ->
                    val command = menu.choices[index]
                    val source = remember { MutableInteractionSource() }
                    val hovered by source.collectIsHoveredAsState()
                    LaunchedEffect(hovered) { if (hovered) menu.select(index) }
                    Row(
                        Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (index == menu.selected) p.menuHover else Color.Transparent)
                            .hoverable(source)
                            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                                menu.choose(index); runCatching { focus.requestFocus() }
                            }
                            .padding(start = 8.8.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("/" + command.name, color = t.textPrimary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (command.hint.isNotBlank()) Text(command.hint, Modifier.padding(start = 12.dp).weight(1f, fill = false), color = t.textMuted,
                            fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
