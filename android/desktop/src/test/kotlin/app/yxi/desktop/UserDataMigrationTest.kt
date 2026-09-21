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
 * · 任何一步失败：不写标记，源目录一个字节都不动——Store.dir 的 catch 回退旧目录才不空数据。
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
    fun `mid copy failure leaves no marker and source untouched so fallback keeps data`() {
        val (source, contents) = seededSource()
        val destination = tempDir()
        // 每个白名单名的 .migrating 临时位都放一个非空目录：不管 listFiles 顺序谁先被搬，
        // Files.copy(REPLACE_EXISTING) 撞上非空目录必抛——复制中途失败的确定性模拟
        contents.keys.forEach { name ->
            destination.resolve(".$name.migrating").apply { mkdirs() }.resolve("blocker").writeText("x")
        }
        assertFailsWith<java.nio.file.FileSystemException> { migrate(source, destination) }
        assertFalse(destination.resolve(".localappdata-migrated-v1").exists(), "没走完不写完成标记")
        contents.forEach { (name, text) ->
            assertEquals(text, source.resolve(name).readText(), "失败回退旧目录的前提：源一个字节没动 ($name)")
        }
    }
}
