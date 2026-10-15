@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
@file:Suppress("DEPRECATION")   // SecurityManager：同 ClassicThemeShotsTest，只拿来拦 exec / 外连

package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import app.yxi.agent.Groups
import app.yxi.agent.Pending
import app.yxi.agent.Session
import app.yxi.agent.SessionState
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * M2 第 4 步（4f-6）：「Code」风格的离屏截图，给骨架量值对规格表（PRD §7、§8 M2）。和 M0 同规格：2560×1380、density 1.25
 * （= 125% 下最大化 2048×1104），每张另出一份语义节点清单（坐标换成 dp，直接对规格表的数）。夹具隔离、拦 exec / 外连、
 * 画到稳定都和 [ClassicThemeShotsTest] 同一套，产物写到 <fixture>/out-code，不进仓库。Code 还在做，先不设基线：
 * 这里只保证画得出、画得稳、不越界。只在 YXI_THEME_SHOT_MODE=code 时跑（经典截图此时让位）；不在 dev/workbench-test-classes.txt 里。
 */
@EnabledOnOs(OS.WINDOWS, OS.MAC)
@EnabledIfEnvironmentVariables(
    EnabledIfEnvironmentVariable(named = "YXI_THEME_SHOT_FIXTURE", matches = ".+"),
    EnabledIfEnvironmentVariable(named = "YXI_THEME_SHOT_MODE", matches = "code"),
)
class CodeThemeShotsTest {
    @Test
    fun `code style shots settle without exec`() {
        val fixture = File(System.getenv("YXI_THEME_SHOT_FIXTURE")).also { require(it.isAbsolute) }.canonicalFile
        val profile = prepareShotProfile(fixture)
        val out = fixture.resolve("out-code").also { it.deleteRecursively(); it.mkdirs() }
        val previous = System.getSecurityManager()
        val guard = NoExec()
        System.setSecurityManager(guard)
        val report = mutableListOf<String>()
        val failures = mutableListOf<String>()
        try {
            assertTrue(Store.dir.canonicalFile.startsWith(profile), "Store.dir 没落在夹具 profile 里：${Store.dir}")
            assertEquals("", Store.warning, "隔离 profile 里的 Store 不该有警告")
            assertNull(Store.setPref(UiStyle.PREF, UiStyle.Code.key))
            System.setProperty("skiko.renderApi", "SOFTWARE")
            ImageIO.setUseCache(false)
            runBlocking(Dispatchers.Swing) {
                val hidden = ComposeWindow()
                check(!hidden.isDisplayable)
                val scope = object : FrameWindowScope { override val window: ComposeWindow get() = hidden }
                try {
                    assertEquals(emptyList(), guard.drain(), "准备阶段就有被拦的 exec / 外连")
                    // 调试时可以只跑几张：YXI_THEME_SHOT_SCENES=名字,名字（不设就是全部；基准只认全部跑的那一轮）
                    val only = System.getenv("YXI_THEME_SHOT_SCENES")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                    for (scene in codeScenes().filter { only == null || it.name in only }) for (theme in listOf("light", "dark")) {
                        val name = "$theme/${scene.name}"
                        val started = System.nanoTime()
                        val verdict = try {
                            val (png, nodes) = shootCode(scene, theme, scope)
                            out.resolve("$name.png").also { it.parentFile.mkdirs() }.writeBytes(png)
                            out.resolve("$name.nodes.txt").writeText(nodes.joinToString("\n") + "\n")
                            "ok nodes=${nodes.size}"
                        } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                            if (e is VirtualMachineError) throw e
                            failures += "$name：" + e.stackTraceToString().lines().take(14).joinToString("\n    "); "error"
                        }
                        val blocked = guard.drain()
                        val denied = blocked.filterNot { it in scene.allowed }
                        if (denied.isNotEmpty()) failures += "$name：拦下了场景外的 exec / 外连 $denied"
                        report += "$name $verdict ${(System.nanoTime() - started) / 1_000_000}ms" + (if (blocked.isEmpty()) "" else " blocked=$blocked")
                    }
                } finally { hidden.dispose() }
            }
        } finally { System.setSecurityManager(previous) }
        val text = (listOf("fixture=$fixture") + report + failures.map { "FAIL $it" }).joinToString("\n")
        out.resolve("report.txt").writeText(text + "\n")
        println(text)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
    /** 一张图：写偏好 → 造 AppState、摆场景（不连、不扫）→ 离屏画到稳定；要开浮层的，点一下再画到稳定。 */
    private suspend fun shootCode(scene: CodeScene, theme: String, scope: FrameWindowScope): Pair<ByteArray, List<String>> {
        Store.setPref("theme", theme)
        Store.setPref("hostScope", "")
        Store.setPref(TranscriptView.PREF, TranscriptView.Normal.key)   // M3h：对话视图默认档每张都回到普通，改它的场景不串到后面
        MacChrome.native = false   // Mac1 第二轮：Mac 原生外观只在 mac-native-* 场景里打开
        Store.setPref(FolderPermissions.PREF, "[]")   // M4i：按文件夹记住的权限模式每张都清空，记了的场景不串到后面
        UsageHistory.clearForFixture()   // M4n：统计卡的数据是全局的，每张都清空（第一遍就串到了后面 24 张新会话页）
        BranchChanges.local = { null }; BranchChanges.remote = { _, _ -> null }   // M4p：分支改动条只在摆了数的场景里出现，也不起 git、不连主机
        RemoteSlashCommands.clearForFixture()   // M4o：主机的命令表、技能表也是全局的（斜杠菜单、技能页的场景各自放）
        val state = AppState().apply { startupRestored = true }   // 跳过 HostEffects 的开机恢复 / 自动重连
        state.navigation.setMode("全部")   // 全部 / 待处理 / 归档落在夹具 profile 里，local-archived 改过（值没变不写盘）
        try {
            scene.arrange(state)
            check(!NativeOverlays.active) { "上一张遗留了 NativeOverlay 租约" }
            val win = WindowState(placement = WindowPlacement.Maximized, size = DpSize(2048.dp, 1104.dp))
            val image = ImageComposeScene(2560, 1380, density = Density(1.25f), coroutineContext = Dispatchers.Swing) {
                ShotFrame(state, scope, win) { val body = scene.content; if (body != null) body() else App(state) }
            }
            try {
                val clock = SceneClock(image)
                var png = clock.settle()
                for (label in scene.taps) {
                    if (label == ENTER) pressEnter(image) else tapLowest(image, label)
                    if (label.startsWith("~")) delay(900)   // tooltip 的 500ms 延时按真实时间走（Swing 调度器）
                    png = clock.settle()
                }
                val nodes = semanticsDump(image, 1.25f)
                scene.verify(state, nodes)
                return png to nodes
            } finally { image.close() }
        } finally {
            (state.conns + listOfNotNull(state.conn)).distinct().forEach { it.close() }   // 9c 起一张图会有几台主机
            state.codexWorkspace.close(); state.remoteOpenCodeTasks.close(); state.remoteAcpTasks.close(); state.closeLocalFeatures()
            shotServers.forEach { it.stop(0) }; shotServers.clear()
        }
    }
}

/**
 * 右侧面板（M4a）：语义节点里没有图标的描述，按位置核——每个标题字起于卡片左缘 + 13.6（外环 0.8 + 12.8），
 * 同一行右端（x 2011.2 = 填充右缘 2039.2 − 4 − 24，和标题字同一条中线）有关闭按钮；会话头的图标按钮（y 48）都在卡片左边。
 */
private fun checkPanels(nodes: List<String>, vararg cards: Pair<String, Float>, headerButtons: Int = 4) {
    fun num(line: String, key: String) = Regex("""$key=([0-9.]+)""").find(line)!!.groupValues[1].toFloat()
    val buttons = nodes.filter { it.contains("w=24.0 h=24.0 Button") }
    for ((title, left) in cards) {
        val head = nodes.firstOrNull { it.trimEnd().endsWith(" $title") && kotlin.math.abs(num(it, "x") - (left + 13.6f)) < 1f } ?: error("没有起于 ${left + 13.6f} 的「$title」面板标题")
        val mid = num(head, "y") + num(head, "h") / 2
        check(buttons.any { kotlin.math.abs(num(it, "x") - 2011.2f) < 1f && kotlin.math.abs(num(it, "y") + 12f - mid) < 1.5f }) { "「$title」面板标题那一行右端没有关闭按钮" }
    }
    val header = buttons.filter { num(it, "y") in 47f..49f && num(it, "x") > 300f && kotlin.math.abs(num(it, "x") - 2011.2f) >= 1f }
    check(header.size >= headerButtons && header.all { num(it, "x") + 24f <= cards.minOf { c -> c.second } }) { "会话头的按钮没缩到面板左边：$header" }
}

/**
 * Code 截图场景：[taps] 依次点文字是它的控件（同名取最靠下的一个：chips 在浮层下面；带「~」前缀的只悬停、等 tooltip；带「@」前缀的只按图标的
 * contentDescription 找；[ENTER] 是在拿着焦点的输入框里按回车）；[allowed] 同经典夹具；
 * [content] 不为空就画它、不画 App（同经典夹具的 approval-card）。[verify] 在点完、画稳之后拿状态和语义节点清单核结果，抛错就算这张失败。
 */
private class CodeScene(val name: String, val taps: List<String> = emptyList(), val allowed: Set<String> = emptySet(),
    val content: (@Composable () -> Unit)? = null, val verify: (AppState, List<String>) -> Unit = { _, _ -> }, val arrange: (AppState) -> Unit = {})

