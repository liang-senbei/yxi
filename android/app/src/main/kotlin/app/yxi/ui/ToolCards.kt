package app.yxi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.yxi.agent.ChatItem
import app.yxi.agent.Pending
import app.yxi.ui.theme.*
import com.mikepenz.markdown.m3.Markdown
import org.json.JSONArray
import org.json.JSONObject

private val Pill = RoundedCornerShape(100.dp)
private val Mono = FontFamily.Monospace

/**
 * 工具卡片 —— **按工具定制**。
 *
 * 排序理由是实测的调用次数（扫了本机 145 个转录、294MB）：
 * Bash 5536 · Edit 935 · WebFetch 890 · Read 679 · WebSearch 433 · Write 254 · Agent 148。
 * 所以 Bash 和 Edit 值得单独做，剩下的共用一个朴素卡片就够。
 *
 * ⚠️ **富渲染靠 `toolUseResult`（[ChatItem.ToolCall.meta]），不是靠 `tool_result.content`。**
 * content 是给模型看的扁文本（stdout/stderr 混在一起、Read 带 `cat -n` 行号）；
 * meta 才有结构（`structuredPatch`、分开的 stdout/stderr、`answers`）。
 * 但 meta **可能为 null**（老转录 / 子 agent 转录里很常见），所以每条路都要有降级。
 */
@Composable
fun ToolCard(c: ChatItem.ToolCall) {
    // ⚠️ **默认折叠成一行。** 一个回合里 Bash/Read 动辄十几条，全铺开的话
    // 正文（Claude 到底说了什么）被挤得几乎看不见 —— 用户原话：
    // 「全都是 bash 和 read 这些可读性太差」。
    //
    // 两个例外**不折叠**，它们不是噪音：
    //   · 出错的 —— 失败才是你要看的那条
    //   · 要你拿主意的（AskUserQuestion / ExitPlanMode）
    val alwaysOpen = c.name == "AskUserQuestion" || c.name == "ExitPlanMode" || c.name == "TodoWrite"
    var open by remember(c.key) { mutableStateOf(alwaysOpen || c.isError) }
    Surface(color = SurfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(
            Modifier.fillMaxWidth().clickable { open = !open }
                // 折叠时收紧留白 —— 十几张卡片各多 6dp，加起来就是一屏
                .padding(16.dp, if (open) 13.dp else 9.dp),
        ) {
            Header(c, open)
            if (!open) return@Column
            when (c.name) {
                "Bash" -> BashBody(c, true)
                "Edit" -> EditBody(c, true)
                "Write" -> WriteBody(c, true)
                "Read" -> ReadBody(c)
                "Agent", "Task" -> AgentBody(c, true)
                "AskUserQuestion" -> AskBody(c)
                "ExitPlanMode" -> PlanBody(c, true)
                "TodoWrite" -> TodoBody(c)
                else -> PlainBody(c, true)
            }
        }
    }
}

/**
 * 折叠时那一行摘要 —— **必须能认出「这是哪一条」**，否则折叠就等于全删了。
 * 命令取第一行、文件取文件名（全路径在手机上一行也放不下）。
 */
private fun summary(c: ChatItem.ToolCall): String {
    val i = c.input
    if (i.has("todos")) {
        val a = i.optJSONArray("todos"); val n = a?.length() ?: 0
        val done = (0 until n).count { a?.optJSONObject(it)?.optString("status") == "completed" }
        return "$done/$n"
    }
    val raw = when {
        i.has("command") -> i.optString("command")
        i.has("file_path") -> i.optString("file_path").substringAfterLast('/')
        i.has("pattern") -> i.optString("pattern")
        i.has("description") -> i.optString("description")
        i.has("prompt") -> i.optString("prompt")
        i.has("path") -> i.optString("path").substringAfterLast('/')
        i.has("url") -> i.optString("url")
        else -> i.keys().asSequence().firstOrNull()?.let { i.optString(it) }.orEmpty()
    }
    return raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
}

/**
 * TodoWrite 渲染成真待办清单 ☐ 待办 / ▶ 正在做 / ☑ 完成。
 * 比 Bash/Read 那些高信号 —— 一眼看清 Claude 的计划和进度。默认展开。
 */
