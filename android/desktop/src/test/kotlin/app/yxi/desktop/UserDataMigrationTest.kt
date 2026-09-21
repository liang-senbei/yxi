package app.yxi.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UserDataMigration 定向测试：全部临时目录假文件（凭据全是假字符串，零真凭据、零真安装、
 * 不触碰真实 %LOCALAPPDATA%）。钉住迁移的安全边界：
 * · 白名单复制（protected 副本/auth/known_hosts/prefs…），安装器领地（Update.exe/current/packages）绝不跟过来；
 * · 目标已存在的文件永远赢（含用户已重新登录/已登出）；
 * · 完成标记只在整个迁移成功后落盘——重跑靠标记短路，不会把用户已清掉的登录复活；
 * · 两阶段事务（journal 记账→全量预备→统一提交→落章）：中途失败只回滚**本次新建**的目标
 *   与临时位，源一个字节不动；硬杀残留靠 journal 恢复——回退旧目录的旧版继续写新 token/
 *   hosts 后，下次重试必须拿到新源，绝不让半迁移的 stale 残留压过新数据。
 */
class UserDataMigrationTest {
    private fun tempDir() = Files.createTempDirectory("yxi-migration").toFile()

    /** 源目录布好白名单全家桶（内容各不相同便于比对），返回 (源, 内容表)。 */
    private fun seededSource(): Pair<File, Map<String, String>> {
        val source = tempDir()
        val contents = mapOf(
            "hosts.json" to """[{"id":"h1","hostname":"old.example"}]""",
            "auth.json" to "dpapi-fake-blob-not-a-real-credential",
            "prefs.json" to """{"theme":"dark"}""",
            "known_hosts" to "old.example ssh-ed25519 FAKEFINGERPRINT",
            "id_ed25519" to "-----FAKE KEY NOT REAL-----",
            "hosts.json.migration-copy.protected" to "protected-current-fake",
            "hosts.json.bak.migration-copy.protected" to "protected-bak-fake",
            "hosts.json.damaged.migration-copy.protected" to "protected-damaged-fake",
            "hosts.json.protected.migration-20260922" to "protected-timestamped-fake",
        )
        contents.forEach { (name, text) -> source.resolve(name).writeText(text) }
        return source to contents
    }

    private fun migrate(source: File, destination: File) = UserDataMigration.migrate(source, destination)

    @Test
    fun `copies protected copies auth known hosts and prefs and never touches source`() {
        val (source, contents) = seededSource()
        val destination = tempDir()
        migrate(source, destination)
        contents.forEach { (name, text) ->
            assertEquals(text, destination.resolve(name).readText(), name)
            assertEquals(text, source.resolve(name).readText(), "源必须原样保留：$name") // Never delete or replace a source
        }
        assertTrue(destination.resolve(".localappdata-migrated-v1").isFile)
    }

    @Test
    fun `leaves installer owned entries and stray files behind`() {
        val (source, contents) = seededSource()
        source.resolve("Update.exe").writeText("velopax-installer-fake")
        source.resolve("current").apply { mkdirs() }.resolve("app.dll").writeText("fake")
        source.resolve("packages").apply { mkdirs() }.resolve("pkg.nupkg").writeText("fake")
        source.resolve("stray-notes.txt").writeText("not on the allowlist")
        // 白名单是正漏（allowlist）：光名字对还不够——把 known_hosts 换成符号链接，
        // 名字仍在白名单里，也绝不能跟过来（链接指哪都一样，防链出源目录）
        source.resolve("known_hosts").delete()
        Files.createSymbolicLink(source.resolve("known_hosts").toPath(), source.resolve("prefs.json").toPath())
        val destination = tempDir()
        migrate(source, destination)
        listOf("Update.exe", "current", "packages", "stray-notes.txt", "known_hosts").forEach {
            assertFalse(destination.resolve(it).exists(), "不该跟过来：$it")
        }
        assertTrue(destination.resolve("auth.json").isFile) // 真身照常复制
        assertEquals(contents["auth.json"], destination.resolve("auth.json").readText())
    }

    @Test
    fun `existing destination file always wins including a fresh login`() {
        val (source, _) = seededSource()
        val destination = tempDir()
        destination.resolve("auth.json").writeText("fresh-home-login-fake") // 用户在新目录已重新登录
        migrate(source, destination)
        assertEquals("fresh-home-login-fake", destination.resolve("auth.json").readText()) // 旧登录绝不覆盖新登录
        assertEquals("""{"theme":"dark"}""", destination.resolve("prefs.json").readText()) // 没冲突的照常搬
    }