/** 规格 §3.8 / §3.2 / §3.1 / §3.3 / §3.4 要量的：新会话页空态、浮层和 tooltip，有会话时的 SSH 对话、审批卡、排队。 */
private fun codeScenes() = listOf(
    CodeScene("home") { codeHome(it) },
    CodeScene("home-permission", listOf("请求批准")) { codeHome(it) },
    CodeScene("home-runner", listOf("Claude Code")) { codeHome(it) },
    CodeScene("home-env", listOf(HOST.label)) { codeHome(it) },
    CodeScene("home-folder", listOf("demo-app")) { codeHome(it) },
    CodeScene("home-folder-hint", listOf("~demo-app")) { codeHome(it) },
    CodeScene("home-permission-again", listOf("请求批准", "请求批准")) { codeHome(it) },
    // M4n：新会话页的统计卡（规格表 §3.8）——这台服务器 ccusage 的近半年用量（夹具直接放数据，「今天」是固定时钟 2025-10-09）
    CodeScene("home-usage-card", verify = { _, nodes ->
        for (text in listOf("概览", "模型", "全部", "30 天", "7 天", "用量（tokens）", "最长连续", "最常用模型")) check(nodes.any { it.trimEnd().endsWith(" $text") }) { "统计卡没有「$text」" }
    }) { codeHome(it); UsageHistory.put(it.conn!!.host.id, shotUsageDays()) },
    // M4i：这个文件夹上次建会话用的是「自动审批」，新会话页的权限 chip 就从它开始（PRD §6，参照端按文件夹记住）
    CodeScene("home-permission-remembered", verify = { _, nodes ->
        check(nodes.any { it.trimEnd().endsWith(" 自动审批") } && nodes.none { it.trimEnd().endsWith(" 请求批准") }) { "权限 chip 没换成这个文件夹记住的「自动审批」" }
    }) { codeHome(it); val c = it.conn!!; FolderPermissions.remember(c.host.id, it.newSessionDraft.directoryFor(c), app.yxi.agent.PermissionMode.Auto) },
    CodeScene("ssh-chat") { remote(it, tab = 0) },
    // SSH 对话的工具组（M3e）：Code 的摘要行 + 卡片，点开的条目里是原来的富渲染 ToolCard（这里是 Bash：命令和输出）
    CodeScene("ssh-chat-tool", listOf("运行了一条命令", "./gradlew test --offline")) { remote(it, tab = 0) },
    // M4p：SSH 对话的分支改动条（服务器上的那段脚本由夹具换成固定的数，不连主机）；点徽章切到「改动」
    CodeScene("ssh-chat-branch-bar", verify = { _, nodes ->
        for (text in listOf("main", "+48", "−7")) check(nodes.any { it.trimEnd().endsWith(" $text") }) { "分支改动条里没有「$text」" }
    }) { remote(it, tab = 0); BranchChanges.remote = { _, _ -> BranchStat("main", "4c1d9e2a7b30", 3, 48, 7) } },
    CodeScene("ssh-chat-branch-bar-open", listOf("+48"), verify = { state, _ -> check(state.tab == 3) { "点徽章没切到「改动」：tab=${state.tab}" } }) {
        remote(it, tab = 0); BranchChanges.remote = { _, _ -> BranchStat("main", "4c1d9e2a7b30", 3, 48, 7) }
    },
    // M4k：斜杠菜单（SSH 上的 Claude 会话）：草稿「/mo」筛出内置的 /model 和这台主机上的 /mobile-release（主机的命令表先放进缓存，不连主机）；
    // 浮层在输入卡上方 6、和卡片同宽，第一行高亮
    CodeScene("ssh-chat-slash", verify = { _, nodes ->
        for (text in listOf("/model", "换模型", "/mobile-release", "自定义命令")) check(nodes.any { it.trimEnd().endsWith(" $text") }) { "斜杠菜单里没有「$text」" }
        check(nodes.none { it.trimEnd().endsWith(" /compact") || it.trimEnd().endsWith(" /memory") }) { "「/mo」没筛掉别的命令" }
        checkSlashMenu(nodes, "/mo", "/mobile-release")
    }) { sshSlash(it) },
    // 菜单开着时回车补进高亮的那条（「/model 」、菜单收起），不发送、不排队
    CodeScene("ssh-chat-slash-enter", listOf(ENTER), verify = { state, nodes ->
        val key = taskNavigationKey(HOST, SESSION)
        check(state.chatDrafts[key]?.value?.text == "/model ") { "回车没把 /model 补进草稿：${state.chatDrafts[key]?.value?.text}" }
        check(state.instructions.entries.none { it.taskKey == key }) { "回车把草稿发出去了" }
        check(nodes.none { it.trimEnd().endsWith(" /mobile-release") }) { "补完后菜单还开着" }
    }) { sshSlash(it) },
    // SSH 会话的标题菜单里的「对话视图」（M3g）：三档平铺、当前档（普通）打勾
    CodeScene("ssh-title-view-menu", listOf("@任务操作"), verify = { _, nodes ->
        for (text in listOf("对话视图 · Ctrl+O", "普通", "思考", "详细")) check(nodes.any { it.endsWith(" $text") }) { "标题菜单里没有「$text」" }
    }) { remote(it, tab = 0) },
    // 右侧面板（M4a，规格表 §3.12）：对话留在左边，面板是浮起的卡片（顶 y 44、离右 / 下 8、圆角 10，头部 32：标题 + 关闭）；
    // 终端宽 578.4，改动 / 文件 / 预览宽 1040；两张都开时上下分屏。会话头的右缘缩到卡片左边
    CodeScene("ssh-panel-terminal", verify = { _, nodes -> checkPanels(nodes, "终端" to 1461.6f) }) { remote(it, tab = 1) },
    // M4d：SSH 会话的「文件」卡片也是左树右预览；夹具没连服务器，树那边是读不到目录的说明
    CodeScene("ssh-panel-files", verify = { _, nodes -> checkPanels(nodes, "文件" to 1000f); check(nodes.any { "筛选文件" in it }) { "没有过滤框" } }) { remote(it, tab = 2) },
    CodeScene("ssh-panel-changes", verify = { _, nodes -> checkPanels(nodes, "改动" to 1000f) }) { remote(it, tab = 3) },
    CodeScene("ssh-panel-split", verify = { _, nodes -> checkPanels(nodes, "终端" to 1000f, "已打开的文件" to 1000f) }) {
        remote(it, tab = 1); it.filePanelOpen = true
    },
    // Mac 原生（Mac1 第二轮，规格表 §7.1）：没有自绘的窗口三键（左上是系统红绿灯，离屏画不出来），左上四个图标右移到灯后面（各加 72 − 14，侧栏开关 x 102.4）；
    // 快捷键写成 ⌘ 的样子（菜单里 ⌘N / ⌘K / ⌘B / ⌘, / ⌘/，tooltip 里 ⌘B），Ctrl 不再出现
    CodeScene("mac-native-menu", listOf("@菜单"), verify = { _, nodes ->
        for (key in listOf("⌘N", "⌘K", "⌘B", "⌘,", "⌘/")) check(nodes.any { it.trimEnd().endsWith(" $key") }) { "菜单里没有「$key」" }
        check(nodes.none { "Ctrl+" in it }) { "Mac 原生时还写着 Ctrl" }
        check(nodes.any { it.contains("x=102.4 y=4.0 w=28.0 h=28.0 Button") }) { "侧栏开关没挪到红绿灯后面（应在 44 + 72 − 14）" }
    }) { remote(it, tab = 0); MacChrome.native = true },
    CodeScene("mac-native-tooltip", listOf("~收起侧栏"), verify = { _, nodes ->
        check(nodes.any { it.trimEnd().endsWith(" ⌘B") }) { "tooltip 里没有 ⌘B" }
    }) { remote(it, tab = 0); MacChrome.native = true },
    // M4e：右侧面板的快捷键写在入口上——会话头 ⋮ 里「文件」右侧 Ctrl+Shift+F，「网页预览」的 tooltip 带 Ctrl+Shift+P
    CodeScene("ssh-more-menu", listOf("@更多"), verify = { _, nodes ->
        check(nodes.any { it.trimEnd().endsWith(" Ctrl+Shift+F") }) { "「文件」右侧没有 Ctrl+Shift+F" }
    }) { remote(it, tab = 0) },
    CodeScene("ssh-preview-tooltip", listOf("~网页预览"), verify = { _, nodes ->
        check(nodes.any { it.trimEnd().endsWith(" Ctrl+Shift+P") }) { "tooltip 里没有 Ctrl+Shift+P" }
    }) { remote(it, tab = 0) },
    CodeScene("approval-card", content = { ApprovalDockShot() }),
    // 新会话页的两侧边距（M2-d）：主区只有 600 宽时，骨架两侧各留 40，输入卡宽 600 − 80 = 520、左缘 288 + 40（PRD §5.1）。只画骨架和占位的 chip / 输入卡
    CodeScene("home-narrow", content = { NarrowHomeShot() }, verify = { _, nodes ->
        val card = nodes.singleOrNull { it.endsWith(" 窄窗口里的输入卡") } ?: error("没有输入卡")
        // 文字节点不含占位卡自己的内边距 12：卡片 x 328、宽 520 → 文字 x 340、宽 496
        check(" x=340.0 " in " " + card.trim() + " " && " w=496.0 " in " " + card.trim() + " ") { "两侧不是 40：$card" }
    }) { narrowHomeState = it },
    // 排队（§3.4）放最后：指令记录落在夹具 profile 的 instructions.json，后面的场景都会读到；固定 id，重复入队是幂等的
    CodeScene("ssh-queue") { queued(it, 2, automatic = true) },
    CodeScene("ssh-queue-manual") { queued(it, 4, automatic = false) },
    CodeScene("ssh-queue-menu", listOf("更多指令操作")) { queued(it, 4, automatic = false) },
    // 本机（M2 第 8 步）：侧栏的会话列表、搜索框、筛选浮层的本机段，本机首页的三个 chip。放在最后，前面各张的输入不变
    CodeScene("local-home") { localHome(it) },
    CodeScene("local-hover", listOf("~修复解析器的空输入崩溃")) { localHome(it) },
    CodeScene("local-search", listOf("搜索会话")) { localHome(it) },
    CodeScene("local-filter", listOf("筛选")) { localHome(it) },
    CodeScene("local-env", listOf("本机")) { localHome(it) },
    CodeScene("local-folder", listOf("demo-app")) { localHome(it) },
    CodeScene("local-runner", listOf("Claude Code")) { localHome(it) },
    // SSH 侧栏（第 9 步 9c）：三台主机、分组、托管任务、置顶 / 收藏。放在最后：hosts.json 和导航记录落在夹具 profile，前面各张的输入不变
    CodeScene("ssh-list") { sshList(it, saved = false) },
    // 切换任务 Ctrl+K（M4g，规格表 §3.6；放在 ssh-list 后面：sshList 会把主机和侧栏整理落进夹具 profile，放前面会串到 ssh-queue 那几张）：遮罩 + 顶在 264 的 672 宽面板，输入区 64、结果行 36、第一行是当前行
    CodeScene("code-task-switcher", verify = { _, nodes ->
        check(nodes.any { "搜索任务、项目路径、服务器或地区" in it }) { "没有搜索框的占位字" }
        check(nodes.any { it.contains("x=688.0 y=264.0 w=672.0") }) { "面板不在 x 688、y 264、宽 672：" + nodes.filter { "w=672.0" in it } }
    }) { sshList(it, saved = false); it.showTaskSwitcher = true },
    CodeScene("ssh-list-saved") { sshList(it, saved = true) },
    CodeScene("ssh-hover", listOf("~把部署脚本从 Bash 迁到 Python，并补上回滚和健康检查")) { sshList(it, saved = false) },
    // 行 ⋮ 只在 hover 时出现，先悬停再点；同名的置顶行在上面，取最靠下的是分组里那行
    CodeScene("ssh-row-menu", listOf("~web", "会话操作")) { sshList(it, saved = false) },
    CodeScene("ssh-row-menu-codex", listOf("~登录页改版", "会话操作")) { sshList(it, saved = false) },
    // 置顶 / 收藏区小标题的「∨」（contentDescription 唯一）：点开在「置顶」「收藏」之间选
    CodeScene("ssh-saved-menu", listOf("切换置顶 / 收藏")) { sshList(it, saved = false) },
    // 从「置顶」经浮层选「收藏」：列表应和 ssh-list-saved 相同（点完鼠标停在原处，下面那行可能带 hover），偏好写进夹具 profile
    CodeScene("ssh-saved-choose", listOf("切换置顶 / 收藏", "收藏")) { sshList(it, saved = false) },
    // 主机 ⋮：三台都有「主机操作」，取最靠下的是连接失败那台（菜单是「连接」+ 禁用的「新建会话」）
    CodeScene("ssh-host-menu", listOf("主机操作")) { sshList(it, saved = false) },
    // 分组 ⋮：取最靠下的「未分组」；只在这张给它加一条长路径的会话，看目录项在浮层里怎么收（会话列表不落盘，不影响别的场景）
    CodeScene("ssh-group-menu", listOf("分组操作")) {
        sshList(it, saved = false)
        it.conns.first().apply { sessions = sessions + Session("cc-portal", 1, false, "/home/dev/workspace/customer-portal-frontend", 0L, SessionState.Idle, "", 0.0, cmd = "claude", runtimeId = "shot-cc-portal") }
    },
    // 本机 Claude 对话（M2-b）：打开 Claude 那条（threadId 换成 UUID，原生历史按它找文件；只改内存里的索引，不落盘）。夹具里没有原生历史目录、
    // 也没有控制器，画的是没恢复时的停靠区：历史报错 +「重新加载历史」、恢复提示 + 运行器 chip、输入卡（恢复会话后可发送）
    CodeScene("local-claude") {
        localHome(it)
        val records = it.localClaudeTasks.registry.records
        records[0] = records[0].copy(threadId = "00000000-0000-4000-8000-000000000001")
        it.localSelectedTaskKey = records[0].key
    },
    // M4k：同一页、草稿只有「/」：还没恢复（没有 initialize 回包）时列 Claude Code 的内置命令，整份 21 条只露 8 行、在浮层里滚
    CodeScene("local-claude-slash", verify = { _, nodes ->
        for (text in listOf("/compact", "压掉上下文，留个摘要接着聊", "/context")) check(nodes.any { it.trimEnd().endsWith(" $text") }) { "斜杠菜单里没有「$text」" }
        checkSlashMenu(nodes, "/", null, rows = 8)
    }) {
        localHome(it)
        val records = it.localClaudeTasks.registry.records
        records[0] = records[0].copy(threadId = "00000000-0000-4000-8000-000000000001")
        it.localSelectedTaskKey = records[0].key
        it.chatDrafts[records[0].key] = androidx.compose.runtime.mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("/", androidx.compose.ui.text.TextRange(1)))
    },
    // 本机 ACP 对话（M2-b b3）：假传输只答 initialize / session/new（模式在页脚左段，模型在右段），消息直接写进控制器；
    // 记录只进内存里的索引（不落盘）。后两张点开模型 / 模式浮层
    CodeScene("local-acp") { localAcp(it) },
    CodeScene("local-acp-model", listOf("Gemini 2.5 Pro")) { localAcp(it) },
    CodeScene("local-acp-mode", listOf("Default")) { localAcp(it) },
    // M4k：ACP 的斜杠菜单只列运行器报的命令（session/new 之后的 available_commands_update），不混内置；草稿只有「/」
    CodeScene("local-acp-slash", verify = { state, nodes ->
        val c = checkNotNull(state.localAcpTasks.controllers.values.singleOrNull()) { "没有控制器" }
        check(c.slashCommands?.map { it.name } == SHOT_ACP_COMMANDS.map { it.first }) { "控制器没收下运行器的命令表：${c.slashCommands}" }
        for ((name, description) in SHOT_ACP_COMMANDS) check(nodes.any { it.trimEnd().endsWith(" /$name") } && nodes.any { it.trimEnd().endsWith(" $description") }) { "斜杠菜单里没有「/$name」" }
        check(nodes.none { it.trimEnd().endsWith(" /compact") }) { "ACP 的菜单混进了 Claude Code 的内置命令" }
        checkSlashMenu(nodes, "/", "/" + SHOT_ACP_COMMANDS.last().first, rows = SHOT_ACP_COMMANDS.size)
    }) { acpSlash(it) },
    // 回车补进第一条（「/web 」），没有 session/prompt
    CodeScene("local-acp-slash-enter", listOf(ENTER), verify = { state, nodes ->
        check(state.chatDrafts[checkNotNull(state.localSelectedTaskKey)]?.value?.text == "/web ") { "回车没把 /web 补进草稿" }
        check(shotAcpRequests.toList() == listOf("initialize", "session/new")) { "回车把草稿发出去了：$shotAcpRequests" }
        check(nodes.none { it.trimEnd().endsWith(" /plan") }) { "补完后菜单还开着" }
    }) { acpSlash(it) },
    // ACP 审批卡（M3d-2，规格表 §3.3）：控制器里塞一个等审批的请求（三个选项），停靠在输入卡上方；「仅本次拒绝」在左，「仅本次允许」是主按钮
    CodeScene("local-acp-approval", verify = { _, nodes ->
        for (text in listOf("运行 ./gradlew test", "./gradlew :app:test", "拒绝", "总是允许", "允许一次", "取消此次审批"))
            check(nodes.any { it.endsWith(" $text") }) { "审批卡上没有「$text」" }
    }) {
        localAcp(it)
        val c = it.localAcpTasks.controllers.values.single()
        c.pendingApprovals["shot-approval"] = JSONObject().put("id", "shot-approval").put("method", "session/request_permission").put("params", JSONObject()
            .put("toolCall", JSONObject().put("title", "运行 ./gradlew test").put("content", JSONArray().put(JSONObject().put("type", "content")
                .put("content", JSONObject().put("type", "text").put("text", "./gradlew :app:test")))))
            .put("options", JSONArray()
                .put(JSONObject().put("optionId", "allow-once").put("name", "允许一次").put("kind", "allow_once"))
                .put(JSONObject().put("optionId", "allow-always").put("name", "总是允许").put("kind", "allow_always"))
                .put(JSONObject().put("optionId", "reject-once").put("name", "拒绝").put("kind", "reject_once"))))
    },
    // 本机 ACP 接回（本机对齐 P3）：放在本机侧栏整理那几张之前，免得它们落盘的置顶、新建记录串进来。进页面自动接回一次。运行器声明了 loadSession：先回放整段对话再回包；回放里的模型是旧值，回包里是当前值
    CodeScene("local-acp-restore", verify = { state, nodes ->
        check(shotAcpRequests.toList() == listOf("initialize", "session/load")) { "接回的请求不对：$shotAcpRequests" }
        val c = checkNotNull(state.localAcpTasks.controllers.values.singleOrNull()) { "接回后没有登记控制器" }
        check(c.ready && c.note == "已接回会话") { "接回后没就绪：${c.note}" }
        check(c.messages.map { it.author } == listOf("User", "Assistant", "读取 build.gradle.kts", "Assistant", "User", "Assistant")) { "回放的段落不对：${c.messages.map { it.author }}" }
        check(acpConfigSelectors(c.configOptions).single { it.id == "model" }.current == "gemini-2.5-pro") { "模型没按回包里的当前值" }
        check(nodes.any { it.endsWith(" 已接回会话") }) { "停靠区没有「已接回会话」" }
        check(nodes.none { it.endsWith(" 重新连接") }) { "接回后还留着「重新连接」" }
    }) { localAcpRestore(it, "load") },
    // 只声明了 resume：接上但不回放，停靠区说明一句
    CodeScene("local-acp-resume", verify = { state, nodes ->
        check(shotAcpRequests.toList() == listOf("initialize", "session/resume")) { "接回的请求不对：$shotAcpRequests" }
        check(state.localAcpTasks.controllers.values.singleOrNull()?.ready == true) { "接回后没就绪" }
        check(nodes.any { it.endsWith(" 已接回会话；此运行器不回放历史，之前的内容请在原运行器查看") }) { "停靠区没有不回放的说明" }
    }) { localAcpRestore(it, "resume") },
    // 两样都没声明：留原因和两个按钮；点一次「重新连接」，再试一次也一样
    CodeScene("local-acp-unsupported", listOf("重新连接"), verify = { state, nodes ->
        check(shotConnects.get() == 2) { "接回试了 ${shotConnects.get()} 次，应该是自动一次加手动一次" }
        check(shotAcpRequests.toList() == listOf("initialize", "initialize")) { "不支持接回的运行器不该收到接回请求：$shotAcpRequests" }
        check(state.localAcpTasks.controllers.isEmpty() && !state.localAcpTasks.busy) { "接回失败后不该留控制器或忙状态" }
        check(nodes.any { it.endsWith(" 此运行器不支持继续已有会话，可以新建对话") }) { "停靠区没有失败原因" }
        check(nodes.any { it.endsWith(" 重新连接") } && nodes.any { it.endsWith(" 重新检测运行器") }) { "失败后少了按钮" }
    }) { localAcpRestore(it, "none") },
    // 本机 ACP 的排队：自动排队关着，队里两条；点队首的「发送」发出一条，另一条还等着
    CodeScene("local-acp-queue", listOf("发送"), verify = { state, nodes ->
        val c = checkNotNull(state.localAcpTasks.controllers.values.singleOrNull()) { "没有控制器" }
        check(shotAcpRequests.count { it == "session/prompt" } == 1 && !c.busy && !c.autoDispatch) { "手动发送不对：$shotAcpRequests" }
        check(state.instructions.entries.count { it.taskKey == c.taskKey && it.status == InstructionStatus.Local } == 1) { "应该还剩一条等着" }
        check(nodes.any { it.endsWith(" " + SHOT_ACP_QUEUED[1]) } && nodes.any { it.endsWith(" 发送") }) { "排队条上没有剩下那条或「发送」" }
    }) { localAcpQueue(it, automatic = false) },
    // 自动排队开着：页面套用偏好后两条依次发出，每条都等上一轮正常结束
    CodeScene("local-acp-auto", verify = { state, nodes ->
        val c = checkNotNull(state.localAcpTasks.controllers.values.singleOrNull()) { "没有控制器" }
        check(shotAcpRequests.count { it == "session/prompt" } == 2 && !c.busy && c.autoDispatch) { "自动排队没把两条发完：$shotAcpRequests" }
        check(state.instructions.entries.none { it.taskKey == c.taskKey && it.status == InstructionStatus.Local }) { "队里还有没发的" }
        check(c.messages.count { it.author == "User" } == 3) { "对话里应该有三条你的消息" }
        check(nodes.none { it.endsWith(" 发送") }) { "发完了不该还有「发送」" }
    }) { localAcpQueue(it, automatic = true) },
    // 服务器 ACP 对话：打开 ssh-list 里那条 Gemini 托管任务（没有控制器）：会话头换主机图标，停靠区只有说明
    CodeScene("ssh-acp") { sshAcp(it) },
    // 本机 OpenCode 对话（M2-b b4）：打开 localHome 里那条 OpenCode 记录；控制器接回环上的假 OpenCode（只答 refresh 要的几条 GET），
    // 记录只改内存里的索引。第二张多一轮等审批的（审批卡、停止方块、侧栏的状态点）
    CodeScene("local-opencode") { localOpenCode(it, permission = false) },
    CodeScene("local-opencode-permission") { localOpenCode(it, permission = true) },
    // 对话视图（M3g，规格表 §3.4）：助手回复里带一段思考；普通档（默认）不显示，切到「思考」档才显示（弱色斜体）
    CodeScene("local-opencode-thinking", verify = { _, nodes ->
        check(nodes.any { it.endsWith(" 先看 nav.yml 里有多少个页面，再按读者路径分组。") }) { "思考档没显示思考" }
    }) { localOpenCode(it, permission = false, thinking = true); it.transcriptViews[it.localSelectedTaskKey!!] = TranscriptView.Thinking },
    // 读文件条目（M3h，规格表 §3.4）：详细档里自动展开，路径行 + 带行号的内容框（OpenCode 的 00001| 格式）
    CodeScene("local-opencode-read", verify = { _, nodes ->
        check(nodes.any { it.endsWith("/nav.yml") } && nodes.any { it.endsWith(" - title: 快速开始") } && nodes.any { it.trimEnd().endsWith(" 4") }) { "读文件条目没画出路径、行号或内容" }
    }) { localOpenCode(it, permission = false, read = true); it.transcriptViews[it.localSelectedTaskKey!!] = TranscriptView.Detailed },
    // 设置 → 外观里的对话默认视图（M3h）；没单独切过的会话跟着偏好走（下一张：同 local-opencode-read，但没设会话档）
    CodeScene("settings-transcript-view", verify = { state, nodes ->
        check(nodes.any { it.contains("对话默认视图（Code 风格）") } && state.transcriptView("any") == TranscriptView.Detailed) { "设置页没有对话默认视图，或偏好没生效" }
    }) { check(Store.setPref(TranscriptView.PREF, TranscriptView.Detailed.key) == null); it.showSettings = true; it.settingsSection = "外观" },
    CodeScene("local-opencode-read-pref", verify = { _, nodes ->
        check(nodes.any { it.endsWith(" - title: 快速开始") }) { "会话没跟着偏好的详细档展开" }
    }) { localOpenCode(it, permission = false, read = true); check(Store.setPref(TranscriptView.PREF, TranscriptView.Detailed.key) == null) },
    // 思考行（M3h-4，规格表 §3.4）：在跑、对话最后一项是思考 → 状态行换成「0s · 思考中…」；普通档里思考正文不显示
    CodeScene("local-opencode-thinking-now", verify = { _, nodes ->
        check(nodes.any { it.contains("0s · 思考中…") } && nodes.none { it.contains("redirects.yml") } && nodes.none { it.contains("正在运行 · ") }) { "思考行没换上，或普通档里露出了思考正文" }
    }) { localOpenCode(it, permission = false, running = true, thinkingNow = true) },
    // 运行中的状态行（M3f，规格表 §3.4）：在跑、没有审批时对话末尾一行「正在运行 · Ns」；夹具的时钟固定，所以是 0s
    CodeScene("local-opencode-running", verify = { _, nodes ->
        check(nodes.any { it.endsWith(" 正在运行 · 0s") }) { "运行中没有状态行" }
    }) { localOpenCode(it, permission = false, running = true) },
    // 本机 OpenCode 接回（本机对齐 P4）：同一条记录没有控制器，页面自动接回一次（serve 钩子接回环上的假 OpenCode，不起进程）：
    // 读回会话和历史、登记控制器，停靠区回到「就绪」和输入区；只起一次服务
    CodeScene("local-opencode-restore", verify = { state, nodes ->
        check(shotConnects.get() == 1) { "接回起了 ${shotConnects.get()} 次服务，应该只有进页面那一次" }
        val c = checkNotNull(state.localOpenCodeTasks.controllers.values.singleOrNull()) { "接回后没登记控制器" }
        check(c.ready && c.note == "就绪" && !state.localOpenCodeTasks.busy) { "接回后不是就绪：${c.note}" }
        check(nodes.any { it.endsWith(" 侧栏导航太乱了，按「入门 / 指南 / 参考」三组重排一下") }) { "没读回历史" }
        check(nodes.any { it.endsWith(" 核对状态") } && nodes.none { it.endsWith(" 重新连接") }) { "接回后停靠区不对" }
    }) { localOpenCodeRestore(it, known = true) },
    // 本机 OpenCode 接不回：原生端已经删了这个会话（GET 404）。进页面自动试一次，失败留原因和「重新连接」；再手动点一次，一共两次
    CodeScene("local-opencode-gone", listOf("重新连接"), verify = { state, nodes ->
        check(shotConnects.get() == 2) { "接回起了 ${shotConnects.get()} 次服务，应该是自动一次加手动一次" }
        check(state.localOpenCodeTasks.controllers.isEmpty() && !state.localOpenCodeTasks.busy) { "接回失败后不该留控制器或忙状态" }
        check(nodes.any { it.contains("OpenCode 里读不到这个会话") }) { "停靠区没有失败原因" }
        check(nodes.any { it.endsWith(" 重新连接") } && nodes.any { it.endsWith(" 接回会话后可发送") }) { "失败后没有回到「重新连接」或输入提示不对" }
    }) { localOpenCodeRestore(it, known = false) },
    // 服务器 OpenCode 对话：打开 ssh-list 里那条 OpenCode 托管任务（没有控制器）：会话头换主机图标，停靠区只有说明
    CodeScene("ssh-opencode") { sshOpenCode(it) },
    // 本机 Codex 对话（本机对齐 P1b）：打开 localHome 里那条 Codex 记录；控制器接假 app-server（只答 thread/read、thread/goal/get 和 model/list），
    // 记录和控制器只进内存。第一张一轮做完（命令、改文件、Markdown 回复）加一条目标；第二张多一轮等命令审批（审批卡、停止方块、侧栏的状态点）
    CodeScene("local-codex") { localCodex(it, approval = false) },
    CodeScene("local-codex-approval") { localCodex(it, approval = true) },
    // 本机 Codex 接不回：同一数据目录的运行器版本检测失败（侧栏照样列出并选中），面板不去连接；停靠区是原因和三个 chip
    CodeScene("local-codex-unavailable") { localCodexUnavailable(it) },
    // 本机 Codex 接回失败（P1f）：运行器在，一连接就失败（connect 钩子，不起进程）。进页面自动接回只试一次，失败留原因和「重新连接」；
    // 再点「重新连接」手动试一次，一共两次：多了说明自动接回在反复重试，少了说明自动接回没发起或按钮没接上
    CodeScene("local-codex-failed", listOf("重新连接"), verify = { state, nodes ->
        check(shotConnects.get() == 2) { "接回试了 ${shotConnects.get()} 次，应该是自动一次加手动一次" }
        check(state.localCodexTasks.controllers.isEmpty() && !state.localCodexTasks.busy) { "接回失败后不该留控制器或忙状态" }
        check(nodes.any { it.endsWith(" 运行器未确认官方提供方，未启用发送") }) { "停靠区没有失败原因" }
        check(nodes.any { it.endsWith(" 重新连接") }) { "失败后没有回到「重新连接」" }
    }) { localCodexFailed(it) },
    // 本机 Codex 的输入区（P1c）：点开模型 chip（列表来自 model/list，强度条跟着当前模型）；草稿一句话 + 两张图（托盘缩略图读快照）
    // 消息操作行（M3d-3，规格表 §3.4）：鼠标停在你的消息上，下方 8 处出现「复制」（靠右）；不停时位置照留
    CodeScene("local-codex-message-hover", listOf("~给解析器加两组基准测试：大文件和深层嵌套。"), verify = { _, nodes ->
        // 语义清单里没有图标的描述，按位置认：「复制」是 24×24 的按钮，右缘贴对话列右边（x 1552 − 24 = 1528）
        check(nodes.any { "x=1528.0" in it && "w=24.0 h=24.0 Button" in it }) { "鼠标停在消息上没有出现「复制」" }
    }) { localCodex(it, approval = false) },
    // 详细档（M3g）：工具组卡片和每个条目一开始就展开（命令框可见），不用点
    CodeScene("local-codex-detailed", verify = { _, nodes ->
        check(nodes.any { it.trimEnd().endsWith(" $") } && nodes.any { it.trimEnd().endsWith(" 修改") }) { "详细档没把工具组和条目展开" }
    }) { localCodex(it, approval = false); it.transcriptViews[it.localSelectedTaskKey!!] = TranscriptView.Detailed },
    // 工具摘要行和工具组卡片（M3c，规格表 §3.4）：一轮里的命令和文件改动合成一行，点开是撑满列宽的卡片；第二张再点开命令条目（命令框 + 输出）
    CodeScene("local-codex-tools", listOf(SHOT_CODEX_TOOLS), verify = { _, nodes ->
        check(nodes.any { it.trimEnd().endsWith(" 修改") } && nodes.any { it.endsWith(" parser.rs、Cargo.toml") }) { "卡片里没有改文件的条目" }
    }) { localCodex(it, approval = false) },
    CodeScene("local-codex-tools-command", listOf(SHOT_CODEX_TOOLS, SHOT_CODEX_COMMAND), verify = { _, nodes ->
        check(nodes.any { it.trimEnd().endsWith(" $") } && nodes.any { it.endsWith(" $SHOT_CODEX_COMMAND") }) { "命令条目点开后没有命令框" }
    }) { localCodex(it, approval = false) },
    CodeScene("local-codex-model", listOf("gpt-5-codex")) { localCodex(it, approval = false) },
    CodeScene("local-codex-images") { localCodexImages(it) },
    // 本机的 Git 改动（本机对齐 P6a）：会话头右端点「改动」，开在右侧卡片里（M4b，对话留在左边）；LocalGit.run 换成固定的快照，不起 git。
    // 第一张是文件列表，第二张再点一个文件看差异
    CodeScene("local-codex-changes", listOf("@改动"), verify = { _, nodes ->
        // M4c：Code 外观——「分支 → 工作区」、仓库路径和 HEAD、每个文件一行（状态码 + 路径），还没点开时没有 diff
        for (text in listOf("fix/login-errors → 工作区", "MM", "src/auth/errors.ts", "??", "src/auth/errors.test.ts"))
            check(nodes.any { it.trimEnd().endsWith(" $text") }) { "改动视图里没有「$text」" }
        check(nodes.any { it.trimEnd().endsWith("demo-app · 8b7d4e0c2f61") }) { "没有仓库路径和 HEAD" }
        checkPanels(nodes, "改动" to 1000f, headerButtons = 2)
    }) { localCodex(it, approval = false); shotLocalGit() },
    // 本机文件（本机对齐 P6b）：会话头点「文件」，右侧卡片里是本机目录（M4b）（夹具 projects/demo-app，摆几个文件、时间固定）；第二张再点 README.md 看预览
    CodeScene("local-codex-files", listOf("@文件"), verify = { _, nodes ->
        for (text in listOf("src", "README.md", "package.json")) check(nodes.any { it.endsWith(" $text") }) { "文件列表里没有「$text」" }
        checkPanels(nodes, "文件" to 1000f, headerButtons = 2)
    }) { localCodex(it, approval = false); shotLocalFiles() },
    // M4d：Code 外观是规格表 §3.12 的左树右预览——点 README.md，右边是带行号的内容（8 行），头部是文件名；点 src 就地展开出 index.ts
    CodeScene("local-codex-files-preview", listOf("@文件", "README.md"), verify = { _, nodes ->
        check(nodes.count { it.trimEnd().endsWith(" README.md") } >= 2) { "预览头部没有文件名（树里一个 + 头部一个）" }
        check(nodes.any { it.trimEnd().endsWith("- 跑 npm test 看单测") } && nodes.any { it.trimEnd().endsWith(" 8") }) { "预览里没有带行号的 README 内容" }
        check(nodes.none { "从左边选一个文件预览" in it }) { "还停在没选文件的空态" }
    }) { localCodex(it, approval = false); shotLocalFiles() },
    CodeScene("local-codex-files-tree", listOf("@文件", "src"), verify = { _, nodes ->
        check(nodes.any { it.trimEnd().endsWith(" index.ts") }) { "src 没有展开出 index.ts" }
    }) { localCodex(it, approval = false); shotLocalFiles() },
    // M4c：点开文件行，下面是带行号的 diff（规格表 §3.12 / §1.5：色条、行号区、增删底色）；行右端 +3 −1 和两个图标按钮
    CodeScene("local-codex-changes-diff", listOf("@改动", "src/auth/errors.ts"), verify = { _, nodes ->
        check(nodes.any { it.trimEnd().endsWith(" +3") } && nodes.any { it.trimEnd().endsWith(" −1") }) { "差异统计不对" }
        for (text in listOf("12", "13", "14", "    case 401: return '账号或密码不对'", "    default: return '登录失败，请稍后重试'"))
            check(nodes.any { it.trimEnd().endsWith(" ${text.trimEnd()}") }) { "diff 里没有「$text」" }
        check(nodes.none { it.contains("diff --git") || it.contains("+++ b/") }) { "文件头不该画出来" }
    }) { localCodex(it, approval = false); shotLocalGit() },
    // M4p：分支改动条（规格表 §3.3）——会话目录有改动时停在输入卡上方：文件夹图标 + 分支名，右边「+N −N」徽章和关闭；
    // 夹具把 BranchChanges.local 换成固定的数（不起 git）。这张同时开着审批卡：卡 → 条 → 输入卡
    CodeScene("local-codex-branch-bar", verify = { _, nodes ->
        for (text in listOf("fix/login-errors", "+12", "−3")) check(nodes.any { it.trimEnd().endsWith(" $text") }) { "分支改动条里没有「$text」" }
    }) { localCodex(it, approval = true); BranchChanges.local = { SHOT_BRANCH } },
    // 点徽章开右侧「改动」（同会话头的「改动」图标）
    CodeScene("local-codex-branch-bar-open", listOf("+12"), verify = { _, nodes -> checkPanels(nodes, "改动" to 1000f, headerButtons = 2) }) {
        localCodex(it, approval = false); shotLocalGit(); BranchChanges.local = { SHOT_BRANCH }
    },
    // M4m：本机会话头的「打开方式」——找到的编辑器（夹具里替换成固定的两个，不看这台机器装了什么）+「在访达 / 资源管理器中显示」
    CodeScene("local-codex-open-in", listOf("@打开方式"), verify = { _, nodes ->
        for (text in listOf("Visual Studio Code", "Cursor", OpenIn.revealLabel)) check(nodes.any { it.trimEnd().endsWith(" $text") }) { "打开方式里没有「$text」" }
    }) { localCodex(it, approval = false); OpenIn.detect = { listOf(EditorApp("Visual Studio Code", "/usr/local/bin/code"), EditorApp("Cursor", "/usr/local/bin/cursor")) } },
    // M4j：diff 逐行评论——点行号区（这里点新文件的第 13 行）在那一行下面开评论框（取消 / 保存，Ctrl+Enter 一起加入对话）
    CodeScene("local-codex-diff-comment", listOf("@改动", "src/auth/errors.ts", "13"), verify = { _, nodes ->
        check(nodes.any { "对这一行的意见" in it }) { "没开出评论框" }
        check(nodes.any { it.trimEnd().endsWith(" 保存") } && nodes.any { it.trimEnd().endsWith(" 取消") }) { "评论框没有「取消」「保存」" }
    }) { localCodex(it, approval = false); shotLocalGit() },
    // 本机首页选 Codex（P1d）：创建中（输入卡只读、页脚「取消创建」、说明换成 Codex 的）；上次创建待核对（说明，按 Enter 进经典对话框核对）
    CodeScene("local-home-codex-creating") { localHomeCodex(it, review = false) },
    CodeScene("local-home-codex-review") { localHomeCodex(it, review = true) },
    // 本机行菜单（P1e）：悬停 Claude 那条再点 ⋮；条目同服务器的托管任务，没有「移到项目分组」
    CodeScene("local-row-menu", listOf("~修复登录页的报错提示", "会话操作")) { localHome(it) },
    // 本机改名弹窗（P1f）：同上再点「修改显示名称」；只开弹窗不保存，不写整理记录。核弹窗画出来了、框里是原标题
    CodeScene("local-rename", listOf("~修复登录页的报错提示", "会话操作", "修改显示名称"), verify = { state, nodes ->
        for (text in listOf("修改显示名称", "只改 Yxi 里的显示名称，不改运行器里的会话；留空恢复原标题。", "保存", "取消"))
            check(nodes.any { it.endsWith(" $text") }) { "改名弹窗里没有「$text」" }
        // 侧栏那一行一个，输入框里一个
        check(nodes.count { it.endsWith(" 修复登录页的报错提示") } >= 2) { "改名框里不是原标题" }
        val claude = state.localClaudeTasks.registry.records.single { it.threadId == "shot-local-1" }.key
        check(state.navigation.title(claude) == null) { "只开弹窗不该改名" }
    }) { localHome(it) },
    // 本机「归档」筛选（P1e）：归档 OpenCode 那条再切到归档，原生历史不列；状态是全局的，shootCode 每张先复位成「全部」
    CodeScene("local-archived") { localArchived(it) },
    // 本机置顶区（P1e）：置顶 Codex 那条（组里排到最前）、改名 Claude 那条，顶上出现置顶区。整理记录落在夹具 profile 里、会带到后面，
    // 所以放在后面（后面的本机场景只有 local-row-archive 和 local-home-create，都从这张的状态接着做；再后面的服务器场景不读本机整理记录）
    CodeScene("local-saved") { localSaved(it) },
    // 本机行菜单点到底（P1f）：在 local-saved 的状态上，悬停改名后的 Claude 那条、点 ⋮ 再点「归档」：整理记录里归档了，
    // 「全部」下这行不见了（菜单 → navigation → 列表这条链路）。归档写进夹具 profile，所以放在后面；摆场景时先恢复，深色那张照样从头点
    CodeScene("local-row-archive", listOf("~登录页报错提示（已改名）", "会话操作", "归档"), verify = { state, nodes ->
        val claude = state.localClaudeTasks.registry.records.single { it.threadId == "shot-local-1" }.key
        check(state.navigation.archived(claude)) { "点了「归档」，整理记录里没归档" }
        check(state.navigation.error.isBlank()) { state.navigation.error }
        check(nodes.none { it.endsWith(" 登录页报错提示（已改名）") }) { "归档后「全部」里还列着这一行" }
        check(nodes.any { it.endsWith(" 给解析器加基准测试") }) { "归档一条不该带走别的行" }
    }) { localRowArchive(it) },
    // 本机首页直接建（P1f，补 P1d 的缺口）：选 Codex、摆一句提示词，在输入卡里按回车：连上运行器（假 app-server）、读模型、thread/start，
    // 切到新对话，提示词原样留在它的输入框里、不发出。新会话写进夹具 profile 的会话记录，所以放在本机场景的最后（后面只有服务器场景，
    // 服务器侧栏不列本机会话）；摆场景时先清掉上一张建的
    CodeScene("local-home-create", listOf(ENTER), verify = { state, nodes ->
        val tasks = state.localCodexTasks
        val record = tasks.registry.records.singleOrNull { it.threadId == SHOT_CREATED_THREAD } ?: error("按了回车，会话记录里没有新会话")
        check(record.title == SHOT_HOME_PROMPT && record.model == "gpt-5-codex") { "新会话的标题或模型不对：${record.title} / ${record.model}" }
        check(state.localSelectedTaskKey == record.key) { "建好之后没有切到新对话" }
        check(tasks.controllers[record.key] != null && !tasks.busy && tasks.recoveryThreadId.isBlank()) { "建好之后没接上，或留了待核对" }
        // 直接建按 autoRun 走会话的排队偏好（夹具 profile 里没设，默认开）：同接回，打开自动逐轮
        check(tasks.controllers.getValue(record.key).autoDispatch) { "直接建之后没按排队偏好打开自动逐轮" }
        check(state.chatDrafts[record.key]?.value?.text == SHOT_HOME_PROMPT) { "提示词没有原样放进新对话的输入框" }
        check(state.localHomeDraft.text.text.isEmpty()) { "首页草稿没清" }
        // 只建一次（按下、抬起各一次也不重复建），提示词不发出
        check(shotRequests.count { it == "thread/start" } == 1) { "thread/start 发了 ${shotRequests.count { it == "thread/start" }} 次" }
        check(shotRequests.none { it.startsWith("turn/") }) { "提示词被发出去了：$shotRequests" }
        // 侧栏新行、会话头、输入框各一个
        check(nodes.count { it.endsWith(" $SHOT_HOME_PROMPT") } >= 3) { "新对话页上提示词不够三处（侧栏 / 会话头 / 输入框）" }
    }) { localHomeCreate(it) },
    // 本机 Claude 自动恢复（本机对齐 P5）：恢复成功会把会话记录写进夹具 profile，所以放在本机场景最后（后面只有服务器场景）；同一条记录换成夹具目录里的配置目录（有一份原生历史），一个就绪的运行器指着它；
    // 读完历史后页面自动恢复一次（resumeConnection 钩子接假的控制传输，不起进程）：历史在、模型 chip 在、停靠区不再有「恢复此会话」
    CodeScene("local-claude-restore", verify = { state, nodes ->
        check(shotConnects.get() == 1) { "恢复连了 ${shotConnects.get()} 次，应该只有进页面那一次" }
        val c = checkNotNull(state.localClaudeTasks.controllers.values.singleOrNull()) { "自动恢复后没有控制器：" + nodes.filter { "x=800.0" in it || "x=793.6" in it }.map { it.substringAfter("  ") } }
        check(c.ready && !state.localClaudeTasks.busy) { "恢复后不是就绪" }
        check(nodes.any { it.endsWith(" 登录页的报错提示太笼统了，按错误码分开写") }) { "没读到原生历史" }
        check(nodes.none { it.endsWith(" 恢复此会话") }) { "恢复后停靠区还有「恢复此会话」" }
    }) { localClaudeRestore(it) },
    // 服务器 Codex 对话（本机对齐 P2）：Code 下服务器上用 Codex 运行器的会话页和本机同一套（会话头、居中对话列、停靠区）。打开 ssh-list 里那条
    // 「登录页改版」，控制器接假 app-server、只进内存。第一张一轮做完加一条目标，第二张多一轮等命令审批，第三张点开会话头的 ⋮，第四张点开模型 chip
    CodeScene("server-codex", verify = { _, nodes ->
        check(nodes.none { it.endsWith(" 连接任务") }) { "控制器已在，不该再显示「连接任务」" }
        check(nodes.count { it.endsWith(" 登录页改版") } >= 3) { "会话头没有任务标题（侧栏两行 + 会话头）" }
    }) { serverCodex(it, approval = false) },
    CodeScene("server-codex-approval") { serverCodex(it, approval = true) },
    // 服务器 Codex 页的「改动」（M4b）：同本机，开在右侧卡片里，对话留在左边（夹具没连服务器，卡片里是读不到改动的说明）
    CodeScene("server-codex-changes", listOf("@改动"), verify = { _, nodes -> checkPanels(nodes, "改动" to 1000f, headerButtons = 3) }) { serverCodex(it, approval = false) },
    // 侧栏也有一行「更多」（文字），会话头的 ⋮ 是纯图标：只按 contentDescription 找（@）
    CodeScene("server-codex-more", listOf("@更多"), verify = { _, nodes ->
        for (text in listOf("在此对话应用当前服务器线路", "模型与线路", "复制任务编号"))
            check(nodes.any { it.endsWith(" $text") }) { "会话头的「更多」菜单里没有「$text」" }
    }) { serverCodex(it, approval = false) },
    // 服务器 Codex 的模型菜单（P2）：chip 和浮层同本机，末尾多「当前线路」和「管理第三方线路」。这条任务没记过模型（用服务器默认），
    // 列表里不打勾、没有强度 chip（交接 §7-28）
    CodeScene("server-codex-model", listOf("gpt-5-codex"), verify = { _, nodes ->
        for (text in listOf("gpt-5", "刷新模型列表", "当前线路 · openai", "管理第三方线路"))
            check(nodes.any { it.endsWith(" $text") }) { "服务器 Codex 的模型菜单里没有「$text」" }
    }) { serverCodex(it, approval = false) },
    // 服务器 Codex 没接上（P2，§7-27）：ssh 断了、正在重连。进页面不自动连接；停靠区说明「连接后恢复对话」，「连接任务」置灰、
    // 旁注「服务器未连接」；侧栏主机标题跟着是「正在重新连接…」
    CodeScene("server-codex-offline", verify = { state, nodes ->
        check(state.codexWorkspace.controllers.isEmpty()) { "进页面就自动连接了" }
        for (text in listOf("连接后恢复对话", "连接任务", "服务器未连接"))
            check(nodes.any { it.endsWith(" $text") }) { "没接上时缺「$text」" }
        check(nodes.any { it.contains("正在重新连接") }) { "侧栏主机标题没跟着显示「正在重新连接…」" }
    }) { serverCodexOffline(it) },
    // 服务器连着、任务还没接上（重开应用后点回这条最常见）：「连接任务」可点，不该说「服务器未连接」
    CodeScene("server-codex-detached", verify = { state, nodes ->
        check(state.codexWorkspace.controllers.isEmpty()) { "进页面就自动连接了" }
        check(nodes.any { it.endsWith(" 连接任务") } && nodes.any { it.endsWith(" 连接后恢复对话") }) { "没接上时缺「连接任务」或说明" }
        check(nodes.none { it.endsWith(" 服务器未连接") }) { "服务器连着，却说「服务器未连接」" }
    }) { serverCodexDetached(it) },
    // 再点「连接任务」：经 workspace.open 用 thread/resume 接回（只一次），读回历史，「连接任务」那行收起；不发任何轮次
    CodeScene("server-codex-connect", listOf("连接任务"), verify = { state, nodes ->
        val workspace = state.codexWorkspace
        val controller = state.codexSelectedTaskKey?.let { workspace.controllers[it] }
        check(controller != null && controller.ready && !workspace.busy) {
            "点了「连接任务」没接上：" + nodes.filter { "连接" in it || "失败" in it || "无效" in it }.takeLast(4)
        }
        check(shotRequests.count { it == "thread/resume" } == 1) { "thread/resume 发了 ${shotRequests.count { it == "thread/resume" }} 次" }
        check(shotRequests.none { it.startsWith("turn/") }) { "接回时发了轮次：$shotRequests" }
        check(nodes.none { it.endsWith(" 连接任务") }) { "接上之后还显示「连接任务」" }
        check(nodes.any { it.contains("给解析器加两组基准测试") }) { "接上之后没读回历史" }
    }) { serverCodexDetached(it) },
    // 服务器首页选对话工作台（P2）：说明行换成工作台的，页脚左边换成「恢复已有任务」；不按回车
    CodeScene("server-home-codex", verify = { _, nodes ->
        for (text in listOf("恢复已有任务", "Codex · 对话工作台"))
            check(nodes.any { it.endsWith(" $text") }) { "首页选对话工作台时缺「$text」" }
        check(nodes.any { it.contains("对话工作台支持排队、引导和侧栏预览") }) { "首页没有对话工作台的说明行" }
    }) { serverHomeCreate(it) },
    // 服务器首页直接建（P2，§7-21）：按回车，用服务器默认配置建好（不带独立配置）、切到新对话，提示词留在输入框、不发出。
    // 建的记录写进夹具 profile 的任务登记，所以放在最后（深色那张由 sshList 的 forceState 盖回，照样从头建）
    CodeScene("server-home-create", listOf(ENTER), verify = { state, nodes ->
        val d = state.newSessionDraft
        check(d.error.isBlank()) { "建对话报错：${d.error}" }
        val workspace = state.codexWorkspace
        val record = workspace.registry.records.singleOrNull { it.threadId == SHOT_SERVER_CREATED_THREAD } ?: error("按了回车，服务器的任务登记里没有新对话")
        check(record.title == SHOT_HOME_PROMPT && record.directory == "/srv/web" && record.profileId.isEmpty()) { "新对话的标题、目录或配置不对：$record" }
        check(state.page == Page.Codex && state.codexSelectedTaskKey == record.key) { "建好之后没有切到新对话" }
        val controller = workspace.controllers[record.key] ?: error("建好之后没接上")
        check(controller.ready && !workspace.busy && workspace.recoveryThreadId.isBlank()) { "建好之后没就绪，或留了恢复编号" }
        // 夹具 profile 里没设这条的排队偏好（默认开）：同本机直接建，打开自动逐轮
        check(controller.autoDispatch) { "直接建之后没按排队偏好打开自动逐轮" }
        check(state.chatDrafts[record.key]?.value?.text == SHOT_HOME_PROMPT) { "提示词没有原样放进新对话的输入框" }
        check(d.text.text.isEmpty() && !d.busy) { "首页草稿没清，或还在忙" }
        // 只建一次（按下、抬起各一次也不重复建），提示词不发出
        check(shotRequests.count { it == "thread/start" } == 1) { "thread/start 发了 ${shotRequests.count { it == "thread/start" }} 次" }
        check(shotRequests.none { it.startsWith("turn/") }) { "提示词被发出去了：$shotRequests" }
        // 侧栏「未分组」里的新行、会话头、输入框各一个
        check(nodes.count { it.endsWith(" $SHOT_HOME_PROMPT") } >= 3) { "新对话页上提示词不够三处（侧栏 / 会话头 / 输入框）" }
    }) { serverHomeCreate(it) },
    // 服务器 ACP 的排队：和本机共用 Code 对话页，排队条一样接上（自动排队关着，两条等着）
    CodeScene("ssh-acp-queue", verify = { _, nodes ->
        check(nodes.any { it.endsWith(" " + SHOT_ACP_QUEUED[0]) } && nodes.any { it.endsWith(" 发送") }) { "服务器 ACP 的停靠区没有排队条" }
    }) { sshAcpQueue(it) },
    // 定时任务的 Code 页（M4l）：960 宽的列居中、标题 22、黑底「新建任务」，每个计划一张卡片（立即运行 / 暂停或恢复 / 编辑 / 执行记录 / 删除）。
    // 写进夹具 profile 的 scheduled-tasks.json，所以放在最后
    CodeScene("code-scheduled-tasks", verify = { _, nodes ->
        for (text in listOf("定时任务", "新建任务", "每天整理项目进度", "立即运行", "已暂停")) check(nodes.any { it.trimEnd().endsWith(" $text") }) { "定时任务页没有「$text」" }
    }) { state ->
        val at = java.time.LocalDateTime.of(2030, 1, 6, 9, 0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        state.scheduledTasks.put(ScheduledTask("shot-daily", "每天整理项目进度", "看一下昨天合并的提交，整理成三条要点发在这个会话里。", ScheduleTarget("local", "本地 · Claude Code", "claude", "/srv/demo-app"), at, "每天", "Asia/Shanghai"))
        state.scheduledTasks.put(ScheduledTask("shot-weekly", "每周检查依赖更新", "列出有安全更新的依赖，先不要改代码。", ScheduleTarget("local", "本地 · Codex", "codex", "/srv/demo-app"), at + 86_400_000, "每周", "Asia/Shanghai", enabled = false))
        state.page = Page.ScheduledTasks
    },
    // M4o：Code 自定义页（规格表 §3.10）：960 宽的列（x 683.2）、标题「自定义」28、三个 32 高的标签页（顶 y 148、紧挨），
    // 右端搜索图标（中心 x 1543.6）和黑底「添加」70.4×30.4（x 1572、y 148.8）；内容从 y 208 起。都不连主机。
    // 插件标签、位置是没连上的服务器：框架层的空态卡（凹陷面、圆角 12、内边距 16），不进经典子面板
    CodeScene("customize-plugins-offline", verify = { _, nodes ->
        checkCustomizeHead(nodes, add = true)
        checkCustomizeCard(nodes, "示例服务器 未连接")
    }) { customize(it, "服务器") },
    // 「添加」进了市场：上面一行「‹ 已安装插件」，「添加」不再出；运行器 chip 的浮层（浮层头随市场换成「目录来源」）
    CodeScene("customize-plugins-runtime", listOf("@插件运行器"), verify = { _, nodes ->
        checkCustomizeHead(nodes, add = false)
        for (text in listOf("已安装插件", "目录来源", "Claude Code")) check(nodes.any { it.trimEnd().endsWith(" $text") }) { "插件市场 / 运行器浮层里没有「$text」" }
    }) { customize(it, "服务器"); it.customizeMarket = true },
    // MCP 标签（本机）：经典的 SharedMcpPane 放进框里，它列表头的「添加」不出；右上角的「添加」开它的「添加通用 MCP 配置」
    CodeScene("customize-mcp", verify = { _, nodes ->
        checkCustomizeHead(nodes, add = true)
        check(nodes.any { it.trimEnd().endsWith(" 通用 MCP") }) { "MCP 标签里没有经典的共享配置列表" }
        check(nodes.count { it.trimEnd().endsWith(" 添加") } == 1) { "MCP 列表头自己的「添加」还在" }
    }) { customize(it, "本地"); it.sharedMcpPage = true },
    CodeScene("customize-mcp-add", listOf("添加"), verify = { _, nodes ->
        check(nodes.any { it.trimEnd().endsWith(" 添加通用 MCP 配置") }) { "右上角的「添加」没开 MCP 配置弹窗" }
    }) { customize(it, "本地"); it.sharedMcpPage = true },
    // 技能标签：这台服务器的技能先放进和斜杠菜单共用的缓存；用户的在前、插件的按插件名，右端是来源；没连上照列、注明；没有安装入口，不放「添加」
    CodeScene("customize-skills", verify = { _, nodes ->
        checkCustomizeHead(nodes, add = false)
        checkSkillRows(nodes, "pdf" to "用户", "release-notes" to "用户", "lint-fix" to "插件 acme-kit", "brainstorming" to "插件 superpowers", "writing-plans" to "插件 superpowers")
        check(nodes.any { it.trimEnd().endsWith(" 示例服务器 · Claude Code 技能 · 5 个 · 未连接，显示的是上次读到的") }) { "技能数那一行不对" }
    }) { customizeSkills(it) },
    // 搜索词（和经典共用）：搜索框开着、只剩匹配的一行
    CodeScene("customize-skills-search", verify = { _, nodes ->
        checkSkillRows(nodes, "writing-plans" to "插件 superpowers")
        check(nodes.any { it.contains("· 匹配 1 个") }) { "没写匹配数" }
    }) { customizeSkills(it); it.pluginMarketQuery = "plan" },
    // 点开一行：只读的 SKILL.md 弹窗；没连上不读，直接说明
    CodeScene("customize-skill-open", listOf("pdf"), verify = { _, nodes ->
        check(nodes.any { it.trimEnd().endsWith(" 示例服务器 未连接，连上之后才能读 SKILL.md。") }) { "SKILL.md 弹窗没说明未连接" }
        check(nodes.any { it.trimEnd().endsWith(" /home/dev/.claude/skills/pdf/SKILL.md") }) { "弹窗里没有文件路径" }
    }) { customizeSkills(it) },
    // 读成了、没有技能：空态卡（不是「还没读到」）
    CodeScene("customize-skills-empty", verify = { _, nodes -> checkCustomizeCard(nodes, "示例服务器 上还没有技能") }) {
        customize(it, "服务器"); it.customizeSkills = true
        RemoteSlashCommands.put(HOST.id, listOf(app.yxi.agent.ConfigRemote.Tool("claude", "Claude Code",
            listOf(app.yxi.agent.ConfigRemote.Cat("commands", "斜杠命令", listOf(app.yxi.agent.ConfigRemote.Item("deploy")))))))
    },
    // 本机：还没有读本机 ~/.claude 的实现，只放说明卡
    CodeScene("customize-skills-local", verify = { _, nodes -> checkCustomizeCard(nodes, "还不能列出本机的技能") }) { customize(it, "本地"); it.customizeSkills = true },
)

