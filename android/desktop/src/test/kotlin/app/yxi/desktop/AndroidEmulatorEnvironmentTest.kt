package app.yxi.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AndroidEmulatorEnvironment 定向测试：全部用临时目录假 SDK（.exe/.bat 建空文件即算存在，
 * 不真执行任何进程），env 与 listAvds 都走注入。零安装、零启动、零写入。
 */
class AndroidEmulatorEnvironmentTest {
    private fun tempRoot(): File = Files.createTempDirectory("yxi-sdk").toFile()

    /** 按相对路径建空文件（存在性即全部所需），返回根目录。 */
    private fun fakeSdk(root: File, vararg rels: String): File {
        rels.forEach { rel ->
            val f = root.resolve(rel)
            f.parentFile.mkdirs(); f.createNewFile()
        }
        return root
    }

    private fun envOf(vararg pairs: Pair<String, String>) = { name: String -> pairs.toMap()[name] }

    private val noAvds = { _: File -> Pair(emptyList<String>(), null as String?) }

    private fun allTools(root: File) = fakeSdk(
        root,
        "emulator/emulator.exe", "platform-tools/adb.exe",
        "cmdline-tools/latest/bin/sdkmanager.bat", "cmdline-tools/latest/bin/avdmanager.bat",
    )

    @Test
    fun `env root with all four tools is usable with nothing missing`() {
        val root = allTools(tempRoot())
        val r = AndroidEmulatorEnvironment(env = envOf("ANDROID_HOME" to root.absolutePath), listAvds = noAvds).discover()
        assertEquals(root.absolutePath, r.sdkRoot)
        assertEquals(emptyList(), r.missing)
        assertTrue(r.usable)
        assertTrue(r.tools.any { it.name == "adb" && it.path.endsWith("adb.exe") })
    }

    @Test
    fun `stale ANDROID_HOME falls through to LocalAppData standard path`() {
        val ghost = tempRoot().resolve("gone")   // 不存在的目录
        val base = tempRoot()
        val real = allTools(base.resolve("Android").resolve("Sdk"))
        val r = AndroidEmulatorEnvironment(
            env = envOf(
                "ANDROID_HOME" to ghost.absolutePath,
                "LOCALAPPDATA" to base.absolutePath,
            ),
            listAvds = noAvds,
        ).discover()
        assertEquals(real.absolutePath, r.sdkRoot)
        // 候选根全记录，含那个不存在的——排查「为什么没认 ANDROID_HOME」要看它
        assertEquals(2, r.rootsChecked.size)
    }

    @Test
    fun `partial sdk lists missing tools in fixed order and is not usable`() {
        val root = fakeSdk(tempRoot(), "platform-tools/adb.exe")
        val r = AndroidEmulatorEnvironment(env = envOf("ANDROID_HOME" to root.absolutePath), listAvds = noAvds).discover()
        assertEquals(listOf("emulator", "sdkmanager", "avdmanager"), r.missing)
        assertFalse(r.usable)
        assertEquals("adb", r.tools.single().name)
    }

    @Test
    fun `legacy tools bin layout still reported for sdkmanager`() {
        val root = fakeSdk(tempRoot(), "tools/bin/sdkmanager.bat", "tools/bin/avdmanager.bat")
        val r = AndroidEmulatorEnvironment(env = envOf("ANDROID_HOME" to root.absolutePath), listAvds = noAvds).discover()
        assertTrue(r.tools.map { it.name }.containsAll(listOf("sdkmanager", "avdmanager")))
        assertEquals(listOf("emulator", "adb"), r.missing)
    }

    @Test
    fun `avd names come from emulator list and fall back to ini scan on failure`() {
        val root = allTools(tempRoot())
        val base = tempRoot()
        val ini = base.resolve(".android").resolve("avd").apply { mkdirs() }
        listOf("Pixel_8", "api-34").forEach { ini.resolve("$it.ini").createNewFile() }
        // runner 正常：名单来自 emulator 本尊
        val ok = AndroidEmulatorEnvironment(
            env = envOf("ANDROID_HOME" to root.absolutePath, "LOCALAPPDATA" to base.absolutePath),
            listAvds = { Pair(listOf("MyAVD", "MyAVD", "  "), null) },   // 空白行与重复要滤掉
        ).discover()
        assertEquals(listOf("MyAVD"), ok.avds)
        assertEquals("emulator -list-avds", ok.avdSource)
        // runner 挂了：错误上报、名单退回 .ini 扫描
        val bad = AndroidEmulatorEnvironment(
            env = envOf("ANDROID_HOME" to root.absolutePath, "LOCALAPPDATA" to base.absolutePath),
            listAvds = { Pair(emptyList(), "emulator -list-avds 超时（10s）") },
        ).discover()
        assertTrue(bad.errors.single().contains("超时"))
        assertEquals(listOf("Pixel_8", "api-34"), bad.avds)   // 排序后的目录扫描
        assertEquals("avd 目录扫描", bad.avdSource)
    }

    @Test
    fun `no sdk anywhere reports null root and all four missing`() {
        val r = AndroidEmulatorEnvironment(env = envOf("ANDROID_HOME" to "/no/such/path"), listAvds = noAvds).discover()
        assertNull(r.sdkRoot)
        assertEquals(listOf("emulator", "adb", "sdkmanager", "avdmanager"), r.missing)
        assertFalse(r.usable)
        assertTrue(r.avds.isEmpty() && r.avdSource == null)
    }
}
