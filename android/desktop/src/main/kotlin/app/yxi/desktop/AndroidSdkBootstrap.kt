package app.yxi.desktop

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 *    command-line tools 包，返回版本 / 下载地址 / 大小 / 校验值 / 许可证全文——
 *    供 root 展示给用户确认，**这里不做任何改动**。校验算法按清单元数据
 *    （checksum 的 algorithm/type 属性）认 **SHA-1 / SHA-256**——官方这个包实际
 *    给的就是 sha1，只认 sha256 会找不到真实包；十六进制长度一并核对。
 * 2. [install]：下载到 Yxi 暂存目录（实际大小不得超过清单）→ 按元数据校验 →
 *    安全解压（拦 Zip Slip）→ **同卷原子 rename 发布**到
 *    `<sdkRoot>/cmdline-tools/latest`。目标已存在就不覆盖；发布不做覆盖 copy
 *    兜底——copy 会盖掉竞态里别人先放的目录，rename 要么成要么整体不动。
 *
 * ⚠️ **边界**：只装 command-line tools 这一个包，不装平台/镜像、不建 AVD；
 * **绝不代表用户接受许可、绝不调用 sdkmanager**（许可证文本仅供展示）；
 * 不写系统环境变量（ANDROID_HOME 指路是后续的事）。下载有超时/进度/取消，
 * 失败清的**只可能是本次暂存根里的东西**（删除前验证 canonical 位置），
 * 目标目录任何情况下不动。
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
        /** 校验算法（SHA-1 / SHA-256），按官方清单元数据归一化。 */
        val checksumAlgorithm: String,
        /** 校验值（小写十六进制）。 */
        val checksum: String,
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

    /**
     * 拉官方仓库清单，动态挑出 Windows 稳定通道的 command-line tools 包。
     * [isCancelled] 在每个阶段边界（取清单前/取回后/解析后）轮询——探测此前不感知
     * 取消，UI 曾在探测期展示一个只能置 flag 的假取消按钮。
     */
    fun inspect(repoUrl: String = REPO_URL, isCancelled: () -> Boolean = { false }): InspectOutcome {
        if (isCancelled()) return InspectOutcome(reason = "已取消")
        val xml = try {
            fetchXml(repoUrl)
        } catch (e: Exception) {
            return InspectOutcome(reason = "获取仓库清单失败：${e.message?.take(120)}")
        }
        if (isCancelled()) return InspectOutcome(reason = "已取消（清单已取回，未解析）")
        val doc = try {
            parseRepositoryXml(xml)
        } catch (e: Exception) {
            return InspectOutcome(reason = "仓库清单解析失败：${e.message?.take(120)}")
        }
        if (isCancelled()) return InspectOutcome(reason = "已取消（清单已解析，未选包）")

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

        data class Cand(
            val version: String, val url: String, val size: Long,
            val algorithm: String, val checksum: String, val licenseRefs: List<String>,
        )
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
            // 算法按元数据认：官方字段是 algorithm，老清单用 type，两个都接；
            // 只认 SHA-1/SHA-256，且十六进制长度必须对得上，对不上当元数据不可信跳过
            val checksumEl = complete.children("checksum").firstOrNull() ?: return@each
            val algorithm = normalizeAlgorithm(checksumEl.getAttribute("algorithm").ifBlank { checksumEl.getAttribute("type") })
                ?: return@each
            val hex = checksumEl.textContent.trim()
            if (hex.length != if (algorithm == "SHA-1") 40 else 64) return@each
            cands += Cand(
                version = path.substringAfter(';'),
                // 官方清单里的 url 是相对路径，拼回仓库基址
                url = if (relUrl.startsWith("http")) relUrl else REPO_BASE + relUrl,
                size = complete.children("size").firstOrNull()?.textContent?.trim()?.toLongOrNull() ?: -1L,
                algorithm = algorithm,
                checksum = hex.lowercase(),
                licenseRefs = pkg.children("uses-license").mapNotNull { it.getAttribute("ref").takeIf { r -> r.isNotBlank() } },
            )
        }
        // 优先 latest 别名；没有再取版本号最高的稳定包（逐段补零比较，"19.0" < "20.0"）
        val cand = cands.firstOrNull { it.version == "latest" }
            ?: cands.maxByOrNull { it.version.split('.').joinToString(".") { p -> "%08d".format(p.toIntOrNull() ?: 0) } }
            ?: return InspectOutcome(reason = "仓库清单里没有 Windows 稳定版 command-line tools")
        return InspectOutcome(
            Manifest(cand.version, cand.url, cand.size, cand.algorithm, cand.checksum, cand.licenseRefs.mapNotNull { licenseTexts[it] }),
        )
    }

    /**
     * 下载 → 校验 → 安全解压 → 原子发布到 `<sdkRoot>/cmdline-tools/latest`。
     * 任何一步失败都清**本次暂存**并返回可解释错误；目标已存在直接拒绝，
     * 任何失败路径都不碰目标目录。
     */
    fun install(
        manifest: Manifest,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): InstallOutcome {
        // 只从官方 HTTPS 源下载——manifest 会在 UI 展示后回传，防中途被换成别的地址
        if (!manifest.url.startsWith(REPO_BASE)) {
            return InstallOutcome(reason = "下载源不是官方仓库（$REPO_BASE），拒绝")
        }
        val target = targetDir
        if (target.exists()) {
            return InstallOutcome(reason = "目标已存在（${target.path}），不覆盖 —— 已装好的 command-line tools 保持原样")
        }
        stagingDir.mkdirs()
        // 每次安装用独立的 zip 与解压目录，并发互不踩
        val stamp = java.util.UUID.randomUUID().toString()
        val zip = File(stagingDir, "commandlinetools-win-$stamp.zip")
        val extractDir = File(stagingDir, "extract-$stamp")

        // 1) 下载（超时/进度/取消/超清单大小都在下载器里；失败清半截文件）
        val reason = download(manifest.url, zip, manifest.sizeBytes, onProgress, isCancelled)
        if (reason != null) {
            deleteStaged(zip)
            return InstallOutcome(reason = reason)
        }

        // 2) 实际大小不得超过清单声明（清单给可信大小时）
        if (manifest.sizeBytes > 0 && zip.length() != manifest.sizeBytes) {
            deleteStaged(zip)
            return InstallOutcome(reason = "下载内容大小与清单不一致，已删除暂存包")
        }

        // 3) 校验（SHA-1/SHA-256 按清单元数据）：对不上就删，绝不让坏包进 SDK 根
        val actual = try {
            zip.inputStream().use { digestHex(it, manifest.checksumAlgorithm) }
        } catch (e: Exception) {
            deleteStaged(zip)
            return InstallOutcome(reason = "读取下载内容失败：${e.message?.take(120)}")
        }
        if (!actual.equals(manifest.checksum, ignoreCase = true)) {
            deleteStaged(zip)
            return InstallOutcome(
                reason = "校验不符（${manifest.checksumAlgorithm}）：期望 ${manifest.checksum.take(12)}…，实际 ${actual.take(12)}…（坏包已删除）",
            )
        }

        // 4) 安全解压到暂存目录（Zip Slip 拦截 + 解压总量上限 + 可取消）
        val extractError = try {
            unzipSafely(zip, extractDir, isCancelled)
        } catch (e: Exception) {
            "解压失败：${e.message?.take(120)}"
        }
        if (extractError != null) {
            deleteStaged(zip); deleteStaged(extractDir)
            return InstallOutcome(reason = extractError)
        }

        // 5) 官方 zip 顶层就是 cmdline-tools/，同卷原子发布到 <sdkRoot>/cmdline-tools/latest
        val inner = File(extractDir, "cmdline-tools")
        if (!inner.isDirectory) {
            deleteStaged(zip); deleteStaged(extractDir)
            return InstallOutcome(reason = "压缩包结构不符：顶层没有 cmdline-tools 目录")
        }
        if (target.exists()) {
            deleteStaged(zip); deleteStaged(extractDir)
            return InstallOutcome(reason = "目标已出现（${target.path}），不覆盖 —— 请确认后再试")
        }
        if (isCancelled()) {
            deleteStaged(zip); deleteStaged(extractDir)
            return InstallOutcome(reason = "已取消")
        }
        target.parentFile.mkdirs()
        val publishError = publish(inner, target)
        deleteStaged(zip); deleteStaged(extractDir)
        return if (publishError == null) InstallOutcome(installed = true) else InstallOutcome(reason = publishError)
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    /**
     * 删除只允许发生在暂存根内：canonical 不在 stagingDir 里的一律不动。
     * 防的是路径拼接/符号链接把删除指到暂存外——尤其绝不能顺着失败路径删到目标目录。
     */
    private fun deleteStaged(f: File) {
        val root = stagingDir.canonicalPath + File.separator
        val p = try {
            f.canonicalPath
        } catch (_: Exception) {
            return
        }
        if (!p.startsWith(root)) return
        if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    /**
     * 原子发布：同卷唯一暂存目录 rename 上位。**不做覆盖 copy 兜底**——
     * copy overwrite 会盖掉竞态里别人先放的目录；ATOMIC_MOVE 失败时源还留在
     * 暂存里、目标一个字节不动，由调用方只清暂存。
     */
    private fun publish(src: File, dst: File): String? = try {
        // ATOMIC_MOVE permits provider-specific replacement of an existing target.
        // A normal same-volume move without REPLACE_EXISTING refuses that collision.
        Files.move(src.toPath(), dst.toPath())
        null
    } catch (_: FileAlreadyExistsException) {
        "目标已出现（可能被并发安装抢先）——未覆盖、未删除，请确认后再试"
    } catch (_: AtomicMoveNotSupportedException) {
        "暂存目录与 SDK 根不在同一卷，无法原子发布（请把两者配在同一盘后重试）"
    } catch (e: IOException) {
        "安装失败：${e.message?.take(120)}（目标未改动）"
    }

    private fun digestHex(input: InputStream, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
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

    companion object {
        /** Google 官方 SDK 仓库清单（稳定/预览通道都在里面）。 */
        const val REPO_URL = "https://dl.google.com/android/repository/repository2-3.xml"
        /** 清单里的下载 url 是相对路径，拼回这个基址；install 也只认这个前缀。 */
        const val REPO_BASE = "https://dl.google.com/android/repository/"
        private const val STABLE_CHANNEL = "channel-0"

        /** command-line tools 解开 ~500MB；超过 2GB 一定是异常包，zip 炸弹不解。 */
        const val MAX_EXTRACT_BYTES = 2L * 1024 * 1024 * 1024

        /** 清单里的算法名归一化；只认 SHA-1/SHA-256（官方 command-line tools 实际给的是 sha1）。 */
        internal fun normalizeAlgorithm(raw: String): String? = when (raw.lowercase().replace("-", "")) {
            "sha1" -> "SHA-1"
            "sha256" -> "SHA-256"
            else -> null
        }

        /**
         * 许可 hash 的唯一算法来源：License.getLicenseHash() 就是
         * `Hashing.sha1().hashBytes(getValue().getBytes(UTF_8))`——对**未 trim** 的
         * 解析文本原值取 SHA-1 的 UTF-8 字节，十六进制小写。
         */
        internal fun sha1Hex(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /**
         * 轻量解析官方仓库 XML：namespaceAware（子元素无前缀、根带 sdk: 前缀，
         * 按 localName 匹配）+ XXE 防护。inspect 与许可全文解析共用这一个实现。
         */
        internal fun parseRepositoryXml(xml: String): org.w3c.dom.Document {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setNamespaceAware(true)
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
            }
            return factory.newDocumentBuilder().parse(xml.byteInputStream())
        }

        /**
         * 按包的 uses-license 引用解析许可全文（给 UI 展示、供用户逐条同意）。
         * 只解析清单里存在的包；清单没有的包（如 system-images，单独 sys-img 描述符）
         * 如实进 [AndroidSdkComponentInstaller.LicenseTexts.missing]。
         */
        internal fun resolveLicenseTexts(doc: org.w3c.dom.Document, packages: List<String>): AndroidSdkComponentInstaller.LicenseTexts {
            fun Element.childrenByName(name: String): List<Element> =
                (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()
                    .filter { it.localName == name }

            val licenseTexts = mutableMapOf<String, String>()
            val list = doc.getElementsByTagNameNS("*", "license")
            for (i in 0 until list.length) {
                val el = list.item(i) as Element
                // 文本保持解析原值**不 trim**：hash 对原值才算得对（License.getLicenseHash 对
                // getValue() 原文取 sha1，官方文本本身以 \n 收尾）；trim 只该发生在展示层
                el.getAttribute("id").takeIf { it.isNotBlank() }?.let { licenseTexts[it] = el.textContent }
            }
            val docs = linkedMapOf<String, AndroidSdkComponentInstaller.LicenseDoc>()
            val missing = mutableListOf<String>()
            val pkgList = doc.getElementsByTagNameNS("*", "remotePackage")
            val paths = HashMap<String, Element>()
            for (i in 0 until pkgList.length) {
                val el = pkgList.item(i) as Element
                paths[el.getAttribute("path")] = el
            }
            for (pkg in packages) {
                val el = paths[pkg]
                val refs = el?.childrenByName("uses-license")
                    ?.mapNotNull { it.getAttribute("ref").takeIf { r -> r.isNotBlank() } }
                    .orEmpty()
                if (el == null || refs.isEmpty()) {
                    missing += pkg
                    continue
                }
                refs.forEach { ref ->
                    licenseTexts[ref]?.let { docs.putIfAbsent(ref, AndroidSdkComponentInstaller.LicenseDoc(ref, it, sha1Hex(it))) }
                }
            }
            return AndroidSdkComponentInstaller.LicenseTexts(docs = docs.values.toList(), missing = missing)
        }

        /**
         * 真下载：连接 10s / 单次读 30s 超时，进度每 64KB 回调一次，循环里轮询取消；
         * 实际字节数一旦超过清单声明立即中止。
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
                            if (total > 0 && written > total) return "下载内容超出清单大小（$total 字节），已中止"
                            onProgress(written, total)
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
