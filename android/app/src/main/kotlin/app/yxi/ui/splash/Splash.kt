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
        Variant("bloom", "光晕绽放", "对话页那四团粉彩光从中心绽放，Y 先弹出来，再化成整个字") { SplashBloom(it) },
        Variant("ink", "墨迹落定", "一笔墨从空中落到纸上、渗开、定住，再扫过一道光") { SplashInk(it) },
        Variant("particles", "粒子聚合", "几百个小点从四面八方飞进来拼成 Yunxi，再化成真正的字") { SplashParticles(it) },
        Variant("drop", "Y 落下，字展开", "Y 从上面落下弹两下、起一圈涟漪，然后整个字向右展开") { SplashDrop(it) },
    )

    private const val KEY = "splash"

    /** 用户挑中的方案；null = 没挑，冷启动不播 */
    fun chosen(ctx: Context): Variant? {
        val k = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return variants.firstOrNull { it.key == k }
    }

    fun choose(ctx: Context, key: String?) {
        ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE).edit().putString(KEY, key).apply()
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

/**
 * 实验室里那张「开屏动效」卡：列出所有方案，每个能播、能选。
 * ⚠️ 这是 App 自己的东西，不是 agent 推上来的；放在实验室是因为用户就在这儿审东西。
 */
@Composable
fun SplashLabCard() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf<Splash.Variant?>(null) }
    var chosen by remember { mutableStateOf(Splash.chosen(ctx)?.key) }
    val pill = RoundedCornerShape(100.dp)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(14.dp, 6.dp, 14.dp, 4.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t("开屏动效 · 待你审"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        chosen?.let { k -> t("现在用的：%s").format(t(Splash.variants.first { it.key == k }.name)) }
                            ?: t("%d 个方案，一个都还没定 —— 冷启动暂时不播").format(Splash.variants.size),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    )
                }
                Text(if (open) "▾" else "▸", color = MaterialTheme.colorScheme.outline)
            }
            if (open) Column(Modifier.padding(8.dp, 0.dp, 8.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Splash.variants.forEach { v ->
                    val on = chosen == v.key
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                            .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                            .padding(12.dp, 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t(v.name), style = MaterialTheme.typography.bodyLarge,
                                color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                            Text(t(v.blurb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            t("播放"),
                            Modifier.clip(pill).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { playing = v }.padding(12.dp, 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (on) t("用着") else t("就用这个"),
                            Modifier.clip(pill)
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { chosen = if (on) null else v.key; Splash.choose(ctx, chosen) }
                                .padding(12.dp, 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Text(
                    t("选中的那个会在冷启动时播一遍；再点一次「用着」就取消。"),
                    Modifier.padding(8.dp, 4.dp, 8.dp, 0.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
    playing?.let { v -> SplashPlayer(v) { playing = null } }
}
