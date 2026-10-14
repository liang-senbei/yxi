@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package app.yxi.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Code 风格可聚焦浮层（[CodePopup]）的焦点：离屏场景，点一个可点的 chip 开浮层，再用不同方式关掉。
 * 桌面端点 chip 时焦点先落到 chip 上，浮层关掉后还停在那里；输入区（[CodeInputArea]）里的浮层要把焦点还给输入框。
 * 输入卡右端的回车 / 停止也一样，点完要还给输入框。
 */
class CodePopupFocusTest {
    @Test
    fun choosingAnItemReturnsFocusToTheInput() = scenario(inArea = true, close = "item") {
        assertFalse(it.open, "浮层应已关掉"); assertTrue(it.input, "选完菜单项焦点应回输入框：$it")
    }

    @Test
    fun clickingOutsideReturnsFocusToTheInput() = scenario(inArea = true, close = "outside") {
        assertFalse(it.open, "浮层应已关掉"); assertTrue(it.input, "点外面关掉后焦点应回输入框：$it")
    }

    @Test
    fun escapeClosesThePopupAndReturnsFocus() = scenario(inArea = true, close = "escape") {
        assertTrue(it.escape, "Esc 应被浮层处理"); assertFalse(it.open, "Esc 应关掉浮层")
        assertTrue(it.input, "Esc 关掉后焦点应回输入框：$it")
    }

    @Test
    fun aDialogOpenedFromTheMenuKeepsTheKeysUntilItCloses() = scenario(inArea = true, close = "dialog") {
        assertTrue(it.dialog, "对话框应已打开"); assertTrue(it.other, "对话框里的输入框应拿到焦点：$it")
        assertTrue(it.dialogKeys > 0 && it.inputKeys == 0, "对话框开着时按键应归对话框，不落到底下的输入框：$it")
        val then = assertNotNull(it.then)
        assertFalse(then.dialog, "对话框应已关掉"); assertTrue(then.input, "对话框关掉后焦点应在输入框：$then")
        assertTrue(then.inputKeys > 0, "对话框关掉后按键应落在输入框：$then")
    }

    @Test
    fun aFieldOutsideTheAreaKeepsItsFocus() = scenario(inArea = true, close = "search") {
        assertTrue(it.other, "区外的搜索框应拿到焦点：$it"); assertFalse(it.input, "焦点已离开输入区，不该抢回：$it")
    }

    @Test
    fun popupsOutsideTheAreaLeaveFocusOnTheirTrigger() = scenario(inArea = false, close = "outside") {
        assertFalse(it.open, "浮层应已关掉"); assertFalse(it.input, "区外的浮层不管输入框：$it")
        assertTrue(it.chip, "区外的浮层关掉后焦点照旧留在触发它的控件上：$it")
    }

    @Test
    fun escapeClosesPopupsOutsideTheAreaToo() = scenario(inArea = false, close = "escape") {
        assertTrue(it.escape, "Esc 应被浮层处理"); assertFalse(it.open, "Esc 应关掉浮层")
    }

    @Test
    fun clickingSendReturnsFocusToTheInput() = composerScenario(running = false)

    @Test
    fun clickingStopReturnsFocusToTheInput() = composerScenario(running = true)

    /**
     * 输入卡（[CodeComposerCard]）右端的回车 / 停止同样是 clickable，按下就把焦点拿走，点完要还给输入框。
     * 焦点在不在输入框看按键落没落到它的 onKey 上（按下、抬起都会收到）；先点一个别的可点控件做对照：焦点留在那里，按键不该落到输入框。
     */
    private fun composerScenario(running: Boolean) = runBlocking<Unit>(Dispatchers.Swing) {
        val what = if (running) "停止" else "回车"
        var keys = 0
        var clicks = 0
        val scene = ImageComposeScene(800, 600, density = Density(1.25f), coroutineContext = Dispatchers.Swing) {
            val focus = remember { FocusRequester() }
            var draft by remember { mutableStateOf(TextFieldValue("甲")) }
            LaunchedEffect(Unit) { focus.requestFocus() }
            Column(Modifier.fillMaxSize().padding(40.dp)) {
                CodeComposerCard(draft, { draft = it }, "", focus, canSend = true, onSend = { clicks++ }, Modifier.width(400.dp),
                    running = running, onStop = { clicks++ }, onKey = { keys++; false })
                Text("乙", Modifier.padding(top = 40.dp).clickable { })
            }
        }
        var t = 0L
        suspend fun frames() = repeat(12) { Snapshot.sendApplyNotifications(); scene.render(t).close(); t += 16_000_000L; delay(30) }
        try {
            frames()
            pressEscape(scene); frames()
            assertTrue(keys > 0, "起始时按键应落在输入框")
            tap(scene, "乙"); frames()
            val control = keys
            pressEscape(scene); frames()
            assertEquals(control, keys, "对照：点了别的可点控件，焦点留在它身上，按键不该落到输入框")
            tapButton(scene); frames()
            assertEquals(1, clicks, "应点到$what")
            val after = keys
            pressEscape(scene); frames()
            assertTrue(keys > after, "点完$what 后按键应落在输入框")
        } finally { scene.close() }
    }

    private data class Seen(
        val input: Boolean, val chip: Boolean, val other: Boolean,
        val open: Boolean, val dialog: Boolean, val escape: Boolean,
        val inputKeys: Int = 0, val dialogKeys: Int = 0,
        /** close 为 dialog 时：关掉对话框、再按一次键之后。 */
        val then: Seen? = null,
    )

    private fun scenario(inArea: Boolean, close: String, verify: (Seen) -> Unit) =
        runBlocking(Dispatchers.Swing) { verify(play(inArea, close)) }

