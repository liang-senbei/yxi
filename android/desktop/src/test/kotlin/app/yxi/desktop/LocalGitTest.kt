package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

/** 本机对齐 P6a：LocalGit 读本机仓库的改动，结果和服务器脚本同一个形状。用临时目录里新建的仓库，不碰任何真仓库；git 从测试进程的 PATH 找。 */
class LocalGitTest {
    @TempDir lateinit var root: File
    private val saved = LocalGit.searchPath
    @BeforeEach fun usePath() { LocalGit.searchPath = { System.getenv("PATH").orEmpty() } }
    @AfterEach fun restore() { LocalGit.searchPath = saved }

    private fun git(dir: File, vararg args: String) {
        val p = ProcessBuilder(listOf(LocalGit.executable(System.getenv("PATH").orEmpty()), "-C", dir.path) + args).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor() == 0) { "git ${args.joinToString(" ")}：$out" }
    }
    private fun repo(): File = File(root, "repo").apply {
        mkdirs()
        git(this, "init", "-q", "-b", "main")
        git(this, "config", "user.email", "fixture@example.invalid"); git(this, "config", "user.name", "fixture"); git(this, "config", "commit.gpgsign", "false")
        resolve("a.txt").writeText("one\ntwo\n"); resolve("b.txt").writeText("keep\n")
        git(this, "add", "."); git(this, "commit", "-q", "-m", "init")
    }

    @Test fun `status parsing skips the original path of renames`() {
        val raw = "R  new.txt\u0000old.txt\u0000 M a.txt\u0000?? c.txt\u0000".toByteArray()
        assertEquals(listOf("R " to "new.txt", " M" to "a.txt", "??" to "c.txt"), LocalGit.parseStatus(raw))
    }

    @Test fun `snapshot reads unstaged, staged and untracked changes like the server script`(): Unit = runBlocking {
        val repo = repo()
        repo.resolve("a.txt").writeText("one\ntwo\nthree\n")
        repo.resolve("b.txt").writeText("keep\nstaged\n"); git(repo, "add", "b.txt")
        repo.resolve("c.txt").writeText("new\n")
        val unstaged = LocalGit.snapshot(repo.path, "a.txt", staged = false)
        assertEquals(repo.canonicalPath, File(unstaged.getString("root")).canonicalPath)
        assertEquals("main", unstaged.getString("branch")); assertEquals(40, unstaged.getString("head").length)
        val files = (0 until unstaged.getJSONArray("files").length()).map { unstaged.getJSONArray("files").getJSONObject(it).let { f -> f.getString("code") to f.getString("path") } }
        assertEquals(setOf(" M" to "a.txt", "M " to "b.txt", "??" to "c.txt"), files.toSet())
        assertTrue("+three" in unstaged.getString("diff")); assertFalse(unstaged.getBoolean("clipped"))
        assertTrue("+staged" in LocalGit.snapshot(repo.path, "b.txt", staged = true).getString("diff"))
        assertEquals("", LocalGit.snapshot(repo.path, "b.txt", staged = false).getString("diff"), "已暂存的改动不在未暂存的 diff 里")
        assertEquals("未跟踪文件或目录，请通过文件预览打开。", LocalGit.snapshot(repo.path, "c.txt", staged = false).getString("diff"))
        assertEquals("", LocalGit.snapshot(repo.path, "", staged = false).getString("diff"), "没选文件就不读 diff")
        // 从子目录读，root 照样是仓库根
        repo.resolve("sub").mkdirs()
        assertEquals(repo.canonicalPath, File(LocalGit.snapshot(repo.resolve("sub").path, "", false).getString("root")).canonicalPath)
    }

    @Test fun `long diffs are clipped at 256 KiB`(): Unit = runBlocking {
        val repo = repo()
        repo.resolve("a.txt").writeText((1..40_000).joinToString("\n") { "line $it with some padding text" })
        val data = LocalGit.snapshot(repo.path, "a.txt", staged = false)
        assertTrue(data.getBoolean("clipped"))
        assertEquals(LocalGit.DIFF_LIMIT, data.getString("diff").toByteArray(Charsets.UTF_8).size.coerceAtMost(LocalGit.DIFF_LIMIT))
    }

    @Test fun `outside a repository or without git it explains instead of guessing`(): Unit = runBlocking {
        val plain = File(root, "plain").apply { mkdirs() }
        assertTrue(assertFailsWith<IllegalStateException> { LocalGit.snapshot(plain.path, "", false) }.message!!.startsWith("目录不属于 Git 仓库"))
        assertFailsWith<IllegalArgumentException> { LocalGit.snapshot(File(root, "missing").path, "", false) }
        assertEquals("没有找到 git，请先安装 Git", assertFailsWith<IllegalStateException> { LocalGit.executable(File(root, "empty-bin").path) }.message)
        val bin = File(root, "bin").apply { mkdirs() }
        val fake = bin.resolve(if (System.getProperty("os.name").startsWith("Windows")) "git.exe" else "git").apply { writeText(""); setExecutable(true) }
        assertEquals(fake.path, LocalGit.executable(bin.path))
    }
}
