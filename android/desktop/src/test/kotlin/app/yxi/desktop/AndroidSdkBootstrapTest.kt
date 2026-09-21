package app.yxi.desktop

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AndroidSdkBootstrap 定向测试：仓库清单用假 XML 字符串注入、下载用假字节注入，
 * **零真网络、零真下载**；zip 在测试里现做（官方包同款顶层 cmdline-tools/）。
 * 清单元数据按真实仓库口径：command-line tools 的 checksum 是 **sha1**（algorithm
 * 属性），老清单是 type 属性 + sha256——两种都覆盖，另覆盖坏长度/坏算法被跳过。
 */
class AndroidSdkBootstrapTest {

    private fun digest(bytes: ByteArray, algo: String = "SHA-1") =
        MessageDigest.getInstance(algo).digest(bytes).joinToString("") { "%02x".format(it) }

    /** 现做一个迷你 command-line tools zip（顶层目录 cmdline-tools/，和官方包一致）。 */
    private fun toolsZip(vararg extra: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            listOf(
                "cmdline-tools/" to ByteArray(0),
                "cmdline-tools/bin/" to ByteArray(0),
                "cmdline-tools/bin/sdkmanager.bat" to "@echo off\r\n".toByteArray(),
                "cmdline-tools/NOTICE.txt" to "notices".toByteArray(),
            ).forEach { (name, data) ->
                z.putNextEntry(ZipEntry(name)); if (data.isNotEmpty()) z.write(data); z.closeEntry()
            }
            extra.forEach { (name, data) ->
                z.putNextEntry(ZipEntry(name)); if (data.isNotEmpty()) z.write(data); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** 假下载器：把给定字节写进 dest，走 impl 的失败清理路径；cancelMidway 置位时写一半就报已取消。 */
    private fun fakeDownload(
        bytes: ByteArray,
        cancelMidway: AtomicBoolean? = null,
    ): (String, File, Long, (Long, Long) -> Unit, () -> Boolean) -> String? =
        { url, dest, _total, onProgress, _cancelled ->
            var reason: String? = null
            if (!url.startsWith(AndroidSdkBootstrap.REPO_BASE)) {
                reason = "测试假下载器只认官方源"
            } else {
                dest.parentFile.mkdirs()
                dest.outputStream().use { out ->
                    val half = bytes.size / 2
                    out.write(bytes, 0, half)
                    onProgress(half.toLong(), bytes.size.toLong())
                    if (cancelMidway?.get() == true) reason = "已取消"
                    else {
                        out.write(bytes, half, bytes.size - half)
                        onProgress(bytes.size.toLong(), bytes.size.toLong())
                    }
                }
            }
            reason
        }

    private fun manifest(
        zip: ByteArray,
        algo: String = "SHA-1",
        sha: String = digest(zip, algo),
        sizeBytes: Long = zip.size.toLong(),
        url: String = AndroidSdkBootstrap.REPO_BASE + "commandlinetools-win-13114758_latest.zip",
    ) = AndroidSdkBootstrap.Manifest(
        version = "latest",
        url = url,
        sizeBytes = sizeBytes,
        checksumAlgorithm = algo,
        checksum = sha,
        licenses = listOf("仅供展示的许可全文"),
    )

    // ── inspect：仓库清单解析 ────────────────────────────────────────────────

    @Test
    fun `inspect picks stable windows commandlinetools with sha1 metadata and license text`() {
        val xml = """<?xml version="1.0"?>
<sdk:sdk-repository xmlns:sdk="http://schemas.android.com/sdk/android/repo/repository2/3">
  <sdk:license id="android-sdk-license" type="bean">许可全文在这里，只展示。</sdk:license>
  <sdk:channel id="channel-0"/><sdk:channel id="channel-1"/>
  <sdk:remotePackage path="cmdline-tools;19.0">
    <sdk:channelRef ref="channel-0"/>
    <sdk:archives><sdk:archive>
      <sdk:complete><sdk:url>commandlinetools-win-11076708_19.0.zip</sdk:url><sdk:size>111</sdk:size>
        <sdk:checksum type="sha256">${"cd".repeat(32)}</sdk:checksum></sdk:complete>
      <sdk:host-os>windows</sdk:host-os>
    </sdk:archive></sdk:archives>
    <sdk:uses-license ref="android-sdk-license"/>
  </sdk:remotePackage>
  <sdk:remotePackage path="cmdline-tools;latest">
    <sdk:channelRef ref="channel-0"/>
    <sdk:archives>
      <sdk:archive>
        <sdk:complete><sdk:url>commandlinetools-win-13114758_latest.zip</sdk:url><sdk:size>152092487</sdk:size>
          <sdk:checksum algorithm="sha1">${"ab".repeat(20)}</sdk:checksum></sdk:complete>
        <sdk:host-os>windows</sdk:host-os>
      </sdk:archive>
      <sdk:archive>
        <sdk:complete><sdk:url>commandlinetools-linux-13114758_latest.zip</sdk:url><sdk:size>100</sdk:size>
          <sdk:checksum algorithm="sha1">${"ef".repeat(20)}</sdk:checksum></sdk:complete>
        <sdk:host-os>linux</sdk:host-os>
      </sdk:archive>
    </sdk:archives>
    <sdk:uses-license ref="android-sdk-license"/>
  </sdk:remotePackage>
  <sdk:remotePackage path="cmdline-tools;21.0">
    <sdk:channelRef ref="channel-1"/>
    <sdk:archives><sdk:archive>
      <sdk:complete><sdk:url>commandlinetools-win-preview.zip</sdk:url><sdk:size>100</sdk:size>
        <sdk:checksum algorithm="sha1">${"ab".repeat(20)}</sdk:checksum></sdk:complete>
      <sdk:host-os>windows</sdk:host-os>
    </sdk:archive></sdk:archives>
  </sdk:remotePackage>
</sdk:sdk-repository>"""
        var askedUrl: String? = null
        val r = AndroidSdkBootstrap(fetchXml = { u -> askedUrl = u; xml }).inspect()
        assertTrue(r.ok, r.reason)
        assertEquals(AndroidSdkBootstrap.REPO_URL, askedUrl)
        val m = r.manifest!!
        assertEquals("latest", m.version)   // latest 别名优先于 19.0；channel-1 的 21.0 不进候选
        assertEquals(AndroidSdkBootstrap.REPO_BASE + "commandlinetools-win-13114758_latest.zip", m.url)  // 相对 url 拼回基址
        assertEquals(152092487L, m.sizeBytes)
        assertEquals("SHA-1", m.checksumAlgorithm)   // 官方真实口径：algorithm 属性 + sha1
        assertEquals("ab".repeat(20), m.checksum)
        assertEquals(listOf("许可全文在这里，只展示。"), m.licenses)
    }

    @Test
    fun `inspect falls back to highest stable version reading legacy type attr sha256`() {
        val xml = """<sdk:sdk-repository xmlns:sdk="http://schemas.android.com/sdk/android/repo/repository2/3">
          <sdk:channel id="channel-0"/>
          <sdk:remotePackage path="cmdline-tools;19.0">
            <sdk:channelRef ref="channel-0"/>
            <sdk:archives><sdk:archive>
              <sdk:complete><sdk:url>commandlinetools-win-11076708_19.0.zip</sdk:url><sdk:size>111</sdk:size>
                <sdk:checksum type="sha256">${"cd".repeat(32)}</sdk:checksum></sdk:complete>
              <sdk:host-os>windows</sdk:host-os>
            </sdk:archive></sdk:archives>
          </sdk:remotePackage>
        </sdk:sdk-repository>"""
        val r = AndroidSdkBootstrap(fetchXml = { xml }).inspect()
        assertTrue(r.ok, r.reason)
        assertEquals("19.0", r.manifest!!.version)
        assertEquals("SHA-256", r.manifest!!.checksumAlgorithm)
        assertEquals(AndroidSdkBootstrap.REPO_BASE + "commandlinetools-win-11076708_19.0.zip", r.manifest!!.url)
    }

    @Test
    fun `inspect skips candidates whose checksum length or algorithm is bogus`() {
        val xml = """<sdk:sdk-repository xmlns:sdk="http://schemas.android.com/sdk/android/repo/repository2/3">
          <sdk:channel id="channel-0"/>
          <sdk:remotePackage path="cmdline-tools;latest">
            <sdk:channelRef ref="channel-0"/>
            <sdk:archives><sdk:archive>
              <sdk:complete><sdk:url>commandlinetools-win-x.zip</sdk:url><sdk:size>1</sdk:size>
                <sdk:checksum algorithm="sha1">${"ab".repeat(32)}</sdk:checksum></sdk:complete>
              <sdk:host-os>windows</sdk:host-os>
            </sdk:archive></sdk:archives>
          </sdk:remotePackage>
        </sdk:sdk-repository>"""
        // sha1 名义但 64 位十六进制：长度对不上 = 元数据不可信，候选必须被跳过
        val r = AndroidSdkBootstrap(fetchXml = { xml }).inspect()
        assertFalse(r.ok)
        assertTrue(r.reason!!.contains("没有 Windows 稳定版"))
        assertNull(r.manifest)
    }

    @Test
    fun `inspect explains fetch failures and malformed xml`() {
        val bad = AndroidSdkBootstrap(fetchXml = { throw java.io.IOException("连不上 dl.google.com") }).inspect()
        assertFalse(bad.ok)
        assertTrue(bad.reason!!.contains("获取仓库清单失败") && bad.reason!!.contains("连不上"))
        val ugly = AndroidSdkBootstrap(fetchXml = { "<not-xml" }).inspect()
        assertFalse(ugly.ok)
        assertTrue(ugly.reason!!.contains("解析"))
        assertNull(ugly.manifest)
    }

    // ── install：下载 → 校验 → 解压 → 原子发布 ─────────────────────────────

    @Test
    fun `install refuses to overwrite existing target and never downloads`() {
        val root = Files.createTempDirectory("yxi-sdk").toFile()
        val marker = root.resolve("cmdline-tools/latest/bin/sdkmanager.bat")
        marker.parentFile.mkdirs(); marker.writeText("已装好的")
        var downloads = 0
        val b = AndroidSdkBootstrap(
            sdkRoot = root,
            stagingDir = Files.createTempDirectory("yxi-stage").toFile(),
            download = { _, _, _, _, _ -> downloads++; null },
        )
        val r = b.install(manifest(ByteArray(0)))
        assertFalse(r.ok)
        assertTrue(r.reason!!.contains("已存在"))
        assertEquals(0, downloads)
        assertEquals("已装好的", marker.readText())   // 原文件一根手指都没碰
    }

    @Test
    fun `install refuses non official url before touching anything`() {
        val root = Files.createTempDirectory("yxi-sdk").toFile()
        val staging = Files.createTempDirectory("yxi-stage").toFile()
        var downloads = 0
        val b = AndroidSdkBootstrap(
            sdkRoot = root,
            stagingDir = staging,
            download = { _, _, _, _, _ -> downloads++; null },
        )
        val r = b.install(manifest(ByteArray(0), url = "http://evil.example/tools.zip"))
        assertFalse(r.ok)
        assertTrue(r.reason!!.contains("官方"))
        assertEquals(0, downloads)
        assertTrue(staging.listFiles().isNullOrEmpty())
    }

    @Test
    fun `install downloads verifies sha1 extracts and lands at cmdline-tools latest with staging cleaned`() {
        val zip = toolsZip()
        val root = Files.createTempDirectory("yxi-sdk").toFile()
        val staging = Files.createTempDirectory("yxi-stage").toFile()
        var lastProgress: Pair<Long, Long>? = null
        val b = AndroidSdkBootstrap(sdkRoot = root, stagingDir = staging, download = fakeDownload(zip))
        val r = b.install(manifest(zip), onProgress = { done, total -> lastProgress = done to total })
        assertTrue(r.ok, r.reason)
        val installed = root.resolve("cmdline-tools/latest/bin/sdkmanager.bat")
        assertTrue(installed.isFile)
        assertEquals("@echo off\r\n", installed.readText())
        assertEquals(zip.size.toLong(), lastProgress?.second)   // 进度走到了总大小
        assertTrue(staging.listFiles().isNullOrEmpty(), "暂存目录装完应清空")
    }

    @Test
    fun `install rejects downloaded bytes exceeding manifest size and cleans staging`() {
        val zip = toolsZip()
        val root = Files.createTempDirectory("yxi-sdk").toFile()
        val staging = Files.createTempDirectory("yxi-stage").toFile()
        val b = AndroidSdkBootstrap(sdkRoot = root, stagingDir = staging, download = fakeDownload(zip))
        val r = b.install(manifest(zip, sizeBytes = zip.size - 10L))
        assertFalse(r.ok)
        assertTrue(r.reason!!.contains("超出清单大小"))
        assertFalse(root.resolve("cmdline-tools/latest").exists())
        assertTrue(staging.listFiles().isNullOrEmpty(), "超大的下载内容不能留在暂存目录")
    }

    @Test
    fun `install rejects checksum mismatch cleans staging and leaves target absent`() {
        val zip = toolsZip()
        val root = Files.createTempDirectory("yxi-sdk").toFile()
        val staging = Files.createTempDirectory("yxi-stage").toFile()
        val b = AndroidSdkBootstrap(sdkRoot = root, stagingDir = staging, download = fakeDownload(zip))
        val r = b.install(manifest(zip, sha = "deadbeef".repeat(5)))
        assertFalse(r.ok)
        assertTrue(r.reason!!.contains("校验不符"))
        assertFalse(root.resolve("cmdline-tools/latest").exists())
        assertTrue(staging.listFiles().isNullOrEmpty(), "坏包不能留在暂存目录")
    }

    @Test
    fun `install blocks zip slip entries and nothing lands outside staging`() {
        val zip = toolsZip("../evil.txt" to "pwn".toByteArray())
        val root = Files.createTempDirectory("yxi-sdk").toFile()
        val staging = Files.createTempDirectory("yxi-stage").toFile()
        val b = AndroidSdkBootstrap(sdkRoot = root, stagingDir = staging, download = fakeDownload(zip))
        val r = b.install(manifest(zip))
        assertFalse(r.ok)
        assertTrue(r.reason!!.contains("非法路径"))
        assertFalse(root.resolve("cmdline-tools/latest").exists())
        assertFalse(File(staging, "evil.txt").exists())   // `..` 出界目标不得存在
        assertTrue(staging.listFiles().isNullOrEmpty())
    }

    @Test
    fun `install cancelled mid download explains and cleans`() {
        val zip = toolsZip()
        val root = Files.createTempDirectory("yxi-sdk").toFile()
        val staging = Files.createTempDirectory("yxi-stage").toFile()
        val cancel = AtomicBoolean(true)   // 一开始就置位：写一半就取消
        val b = AndroidSdkBootstrap(sdkRoot = root, stagingDir = staging, download = fakeDownload(zip, cancel))
        val r = b.install(manifest(zip))
        assertFalse(r.ok)
        assertTrue(r.reason!!.contains("取消"))
        assertFalse(root.resolve("cmdline-tools/latest").exists())
        assertTrue(staging.listFiles().isNullOrEmpty(), "半截下载不能留在暂存目录")
    }
}