    @Test
    fun `rerun after cleared login does not resurrect the copied auth`() {
        val (source, _) = seededSource()
        val destination = tempDir()
        migrate(source, destination)
        destination.resolve("auth.json").delete() // 用户已登出/清掉登录
        migrate(source, destination)              // 标记短路：重复迁移不恢复，也不重搬其它
        assertFalse(destination.resolve("auth.json").exists(), "已清登录不许被迁移复活")
        assertTrue(destination.resolve(".localappdata-migrated-v1").isFile)
    }

    @Test
    fun `mid copy failure leaves no marker no journal and source untouched`() {
        val (source, contents) = seededSource()
        val destination = tempDir()
        // 每个白名单名的 .migrating 临时位都放一个非空目录：不管 listFiles 顺序谁先被搬，
        // CREATE_NEW 撞上已存在路径必抛——预备阶段失败的确定性模拟（同时钉「预留名上的
        // 预先存在物绝不覆盖」：回滚只删自己建的，这些目录必须原样留下）
        contents.keys.forEach { name ->
            destination.resolve(".$name.migrating").apply { mkdirs() }.resolve("blocker").writeText("x")
        }
        assertFailsWith<java.nio.file.FileSystemException> { migrate(source, destination) }
        assertFalse(destination.resolve(".localappdata-migrated-v1").exists(), "没走完不写完成标记")
        assertFalse(destination.resolve(".localappdata-migrating-journal-v1").exists(), "回滚不留账")
        assertTrue(destination.listFiles()!!.none { it.name.endsWith(".migrating") && it.isFile }, "不剩本进程建的临时文件")
        contents.forEach { (name, text) ->
            assertEquals(text, source.resolve(name).readText(), "失败回退旧目录的前提：源一个字节没动 ($name)")
        }
        contents.keys.forEach { name ->
            assertTrue(destination.resolve(".$name.migrating").isDirectory, "预先存在的路径不是本次新建，回滚不碰：$name")
        }
    }

    @Test
    fun `mid commit failure rolls back this run s targets and retry picks up newer source`() {
        val (source, contents) = seededSource()
        val destination = tempDir()
        destination.resolve("prefs.json").writeText("user-own-newer-prefs") // 用户已有文件：不入账、不回滚
        val failingMover = { from: java.nio.file.Path, to: java.nio.file.Path ->
            if (to.fileName.toString() == "known_hosts") throw java.io.IOException("模拟提交中途失败")
            Files.move(from, to)
        }
        assertFailsWith<java.io.IOException> { UserDataMigration.migrate(source, destination, mover = failingMover) }
        // 无论 listFiles 顺序里谁排在 known_hosts 前面：本次 move 出去的目标已全部回滚，
        // 白名单名在目标目录里只剩用户自己的 prefs.json —— 没有任何半迁移残留
        contents.keys.forEach { name ->
            if (name == "prefs.json") assertEquals("user-own-newer-prefs", destination.resolve(name).readText(), "用户文件原封不动")
            else assertFalse(destination.resolve(name).exists(), "本次新建的目标必须回滚干净：$name")
        }
        assertTrue(destination.listFiles()!!.none { it.name.endsWith(".migrating") }, "临时位不留")
        assertFalse(destination.resolve(".localappdata-migrating-journal-v1").exists(), "回滚不留账")
        assertFalse(destination.resolve(".localappdata-migrated-v1").exists(), "没走完不写完成标记")
        contents.forEach { (name, text) ->
            assertEquals(text, source.resolve(name).readText(), "源一个字节没动：$name")
        }
        // 回退旧目录的旧版继续写新 token（真实边界）：然后下次启动重试——必须拿到**新源**
        source.resolve("auth.json").writeText("newer-token-written-while-fallbacked")
        migrate(source, destination)
        assertTrue(destination.resolve(".localappdata-migrated-v1").isFile)
        assertEquals("newer-token-written-while-fallbacked", destination.resolve("auth.json").readText(), "重试拿新源，不吃回滚前的旧值")
        assertEquals("user-own-newer-prefs", destination.resolve("prefs.json").readText(), "用户文件依旧原封不动")
    }

