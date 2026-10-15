package app.yxi.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * 本机的 Git 改动（本机对齐 P6a）：同服务器 GitChangesPane 那段脚本读的东西（仓库根、status、选中文件的 diff、分支、HEAD），
 * 结果是同一个形状的 JSON，所以本机和服务器共用 [GitChangesView]。本机不经 Python，直接起 git；只读，不写仓库。
 * [run] 可替换（截图夹具不许起进程）；生产默认值 [runGit]。
 */
internal object LocalGit {
    const val DIFF_LIMIT = 262_144
    internal var run: suspend (directory: String, file: String, staged: Boolean) -> JSONObject = ::snapshot
    /** 找 git 用的 PATH：生产是登录 shell 的（macOS 上 Finder 起的 App 只有精简 PATH）；单测换成测试进程自己的。 */
    internal var searchPath: () -> String = { LoginShellPath.await() }

    suspend fun snapshot(directory: String, file: String, staged: Boolean): JSONObject = withContext(Dispatchers.IO) {
        require(File(directory).isAbsolute && File(directory).isDirectory) { "本机目录不存在：$directory" }
        val git = executable(searchPath())
        val root = String(runGit(git, directory, listOf("rev-parse", "--show-toplevel")).requireOk("目录不属于 Git 仓库").stdout).trim()
        val head = runGit(git, root, listOf("rev-parse", "--verify", "HEAD")).let { if (it.code == 0) String(it.stdout).trim() else "" }
        val branch = runGit(git, root, listOf("symbolic-ref", "--short", "-q", "HEAD")).let { if (it.code == 0) String(it.stdout).trim() else "" }
        val records = runGit(git, root, listOf("status", "--porcelain=v1", "-z", "--untracked-files=normal")).requireOk("读取 Git 状态失败").stdout
        val files = parseStatus(records)
        var text = ""; var clipped = false
        val entry = files.firstOrNull { it.second == file }
        if (entry != null) {
            if (entry.first == "??") text = "未跟踪文件或目录，请通过文件预览打开。"
            else {
                val args = listOf("diff", "--no-ext-diff", "--no-textconv", "--no-color") + (if (staged) listOf("--cached") else emptyList()) + listOf("--", file)
                val diff = runGit(git, root, args, limit = DIFF_LIMIT + 1)
                clipped = diff.stdout.size > DIFF_LIMIT
                if (!clipped) diff.requireOk("读取差异失败")
                text = String(diff.stdout, 0, minOf(diff.stdout.size, DIFF_LIMIT), Charsets.UTF_8)
            }
        }
        JSONObject().put("root", root).put("files", JSONArray(files.map { JSONObject().put("code", it.first).put("path", it.second) }))
            .put("diff", text).put("clipped", clipped).put("head", head).put("branch", branch).put("readAt", Instant.now().toString())
    }

    /**
     * 分支改动条的数据（M4p，算法同 [BranchChanges.SCRIPT]）：分支 / HEAD、status 里的文件数（未跟踪的逐个文件算）、工作区相对 HEAD 的增删行数
     * （还没有提交时是已暂存 + 未暂存两份），未跟踪的文本文件按整份新增（最多 [BranchChanges.UNTRACKED_FILES] 个、每个读开头 256 KiB、不跟符号链接、读不了的跳过）。
     * 目录不属于 Git 仓库时返回 null。
     */
    suspend fun stat(directory: String): BranchStat? = withContext(Dispatchers.IO) {
        require(File(directory).isAbsolute && File(directory).isDirectory) { "本机目录不存在：$directory" }
        val git = executable(searchPath())
        val top = runGit(git, directory, listOf("rev-parse", "--show-toplevel"))
        if (top.code != 0) return@withContext null
        val root = String(top.stdout).trim()
        val head = runGit(git, root, listOf("rev-parse", "--verify", "-q", "HEAD")).let { if (it.code == 0) String(it.stdout).trim() else "" }
        val branch = runGit(git, root, listOf("symbolic-ref", "--short", "-q", "HEAD")).let { if (it.code == 0) String(it.stdout).trim() else "" }
        val files = parseStatus(runGit(git, root, listOf("status", "--porcelain=v1", "-z", "--untracked-files=all")).requireOk("读取 Git 状态失败").stdout)
        val base = listOf("diff", "--numstat", "--no-ext-diff", "--no-textconv")
        var added = 0; var removed = 0
        for (args in if (head.isNotBlank()) listOf(base + head) else listOf(base + "--cached", base)) {
            val (a, r) = BranchChanges.numstat(String(runGit(git, root, args).requireOk("读取改动统计失败").stdout, Charsets.UTF_8))
            added += a; removed += r
        }
        files.filter { it.first == "??" }.take(BranchChanges.UNTRACKED_FILES).forEach { (_, path) ->
            val file = File(root, path).toPath()
            // 读不了的（没权限、Agent 刚删掉的临时文件）跳过，不让整条改动条消失
            if (java.nio.file.Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) runCatching {
                added += BranchChanges.lines(java.nio.file.Files.newInputStream(file).use { it.readNBytes(BranchChanges.UNTRACKED_BYTES) })
            }
        }
        BranchStat(branch, head, files.size, added, removed)
    }

