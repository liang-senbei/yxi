package app.yxi.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AndroidSdkComponentInstaller / AndroidAvdCreator 定向测试：runner 全注入假件，
 * **零真安装、零真创建、零真网络**（许可解析走假 XML）。重点核对 AOSP License.java
 * 真实口径：接受记录 = `licenses/<license-id>` 多行 hash、hash 对**未 trim** 的
 * 解析文本原值取 SHA-1、追加格式 `"%n%s"`（首行前也有换行）、勾选 id 必须是所选包
 * uses-license 引用的子集；以及白名单校验先于任何运行、取消/超时/失败有回执、
 * --force 永不出现。
 */
class AndroidSdkSetupTest {

    /** 许可文本 fixture（含首尾换行——hash 必须对未 trim 的原值算）。 */
    private val licText = "\nTerms and Conditions（测试截略）\n"

    /** sha1(licText 的 UTF-8 字节)，与 AOSP License.getLicenseHash 同算法，独立用 python 预先算得。 */
    private val licHash = "da4450d7d508269a726a5b99bcfb59aa2abec480"

    /** 与 licText 无关的另一条 40 位 hex（占位/篡改探测用）。 */
    private val otherHash = "1111111111111111111111111111111111111111"

    /** 官方 XML 形状的假清单：两个包都引 android-sdk-license。 */
    private fun fakeXml(vararg packagePaths: String): String =
        "<sdk:sdk-repository xmlns:sdk=\"http://schemas.android.com/sdk/android/repo/repository2/03\">" +
            "<sdk:license id=\"android-sdk-license\" type=\"text\">" + licText + "</sdk:license>" +
            packagePaths.joinToString("") { p ->
                "<sdk:remotePackage path=\"$p\"><sdk:uses-license ref=\"android-sdk-license\"/></sdk:remotePackage>"
            } +
            "</sdk:sdk-repository>"

    private fun tempRoot() = Files.createTempDirectory("yxi-setup").toFile()

    private fun bat(root: File, name: String): File =
        root.resolve("cmdline-tools/latest/bin/$name").apply { parentFile.mkdirs(); writeText("@echo off\r\n") }

    /** 假 runner：记录每次参数，返回预设 ToolRun。 */
    private class ScriptedRunner(
        val toolRun: ToolRun = ToolRun(0, ""),
    ) : (File, List<String>, Long, () -> Boolean) -> ToolRun {
        val calls = mutableListOf<List<String>>()
        override fun invoke(exe: File, args: List<String>, timeout: Long, cancelled: () -> Boolean): ToolRun {
            calls += args
            return toolRun
        }
    }

    private fun installerWith(root: File, runner: ScriptedRunner, xml: String) =
        AndroidSdkComponentInstaller(sdkRoot = root, runTool = runner, fetchXml = { xml })

    // ── 许可清单与接受记录 ────────────────────────────────────────────────────

    @Test
    fun `licenseTexts keeps raw text and hashes match License java algorithm`() {
        val installer = AndroidSdkComponentInstaller(sdkRoot = tempRoot(), fetchXml = { fakeXml("platform-tools", "emulator") })
        val r = installer.licenseTexts(listOf("platform-tools", "emulator", "system-images;android-35;google_apis;x86_64"))
        assertTrue(r.ok, r.reason)
        val doc = r.docs.single()
        assertEquals("android-sdk-license", doc.id)
        assertEquals(licText, doc.text)                      // 解析原值，绝不能 trim
        assertEquals(licHash, doc.hash)                      // 独立预算的 sha1，钉死算法
        // system-images 不在 repository2-3（单独 sys-img 描述符），如实上报而不是装没看见
        assertEquals(listOf("system-images;android-35;google_apis;x86_64"), r.missing)
    }

    @Test
    fun `listPendingLicenses marks accepted only when record file contains the hash`() {
        val root = tempRoot()
        val xml = fakeXml("platform-tools")
        val installer = AndroidSdkComponentInstaller(sdkRoot = root, runTool = ScriptedRunner(), fetchXml = { xml })

        val fresh = installer.listPendingLicenses(listOf("platform-tools"))
        assertTrue(fresh.ok, fresh.reason)
        assertFalse(fresh.statuses.single().alreadyAccepted)   // 无记录文件 = 未接受

        root.resolve("licenses/android-sdk-license").apply { parentFile.mkdirs(); writeText("\n$otherHash\n") }
        // 文件里只有别的 hash：仍是未接受（同一 id 多版文本逐行累积的口径）
        assertFalse(installer.listPendingLicenses(listOf("platform-tools")).statuses.single().alreadyAccepted)

        root.resolve("licenses/android-sdk-license").appendText("$licHash\n")
        assertTrue(installer.listPendingLicenses(listOf("platform-tools")).statuses.single().alreadyAccepted)
    }

