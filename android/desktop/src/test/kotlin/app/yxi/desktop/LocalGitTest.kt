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

    // M4p：分支改动条——相对 HEAD 的增删（已暂存 + 未暂存）、未跟踪的文本文件按整份新增（二进制不算行、但算文件），子目录里的未跟踪文件逐个算
    @Test fun `branch stat counts changes against HEAD and whole untracked text files`(): Unit = runBlocking {
        val repo = repo()
        repo.resolve("a.txt").writeText("one\nthree\n")
        repo.resolve("b.txt").writeText("keep\nstaged\n"); git(repo, "add", "b.txt")
        repo.resolve("c.txt").writeText("new\n")
        repo.resolve("dir").mkdirs(); repo.resolve("dir/d.txt").writeText("x\ny")
        repo.resolve("bin.dat").writeBytes(byteArrayOf(0, 1, 2))
        val stat = LocalGit.stat(repo.resolve("dir").path)!!
        assertEquals("main", stat.branch); assertEquals(40, stat.head.length); assertEquals("main", stat.label)
        assertEquals(5, stat.files); assertEquals(5, stat.added); assertEquals(1, stat.removed)
        assertNull(LocalGit.stat(File(root, "plain").apply { mkdirs() }.path), "不是仓库就不显示条")
        // 服务器那段脚本对同一个仓库得出同样的数（机器上没有 python3 就跳过）
        val python = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).map { File(it, "python3") }.firstOrNull { it.canExecute() }
        org.junit.jupiter.api.Assumptions.assumeTrue(python != null && !System.getProperty("os.name").startsWith("Windows"))
        val p = ProcessBuilder(python!!.path, "-c", BranchChanges.SCRIPT, repo.path).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, p.waitFor(), p.errorStream.readBytes().toString(Charsets.UTF_8))
        assertEquals(stat, BranchChanges.parse(out.lineSequence().last { it.startsWith("__YXI_BRANCH__:") }.removePrefix("__YXI_BRANCH__:")))
    }

    @Test fun `branch stat before the first commit adds staged and unstaged halves`(): Unit = runBlocking {
        val repo = File(root, "fresh").apply { mkdirs() }
        git(repo, "init", "-q", "-b", "trunk")
        repo.resolve("x.txt").writeText("a\nb\n"); git(repo, "add", "x.txt")
        repo.resolve("x.txt").writeText("a\nb\nc\n")
        val stat = LocalGit.stat(repo.path)!!
        assertEquals(BranchStat("trunk", "", 1, 3, 0), stat)
        assertEquals("尚无提交", stat.copy(branch = "").label); assertEquals("分离 HEAD · 8b7d4e0", stat.copy(branch = "", head = "8b7d4e0c2f61").label)
    }

    @Test fun `numstat sums text rows and skips binaries, line counts follow git`() {
        assertEquals(5 to 3, BranchChanges.numstat("3\t1\ta.txt\n-\t-\timg.png\n2\t2\tdir/b.txt\n"))
        assertEquals(0 to 0, BranchChanges.numstat(""))
        assertEquals(2, BranchChanges.lines("x\ny".toByteArray())); assertEquals(2, BranchChanges.lines("x\ny\n".toByteArray()))
        assertEquals(0, BranchChanges.lines(ByteArray(0))); assertEquals(0, BranchChanges.lines(byteArrayOf(65, 0, 10)))
        assertNull(BranchChanges.parse("null"))
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
