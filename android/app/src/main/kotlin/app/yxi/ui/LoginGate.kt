package app.yxi.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.yxi.R
import app.yxi.agent.Account
import app.yxi.ui.theme.Amber
import app.yxi.ui.theme.Dim
import app.yxi.ui.theme.Muted

private val Pill = RoundedCornerShape(100.dp)

/**
 * 登录门禁 —— **没登录就整个 App 用不了**（用户 2026-09-04 拍板：「进 app 的时候统一先检测本机的
 * 登录情况，弹登录页面，没登录的不允许使用」）。
 *
 * 盖在所有内容之上、盖在开屏动效之下：开屏照播，播完落到这一页。登录成功后
 * [Account.signedIn] 一变，这一层自己让开，不用谁去关它。
 *
 * ⚠️ **判据是本机有没有 refresh token**（[Account.load] 进程启动时读的），**不是去问服务器**。
 * 理由：这个 App 的主场景是「人在外面、网络时好时坏」，拿网络请求当门禁 =
 * 地铁里打不开自己的 App。令牌真被服务器作废了，第一次 refresh 会失败并把人登出，
 * 那时候这一页会带着 [Account.signedOutWhy] 出现 —— 该说的话一句不少。
 *
 * ⚠️ 返回键**退出 App**，不是让开。这一层背后就是完整的 App，让开等于门禁失效。
 */
@Composable
fun LoginGate() {
    if (Account.signedIn) return
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    // 浏览器回来还在换 token 的那一小段：别让人以为没反应，也别让他再点一次
    val busy = Account.pendingCallback != null
    var noBrowser by remember { mutableStateOf(false) }

    BackHandler { activity?.finish() }

    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // 吃掉所有点击：底下是完整的 App，漏一下过去就等于没挡
            .clickable(enabled = false, onClick = {}),
    ) {
        // 顶上一层流动的色相 —— 跟输入框、整页光晕**同一股气**（design/STYLE.md §2.2），
        // 不是给登录页单做的装饰。
        // ⚠️ 必须再叠一层竖向渐变把下边收掉：只铺一个定高的盒子，底边是**一条横着的硬边**
        //    （模拟器上看得很清楚），像界面裂了一道缝。
        Box(
            Modifier.fillMaxWidth().height(300.dp).align(Alignment.TopCenter)
                .background(glowBrush(busy = false, waiting = false))
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.surface,
                    ),
                ),
        )

        Column(
            Modifier.fillMaxSize().padding(36.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.foundation.Image(
                painterResource(R.drawable.logo_mark), contentDescription = null,
                modifier = Modifier.size(96.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text("Yxi", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                t("你的 agent 在服务器上跑，你在这儿把关"),
                style = MaterialTheme.typography.bodyMedium, color = Dim,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(30.dp))

            // 上一次是**怎么掉的登录** —— 撤销 / 过期 / 账号被封，都在这儿说一句，
            // 别让人看着一个突然要重新登录的页面猜
            Account.signedOutWhy?.let {
                Surface(color = Amber.copy(alpha = 0.16f), shape = RoundedCornerShape(14.dp)) {
                    Text(
                        t(it), Modifier.padding(14.dp, 10.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            if (noBrowser) {
                Surface(color = Amber.copy(alpha = 0.16f), shape = RoundedCornerShape(14.dp)) {
                    Text(
                        t("这台手机上没有能打开网页的应用 —— 登录要跳浏览器，装一个再来"),
                        Modifier.padding(14.dp, 10.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            Surface(
                color = MaterialTheme.colorScheme.primary, shape = Pill,
                modifier = Modifier.clip(Pill).clickable(enabled = !busy) {
                    noBrowser = !Account.startLogin(ctx)
                },
            ) {
                Row(
                    Modifier.padding(34.dp, 15.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (busy) CircularProgressIndicator(
                        Modifier.size(16.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text(
                        if (busy) t("正在登录…") else t("登录 / 注册"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            // ⚠️ 这段话得是**真的**：账号只管人和会员，主机与密钥一直在本机
            //    （design/STYLE.md 第 0 条「不骗人」）
            Text(
                t("登录只用来认人 —— 会员额度、兑换和工单跟账号走。\n你连哪台服务器、密钥放在哪，都只在这台手机上。"),
                style = MaterialTheme.typography.labelSmall, color = Muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