    @Test
    fun `install refuses empty or malformed inputs before any run`() {
        val root = tempRoot()
        bat(root, "sdkmanager.bat")
        val runner = ScriptedRunner()
        val installer = installerWith(root, runner, fakeXml("platform-tools"))

        assertFalse(installer.install(listOf("platform-tools"), emptySet()).ok)   // 一个许可都没同意
        assertTrue(runner.calls.isEmpty())
        assertFalse(installer.install(listOf("platform-tools;rm -rf x"), setOf("android-sdk-license")).ok)   // 包名白名单外
        assertTrue(runner.calls.isEmpty())
        assertFalse(installer.install(listOf("platform-tools"), setOf("../escape")).ok)                      // 许可 ID 白名单外
        assertTrue(runner.calls.isEmpty())
        assertFalse(root.resolve("licenses").exists())   // 全部在校验阶段拒掉，没写任何记录
    }

    @Test
    fun `install only accepts ids referenced by chosen packages`() {
        val root = tempRoot()
        bat(root, "sdkmanager.bat")
        val runner = ScriptedRunner()
        val installer = installerWith(root, runner, fakeXml("platform-tools"))

        // 勾了与所选组件无关/不存在的许可：拒绝，不写记录、不跑 sdkmanager
        val r = installer.install(listOf("platform-tools"), setOf("android-sdk-preview-license"))
        assertFalse(r.ok)
        assertTrue(r.reason!!.contains("许可清单"))
        assertTrue(runner.calls.isEmpty())
        assertFalse(root.resolve("licenses").exists())
    }

    @Test
    fun `install writes license id record in License java format and runs sdkmanager`() {
        val root = tempRoot()
        bat(root, "sdkmanager.bat")
        val runner = ScriptedRunner()
        val installer = installerWith(root, runner, fakeXml("platform-tools", "emulator"))

        val r = installer.install(listOf("platform-tools", "emulator"), setOf("android-sdk-license"))
        assertTrue(r.ok, r.reason)
        val licDir = root.resolve("licenses")
        // 记录按 license-id 命名（不是 licenses/<hash>），内容是 License.setAccepted 的 "%n%s" 格式
        assertEquals(listOf("android-sdk-license"), licDir.listFiles()!!.map { it.name })
        // License.setAccepted 写 String.format("%n%s", hash)——前导换行、无尾换行
        assertEquals("\n$licHash", licDir.resolve("android-sdk-license").readText())
        assertFalse(licDir.resolve(licHash).exists())        // 旧错误格式（以 hash 为文件名）不得再出现
        assertEquals(
            listOf("--install", "platform-tools", "emulator", "--sdk_root=${root.absolutePath}"),
            runner.calls.single(),
        )
    }

    @Test
    fun `install appends hash without clobbering existing record and is idempotent`() {
        val root = tempRoot()
        bat(root, "sdkmanager.bat")
        val runner = ScriptedRunner()
        val installer = installerWith(root, runner, fakeXml("platform-tools"))
        root.resolve("licenses/android-sdk-license").apply { parentFile.mkdirs(); writeText("\n$otherHash") }

        assertTrue(installer.install(listOf("platform-tools"), setOf("android-sdk-license")).ok)
        val afterFirst = root.resolve("licenses/android-sdk-license").readText()
        assertEquals("\n$otherHash\n$licHash", afterFirst)   // 只追加、旧记录原样保留
        assertTrue(installer.install(listOf("platform-tools"), setOf("android-sdk-license")).ok)
        assertEquals(afterFirst, root.resolve("licenses/android-sdk-license").readText())   // 幂等：已含就不重写
    }

    @Test
    fun `install surfaces failure cancellation and unaccepted output with receipts`() {
        val root = tempRoot()
        bat(root, "sdkmanager.bat")
        val licIds = setOf("android-sdk-license")

        val fail = ScriptedRunner(ToolRun(1, "done\nerror: something broke"))
        val rf = installerWith(root, fail, fakeXml("platform-tools")).install(listOf("platform-tools"), licIds)
        assertFalse(rf.ok)
        assertTrue(rf.reason!!.contains("退出码 1") && rf.reason.contains("something broke"))

        // sdkmanager 报「were not accepted」（如 system-images 这类清单外包）：给专门的可解释文案
        val unaccepted = ScriptedRunner(ToolRun(1, "The following packages can not be installed since their licenses were not accepted:\n  system-images"))
        val ru = installerWith(root, unaccepted, fakeXml("platform-tools")).install(listOf("platform-tools"), licIds)
        assertFalse(ru.ok)
        assertTrue(ru.reason!!.contains("未全部接受") && ru.reason.contains("未安装"))

        val cancel = ScriptedRunner(ToolRun(-1, "", cancelled = true))
        val rc = installerWith(root, cancel, fakeXml("platform-tools")).install(listOf("platform-tools"), licIds)
        assertFalse(rc.ok)
        assertTrue(rc.reason!!.contains("取消"))

        val timeout = ScriptedRunner(ToolRun(-1, "", timedOut = true))
        val rt = installerWith(root, timeout, fakeXml("platform-tools")).install(listOf("platform-tools"), licIds)
        assertFalse(rt.ok)
        assertTrue(rt.reason!!.contains("超时"))
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
