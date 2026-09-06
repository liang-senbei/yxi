package app.yxi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.agent.Account

/**
 * 「我的资料」—— 点「我的」页的头像进来（老板 2026-09-06：「点击头像可以显示详细点的信息」，
 * 参照 QQ 的「我的资料」卡片）。
 *
 * ⚠️ **这一页只读，不改东西**：改昵称/签名/头像还是走 [MeDialog]（点「编辑资料」）。
 *    两套编辑入口会打架 —— 昵称存在本机 prefs（[Me]），会员/邮箱来自服务端，
 *    混在一页里编辑很容易写花。
 * ⚠️ 拿不到的字段**整行不显示**，不写「未知」「--」—— 宁可少一行也别摆假信息。
 */
@Composable
fun ProfileScreen(
    onAccount: () -> Unit,
    onSkins: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val me = Account.me
    var editMe by remember { mutableStateOf(false) }
    if (editMe) MeDialog { editMe = false }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            t("我的资料"), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(18.dp, 14.dp, 18.dp, 4.dp),
        )

        // ── 头像 + 昵称 + UID
        Card {
            Row(
                Modifier.padding(18.dp, 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MeAvatar(72.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        Me.name(ctx).ifBlank { t("还没起名") },
                        style = MaterialTheme.typography.titleLarge, maxLines = 1,
                    )
                    Me.sign(ctx).takeIf { it.isNotBlank() }?.let {
                        Text(
                            it, Modifier.padding(top = 2.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline, maxLines = 3,
                        )
                    }
                    // UID：找客服、后台反查兑换记录都要它。长按复制（跟「我的」页那张卡一个规矩）。
                    me?.userId?.takeIf { it.isNotBlank() }?.let { uid ->
                        Text(
                            "UID $uid",
                            Modifier.padding(top = 4.dp).combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("uid", uid))
                                    android.widget.Toast.makeText(ctx, t("UID 复制好了"), android.widget.Toast.LENGTH_SHORT).show()
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }

        // ── 账号那几行
        if (me != null) Card {
            Column(Modifier.padding(vertical = 4.dp)) {
                InfoRow(t("会员"), me.tier.name)
                // 到期只在真有会员时才有意义；永久会员写「永久」而不是一个假日期
                when {
                    me.neverExpires -> InfoRow(t("到期"), t("永久"))
                    else -> me.expiresAt?.let { InfoRow(t("到期"), app.yxi.agent.Tz.date(it)) }
                }
                me.email.takeIf { it.isNotBlank() }?.let { InfoRow(t("邮箱"), it, mono = true) }
                InfoRow(t("曦光"), me.tickets.toString())
            }
        }

        // ── 装扮：这一页只给个入口，真正的挑选在装扮页（cc-Yxi_Entertainment 那块）
        // ⚠️ 标题说「我正在用的」，尾巴就得是**正在用的东西**，不能是「去装扮」那种动词 ——
        //    否则这一行什么信息都没给。
        Card(onClick = onSkins) {
            RowLine(t("我正在用的装扮"), Skins.frame(ctx).label)
        }

        Card(onClick = onAccount) { RowLine(t("账号中心"), t("邮箱 · 登录方式")) }
        Card(onClick = { editMe = true }) { RowLine(t("编辑资料"), t("昵称 · 签名 · 头像")) }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Card(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(22.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) { content() }
}

@Composable
private fun RowLine(title: String, tail: String) {
    Row(
        Modifier.fillMaxWidth().padding(18.dp, 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(tail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Text(" ›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(18.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        // ⚠️ 邮箱可能很长：给它剩下的宽度并**允许折行**，别顶到卡片边缘更别被切掉。
        //    `fill = false` = 短值不占满、还是右对齐。
        Text(
            value, Modifier.weight(1f, fill = false),
            style = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            else MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 2,
        )
    }
}