    @Test
    fun `journal recovery clears a killed run s stale artifacts but never user files`() {
        val (source, contents) = seededSource()
        val destination = tempDir()
        // 手工布置「进程硬杀在事务里」的现场：journal 在（只记白名单名）、死进程建的
        // stale 目标与遗留临时位在；用户自己的文件也在——包括一个恰好叫 `.笔记.migrating`
        // 的同名约定文件：恢复只处理 journal 点名的目标与其临时位，绝不全目录扫 `.migrating`
        destination.resolve(".localappdata-migrating-journal-v1").writeText("auth.json\nhosts.json\n")
        destination.resolve("auth.json").writeText("stale-token-from-dead-run")
        destination.resolve(".auth.json.migrating").writeText("half-copied-temp")
        destination.resolve("prefs.json").writeText("user-own-prefs")
        destination.resolve(".notes.migrating").writeText("user's own file, coincidental name")
        migrate(source, destination)
        // journal 点名的 auth/hosts 是死进程新建物 → 清掉后按**当前源**重搬；
        // 目标已存在的 prefs 走 existing-wins 原地不动；没点名的用户文件一个都不碰
        assertEquals(contents["auth.json"], destination.resolve("auth.json").readText(), "stale 目标清除后重拿新源")
        assertEquals(contents["hosts.json"], destination.resolve("hosts.json").readText())
        assertEquals("user-own-prefs", destination.resolve("prefs.json").readText())
        assertEquals("user's own file, coincidental name", destination.resolve(".notes.migrating").readText(),
            "不在 journal 里就没有「清理」可言：用户自己的同名约定文件绝不误删")
        assertFalse(destination.resolve(".auth.json.migrating").exists(), "journal 点名的遗留临时位清掉")
        assertFalse(destination.resolve(".localappdata-migrating-journal-v1").exists())
        assertTrue(destination.resolve(".localappdata-migrated-v1").isFile)
    }

    @Test
    fun `corrupt journal with foreign or separator entry aborts recovery and preserves everything`() {
        val (source, _) = seededSource()
        val destination = tempDir()
        val outside = File(destination.parentFile, "evil.txt")
        // 硬杀现场 + journal 被写坏（混入白名单外的名字与路径分隔符）：恢复必须整体拒绝——
        // 不吞异常、不删 journal、不删任何现存路径；../evil 解析在目标目录之外，更不能被够到
        outside.writeText("innocent bystander")
        destination.resolve(".localappdata-migrating-journal-v1").writeText("auth.json\n../evil.txt\n")
        destination.resolve("auth.json").writeText("stale-token-from-dead-run")
        destination.resolve(".auth.json.migrating").writeText("half-copied-temp")
        assertFailsWith<IllegalArgumentException> { migrate(source, destination) }
        assertTrue(destination.resolve(".localappdata-migrating-journal-v1").isFile, "恢复失败不吞不删：journal 留给下次")
        assertFalse(destination.resolve(".localappdata-migrated-v1").exists(), "没迁完不落章")
        assertEquals("stale-token-from-dead-run", destination.resolve("auth.json").readText(), "现场一个字节不动")
        assertTrue(destination.resolve(".auth.json.migrating").isFile)
        assertEquals("innocent bystander", outside.readText(), "目录外的路径绝不能被 journal 够到")
        outside.delete()
    }

    @Test
    fun `target that appears during a failed commit survives rollback as user data`() {
        val (source, contents) = seededSource()
        val destination = tempDir()
        // 提交中途：known_hosts 的目标位被「并发写入」后 move 失败——没有成功 move 过的
        // 名字绝不进回滚清单（不扫 exists 删目标），这个半路出现的文件是用户数据，不许动
        val racing = { from: java.nio.file.Path, to: java.nio.file.Path ->
            if (to.fileName.toString() == "known_hosts") {
                File(destination, "known_hosts").writeText("concurrent-user-data")
                throw java.io.IOException("模拟并发写入后 move 失败")
            }
            Files.move(from, to)
        }
        assertFailsWith<java.io.IOException> { UserDataMigration.migrate(source, destination, mover = racing) }
        assertEquals("concurrent-user-data", destination.resolve("known_hosts").readText(),
            "没 move 成功的名字不在回滚清单里：半路出现的用户数据原样保留")
        assertFalse(destination.resolve(".localappdata-migrated-v1").exists())
        // 重试：半路出现的文件走 existing-wins 跳过，其余照常迁完落章
        migrate(source, destination)
        assertTrue(destination.resolve(".localappdata-migrated-v1").isFile)
        assertEquals("concurrent-user-data", destination.resolve("known_hosts").readText(), "existing-wins 不覆盖它")
        assertEquals(contents["auth.json"], destination.resolve("auth.json").readText())
    }
}
