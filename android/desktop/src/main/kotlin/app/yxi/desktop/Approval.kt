package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yxi.agent.Pending

/**
 * 审批卡要显示的「工具名 + 命令原文」。[Pending] 只有标题和选项（`Do you want to proceed?` + Yes/No），
 * 命令本身在标题上面那一块屏幕文本里 —— 所以新提示一来要再抓一次屏交给 [approvalOf]。
 */
data class Approval(val tool: String?, val body: String)

/**
 * 权限提示的抬头 → 工具名。⚠️ 白名单：抬头认不出就不当工具名（转录里随便一行字
 * 都可能落在标题上面，当成工具名会显示成「允许 Claude 使用 ● 我来看看？」）。
 * `Tool use` 是 MCP 工具，名字在它下一行：`mcp__x__y(…) (MCP)`，取到 `(` 为止。
 */
private val TOOL_HEADERS = listOf(
    "Bash command" to "Bash", "Edit file" to "Edit", "Write file" to "Write", "Create file" to "Write",
    "Read file" to "Read", "Fetch" to "WebFetch", "Web search" to "WebSearch", "Tool use" to "",
)
private val OPTION1 = Regex("""^[❯>]?\s*1\.\s+\S""")

/** 标题上面那一块收到这些就停：分隔线 / 边框、多题的标签栏、转录里的条目（● ⎿ 状态行）、用户说过的话（> …）。 */
private fun stop(l: String): Boolean =
    l.count { it == '─' } >= 4 || (l.isNotEmpty() && l[0] in "╭╰├┌└❯>●⎿✻✽✶✳✢·*") || l.startsWith("←") || l.endsWith("→")

/** 从整屏文本里认出工具名和命令。认不出（不是权限提示 / 抬头滚出屏了）就两样都空。 */
fun approvalOf(screen: String?): Approval {
    val none = Approval(null, "")
    // 老版本的提示框带 │ 边框，先剥掉
    val ls = screen?.lines()?.map { it.trim().removePrefix("│").removeSuffix("│").trim() } ?: return none
    val opt1 = ls.indexOfLast { OPTION1.containsMatchIn(it) }
    if (opt1 < 0) return none
    var i = opt1 - 1
    while (i >= 0 && ls[i].isBlank()) i--                              // 选项上面可能空一行
    while (i >= 0 && ls[i].isNotBlank() && !stop(ls[i])) i--          // 越过标题（窄屏会折成几行，跟 Prompt 的取法一致）
    val block = ArrayList<String>()
    var j = i
    while (j >= 0 && block.size < 80 && !stop(ls[j])) { block += ls[j]; j-- }
    block.reverse()
    val h = block.indexOfLast { l -> TOOL_HEADERS.any { l.startsWith(it.first) } }
    if (h < 0) return none
    val body = block.subList(h + 1, block.size)
        .filterNot { it == "This command requires approval" }
        .dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
    val tool = TOOL_HEADERS.first { block[h].startsWith(it.first) }.second
        .ifEmpty { body.firstOrNull { it.isNotBlank() }?.substringBefore('(')?.trim().orEmpty().ifBlank { "MCP 工具" } }
    return Approval(tool, body.joinToString("\n"))
}

/**
 * 权限提示的选项文案 → 中文短语。只给权限提示用（AskUserQuestion / 计划批准的选项原文照显示）。
 * ⚠️ `don't ask again for: …` 不是「本会话」—— Claude Code 会把它写进项目的 settings.local.json，**以后都不问**
 * （PromptTest 里也记着这一条），所以标成「不再询问」，别写成本会话骗人；只有文案里明说 session 的才是本会话。
 */
fun zhOption(label: String): String {
    val l = label.lowercase()
    return when {
        l.startsWith("yes") && "session" in l -> "本会话允许"
        l.startsWith("yes") && ("don't ask" in l || "always" in l) -> "不再询问"
        l.startsWith("yes") -> "允许一次"
        l.startsWith("no") || "(esc)" in l || "tell claude" in l -> "拒绝"
        else -> label
    }
}

fun isReject(label: String) = zhOption(label) == "拒绝"

/** 是不是权限提示（而不是 AskUserQuestion / 计划批准）：认出了工具，或标题是 Claude Code 那句 `Do you want to …?` */
fun Pending.isPermission(a: Approval) = a.tool != null || title.startsWith("Do you want to")

