@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package app.yxi.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M4q 交叉审查的几处：Code 设置弹窗的键盘焦点（[CodeModalFocus]）、分段控件和导航项的选中语义、对话里弹出的界面件不跟对话字号缩放
 * （[CodeChromeText]）、模态时放行的全局键（[modalShortcut]）。离屏场景，不碰 Store / AppState（不需要夹具 profile）。
 */
class CodeSettingsUiTest {
    /**
     * 底下一个已经拿着焦点、数按键的「输入框」（代表遮罩底下的对话输入框）；打开模态后：焦点进弹窗里的搜索框，按键不落到底下，
     * Tab 在弹窗里的两个输入框之间走、走到头也不出去，Esc 关掉弹窗。
     */
    @Test fun `the modal takes the keys, keeps focus inside and closes on escape`() = runBlocking<Unit>(Dispatchers.Swing) {
        var composerKeys = 0
        var composerFocused = false
        var searchKeys = 0
        var searchFocused = false
        var otherFocused = false
        var open by mutableStateOf(false)
        val scene = ImageComposeScene(800, 600, density = Density(1.25f), coroutineContext = Dispatchers.Swing) {
            val composer = remember { FocusRequester() }
            var draft by remember { mutableStateOf(TextFieldValue("")) }
            LaunchedEffect(Unit) { composer.requestFocus() }
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                BasicTextField(draft, { draft = it }, Modifier.width(300.dp).onPreviewKeyEvent { composerKeys++; false }
                    .focusRequester(composer).onFocusChanged { composerFocused = it.isFocused })
                if (open) {
                    val search = remember { FocusRequester() }
                    CodeModalFocus({ open = false }, search) {
                        Column {
                            var q by remember { mutableStateOf(TextFieldValue("")) }
                            var r by remember { mutableStateOf(TextFieldValue("")) }
                            BasicTextField(q, { q = it }, Modifier.width(200.dp).onPreviewKeyEvent { searchKeys++; false }.focusRequester(search)
                                .onFocusChanged { searchFocused = it.isFocused }, singleLine = true)
                            BasicTextField(r, { r = it }, Modifier.width(200.dp).onFocusChanged { otherFocused = it.isFocused }, singleLine = true)
                        }
                    }
                }
            }
        }
        var t = 0L
        suspend fun frames() = repeat(12) { Snapshot.sendApplyNotifications(); scene.render(t).close(); t += 16_000_000L; delay(30) }
        try {
            frames()
            assertTrue(composerFocused, "起始时底下的输入框应有焦点")
            open = true; frames()
            assertTrue(searchFocused, "弹窗打开后焦点应在搜索框")
            assertFalse(composerFocused, "弹窗开着时底下的输入框不该还拿着焦点")
            val before = composerKeys
            press(scene, Key.A); frames()
            assertEquals(before, composerKeys, "弹窗开着时按键不该落到底下的输入框")
            assertTrue(searchKeys > 0, "按键应落在弹窗里的搜索框")
            var reachedOther = false
            repeat(4) { press(scene, Key.Tab); frames(); reachedOther = reachedOther || otherFocused; assertFalse(composerFocused, "Tab 不该把焦点带出弹窗") }
            assertTrue(reachedOther, "Tab 应在弹窗里的输入框之间走（对照：说明 Tab 遍历真的发生了）")
            assertEquals(before, composerKeys, "Tab 也不该落到底下的输入框")
            assertTrue(press(scene, Key.Escape), "Esc 应被弹窗处理")
            frames()
            assertFalse(open, "Esc 应关掉弹窗")
        } finally { scene.close() }
    }

    @Test fun `segmented options and settings tabs expose their selected state`() = runBlocking<Unit>(Dispatchers.Swing) {
        val scene = ImageComposeScene(800, 300, density = Density(1.25f), coroutineContext = Dispatchers.Swing) {
            Column {
                CodeSegmented(listOf("a" to "小", "b" to "中", "c" to "大"), "b") {}
                CodeSettingsNavItem("外观", selected = true, jump = false) {}
                CodeSettingsNavItem("通知", selected = false, jump = false) {}
                CodeSettingsNavItem("账号", selected = false, jump = true) {}
            }
        }
        try {
            scene.render(0).close()
            fun node(text: String): SemanticsNode = find(scene) { n ->
                n.config.getOrNull(SemanticsProperties.Role) != null && n.children.any { c -> c.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == text }
            }.single()
            fun selected(text: String) = node(text).config.getOrNull(SemanticsProperties.Selected)
            fun role(text: String) = node(text).config.getOrNull(SemanticsProperties.Role)
            assertEquals(listOf(false, true, false), listOf("小", "中", "大").map { selected(it) })
            assertEquals(Role.RadioButton, role("中"))
            assertTrue(find(scene) { it.config.contains(SemanticsProperties.SelectableGroup) }.isNotEmpty(), "分段控件应是一组（selectableGroup）")
            assertEquals(Role.Tab, role("外观")); assertEquals(true, selected("外观")); assertEquals(false, selected("通知"))
            assertEquals(Role.Button, role("账号"), "跳到别的页面的分区是按钮")
        } finally { scene.close() }
    }

    /** 对话里（[CodeChatText] 放大了 fontScale）弹出的界面件换回缩放前的 Density；对话外原样。 */
    @Test fun `chrome inside a scaled conversation goes back to the unscaled font`() = runBlocking<Unit>(Dispatchers.Swing) {
        var outside = 0f
        var outsideChrome = 0f
        var scaled = 0f
        var chrome = 0f
        val scene = ImageComposeScene(400, 200, density = Density(1.25f), coroutineContext = Dispatchers.Swing) {
            val base = LocalDensity.current
            outside = base.fontScale
            CodeChromeText { outsideChrome = LocalDensity.current.fontScale }
            CompositionLocalProvider(LocalDensity provides Density(base.density, base.fontScale * 16f / 14f), LocalChatBaseDensity provides base) {
                scaled = LocalDensity.current.fontScale
                CodeChromeText { chrome = LocalDensity.current.fontScale }
            }
        }
        try {
            scene.render(0).close()
            assertEquals(outside, outsideChrome, "对话外 CodeChromeText 不改 Density")
            assertEquals(outside * 16f / 14f, scaled, 1e-5f)
            assertEquals(outside, chrome, "tooltip / 浮层里的字应回到缩放前")
        } finally { scene.close() }
    }

    /** 模态时只放行缩放和 Ctrl+,，其余全局键（新会话、切换任务、开面板、切会话…）吞掉。 */
    @Test fun `only zoom and the settings key pass while the settings modal is open`() {
        for (key in listOf(Key.Equals, Key.NumPadAdd, Key.Minus, Key.NumPadSubtract, Key.Zero, Key.NumPad0, Key.Comma)) assertTrue(modalShortcut(event(key)), "$key 应放行")
        for (key in listOf(Key.N, Key.K, Key.B, Key.J, Key.Tab, Key.Slash, Key.P, Key.F, Key.Backslash, Key.Grave, Key.One, Key.F5)) assertFalse(modalShortcut(event(key)), "$key 应吞掉")
    }

    private fun event(key: Key, type: KeyEventType = KeyEventType.KeyDown) = KeyEvent(key, type, 0, isCtrlPressed = true, nativeEvent =
        java.awt.event.KeyEvent(java.awt.Canvas(), if (type == KeyEventType.KeyDown) java.awt.event.KeyEvent.KEY_PRESSED else java.awt.event.KeyEvent.KEY_RELEASED,
            System.currentTimeMillis(), java.awt.event.InputEvent.CTRL_DOWN_MASK, awtCode(key), java.awt.event.KeyEvent.CHAR_UNDEFINED))

    private fun awtCode(key: Key): Int = when (key) {
        Key.A -> java.awt.event.KeyEvent.VK_A
        Key.Tab -> java.awt.event.KeyEvent.VK_TAB
        Key.Escape -> java.awt.event.KeyEvent.VK_ESCAPE
        else -> java.awt.event.KeyEvent.VK_UNDEFINED
    }

    /** 合成一次按下 + 抬起（不带修饰键，带上 AWT 原生事件）；返回按下是否被处理。 */
    private fun press(scene: ImageComposeScene, key: Key): Boolean {
        val src = java.awt.Canvas()
        val char = if (key == Key.A) 'a' else java.awt.event.KeyEvent.CHAR_UNDEFINED
        fun ev(id: Int, type: KeyEventType) = KeyEvent(key, type, char.code,
            nativeEvent = java.awt.event.KeyEvent(src, id, System.currentTimeMillis(), 0, awtCode(key), char))
        val handled = scene.sendKeyEvent(ev(java.awt.event.KeyEvent.KEY_PRESSED, KeyEventType.KeyDown))
        scene.sendKeyEvent(ev(java.awt.event.KeyEvent.KEY_RELEASED, KeyEventType.KeyUp))
        return handled
    }

    private fun find(scene: ImageComposeScene, match: (SemanticsNode) -> Boolean): List<SemanticsNode> {
        val hits = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) { if (match(node)) hits += node; node.children.forEach(::walk) }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return hits
    }
}
