@file:OptIn(ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION")   // SecurityManager：JDK 21 仍可用（-Djava.security.manager=allow），这里只拿来拦 exec / 外连

package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import app.yxi.agent.Pending
import app.yxi.agent.Session
import app.yxi.agent.SessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * M0：「经典」风格的离屏截图夹具（PRD §4.3 ①）。6 个场景 × 浅/深，出 2560×1380、density 1.25 的 PNG
 * （= 125% 缩放下最大化 2048×1104）。录制模式把缺的基线写进去；比对模式逐像素比，不一致就失败并出 diff 图。
 *
 * 主题只通过隔离 profile 里的偏好 theme=light/dark + 无参 [YxiTheme] 生效，不碰任何风格 API，
 * 所以同一份夹具在引入「Code 风格」前后原样能跑：M1 之后跑比对 = 经典风格零回归验收。
 *
 * YXI_THEME_SHOT_FIXTURE 指向仓库外的绝对目录，build.gradle.kts 把 user.home / HOME / USERPROFILE / APPDATA /
 * LOCALAPPDATA 指到 <fixture>/profile（与 YXI_SUBSCRIPTION_UI_FIXTURE 同一套）；每次开跑清空重建。
 * 不在 dev/workbench-test-classes.txt 里（PRD §10-4），不设 env 就跳过。
 * YXI_THEME_SHOT_MODE=switch / code 时让位给风格切换的状态用例 / Code 截图：共用 profile 和 Store 单例，不能在同一个 JVM 里跑。
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named = "YXI_THEME_SHOT_FIXTURE", matches = ".+")
@DisabledIfEnvironmentVariable(named = "YXI_THEME_SHOT_MODE", matches = "switch|code")
class ClassicThemeShotsTest {
    @Test
    fun `classic light and dark shots match baselines`() {
        val fixture = File(System.getenv("YXI_THEME_SHOT_FIXTURE")).also { require(it.isAbsolute) }.canonicalFile
        val baselines = File(requireNotNull(System.getenv("YXI_THEME_SHOT_BASELINE")) { "YXI_THEME_SHOT_BASELINE 未设置" })
            .also { require(it.isAbsolute) }.canonicalFile
        val mode = System.getenv("YXI_THEME_SHOT_MODE").orEmpty().ifBlank { "compare" }
        require(mode == "record" || mode == "compare") { "YXI_THEME_SHOT_MODE 只能是 record / compare：$mode" }
        val profile = prepareShotProfile(fixture, baselines)
        val previous = System.getSecurityManager()
        val guard = NoExec()
        // 从第一次碰 Store 起就拦：任何拉起进程 / 连外网都是夹具失守
        System.setSecurityManager(guard)
        val report = mutableListOf<String>()
        val failures = mutableListOf<String>()
        try {
            assertTrue(Store.dir.canonicalFile.startsWith(profile), "Store.dir 没落在夹具 profile 里：${Store.dir}")
            assertEquals("", Store.warning, "隔离 profile 里的 Store 不该有警告")
            stamp(baselines, fixture, mode == "record")
            val out = fixture.resolve("out").also { it.deleteRecursively(); it.mkdirs() }
            val projects = listOf("demo-app", "docs-site").map { fixture.resolve("projects/$it").also(File::mkdirs).path }
            System.setProperty("skiko.renderApi", "SOFTWARE")
            ImageIO.setUseCache(false)
            runBlocking(Dispatchers.Swing) {
                // TitleBar 是 FrameWindowScope 扩展，WindowDraggableArea 要 window：造一个不 pack、不 show 的空壳
                val hidden = ComposeWindow()
                check(!hidden.isDisplayable)
                val scope = object : FrameWindowScope { override val window: ComposeWindow get() = hidden }
                try {
                    assertEquals(emptyList(), guard.drain(), "准备阶段就有被拦的 exec / 外连")
                    for (scene in scenes()) for (theme in listOf("light", "dark")) {
                        val name = "$theme/${scene.name}.png"
                        val started = System.nanoTime()
                        val verdict = try {
                            val png = shoot(scene, theme, scope, projects)
                            val actual = out.resolve(name).also { it.parentFile.mkdirs(); it.writeBytes(png) }
                            val baseline = baselines.resolve(name)
                            when {
                                !baseline.exists() && mode == "record" -> { baseline.parentFile.mkdirs(); baseline.writeBytes(png); "recorded" }
                                !baseline.exists() -> { failures += "$name：基线不存在（先用 YXI_THEME_SHOT_MODE=record 录）"; "missing" }
                                else -> {
                                    val diff = out.resolve(name.removeSuffix(".png") + ".diff.png")
                                    val n = diffPixels(baseline.readBytes(), png, diff)
                                    if (n != 0) failures += "$name：" + (if (n < 0) "尺寸不同" else "$n 个像素不同") + "，实际 $actual，差异 $diff"
                                    "diff=" + (if (n < 0) "size" else "$n")
                                }
                            }
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
        val text = (listOf("mode=$mode", "fixture=$fixture", "baseline=$baselines") + report + failures.map { "FAIL $it" }).joinToString("\n")
        fixture.resolve("out/report.txt").writeText(text + "\n")
        println(text)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** 基线目录记下录制时的夹具路径：常规页、本机项目都会显示路径，换了目录就不是同一张图。 */
    private fun stamp(baselines: File, fixture: File, record: Boolean) {
        val file = baselines.resolve("fixture.txt")
        val text = "fixture=${fixture.path}\nos=${System.getProperty("os.name")}\n"
        if (!file.exists() && record) { baselines.mkdirs(); file.writeText(text); return }
        assertTrue(file.exists(), "$baselines 里没有 fixture.txt：先用 YXI_THEME_SHOT_MODE=record 录基线")
        assertEquals(file.readText(), text, "基线是用另一个夹具目录录的：YXI_THEME_SHOT_FIXTURE 要和录制时一致")
    }

    /** 一张图：先写偏好（主题 + 场景要的），再造 AppState、摆场景（不连、不扫、不 select），离屏画到稳定。 */
    private suspend fun shoot(scene: Scene, theme: String, scope: FrameWindowScope, projects: List<String>): ByteArray {
        Store.setPref("theme", theme)
        Store.setPref("hostScope", if (scene.local) LOCAL_HOST_SCOPE else "")
        Store.setPref("localProjects", JSONArray(projects).toString())
        val state = AppState().apply { startupRestored = true }   // 跳过 HostEffects 的开机恢复 / 自动重连
        try {
            scene.arrange(state)
            check(!NativeOverlays.active) { "上一张遗留了 NativeOverlay 租约，终端页会整块不画" }
            val win = WindowState(placement = WindowPlacement.Maximized, size = DpSize(2048.dp, 1104.dp))
            val body = scene.content
            return capture { ShotFrame(state, scope, win) { if (body != null) body() else App(state) } }
        } finally {
            state.conn?.close()
            state.codexWorkspace.close(); state.remoteOpenCodeTasks.close(); state.remoteAcpTasks.close(); state.closeLocalFeatures()
        }
    }
}

/**
 * 核对 build.gradle.kts 已把 home 指到 <fixture>/profile，再清空重建。目录不像本夹具建的就不删。
 * [outside]：同样不许落进 git 工作树的其他目录（基线）。
 */
internal fun prepareShotProfile(fixture: File, vararg outside: File): File {
    val profile = fixture.resolve("profile")
    assertEquals(profile, File(System.getProperty("user.home")).canonicalFile, "user.home 没指到夹具 profile（env 要在 gradle 命令前设置）")
    for (key in listOf("HOME", "USERPROFILE")) assertEquals(profile, File(System.getenv(key).orEmpty()).canonicalFile, "$key 没隔离")
    assertEquals(profile.resolve("Roaming"), File(System.getenv("APPDATA").orEmpty()).canonicalFile, "APPDATA 没隔离")
    assertEquals(profile.resolve("Local"), File(System.getenv("LOCALAPPDATA").orEmpty()).canonicalFile, "LOCALAPPDATA 没隔离")
    // 截图不进仓库：夹具和基线都不许落在任何 git 工作树里
    val repos = generateSequence(File("").canonicalFile) { it.parentFile }.filter { it.resolve(".git").exists() }.toList()
    for (dir in listOf(fixture, *outside)) for (repo in repos) assertFalse(dir.startsWith(repo), "$dir 在仓库 $repo 里，截图不进仓库")
    val marker = fixture.resolve(".yxi-theme-shot-fixture")
    if (fixture.isDirectory && !marker.exists()) assertTrue(fixture.list().isNullOrEmpty(), "$fixture 非空且不是本夹具建的，拒绝清空")
    fixture.mkdirs(); marker.writeText("主题截图夹具目录：每次运行清空 profile/ 和本次用例的 out 目录\n")
    check(profile.deleteRecursively()) { "清不掉 $profile（有文件被占用？）" }
    listOf(profile.resolve("Roaming"), profile.resolve("Local")).forEach { check(it.mkdirs()) { "建不了 $it" } }
    return profile
}

/** 场景：[arrange] 只喂合成状态；[allowed] 是这一张允许被拦下的 exec / 外连（拦下即不执行）。 */
private class Scene(val name: String, val local: Boolean = false, val allowed: Set<String> = emptySet(),
    val content: (@Composable () -> Unit)? = null, val arrange: (AppState) -> Unit = {})

/** PRD §4.3 ① 的 6 个场景。按场景顺序跑：hosts.json 只在第 2 个场景从 0 变 1，前后两次运行完全一致。 */
private fun scenes() = listOf(
    Scene("empty-workbench"),
    Scene("ssh-chat") { remote(it, tab = 0) },
    Scene("local-workbench", local = true) { localWorkbench(it) },
    Scene("approval-card", content = { ApprovalShot() }),
    // 常规页「开机自启」在组合里同步跑 reg query：拦下来按未开启画，也就不依赖这台机器的注册表
    Scene("settings-general", allowed = setOf("exec:reg")) { it.showSettings = true; it.settingsSection = "常规" },
    Scene("terminal") { remote(it, tab = 1) },
)

internal val HOST = Host("shot-host", "示例服务器", "build-01.example.invalid", username = "dev")
internal val SESSION = Session("cc-demo-app", 1, false, "/srv/demo-app", 0L, SessionState.Idle, "", 0.0,
    cmd = "claude", runtimeId = "shot-runtime-1")
/** 远端场景：只写 hosts.json、造 Conn 但不 start()（不连）；会话列表和对话缓存直接喂合成数据。 */
internal fun remote(state: AppState, tab: Int) {
    Store.save(listOf(HOST))
    val conn = Conn(HOST, NoHostKeys).apply { sessions = listOf(SESSION); forceState("groupsLoaded", true) }
    state.conns.add(conn); state.conn = conn; state.session = SESSION; state.tab = tab
    // ChatPane 先用进程内对话缓存画（runtimeId 非空才用）；没连上时它只标一句「连接断了 —— 显示缓存」，不去读远端
    val entry = DesktopTranscriptMemory.Entry("/srv/demo-app/.claude/projects/shot/shot-runtime-1.jsonl", 0L)
    val lines = transcriptLines()
    val (lease, _) = entry.claim()
    checkNotNull(entry.append(lease, lines, lines.sumOf { it.toByteArray().size + 1L }))
    entry.release(lease)
    DesktopTranscriptMemory.put(taskNavigationKey(HOST, SESSION), entry)
}

/** 本机场景：scanned=true 先于组合，LocalWorkspacePane 的 LaunchedEffect 就不会 refresh()（那会扫运行器、拉 codex）。 */
private fun localWorkbench(state: AppState) {
    val codex = LocalRuntimeInstallation("codex", "PATH", listOf("C:\\fixture\\bin\\codex.exe"), "C:\\fixture\\codex-home", "codex-cli 0.0.0-fixture")
    state.localWorkspace.run {
        forceState("installations", listOf(codex)); forceState("selectedRuntime", codex)
        forceState("account", NativeAccountStatus("示例账号（已登录）", "OpenAI", 0L))
        forceState("threads", listOf(
            NativeHistoryThread("shot-thread-1", "修复解析器的空输入崩溃", "D:\\fixture\\demo-app", "openai", "cli", 0L, "idle"),
            NativeHistoryThread("shot-thread-2", "补充 README 的安装说明", "D:\\fixture\\docs-site", "openai", "cli", 0L, "idle"),
            NativeHistoryThread("shot-thread-3", "升级依赖并跑通测试", "D:\\fixture\\demo-app", "openai", "vscode", 0L, "idle")))
        forceState("scanned", true)
    }
}

/** 审批卡：ChatPane 里的 pending 只来自 SSH 轮询，离屏够不着；按对话区宽度单独画同一个 [ApprovalCard]。 */
@Composable
private fun ApprovalShot() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 820.dp).fillMaxWidth()) {
            ApprovalCard(Pending("是否允许 Claude 运行这条命令？", listOf(Pending.Option(1, "Yes"),
                Pending.Option(2, "Yes, and don’t ask again for git:*"), Pending.Option(3, "No")), false, "shot-approval"),
                Approval("Bash", "git push origin feature/parser-fix"), busy = false, onKey = {}, onSubmit = {})
        }
    }
}

/** 合成的 Claude 会话 jsonl：提问 → Bash 工具调用 → 结果 → 带列表和代码块的回复。没有时间戳。 */
private fun transcriptLines(): List<String> {
    fun user(uuid: String, content: Any) = JSONObject().put("type", "user").put("uuid", uuid)
        .put("message", JSONObject().put("role", "user").put("content", content)).toString()
    fun assistant(uuid: String, vararg blocks: JSONObject) = JSONObject().put("type", "assistant").put("uuid", uuid)
        .put("message", JSONObject().put("role", "assistant").put("content", JSONArray(blocks.toList()))).toString()
    fun text(s: String) = JSONObject().put("type", "text").put("text", s)
    val bash = JSONObject().put("type", "tool_use").put("id", "shot-tool-1").put("name", "Bash")
        .put("input", JSONObject().put("command", "./gradlew test --offline").put("description", "运行单元测试"))
    val result = JSONObject().put("type", "tool_result").put("tool_use_id", "shot-tool-1").put("is_error", false)
        .put("content", "> Task :app:test FAILED\nParserTest > parsesEmptyInput() FAILED\n1 test completed, 1 failed")
    return listOf(user("shot-u1", "帮我看看 demo-app 的单元测试为什么失败"), assistant("shot-a1", text("先跑一遍测试看看失败信息。"), bash),
        user("shot-u2", JSONArray().put(result)), assistant("shot-a2", text(REPLY)))
}
private val REPLY = """
    失败原因是 `Parser.parse("")` 直接读了 `tokens[0]`，空输入时数组越界。建议这样改：

    1. 在 `parse` 开头对空白输入提前返回 `Ast.Empty`；
    2. 给 `parsesEmptyInput()` 补一条只有空格的用例。

    ```kotlin
    fun parse(input: String): Ast {
        if (input.isBlank()) return Ast.Empty
        return Ast.Program(tokenize(input).map(::statement))
    }
    ```

    改完再跑一次 `./gradlew test` 应该全部通过。
""".trimIndent()

/** 与 Main.kt 的窗口内容同构：Zoomed → 无参 YxiTheme → [WindowFrame]（背景 Column → 经典标题栏 + 正文）。 */
@Composable
internal fun ShotFrame(state: AppState, scope: FrameWindowScope, win: WindowState, body: @Composable () -> Unit) {
    val base = LocalWindowInfo.current
    // ImageComposeScene 自报窗口有焦点，输入框光标就会闪；按失焦画（不画光标），帧才稳定
    CompositionLocalProvider(LocalWindowInfo provides remember(base) { Unfocused(base) }) {
        Zoomed {
            YxiTheme {
                with(scope) { WindowFrame(state, win, {}) { body() } }
            }
        }
    }
}

private class Unfocused(base: WindowInfo) : WindowInfo by base {
    override val isWindowFocused: Boolean get() = false
}
/**
 * 离屏画到稳定：固定时钟（不读系统时间），先按 16ms 步进暖几十帧让动画 / 异步解析落定，
 * 再每步进 1s 编一次 PNG，连续 3 张逐字节相同才收。落不定就失败，不拿半截画面当基线。
 */
private suspend fun capture(content: @Composable () -> Unit): ByteArray {
    val scene = ImageComposeScene(2560, 1380, density = Density(1.25f), coroutineContext = Dispatchers.Swing, content = content)
    try { return SceneClock(scene).settle() } finally { scene.close() }
}

/** 同一个离屏 scene 的时钟：只往前走，可以反复 [settle]（切换风格后接着画同一棵组合树）。 */
internal class SceneClock(private val scene: ImageComposeScene) {
    private var t = 0L
    private fun frame(encode: Boolean): ByteArray? {
        Snapshot.sendApplyNotifications()
        val image = scene.render(t)
        try { return if (encode) image.encodeToData(EncodedImageFormat.PNG)!!.let { data -> data.bytes.also { data.close() } } else null }
        finally { image.close() }
    }
    suspend fun settle(): ByteArray {
        repeat(30) { frame(false); t += 16_000_000L; delay(50) }
        var last: ByteArray? = null
        var same = 0
        repeat(40) {
            val png = frame(true)!!
            same = if (png contentEquals last) same + 1 else 0
            if (same >= 2) return png
            last = png; t += 1_000_000_000L; delay(200)
        }
        fail("画面 40 秒内没稳定（动画 / 光标 / 异步加载没停）")
    }
}

/** 逐像素比；不同的像素在 diff 图里标品红，其余压暗。尺寸不同返回 -1。 */
internal fun diffPixels(expected: ByteArray, actual: ByteArray, diffOut: File): Int {
    if (expected contentEquals actual) return 0
    val a = ImageIO.read(ByteArrayInputStream(expected))
    val b = ImageIO.read(ByteArrayInputStream(actual))
    if (a.width != b.width || a.height != b.height) return -1
    val pa = a.getRGB(0, 0, a.width, a.height, null, 0, a.width)
    val pb = b.getRGB(0, 0, b.width, b.height, null, 0, b.width)
    var n = 0
    val out = IntArray(pb.size) { i -> if (pa[i] != pb[i]) { n++; 0xFFFF00FF.toInt() } else 0xFF000000.toInt() or ((pb[i] shr 2) and 0x3F3F3F) }
    if (n > 0) ImageIO.write(BufferedImage(b.width, b.height, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, b.width, b.height, out, 0, b.width) }, "png", diffOut)
    return n
}

/** 直接写 `by mutableStateOf` 的私有 set：这些状态正常只能经扫描本机 / 连服务器得到，夹具不走那些入口。 */
@Suppress("UNCHECKED_CAST")
internal fun Any.forceState(name: String, value: Any?) {
    val field = javaClass.getDeclaredField("$name\$delegate").apply { isAccessible = true }
    (field.get(this) as MutableState<Any?>).value = value
}

/**
 * 只拦两件事：起子进程（exec）和连非回环地址。其余权限一律放行。被拦的记下来，由场景的 allowed 判定是否越界。
 * pty4j / JNA 走 native 起进程不经过这里，但这些场景都不会碰终端 PTY。
 */
internal class NoExec : SecurityManager() {
    private val denied = java.util.Collections.synchronizedList(mutableListOf<String>())
    fun drain(): List<String> = synchronized(denied) { denied.toList().also { denied.clear() } }
    override fun checkPermission(perm: java.security.Permission) {}
    override fun checkPermission(perm: java.security.Permission, context: Any?) {}
    override fun checkExec(cmd: String) {
        // File.canExecute / Files.isExecutable 也走 checkExec：只探测、不起进程，放行；只拦 ProcessBuilder.start（Runtime.exec 也经它）
        if (Thread.currentThread().stackTrace.none { it.className == "java.lang.ProcessBuilder" && it.methodName == "start" }) return
        denied += "exec:" + File(cmd).name.substringBefore('.').lowercase()
        throw SecurityException("截图夹具不许起子进程：$cmd")
    }
    override fun checkConnect(host: String, port: Int) {
        if (host == "localhost" || host.startsWith("127.") || host == "::1" || host == "[::1]" || host == "0:0:0:0:0:0:0:1") return
        denied += "connect:$host:$port"
        throw SecurityException("截图夹具不许连外部地址：$host:$port")
    }
    override fun checkConnect(host: String, port: Int, context: Any?) = checkConnect(host, port)
}
