package app.yxi.desktop

import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

internal data class LocalRuntimeInstallation(val engine: String, val source: String, val command: List<String>,
    val home: String, val version: String = "", val problem: String = "") {
    val id: String get() = engine + ":" + command.joinToString("|")
    val ready get() = version.isNotBlank() && problem.isBlank()
}

/** Discovery never executes npm/PowerShell shims or installs anything. Only version probes run. */
internal object LocalRuntimeDiscovery {
    val engines = listOf("codex", "claude", "opencode", "gemini", "grok", "hermes")
    fun title(engine: String) = when (engine) { "codex" -> "Codex"; "claude" -> "Claude Code"; "opencode" -> "OpenCode"; "gemini" -> "Gemini"; "grok" -> "Grok Build"; "hermes" -> "Hermes"; else -> engine }

    internal fun candidates(userHome: File = File(System.getProperty("user.home")), env: Map<String, String> = System.getenv(),
        windows: Boolean = System.getProperty("os.name").startsWith("Windows")): List<LocalRuntimeInstallation> {
        val paths = env.entries.firstOrNull { it.key.equals("PATH", true) }?.value.orEmpty()
            .split(if (windows) ';' else ':').filter { it.isNotBlank() }.map { File(it.trim('"')) }.filter { it.isAbsolute }
        val roots = (paths + listOfNotNull(env["APPDATA"]?.let { File(it, "npm") }, File(userHome, ".local/bin"))).distinct()
        val result = mutableListOf<LocalRuntimeInstallation>()
        fun dataHome(engine: String) = when (engine) {
            "codex" -> env["CODEX_HOME"] ?: File(userHome, ".codex").path
            "claude" -> env["CLAUDE_CONFIG_DIR"] ?: File(userHome, ".claude").path
            "gemini" -> File(env["GEMINI_CLI_HOME"]?.takeIf { it.isNotBlank() } ?: userHome.path, ".gemini").path
            "grok" -> File(userHome, ".grok").path
            "hermes" -> env["HERMES_HOME"] ?: if (windows && env["LOCALAPPDATA"] != null) File(env.getValue("LOCALAPPDATA"), "hermes").path else File(userHome, ".hermes").path
            else -> File(env["XDG_DATA_HOME"] ?: File(userHome, ".local/share").path, "opencode").path
        }
        fun add(engine: String, source: String, command: List<File>, problem: String = "") {
            if (command.all { it.isFile }) result += LocalRuntimeInstallation(engine, source,
                command.map { it.canonicalPath }, File(dataHome(engine)).absolutePath, problem = problem)
        }
        for (engine in engines) {
            val nativeDirs = roots + when (engine) {
                "opencode" -> listOf(File(userHome, ".opencode/bin"))
                "grok" -> listOfNotNull(File(userHome, ".grok/bin"), env["GROK_BIN_DIR"]?.let { File(it).takeIf { file -> file.isAbsolute } })
                "hermes" -> listOf(File(dataHome(engine), "bin"), File(dataHome(engine), if (windows) "hermes-agent/venv/Scripts" else "hermes-agent/venv/bin"))
                else -> emptyList()
            }
            nativeDirs.forEach { dir ->
                val binary = File(dir, engine + if (windows) ".exe" else "")
                if (windows || binary.canExecute()) add(engine, "本机安装", listOf(binary))
            }
            if (windows && engine in setOf("codex", "claude", "opencode", "gemini")) {
                val packageName = when (engine) { "codex" -> "@openai/codex"; "claude" -> "@anthropic-ai/claude-code"; "gemini" -> "@google/gemini-cli"; else -> "opencode-ai" }
                roots.forEach { dir ->
                    val packageDir = File(dir, "node_modules/$packageName")
                    val manifest = File(packageDir, "package.json")
                    if (!manifest.isFile || manifest.length() > 1024 * 1024) return@forEach
                    val json = runCatching { JSONObject(manifest.readText()) }.getOrNull() ?: return@forEach
                    if (json.optString("name") != packageName) return@forEach
                    val bin = (json.opt("bin") as? String) ?: json.optJSONObject("bin")?.optString(engine).orEmpty()
                    val entry = File(packageDir, bin).canonicalFile
                    if (bin.isBlank() || !entry.toPath().startsWith(packageDir.canonicalFile.toPath()) || !entry.isFile) return@forEach
                    fun pe(file: File) = file.isFile && runCatching { file.inputStream().use { it.read() == 77 && it.read() == 90 } }.getOrDefault(false)
                    if (pe(entry)) { add(engine, "npm", listOf(entry)); return@forEach }
                    if (engine == "codex") {
                        val arm = System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
                        val target = if (arm) "aarch64" else "x86_64"
                        val platformPackage = "@openai/codex-win32-" + if (arm) "arm64" else "x64"
                        val native = listOf(File(dir, "node_modules/$platformPackage/vendor"), File(packageDir, "node_modules/$platformPackage/vendor"), File(packageDir, "vendor"))
                            .map { File(it, "$target-pc-windows-msvc/bin/codex.exe") }.firstOrNull(::pe)
                        if (native != null) { add(engine, "npm", listOf(native)); return@forEach }
                    }
                    val node = (listOf(dir) + paths).map { File(it, "node.exe") }.firstOrNull { it.isFile }
                    if (node != null) add(engine, "npm", listOf(node, entry))
                    else add(engine, "npm", listOf(entry), "已找到 npm 安装，但未找到 Node.js")
                }
            }
        }
        if (windows) add("codex", "桌面附带", listOf(File(dataHome("codex"), "plugins/.plugin-appserver/codex.exe")))
        return result.distinctBy { it.id }
    }