/** 自定义页（M4o）：[remote] 的服务器（不连），切到插件页；[location] 是「本地」或「服务器」（同经典的 pluginLocation）。 */
private fun customize(state: AppState, location: String) {
    remote(state, tab = 0)
    state.pluginLocation = location
    state.page = Page.Plugins
}

/** 技能标签：这台服务器的技能放进缓存（顺序故意打乱，页面按 用户 → 插件名 → 技能名 排）。 */
private fun customizeSkills(state: AppState) {
    customize(state, "服务器")
    state.customizeSkills = true
    fun skill(name: String, from: String = "") = app.yxi.agent.ConfigRemote.Item(name, from = from,
        path = if (from.isEmpty()) "/home/dev/.claude/skills/$name/SKILL.md" else "/home/dev/.claude/plugins/cache/${from.substringBefore('@')}/skills/$name/SKILL.md")
    RemoteSlashCommands.put(HOST.id, listOf(app.yxi.agent.ConfigRemote.Tool("claude", "Claude Code", listOf(app.yxi.agent.ConfigRemote.Cat("skills", "技能 Skills", listOf(
        skill("writing-plans", "superpowers@superpowers-marketplace"), skill("pdf"), skill("brainstorming", "superpowers@superpowers-marketplace"),
        skill("release-notes"), skill("lint-fix", "acme-kit@acme")))))))
}