    /**
     * 场景自上而下：（区外的搜索框）→ 输入区 [输入框，inArea 时 chip 也在区里] →（inArea 为假时 chip 在区外）。
     * 浮层里只有一项「甲」；close 为 dialog / search 时，选完再打开对话框 / 区外的搜索框，两者都自己请求焦点。
     */
    private suspend fun play(inArea: Boolean, close: String): Seen {
        var input = false
        var chip = false
        var other = false
        var escape = false
        var inKeys = 0 // 输入框、对话框里的输入框各自收到的按键数（只计数，不处理）
        var dlgKeys = 0
        var open by mutableStateOf(false)
        var dialog by mutableStateOf(false)
        var search by mutableStateOf(false)
        val scene = ImageComposeScene(800, 600, density = Density(1.25f), coroutineContext = Dispatchers.Swing) {
            val focus = remember { FocusRequester() }
            var draft by remember { mutableStateOf(TextFieldValue("")) }
            LaunchedEffect(Unit) { focus.requestFocus() }
            val chipBox: @Composable () -> Unit = {
                Box(Modifier.padding(top = 200.dp)) {
                    Text("芯片", Modifier.onFocusChanged { chip = it.isFocused }.clickable { open = true })
                    if (open) CodePopup({ open = false }, side = PopupSide.Above) {
                        Text("甲", Modifier.clickable { open = false; dialog = close == "dialog"; search = close == "search" })
                    }
                }
            }
            Column(Modifier.fillMaxSize().padding(40.dp)) {
                if (search) {
                    val sf = remember { FocusRequester() }
                    var sv by remember { mutableStateOf(TextFieldValue("")) }
                    LaunchedEffect(Unit) { sf.requestFocus() }
                    BasicTextField(sv, { sv = it }, Modifier.width(300.dp).focusRequester(sf).onFocusChanged { other = it.isFocused })
                }
                CodeInputArea(focus) {
                    BasicTextField(draft, { draft = it }, Modifier.width(300.dp).onPreviewKeyEvent { inKeys++; false }.focusRequester(focus).onFocusChanged { input = it.isFocused })
                    if (inArea) chipBox()
                }
                if (!inArea) chipBox()
            }
            val dialogText: @Composable () -> Unit = {
                val df = remember { FocusRequester() }
                var dv by remember { mutableStateOf(TextFieldValue("")) }
                LaunchedEffect(Unit) { df.requestFocus() }
                BasicTextField(dv, { dv = it }, Modifier.width(200.dp).onPreviewKeyEvent { dlgKeys++; false }.focusRequester(df).onFocusChanged { other = it.isFocused })
            }
            if (dialog) WorkbenchDialog({ dialog = false }, confirmButton = {}, text = dialogText)
        }
        var t = 0L
        suspend fun frames() = repeat(12) { Snapshot.sendApplyNotifications(); scene.render(t).close(); t += 16_000_000L; delay(30) }
        try {
            frames()
            assertTrue(input, "起始时输入框应有焦点")
            tap(scene, "芯片"); frames()
            assertTrue(open, "点 chip 应打开浮层")
            when (close) {
                "outside" -> click(scene, Offset(780f, 580f))
                "escape" -> escape = pressEscape(scene)
                else -> tap(scene, "甲")
            }
            frames()
            val seen = Seen(input, chip, other, open, dialog, escape)
            if (close != "dialog") return seen
            // 对话框开着时按一次键，看落在哪一层；再关掉对话框（离屏场景里 Esc 关不掉 AlertDialog，直接改状态）按一次
            pressEscape(scene); frames()
            val whileOpen = seen.copy(inputKeys = inKeys, dialogKeys = dlgKeys)
            dialog = false; frames()
            pressEscape(scene); frames()
            return whileOpen.copy(then = Seen(input, chip, other, open, dialog, escape, inKeys, dlgKeys))
        } finally { scene.close() }
    }

    /** 合成一次 Esc 按下 + 抬起（带上 AWT 原生事件）；返回按下是否被处理。 */
    private fun pressEscape(scene: ImageComposeScene): Boolean {
        val src = java.awt.Canvas()
        fun key(id: Int, type: KeyEventType) = KeyEvent(Key.Escape, type, 0, false, false, false, false,
            java.awt.event.KeyEvent(src, id, System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_ESCAPE, java.awt.event.KeyEvent.CHAR_UNDEFINED))
        val handled = scene.sendKeyEvent(key(java.awt.event.KeyEvent.KEY_PRESSED, KeyEventType.KeyDown))
        scene.sendKeyEvent(key(java.awt.event.KeyEvent.KEY_RELEASED, KeyEventType.KeyUp))
        return handled
    }

    /** 点文字全等于 [label] 的节点（多个命中取最靠下的）。 */
    private fun tap(scene: ImageComposeScene, label: String) {
        val hits = find(scene) { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == label }
        click(scene, (hits.maxByOrNull { it.boundsInWindow.top } ?: error("没有「$label」")).boundsInWindow.center)
    }

    /** 点唯一一个按钮角色的节点（输入卡右端的回车 / 停止）。 */
    private fun tapButton(scene: ImageComposeScene) =
        click(scene, find(scene) { it.config.getOrNull(SemanticsProperties.Role) == Role.Button }.single().boundsInWindow.center)

    private fun find(scene: ImageComposeScene, match: (SemanticsNode) -> Boolean): List<SemanticsNode> {
        val hits = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) { if (match(node)) hits += node; node.children.forEach(::walk) }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return hits
    }

    private fun click(scene: ImageComposeScene, at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at)
        scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
    }
}
