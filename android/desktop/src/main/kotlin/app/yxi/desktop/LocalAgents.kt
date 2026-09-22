package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal class LocalAgentJob(val id: String, val engine: String, val directory: File, val prompt: String, val log: File,
    val resumedFrom: String? = null) {
    var status by mutableStateOf("准备启动"); internal set
    var output by mutableStateOf(""); internal set
    @Volatile internal var process: Process? = null
    internal var cancelRequested = false
    var sessionId by mutableStateOf(resumedFrom); internal set
    internal var active by mutableStateOf(true)
    val running get() = active
}

/** Local CLI jobs are user-initiated; inherit native credentials and permission defaults. */
internal class LocalAgents(private val root: File = File(Store.dir, "local-agents")) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    val jobs = mutableStateListOf<LocalAgentJob>()
    init {
        root.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("[a-f0-9-]{36}")) }
            ?.sortedByDescending { it.lastModified() }?.take(100)?.forEach { folder ->
                runCatching {
                    val saved = JSONObject(File(folder, "task.json").readText())
                    LocalAgentJob(folder.name, saved.getString("engine"), File(saved.getString("directory")), saved.optString("prompt"), File(folder, "output.log"),
                        saved.optString("resumedFrom").takeIf(::validSessionId)).also {
                        it.status = saved.getString("status").let { status -> if (status in setOf("created", "准备启动", "正在运行", "正在停止")) "上次运行结果未确认" else status }
                        it.sessionId = saved.optString("sessionId").takeIf(::validSessionId)
                        it.active = false
                        it.output = readableOutput(readTail(it.log))
                        jobs.add(it)
                    }
                }
            }
    }
    fun start(engine: String, directory: String, prompt: String) = launch(engine, directory, prompt, null)
    fun continueSession(job: LocalAgentJob, prompt: String) {
        require(jobs.any { it === job } && !job.running && job.status == "已完成") { "只能继续已确认完成的会话" }
        val session = job.sessionId?.takeIf(::validSessionId) ?: error("运行器未提供可恢复的会话标识")
        check(jobs.none { it.engine == job.engine && it.sessionId == session && (it.running || it.status == "上次运行结果未确认") }) { "此会话仍在运行或上次结果未确认" }
        launch(job.engine, job.directory.path, prompt, session)
    }
    private fun launch(engine: String, directory: String, prompt: String, resume: String?) {
        require(engine in setOf("codex", "claude") && prompt.isNotBlank())
        val cwd = File(directory).canonicalFile
        require(cwd.isDirectory) { "本机工作目录不存在" }
        check(jobs.count { it.running } < 4) { "最多同时运行 4 个本机任务" }
        val folder = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        val job = LocalAgentJob(folder.name, engine, cwd, prompt, File(folder, "output.log"), resume)
        persist(job, "created")
        jobs.add(0, job)
        scope.launch {
            try {
                val binary = binary(engine) ?: error("本机未找到 $engine，请先安装并完成登录")
                val args = command(binary, engine, resume)
                val process = withContext(Dispatchers.IO) {
                    ProcessBuilder(args).directory(cwd).redirectErrorStream(true).redirectOutput(job.log).start().also {
                        // Retain ownership before crossing the cancellable dispatcher boundary.
                        job.process = it
                    }
                }
                job.process = process
                if (job.cancelRequested) stop(job) else {
                    job.status = "正在运行"
                    withContext(Dispatchers.IO) { process.outputStream.use { it.write(prompt.toByteArray(Charsets.UTF_8)) } }
                }
                while (process.isAlive) {
                    if (job.log.length() > 16 * 1024 * 1024) { stop(job); job.status = "输出超过 16 MB，已停止" }
                    job.output = readableOutput(tail(job.log))
                    delay(700)
                }
                job.output = readableOutput(tail(job.log))
                val identity = withContext(Dispatchers.IO) { sessionIdentity(engine, job.log) }
                check(resume == null || identity != null) { "运行器未确认恢复会话，结果需核对" }
                if (identity != null) {
                    check(resume == null || identity == resume) { "运行器返回了不同的会话，结果需核对" }
                    job.sessionId = identity
                }
                job.status = if (job.cancelRequested) "已停止" else if (process.exitValue() == 0) "已完成" else "运行失败 (${process.exitValue()})"
            } catch (e: Exception) { job.status = if (job.cancelRequested) "已停止" else "启动失败"; job.output = e.message.orEmpty() }
            finally {
                job.process?.takeIf { it.isAlive }?.let { terminate(it) }
                val saved = withContext(NonCancellable + Dispatchers.IO) { runCatching { persist(job, job.status) } }
                if (saved.isFailure) job.status = "结果记录未保存，请保留日志核对"
                job.active = false
            }
        }
    }
    fun stop(job: LocalAgentJob) {
        if (!job.running || job.status !in setOf("准备启动", "正在运行", "正在停止")) return
        job.cancelRequested = true; job.status = "正在停止"
        job.process?.let(::terminate)
    }
    private fun terminate(process: Process) {
        if (!process.isAlive) return
        val children = process.toHandle().descendants().use { it.toList() }
        children.asReversed().forEach { it.destroy() }; process.destroy()
        children.asReversed().filter { it.isAlive }.forEach { it.destroyForcibly() }
        if (process.isAlive) process.destroyForcibly()
    }
    private suspend fun tail(file: File): String = withContext(Dispatchers.IO) {
        readTail(file)
    }
    override fun close() { jobs.filter { it.running }.forEach(::stop); scope.cancel() }
    private fun persist(job: LocalAgentJob, status: String) {
        DurableFile.replace(File(job.log.parentFile, "task.json"), JSONObject().put("engine", job.engine).put("directory", job.directory.path)
            .put("status", status).put("prompt", job.prompt).put("sessionId", job.sessionId.orEmpty()).put("resumedFrom", job.resumedFrom.orEmpty()).toString())
    }
    companion object {
        private fun readTail(file: File): String = if (!file.isFile) "" else java.io.RandomAccessFile(file, "r").use { f ->
            val length = f.length(); f.seek((length - 64000).coerceAtLeast(0))
            val bytes = ByteArray((length - f.filePointer).toInt()); f.readFully(bytes); bytes.toString(Charsets.UTF_8)
        }
        internal fun validSessionId(value: String) = value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
        internal fun command(binary: File, engine: String, resume: String?): List<String> {
            require(resume == null || validSessionId(resume))
            return if (engine == "codex") listOf(binary.path, "exec") +
                (if (resume != null) listOf("resume", "--skip-git-repo-check", "--json", resume, "-") else listOf("--skip-git-repo-check", "--json", "-"))
            else listOf(binary.path, "-p", "--output-format", "stream-json", "--verbose") +
                (if (resume != null) listOf("--resume", resume) else emptyList())
        }
        private fun sessionIdentity(engine: String, log: File): String? {
            if (!log.isFile) return null
            return log.bufferedReader().use { reader -> reader.lineSequence().take(1000).mapNotNull { line ->
                val event = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
                val id = if (engine == "codex" && event.optString("type") == "thread.started") event.optString("thread_id")
                    else if (engine == "claude" && event.optString("type") in setOf("system", "result")) event.optString("session_id") else ""
                id.takeIf(::validSessionId)
            }.firstOrNull() }
        }
        internal fun readableOutput(raw: String): String = raw.lineSequence().mapNotNull { line ->
            val event = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull line.takeIf { it.isNotBlank() }
            when (event.optString("type")) {
                "item.completed" -> event.optJSONObject("item")?.let { it.optString("text").ifBlank { it.optString("aggregated_output") } }?.takeIf { it.isNotBlank() }
                "assistant" -> event.optJSONObject("message")?.optJSONArray("content")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) }.joinToString("\n") }
                "result" -> event.optString("result").takeIf(String::isNotBlank)
                "error" -> event.optString("message").ifBlank { event.optJSONObject("error")?.optString("message").orEmpty() }
                else -> null
            }
        }.filter { it.isNotBlank() }.joinToString("\n").takeLast(64000)
        fun binary(engine: String): File? {
            val home = File(System.getProperty("user.home"))
            val windows = System.getProperty("os.name").startsWith("Windows")
            val name = engine + if (windows) ".exe" else ""
            val paths = listOf(File(home, ".local/bin/$name")) +
                (if (engine == "codex") listOf(File(System.getenv("CODEX_HOME") ?: File(home, ".codex").path, "plugins/.plugin-appserver/codex.exe")) else emptyList()) +
                System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name) }
            return paths.firstOrNull { it.isFile && it.canExecute() }
        }
    }
}