/** 自定义页头部（规格表 §3.10）：标题起于内容列左缘 683.2；三个标签 32 高、顶 148、紧挨；[add] 时「添加」在 x 1572、y 148.8、70.4×30.4，搜索图标中心 x 1543.6。 */
private fun checkCustomizeHead(nodes: List<String>, add: Boolean) {
    fun num(line: String, key: String) = Regex("""$key=(-?[0-9.]+)""").find(line)!!.groupValues[1].toFloat()
    val title = nodes.singleOrNull { it.trimEnd().endsWith(" 自定义") } ?: error("没有标题「自定义」")
    check(kotlin.math.abs(num(title, "x") - 683.2f) < 0.5f) { "标题没起于内容列左缘 683.2：$title" }
    val tabs = nodes.filter { it.trimEnd().endsWith(" Tab") }
    check(tabs.size == 3 && tabs.all { num(it, "h") == 32f && num(it, "y") == 148f }) { "标签页不是 3 个 32 高、顶 148：$tabs" }
    check(kotlin.math.abs(num(tabs[0], "x") - 683.2f) < 0.5f && (1..2).all { kotlin.math.abs(num(tabs[it], "x") - num(tabs[it - 1], "x") - num(tabs[it - 1], "w")) < 0.5f }) { "标签页没从 683.2 起紧挨着排：$tabs" }
    val button = nodes.any { it.contains("x=1572.0 y=148.8 w=70.4 h=30.4 Button") }
    check(button == add) { if (add) "「添加」不在 x 1572、y 148.8、70.4×30.4" else "这里不该有「添加」" }
    if (add) check(nodes.any { it.contains("x=1529.6 ") && it.contains(" w=28.0 h=28.0 Button") }) { "搜索图标的中心不在 x 1543.6" }
}

