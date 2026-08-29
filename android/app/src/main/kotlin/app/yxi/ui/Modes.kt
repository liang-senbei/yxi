package app.yxi.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * **模式快切** —— 点一下就把对应的斜杠命令发进会话，切模型/思考强度/ultracode 等。
 *
 * 关键：这些模式（`/model`、`/effort`、`/ponytail`…）是**各自独立的斜杠命令**，
 * 所以**能叠加**——连点几个就都生效（1M + 最大思考）。
 *
 * ⚠️ 命令**做成可编辑**：不同 Claude Code 版本 / 你的习惯，具体命令可能不一样。
 * 给了最可能的默认，进去自己改成真能用的那句就行（跟 [Snippets] 一个思路）。本地存。
 */
object Modes {
    // ⚠️ 默认命令是**从真转录里核过的形式**，别凭感觉写：
    //   · `/model opus[1m]` **不认**（实测回「Kept model as …」，等于没切）；
    //     认的是**全名带后缀**。两条都在转录里有成功回执：
    //       `/model claude-opus-4-6[1m]` → 「Set model to Opus 4.6 (1M context)」
    //       `/model claude-opus-5[1m]`   → 「Set model to Opus 5 (1M context)」
    //   · `/effort max|high|mid` 是真的（转录里用过很多次）。
    // 各家版本/习惯可能不同，所以整张表在界面上**可编辑**。
    private val DEFAULTS = listOf(
        "Opus 4.6 · 1M" to "/model claude-opus-4-6[1m]",
        "Opus 5 · 1M" to "/model claude-opus-5[1m]",
        "最大思考" to "/effort max",
        "高强度" to "/effort high",
        "中等" to "/effort mid",
        "ultracode" to "/ponytail ultra",
        "普通" to "/ponytail",
    )
    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)
    fun get(ctx: Context): List<Pair<String, String>> {
        val saved = p(ctx).getString("modes", null) ?: return DEFAULTS
        val list = saved.split("\n").mapNotNull { ln ->
            val i = ln.indexOf('|'); if (i <= 0) null
            else ln.substring(0, i).trim() to ln.substring(i + 1).trim()
        }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
        return list.ifEmpty { DEFAULTS }
    }
    fun set(ctx: Context, list: List<Pair<String, String>>) =
        p(ctx).edit().putString("modes", list.joinToString("\n") { "${it.first}|${it.second}" }).apply()
}

private val ChipShape = RoundedCornerShape(16.dp)
private val Mono = FontFamily.Monospace

/** 底部弹出的模式快切。点 chip = 发命令（[onPick]），**不关面板** —— 好连点叠加。 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ModeSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var modes by remember { mutableStateOf(Modes.get(ctx)) }
    var editing by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(18.dp, 0.dp, 18.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(t("切模式"), style = MaterialTheme.typography.titleLarge)
            Text(t("点一下就发进会话；能叠加的连着点，比如先 1M 再 最大思考。"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                modes.forEach { (label, cmd) ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = ChipShape,
                        modifier = Modifier.clip(ChipShape).clickable { onPick(cmd) }) {
                        Column(Modifier.padding(16.dp, 10.dp)) {
                            Text(label, style = MaterialTheme.typography.titleSmall)
                            Text(cmd, style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = ChipShape,
                    modifier = Modifier.clip(ChipShape).clickable { editing = true }) {
                    Box(Modifier.padding(16.dp, 10.dp).heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
                        Text(t("编辑"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
    if (editing) {
        var draft by remember { mutableStateOf(modes.joinToString("\n") { "${it.first}|${it.second}" }) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(t("编辑模式（一行一个：名字|命令）")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t("例：最大思考|/effort max —— 命令按你的 Claude Code 实际能用的填"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono))
                }
            },
            confirmButton = {
                TextButton({
                    val list = draft.split("\n").mapNotNull { ln ->
                        val i = ln.indexOf('|'); if (i <= 0) null
                        else ln.substring(0, i).trim() to ln.substring(i + 1).trim()
                    }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
                    Modes.set(ctx, list); modes = Modes.get(ctx); editing = false
                }) { Text(t("保存")) }
            },
            dismissButton = { TextButton({ editing = false }) { Text(t("取消")) } },
        )
    }
}