@Composable
private fun TodoBody(c: ChatItem.ToolCall) {
    val todos = c.input.optJSONArray("todos") ?: return
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (idx in 0 until todos.length()) {
            val o = todos.optJSONObject(idx) ?: continue
            val status = o.optString("status")
            val content = o.optString("content").ifBlank { o.optString("activeForm") }
            val done = status == "completed"
            val (mark, markColor) = when (status) {
                "completed" -> "☑" to Muted           // ☑
                "in_progress" -> "▶" to Copper         // ▶
                else -> "☐" to MaterialTheme.colorScheme.onSurfaceVariant   // ☐
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(mark, style = MaterialTheme.typography.bodyMedium, color = markColor)
                Text(
                    content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (done) Muted else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
            }
        }
    }
}

/** ⚠️ `@Composable`：配色跟着风格走（[app.yxi.ui.Skin]），要读当前 Palette。 */
@androidx.compose.runtime.Composable
private fun accent(name: String) = when (name) {
    "Bash" -> Copper
    "Edit", "Write" -> Teal
    "AskUserQuestion", "ExitPlanMode" -> Amber   // 这两个本来就是「要你拿主意」的
    "TodoWrite" -> Teal   // 计划/进度
    else -> Muted
}

@Composable
private fun Header(c: ChatItem.ToolCall, open: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(c.name, style = MaterialTheme.typography.labelLarge, color = accent(c.name))
        if (!open) {
            Text(
                summary(c),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                color = Dim,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        val code = c.result?.let { EXIT.find(it)?.groupValues?.get(1) }
        when {
            // ⚠️ 退出码只在**失败**的输出开头有 `Exit code N`；成功时压根没有这个字段，
            // 隐含是 0。所以别指望能显示「exit 0」——那是编出来的。
            code != null -> Chip("exit $code", MaterialTheme.colorScheme.error)
            c.isError -> Chip(t("出错"), MaterialTheme.colorScheme.error)
            c.result != null -> Text(t("完成"), style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = Dim)
            else -> Chip(t("进行中"), Amber)
        }
    }
}

private val EXIT = Regex("""^Exit code (\d+)""")

@Composable
private fun Chip(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.16f), shape = Pill) {
        Text(text, Modifier.padding(9.dp, 3.dp), style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = color)
    }
}

@Composable
private fun Code(text: String, color: Color = OnSurfaceVariant, max: Int = Int.MAX_VALUE) = Text(
    text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = color, maxLines = max,
)

@Composable
private fun Block(content: @Composable ColumnScope.() -> Unit) = Surface(
    color = SurfaceContainerLowest, shape = MaterialTheme.shapes.medium,
    modifier = Modifier.padding(top = 9.dp).fillMaxWidth(),
) { Column(Modifier.padding(12.dp), content = content) }

// ——— Bash：命令 + 输出，stdout/stderr 分开 ———

@Composable
private fun BashBody(c: ChatItem.ToolCall, open: Boolean) {
    // 命令不折行、横着滚 —— 折行会把长管道拆得没法读
    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
        Code(c.input.optString("command"), OnSurface)
    }
    val m = c.meta
    val stdout = m?.optString("stdout").orEmpty().ifEmpty { c.result.orEmpty() }
    val stderr = m?.optString("stderr").orEmpty()
    AnimatedVisibility(open && (stdout.isNotBlank() || stderr.isNotBlank())) {
        Block {
            if (stdout.isNotBlank()) Code(stdout.take(4000), Muted)
            if (stderr.isNotBlank()) {
                if (stdout.isNotBlank()) Spacer(Modifier.height(8.dp))
                Code(stderr.take(2000), DiffDelFg)
            }
        }
    }
    if (!open) Preview(stdout.ifBlank { stderr })
}

@Composable
private fun Preview(text: String) {
    val t = text.trim().lineSequence().firstOrNull { it.isNotBlank() } ?: return
    Text(t.take(120), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = Dim, maxLines = 1)
}

// ——— Edit：真 diff ———

