@file:OptIn(ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION")   // SecurityManager：同 ClassicThemeShotsTest，只拿来拦 exec / 外连

package app.yxi.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * M1 验收 §4.3 ②（应用级）：M0 的 ssh-chat 场景 + 一份草稿，在同一个离屏 scene 里按
 * 经典浅 → Code 浅 → 经典深 → Code 深 → 经典浅 … 切 20 次，每次都画到稳定再核对：
 *  - 每步 LocalThemeSpec == ThemeSpec.of(风格, 明暗)（走的是设置页同一条 Store.setPref → 无参 YxiTheme）；
 *    Code 帧和同明暗的经典帧不同（真切过去了）；
 *  - 同一组合每次回来逐字节同一张图，第 20 步 = 开头那张经典浅色；
 *  - 同一组合每次回来语义树结构签名一致（层级 / 文字 / 角色 / 位置；节点 id 不比：M2 起两种风格是不同的外壳子树，
 *    切风格整组重建，id 必变）；
 *  - 业务状态是同一个对象：草稿 MutableState、暂存附件列表和里面的条目（注入一条「上传失败」的，两种风格的输入框都显示它）；
 *    输入框显示原文；连接 / 会话 / 标签页不变；没起进程、没外连（夹具没登录令牌，MeAuth.load 不联网；
 *    登录时侧栏每次进组合拉一次资料，同经典侧栏收起再展开，见 Sidebar.kt 的注释）。
 * 最后按用户的路径再走一遍：开设置 → 外观 → 切 Code → 切回经典 → 关设置，画面和结构签名回到开头。
 *
 * 和 ClassicThemeShotsTest 共用夹具目录和 Store 单例，所以只在 YXI_THEME_SHOT_MODE=switch 时跑（那边此时让位）；
 * 比的是同一次运行里的帧，不需要基线。不在 dev/workbench-test-classes.txt 里（PRD §10-4）。
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariables(
    EnabledIfEnvironmentVariable(named = "YXI_THEME_SHOT_FIXTURE", matches = ".+"),
    EnabledIfEnvironmentVariable(named = "YXI_THEME_SHOT_MODE", matches = "switch"),
)
class StyleSwitchStateTest {
    @Test
    fun `switching style 20 times keeps app state in place`() {
        val fixture = File(System.getenv("YXI_THEME_SHOT_FIXTURE")).also { require(it.isAbsolute) }.canonicalFile
        val profile = prepareShotProfile(fixture)
        val out = fixture.resolve("out-switch").also { it.deleteRecursively(); it.mkdirs() }
        val previous = System.getSecurityManager()
        val guard = NoExec()
        System.setSecurityManager(guard)
        val report = mutableListOf<String>()
        val failures = mutableListOf<String>()
        try {
            assertTrue(Store.dir.canonicalFile.startsWith(profile), "Store.dir 没落在夹具 profile 里：${Store.dir}")
            assertEquals("", Store.warning, "隔离 profile 里的 Store 不该有警告")
            System.setProperty("skiko.renderApi", "SOFTWARE")
            ImageIO.setUseCache(false)
            assertNull(Store.setPref("theme", "light")); assertNull(Store.setPref(UiStyle.PREF, UiStyle.Classic.key))
            assertNull(Store.setPref("hostScope", ""))
            runBlocking(Dispatchers.Swing) {
                val hidden = ComposeWindow()
                check(!hidden.isDisplayable)
                val scope = object : FrameWindowScope { override val window: ComposeWindow get() = hidden }
                try { switchAndCheck(scope, guard, out, report, failures) } finally { hidden.dispose() }
            }
        } finally {
            System.setSecurityManager(previous)
            val text = (listOf("fixture=$fixture") + report + failures.map { "FAIL $it" }).joinToString("\n")
            out.resolve("report.txt").writeText(text + "\n")
            println(text)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private suspend fun switchAndCheck(scope: FrameWindowScope, guard: NoExec, out: File, report: MutableList<String>, failures: MutableList<String>) {
        val state = AppState().apply { startupRestored = true }   // 跳过 HostEffects 的开机恢复 / 自动重连
        try {
            remote(state, tab = 0)
            val key = taskNavigationKey(HOST, SESSION)
            val typed = "切换风格时这句草稿不能丢"
            val draft = mutableStateOf(TextFieldValue(typed, TextRange(typed.length)))
            state.chatDrafts[key] = draft
            // 不经 SFTP：直接放一条失败态的（静态，不转圈），和上传过又失败的条目一样留在输入框上
            val attached = DraftAttach("说明.pdf", false, 1024, "shot-1") { error("不该读附件内容") }.also { it.state = DraftState.Failed("示例失败") }
            val staged = state.chatAttachments.of(key).also { it += attached }
            val conn = checkNotNull(state.conn)
            val probe = StyleProbe()
            val win = WindowState(placement = WindowPlacement.Maximized, size = DpSize(2048.dp, 1104.dp))
            val scene = ImageComposeScene(2560, 1380, density = Density(1.25f), coroutineContext = Dispatchers.Swing) {
                ShotFrame(state, scope, win) { probe.Content(); App(state) }
            }
            try {
                val clock = SceneClock(scene)
                val firstPng = mutableMapOf<String, ByteArray>()
                val firstTree = mutableMapOf<String, List<String>>()
                /** 画到稳定再核对一步；返回这一帧的语义树。 */
                suspend fun verify(step: String, style: UiStyle, dark: Boolean): ShotSemantics {
                    val combo = "${style.key}-${if (dark) "dark" else "light"}"
                    val started = System.nanoTime()
                    val png = clock.settle()
                    if (probe.spec != ThemeSpec.of(style, dark)) failures += "$step：LocalThemeSpec 没跟上偏好（期望 $combo）"
                    val ref = firstPng[combo]
                    val diff = if (ref == null) { firstPng[combo] = png; out.resolve("$combo.png").writeBytes(png); 0 }
                        else diffPixels(ref, png, out.resolve("$step-$combo.diff.png")).also {
                            if (it != 0) { out.resolve("$step-$combo.png").writeBytes(png); failures += "$step：回到 $combo 和第一次不是同一张图（${if (it < 0) "尺寸不同" else "$it 个像素不同"}）" }
                        }
                    val tree = shotSemantics(scene)
                    val before = firstTree.getOrPut(combo) { tree.signature }
                    if (tree.signature != before) {
                        val at = tree.signature.indices.firstOrNull { it >= before.size || tree.signature[it] != before[it] } ?: tree.signature.size
                        failures += "$step：回到 $combo 语义树和第一次不同（${before.size} → ${tree.signature.size} 行，第 $at 行起：" +
                            "「${before.getOrNull(at)?.take(120)}」→「${tree.signature.getOrNull(at)?.take(120)}」）"
                    }
                    val blocked = guard.drain()
                    if (blocked.isNotEmpty()) failures += "$step：拦下了 exec / 外连 $blocked"
                    report += "$step $combo diff=$diff nodes=${tree.signature.size} ${(System.nanoTime() - started) / 1_000_000}ms"
                    return tree
                }
                /** 输入框里还是原来的草稿、暂存区里还是那条附件（文件名和「上传失败」都画出来了）。 */
                fun ShotSemantics.keptInput(step: String) {
                    if (typed !in edits) failures += "$step：输入框里不是原来的草稿"
                    if ("说明.pdf" !in texts || "上传失败" !in texts) failures += "$step：输入框上没有那条暂存附件"
                }
                // 第 0 步是开头的经典浅色；之后每步都换风格、每两步换一次明暗，四种组合轮着来，第 20 步回到经典浅色
                for (i in 0..20) {
                    val style = if (i % 2 == 0) UiStyle.Classic else UiStyle.Code
                    val dark = (i / 2) % 2 == 1
                    if (i > 0) {
                        assertNull(Store.setPref(UiStyle.PREF, style.key, "界面风格没存上"), "第 $i 步风格没存上")
                        val wasDark = ((i - 1) / 2) % 2 == 1
                        if (dark != wasDark) assertNull(Store.setPref("theme", if (dark) "dark" else "light", "明暗没存上"), "第 $i 步明暗没存上")
                    }
                    verify("step$i", style, dark).keptInput("step$i")
                }
                for (mode in listOf("light", "dark")) {
                    val classic = checkNotNull(firstPng["classic-$mode"]); val code = checkNotNull(firstPng["code-$mode"])
                    if (classic contentEquals code) failures += "Code $mode 和经典 $mode 画出来一样：风格没切过去"
                }
                // 用户的实际路径：设置 → 外观 → 切 Code → 切回经典 → 关设置。设置对话框在外壳之外（HostDialogs），切风格不重建它；
                // 底下的外壳照样整组换掉
                state.settingsSection = "外观"; state.showSettings = true
                val settingsClassic = clock.settle().also { out.resolve("settings-classic.png").writeBytes(it) }
                assertNull(Store.setPref(UiStyle.PREF, UiStyle.Code.key, "界面风格没存上"))
                val settingsCode = clock.settle().also { out.resolve("settings-code.png").writeBytes(it) }
                if (probe.spec != ThemeSpec.of(UiStyle.Code, false)) failures += "设置页里切 Code：LocalThemeSpec 没跟上"
                if (settingsClassic contentEquals settingsCode) failures += "设置页里切 Code 后画面没变"
                assertNull(Store.setPref(UiStyle.PREF, UiStyle.Classic.key, "界面风格没存上"))
                clock.settle()
                // 设置对话框一进组合就同步 reg query 一次（Settings.kt 开机自启的初值，拦下按未开启）；切风格要是把对话框拆了重建就是两次
                val settingsBlocked = guard.drain()
                if (settingsBlocked != listOf("exec:reg")) failures += "设置页阶段被拦的应该正好是一次 reg query：$settingsBlocked"
                state.showSettings = false
                verify("settings-closed", UiStyle.Classic, false).keptInput("关设置后")
                check(!NativeOverlays.active) { "关了设置 NativeOverlay 租约还在" }
                assertSame(draft, state.chatDrafts[key], "草稿 MutableState 被换掉了")
                assertEquals(setOf(key), state.chatDrafts.keys, "多出了别的草稿槽")
                assertEquals(typed, draft.value.text)
                assertSame(staged, state.chatAttachments.of(key), "暂存附件列表被换掉了")
                assertEquals(listOf(attached), staged.toList(), "暂存附件变了"); assertEquals(1, state.chatAttachments.draftCount())
                assertEquals(DraftState.Failed("示例失败"), attached.state)
                assertSame(conn, state.conn); assertEquals(listOf(conn), state.conns.toList())
                assertEquals(SESSION, state.session); assertEquals(0, state.tab)
                assertEquals(Conn.Status.Idle, conn.status, "切风格不该碰连接")
                assertEquals(1, probe.identities.size, "根部 remember 被重建过")
                assertEquals(1, probe.launched, "根部 LaunchedEffect 重启过"); assertEquals(0, probe.disposed)
                assertTrue(probe.compositions >= 23, "只重组了 ${probe.compositions} 次")
            } finally { scene.close() }
            // close 之后根部 LaunchedEffect 的协程要回 EDT 才走 finally
            repeat(20) { if (probe.disposed == 0) delay(50) }
            assertEquals(1, probe.disposed, "scene 关掉后根部 effect 应该正好退出一次")
        } finally {
            state.conn?.close()
            state.codexWorkspace.close(); state.chatAttachments.close(); state.remoteOpenCodeTasks.close(); state.remoteAcpTasks.close(); state.closeLocalFeatures()
        }
    }
}

/** 挂在 YxiTheme 里、App 旁边：记下每次组合看到的 ThemeSpec、remember 的实例、LaunchedEffect 起停次数。 */
private class StyleProbe {
    var spec: ThemeSpec? = null
    var compositions = 0
    var launched = 0
    var disposed = 0
    val identities = mutableSetOf<Any>()
    @Composable
    fun Content() {
        val seen = LocalThemeSpec.current
        val identity = remember { Any() }
        SideEffect { compositions++; identities += identity; spec = seen }
        LaunchedEffect(Unit) { launched++; try { awaitCancellation() } finally { disposed++ } }
    }
}

/** 一帧的语义树（未合并）：[signature] 每节点一行「层级 文字 / 可编辑文本 / 角色 / 位置」，不含 id；[edits] 是输入框里的文本。 */
private class ShotSemantics(val signature: List<String>, val texts: Set<String>, val edits: Set<String>)

private fun shotSemantics(scene: ImageComposeScene): ShotSemantics {
    val lines = mutableListOf<String>(); val texts = mutableSetOf<String>(); val edits = mutableSetOf<String>()
    fun walk(node: SemanticsNode, depth: Int) {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        val edit = node.config.getOrNull(SemanticsProperties.EditableText)?.text
        text?.let { texts += it }; edit?.let { edits += it }
        lines += "$depth t=$text e=$edit r=${node.config.getOrNull(SemanticsProperties.Role)} ${node.boundsInWindow}"
        for (child in node.children) walk(child, depth + 1)
    }
    for (owner in scene.semanticsOwners) walk(owner.unmergedRootSemanticsNode, 0)
    return ShotSemantics(lines, texts, edits)
}