/** 空态卡：标题字起于内容列左缘 + 16（x 699.2），在 y 208 起的卡片里。 */
private fun checkCustomizeCard(nodes: List<String>, title: String) {
    fun num(line: String, key: String) = Regex("""$key=(-?[0-9.]+)""").find(line)!!.groupValues[1].toFloat()
    val line = nodes.singleOrNull { it.trimEnd().endsWith(" $title") } ?: error("没有空态卡「$title」")
    check(kotlin.math.abs(num(line, "x") - 699.2f) < 0.5f && num(line, "y") in 224f..240f) { "空态卡标题不在 x 699.2、卡片 y 208 + 16 起：$line" }
}

/** 技能行：名字按 [rows] 的顺序从上到下、起于 683.2 + 12，同一行右端是来源；没有别的行。 */
private fun checkSkillRows(nodes: List<String>, vararg rows: Pair<String, String>) {
    fun num(line: String, key: String) = Regex("""$key=(-?[0-9.]+)""").find(line)!!.groupValues[1].toFloat()
    val names = nodes.filter { n -> kotlin.math.abs(num(n, "x") - 695.2f) < 0.5f && num(n, "y") > 220f && n.trimEnd().substringAfterLast(' ').let { last -> rows.any { it.first == last } || last.matches(Regex("[a-z-]+")) } }
    check(names.map { it.trimEnd().substringAfterLast(' ') } == rows.map { it.first }) { "技能行的名字或顺序不对：$names" }
    for ((name, source) in rows) {
        val row = names.single { it.trimEnd().endsWith(" $name") }
        check(nodes.any { it.trimEnd().endsWith(" $source") && kotlin.math.abs(num(it, "y") - num(row, "y")) < 2f && num(it, "x") > 1400f }) { "「$name」那行右端没有「$source」" }
    }
}

/**
 * 审批卡：pending 只来自 SSH 轮询，离屏够不着（同经典夹具）；照 CodeShell + ChatPane 的停靠区摆同一个 [ApprovalCard]：
 * 左侧栏 288，主区底部是两侧 40、最宽 768 的居中列，卡片下面留输入区的 78.4。数据同经典的 approval-card。
 */
@Composable
private fun NarrowHomeShot() {
    val state = narrowHomeState ?: return
    val t = Tokens.current
    Row(Modifier.fillMaxSize().background(t.surface0)) {
        Spacer(Modifier.width(288.dp).fillMaxHeight())
        Box(Modifier.width(600.dp).fillMaxHeight()) {
            CodeHomeFrame(state, remember { FocusRequester() }, notes = {}, chips = { Text("文件夹 chip", color = t.textSecondary) },
                footer = { Text("页脚", color = t.textMuted) },
                card = { Text("窄窗口里的输入卡", Modifier.fillMaxWidth().background(t.surface1).padding(12.dp), color = t.textPrimary) })
        }
    }
}

/** [NarrowHomeShot] 用的状态：arrange 时记下（content 场景拿不到 AppState）。 */
private var narrowHomeState: AppState? = null

@Composable
private fun ApprovalDockShot() {
    Row(Modifier.fillMaxSize().background(Tokens.current.surface0)) {
        Spacer(Modifier.width(288.dp).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
            Column(Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 40.dp).widthIn(max = 768.dp).fillMaxWidth()) {
                ApprovalCard(Pending("是否允许 Claude 运行这条命令？", listOf(Pending.Option(1, "Yes"),
                    Pending.Option(2, "Yes, and don’t ask again for git:*"), Pending.Option(3, "No")), false, "shot-approval"),
                    Approval("Bash", "git push origin feature/parser-fix"), busy = false, onKey = {}, onSubmit = {})
                Spacer(Modifier.height(78.4.dp))
            }
        }
    }
}

/** 断开的 SSH 对话 + 排队的前 [count] 条（第 3 条两行；第 4 条在默认收起的列表之外）。[automatic] 是偏好，会带到后面的场景，所以每个场景都写。 */
private fun queued(state: AppState, count: Int, automatic: Boolean) {
    remote(state, tab = 0)
    val key = taskNavigationKey(HOST, SESSION)
    check(QueuePreferences.setEnabled(key, automatic) == null) { "写排队偏好失败" }
    QUEUED.take(count).forEachIndexed { i, text -> state.instructions.enqueue(key, text, id = "shot-queue-${i + 1}") }
}
private val QUEUED = listOf(
    "跑一遍单元测试，把失败的用例列出来",
    "顺手更新 README 里的安装步骤",
    "然后检查一下解析器对空输入和超长输入的处理，确认不会崩溃；如果有问题，先写一个能复现的测试用例，再修复它，最后把改动的要点整理成一段简短的说明",
    "提交前再跑一次格式检查",
)

/** 新会话页：主机算已连上（发送键、环境 chip 按已连接画），但不 start()，也不选会话；文件夹默认取会话列表里最近的 /srv/demo-app。 */
private fun codeHome(state: AppState) {
    remote(state, tab = 0)
    state.session = null
    checkNotNull(state.conn).status = Conn.Status.Connected
}

/**
 * 本机首页：夹具下两个真实的项目目录，外加一个已经不在的（原生历史里还引用着，侧栏和文件夹浮层标「不可用」）；
 * Codex 和 Claude Code 两个就绪的运行器（只写状态、不扫描，scanned=true 先于组合）；Yxi 建的三条对话和四条原生历史。
 * 都只喂数据：不选中任何对话（打开会去连运行器），「加载更多历史」只画不点。
 */
private fun localHome(state: AppState) {
    val root = File(System.getenv("YXI_THEME_SHOT_FIXTURE")).canonicalFile.resolve("projects")
    val (demo, docs) = listOf("demo-app", "docs-site").map { root.resolve(it).also(File::mkdirs).canonicalFile }
    val gone = root.resolve("legacy-tool").canonicalFile.also { check(!it.exists()) { "$it 应该不存在" } }
    state.selectLocal()
    val w = state.localWorkspace
    w.addProject(demo); w.addProject(docs)
    val codex = LocalRuntimeInstallation("codex", "PATH", listOf("C:\\fixture\\bin\\codex.exe"), "C:\\fixture\\codex-home", "codex-cli 0.0.0-fixture")
    val claude = LocalRuntimeInstallation("claude", "PATH", listOf("C:\\fixture\\bin\\claude.exe"), "C:\\fixture\\claude-home", "2.0.0 (Claude Code)")
    fun task(engine: String, id: String, dir: File, title: String, at: Long) = LocalCodexTaskRecord(id, System.getProperty("user.name"),
        System.getProperty("os.name"), File(if (engine == "codex") codex.home else claude.home).canonicalPath, dir.path, title, "default", at, engine)
    state.localClaudeTasks.registry.records += task("claude", "shot-local-1", demo, "修复登录页的报错提示", 1_700_000_300_000L)
    state.localCodexTasks.registry.records += task("codex", "shot-local-2", demo, "给解析器加基准测试", 1_700_000_200_000L)
    state.localOpenCodeTasks.registry.records += task("opencode", "shot-local-3", docs, "整理文档站的导航", 1_700_000_100_000L)
    w.forceState("installations", listOf(codex, claude)); w.forceState("selectedRuntime", codex)
    w.forceState("threads", listOf(
        NativeHistoryThread("shot-thread-1", "修复解析器的空输入崩溃", demo.path, "openai", "cli", 0L, "idle"),
        NativeHistoryThread("shot-thread-2", "补充 README 的安装说明", docs.path, "openai", "cli", 0L, "idle"),
        NativeHistoryThread("shot-thread-3", "升级依赖并跑通测试", demo.path, "openai", "vscode", 0L, "idle"),
        NativeHistoryThread("shot-thread-4", "迁移旧的构建脚本", gone.path, "openai", "cli", 0L, "idle")))
    w.forceState("next", "shot-cursor")
    w.forceState("scanned", true)
}

/** 本机「归档」筛选：[localHome] 再归档 OpenCode 那条、切到「归档」（local-saved 那张会把它恢复回来）。 */
private fun localArchived(state: AppState) {
    localHome(state)
    val nav = state.navigation
    nav.setArchived(state.localOpenCodeTasks.registry.records.single { it.threadId == "shot-local-3" }.key, true)
    nav.setMode("归档")
    check(nav.error.isBlank()) { nav.error }
}

/**
 * 本机置顶区：[localHome] 再置顶 Codex 那条、改名 Claude 那条（都幂等：浅色深色两张、重跑都一样），恢复 [localArchived] 归档的那条。
 * 置顶 / 收藏的选择会被 SSH 场景改成「收藏」，这里写回「置顶」（在组合之前，区里读得到）。
 */
private fun localSaved(state: AppState) {
    localHome(state)
    val nav = state.navigation
    fun key(registry: List<LocalCodexTaskRecord>, id: String) = registry.single { it.threadId == id }.key
    val claude = key(state.localClaudeTasks.registry.records, "shot-local-1")
    val codex = key(state.localCodexTasks.registry.records, "shot-local-2")
    val openCode = key(state.localOpenCodeTasks.registry.records, "shot-local-3")
    Store.setPref("sidebarSavedKind", "置顶")
    if (!nav.pinned(codex)) nav.togglePin(codex)
    nav.rename(claude, "登录页报错提示（已改名）")
    nav.setArchived(openCode, false)
    check(nav.error.isBlank()) { nav.error }
}

/** 本机行菜单点「归档」之前：[localSaved] 的状态，再把 Claude 那条恢复回来（浅色那张点过归档，深色那张要从头点）。 */
private fun localRowArchive(state: AppState) {
    localSaved(state)
    val nav = state.navigation
    nav.setArchived(state.localClaudeTasks.registry.records.single { it.threadId == "shot-local-1" }.key, false)
    check(nav.error.isBlank()) { nav.error }
}

/** 首页直接建用的提示词（也是新会话的标题：取第一行）和假 app-server 回的会话标识（本机、服务器各一个）。 */
private const val SHOT_HOME_PROMPT = "给解析器的空输入补一个回归测试"
private const val SHOT_CREATED_THREAD = "019a-shot-created"
private const val SHOT_SERVER_CREATED_THREAD = "019a-shot-server-created"

/** 假 app-server 收到的请求方法，按到达顺序（[localHomeCreate]、[serverHomeCreate]、[serverCodexDetached] 清零，场景核对时读）。 */
private val shotRequests = java.util.concurrent.CopyOnWriteArrayList<String>()

/**
 * 本机首页直接建：侧栏同 [localRowArchive]（Claude 那条恢复回来），首页选 Codex、摆提示词；connect 钩子给一个假 app-server，不起进程、不外连。
 * 浅色那张建的会话已经写进夹具 profile 的会话记录（AppState 起来时读进来），先清掉，再由 [localHome] 摆回那条 Codex 记录，深色那张照样从头建。
 */
private fun localHomeCreate(state: AppState) {
    state.localCodexTasks.registry.records.clear()
    localRowArchive(state)
    localHomePrompt(state)
    shotRequests.clear()
    // thread/start 回的是刚建的空会话（没有轮次、空闲）；cwd 和模型由假 app-server 按请求填
    val created = JSONObject().put("id", SHOT_CREATED_THREAD).put("status", JSONObject().put("type", "idle")).put("turns", JSONArray())
    state.localCodexTasks.connect = { CodexAppServer(ShotCodexTransport(created, null, null)) }
}

/** 9c 的另外两台主机：连上了但没有会话（空态说明）；连接失败、名字很长（置顶区右端的主机名省略到 120）。 */
private val IDLE_HOST = Host("shot-host-2", "空闲服务器", "idle-01.example.invalid", username = "dev")
private val FAILED_HOST = Host("shot-host-3", "华东二区持续集成构建机（夜间任务专用）", "ci-07.example.invalid", username = "dev")

/**
 * SSH 侧栏（第 9 步 9c）：主力机有命名分组（展开的「前端」、折叠的「后端」、空的「空组」）和「未分组」，四种状态点，一条改成长标题（渐隐），
 * Codex / OpenCode / Gemini 托管任务各一条，几条置顶和收藏、一条没启动的收藏；第二台连上了但没有会话；第三台连接失败，只有一条置顶的
 * Codex 任务。[saved] 为真时置顶区切到「收藏」（偏好会带到后面的场景，所以每次都写）。只喂数据：不 start()、不选会话（同 [codeHome]）。
 */
private fun sshList(state: AppState, saved: Boolean) {
    check(Store.setPref("sidebarSavedKind", if (saved) "收藏" else "置顶") == null) { "写 sidebarSavedKind 失败" }
    Store.save(listOf(HOST, IDLE_HOST, FAILED_HOST))
    fun session(name: String, cwd: String, st: SessionState) = Session(name, 1, false, cwd, 0L, st, "", 0.0, cmd = "claude", runtimeId = "shot-$name")
    val web = session("cc-web", "/srv/web", SessionState.Working)
    val docs = session("cc-docs", "/srv/docs", SessionState.NeedsYou)
    val api = session("cc-api", "/srv/api", SessionState.Idle)
    val ops = session("cc-ops", "/srv/ops", SessionState.Done)
    val main = Conn(HOST, NoHostKeys).apply {
        sessions = listOf(SESSION, web, docs, api, ops)
        forceState("projectGroups", Groups.Table(mapOf("前端" to listOf("cc-web", "cc-docs"), "后端" to listOf("cc-api"), "空组" to emptyList<String>())))
        forceState("groupsLoaded", true)
        status = Conn.Status.Connected
    }
    val idle = Conn(IDLE_HOST, NoHostKeys).apply { forceState("groupsLoaded", true); status = Conn.Status.Connected }
    val failed = Conn(FAILED_HOST, NoHostKeys).apply { status = Conn.Status.Failed; error = "连接超时：ci-07.example.invalid:22" }
    state.conns.addAll(listOf(main, idle, failed)); state.conn = main; state.session = null
    val nav = state.navigation
    fun pin(key: String) { if (!nav.pinned(key)) nav.togglePin(key) }   // togglePin 是翻转；两个场景共用导航记录，要幂等
    fun key(s: Session) = taskNavigationKey(HOST, s)
    pin(key(docs)); pin(key(web))
    nav.setCollapsed("server-group:" + projectKey(HOST, "/") + ":后端", true)
    nav.rename(key(ops), "把部署脚本从 Bash 迁到 Python，并补上回滚和健康检查")
    nav.setFavorite(key(web), HOST, web, true)
    val deploy = session("cc-deploy", "/srv/deploy", SessionState.Idle)   // 不在会话列表里：收藏区的「没启动」
    nav.setFavorite(taskNavigationKey(HOST, deploy), HOST, deploy, true)
    val hostKey = projectKey(HOST, "/")
    val login = CodexTaskRecord(hostKey, "shot-codex-1", "/srv/web", "登录页改版", 3L)
    val nightly = CodexTaskRecord(projectKey(FAILED_HOST, "/"), "shot-codex-2", "/srv/ci", "排查夜间构建失败", 2L)
    state.codexWorkspace.registry.forceState("records", listOf(login, nightly))
    nav.setGroup(login.key, "前端"); pin(login.key); pin(nightly.key)
    val lint = LocalCodexTaskRecord("shot-opencode-1", "dev", "linux", "/home/dev", "/srv/docs", "修文档站的 lint", "m", 2L, engine = "opencode", hostKey = hostKey)
    state.remoteOpenCodeTasks.registry.records += lint
    nav.setGroup(lint.key, "前端")
    val review = LocalCodexTaskRecord("shot-acp-1", "dev", "linux", "/home/dev", "/srv/api", "梳理接口错误码", "m", 1L, engine = "gemini", hostKey = hostKey)
    state.remoteAcpTasks.registry.records += review
    nav.setNativeFavorite(review.key, true)
}

/**
 * 本机 ACP 对话（M2-b b3）：在 [localHome] 上加一条 Gemini 对话并打开。控制器接 [ShotAcpTransport]（管道流，不起进程、不外连），
 * 握手在 EDT 上同步跑完；消息直接写进控制器（不走 session/prompt）。记录只进内存里的索引，收尾由 closeLocalFeatures 关掉控制器、客户端和传输。
 * [commands] 不为空时运行器在 session/new 之后报这张命令表（M4k 斜杠菜单）。
 */
