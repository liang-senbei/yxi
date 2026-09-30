@file:OptIn(ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION")   // SecurityManager：同 ClassicThemeShotsTest，只拿来拦 exec / 外连

package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
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
import app.yxi.agent.Groups
import app.yxi.agent.Pending
import app.yxi.agent.Session
import app.yxi.agent.SessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * M2 第 4 步（4f-6）：「Code」风格的离屏截图，给骨架量值对规格表（PRD §7、§8 M2）。和 M0 同规格：2560×1380、density 1.25
 * （= 125% 下最大化 2048×1104），每张另出一份语义节点清单（坐标换成 dp，直接对规格表的数）。夹具隔离、拦 exec / 外连、
 * 画到稳定都和 [ClassicThemeShotsTest] 同一套，产物写到 <fixture>/out-code，不进仓库。Code 还在做，先不设基线：
 * 这里只保证画得出、画得稳、不越界。只在 YXI_THEME_SHOT_MODE=code 时跑（经典截图此时让位）；不在 dev/workbench-test-classes.txt 里。
 */
@EnabledOnOs(OS.WINDOWS)
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
                    for (scene in codeScenes()) for (theme in listOf("light", "dark")) {
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
        val state = AppState().apply { startupRestored = true }   // 跳过 HostEffects 的开机恢复 / 自动重连
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
                    tapLowest(image, label)
                    if (label.startsWith("~")) delay(900)   // tooltip 的 500ms 延时按真实时间走（Swing 调度器）
                    png = clock.settle()
                }
                return png to semanticsDump(image, 1.25f)
            } finally { image.close() }
        } finally {
            (state.conns + listOfNotNull(state.conn)).distinct().forEach { it.close() }   // 9c 起一张图会有几台主机
            state.codexWorkspace.close(); state.remoteOpenCodeTasks.close(); state.remoteAcpTasks.close(); state.closeLocalFeatures()
        }
    }
}

/**
 * Code 截图场景：[taps] 依次点文字是它的控件（同名取最靠下的一个：chips 在浮层下面；带「~」前缀的只悬停、等 tooltip）；[allowed] 同经典夹具；
 * [content] 不为空就画它、不画 App（同经典夹具的 approval-card）。
 */
private class CodeScene(val name: String, val taps: List<String> = emptyList(), val allowed: Set<String> = emptySet(),
    val content: (@Composable () -> Unit)? = null, val arrange: (AppState) -> Unit = {})

/** 规格 §3.8 / §3.2 / §3.1 / §3.3 / §3.4 要量的：新会话页空态、浮层和 tooltip，有会话时的 SSH 对话、审批卡、排队。 */
private fun codeScenes() = listOf(
    CodeScene("home") { codeHome(it) },
    CodeScene("home-permission", listOf("请求批准")) { codeHome(it) },
    CodeScene("home-runner", listOf("Claude Code")) { codeHome(it) },
    CodeScene("home-env", listOf(HOST.label)) { codeHome(it) },
    CodeScene("home-folder", listOf("demo-app")) { codeHome(it) },
    CodeScene("home-folder-hint", listOf("~demo-app")) { codeHome(it) },
    CodeScene("home-permission-again", listOf("请求批准", "请求批准")) { codeHome(it) },
    CodeScene("ssh-chat") { remote(it, tab = 0) },
    CodeScene("approval-card", content = { ApprovalDockShot() }),
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
)

/**
 * 审批卡：pending 只来自 SSH 轮询，离屏够不着（同经典夹具）；照 CodeShell + ChatPane 的停靠区摆同一个 [ApprovalCard]：
 * 左侧栏 288，主区底部是两侧 40、最宽 768 的居中列，卡片下面留输入区的 78.4。数据同经典的 approval-card。
 */
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
/** 按文字找控件，点它中心（移入 → 按下 → 松开，和真鼠标一样先有悬停；「~」前缀只移入）。找不到就失败，不拿没开浮层的图充数。
 *  画面里没有这段文字时，再按图标的 contentDescription 找（纯图标按钮，如排队项的 ⋯）。 */
private fun tapLowest(scene: ImageComposeScene, spec: String) {
    val hoverOnly = spec.startsWith("~")
    val label = spec.removePrefix("~")
    val hits = mutableListOf<SemanticsNode>()
    val icons = mutableListOf<SemanticsNode>()
    fun walk(node: SemanticsNode) {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        if (text == label) hits += node
        if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true) icons += node
        for (child in node.children) walk(child)
    }
    for (owner in scene.semanticsOwners) walk(owner.unmergedRootSemanticsNode)
    val target = hits.ifEmpty { icons }.maxByOrNull { it.boundsInWindow.top } ?: fail("画面里没有「$label」")
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
