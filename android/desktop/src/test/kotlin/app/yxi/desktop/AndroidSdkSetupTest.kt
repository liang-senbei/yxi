package app.yxi.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AndroidSdkComponentInstaller / AndroidAvdCreator 定向测试：runner 全注入假件，
 * **零真安装、零真创建、零真网络**（licenseTexts 走假 XML）。重点核对：
 * 白名单校验先于任何运行、许可只接受 sdkmanager 待接受名单的子集、
 * 取消/超时/失败有回执、--force 永不出现。
 */
class AndroidSdkSetupTest {

    /** 40 位许可哈希样例（真实 CI 常见值形状）。 */
    private val h1 = "8933bad161af4178b3adcb35d0d3b36d67392d67"
    private val h2 = "d56f5187479451eabf01fb78af6dfcb131a6481e"
    private val hUnknown = "0123456789abcdef0123456789abcdef01234567"

    private fun tempRoot() = Files.createTempDirectory("yxi-setup").toFile()

    private fun bat(root: File, name: String): File =
        root.resolve("cmdline-tools/latest/bin/$name").apply { parentFile.mkdirs(); writeText("@echo off\r\n") }

    /** 假 runner：按是否含 --licenses 分流返回，记录每次参数。 */
    private class ScriptedRunner(
        private val licensesOutput: String = "",
        private val licensesExit: Int = 0,
        var toolRun: ToolRun = ToolRun(0, ""),
    ) : (File, List<String>, Long, () -> Boolean) -> ToolRun {
        val calls = mutableListOf<List<String>>()
        override fun invoke(exe: File, args: List<String>, timeout: Long, cancelled: () -> Boolean): ToolRun {
            calls += args
            return if ("--licenses" in args) ToolRun(licensesExit, licensesOutput) else toolRun
        }
    }

    // ── installer：许可列表与安装 ────────────────────────────────────────────

    @Test
    fun `listLicenses extracts hashes from sdkmanager output and requires tool present`() {
        val root = tempRoot()
        val noBat = AndroidSdkComponentInstaller(sdkRoot = root)
        assertFalse(noBat.listLicenses().ok)
        assertTrue(noBat.listLicenses().reason!!.contains("找不到 sdkmanager"))

        bat(root, "sdkmanager.bat")
        val runner = ScriptedRunner(licensesOutput = "- $h1\n* $h2 (license)\n")
        val installer = AndroidSdkComponentInstaller(sdkRoot = root, runTool = runner)
        val r = installer.listLicenses()
        assertTrue(r.ok, r.reason)
        assertEquals(listOf(h1, h2), r.licenses)
        assertTrue(runner.calls.single().contains("--licenses"))
        assertTrue(runner.calls.single().last().startsWith("--sdk_root="))
    }