private fun localAcp(state: AppState, commands: JSONArray? = null) {
    localHome(state)
    val demo = File(System.getenv("YXI_THEME_SHOT_FIXTURE")).canonicalFile.resolve("projects").resolve("demo-app").canonicalFile
    val record = LocalCodexTaskRecord("shot-acp-local", System.getProperty("user.name"), System.getProperty("os.name"),
        File("C:\\fixture\\gemini-home").canonicalPath, demo.path, "排查构建缓存为什么没命中", "default", 1_700_000_400_000L, "gemini")
    val client = AcpClient(ShotAcpTransport(record.threadId, commands = commands))
    runBlocking { client.initialize(); client.newSession(demo.path) }
    val controller = AcpTaskController(record.key, record.threadId, client, state.instructions)
    state.localAcpTasks.registry.records += record
    state.localAcpTasks.controllers[record.key] = controller
    controller.messages += listOf(
        AcpMessage("user-1", "User", "构建每次都全量重跑，帮我看看缓存为什么没命中"),
        AcpMessage("tool-1", "读取 build.gradle.kts", "读取了 1 个文件", "tool", "completed"),
        AcpMessage("assistant-1", "Assistant", "## 原因\n\n`generateVersion` 把**当前时间**写进了输出，每次都让下游任务的缓存键失效。\n\n- 时间戳只在发版任务里写入\n- 或者把它标成 `@Internal`，不参与缓存键"))
    state.localSelectedTaskKey = record.key
}

/** 截图用的 ACP 传输：答握手（initialize 带 [capabilities]、带模式 / 模型两个配置项的 session/new）；接回时 session/load 先回放 [replay] 再回包，
 *  session/resume 直接回包（回包都带同样两个配置项，模型是 Pro）；[reply] 不为空就答 session/prompt（先回一段话，再 end_turn）。别的请求不回。
 *  [commands] 不为空就在 session/new 回包之后发一条 available_commands_update（M4k）。
 *  收到的方法记进 [shotAcpRequests]。管道流，不起进程、不外连。回包在 write 里同步写（AcpClient 先登记 pending 再写），写端是 EDT
 *  （一直活着，读端不会报 Pipe broken）；回放和回复都很短，管道写不满。 */
private class ShotAcpTransport(private val sessionId: String, private val capabilities: JSONObject = JSONObject(),
    private val replay: List<JSONObject> = emptyList(), private val reply: ((String) -> String)? = null, private val commands: JSONArray? = null) : AcpTransport {
    override val output = PipedInputStream(65536)
    private val pipe = PipedOutputStream(output)
    override suspend fun write(text: String): Boolean {
        val request = JSONObject(text.trim())
        val method = request.optString("method")
        shotAcpRequests += method
        val result = when (method) {
            "initialize" -> JSONObject().put("protocolVersion", 1).put("agentCapabilities", capabilities)
            "session/new" -> JSONObject().put("sessionId", sessionId).put("configOptions", shotAcpOptions("gemini-2.5-pro"))
            "session/load" -> { replay.forEach(::update); JSONObject().put("configOptions", shotAcpOptions("gemini-2.5-pro")) }
            "session/resume" -> JSONObject().put("configOptions", shotAcpOptions("gemini-2.5-pro"))
            "session/prompt" -> reply?.let { answer ->
                update(acpChunk("agent_message_chunk", answer(request.getJSONObject("params").getJSONArray("prompt").getJSONObject(0).getString("text"))))
                JSONObject().put("stopReason", "end_turn")
            }
            else -> null
        } ?: return true
        send(JSONObject().put("id", request.get("id")).put("result", result))
        if (method == "session/new" && commands != null) update(JSONObject().put("sessionUpdate", "available_commands_update").put("availableCommands", commands))
        return true
    }
    private fun send(message: JSONObject) { pipe.write((message.put("jsonrpc", "2.0").toString() + "\n").toByteArray()); pipe.flush() }
    private fun update(update: JSONObject) = send(JSONObject().put("method", "session/update").put("params", JSONObject().put("sessionId", sessionId).put("update", update)))
    override fun close() { pipe.close(); output.close() }
}

/** [ShotAcpTransport] 收到的方法（本机对齐 P3 的场景摆场景时清空）。 */
private val shotAcpRequests = java.util.concurrent.CopyOnWriteArrayList<String>()

/** 斜杠菜单（M4k）的 SSH 场景：[remote] 上把这台主机的命令表先放进缓存（不连主机），草稿「/mo」。 */
private fun sshSlash(state: AppState) {
    remote(state, tab = 0)
    RemoteSlashCommands.put(HOST.id, listOf(app.yxi.agent.ConfigRemote.Tool("claude", "Claude Code", listOf(app.yxi.agent.ConfigRemote.Cat("commands", "斜杠命令",
        listOf(app.yxi.agent.ConfigRemote.Item("mobile-release"), app.yxi.agent.ConfigRemote.Item("deploy")))))))
    state.chatDrafts[taskNavigationKey(HOST, SESSION)] = androidx.compose.runtime.mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("/mo", androidx.compose.ui.text.TextRange(3)))
}

/** 斜杠菜单（M4k）的 ACP 场景：[localAcp] 的运行器在 session/new 之后报 [SHOT_ACP_COMMANDS]，草稿「/」。请求记录先清空。 */
private fun acpSlash(state: AppState) {
    shotAcpRequests.clear()
    localAcp(state, commands = JSONArray(SHOT_ACP_COMMANDS.map { (name, description) -> JSONObject().put("name", name).put("description", description) }))
    state.chatDrafts[checkNotNull(state.localSelectedTaskKey)] = androidx.compose.runtime.mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("/", androidx.compose.ui.text.TextRange(1)))
}

/** local-acp-slash 里运行器报的命令表（名字、说明；同 ACP 文档的例子）。 */
private val SHOT_ACP_COMMANDS = listOf("web" to "Search the web for information", "test" to "Run tests for the current project",
    "plan" to "Create a detailed implementation plan")

/**
 * 斜杠菜单的位置（M4k）：按输入框（草稿是 [draft] 的那个可编辑节点）推出输入卡——单行草稿时输入行 44 高、字贴底（下边距 12），
 * 卡片顶 = 字底 + 12 − 44，左缘 = 字起点 − 10。每行的命令名字墨起于卡片左 + 12.8（浮层内边距 4 + 行内 8.8）；[last] 是最下面一行的命令名
 * （行高 24、字高 20 居中），它的行底 + 内边距 4 就是浮层底，离卡片顶 6。[rows] 不为空时核露出来的行数（多了在浮层里滚，看不见的行不进语义树）。
 */
private fun checkSlashMenu(nodes: List<String>, draft: String, last: String?, rows: Int? = null) {
    fun num(line: String, key: String) = Regex("""$key=(-?[0-9.]+)""").find(line)!!.groupValues[1].toFloat()
    val field = nodes.lastOrNull { it.trimEnd().endsWith(" $draft") && !it.contains("Button") } ?: error("没有草稿是「$draft」的输入框")
    val left = num(field, "x") - 10f
    val top = num(field, "y") + num(field, "h") + 12f - 44f
    // 浮层是单独的一层（行首的 L 号和输入框那层不同）
    val names = nodes.filter { !it.startsWith(field.substringBefore(' ') + " ") && Regex(" /[^ ]+$").containsMatchIn(it.trimEnd()) && num(it, "y") < top }
    check(names.isNotEmpty()) { "输入卡上方没有斜杠菜单" }
    check(names.all { kotlin.math.abs(num(it, "x") - (left + 12.8f)) < 1f }) { "命令名没起于卡片左缘 + 12.8：$names" }
    if (rows != null) check(names.size == rows) { "露出来 ${names.size} 行，应是 $rows：$names" }
    val bottom = names.maxBy { num(it, "y") }
    if (last != null) check(bottom.trimEnd().endsWith(" $last")) { "最下面一行不是「$last」：$bottom" }
    val gap = top - (num(bottom, "y") + num(bottom, "h") + 2f + 4f)
    check(kotlin.math.abs(gap - 6f) < 1f) { "浮层底离卡片顶 $gap，应是 6（输入框 $field，最下一行 $bottom）" }
}

/** 截图 ACP 会话的两个配置项：模式（Default）和模型（[model] 是当前值）。 */
private fun shotAcpOptions(model: String): JSONArray {
    fun selector(id: String, name: String, category: String, current: String, vararg choices: Pair<String, String>) = JSONObject()
        .put("id", id).put("name", name).put("category", category).put("type", "select").put("currentValue", current)
        .put("options", JSONArray().apply { choices.forEach { (v, n) -> put(JSONObject().put("value", v).put("name", n)) } })
    return JSONArray()
        .put(selector("mode", "模式", "mode", "default", "default" to "Default", "autoEdit" to "Auto Edit", "yolo" to "YOLO"))
        .put(selector("model", "模型", "model", model, "gemini-2.5-pro" to "Gemini 2.5 Pro", "gemini-2.5-flash" to "Gemini 2.5 Flash"))
}

private fun acpChunk(kind: String, text: String) = JSONObject().put("sessionUpdate", kind).put("content", JSONObject().put("type", "text").put("text", text))

/** [localAcpRestore] 回放的对话：同 [localAcp] 那段，再加一来一回；回复拆成几片，工具卡夹在两段文字中间。回放里的模型是旧值（Flash），回包里是 Pro。 */
private fun shotAcpReplay() = listOf(
    acpChunk("user_message_chunk", "构建每次都全量重跑，帮我看看缓存为什么没命中"),
    acpChunk("agent_message_chunk", "先看看构建脚本。"),
    JSONObject().put("sessionUpdate", "tool_call").put("toolCallId", "tool-1").put("title", "读取 build.gradle.kts").put("status", "completed")
        .put("content", JSONArray().put(JSONObject().put("type", "content").put("content", JSONObject().put("type", "text").put("text", "读取了 1 个文件")))),
    acpChunk("agent_message_chunk", "## 原因\n\n`generateVersion` 把**当前时间**写进了输出，"),
    acpChunk("agent_message_chunk", "每次都让下游任务的缓存键失效。\n\n- 时间戳只在发版任务里写入\n- 或者把它标成 `@Internal`，不参与缓存键"),
    JSONObject().put("sessionUpdate", "config_option_update").put("configOptions", shotAcpOptions("gemini-2.5-flash")),
    acpChunk("user_message_chunk", "那就只在发版任务里写时间戳"),
    acpChunk("agent_message_chunk", "改好了：`generateVersion` 只在 `release` 任务里运行，日常构建从第二次起就能命中缓存。"))

/** 本机 / 服务器 ACP 排队场景里排着的两条。 */
private val SHOT_ACP_QUEUED = listOf("加到流水线里，第二次构建要求全部命中缓存", "顺手把检查结果写进构建摘要")

/** 服务器 ACP 对话：[sshList] 里那条 Gemini 托管任务，照侧栏行的 open（OpenCodeTaskRow）打开。conn 已由 sshList 选好，不调 select
 *  （它会写 lastHost 等偏好）；不连、不建控制器。 */
private fun sshAcp(state: AppState) {
    sshList(state, saved = false)
    val review = state.remoteAcpTasks.registry.records.single { it.threadId == "shot-acp-1" }
    state.remoteAcpEngine = review.engine; state.remoteAcpSelectedKey = review.key; state.page = Page.Acp
}

/**
 * 本机 ACP 接回（本机对齐 P3）：[localHome] 上加一条 Gemini 记录、一个用原数据目录的 Gemini 运行器，打开记录；页面自动接回一次。
 * connect 钩子换成 [ShotAcpTransport]（[mode]：load 声明 loadSession、回放 [shotAcpReplay]；resume 只声明 resume；none 都不声明），
 * 不起进程、不外连；钩子计数进 [shotConnects]。记录只改内存（回包里的模型和记录一致，不写盘）。
 */
private fun localAcpRestore(state: AppState, mode: String) {
    localHome(state)
    val demo = File(System.getenv("YXI_THEME_SHOT_FIXTURE")).canonicalFile.resolve("projects").resolve("demo-app").canonicalFile
    val gemini = LocalRuntimeInstallation("gemini", "PATH", listOf("C:\\fixture\\bin\\gemini.exe"), "C:\\fixture\\gemini-home", "0.0.0-fixture")
    val record = LocalCodexTaskRecord("shot-acp-$mode", System.getProperty("user.name"), System.getProperty("os.name"),
        File(gemini.home).canonicalPath, demo.path, "排查构建缓存为什么没命中", "gemini-2.5-pro", 1_700_000_400_000L, "gemini")
    state.localWorkspace.forceState("installations", state.localWorkspace.installations + gemini)
    val capabilities = when (mode) {
        "load" -> JSONObject().put("loadSession", true)
        "resume" -> JSONObject().put("sessionCapabilities", JSONObject().put("resume", JSONObject()))
        else -> JSONObject()
    }
    shotConnects.set(0); shotAcpRequests.clear()
    state.localAcpTasks.connect = { _, _ ->
        shotConnects.incrementAndGet()
        AcpClient(ShotAcpTransport(record.threadId, capabilities, if (mode == "load") shotAcpReplay() else emptyList())).also { it.initialize(terminalAuthentication = true) }
    }
    val records = state.localAcpTasks.registry.records
    if (records.none { it.key == record.key }) records += record
    state.localSelectedTaskKey = record.key
}

/**
 * 本机 ACP 的排队（本机对齐 P3）：同 [localAcp] 直接摆一个连着的控制器（不走接回），先有一来一回，队里两条；[automatic] 写进排队偏好，
 * 页面套用。传输每条都回一段话和 end_turn。记录编号按主题分开：上一张的队列和偏好落在夹具 profile 里，同一编号会串到下一张。
 */
private fun localAcpQueue(state: AppState, automatic: Boolean) {
    localHome(state)
    val demo = File(System.getenv("YXI_THEME_SHOT_FIXTURE")).canonicalFile.resolve("projects").resolve("demo-app").canonicalFile
    val record = LocalCodexTaskRecord("shot-acp-${if (automatic) "auto" else "queue"}-" + Store.pref("theme", "light"), System.getProperty("user.name"),
        System.getProperty("os.name"), File("C:\\fixture\\gemini-home").canonicalPath, demo.path, "给构建缓存加回归检查", "gemini-2.5-pro", 1_700_000_500_000L, "gemini")
    val client = AcpClient(ShotAcpTransport(record.threadId, reply = { "好的，已经处理：$it" }))
    runBlocking { client.initialize(); client.newSession(demo.path) }
    shotAcpRequests.clear()
    val controller = AcpTaskController(record.key, record.threadId, client, state.instructions)
    state.localAcpTasks.registry.records += record
    state.localAcpTasks.controllers[record.key] = controller
    controller.messages += listOf(
        AcpMessage("user-1", "User", "给 generateVersion 的缓存问题补一个回归检查"),
        AcpMessage("assistant-1", "Assistant", "可以在 CI 里连跑两次构建，第二次要求全部命中缓存。要我直接加到流水线里吗？"))
    check(QueuePreferences.setEnabled(record.key, automatic) == null) { "写排队偏好失败" }
    SHOT_ACP_QUEUED.forEachIndexed { i, text -> state.instructions.enqueue(record.key, text, id = "${record.threadId}-${i + 1}") }
    state.localSelectedTaskKey = record.key
}

/** 服务器 ACP 的排队（本机对齐 P3）：[sshAcp] 那条 Gemini 托管任务接一个连着的控制器（[ShotAcpTransport]，不连主机），队里两条、自动排队关着。 */
private fun sshAcpQueue(state: AppState) {
    sshAcp(state)
    val record = state.remoteAcpTasks.registry.records.single { it.threadId == "shot-acp-1" }
    val client = AcpClient(ShotAcpTransport(record.threadId))
    runBlocking { client.initialize(); client.newSession(record.directory) }
    state.remoteAcpTasks.controllers[record.key] = AcpTaskController(record.key, record.threadId, client, state.instructions)
    check(QueuePreferences.setEnabled(record.key, false) == null) { "写排队偏好失败" }
    SHOT_ACP_QUEUED.forEachIndexed { i, text -> state.instructions.enqueue(record.key, text, id = "shot-acp-remote-${i + 1}") }
}

/**
 * 本机 Claude 自动恢复（本机对齐 P5）：[localHome] 里那条 Claude 记录换成 UUID 会话、配置目录换到夹具目录里的 claude-home（写一份两条的原生历史），
 * 加一个就绪的运行器指着它（只有它匹配，页面才会自动恢复）。resumeConnection 钩子照 LocalClaudeTasksTest 的写法走真的
 * LocalClaudeSubscription.resume 核对，只把起进程换成 [ShotClaudeTransport]；次数记进 [shotConnects]。
 */
