package app.yxi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 「我的」——登录 / 资料 / 会员 / 额度 / 工单 / 邮件。
 *
 * ⚠️ **这里是个占位，内容归 cc-logto_yxi**（09-08 分工）。壳（左栏入口 + [Page.Me] 路由）是 pilot 的，
 * 约好的接口就是这一个函数：`@Composable fun MePane(state: AppState)` —— 他直接换函数体，
 * 别的界面拆到 `Me*.kt` 里去，这样两边不会各开一个入口。
 *
 * 登录走**系统浏览器 + 本机回环端口回调**（不内嵌 webview，见 desktop-reference §2.1）。
 * 回环端口用 `ServerSocket(0)` 动态取，别写死 —— 单实例那套（Shell.kt:106）也是这么做的。
 */
@Composable
fun MePane(state: AppState) {
    val t = Tokens.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp)) {
            Text("我的", style = MaterialTheme.typography.titleLarge, color = t.textPrimary)
            Text(
                "登录、会员、额度、工单、邮件 —— 正在做。",
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium, color = t.textMuted,
            )
        }
    }
}
