package app.yxi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.yxi.agent.Attachments
import app.yxi.agent.ChatItem

/**
 * **发过的话** —— 长按「对话」那个模式芯片弹出来的浮层，往前翻**这个会话里自己发出去的每一条**
 * （老板 2026-09-07：「长按对话会展开一个画中画可以往前翻之前我们发过的内容，这个按会话来计算」）。
 *
 * ⚠️ **数据不另存一份**：直接从已经解析好的转录里滤 [ChatItem.UserText] / [ChatItem.Queued]。
 *   「按会话计算」因此是天然成立的 —— 转录本来就是一个会话一份，不需要再按会话分桶，
 *   也不会出现「本地存的历史和服务器上的对话对不上」这种两个真相源的问题。
 *
 * 点一条 = 填回输入框（接着改再发，这是最常用的：上一条说了一半、或者要发个类似的）；
 * 长按一条 = 复制原文。
 */
@Composable
fun SentHistory(
    items: List<ChatItem>,
    onPick: (String) -> Unit,
    onCopy: (String) -> Unit,
    onClose: () -> Unit,
) {
    // 新的在上 —— 翻历史几乎总是从最近的开始找
    // ⚠️ **带附件的消息要把标记摘掉**：原文里附件是 `[图片1] /root/...jpg` 这样的整行，
    //    直接显示就是一串路径（气泡里是画成缩略图的），填回输入框更糟 —— 把标记也塞回去了。
    //    所以列表和填回都用 [Attachments.parseRefs] 摘出来的正文，附件只在右上角记个数。
    val sent = remember(items) {
        items.mapNotNull { item ->
            val raw = when (item) {
                is ChatItem.UserText -> item.text
                // 排队中的也算「我发出去的」：用户按了发送，只是还没轮到它
                is ChatItem.Queued -> item.text
                else -> null
            } ?: return@mapNotNull null
            val (refs, body) = Attachments.parseRefs(raw)
            Sent(item.key, body.trim(), refs.size)
        }.filter { it.text.isNotBlank() || it.attachments > 0 }.asReversed()
    }

    Dialog(onDismissRequest = onClose) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp, 16.dp, 20.dp, 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t("发过的话"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(
                        t("%d 条").format(sent.size),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (sent.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(20.dp, 10.dp, 20.dp, 26.dp), contentAlignment = Alignment.Center) {
                        Text(
                            t("这个会话里还没发过话。"),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                } else {
                    Text(
                        t("点一条填回输入框 · 长按复制"),
                        Modifier.padding(20.dp, 0.dp, 20.dp, 8.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    )
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f, fill = false),
                        contentPadding = PaddingValues(14.dp, 0.dp, 14.dp, 18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(sent, key = { it.key }) { one ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                    .combinedClickable(
                                        onClick = { onPick(one.text); onClose() },
                                        onLongClick = { onCopy(one.text) },
                                    ),
                            ) {
                                Row(Modifier.padding(14.dp, 10.dp), verticalAlignment = Alignment.Top) {
                                    Text(
                                        one.text.ifBlank { t("（只有附件）") },
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        // ⚠️ 最多 4 行：翻历史是扫一眼找那一条，不是在这儿读全文；
                                        //    点进输入框之后才是完整的
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (one.attachments > 0) Text(
                                        t("📎%d").format(one.attachments),
                                        Modifier.padding(start = 8.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一条发过的话：[text] 已经摘掉附件标记，[attachments] 是它带了几个附件 */
private data class Sent(val key: String, val text: String, val attachments: Int)