private fun localClaudeRestore(state: AppState) {
    // 前面几张（和浅色那张的自己）落盘的会话记录会被读回来，先清掉，再由 localHome 摆回同样的三条
    state.localClaudeTasks.registry.records.clear(); state.localCodexTasks.registry.records.clear()
    state.localOpenCodeTasks.registry.records.clear(); state.localAcpTasks.registry.records.clear()
    localHome(state)
    val fixture = File(System.getenv("YXI_THEME_SHOT_FIXTURE")).canonicalFile
    val home = fixture.resolve("claude-home").canonicalFile
    val records = state.localClaudeTasks.registry.records
    val record = records[0].copy(threadId = "00000000-0000-4000-8000-000000000005", runtimeHome = home.path, model = "claude-sonnet-4-5", provider = "official:claude")
    records[0] = record
    val rows = listOf(
        JSONObject().put("type", "user").put("uuid", "shot-u1").put("parentUuid", JSONObject.NULL)
            .put("message", JSONObject().put("role", "user").put("content", "登录页的报错提示太笼统了，按错误码分开写")),
        JSONObject().put("type", "assistant").put("uuid", "shot-a1").put("parentUuid", "shot-u1")
            .put("message", JSONObject().put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "text")
                .put("text", "好的，我按 401 / 403 / 429 分开提示，并保留原来的兜底文案。")))))
    home.resolve("projects/demo-app").apply { deleteRecursively(); mkdirs() }.resolve("${record.threadId}.jsonl")
        .writeText(rows.joinToString("") { it.put("sessionId", record.threadId).put("cwd", record.directory).toString() + "\n" })
    val claude = LocalRuntimeInstallation("claude", "PATH", listOf("C:\\fixture\\bin\\claude-restore.exe"), home.path, "2.0.0 (Claude Code)")
    state.localWorkspace.forceState("installations", state.localWorkspace.installations + claude)
    shotConnects.set(0)
    state.localClaudeTasks.resumeConnection = { runtime, saved ->
        shotConnects.incrementAndGet()
        LocalClaudeSubscription().resume(runtime, saved) { _, _, id -> ClaudeControlClient(ShotClaudeTransport(saved.model), id) }
    }
    state.localSelectedTaskKey = record.key
}

/** localCodex 第一轮的工具摘要行（命令 + 文件改动）和其中命令条目的标题（M3c 的两个场景点它们）。 */
private const val SHOT_CODEX_TOOLS = "运行了一条命令，改了 parser.rs、Cargo.toml"
private const val SHOT_CODEX_COMMAND = "rg -n \"pub fn parse\" src"

/** 本机文件的夹具（本机对齐 P6b）：projects/demo-app 下摆一个 src 目录和两个文件，修改时间都固定（列表里显示时间），只写在夹具目录里。 */
private fun shotLocalFiles() {
    val demo = File(System.getenv("YXI_THEME_SHOT_FIXTURE")).canonicalFile.resolve("projects").resolve("demo-app")
    val at = 1_760_000_000_000L
    demo.resolve("src").mkdirs()
    demo.resolve("src/index.ts").apply { writeText("export const parse = (input: string) => input.trim()\n"); setLastModified(at) }
    demo.resolve("package.json").apply { writeText("{\n  \"name\": \"demo-app\",\n  \"scripts\": { \"test\": \"vitest\" }\n}\n"); setLastModified(at) }
    demo.resolve("README.md").apply { writeText("# demo-app\n\n解析器的小示例。\n\n## 开发\n\n- 跑 npm test 看单测\n- 改 src/index.ts\n"); setLastModified(at) }
    demo.resolve("src").setLastModified(at)
}

/** M4p：本机分支改动条的固定数（同 [shotLocalGit] 的分支：两个文件、+12 −3）。 */
private val SHOT_BRANCH = BranchStat("fix/login-errors", "8b7d4e0c2f61a93d5e7b0c4a1f2e3d4c5b6a7980", 2, 12, 3)

/** 本机 Git 改动的固定快照（本机对齐 P6a）：一个两边都改了的文件和一个未跟踪的测试文件；选了 errors.ts 时给一段差异（未暂存的那份）。 */
private fun shotLocalGit() {
    LocalGit.run = { directory, file, staged ->
        val diff = if (file != "src/auth/errors.ts") "" else if (staged) "" else listOf(
            "diff --git a/src/auth/errors.ts b/src/auth/errors.ts", "index 3f2a9c1..8b7d4e0 100644",
            "--- a/src/auth/errors.ts", "+++ b/src/auth/errors.ts", "@@ -12,7 +12,9 @@ export function loginMessage(code: number) {",
            "   switch (code) {", "-    default: return '登录失败，请稍后重试'", "+    case 401: return '账号或密码不对'",
            "+    case 403: return '这个账号没有权限登录'", "+    default: return '登录失败，请稍后重试'", "   }", " }").joinToString("\n")
        JSONObject().put("root", directory).put("branch", "fix/login-errors").put("head", "8b7d4e0c2f61a93d5e7b0c4a1f2e3d4c5b6a7980")
            .put("files", JSONArray().put(JSONObject().put("code", "MM").put("path", "src/auth/errors.ts"))
                .put(JSONObject().put("code", "??").put("path", "src/auth/errors.test.ts")))
            .put("diff", diff).put("clipped", false).put("readAt", "2026-10-15T03:40:00Z")
    }
}

/** 截图用的假 Claude 控制传输（同 LocalClaudeTasksTest 的 Fixture，只答控制请求）；块队列代替管道流，同 [ShotCodexTransport]。 */
private class ShotClaudeTransport(private var model: String) : ClaudeControlTransport {
    private val chunks = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private val end = ByteArray(0)
    private var effort = "medium"
    override val output: java.io.InputStream = object : java.io.InputStream() {
        private var current = ByteArray(0)
        private var at = 0
        private fun fill(): Boolean {
            while (at >= current.size) {
                val next = chunks.take()
                if (next === end) { chunks.put(end); return false }
                current = next; at = 0
            }
            return true
        }
        override fun read(): Int = if (fill()) current[at++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, current.size - at)
            System.arraycopy(current, at, b, off, n); at += n
            return n
        }
    }
    override suspend fun write(text: String): Boolean {
        val request = JSONObject(text)
        if (request.optString("type") != "control_request") return true
        val control = request.getJSONObject("request")
        val subtype = control.getString("subtype")
        if (subtype == "set_model") model = control.getString("model")
        if (subtype == "apply_flag_settings") effort = control.getJSONObject("settings").optString("effortLevel", effort)
        val value = if (subtype == "initialize") JSONObject()
            .put("account", JSONObject().put("tokenSource", "claude.ai").put("apiProvider", "firstParty"))
            .put("models", JSONArray(listOf("claude-sonnet-4-5", "claude-opus-4-1").map { name ->
                JSONObject().put("resolvedModel", name).put("supportsEffort", true).put("supportedEffortLevels", JSONArray(listOf("low", "medium", "high")))
            }))
        else JSONObject().put("effective", ClaudeSubscriptionSettings.overlay()).put("applied", JSONObject().put("model", model).put("effort", effort))
        chunks.put((JSONObject().put("type", "control_response").put("response", JSONObject().put("subtype", "success")
            .put("request_id", request.getString("request_id")).put("response", value)).toString() + "\n").toByteArray())
        return true
    }
    override fun close() { chunks.put(end) }
}

/** 截图用的假 OpenCode（回环，NoExec 放行）：[localOpenCode] 起，shootCode 收尾时停。 */
private val shotServers = mutableListOf<HttpServer>()

/**
 * 本机 OpenCode 对话（M2-b b4）：打开 [localHome] 里那条 OpenCode 记录（供应商 / 模型换成像样的；key 不含它们，侧栏照样选中）。
 * 控制器接回环上的假 OpenCode：只答 refresh 要的几条 GET（会话归属、200 条消息、审批、提问、状态），别的给 404；
 * 在 EDT 上先同步 refresh 一次（请求跑在 IO 上），第一帧就有数据，之后面板每 2 秒刷新一次，回的内容不变。[permission] 多一轮等审批的。
 */
private fun localOpenCode(state: AppState, permission: Boolean, running: Boolean = false, thinking: Boolean = false, read: Boolean = false, thinkingNow: Boolean = false) {
    localHome(state)
    val records = state.localOpenCodeTasks.registry.records
    val index = records.indexOfFirst { it.threadId == "shot-local-3" }
    val record = records[index].copy(provider = "anthropic", model = "claude-sonnet-4-5").also { records[index] = it }
    val sid = record.threadId
    fun message(id: String, role: String, vararg parts: JSONObject) = JSONObject()
        .put("info", JSONObject().put("id", id).put("sessionID", sid).put("role", role))
        .put("parts", JSONArray().apply { parts.forEach { put(it) } })
    fun text(value: String) = JSONObject().put("type", "text").put("text", value)
    val history = JSONArray()
        .put(message("msg_shot_1", "user", text("侧栏导航太乱了，按「入门 / 指南 / 参考」三组重排一下")))
        .put(message("msg_shot_2", "assistant", *(if (thinking) arrayOf(JSONObject().put("type", "reasoning").put("text", "先看 nav.yml 里有多少个页面，再按读者路径分组。")) else emptyArray()),
            *(if (read) arrayOf(JSONObject().put("type", "tool").put("tool", "read").put("state", JSONObject().put("status", "completed")
                .put("input", JSONObject().put("filePath", record.directory + "/nav.yml"))
                .put("output", "<file>\n00001| - title: 安装\n00002|   path: install.md\n00003| - title: 快速开始\n00004|   path: quickstart.md\n</file>"))) else emptyArray()),
            text("## 调整方案\n\n现在的 `nav.yml` 把 14 个页面平铺在一层，我按读者路径分成三组：\n\n" +
            "- **入门**：安装、快速开始\n- **指南**：部署、配置、排错\n- **参考**：命令行、配置项、API\n\n旧链接保留跳转，不会 404。")))
    val permissions = JSONArray()
    val status = JSONObject()
    if (permission) {
        history.put(message("msg_shot_3", "user", text("改完跑一遍站点构建，确认没有死链")))
            .put(message("msg_shot_4", "assistant", text("先跑构建；这条命令要你批准。")))
        permissions.put(JSONObject().put("id", "per_shot_1").put("sessionID", sid).put("permission", "bash")
            .put("patterns", JSONArray().put("npm run docs:build")))
        status.put(sid, JSONObject().put("type", "busy"))
    }
    if (thinkingNow) history.put(message("msg_shot_5", "user", text("旧链接都跳到哪？列一下")))   // M3h-4：最后一项是思考 → 思考行
        .put(message("msg_shot_6", "assistant", JSONObject().put("type", "reasoning").put("text", "要对照 redirects.yml 和新的分组。")))
    if (running) status.put(sid, JSONObject().put("type", "busy"))   // M3f：在跑、没有审批，对话末尾出状态行
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
        val body = when (exchange.requestURI.path) {
            "/session/$sid" -> JSONObject().put("id", sid).put("directory", record.directory).toString()
            "/session/$sid/message" -> history.toString()
            "/permission" -> permissions.toString()
            "/question" -> "[]"
            "/session/status" -> status.toString()
            else -> null
        }?.toByteArray()
        exchange.sendResponseHeaders(if (body == null) 404 else 200, body?.size?.toLong() ?: -1L)
        body?.let { exchange.responseBody.write(it) }
        exchange.close()
    }
    server.start(); shotServers += server
    val controller = OpenCodeTaskController(record.key, sid, record.directory, record.provider, record.model,
        OpenCodeClient(server.address.port, "fixture", record.directory), state.instructions)
    runBlocking { controller.refresh() }
    state.localOpenCodeTasks.controllers[record.key] = controller
    state.localSelectedTaskKey = record.key
}

/**
 * 本机 OpenCode 接回（本机对齐 P4）：[localHome] 里那条 OpenCode 记录换成用 OpenCode 运行器自己的数据目录（key 跟着变，只改内存），
 * 加一个就绪的 OpenCode 运行器，打开记录、不建控制器，让页面自动接回。serve 钩子每次起一个回环上的假 OpenCode（计数进 [shotConnects]），
 * 答 refresh 要的几条 GET，历史同 [localOpenCode] 的第一轮；[known] 为 false 时这个会话 404（原生端已删）。
 */