@Composable
private fun EditBody(c: ChatItem.ToolCall, open: Boolean) {
    Path(c.input.optString("file_path"))
    val patch = c.meta?.optJSONArray("structuredPatch")
    if (patch != null && patch.length() > 0) {
        val lines = hunkLines(patch)
        Diff(if (open) lines else lines.take(6))
        if (!open && lines.size > 6) Text(t("…还有 %d 行").format(lines.size - 6), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = Dim)
    } else {
        // meta 没有（老转录）→ 退回原始的 old/new 两段，照样能看出改了什么
        AnimatedVisibility(open) {
            Block {
                Code(("- " + c.input.optString("old_string")).take(1200), DiffDelFg)
                Spacer(Modifier.height(8.dp))
                Code(("+ " + c.input.optString("new_string")).take(1200), DiffAddFg)
            }
        }
    }
}

/** `structuredPatch[].lines` 是带 ` `/`-`/`+` 前缀的 unified-diff 行，直接用。 */
private fun hunkLines(patch: JSONArray): List<String> = buildList {
    for (i in 0 until patch.length()) {
        val ls = patch.optJSONObject(i)?.optJSONArray("lines") ?: continue
        for (j in 0 until ls.length()) add(ls.optString(j))
    }
}

@Composable
private fun Diff(lines: List<String>) = Block {
    lines.forEach { l ->
        val (fg, bg) = when (l.firstOrNull()) {
            '+' -> DiffAddFg to DiffAddBg
            '-' -> DiffDelFg to DiffDelBg
            else -> Dim to Color.Transparent
        }
        Text(
            l.ifBlank { " " },
            Modifier.fillMaxWidth().background(bg).padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
            color = fg, maxLines = 1,
        )
    }
}

// ——— Write / Read / Agent ———

@Composable
private fun WriteBody(c: ChatItem.ToolCall, open: Boolean) {
    Path(c.input.optString("file_path"))
    val kind = c.meta?.optString("type")
    if (kind == "update") Text(t("覆盖已有文件"), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = Amber)
    AnimatedVisibility(open) { Block { Code(c.input.optString("content").take(3000), Muted) } }
}

@Composable
private fun ReadBody(c: ChatItem.ToolCall) {
    Path(c.input.optString("file_path"))
    val f = c.meta?.optJSONObject("file")
    val note = when {
        c.meta?.optString("type") == "image" -> t("图片 ") + (f?.optJSONObject("dimensions")?.let { "${it.optInt("originalWidth")}×${it.optInt("originalHeight")}" } ?: "")
        f != null -> "${f.optInt("numLines")} 行 / 共 ${f.optInt("totalLines")} 行"
        else -> null
    }
    note?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = Dim) }
}

@Composable
private fun AgentBody(c: ChatItem.ToolCall, open: Boolean) {
    Text(c.input.optString("description"), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant)
    val t = c.input.optString("subagent_type")
    val async = c.meta?.optBoolean("isAsync") == true
    Text(
        listOfNotNull(t.takeIf { it.isNotBlank() }, if (async) t("后台跑") else null).joinToString(" · "),
        Modifier.padding(top = 3.dp), style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = Dim,
    )
    // 异步启动时 result 只是句「已启动」的元数据，展开也没内容可看，别浪费一次点击
    AnimatedVisibility(open && !async && c.result != null) { Block { Code(c.result.orEmpty().take(4000), Muted) } }
}

@Composable
private fun Path(p: String) {
    if (p.isBlank()) return
    Row(Modifier.padding(top = 7.dp).horizontalScroll(rememberScrollState())) { Code(p, OnSurface) }
}

// ——— 已经答过的 AskUserQuestion / ExitPlanMode ———

/**
 * 已答的问题：显示问了什么、你选了什么。
 * ⚠️ 待答的问题**不在转录里**（`tool_use` 要等工具跑完才落盘），
 * 那种走 [PendingCard]，数据来自屏幕。
 */
@Composable
private fun AskBody(c: ChatItem.ToolCall) {
    val qs = c.input.optJSONArray("questions") ?: return
    // ⚠️ answers 的键是**问题原文**，不是下标；多选的答案是 `", "` 拼起来的**一个字符串**
    val answers = c.meta?.optJSONObject("answers")
    for (i in 0 until qs.length()) {
        val q = qs.optJSONObject(i) ?: continue
        val text = q.optString("question")
        Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
        val a = answers?.optString(text).orEmpty()
        if (a.isNotBlank()) {
            Surface(color = CopperContainer, shape = Pill, modifier = Modifier.padding(top = 6.dp)) {
                Text(a, Modifier.padding(12.dp, 5.dp), style = MaterialTheme.typography.labelMedium, color = OnCopperContainer)
            }
        } else if (c.isError) {
            Text(t("你拒绝了"), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = Dim)
        }
    }
}

