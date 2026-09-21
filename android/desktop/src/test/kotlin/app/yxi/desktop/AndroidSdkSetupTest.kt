package app.yxi.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AndroidSdkComponentInstaller / AndroidAvdCreator 定向测试：runner/fetchXml 全注入假件，
 * **零真安装、零真创建、零真网络**。重点核对 AOSP License.java 真实口径：接受记录 =
 * `licenses/<license-id>` 多行 hash、hash 对**未 trim** 的解析文本原值取 SHA-1、追加格式
 * `"%n%s"`；consent 快照（同意时 hash 必须==安装时清单 hash，防旧条款换新文本）；勾选 id
 * 必须是所选包 uses-license 引用的子集；清单（含 sys-img 描述符）核对不了的包先拒；
 * 取消在写记录前有闸门；白名单先于任何运行；--force 永不出现。
 */
class AndroidSdkSetupTest {

    /** 许可文本 fixture（含首尾换行——hash 必须对未 trim 的原值算）。 */
    private val licText = "\nTerms and Conditions（测试截略）\n"

    /** sha1(licText 的 UTF-8 字节)，与 AOSP License.getLicenseHash 同算法，独立用 python 预先算得。 */
    private val licHash = "da4450d7d508269a726a5b99bcfb59aa2abec480"

    /** 与 licText 无关的另一条 40 位 hex（占位/篡改/过期快照探测用）。 */
    private val otherHash = "1111111111111111111111111111111111111111"

    private val licId = "android-sdk-license"

    /** 用户同意快照：勾选 licId 时看到的 hash。 */
    private val consent = mapOf(licId to licHash)

    /** 官方主清单形状的假 XML：两个包都引 android-sdk-license（不含 system-images）。 */
    private val repoXml: String =
        "<sdk:sdk-repository xmlns:sdk=\"http://schemas.android.com/sdk/android/repo/repository2/03\">" +
            "<sdk:license id=\"$licId\" type=\"text\">" + licText + "</sdk:license>" +
            listOf("platform-tools", "emulator").joinToString("") { p ->
                "<sdk:remotePackage path=\"$p\"><sdk:uses-license ref=\"$licId\"/></sdk:remotePackage>"
            } +
            "</sdk:sdk-repository>"

    /** 官方站点列表形状：android 与 android-wear 两个 sys-img 站点（相对 URL）。 */
    private val sitesXml: String =
        "<common:site-list xmlns:common=\"http://schemas.android.com/repository/android/sites-common/1\">" +
            "<site><displayName>Android System Images</displayName><url>sys-img/android/sys-img2-5.xml</url></site>" +
            "<site><displayName>Wear OS System Images</displayName><url>sys-img/android-wear/sys-img2-5.xml</url></site>" +
            "</common:site-list>"

    /** sys-img 描述符形状：36/35 两个稳定 x86_64 + 一个 arm64（滤掉）+ 一个 preview 通道（滤掉）。 */
    private val sysImgXml: String =
        "<sdk:sdk-sys-img xmlns:sdk=\"http://schemas.android.com/sdk/android/repo/sys-img2/05\">" +
            "<sdk:license id=\"$licId\" type=\"text\">" + licText + "</sdk:license>" +
            listOf(36 to "844217077", 35 to "720747116").joinToString("") { (api, size) ->
                "<sdk:remotePackage path=\"system-images;android-$api;default;x86_64\">" +
                    "<sdk:type-details><sdk:api-level>$api</sdk:api-level><sdk:tag><sdk:id>default</sdk:id></sdk:tag>" +
                    "<sdk:abi>x86_64</sdk:abi></sdk:type-details>" +
                    "<sdk:channelRef ref=\"channel-0\"/><sdk:uses-license ref=\"$licId\"/>" +
                    "<sdk:archives><sdk:archive><sdk:complete><sdk:size>$size</sdk:size></sdk:complete></sdk:archive></sdk:archives>" +
                    "</sdk:remotePackage>"
            } +
            "<sdk:remotePackage path=\"system-images;android-36;default;arm64-v8a\">" +
            "<sdk:type-details><sdk:api-level>36</sdk:api-level><sdk:tag><sdk:id>default</sdk:id></sdk:tag>" +
            "<sdk:abi>arm64-v8a</sdk:abi></sdk:type-details>" +
            "<sdk:channelRef ref=\"channel-0\"/><sdk:uses-license ref=\"$licId\"/></sdk:remotePackage>" +
            "<sdk:remotePackage path=\"system-images;android-37;default;x86_64\">" +
            "<sdk:type-details><sdk:api-level>37</sdk:api-level><sdk:tag><sdk:id>default</sdk:id></sdk:tag>" +
            "<sdk:abi>x86_64</sdk:abi></sdk:type-details>" +
            "<sdk:channelRef ref=\"channel-1\"/><sdk:uses-license ref=\"$licId\"/></sdk:remotePackage>" +
            "</sdk:sdk-sys-img>"