/**
 * 审批卡（照 Codex：问句标题 + 命令原文 + 一排真实选项；Enter 批准、Esc 拒绝在输入框那边接）。
 * ⚠️ 卡片上显示几号，点下去就送几号 —— 别改成按下标送键，顺序对不上会点 A 选中 B 且不报错。
 */
@Composable
fun ApprovalCard(p: Pending, a: Approval, busy: Boolean, onKey: (String) -> Unit, onSubmit: () -> Unit) {
    val t = Tokens.current
    val perm = p.isPermission(a)
    val simple = perm && !p.multiSelect && p.tabs.size <= 1 && !p.review   // 一排按钮；多选 / 多题还是列表 + 提交
    val shape = RoundedCornerShape(Radius)
    Column(
        Modifier.fillMaxWidth().padding(12.dp, 4.dp).background(t.surface2, shape).border(1.dp, t.border, shape).padding(14.dp, 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            a.tool?.let { "允许 Claude 使用 $it？" } ?: p.title.ifBlank { if (p.multiSelect) "等你选（可多选）" else "等你选" },
            fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = t.textPrimary,
        )
        if (a.body.isNotBlank()) CodeBlock(a.body, Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
        if (p.tabs.size > 1) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !busy, onClick = { onKey("Left") }) { Text("← 上一题") }
            p.tabs.forEach { Text((if (it.submit) "✔ " else if (it.answered) "☑ " else "☐ ") + it.label, fontSize = 11.sp, color = t.textSecondary) }
            TextButton(enabled = !busy, onClick = { onKey("Right") }) { Text("下一题 →") }
        }
        if (simple) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                p.options.forEachIndexed { i, o ->
                    val zh = zhOption(o.label)
                    when {
                        i == 0 -> Button(enabled = !busy, onClick = { onKey(o.number.toString()) }, colors = primaryButton()) { Text(zh) }
                        zh == "拒绝" -> OutlinedButton(enabled = !busy, onClick = { onKey(o.number.toString()) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = t.danger)) { Text(zh) }
                        else -> OutlinedButton(enabled = !busy, onClick = { onKey(o.number.toString()) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = t.textPrimary)) { Text(zh) }
                    }
                }
            }
            // 原文照样给看：号码是契约，而且「不再询问」后面跟的范围（for: pip install *）用户得知道
            Text(
                p.options.joinToString(" · ") { "${it.number} ${it.label}" } + "   Enter = ${zhOption(p.options.first().label)} · Esc = 拒绝",
                fontSize = 11.sp, lineHeight = 16.sp, color = t.textMuted,
            )
        } else {
            if (p.multiSelect) Text("点一项是勾 / 取消，选完再提交", fontSize = 11.sp, color = t.textMuted)
            p.options.forEach { o ->
                Row(
                    Modifier.fillMaxWidth().background(if (o.checked) t.selected else t.surface1, shape).border(1.dp, t.border, shape)
                        .clickable(enabled = !busy) { onKey(o.number.toString()) }.padding(12.dp, 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(if (p.multiSelect) (if (o.checked) "☑" else "☐") else "${o.number}", style = CodeStyle, color = t.textSecondary)
                    Column {
                        Text(o.label, fontSize = 14.sp, lineHeight = 20.sp, color = t.textPrimary)
                        if (o.description.isNotBlank()) Text(o.description, fontSize = 11.sp, lineHeight = 16.sp, color = t.textMuted)
                    }
                }
            }
            if (p.multiSelect || p.tabs.size > 1 || p.review) Button(onClick = onSubmit, enabled = !busy, colors = primaryButton()) { Text(if (p.review) "确认提交" else "提交答案") }
            Text(if (p.multiSelect || p.tabs.size > 1 || p.review) "Enter = 提交 · Esc = 取消" else "Enter = 选第 1 项 · Esc = 取消", fontSize = 11.sp, color = t.textMuted)
        }
    }
}

/** 主按钮（Codex：底 = 正文色、字 = 面板色）。 */
@Composable
fun primaryButton() = Tokens.current.let {
    ButtonDefaults.buttonColors(containerColor = it.textPrimary, contentColor = it.surface2, disabledContainerColor = it.selected, disabledContentColor = it.textMuted)
}
