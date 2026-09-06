package app.yxi.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.agent.Account
import app.yxi.ui.theme.Muted

/**
 * 「账号中心」—— 这个账号本身的事：邮箱、怎么登进来的、UID（老板 2026-09-06）。
 *
 * ⚠️ **跟「会员中心」分开**：那边是买了什么、什么时候到期；这边是「你是谁」。
 * ⚠️ 登录方式的取值会变多（Logto 以后加连接器就会冒新值，见 `logto_yxi/design/account.md`），
 *    所以**不写穷举 when**：认得的翻译成人话，认不得的就只显示邮箱、不显示来源
 *    —— 宁可少一行，也别把 `github` 显示成「未知方式」。
 */
@Composable
fun AccountScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val me = Account.me

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            t("账号中心"), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(18.dp, 14.dp, 18.dp, 4.dp),
        )

        if (me == null) {
            Hint(t("还没登录。"))
            return@Column
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(22.dp)),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                me.email.takeIf { it.isNotBlank() }?.let { Line(t("邮箱"), it, mono = true, copy = ctx) }
                // 认不出来的连接器名就整行不显示 —— 不写「未知」
                signInLabel(me.signInWith)?.let { Line(t("登录方式"), it) }
                me.userId.takeIf { it.isNotBlank() }?.let { Line("UID", it, mono = true, copy = ctx) }
            }
        }

        Hint(t("邮箱和登录方式来自登录服务，改不了；换绑请提工单。"))
    }
}

/** 认得的翻成人话；认不得的返回 null（整行不显示）。⚠️ 别改成 `else -> "其他"`。 */
private fun signInLabel(v: String): String? = when (v) {
    "email" -> t("邮箱注册")
    "google" -> t("Google 账号")
    "github" -> t("GitHub 账号")
    else -> null
}

@Composable
private fun Line(label: String, value: String, mono: Boolean = false, copy: android.content.Context? = null) {
    Row(
        Modifier.fillMaxWidth().let { m ->
            if (copy == null) m else m.combinedClickable(
                onClick = {},
                onLongClick = {
                    val cm = copy.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
                    android.widget.Toast.makeText(copy, t("%s 复制好了").format(label), android.widget.Toast.LENGTH_SHORT).show()
                },
            )
        }.padding(18.dp, 14.dp),
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

@Composable
private fun Hint(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        Text(text, Modifier.padding(18.dp, 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}
