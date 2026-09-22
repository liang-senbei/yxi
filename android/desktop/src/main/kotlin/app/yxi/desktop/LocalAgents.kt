package app.yxi.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal class LocalAgentJob(val id: String, val engine: String, val directory: File, val prompt: String, val log: File) {
    var status by mutableStateOf("准备启动"); internal set
    var output by mutableStateOf(""); internal set
    internal var process: Process? = null
    internal var cancelRequested = false
    val running get() = status in setOf("准备启动", "正在运行", "正在停止")
}

/** Local CLI jobs are user-initiated; inherit native credentials and permission defaults. */
internal class LocalAgents(private val root: File = File(Store.dir, "local-agents")) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    val jobs = mutableStateListOf<LocalAgentJob>()
    fun start(engine: String, directory: String, prompt: String) {
        require(engine in setOf("codex", "claude") && prompt.isNotBlank())
        val cwd = File(directory).canonicalFile
        require(cwd.isDirectory) { "本机工作目录不存在" }
        check(jobs.count { it.running } < 4) { "最多同时运行 4 个本机任务" }
        val folder = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        DurableFile.replace(File(folder, "task.json"), JSONObject().put("engine", engine).put("directory", cwd.path).put("status", "created").toString())
        val job = LocalAgentJob(folder.name, engine, cwd, prompt, File(folder, "output.log"))
        jobs.add(0, job)
        scope.launch {
            try {
                val binary = binary(engine) ?: error("本机未找到 $engine，请先安装并完成登录")
                val args = if (engine == "codex") listOf(binary.path, "exec", "--json", "-") else listOf(binary.path, "-p", "--output-format", "stream-json", "--verbose")
                val process = withContext(Dispatchers.IO) {
                    ProcessBuilder(args).directory(cwd).redirectErrorStream(true).redirectOutput(job.log).start()
                }
                job.process = process
                if (job.cancelRequested) stop(job) else {
                    job.status = "正在运行"
                    withContext(Dispatchers.IO) { process.outputStream.use { it.write(prompt.toByteArray(Charsets.UTF_8)) } }
                }
                while (process.isAlive) {
                    if (job.log.length() > 16 * 1024 * 1024) { stop(job); job.status = "输出超过 16 MB，已停止" }
                    job.output = tail(job.log)
                    delay(700)
                }
                job.output = tail(job.log)
                job.status = if (job.cancelRequested) "已停止" else if (process.exitValue() == 0) "已完成" else "运行失败 (${process.exitValue()})"
            } catch (e: Exception) { job.status = if (job.cancelRequested) "已停止" else "启动失败"; job.output = e.message.orEmpty() }
            finally {
                job.process?.takeIf { it.isAlive }?.let { terminate(it) }
                withContext(Dispatchers.IO) { DurableFile.replace(File(folder, "task.json"), JSONObject().put("engine", engine).put("directory", cwd.path).put("status", job.status).toString()) }
            }
        }
    }
    fun stop(job: LocalAgentJob) {
        job.cancelRequested = true; job.status = "正在停止"
        job.process?.let(::terminate)
    }
    private fun terminate(process: Process) {
        val children = process.toHandle().descendants().use { it.toList() }
        children.asReversed().forEach { it.destroy() }; process.destroy()
        children.asReversed().filter { it.isAlive }.forEach { it.destroyForcibly() }
        if (process.isAlive) process.destroyForcibly()
    }
    private suspend fun tail(file: File): String = withContext(Dispatchers.IO) {
        if (!file.isFile) "" else java.io.RandomAccessFile(file, "r").use { f ->
            f.seek((f.length() - 64000).coerceAtLeast(0)); val bytes = ByteArray((f.length() - f.filePointer).toInt()); f.readFully(bytes); bytes.toString(Charsets.UTF_8)
        }
    }
    override fun close() { jobs.filter { it.running }.forEach(::stop); scope.cancel() }
    companion object {
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
