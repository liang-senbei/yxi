package app.yxi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 会员中心 —— pro / ultra 两档（用户 2026-09-04 拍板，QQ 侧边栏那个「会员中心」的位置）。
 *
 * ⚠️ **这一版只是「有什么」，还不能买**：账号和订阅在 logto 那边做（已同步过去），
 * 价格也没定。所以按钮是「即将开放」，不做假的下单流程 —— 让人点了没反应比不给按钮更糟。
 */
@Composable
fun MemberScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tier = Me.tier(ctx)
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp, 12.dp, 18.dp, 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "←", Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onBack).padding(10.dp, 4.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(t("会员中心"), style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MeAvatar(52.dp)
            Column(Modifier.weight(1f)) {
                Text(Me.name(ctx).ifBlank { t("还没设昵称") }, style = MaterialTheme.typography.titleMedium)
                Text(
                    when (tier) {
                        Me.Tier.Free -> t("当前：免费版")
                        Me.Tier.Pro -> t("当前：Pro")
                        Me.Tier.Ultra -> t("当前：Ultra")
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Plan(
            name = t("免费版"), tagline = t("一台机器，够用"),
            accent = MaterialTheme.colorScheme.outline, own = tier == Me.Tier.Free,
            lines = listOf(t("1 台主机"), t("会话看板 + 对话 + 终端"), t("本机语音转写")),
        )
        Spacer(Modifier.height(12.dp))
        Plan(
            name = "Pro", tagline = t("多机器盯梢，随手就批"),
            accent = Color(0xFF4C8DF6), own = tier == Me.Tier.Pro,
            lines = listOf(t("主机不限台"), t("后台盯梢 + 通知里直接批"), t("实验室：让 agent 画图"), t("分组与组规同步")),
        )
        Spacer(Modifier.height(12.dp))
        Plan(
            name = "Ultra", tagline = t("整队 agent 一起带"),
            accent = Color(0xFFB07CFF), own = tier == Me.Tier.Ultra,
            lines = listOf(t("Pro 的全部"), t("多设备同步"), t("实验室额度更高"), t("优先支持")),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            t("订阅还没开放 —— 先把两档能有什么摆在这儿，价格定了再说。"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun Plan(name: String, tagline: String, accent: Color, own: Boolean, lines: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 顶上一条渐变，区分档位；不是整块上色 —— 卡片一花，字就难读
            Box(Modifier.fillMaxWidth().height(4.dp).background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.15f)))))
            Column(Modifier.padding(16.dp, 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    YxiIcon(Ico.Crown, size = 20.dp, tint = accent)
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    if (own) Text(
                        t("当前"),
                        Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(8.dp, 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.weight(1f))
                    if (!own) Text(
                        t("即将开放"), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    tagline, Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(10.dp))
                lines.forEach {
                    Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.padding(top = 7.dp).size(5.dp).clip(RoundedCornerShape(3.dp)).background(accent))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
