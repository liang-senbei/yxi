@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package app.yxi.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 斜杠菜单（M4k）的离屏交互：[CodeLocalComposer] 摆在场景里，按真的键、点真的行。
 * 核三件事：输入框失焦菜单就收起、焦点回来再弹；命令表晚到（主机的那份）时按 ↓ 选中的那条不变；点一行补进草稿、焦点还在输入框。
 */
class SlashMenuUiTest {
    private val commands = listOf(SlashCommand("compact", "压掉上下文"), SlashCommand("context", "上下文占了多少"), SlashCommand("cost", "花了多少"))

    /** 场景里的状态：草稿、命令表、焦点放不放到旁边那个输入框（[away]）、落到输入卡 onKey 上的按键数。 */
    private class Harness(val scene: ImageComposeScene, val draft: MutableState<TextFieldValue>, val list: MutableState<List<SlashCommand>>,
        val away: MutableState<Boolean>, val keys: IntArray) {
        private var t = 0L
        suspend fun frames() = repeat(12) { Snapshot.sendApplyNotifications(); scene.render(t).close(); t += 16_000_000L; delay(30) }
        /** 浮层里的命令名（浮层是主层之外的语义层）。 */
        fun rows(): List<String> {
            val out = mutableListOf<String>()
            fun walk(node: SemanticsNode) {
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.takeIf { it.startsWith("/") }?.let { out += it }
                node.children.forEach(::walk)
            }
            scene.semanticsOwners.drop(1).forEach { walk(it.unmergedRootSemanticsNode) }
            return out
        }
        fun press(key: Key, code: Int): Boolean {
            val src = java.awt.Canvas()
            fun event(id: Int, type: KeyEventType) = KeyEvent(key, type, 0, false, false, false, false,
                java.awt.event.KeyEvent(src, id, System.currentTimeMillis(), 0, code, java.awt.event.KeyEvent.CHAR_UNDEFINED))
            val handled = scene.sendKeyEvent(event(java.awt.event.KeyEvent.KEY_PRESSED, KeyEventType.KeyDown))
            scene.sendKeyEvent(event(java.awt.event.KeyEvent.KEY_RELEASED, KeyEventType.KeyUp))
            return handled
        }
        fun tap(label: String) {
            val hits = mutableListOf<SemanticsNode>()
            fun walk(node: SemanticsNode) {
                if (node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == label) hits += node
                node.children.forEach(::walk)
            }
            scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
            val at = (hits.maxByOrNull { it.boundsInWindow.top } ?: error("没有「$label」")).boundsInWindow.center
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
        }
    }

    private fun scenario(initial: String, body: suspend Harness.() -> Unit) = runBlocking(Dispatchers.Swing) {
        val draft = mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
        val list = mutableStateOf(commands)
        val away = mutableStateOf(false)
        val keys = IntArray(1)
        val scene = ImageComposeScene(1000, 700, density = Density(1.25f), coroutineContext = Dispatchers.Swing) {
            val focus = remember { FocusRequester() }
            val other = remember { FocusRequester() }
            LaunchedEffect(away.value) { if (away.value) other.requestFocus() else focus.requestFocus() }
            Column(Modifier.fillMaxSize().padding(start = 40.dp, top = 300.dp)) {
                BasicTextField("", {}, Modifier.width(200.dp).focusRequester(other))
                CodeLocalComposer(draft.value, { draft.value = it }, "", focus, canSend = true, onSend = {}, running = false, onStop = {},
                    onKey = { keys[0]++; false }, slashCommands = list.value, start = {}, end = {})
            }
        }
        try { val harness = Harness(scene, draft, list, away, keys); harness.frames(); harness.body() } finally { scene.close() }
    }

    @Test fun focusLossHidesTheMenuAndFocusComingBackShowsIt() = scenario("/") {
        assertEquals(listOf("/compact", "/context", "/cost"), rows())
        away.value = true; frames()
        assertEquals(emptyList(), rows(), "输入框失焦（Shift+Tab、切任务）菜单应收起")
        away.value = false; frames()
        assertEquals(listOf("/compact", "/context", "/cost"), rows(), "焦点回到输入框、草稿没变，菜单应再弹")
    }

    @Test fun aLateListKeepsTheHighlightedCommand() = scenario("/co") {
        assertTrue(press(Key.DirectionDown, java.awt.event.KeyEvent.VK_DOWN)); frames()
        // 主机的命令表晚到，前面多了一条：高亮仍是按 ↓ 选中的 context，不是回到第一行
        list.value = listOf(SlashCommand("codegen", "生成代码")) + commands; frames()
        assertEquals(listOf("/codegen", "/compact", "/context", "/cost"), rows())
        assertTrue(press(Key.Enter, java.awt.event.KeyEvent.VK_ENTER)); frames()
        assertEquals("/context ", draft.value.text)
        assertEquals(emptyList(), rows())
    }

    @Test fun clickingARowCompletesTheDraftAndKeepsFocusInTheInput() = scenario("/") {
        tap("/cost"); frames()
        assertEquals("/cost ", draft.value.text)
        assertEquals(emptyList(), rows())
        val before = keys[0]
        press(Key.Escape, java.awt.event.KeyEvent.VK_ESCAPE); frames()
        assertTrue(keys[0] > before, "点完一行后按键应落在输入框")
    }
}
