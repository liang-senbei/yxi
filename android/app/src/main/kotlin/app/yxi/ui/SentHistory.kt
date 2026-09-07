package app.yxi.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.yxi.agent.Tz

/**
 * **发过的话** —— 长按「对话」那个模式芯片弹出来的浮层，往前翻**这个会话里自己发出去的每一条**
 * （老板 2026-09-07：「长按对话会展开一个画中画可以往前翻之前我们发过的内容，这个按会话来计算」
 * 「每个发的话都会记三天，三天后就清理」）。
 *
 * 数据来自 [SentLog]（按会话落盘、发出去就记、留三天），**不是从转录里滤** ——
 * 转录够不到远处，而用户翻历史要找的恰恰是远处那条。为什么改，见 [SentLog] 顶部。
 *
 * 点一条 = 填回输入框（接着改再发，这是最常用的：上一条说了一半、或者要发个类似的）；
 * 长按一条 = 复制原文。
 */
@Composable
fun SentHistory(
    sent: List<SentLog.Entry>,
    onPick: (String) -> Unit,
    onCopy: (String) -> Unit,
    onClose: () -> Unit,
) {
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
                            t("这三天里，这个会话没发过话。"),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                } else {
                    Text(
                        t("点一条填回输入框 · 长按复制 · 只留三天"),
                        Modifier.padding(20.dp, 0.dp, 20.dp, 8.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    )
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f, fill = false),
                        contentPadding = PaddingValues(14.dp, 0.dp, 14.dp, 18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(sent) { one ->
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
                                    Column(
                                        Modifier.padding(start = 8.dp),
                                        horizontalAlignment = Alignment.End,
                                    ) {
                                        if (one.at > 0) Text(
                                            Tz.stamp(one.at / 1000),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                        if (one.attachments > 0) Text(
                                            t("📎%d").format(one.attachments),
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
}
