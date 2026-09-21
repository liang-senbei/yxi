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
 * **零真网络、零真下载**；zip 在测试里现做（官方包同款顶层 cmdline-tools/），
 * 覆盖坏校验 / Zip Slip / 取消 / 不覆盖已有目标这些分支。
 */
class AndroidSdkBootstrapTest {

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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

    private fun manifest(zip: ByteArray, sha: String = sha256(zip)) = AndroidSdkBootstrap.Manifest(
        version = "latest",
        url = AndroidSdkBootstrap.REPO_BASE + "commandlinetools-win-13114758_latest.zip",
        sizeBytes = zip.size.toLong(),
        sha256 = sha,
        licenses = listOf("仅供展示的许可全文"),
    )

    // ── inspect：仓库清单解析 ────────────────────────────────────────────────

    @Test
    fun `inspect picks stable windows commandlinetools and surfaces license text`() {
        val xml = """<?xml version="1.0"?>
<sdk:sdk-repository xmlns:sdk="http://schemas.android.com/sdk/android/repo/repository2/3">
  <sdk:license id="android-sdk-license" type="bean">许可全文在这里，只展示。</sdk:license>
  <sdk:channel id="channel-0"/><sdk:channel id="channel-1"/>
  <sdk:remotePackage path="cmdline-tools;19.0">
    <sdk:channelRef ref="channel-0"/>
    <sdk:archives><sdk:archive>
      <sdk:complete><sdk:url>commandlinetools-win-11076708_19.0.zip</sdk:url><sdk:size>111</sdk:size>
        <sdk:checksum type="sha256">${"ab".repeat(32)}</sdk:checksum></sdk:complete>
      <sdk:host-os>windows</sdk:host-os>
    </sdk:archive></sdk:archives>
    <sdk:uses-license ref="android-sdk-license"/>
  </sdk:remotePackage>
  <sdk:remotePackage path="cmdline-tools;latest">
    <sdk:channelRef ref="channel-0"/>
    <sdk:archives>
      <sdk:archive>
        <sdk:complete><sdk:url>commandlinetools-win-13114758_latest.zip</sdk:url><sdk:size>152092487</sdk:size>
          <sdk:checksum type="sha256">${"cd".repeat(32)}</sdk:checksum></sdk:complete>
        <sdk:host-os>windows</sdk:host-os>
      </sdk:archive>
      <sdk:archive>
        <sdk:complete><sdk:url>commandlinetools-linux-13114758_latest.zip</sdk:url><sdk:size>100</sdk:size>
          <sdk:checksum type="sha256">${"ee".repeat(32)}</sdk:checksum></sdk:complete>
        <sdk:host-os>linux</sdk:host-os>
      </sdk:archive>
    </sdk:archives>
    <sdk:uses-license ref="android-sdk-license"/>
  </sdk:remotePackage>
  <sdk:remotePackage path="cmdline-tools;21.0">
    <sdk:channelRef ref="channel-1"/>
    <sdk:archives><sdk:archive>
      <sdk:complete><sdk:url>commandlinetools-win-preview.zip</sdk:url><sdk:size>100</sdk:size>
        <sdk:checksum type="sha256">${"ff".repeat(32)}</sdk:checksum></sdk:complete>
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
        assertEquals("cd".repeat(32), m.sha256)
        assertEquals(listOf("许可全文在这里，只展示。"), m.licenses)
    }

    @Test
    fun `inspect falls back to highest stable version when no latest alias`() {
        val xml = """<sdk:sdk-repository xmlns:sdk="http://schemas.android.com/sdk/android/repo/repository2/3">
          <sdk:channel id="channel-0"/>
          <sdk:remotePackage path="cmdline-tools;19.0">
            <sdk:channelRef ref="channel-0"/>
            <sdk:archives><sdk:archive>
              <sdk:complete><sdk:url>commandlinetools-win-11076708_19.0.zip</sdk:url><sdk:size>111</sdk:size>
                <sdk:checksum type="sha256">${"ab".repeat(32)}</sdk:checksum></sdk:complete>
              <sdk:host-os>windows</sdk:host-os>
            </sdk:archive></sdk:archives>
          </sdk:remotePackage>
        </sdk:sdk-repository>"""
        val r = AndroidSdkBootstrap(fetchXml = { xml }).inspect()
        assertTrue(r.ok, r.reason)
        assertEquals("19.0", r.manifest!!.version)
        assertEquals(AndroidSdkBootstrap.REPO_BASE + "commandlinetools-win-11076708_19.0.zip", r.manifest!!.url)
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

    // ── install：下载 → 校验 → 解压 → 安装 ──────────────────────────────────

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
    fun `install downloads verifies extracts and lands at cmdline-tools latest with staging cleaned`() {
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
    fun `install rejects checksum mismatch cleans staging and leaves target absent`() {
        val zip = toolsZip()
        val root = Files.createTempDirectory("yxi-sdk").toFile()
        val staging = Files.createTempDirectory("yxi-stage").toFile()
        val b = AndroidSdkBootstrap(sdkRoot = root, stagingDir = staging, download = fakeDownload(zip))
        val r = b.install(manifest(zip, sha = "deadbeef".repeat(8)))
        assertFalse(r.ok)
        assertTrue(r.reason!!.contains("校验"))
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