    /** `status --porcelain=v1 -z`：每条「XY 路径」以 NUL 结尾；改名 / 复制后面多跟一条原路径，跳过（同服务器脚本）。 */
    internal fun parseStatus(raw: ByteArray): List<Pair<String, String>> {
        val records = String(raw, Charsets.UTF_8).split('\u0000')
        val files = ArrayList<Pair<String, String>>()
        var index = 0
        while (index < records.size) {
            val record = records[index]; index++
            if (record.length < 4) continue
            val code = record.substring(0, 2)
            files += code to record.substring(3)
            if ('R' in code || 'C' in code) index++
        }
        return files
    }

    /** 在登录 shell 的 PATH（macOS，LoginShellPath）里找 git。macOS 没装命令行开发者工具时 /usr/bin/git 只是占位程序，一跑就弹安装框，所以先拦下。 */
    internal fun executable(path: String): String {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val found = path.split(File.pathSeparatorChar).filter { it.isNotBlank() }.asSequence()
            .flatMap { dir -> (if (windows) listOf("git.exe", "git.cmd") else listOf("git")).asSequence().map { File(dir, it) } }
            .firstOrNull { it.isFile && it.canExecute() } ?: error("没有找到 git，请先安装 Git")
        if (System.getProperty("os.name").startsWith("Mac") && found.path == "/usr/bin/git" &&
            !File("/Library/Developer/CommandLineTools/usr/bin/git").exists() && File("/Applications").listFiles { f -> f.name.startsWith("Xcode") }.isNullOrEmpty())
            error("这台 Mac 还没装命令行开发者工具，/usr/bin/git 不能用；装好后（或装 Homebrew 的 git）再看改动")
        return found.path
    }

    internal class Result(val code: Int, val stdout: ByteArray, val stderr: String) {
        fun requireOk(what: String): Result { check(code == 0) { what + stderr.trim().take(500).let { if (it.isBlank()) "" else "：$it" } }; return this }
    }

    /** 起一次 git（只读命令）：stdout 最多读 [limit] 字节，超了就停；15 秒没结束就杀掉。 */
    private fun runGit(git: String, directory: String, args: List<String>, limit: Int = 8 * 1024 * 1024): Result {
        val process = ProcessBuilder(listOf(git, "--no-pager", "-C", directory) + args).redirectInput(ProcessBuilder.Redirect.from(File(if (File.separatorChar == '\\') "NUL" else "/dev/null")))
            .apply { LoginShellPath.applyTo(environment()); environment()["GIT_TERMINAL_PROMPT"] = "0"; environment()["GIT_OPTIONAL_LOCKS"] = "0" }.start()
        try {
            val err = StringBuilder()
            val drain = Thread { runCatching { err.append(String(process.errorStream.readNBytes(64 * 1024), Charsets.UTF_8)) } }.apply { isDaemon = true; start() }
            val out = process.inputStream.readNBytes(limit)
            if (out.size >= limit) { process.destroy(); return Result(0, out, "") }
            check(process.waitFor(15, TimeUnit.SECONDS)) { "git 15 秒内没有结束" }
            drain.join(1000)
            return Result(process.exitValue(), out, err.toString())
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
