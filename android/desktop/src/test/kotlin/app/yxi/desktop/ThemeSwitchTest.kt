package app.yxi.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import kotlin.test.*

/**
 * M1 验收②的主题层部分：四种（风格 × 明暗）组合来回切 20 次，主题下面的状态一个都不丢——
 * remember 的对象还是同一个、LaunchedEffect 不重跑、DisposableEffect 不被拆，每次都拿到对应的 spec。
 * 走 [YxiTheme] 的显式参数重载，不碰 Store / 真实 prefs.json。整个应用的草稿、附件、连接、运行中任务交给 M0 夹具。
 */
@OptIn(ExperimentalComposeUiApi::class)
class ThemeSwitchTest {
    @Test fun `switching style and brightness 20 times keeps state below the theme`() {
        val style = mutableStateOf(UiStyle.Classic)
        val dark = mutableStateOf(false)
        val identities = mutableSetOf<Any>()
        var launched = 0
        var disposed = 0
        var compositions = 0
        var seenSpec: ThemeSpec? = null
        var seenTokens: Tokens? = null
        var seenBackground = Color.Unspecified
        val scene = ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            YxiTheme(style.value, dark.value) {
                val identity = remember { Any() }
                LaunchedEffect(Unit) { launched++ }
                DisposableEffect(Unit) { onDispose { disposed++ } }
                val spec = LocalThemeSpec.current
                val tokens = Tokens.current
                val background = MaterialTheme.colorScheme.background
                SideEffect { identities += identity; compositions++; seenSpec = spec; seenTokens = tokens; seenBackground = background }
            }
        }
        try {
            scene.render(0).close()
            assertSame(ThemeSpec.classicLight, seenSpec)
            // 每一步都和上一步不同（第一步就离开初始的经典浅色），20 次都是真切换
            val combos = listOf(UiStyle.Classic to true, UiStyle.Code to false, UiStyle.Code to true, UiStyle.Classic to false)
            repeat(20) { i ->
                val (s, d) = combos[i % combos.size]
                style.value = s
                dark.value = d
                Snapshot.sendApplyNotifications()
                scene.render((i + 1) * 16_000_000L).close()
                val expected = ThemeSpec.of(s, d)
                assertSame(expected, seenSpec, "第 ${i + 1} 次切到 $s/$d")
                assertSame(expected.tokens, seenTokens)
                assertEquals(expected.tokens.surface0, seenBackground)
            }
            assertEquals(1, identities.size, "remember 的对象被重建了")
            assertEquals(1, launched, "LaunchedEffect 重跑了")
            assertEquals(0, disposed, "切换途中 DisposableEffect 被拆了")
            assertTrue(compositions >= 21, "只重组了 $compositions 次，切换没传下去")
        } finally {
            scene.close()
        }
        assertEquals(1, disposed)
    }
}