    /** 按 URL 分流的假 fetchXml：站点列表 / sys-img 描述符 / 主清单。 */
    private fun dispatchFetch(repo: String = repoXml, sites: String = sitesXml, sysimg: String = sysImgXml): (String) -> String =
        { url ->
            when {
                "addons_list" in url -> sites
                "sys-img" in url -> sysimg
                else -> repo
            }
        }

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

    private fun installerWith(root: File, runner: ScriptedRunner, fetch: (String) -> String = dispatchFetch()) =
        AndroidSdkComponentInstaller(sdkRoot = root, runTool = runner, fetchXml = fetch)

    // ── 许可清单与接受记录 ────────────────────────────────────────────────────

    @Test
    fun `licenseTexts keeps raw text and hashes match License java algorithm`() {
        val installer = AndroidSdkComponentInstaller(sdkRoot = tempRoot(), fetchXml = dispatchFetch())
        val r = installer.licenseTexts(listOf("platform-tools", "emulator"))
        assertTrue(r.ok, r.reason ?: "<无 reason>")
        val doc = r.docs.single()
        assertEquals(licId, doc.id)
        assertEquals(licText, doc.text)                      // 解析原值，绝不能 trim
        assertEquals(licHash, doc.hash)                      // 独立预算的 sha1，钉死算法
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun `licenseTexts falls back to sys-img descriptor for packages missing from main manifest`() {
        val installer = AndroidSdkComponentInstaller(sdkRoot = tempRoot(), fetchXml = dispatchFetch())
        // system-images 不在主清单（单独 sys-img 描述符），fallback 后应解析成功且 missing 清空
        val r = installer.licenseTexts(listOf("platform-tools", "system-images;android-36;default;x86_64"))
        assertTrue(r.ok, r.reason ?: "<无 reason>")
        assertEquals(listOf(licId), r.docs.map { it.id })
        assertEquals(licHash, r.docs.single().hash)          // 描述符内嵌同一份全文，hash 一致
        assertTrue(r.missing.isEmpty())
        // 描述符里也没有的包：如实 missing，不给假绿灯
        val bad = installer.licenseTexts(listOf("system-images;android-99;nope;x86"))
        assertTrue(bad.ok, bad.reason ?: "<无 reason>")
        assertEquals(listOf("system-images;android-99;nope;x86"), bad.missing)
    }

    @Test
    fun `listPendingLicenses marks accepted only when record file contains the hash`() {
        val root = tempRoot()
        val installer = AndroidSdkComponentInstaller(sdkRoot = root, runTool = ScriptedRunner(), fetchXml = dispatchFetch())

        val fresh = installer.listPendingLicenses(listOf("platform-tools"))
        assertTrue(fresh.ok, fresh.reason ?: "<无 reason>")
        assertFalse(fresh.statuses.single().alreadyAccepted)   // 无记录文件 = 未接受

        root.resolve("licenses/$licId").apply { parentFile.mkdirs(); writeText("\n$otherHash\n") }
        // 文件里只有别的 hash：仍是未接受（同一 id 多版文本逐行累积的口径）
        assertFalse(installer.listPendingLicenses(listOf("platform-tools")).statuses.single().alreadyAccepted)

        root.resolve("licenses/$licId").appendText("$licHash\n")
        assertTrue(installer.listPendingLicenses(listOf("platform-tools")).statuses.single().alreadyAccepted)
    }

    // ── 安装闸门 ─────────────────────────────────────────────────────────────

    @Test
    fun `install refuses empty or malformed inputs before any run`() {
        val root = tempRoot()
        bat(root, "sdkmanager.bat")
        val runner = ScriptedRunner()
        val installer = installerWith(root, runner)

        assertFalse(installer.install(listOf("platform-tools"), emptySet()).ok)   // 一个许可都没同意
        assertTrue(runner.calls.isEmpty())
        assertFalse(installer.install(listOf("platform-tools;rm -rf x"), setOf(licId), consent).ok)   // 包名白名单外
        assertTrue(runner.calls.isEmpty())
        assertFalse(installer.install(listOf("platform-tools"), setOf("../escape"), consent).ok)      // 许可 ID 白名单外
        assertTrue(runner.calls.isEmpty())
        assertFalse(installer.install(listOf("platform-tools"), setOf(licId)).ok)                     // 没带 consent 快照
        assertTrue(runner.calls.isEmpty())
        assertFalse(root.resolve("licenses").exists())   // 全部在校验阶段拒掉，没写任何记录
    }

    @Test
    fun `install only accepts ids referenced by chosen packages`() {
        val root = tempRoot()
        bat(root, "sdkmanager.bat")
        val runner = ScriptedRunner()
        val installer = installerWith(root, runner)

        // 勾了与所选组件无关/不存在的许可：拒绝，不写记录、不跑 sdkmanager
        val r = installer.install(listOf("platform-tools"), setOf("android-sdk-preview-license"), mapOf("android-sdk-preview-license" to licHash))
        assertFalse(r.ok)
        assertTrue(r.reason!!.contains("许可清单"))
        assertTrue(runner.calls.isEmpty())
        assertFalse(root.resolve("licenses").exists())
    }

    @Test
    fun `install rejects stale consent snapshot and unverifiable packages before writing`() {
        val root = tempRoot()
        bat(root, "sdkmanager.bat")
        val runner = ScriptedRunner()
        val installer = installerWith(root, runner)

        // 快照 hash 与当前清单不一致（官方换条款 / 用户拿别的文本的 hash 冒充）：拒
        val stale = installer.install(listOf("platform-tools"), setOf(licId), mapOf(licId to otherHash))
        assertFalse(stale.ok)
        assertTrue(stale.reason!!.contains("不一致"))
        assertTrue(runner.calls.isEmpty())
        assertFalse(root.resolve("licenses").exists())

        // 清单（含 sys-img 描述符）里都不存在的包：许可无从核对，先拒
        val unverifiable = installer.install(listOf("system-images;android-99;nope;x86"), setOf(licId), consent)
        assertFalse(unverifiable.ok)
        assertTrue(unverifiable.reason!!.contains("无法核对"))
        assertTrue(runner.calls.isEmpty())
        assertFalse(root.resolve("licenses").exists())
    }

    @Test
    fun `install refuses after review when cancelled before writing records`() {
        val root = tempRoot()
        bat(root, "sdkmanager.bat")
        val runner = ScriptedRunner()
        val installer = installerWith(root, runner)

        val r = installer.install(listOf("platform-tools"), setOf(licId), consent, isCancelled = { true })
        assertFalse(r.ok)
        assertTrue(r.reason!!.contains("取消") && r.reason.contains("未写任何许可记录"))
        assertTrue(runner.calls.isEmpty())
        assertFalse(root.resolve("licenses").exists())
    }

    @Test
    fun `install writes license id record in License java format and runs sdkmanager`() {
        val root = tempRoot()
        bat(root, "sdkmanager.bat")
        val runner = ScriptedRunner()
        val installer = installerWith(root, runner)

        val r = installer.install(listOf("platform-tools", "emulator"), setOf(licId), consent)
        assertTrue(r.ok, r.reason ?: "<无 reason>")
        val licDir = root.resolve("licenses")
        // 记录按 license-id 命名（不是 licenses/<hash>），内容是 License.setAccepted 的 "%n%s" 格式
        assertEquals(listOf(licId), licDir.listFiles()!!.map { it.name })
        // License.setAccepted 写 String.format("%n%s", hash)——前导换行、无尾换行
        assertEquals("\n$licHash", licDir.resolve(licId).readText())
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
        val installer = installerWith(root, runner)
        root.resolve("licenses/$licId").apply { parentFile.mkdirs(); writeText("\n$otherHash") }

        assertTrue(installer.install(listOf("platform-tools"), setOf(licId), consent).ok)
        val afterFirst = root.resolve("licenses/$licId").readText()
        assertEquals("\n$otherHash\n$licHash", afterFirst)   // 只追加、旧记录原样保留
        assertTrue(installer.install(listOf("platform-tools"), setOf(licId), consent).ok)
        assertEquals(afterFirst, root.resolve("licenses/$licId").readText())   // 幂等：已含就不重写
    }

    @Test
    fun `install surfaces failure cancellation and unaccepted output with receipts`() {
        val root = tempRoot()
        bat(root, "sdkmanager.bat")
        val licIds = setOf(licId)

        val fail = ScriptedRunner(ToolRun(1, "done\nerror: something broke"))
        val rf = installerWith(root, fail).install(listOf("platform-tools"), licIds, consent)
        assertFalse(rf.ok)
        assertTrue(rf.reason!!.contains("退出码 1") && rf.reason.contains("something broke"))

        // sdkmanager 报「were not accepted」（如依赖包的许可不在勾选里）：给专门的可解释文案
        val unaccepted = ScriptedRunner(ToolRun(1, "The following packages can not be installed since their licenses were not accepted:\n  emulator"))
        val ru = installerWith(root, unaccepted).install(listOf("platform-tools"), licIds, consent)
        assertFalse(ru.ok)
        assertTrue(ru.reason!!.contains("未全部接受") && ru.reason.contains("未安装"))

        val cancel = ScriptedRunner(ToolRun(-1, "", cancelled = true))
        val rc = installerWith(root, cancel).install(listOf("platform-tools"), licIds, consent)
        assertFalse(rc.ok)
        assertTrue(rc.reason!!.contains("取消"))

        val timeout = ScriptedRunner(ToolRun(-1, "", timedOut = true))
        val rt = installerWith(root, timeout).install(listOf("platform-tools"), licIds, consent)
        assertFalse(rt.ok)
        assertTrue(rt.reason!!.contains("超时"))
    }

    // ── 系统镜像列表 ──────────────────────────────────────────────────────────

    @Test
    fun `systemImages lists stable matching abi sorted by api desc from official descriptors`() {
        val installer = AndroidSdkComponentInstaller(sdkRoot = tempRoot(), fetchXml = dispatchFetch())
        val r = installer.systemImages()
        assertTrue(r.ok, r.reason ?: "<无 reason>")
        // 只有稳定通道 + x86_64 入选：arm64-v8a 与 channel-1 的 37 被滤掉；新 API 在前
        assertEquals(
            listOf("system-images;android-36;default;x86_64", "system-images;android-35;default;x86_64"),
            r.images.map { it.path },
        )
        val top = r.images.first()
        assertEquals(36, top.apiLevel)
        assertEquals("x86_64", top.abi)
        assertEquals("default", top.tagId)
        assertEquals(844217077L, top.sizeBytes)
        assertEquals(listOf(licId), top.licenseIds)
    }

    @Test
    fun `systemImages validates vendor and reports unknown vendor`() {
        val installer = AndroidSdkComponentInstaller(sdkRoot = tempRoot(), fetchXml = dispatchFetch())
        // 白名单外（会拼进 fetch URL）：直接拒，不发请求
        assertFalse(installer.systemImages(vendor = "../etc").ok)
        // 站点列表里没有的 vendor：可解释 reason
        val r = installer.systemImages(vendor = "nonexistent")
        assertFalse(r.ok)
        assertTrue(r.reason!!.contains("nonexistent"))
    }

    // ── avd creator ──────────────────────────────────────────────────────────

    @Test
    fun `create validates name image and profile before any run`() {
        val root = tempRoot()
        bat(root, "avdmanager.bat")
        val calls = mutableListOf<List<String>>()
        val runner = { _: File, args: List<String>, _: Long, _: () -> Boolean -> calls += args; ToolRun(0, "") }
        val creator = AndroidAvdCreator(sdkRoot = root, runTool = runner)
        assertFalse(creator.create("my avd", "system-images;android-36;default;x86_64", "pixel_7").ok)   // 名字带空格
        assertFalse(creator.create("ok", "../escape", "pixel_7").ok)                                        // 镜像包名非法
        assertFalse(creator.create("ok", "system-images;android-36;default;x86_64", "pixel 7").ok)      // 规格带空格
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
        assertTrue(creator.create("Pixel_Test", "system-images;android-36;default;x86_64", "pixel_7").ok)
        assertEquals(
            listOf("create", "avd", "-n", "Pixel_Test", "-k", "system-images;android-36;default;x86_64", "-d", "pixel_7"),
            calls.single(),
        )
        assertTrue(calls.single().none { it.equals("--force", ignoreCase = true) })   // 已有 AVD 不覆盖

        run = ToolRun(1, "Error: AVD \"Pixel_Test\" already exists.")
        assertFalse(creator.create("Pixel_Test", "system-images;android-36;default;x86_64", "pixel_7").ok)
        assertTrue(calls.last().none { it.equals("--force", ignoreCase = true) })
        // 重名的可解释 reason 由 create 返回（contains 已存在），不靠 --force 硬闯
        assertEquals(2, calls.size)
    }
}
