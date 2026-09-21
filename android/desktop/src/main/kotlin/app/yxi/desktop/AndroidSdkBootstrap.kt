package app.yxi.desktop

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * AndroidSdkBootstrap —— 一键模拟器的首次配置：把 Google 官方 command-line tools
 * 装进 **Yxi 自有的 SDK 根**（`Store.dir/android-sdk`）。
 *
 * 两步，都由 root UI 显式发起：
 * 1. [inspect]：拉官方仓库清单（repository2-3.xml），动态找出 Windows 稳定通道的
 *    command-line tools 包，返回版本 / 下载地址 / 大小 / sha256 / 许可证全文——
 *    供 root 展示给用户确认，**这里不做任何改动**；
 * 2. [install]：下载到 Yxi 暂存目录 → sha256 校验 → 安全解压（拦 Zip Slip）→
 *    挪到 `<sdkRoot>/cmdline-tools/latest`。目标已存在就不覆盖。
 *
 * ⚠️ **边界**：只装 command-line tools 这一个包，不装平台/镜像、不建 AVD；
 * **绝不代表用户接受许可、绝不调用 sdkmanager**（许可证文本仅供展示）；
 * 不写系统环境变量（ANDROID_HOME 指路是后续的事）。下载有超时/进度/取消，
 * 失败一律清掉半成品并留可解释错误——半截安装比没有更糟。
 */