private fun localOpenCodeRestore(state: AppState, known: Boolean) {
    localHome(state)
    val opencode = LocalRuntimeInstallation("opencode", "PATH", listOf("C:\\fixture\\bin\\opencode.exe"), "C:\\fixture\\opencode-home", "1.18.32")
    state.localWorkspace.forceState("installations", state.localWorkspace.installations + opencode)
    val records = state.localOpenCodeTasks.registry.records
    val index = records.indexOfFirst { it.threadId == "shot-local-3" }
    val record = records[index].copy(runtimeHome = File(opencode.home).canonicalPath, provider = "anthropic", model = "claude-sonnet-4-5")
        .also { records[index] = it }
    val sid = record.threadId
    val history = JSONArray()
        .put(JSONObject().put("info", JSONObject().put("id", "msg_shot_1").put("sessionID", sid).put("role", "user"))
            .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", "侧栏导航太乱了，按「入门 / 指南 / 参考」三组重排一下"))))
        .put(JSONObject().put("info", JSONObject().put("id", "msg_shot_2").put("sessionID", sid).put("role", "assistant"))
            .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", "## 调整方案\n\n按读者路径分成「入门 / 指南 / 参考」三组，旧链接保留跳转。"))))
    shotConnects.set(0)
    state.localOpenCodeTasks.serve = { _, _, _ ->
        shotConnects.incrementAndGet()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = when (exchange.requestURI.path) {
                "/session/$sid" -> if (known) JSONObject().put("id", sid).put("directory", record.directory).toString() else null
                "/session/$sid/message" -> if (known) history.toString() else null
                "/permission", "/question" -> "[]"
                "/session/status" -> "{}"
                "/mcp" -> "{}"
                else -> null
            }?.toByteArray()
            exchange.sendResponseHeaders(if (body == null) 404 else 200, body?.size?.toLong() ?: -1L)
            body?.let { exchange.responseBody.write(it) }
            exchange.close()
        }
        // 不进 shotServers：接回失败时 open 自己关，接上的由 closeLocalFeatures → LocalOpenCodeTasks.close 关，免得停两次
        server.start()
        object : OpenCodeServing {
            override val client = OpenCodeClient(server.address.port, "fixture", record.directory)
            override fun close() = server.stop(0)
        }
    }
    state.localSelectedTaskKey = record.key
}

/** 服务器 OpenCode 对话：[sshList] 里那条 OpenCode 托管任务，照侧栏行的 open（OpenCodeTaskRow）打开；同 [sshAcp]，不调 select、不连、不建控制器。 */
private fun sshOpenCode(state: AppState) {
    sshList(state, saved = false)
    val lint = state.remoteOpenCodeTasks.registry.records.single { it.threadId == "shot-opencode-1" }
    state.remoteOpenCodeSelectedKey = lint.key; state.page = Page.OpenCode
}

/**
 * 服务器 Codex 对话（本机对齐 P2）：[sshList] 里那条「登录页改版」，照侧栏行的 open 选中（conn 已由 sshList 选好，不调 select）。
 * 控制器接 [ShotCodexTransport]（不起进程、不外连），在 EDT 上同步 reconcile 一次，第一帧就有历史；控制器已在，「连接任务」不出现。
 * 再照 workspace.open 记下会话配置（模型、供应商，模型菜单的「当前线路」读它）。
 * 只进内存，收尾由 codexWorkspace.close 关掉。[approval] 同 [localCodex]：多一轮进行中、命令在等审批。
 */
private fun serverCodex(state: AppState, approval: Boolean) {
    sshList(state, saved = false)
    val login = state.codexWorkspace.registry.records.single { it.threadId == "shot-codex-1" }
    val request = if (!approval) null else JSONObject().put("id", 7).put("method", "item/commandExecution/requestApproval").put("params", JSONObject()
        .put("threadId", login.threadId).put("turnId", "turn-2").put("itemId", "item-6").put("command", "cargo bench --bench parser")
        .put("cwd", login.directory).put("reason", "要编译 criterion 并写入 target 目录"))
    val goal = if (approval) null else JSONObject().put("threadId", login.threadId).put("objective", "解析器的性能回归要有基准测试兜底，CI 里每晚跑一次")
        .put("status", "active").put("timeUsedSeconds", 754L).put("tokensUsed", 182_406L).put("tokenBudget", JSONObject.NULL)
    val controller = CodexTaskController(login.key, login.threadId, CodexAppServer(ShotCodexTransport(shotCodexThread(login.threadId, login.directory, approval), goal, request)),
        state.instructions, initialModel = login.modelOverride, initialEffort = login.effortOverride)
    runBlocking { controller.reconcile() }
    controller.recordSessionConfiguration(JSONObject().put("model", "gpt-5-codex").put("modelProvider", "openai"))
    state.codexWorkspace.controllers[login.key] = controller
    state.codexSelectedTaskKey = login.key; state.page = Page.Codex
}

/**
 * 服务器 Codex 没接上、ssh 断了：同一条选中、不注入控制器，主机状态摆成「正在重新连接…」（同 Conn 断线后的状态，只改内存、不起重连）。
 * 夹具的 ssh 本来就没连，online 为假：「连接任务」置灰，旁边注「服务器未连接」，和侧栏的主机标题对得上。
 */
private fun serverCodexOffline(state: AppState) {
    sshList(state, saved = false)
    val login = state.codexWorkspace.registry.records.single { it.threadId == "shot-codex-1" }
    checkNotNull(state.conn).status = Conn.Status.Reconnecting
    state.codexSelectedTaskKey = login.key; state.page = Page.Codex
}

/**
 * 服务器连着、任务没接上：同一条选中、不注入控制器；connected 缝放行（夹具的 ssh 没连），clientFactory 给假 app-server。
 * 点「连接任务」走真的 workspace.open（thread/resume → thread/read），不起进程、不外连；接回的控制器只进内存，收尾由 codexWorkspace.close 关掉。
 */
private fun serverCodexDetached(state: AppState) {
    sshList(state, saved = false)
    val login = state.codexWorkspace.registry.records.single { it.threadId == "shot-codex-1" }
    state.codexSelectedTaskKey = login.key; state.page = Page.Codex
    shotRequests.clear()
    state.codexWorkspace.clientFactory = { CodexAppServer(ShotCodexTransport(shotCodexThread(login.threadId, login.directory, approval = false), null, null)) }
    state.codexWorkspace.connected = { true }
}

/**
 * 服务器首页直接建（P2，交接 §7-21）：Code 首页选对话工作台、文件夹 /srv/web、摆提示词；clientFactory 给假 app-server，connected 缝放行
 * （夹具的 ssh 没连）。不起进程、不外连。浅色那张建的记录写进夹具 profile 的任务登记，sshList 的 forceState 会盖掉，深色那张照样从头建。
 */
private fun serverHomeCreate(state: AppState) {
    sshList(state, saved = false)
    val d = state.newSessionDraft
    d.agent = NewSessionDraft.CODEX_WORKBENCH
    d.setDirectory(checkNotNull(state.conn), "/srv/web")
    d.text = androidx.compose.ui.text.input.TextFieldValue(SHOT_HOME_PROMPT)
    shotRequests.clear()
    // thread/start 回的是刚建的空对话（没有轮次、空闲）；cwd 由假 app-server 按请求填
    val created = JSONObject().put("id", SHOT_SERVER_CREATED_THREAD).put("status", JSONObject().put("type", "idle")).put("turns", JSONArray())
    state.codexWorkspace.clientFactory = { CodexAppServer(ShotCodexTransport(created, null, null)) }
    state.codexWorkspace.connected = { true }
}

/**
 * 本机 Codex 对话（本机对齐 P1b）：打开 [localHome] 里那条 Codex 记录（模型换成真实的 id、强度记成 medium；key 不含它们，侧栏照样选中）。
 * 控制器接 [ShotCodexTransport]（不起进程、不外连），在 EDT 上同步 reconcile 一次，第一帧就有历史；控制器已在，面板不去接回。
 * 记录和控制器只进内存，收尾由 closeLocalFeatures 关掉。[approval] 多一轮进行中、命令在等审批（请求跟在 thread/read 的回包后面，EDT 空出来后才到）。
 */
private fun localCodex(state: AppState, approval: Boolean) {
    localHome(state)
    val records = state.localCodexTasks.registry.records
    val index = records.indexOfFirst { it.threadId == "shot-local-2" }
    val record = records[index].copy(model = "gpt-5-codex", effort = "medium").also { records[index] = it }
    val cwd = "C:\\work\\demo-app"   // 命令和审批里显示的目录：不用夹具的真实路径（随机器变）
    val request = if (!approval) null else JSONObject().put("id", 7).put("method", "item/commandExecution/requestApproval").put("params", JSONObject()
        .put("threadId", record.threadId).put("turnId", "turn-2").put("itemId", "item-6").put("command", "cargo bench --bench parser")
        .put("cwd", cwd).put("reason", "要编译 criterion 并写入 target 目录"))
    val goal = if (approval) null else JSONObject().put("threadId", record.threadId).put("objective", "解析器的性能回归要有基准测试兜底，CI 里每晚跑一次")
        .put("status", "active").put("timeUsedSeconds", 754L).put("tokensUsed", 182_406L).put("tokenBudget", JSONObject.NULL)
    val controller = CodexTaskController(record.key, record.threadId, CodexAppServer(ShotCodexTransport(shotCodexThread(record.threadId, cwd, approval), goal, request)),
        state.instructions, initialModel = record.model, initialEffort = record.effort)
    runBlocking { controller.reconcile() }
    state.localCodexTasks.controllers[record.key] = controller
    state.localSelectedTaskKey = record.key
}

/** 本机 Codex 接不回：同一数据目录的运行器版本检测失败（文案同 LocalRuntimeDiscovery），侧栏照样列出、选中这条；面板只给原因和 chip，不连接。 */
private fun localCodexUnavailable(state: AppState) {
    localHome(state)
    val w = state.localWorkspace
    val broken = w.installations.first { it.engine == "codex" }.copy(version = "", problem = "版本检测失败，请检查安装或依赖")
    w.forceState("installations", w.installations.map { if (it.engine == "codex") broken else it }); w.forceState("selectedRuntime", broken)
    val records = state.localCodexTasks.registry.records
    val index = records.indexOfFirst { it.threadId == "shot-local-2" }
    records[index] = records[index].copy(model = "gpt-5-codex")
    state.localSelectedTaskKey = records[index].key
}

/** 接回试了几次：[localCodexFailed]、[localAcpRestore] 的 connect 钩子计数，每张图摆场景时清零。 */
private val shotConnects = java.util.concurrent.atomic.AtomicInteger()

/**
 * 本机 Codex 接回失败：[localHome] 里那条 Codex 记录选中、没有控制器，运行器检测正常；connect 钩子计一次数就抛
 * （文案是官方连接核对失败时的真实文案），不起进程、不外连。记录只改内存。
 */
private fun localCodexFailed(state: AppState) {
    localHome(state)
    shotConnects.set(0)
    state.localCodexTasks.connect = { shotConnects.incrementAndGet(); throw IllegalStateException("运行器未确认官方提供方，未启用发送") }
    val records = state.localCodexTasks.registry.records
    val index = records.indexOfFirst { it.threadId == "shot-local-2" }
    records[index] = records[index].copy(model = "gpt-5-codex")
    state.localSelectedTaskKey = records[index].key
}

/**
 * 本机 Codex 的图片草稿（P1c）：在 [localCodex] 上摆一句草稿和两张图。图是纯色块拼的示意截图（只填整数矩形、不画字，字节每次一样），
 * 经 images.capture 存进夹具 profile 里的快照目录；草稿只进内存（AppState 的两张表），不入队。
 */
private fun localCodexImages(state: AppState) {
    localCodex(state, approval = false)
    val key = checkNotNull(state.localSelectedTaskKey)
    state.chatDrafts[key] = androidx.compose.runtime.mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("对照这两张截图，看看深色模式下侧栏哪里不一样？"))
    fun mock(background: Int, bar: Int, panel: Int): ByteArray {
        val image = java.awt.image.BufferedImage(320, 200, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = java.awt.Color(background); g.fillRect(0, 0, 320, 200)
            g.color = java.awt.Color(bar); g.fillRect(0, 0, 320, 24)
            g.color = java.awt.Color(panel); g.fillRect(0, 24, 96, 176); g.fillRect(112, 40, 192, 56); g.fillRect(112, 112, 136, 16)
        } finally { g.dispose() }
        return java.io.ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
    val drafts = state.localCodexImageDrafts.getOrPut(key) { androidx.compose.runtime.mutableStateListOf() }
    drafts += state.localCodexTasks.images.capture("浅色.png", mock(0xF5F5F4, 0xE7E5E4, 0xD6D3D1))
    drafts += state.localCodexTasks.images.capture("深色.png", mock(0x1C1917, 0x292524, 0x44403C))
}

/**
 * 本机首页选 Codex（P1d）：在 [localHome] 上把运行器换成 Codex、摆一句提示词。[review] 为假时只把草稿记成创建中（没有 job，不连运行器）；
 * 为真时把上次创建记成待核对（只改内存里的状态，不写创建记录）。
 */
private fun localHomeCodex(state: AppState, review: Boolean) {
    localHome(state)
    localHomePrompt(state)
    if (review) state.localCodexTasks.forceState("recoveryThreadId", "019a-shot-unconfirmed") else state.localHomeDraft.busy = true
}

/** 首页的运行器换成 Codex（工作台选中的那个）、输入卡里摆 [SHOT_HOME_PROMPT]。 */
private fun localHomePrompt(state: AppState) {
    val d = state.localHomeDraft
    d.runtimeId = state.localWorkspace.installations.first { it.engine == "codex" }.id
    d.text = androidx.compose.ui.text.input.TextFieldValue(SHOT_HOME_PROMPT)
}

/** thread/read 的 result.thread：一轮做完（查代码、改两个文件、回复）；[approval] 时多一轮进行中、命令在等审批（字段同 CodexTaskController.decodeMessage）。 */
private fun shotCodexThread(id: String, cwd: String, approval: Boolean): JSONObject {
    fun turn(turnId: String, status: String, vararg items: JSONObject) = JSONObject().put("id", turnId).put("status", status)
        .put("items", JSONArray().apply { items.forEach { put(it) } })
    fun user(itemId: String, text: String) = JSONObject().put("type", "userMessage").put("id", itemId)
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
    fun command(itemId: String, command: String, status: String) = JSONObject().put("type", "commandExecution").put("id", itemId)
        .put("command", command).put("cwd", cwd).put("status", status)
    fun change(path: String, diff: String) = JSONObject().put("path", path).put("diff", diff)
    val turns = JSONArray().put(turn("turn-1", "completed",
        user("item-1", "给解析器加两组基准测试：大文件和深层嵌套。"),
        command("item-2", "rg -n \"pub fn parse\" src", "completed").put("exitCode", 0)
            .put("aggregatedOutput", "src/parser.rs:42:pub fn parse(input: &str) -> Result<Ast, ParseError> {"),
        JSONObject().put("type", "fileChange").put("id", "item-3").put("status", "completed").put("changes", JSONArray()
            .put(change("benches/parser.rs", "+use criterion::{criterion_group, criterion_main, Criterion};\n+criterion_group!(benches, large_file, deep_nesting);"))
            .put(change("Cargo.toml", "+[dev-dependencies]\n+criterion = \"0.5\"\n+\n+[[bench]]\n+name = \"parser\"\n+harness = false"))),
        JSONObject().put("type", "agentMessage").put("id", "item-4").put("text", "加好了两组基准测试，都在 `benches/parser.rs`：\n\n" +
            "- **large_file**：解析 2 MB 的生成输入\n- **deep_nesting**：括号嵌套 512 层\n\n" +
            "`Cargo.toml` 里加了 `criterion` 和 `[[bench]]` 段，运行 `cargo bench --bench parser` 看结果。")))
    if (approval) turns.put(turn("turn-2", "inProgress",
        user("item-5", "跑一下，看看深层嵌套要多久。"),
        command("item-6", "cargo bench --bench parser", "inProgress")))
    return JSONObject().put("id", id).put("model", "gpt-5-codex").put("modelProvider", "openai").put("cwd", cwd).put("turns", turns)
}

/**
 * 截图用的假 Codex app-server：只答 thread/read、thread/goal/get（[goal] 为空就回空目标）、model/list（两个模型、一页）、
 * thread/start（首页直接建：回 [thread]，cwd 和模型照请求填，请求里没带模型和供应商就回运行器的默认，同真运行器）
 * 和 thread/resume（点「连接任务」：回 [thread]，模型和供应商同上），别的请求回空 result；
 * 收到的方法记进 [shotRequests]。不起进程、不外连。
 * 块队列代替管道流（写端线程不必一直活着）；[request] 不为空时跟在 thread/read 的回包后面（reader 按顺序处理，控制器在 EDT 空出来后收到）。
 */
private class ShotCodexTransport(private val thread: JSONObject, private val goal: JSONObject?, private val request: JSONObject?) : CodexTransport {
    private val chunks = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private val end = ByteArray(0)
    override val output: java.io.InputStream = object : java.io.InputStream() {
        private var current = ByteArray(0)
        private var at = 0
        private fun fill(): Boolean {
            while (at >= current.size) {
                val next = chunks.take()
                if (next === end) { chunks.put(end); return false }
                current = next; at = 0
            }
            return true
        }
        override fun read(): Int = if (fill()) current[at++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, current.size - at)
            System.arraycopy(current, at, b, off, n); at += n
            return n
        }
    }
    private fun send(message: JSONObject) = chunks.put((message.toString() + "\n").toByteArray())
    override suspend fun write(text: String): Boolean {
        val message = JSONObject(text.trim())
        val method = message.optString("method")
        if (!message.has("id") || method.isEmpty()) return true
        shotRequests += method
        val result = when (method) {
            "thread/read" -> JSONObject().put("thread", thread)
            // 服务器默认配置建（不带独立配置）时请求里只有 cwd：模型和供应商回运行器的默认
            "thread/start" -> message.getJSONObject("params").let { p ->
                JSONObject().put("thread", JSONObject(thread.toString()).put("cwd", p.getString("cwd")))
                    .put("model", p.optString("model", "gpt-5-codex")).put("modelProvider", p.optString("modelProvider", "openai"))
            }
            // 任务没记过模型时请求里只有 threadId：同上回运行器的默认
            "thread/resume" -> message.getJSONObject("params").let { p ->
                JSONObject().put("thread", thread)
                    .put("model", p.optString("model", "gpt-5-codex")).put("modelProvider", p.optString("modelProvider", "openai"))
            }
            "thread/goal/get" -> JSONObject().put("goal", goal ?: JSONObject.NULL)
            // 字段同 CodexTaskController.refreshModels 读的：displayName 就写 id（真运行器多半也是）
            "model/list" -> JSONObject().put("nextCursor", JSONObject.NULL).put("data", JSONArray()
                .put(shotCodexModel("gpt-5-codex", "low", "medium", "high"))
                .put(shotCodexModel("gpt-5", "minimal", "low", "medium", "high")))
            else -> JSONObject()
        }
        send(JSONObject().put("id", message.get("id")).put("result", result))
        if (method == "thread/read" && request != null) send(request)
        return true
    }
    override fun close() { chunks.put(end) }
}

private fun shotCodexModel(id: String, vararg efforts: String) = JSONObject().put("model", id).put("displayName", id)
    .put("supportedReasoningEfforts", JSONArray().apply { efforts.forEach { put(JSONObject().put("reasoningEffort", it)) } })
    .put("defaultReasoningEffort", "medium")

/** [CodeScene.taps] 里的这一项不点控件，在拿着焦点的输入框里按回车（[pressEnter]）。 */
private const val ENTER = "⏎"

/** 合成一次回车按下 + 抬起（带上 AWT 原生事件，同 CodePopupFocusTest 的 Esc）；按下没人处理就失败（焦点不在输入框）。 */
private fun pressEnter(scene: ImageComposeScene) {
    val src = java.awt.Canvas()
    fun key(id: Int, type: KeyEventType) = KeyEvent(Key.Enter, type, 0, false, false, false, false,
        java.awt.event.KeyEvent(src, id, System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.CHAR_UNDEFINED))
    check(scene.sendKeyEvent(key(java.awt.event.KeyEvent.KEY_PRESSED, KeyEventType.KeyDown))) { "回车没人处理：焦点不在输入框" }
    scene.sendKeyEvent(key(java.awt.event.KeyEvent.KEY_RELEASED, KeyEventType.KeyUp))
}

/** 按文字找控件，点它中心（移入 → 按下 → 松开，和真鼠标一样先有悬停；「~」前缀只移入）。找不到就失败，不拿没开浮层的图充数。
 *  画面里没有这段文字时，再按图标的 contentDescription 找（纯图标按钮，如排队项的 ⋯）；「@」前缀只按 contentDescription 找
 *  （同名的文字在别处时，如侧栏那行「更多」和会话头的 ⋮）。 */
private fun tapLowest(scene: ImageComposeScene, spec: String) {
    val hoverOnly = spec.startsWith("~")
    val iconOnly = spec.startsWith("@")
    val label = spec.removePrefix("~").removePrefix("@")
    val hits = mutableListOf<SemanticsNode>()
    val icons = mutableListOf<SemanticsNode>()
    fun walk(node: SemanticsNode) {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        if (text == label) hits += node
        if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true) icons += node
        for (child in node.children) walk(child)
    }
    for (owner in scene.semanticsOwners) walk(owner.unmergedRootSemanticsNode)
    val target = (if (iconOnly) icons else hits.ifEmpty { icons }).maxByOrNull { it.boundsInWindow.top } ?: fail("画面里没有「$label」")
    val at = target.boundsInWindow.center
    scene.sendPointerEvent(PointerEventType.Move, at)
    if (hoverOnly) return
    scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
    scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
}

/** 未合并语义树逐层列出：层号、左上角和宽高（dp，= 参考端 CSS px）、角色、文字。浮层 / tooltip 是单独的层。 */
private fun semanticsDump(scene: ImageComposeScene, density: Float): List<String> {
    val lines = mutableListOf<String>()
    fun walk(layer: Int, depth: Int, node: SemanticsNode) {
        val b = node.boundsInWindow
        val role = node.config.getOrNull(SemanticsProperties.Role)?.toString().orEmpty()
        val text = (node.config.getOrNull(SemanticsProperties.EditableText)?.text
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }).orEmpty().replace("\n", "⏎")
        lines += String.format(Locale.ROOT, "L%d %s x=%.1f y=%.1f w=%.1f h=%.1f %s %s", layer, "  ".repeat(depth),
            b.left / density, b.top / density, b.width / density, b.height / density, role, text).trimEnd()
        for (child in node.children) walk(layer, depth + 1, child)
    }
    scene.semanticsOwners.forEachIndexed { i, owner -> walk(i, 0, owner.unmergedRootSemanticsNode) }
    return lines
}

/** 统计卡的假数据（M4n）：从 2025-04 到「今天」（固定时钟 2025-10-09）每隔几天用一次，两个模型，量有大有小，热力图三档都出得来。 */
private fun shotUsageDays(): List<app.yxi.agent.Day> {
    val today = java.time.LocalDate.of(2025, 10, 9)
    return (0L until 182L).mapNotNull { back ->
        val date = today.minusDays(back)
        val seed = (date.dayOfYear * 37 + date.dayOfMonth * 11) % 23
        if (seed < 9) null else {
            val tokens = seed * 180_000L + (if (date.dayOfWeek.value <= 5) 900_000L else 0L)
            val cost = tokens / 1e6 * 4.2
            app.yxi.agent.Day(date.toString(), tokens, cost, listOf(
                app.yxi.agent.ModelCost("opus-4-1", tokens * 2 / 3, cost * 0.8), app.yxi.agent.ModelCost("sonnet-4-5", tokens / 3, cost * 0.2)))
        }
    }.reversed()
}
