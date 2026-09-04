package app.yxi.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * **常用语 / 罐头回复** —— 你自己的短语库，一点就塞进回复框。
 *
 * 跟斜杠命令菜单不同：那是 Claude 的命令，这是**你的话**。手机打字是回复的瓶颈，
 * 大部分「催一句」就是这么几句，点一下省掉拇指打字。本地存（每台设备自己的偏好）。
 */
object Snippets {
    private val DEFAULTS = listOf(
        "继续", "好的，按你说的做", "先给方案再动手", "跑一下测试", "commit 并推一下", "停一下，我看看",
    )
    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)
    /**
     * ⚠️ 抽到的**快捷语包只接在默认那几条后面**（[Skins.phrases]）。
     * 自己编辑过常用语的人，那份列表是他的 —— 别往里塞东西，哪怕是他抽到的。
     */
    fun get(ctx: Context): List<String> =
        p(ctx).getString("snippets", null)
            ?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.ifEmpty { null } ?: Skins.phrases(ctx, DEFAULTS)
    fun set(ctx: Context, list: List<String>) =
        p(ctx).edit().putString("snippets", list.joinToString("\n")).apply()
}

private val ChipShape = RoundedCornerShape(100.dp)

/** 一排可横滑的常用语 chip，点一个回调 [onPick]（一般是填进回复框）。末尾一个「编辑」chip。 */
@Composable
fun SnippetChips(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var phrases by remember { mutableStateOf(Snippets.get(ctx)) }
    var editing by remember { mutableStateOf(false) }
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(phrases, key = { it }) { s ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = ChipShape,
                modifier = Modifier.clip(ChipShape).clickable { onPick(s) },
            ) {
                Text(s, Modifier.padding(14.dp, 8.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = ChipShape,
                modifier = Modifier.clip(ChipShape).clickable { editing = true },
            ) {
                Text(t("编辑"), Modifier.padding(14.dp, 8.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
    if (editing) {
        var draft by remember { mutableStateOf(phrases.joinToString("\n")) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(t("常用语（一行一句）")) },
            text = {
                OutlinedTextField(
                    draft, { draft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    placeholder = { Text(t("一行一句…")) },
                )
            },
            confirmButton = {
                TextButton({
                    val list = draft.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                    Snippets.set(ctx, list); phrases = Snippets.get(ctx); editing = false
                }) { Text(t("保存")) }
            },
            dismissButton = { TextButton({ editing = false }) { Text(t("取消")) } },
        )
    }
}
