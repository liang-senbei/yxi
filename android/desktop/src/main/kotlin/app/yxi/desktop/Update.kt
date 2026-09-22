package app.yxi.desktop

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandler
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * 自动更新。打包走 Velopack（Claude Desktop 用的 Squirrel.Windows 的继任者，同一套 Setup.exe / RELEASES 路线）。
 * Velopack 没有 Java SDK（官网：Java = planned），但它的协议就是「一个 releases.win.json + 一个 nupkg + `Update.exe apply`」，
 * 所以这里用 JDK 自带的 HttpClient 自己查、自己下、自己校验 SHA256，最后把「换文件 + 重启」交给 Update.exe
 * （`apply --waitPid 我们` = 等我们退干净再换掉 current\，装完默认拉起新版；Update.exe 自己没有 check / download 子命令）。
 * 装完的布局：%LOCALAPPDATA%\Yxi\Update.exe、current\Yxi.exe、current\runtime\、packages\（下载的 nupkg 放这，Velopack 自己会清）。
 */
object Updater {
    sealed interface State
    data object Idle : State
    data class Available(val version: String) : State
    data class Downloading(val pct: Int) : State
    data class Ready(val version: String) : State
    data class Error(val msg: String) : State

    var state: State by mutableStateOf(Idle)
        private set

    /** 当前版本：jpackage 启动器按 app/Yxi.cfg 塞的 -Djpackage.app-version（= build.gradle.kts 的 packageVersion）；`java -jar` 跑没有这个。 */
    val version: String = System.getProperty("jpackage.app-version") ?: "dev"
    const val FEED = "https://yxi.keuury.com/desktop/"

    // java.home = …\Yxi\current\runtime；往上找到 Update.exe 才算 Velopack 装的。找不到（java -jar / gradle run / MSI）就不查更新。
    private val updateExe: File? = generateSequence(File(System.getProperty("java.home"))) { it.parentFile }
        .map { File(it, "Update.exe") }.firstOrNull { it.isFile }
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    private var pending: JSONObject? = null   // check 挑出来的那条 Full 资产
    private var ready: File? = null           // 下好并校验过的 nupkg

    /**
     * main 里第一行调用。① Velopack 装 / 卸 / 升级时会带 --veloapp-install|updated|obsolete|uninstall 拉起我们，
     * 要求 15–30 秒内退出，不退就被杀（docs.velopack.io/integrating/hooks）——JVM 起来直接退，别开窗。
     * ② 启动 10 秒后查一次，之后每 6 小时（Claude Desktop 同款：后台静默下好，等用户点重启）。
     */
    fun boot(args: Array<String>) {
        if (args.any { it.startsWith("--veloapp-") }) exitProcess(0)
        if (updateExe == null) return
        thread(isDaemon = true, name = "yxi-update") { Thread.sleep(10_000); while (true) { check(); Thread.sleep(6 * 3600_000L) } }
    }

    /** 读 releases.win.json，Type=Full 里挑最高版本，比当前新就开始下。查失败只打日志，不打扰用户（可能只是没网）。 */
    @Synchronized fun check() {
        if (state is Downloading || state is Available) return
        runCatching {
            val latest = pickUpdate(fetch(FEED + "releases.win.json", BodyHandlers.ofString()), version, (state as? Ready)?.version) ?: return
            pending = latest
            state = Available(latest.getString("Version"))
            download()
        }.onFailure { println("update check: $it") }
    }

