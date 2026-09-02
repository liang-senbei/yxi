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
    )

    /**
     * 冷启动播哪个：**四款随机轮播**（用户 2026-09-02 在实验室里定的：粒子聚合 + 星尘汇聚 + 旋涡 + 星点闪现）。
     * 笔触书写 / 墨迹落定 / Y 落下留着备用，不在轮播里。
     * ⚠️ 实验室是「服务器推过来给用户审」的地方，App 里没有选择器 —— 改轮播名单就改这里发版。
     */
    val ROTATION = listOf("particles", "stardust", "vortex", "sparkle")

    /** 每次冷启动随机挑一个，**不重复上一次**（连着两次一样看着像坏了）。 */
    fun chosen(ctx: Context): Variant? {
        val prefs = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)
        val last = prefs.getString("splash.last", null)
        val pool = ROTATION.filter { it != last }.ifEmpty { ROTATION }
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
    val variant = remember { Splash.chosen(ctx) }
    var done by remember { mutableStateOf(variant == null) }
    content()
    if (!done && variant != null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            variant.content { done = true }
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