@Composable
private fun PlanBody(c: ChatItem.ToolCall, open: Boolean) {
    // ⚠️ 用户可能在批准前**改过计划**，所以最终版在 result 里不在 input 里
    val plan = c.meta?.optString("plan").orEmpty().ifBlank { c.input.optString("plan") }
    Text(if (c.isError) t("计划被否了") else t("计划"), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, color = Amber)
    Block {
        if (open) Markdown(plan, typography = yxiMarkdown(), modifier = Modifier.fillMaxWidth())
        else Text(plan.lineSequence().filter { it.isNotBlank() }.take(4).joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
private fun PlainBody(c: ChatItem.ToolCall, open: Boolean) {
    summarize(c)?.let { Text(it, Modifier.padding(top = 7.dp), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono), color = OnSurfaceVariant, maxLines = if (open) 40 else 2) }
    AnimatedVisibility(open && c.result != null) { Block { Code(c.result.orEmpty().take(4000), Muted) } }
}

private fun summarize(c: ChatItem.ToolCall): String? = when (c.name) {
    "WebFetch" -> c.input.optString("url")
    "WebSearch" -> c.input.optString("query")
    "Skill" -> c.input.optString("skill")
    else -> c.input.toString().takeIf { it.length > 2 }?.take(200)
}?.takeIf { it.isNotBlank() }

// ——— 待答的提示：唯一能点的卡片 ———

/**
 * **此刻正在等你**的那个选择器，数据来自 `tmux capture-pane`。
 * 琥珀色 —— 全 app 只有「需要你动手」才用这个颜色（PRD 附录 J.1）。
 *
 * ⚠️ **卡片上显示几号，点下去就送几号。** 不要改成按下标送键：
 * 一旦屏幕顺序和列表顺序对不上，就会**点 A 选中 B 且不报错**。
 */
@Composable
fun PendingCard(
    p: Pending,
    busy: Boolean,
    onPick: (Pending.Option) -> Unit,
    onSubmit: () -> Unit,
    onDiff: (() -> Unit)? = null,
    /** 回到上一题（送 ←）。多问题时才有意义。 */
    onPrev: (() -> Unit)? = null,
    /** 去下一题（送 →）。 */
    onNext: (() -> Unit)? = null,
) {
    Surface(color = SurfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp, 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).background(Amber, Pill))
                Text(if (p.multiSelect) t("等你选（可多选）") else t("等你选"), style = MaterialTheme.typography.labelMedium, color = Amber)
                Spacer(Modifier.weight(1f))
                // 审批前看一眼 Claude 改了什么 —— 看得见改动，「允许提交/编辑」才是知情的
                onDiff?.let {
                    Surface(color = SurfaceContainer, shape = Pill, modifier = Modifier.clip(Pill).clickable(onClick = it)) {
                        Text(t("看改动"), Modifier.padding(12.dp, 5.dp),
                            style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
                    }
                }
            }
            if (p.title.isNotBlank()) Text(p.title, style = MaterialTheme.typography.titleSmall, color = OnSurface)
            // 多问题：把标签栏画出来（☒=答过 / ☐=还没），并给出 ←/→ 来回走 ——
            // 用户要的「能回上一题改选择」。TUI 本来就支持（脚注 Tab/Arrow keys to navigate）。
            if (p.tabs.size > 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                ) {
                    onPrev?.let {
                        Surface(color = SurfaceContainer, shape = Pill,
                            modifier = Modifier.clip(Pill).clickable(enabled = !busy, onClick = it)) {
                            Text(t("← 上一题"), Modifier.padding(12.dp, 5.dp),
                                style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
                        }
                    }
                    p.tabs.forEach { tab ->
                        Text(
                            (if (tab.submit) "✔ " else if (tab.answered) "☑ " else "☐ ") + tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (tab.answered) Teal else Dim,
                        )
                    }
                    onNext?.let {
                        Surface(color = SurfaceContainer, shape = Pill,
                            modifier = Modifier.clip(Pill).clickable(enabled = !busy, onClick = it)) {
                            Text(t("下一题 →"), Modifier.padding(12.dp, 5.dp),
                                style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
                        }
                    }
                }
            }
            p.options.forEach { o ->
                Surface(
                    color = if (o.checked) CopperContainer else SurfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled = !busy) { onPick(o) },
                ) {
                    Row(Modifier.padding(14.dp, 11.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (p.multiSelect) (if (o.checked) "☑" else "☐") else "${o.number}",
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = Mono),
                            color = if (o.checked) OnCopperContainer else Dim,
                        )
                        Column {
                            Text(o.label, style = MaterialTheme.typography.bodyMedium, color = if (o.checked) OnCopperContainer else OnSurface)
                            if (o.description.isNotBlank()) {
                                Text(o.description, style = MaterialTheme.typography.labelSmall, color = if (o.checked) OnCopperContainer.copy(alpha = 0.7f) else Dim)
                            }
                        }
                    }
                }
            }
            // 提交：多选时数字只是「勾选」，必须显式交卷；多问题时也给一个入口
            // （一路 → 走到复核页再选 Submit answers，由调用方处理）。
            if (p.multiSelect || p.tabs.size > 1 || p.review) {
                Button(onSubmit, enabled = !busy, shape = Pill, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(if (p.review) t("确认提交") else t("提交答案"))
                }
                if (p.multiSelect) Text(
                    t("多选：点选项是勾/取消，选完再点提交"),
                    style = MaterialTheme.typography.labelSmall, color = Dim,
                )
            }
        }
    }
}