    suspend fun discover(): List<LocalRuntimeInstallation> = withContext(Dispatchers.IO) {
        val overrides = runCatching { JSONObject(Store.pref("localRuntimePaths", "{}")) }.getOrDefault(JSONObject())
        val manual = engines.mapNotNull { engine -> overrides.optString(engine).takeIf { it.isNotBlank() }?.let { path ->
            runCatching { manualCandidate(engine, File(path)) }.getOrNull() } }
        (manual + candidates()).distinctBy { it.id }.map { candidate ->
            currentCoroutineContext().ensureActive()
            if (candidate.problem.isNotBlank()) candidate else verify(candidate)
        }
    }

    internal fun manualCandidate(engine: String, file: File): LocalRuntimeInstallation {
        require(engine in engines && file.isFile && file.canExecute()) { "请选择可执行的运行器文件" }
        val windows = System.getProperty("os.name").startsWith("Windows")
        require(!windows || file.extension.equals("exe", true)) { "请选原生 .exe；npm 安装会自动解析，不能直接运行 .cmd/.ps1 包装脚本" }
        val home = File(System.getProperty("user.home"))
        val dataHome = when (engine) {
            "codex" -> System.getenv("CODEX_HOME") ?: File(home, ".codex").path
            "claude" -> System.getenv("CLAUDE_CONFIG_DIR") ?: File(home, ".claude").path
            "gemini" -> File(System.getenv("GEMINI_CLI_HOME")?.takeIf { it.isNotBlank() } ?: home.path, ".gemini").path
            "grok" -> File(home, ".grok").path
            "hermes" -> System.getenv("HERMES_HOME") ?: if (windows && System.getenv("LOCALAPPDATA") != null) File(System.getenv("LOCALAPPDATA"), "hermes").path else File(home, ".hermes").path
            else -> File(System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path, "opencode").path
        }
        return LocalRuntimeInstallation(engine, "用户指定", listOf(file.canonicalPath), File(dataHome).absolutePath)
    }

    internal suspend fun verify(candidate: LocalRuntimeInstallation): LocalRuntimeInstallation = withContext(Dispatchers.IO) {
        var process: Process? = null
        val output = kotlin.io.path.createTempFile("yxi-version-", ".txt").toFile()
        try {
            process = ProcessBuilder(candidate.command + if (candidate.engine == "grok") listOf("--no-auto-update", "version") else listOf("--version")).directory(File(System.getProperty("user.home")))
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(output).start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
                check(System.nanoTime() < deadline) { "版本检测超时" }
                check(output.length() <= 16384) { "版本响应超过大小限制" }
            }
            val bytes = output.inputStream().use { it.readNBytes(16385) }
            check(bytes.size <= 16384 && process.exitValue() == 0) { "版本检测失败，请检查安装或依赖" }
            val text = bytes.toString(Charsets.UTF_8).trim()
            val version = Regex("(?<![\\d.])\\d+\\.\\d+\\.\\d+(?:[-+][A-Za-z0-9.-]+)?").find(text)?.value
            check(version != null) { "已找到入口，但无法确认版本" }
            candidate.copy(version = version)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { candidate.copy(problem = e.message?.take(160) ?: "版本检测失败") }
        finally { process?.let(::stopOwnedProcess); output.delete() }
    }

    internal fun stopOwnedProcess(process: Process) {
        val children = runCatching { process.descendants().use { it.toList() } }.getOrDefault(emptyList())
        children.asReversed().forEach { it.destroyForcibly() }
        if (process.isAlive) process.destroyForcibly()
        runCatching { process.inputStream.close(); process.outputStream.close(); process.errorStream.close() }
    }
}