    @Synchronized fun checkManually(): String {
        if (state is Downloading) return "正在下载更新"
        val raw = fetch(FEED + "releases.win.json", BodyHandlers.ofString())
        val assets = JSONObject(raw).getJSONArray("Assets")
        val latest = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.optString("Type") == "Full" && it.optString("PackageId") == "Yxi" }
            .maxWithOrNull { a, b -> cmpVer(a.getString("Version"), b.getString("Version")) }
            ?: error("更新清单没有可用的 Windows 安装包")
        val latestVersion = latest.getString("Version")
        (state as? Ready)?.let {
            if (cmpVer(latestVersion, it.version) <= 0) return "更新 ${it.version} 已就绪，可安装并重启"
        }
        if (version != "dev" && cmpVer(latestVersion, version) <= 0) return "已是最新版本 · $version"
        if (updateExe == null) return "官网最新版本 $latestVersion · 请下载安装包更新"
        pending = latest
        state = Available(latestVersion)
        download()
        return "发现 $latestVersion，正在下载"
    }

    /** 下 pending 那个 nupkg 到 packages\，SHA256 对得上才算 Ready；上次下好没重启的直接复用。 */
    @Synchronized fun download() {
        if (updateExe == null || state is Downloading || state is Ready) return
        val a = pending ?: return
        state = Downloading(0)
        thread(isDaemon = true, name = "yxi-download") {
            runCatching {
                val dir = File(updateExe!!.parentFile, "packages").apply { mkdirs() }
                val out = File(dir, a.getString("FileName"))
                val sha = a.getString("SHA256")
                if (!(out.isFile && sha256(out).equals(sha, ignoreCase = true))) {
                    val part = File(dir, out.name + ".partial")
                    val size = a.getLong("Size")
                    state = Downloading(0)
                    fetch(FEED + a.getString("FileName"), BodyHandlers.ofInputStream()).use { inp ->
                        part.outputStream().use { o ->
                            val buf = ByteArray(1 shl 16); var done = 0L
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                o.write(buf, 0, n); done += n
                                val pct = (done * 100 / size).toInt()
                                if (pct != (state as? Downloading)?.pct) state = Downloading(pct)
                            }
                        }
                    }
                    require(sha256(part).equals(sha, ignoreCase = true)) { "下载的文件校验不符" }
                    Files.move(part.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                ready = out
                state = Ready(a.getString("Version"))
            }.onFailure { state = Error(it.message ?: it.toString()) }
        }
    }

    /** 拉起 Update.exe 后自己退出：它等我们（--waitPid）退干净再换 current\，装完默认重启新版（VELOPACK_RESTART=true）。 */
    @Synchronized fun restartToApply() {
        if (state !is Ready) return // An old confirmation dialog must not install a superseded cached package.
        val pkg = ready ?: return
        ProcessBuilder(updateExe!!.path, "--silent", "apply", "--package", pkg.path, "--waitPid", ProcessHandle.current().pid().toString())
            .directory(updateExe.parentFile).start()
        exitProcess(0)
    }

    /** 纯逻辑，拆出来好测：feed 里 Type=Full 的最高版本，比 current 新才返回。 */
    fun pickUpdate(feedJson: String, current: String, readyVersion: String? = null): JSONObject? {
        val assets = JSONObject(feedJson).getJSONArray("Assets")
        val latest = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.getString("Type") == "Full" && it.optString("PackageId") == "Yxi" }
            .maxWithOrNull { p, q -> cmpVer(p.getString("Version"), q.getString("Version")) } ?: return null
        return latest.takeIf { cmpVer(it.getString("Version"), current) > 0 &&
            (readyVersion == null || cmpVer(it.getString("Version"), readyVersion) > 0) }
    }

    // 按数字段比（1.0.10 > 1.0.9）。JDK 的 Runtime.Version 不认 1.1.0 这种末尾带 0 的号，所以自己写。-beta 之类的后缀直接忽略。
    fun cmpVer(a: String, b: String): Int {
        val x = a.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = (x.getOrNull(i) ?: 0) - (y.getOrNull(i) ?: 0); if (d != 0) return d }
        return 0
    }

    private fun <T> fetch(url: String, handler: BodyHandler<T>): T {
        val r = http.send(HttpRequest.newBuilder(URI(url)).build(), handler)
        require(r.statusCode() == 200) { "HTTP ${r.statusCode()} $url" }
        return r.body()
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp -> val buf = ByteArray(1 shl 16); while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return HexFormat.of().formatHex(md.digest())
    }
}

/** 最简横幅（放侧栏顶部之类的地方；Idle 时什么都不画）。文案照 Codex。 */
@Composable
fun UpdateBanner() {
    val s = Updater.state
    var confirm by remember { mutableStateOf(false) }
    var notesOpen by remember { mutableStateOf(false) }
    if (notesOpen) ReleaseNotesDialog(when (s) {
        is Updater.Ready -> s.version
        is Updater.Available -> s.version
        else -> null
    }) { notesOpen = false }
    if (s is Updater.Idle) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (s) {
                is Updater.Available -> "有新的 Yxi 更新可用：${s.version}"
                is Updater.Downloading -> "正在下载更新，${s.pct}%"
                is Updater.Ready -> "Yxi ${s.version} 已下载好"
                is Updater.Error -> "更新失败：${s.msg}"
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
        )
        TextButton({ notesOpen = true }) { Text("更新内容") }
        when (s) {
            is Updater.Ready -> TextButton(onClick = { confirm = true }) { Text("立即重启") }
            is Updater.Error -> TextButton(onClick = Updater::download) { Text("重试") }
            else -> Spacer(Modifier)
        }
    }
    if (confirm) WorkbenchDialog(
        onDismissRequest = { confirm = false },
        title = { Text("现在更新 Yxi？") },
        text = { Text("Yxi 将退出以安装更新，这会断开当前连接；服务器上的会话不受影响") },
        confirmButton = { TextButton(onClick = Updater::restartToApply) { Text("更新") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
    )
}
