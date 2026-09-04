package app.yxi.ui.splash

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.yxi.ui.t

/**
 * 开屏 logo 动效 —— **几个方案摆在实验室里给用户挑**，挑中的那个才在冷启动时播。
 *
 * ⚠️ 用户拍板之前**一个都不上**：默认 [chosen] 是空，冷启动没有开屏。
 * 每个方案是一个独立文件（`SplashBrush.kt` 等），只有一个约定：
 * 全屏、自己画背景、结束时调一次 `onDone`、系统关了动画就直接给最终画面。
 */
object Splash {

    class Variant(
        val key: String,
        val name: String,
        /** 一句话概念，给挑的人看 */
        val blurb: String,
        val content: @Composable (onDone: () -> Unit) -> Unit,
    )

    val variants: List<Variant> = listOf(
        Variant("brush", "笔触书写", "像毛笔从左往右把 Yunxi 写出来，前沿带一点渗墨") { SplashBrush(it) },
        Variant("ink", "墨迹落定", "一笔墨从空中落到纸上、渗开、定住，再扫过一道光") { SplashInk(it) },
        Variant("particles", "粒子聚合", "几百个小点从四面八方飞进来拼成 Yunxi，再化成真正的字") { SplashParticles(it) },
        Variant("stardust", "星尘汇聚", "发光的星尘打着旋落定，Gemini 火花的蓝紫粉渐变，落定褪成墨黑再扫一道高光") { SplashStardust(it) },
        Variant("vortex", "旋涡", "从屏幕外螺旋旋进来带拖尾，飞行中颜色沿色相流动，落定褪成墨黑") { SplashVortex(it) },
        Variant("sparkle", "星点闪现", "粒子不飞，一颗颗在原位亮起来，一部分是四角星 ✦，最后笔画尖端闪几颗大 ✦") { SplashSparkle(it) },
        Variant("drop", "Y 落下，字展开", "Y 从上面落下弹两下、起一圈涟漪，然后整个字向右展开") { SplashDrop(it) },
        // ↓ 新品牌标记（两片花瓣）的三支。上面七支画的都是**上一版**的手写 Yunxi 字，已不在轮播里。
        Variant("flip", "翻面", "logo 像卡片从侧面翻正，落定后一道斜光顺着 logo 的形状扫过") { SplashFlip(it) },
        Variant("converge", "聚合", "几千个带原色的小方块飞进来拼成 logo，拼完换成清晰的原图") { SplashConverge(it) },
        Variant("develop", "显影", "像相纸显影，logo 从底部的根被一圈长大的光扫出来（只在深色主题）") { SplashDevelop(it) },
    )

    /**
     * 冷启动播哪个 —— **按主题分，不是一锅随机**（用户 2026-09-04 定的）：
     *
     * · **深色**：只播「显影」。它靠暗底上那圈蓝白亮边，浅底上既看不见、隐喻也不成立。
     * · **浅色**：在「翻面」和「聚合」之间随机。
     *
     * ⚠️ 上一版那四支（粒子聚合 / 星尘 / 旋涡 / 星点）画的是**手写 Yunxi 字**，是旧品牌，已下轮播。
     *    换 logo 之后没同步换开屏，用户看到的还是老动画 —— 换品牌记得连开屏一起换。
     * ⚠️ 实验室是「服务器推过来给用户审」的地方，App 里没有选择器 —— 改名单就改这里发版。
     */
    val DARK_ROTATION = listOf("develop")
    val LIGHT_ROTATION = listOf("flip", "converge")

    /** 每次冷启动随机挑一个，**不重复上一次**（连着两次一样看着像坏了）。 */
    fun chosen(ctx: Context, dark: Boolean): Variant? {
        val rotation = if (dark) DARK_ROTATION else LIGHT_ROTATION
        val prefs = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)
        val last = prefs.getString("splash.last", null)
        val pool = rotation.filter { it != last }.ifEmpty { rotation }
        val key = pool.random()
        prefs.edit().putString("splash.last", key).apply()
        return variants.firstOrNull { it.key == key }
    }
}

/**
 * 冷启动那一下：挑中了方案就盖在整个 App 上播一遍，播完让开。
 * ⚠️ 只在**进程冷启动**时播一次（`remember` 挂在 Activity 的组合树上，切页不会重播）。
 */
@Composable
fun SplashGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    // 深浅由**当前皮肤的底色亮度**判断 —— 不用去问 Skin，谁改了主题这里都跟着对
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val variant = remember { Splash.chosen(ctx, dark) }
    var done by remember { mutableStateOf(variant == null) }
    content()
    if (!done && variant != null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            variant.content { done = true }
            Greeting(Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * 开屏上那句问候 + 昵称（老板 2026-09-05）。
 *
 * ⚠️⚠️ **一个网络请求都不发，因此不会让开屏慢哪怕一帧。**
 * 昵称来自 [app.yxi.agent.Account.me]，那是 `Account.load()` 在 `onCreate` 里
 * **从本机缓存同步读出来的**（`auth.me`，SharedPreferences）—— 走到这儿它已经在内存里了。
 * 老板问的就是这个：「会不会有延迟？有延迟就不做。」答案是不会，**前提是这里永远不等** ——
 * 以后谁也别在开屏上加「先拉一下资料再显示名字」，那就正好把这条毁了：
 * 开屏是**给人看进度的**，它自己一旦要等，就成了进度本身。
 *
 * ⚠️ 没登录 / 还没拉过资料 → 昵称那行**直接不画**，不占位、不显示「加载中」、不写「用户」。
 *    问候语单独一行也成立。
 * ⚠️ 摆在**底部**不是紧贴图标下面：几种开屏方案（[SplashDrop] / [SplashInk] / …）
 *    图标落点各不相同，贴着谁都会撞上某一种。
 */
@Composable
private fun Greeting(modifier: Modifier = Modifier) {
    val name = app.yxi.agent.Account.me?.nickname?.takeIf { it.isNotBlank() }
    // 比图标晚一点浮出来 —— 跟图标同时出现会抢戏
    val a = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(260)
        a.animateTo(1f, androidx.compose.animation.core.tween(420))
    }
    Column(
        modifier.padding(bottom = 78.dp).graphicsLayer { alpha = a.value },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Have a nice day",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.outline,
        )
        if (name != null) {
            Spacer(Modifier.padding(top = 3.dp))
            Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** 实验室里点「播放」：全屏播一遍，播完停在最后一帧，点哪儿都关。 */
@Composable
fun SplashPlayer(variant: Splash.Variant, onClose: () -> Unit) {
    var finished by remember(variant.key) { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                .clickable { if (finished) onClose() },
        ) {
            androidx.compose.runtime.key(variant.key) { variant.content { finished = true } }
            if (finished) Text(
                t("点一下关闭"),
                Modifier.align(Alignment.BottomCenter).padding(0.dp, 0.dp, 0.dp, 40.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