class AndroidSdkBootstrap(
    /** Yxi 自有 SDK 根，默认在 Yxi 数据目录下，不碰系统标准路径。 */
    private val sdkRoot: File = File(Store.dir, "android-sdk"),
    /** 下载暂存目录，同样在 Yxi 数据目录下；装完即清，不留半截包。 */
    private val stagingDir: File = File(Store.dir, "sdk-staging"),
    /** 仓库清单获取器。生产走 HttpURLConnection（带超时）；测试注假字符串。 */
    private val fetchXml: (String) -> String = ::fetchXmlNative,
    /** 下载器：返回 null = 成功，非 null = 可解释错误（半截文件由调用方清）。测试注假字节。 */
    private val download: (String, File, Long, (Long, Long) -> Unit, () -> Boolean) -> String? = ::downloadNative,
) {

    /** 仓库里找到的 Windows 稳定版 command-line tools 包——给 root UI 展示用。 */
    data class Manifest(
        val version: String,
        val url: String,
        val sizeBytes: Long,
        val sha256: String,
        /** 许可证全文，仅展示。本模块绝不代表用户接受许可。 */
        val licenses: List<String>,
    )

    data class InspectOutcome(val manifest: Manifest? = null, val reason: String? = null) {
        val ok: Boolean get() = manifest != null
    }

    data class InstallOutcome(val installed: Boolean = false, val reason: String? = null) {
        val ok: Boolean get() = installed
    }

    /** 安装目标：`<sdkRoot>/cmdline-tools/latest`（官方布局）。已存在就不覆盖。 */
    val targetDir: File get() = File(File(sdkRoot, "cmdline-tools"), "latest")

    /** 拉官方仓库清单，动态挑出 Windows 稳定通道的 command-line tools 包。 */
    fun inspect(repoUrl: String = REPO_URL): InspectOutcome {
        val xml = try {
            fetchXml(repoUrl)
        } catch (e: Exception) {
            return InspectOutcome(reason = "获取仓库清单失败：${e.message?.take(120)}")
        }
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setNamespaceAware(true)   // 官方 XML 标签带 sdk: 前缀，按 localName 匹配
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                // XXE 防护：清单是外部内容，绝不允许 DOCTYPE/外部实体
                try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
            }
            factory.newDocumentBuilder().parse(xml.byteInputStream())
        } catch (e: Exception) {
            return InspectOutcome(reason = "仓库清单解析失败：${e.message?.take(120)}")
        }

        fun each(name: String, block: (Element) -> Unit) {
            val list = doc.getElementsByTagNameNS("*", name)
            for (i in 0 until list.length) block(list.item(i) as Element)
        }
        fun Element.children(name: String): List<Element> =
            (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()
                .filter { it.localName == name }

        val licenseTexts = mutableMapOf<String, String>()
        each("license") { el ->
            el.getAttribute("id").takeIf { it.isNotBlank() }?.let { licenseTexts[it] = el.textContent.trim() }
        }

        data class Cand(val version: String, val url: String, val size: Long, val sha: String, val licenseRefs: List<String>)
        val cands = mutableListOf<Cand>()
        each("remotePackage") { pkg ->
            val path = pkg.getAttribute("path")
            if (!path.startsWith("cmdline-tools;")) return@each
            // 只要稳定通道；preview/beta 通道一律不进候选
            if (pkg.children("channelRef").none { it.getAttribute("ref") == STABLE_CHANNEL }) return@each
            val archive = pkg.children("archives").firstOrNull()?.children("archive")
                ?.firstOrNull { a -> a.children("host-os").firstOrNull()?.textContent?.trim() == "windows" }
                ?: return@each
            val complete = archive.children("complete").firstOrNull() ?: return@each
            val relUrl = complete.children("url").firstOrNull()?.textContent?.trim() ?: return@each
            val sha = complete.children("checksum").firstOrNull()
                ?.takeIf { it.getAttribute("type").equals("sha256", true) }?.textContent?.trim() ?: return@each
            cands += Cand(
                version = path.substringAfter(';'),
                // 官方清单里的 url 是相对路径，拼回仓库基址
                url = if (relUrl.startsWith("http")) relUrl else REPO_BASE + relUrl,
                size = complete.children("size").firstOrNull()?.textContent?.trim()?.toLongOrNull() ?: -1L,
                sha = sha.lowercase(),
                licenseRefs = pkg.children("uses-license").mapNotNull { it.getAttribute("ref").takeIf { r -> r.isNotBlank() } },
            )
        }
        // 优先 latest 别名；没有再取版本号最高的稳定包（逐段补零比较，"19.0" < "20.0"）
        val cand = cands.firstOrNull { it.version == "latest" }
            ?: cands.maxByOrNull { it.version.split('.').joinToString(".") { p -> "%08d".format(p.toIntOrNull() ?: 0) } }
            ?: return InspectOutcome(reason = "仓库清单里没有 Windows 稳定版 command-line tools")
        return InspectOutcome(
            Manifest(cand.version, cand.url, cand.size, cand.sha, cand.licenseRefs.mapNotNull { licenseTexts[it] }),
        )
    }

    /**
     * 下载 → 校验 → 安全解压 → 装到 `<sdkRoot>/cmdline-tools/latest`。
     * 任何一步失败都清掉半成品并返回可解释错误；目标已存在直接拒绝，不覆盖。
     */
    fun install(
        manifest: Manifest,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): InstallOutcome {
        val target = targetDir
        if (target.exists()) {
            return InstallOutcome(reason = "目标已存在（${target.path}），不覆盖 —— 已装好的 command-line tools 保持原样")
        }
        stagingDir.mkdirs()
        val zip = File(stagingDir, "commandlinetools-win-${manifest.version}.zip")
        val extractDir = File(stagingDir, "extract-${System.currentTimeMillis()}")

        // 1) 下载（超时/进度/取消都在下载器里；失败清半截文件）
        val reason = download(manifest.url, zip, manifest.sizeBytes, onProgress, isCancelled)
        if (reason != null) {
            zip.delete()
            return InstallOutcome(reason = reason)
        }

        // 2) sha256 校验：对不上就删，绝不让坏包进 SDK 根
        val actual = try {
            zip.inputStream().use { sha256Hex(it) }
        } catch (e: Exception) {
            zip.delete()
            return InstallOutcome(reason = "读取下载内容失败：${e.message?.take(120)}")
        }
        if (!actual.equals(manifest.sha256, ignoreCase = true)) {
            zip.delete()
            return InstallOutcome(reason = "校验不符：期望 ${manifest.sha256.take(12)}…，实际 ${actual.take(12)}…（坏包已删除）")
        }

        // 3) 安全解压到暂存目录（Zip Slip 拦截 + 解压总量上限）
        val extractError = try {
            unzipSafely(zip, extractDir, isCancelled)
        } catch (e: Exception) {
            "解压失败：${e.message?.take(120)}"
        }
        if (extractError != null) {
            zip.delete(); extractDir.deleteRecursively()
            return InstallOutcome(reason = extractError)
        }

        // 4) 官方 zip 顶层就是 cmdline-tools/，挪到 <sdkRoot>/cmdline-tools/latest
        val inner = File(extractDir, "cmdline-tools")
        if (!inner.isDirectory) {
            cleanup(zip, extractDir)
            return InstallOutcome(reason = "压缩包结构不符：顶层没有 cmdline-tools 目录")
        }
        if (target.exists()) {
            cleanup(zip, extractDir)
            return InstallOutcome(reason = "目标已出现（${target.path}），不覆盖 —— 请确认后再试")
        }
        target.parentFile.mkdirs()
        try {
            moveDir(inner, target)
        } catch (e: Exception) {
            target.deleteRecursively()   // 回滚半截安装
            cleanup(zip, extractDir)
            return InstallOutcome(reason = "安装失败：${e.message?.take(120)}（已回滚，目标不留半成品）")
        }
        cleanup(zip, extractDir)
        return InstallOutcome(installed = true)
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    private fun cleanup(vararg files: File) {
        files.forEach { if (it.isDirectory) it.deleteRecursively() else it.delete() }
    }

    private fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 解压必须全程圈在暂存目录里：条目路径带 `..` 出界的（Zip Slip）直接拒。
     * `canonicalPath` 在 Windows 上同样解析 `\`，两种分隔符的出界都拦得住。
     * 另设解压总量上限——zip 炸弹不解。解压中也可取消。
     */
    private fun unzipSafely(zip: File, dest: File, isCancelled: () -> Boolean): String? {
        dest.mkdirs()
        val prefix = dest.canonicalPath + File.separator
        var total = 0L
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val entry = z.nextEntry ?: break
                if (isCancelled()) return "已取消"
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(prefix)) {
                    return "压缩包含非法路径：${entry.name.take(80)}"
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        while (true) {
                            val n = z.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            total += n
                            if (total > MAX_EXTRACT_BYTES) {
                                return "解压内容超过上限（${MAX_EXTRACT_BYTES / (1024 * 1024)}MB），疑似异常包"
                            }
                        }
                    }
                }
                z.closeEntry()
            }
        }
        return null
    }

    /** 同卷直接 move；跨卷（暂存和 SDK 根被配到不同盘）退回复制+删源。 */
    private fun moveDir(src: File, dst: File) {
        try {
            Files.move(src.toPath(), dst.toPath())
            return
        } catch (_: IOException) {
            // 跨卷等情况走复制兜底
        }
        dst.mkdirs()
        src.walkTopDown().forEach { f ->
            val t = File(dst, f.relativeTo(src).path)
            if (f.isDirectory) t.mkdirs() else f.copyTo(t, overwrite = true)
        }
        src.deleteRecursively()
    }

    companion object {
        /** Google 官方 SDK 仓库清单（稳定/预览通道都在里面）。 */
        const val REPO_URL = "https://dl.google.com/android/repository/repository2-3.xml"
        /** 清单里的下载 url 是相对路径，拼回这个基址。 */
        const val REPO_BASE = "https://dl.google.com/android/repository/"
        private const val STABLE_CHANNEL = "channel-0"

        /** command-line tools 解开 ~500MB；超过 2GB 一定是异常包，zip 炸弹不解。 */
        const val MAX_EXTRACT_BYTES = 2L * 1024 * 1024 * 1024

        private fun fetchXmlNative(url: String): String {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 30_000; requestMethod = "GET"
            }
            return try {
                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }

        /**
         * 真下载：连接 10s / 单次读 30s 超时，进度每 64KB 回调一次，循环里轮询取消。
         * 返回 null = 成功；非 null = 可解释错误（半截文件由调用方清理）。
         */
        private fun downloadNative(
            url: String,
            dest: File,
            total: Long,
            onProgress: (Long, Long) -> Unit,
            isCancelled: () -> Boolean,
        ): String? {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 30_000; instanceFollowRedirects = true
            }
            return try {
                if (c.responseCode !in 200..299) return "下载失败：HTTP ${c.responseCode}"
                dest.parentFile?.mkdirs()
                var written = 0L
                c.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (isCancelled()) return "已取消"
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            onProgress(written, if (total > 0) total else c.contentLengthLong)
                        }
                    }
                }
                null
            } catch (e: Exception) {
                "下载失败：${e.message?.take(120)}"
            } finally {
                c.disconnect()
            }
        }
    }
}