/**
 * **一串同名工具卡片合成一张。** 一个回合里连着七八条 Bash / Read，满屏都是同一个词
 * （用户原话：「能不能合成一个总的 bash，点击总的会展开小的」）。
 *
 * 规则在 [groupToolRuns]：连续 ≥ 3 条同名工具卡才合；**最后一条还在跑的不合进去**
 * （进行中的那条要看得见）；出错的一串不合（失败才是要看的）。
 * 合起来那张只有一行：工具名 × 条数 · 第一条到最后一条的摘要 · 状态。点一下展开成原来的小卡片。
 */
@Composable
fun ToolGroupCard(calls: List<ChatItem.ToolCall>, open: Boolean, onToggle: () -> Unit) {
    Surface(color = SurfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp, 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(calls.first().name, style = MaterialTheme.typography.labelLarge, color = accent(calls.first().name))
                Chip("× ${calls.size}", accent(calls.first().name))
                Text(
                    if (open) "" else summary(calls.first()),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                    color = Dim, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (open) t("收起") else t("完成"),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = Dim,
                )
            }
            if (open) Column(
                Modifier.padding(8.dp, 0.dp, 8.dp, 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                calls.forEach { ToolCard(it) }
            }
        }
    }
}

/** 列表里的一行：要么一条内容，要么一串合起来的工具卡。 */
sealed interface ChatRow {
    val key: String
    data class One(val item: ChatItem) : ChatRow { override val key get() = item.key }
    data class Group(val calls: List<ChatItem.ToolCall>) : ChatRow { override val key get() = "group-" + calls.first().key }
}

/**
 * 把连续 ≥ [min] 条**同名、已完成、没出错**的工具卡合成一组。
 * ⚠️ 纯函数，别在这里碰任何状态；展开与否由界面按 [ChatRow.key] 记。
 */
fun groupToolRuns(items: List<ChatItem>, min: Int = 3): List<ChatRow> {
    val out = ArrayList<ChatRow>(items.size)
    var i = 0
    while (i < items.size) {
        val it0 = items[i]
        if (it0 is ChatItem.ToolCall && it0.result != null && !it0.isError && it0.name !in NEVER_GROUP) {
            var j = i
            while (j < items.size) {
                val c = items[j] as? ChatItem.ToolCall ?: break
                if (c.name != it0.name || c.result == null || c.isError) break
                j++
            }
            if (j - i >= min) {
                out += ChatRow.Group(items.subList(i, j).map { it as ChatItem.ToolCall })
                i = j
                continue
            }
        }
        out += ChatRow.One(it0)
        i++
    }
    return out
}

/** 这些不是噪音，一条都不许被合掉。 */
private val NEVER_GROUP = setOf("AskUserQuestion", "ExitPlanMode", "TodoWrite")