    @Test
    fun `install refuses empty or malformed inputs before any run`() {
        val root = tempRoot()
        bat(root, "sdkmanager.bat")
        val runner = ScriptedRunner()
        val installer = AndroidSdkComponentInstaller(sdkRoot = root, runTool = runner)

        assertFalse(installer.install(listOf("emulator"), emptySet()).ok)   // 一个许可都没同意
        assertTrue(runner.calls.isEmpty())
        assertFalse(installer.install(listOf("emulator;rm -rf x"), setOf(h1)).ok)   // 包名含空格=白名单外
        assertTrue(runner.calls.isEmpty())
        assertFalse(installer.install(listOf("emulator"), setOf("short-hash")).ok)  // 哈希不是 40 位十六进制
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `install only accepts hashes in sdkmanager current unaccepted list`() {
        val root = tempRoot()
        bat(root, "sdkmanager.bat")
        val runner = ScriptedRunner(licensesOutput = "$h1\n")
        val installer = AndroidSdkComponentInstaller(sdkRoot = root, runTool = runner)
        val r = installer.install(listOf("platform-tools"), setOf(h1, hUnknown))
        assertFalse(r.ok)
        assertTrue(r.reason!!.contains("待接受名单"))
        // 只核对、没安装、没写任何许可记录
        assertEquals(1, runner.calls.size)
        assertTrue(runner.calls.single().contains("--licenses"))
        assertFalse(root.resolve("licenses").exists())
    }

    @Test
    fun `install writes records only for accepted hashes and runs sdkmanager install`() {
        val root = tempRoot()
        bat(root, "sdkmanager.bat")
        val runner = ScriptedRunner(licensesOutput = "$h1\n$h2\n")
        val installer = AndroidSdkComponentInstaller(sdkRoot = root, runTool = runner)
        val r = installer.install(listOf("platform-tools", "emulator"), setOf(h1, h2))
        assertTrue(r.ok, r.reason)
        val licDir = root.resolve("licenses")
        assertEquals(setOf(h1, h2), licDir.listFiles()!!.map { it.name }.toSet())   // 记录=勾选集合，一个不多
        assertEquals("$h1\n", licDir.resolve(h1).readText())
        assertEquals(
            listOf("--install", "platform-tools", "emulator", "--sdk_root=${root.absolutePath}"),
            runner.calls[1],
        )
    }

    @Test
    fun `install surfaces failure and cancellation with receipts`() {
        val root = tempRoot()
        bat(root, "sdkmanager.bat")
        val fail = ScriptedRunner(licensesOutput = "$h1\n", toolRun = ToolRun(1, "done\nerror: something broke"))
        val rf = AndroidSdkComponentInstaller(sdkRoot = root, runTool = fail)
            .install(listOf("emulator"), setOf(h1))
        assertFalse(rf.ok)
        assertTrue(rf.reason!!.contains("退出码 1") && rf.reason!!.contains("something broke"))

        val cancel = ScriptedRunner(licensesOutput = "$h1\n", toolRun = ToolRun(-1, "", cancelled = true))
        val rc = AndroidSdkComponentInstaller(sdkRoot = root, runTool = cancel)
            .install(listOf("emulator"), setOf(h1))
        assertFalse(rc.ok)
        assertTrue(rc.reason!!.contains("取消"))
    }

    @Test
    fun `licenseTexts resolves per package refs from official xml and reports missing`() {
        val xml = """<sdk:sdk-repository xmlns:sdk="http://schemas.android.com/sdk/android/repo/repository2/03">
          <sdk:license id="android-sdk-license" type="text">许可全文（测试截略，官方 17KB）</sdk:license>
          <sdk:remotePackage path="emulator">
            <sdk:uses-license ref="android-sdk-license"/>
          </sdk:remotePackage>
        </sdk:sdk-repository>"""
        val installer = AndroidSdkComponentInstaller(sdkRoot = tempRoot(), fetchXml = { xml })
        val r = installer.licenseTexts(listOf("emulator", "system-images;android-35;google_apis;x86_64"))
        assertTrue(r.ok, r.reason)
        assertEquals(listOf("android-sdk-license"), r.docs.map { it.id })
        assertTrue(r.docs.single().text.contains("许可全文"))
        // system-images 不在 repository2-3（单独 sys-img 描述符），如实上报而不是装没看见
        assertEquals(listOf("system-images;android-35;google_apis;x86_64"), r.missing)
    }

    // ── avd creator ──────────────────────────────────────────────────────────

    @Test
    fun `create validates name image and profile before any run`() {
        val root = tempRoot()
        bat(root, "avdmanager.bat")
        val calls = mutableListOf<List<String>>()
        val runner = { _: File, args: List<String>, _: Long, _: () -> Boolean -> calls += args; ToolRun(0, "") }
        val creator = AndroidAvdCreator(sdkRoot = root, runTool = runner)
        assertFalse(creator.create("my avd", "system-images;android-35;google_apis;x86_64", "pixel_7").ok)   // 名字带空格
        assertFalse(creator.create("ok", "../escape", "pixel_7").ok)                                        // 镜像包名非法
        assertFalse(creator.create("ok", "system-images;android-35;google_apis;x86_64", "pixel 7").ok)      // 规格带空格
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `create runs avdmanager without force and reports duplicate name`() {
        val root = tempRoot()
        bat(root, "avdmanager.bat")
        val calls = mutableListOf<List<String>>()
        var run = ToolRun(0, "")
        val runner = { _: File, args: List<String>, _: Long, _: () -> Boolean -> calls += args; run }
        val creator = AndroidAvdCreator(sdkRoot = root, runTool = runner)
        assertTrue(creator.create("Pixel_Test", "system-images;android-35;google_apis;x86_64", "pixel_7").ok)
        assertEquals(
            listOf("create", "avd", "-n", "Pixel_Test", "-k", "system-images;android-35;google_apis;x86_64", "-d", "pixel_7"),
            calls.single(),
        )
        assertTrue(calls.single().none { it.equals("--force", ignoreCase = true) })   // 已有 AVD 不覆盖

        run = ToolRun(1, "Error: AVD \"Pixel_Test\" already exists.")
        assertFalse(creator.create("Pixel_Test", "system-images;android-35;google_apis;x86_64", "pixel_7").ok)
        assertTrue(calls.last().none { it.equals("--force", ignoreCase = true) })
        // 重名的可解释 reason 由 create 返回（contains 已存在），不靠 --force 硬闯
        assertEquals(2, calls.size)
    }
}
