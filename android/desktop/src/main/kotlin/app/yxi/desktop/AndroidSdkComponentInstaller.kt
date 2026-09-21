package app.yxi.desktop

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 一次外部工具运行的结果。exitCode=-1 且 cancelled/timedOut=false 表示没跑起来（output 里是原因）。 */
data class ToolRun(val exitCode: Int, val output: String, val cancelled: Boolean = false, val timedOut: Boolean = false)

/** 工具输出只保留末尾 64KB——回执与诊断够用，内存不随输出增长。 */
internal const val TOOL_OUTPUT_TAIL_BYTES = 64 * 1024

/**
 * 输出尾部环形缓冲：外部工具一跑几分钟，输出总量不可控，
 * 只保留**末尾** [cap] 字节（诊断信息都在结尾），内存/磁盘都不随输出增长。
 */
private class TailBuffer(private val cap: Int) {
    private val lock = Any()
    private val buf = ByteArray(cap)
    private var len = 0

    fun append(b: ByteArray, off: Int, n: Int) {
        synchronized(lock) {
            if (n <= 0) return
            if (n >= cap) {
                System.arraycopy(b, off + n - cap, buf, 0, cap)
                len = cap
                return
            }
            val keep = minOf(len, cap - n)
            System.arraycopy(buf, len - keep, buf, 0, keep)
            System.arraycopy(b, off, buf, keep, n)
            len = keep + n
        }
    }

    fun text(): String = synchronized(lock) { String(buf, 0, len, Charsets.UTF_8) }
}

/**
 * 真跑 SDK 工具（sdkmanager/avdmanager 的 .bat）。
 * ⚠️ **参数列表不等于安全**：Windows 上 `cmd /c` 仍会解释元字符（`%VAR%`/`!var!`/
 * 重定向/管道在引号内照样展开），所以安全靠**上游白名单**：包名/许可 ID/AVD 名
 * 全匹配 regex 白名单、路径参数查 cmd 元字符——无法安全传入的输入直接拒绝，
 * 不做转义赌注。
 *
 * **进程纪律**（对 sdkmanager 源码行为的对应处理）：
 * - 启动后**立即关闭 stdin**——sdkmanager 的交互提示（`--licenses` 的 `(y/N)?`、
 *   install 的「Continue installing the remaining packages?」）读到 EOF 一律走
 *   「拒绝」分支并退出，既不会挂着等输入，也绝不会替我们接受任何许可；
 * - 输出由**drain 线程持续抽干**进 [TailBuffer]（只留末尾 64KB）：旧实现重定向到
 *   临时文件、跑多久长多大，长安装能把磁盘写穿；
 * - .bat 依赖 JAVA_HOME：用户机器可能没装 JDK——把**我们自己运行时的** java.home
 *   （`System.getProperty("java.home")`，bin 下有 java 可执行才设）只放进**子进程**
 *   的环境，绝不写系统环境变量；
 * - 取消/超时杀 **进程树**：`cmd /c` 的父进程死了 Java 子进程不一定死，
 *   Windows 上 `taskkill /F /T /PID` 杀整棵，其余平台 destroyForcibly 兜底。
 */
internal fun runToolNative(exe: File, args: List<String>, timeoutSeconds: Long, isCancelled: () -> Boolean): ToolRun {
    val p = try {
        ProcessBuilder(listOf("cmd", "/c", exe.absolutePath) + args)
            .redirectErrorStream(true)
            .apply { environment().putAll(javaHomeEnv()) }
            .start()
    } catch (e: IOException) {
        return ToolRun(-1, "无法启动 ${exe.name}：${e.message?.take(120)}（cmd /c 仅在 Windows 上可用）")
    }
    runCatching { p.outputStream.close() }   // stdin EOF：任何交互提示都走「拒绝」，绝不盲 yes
    val tail = TailBuffer(TOOL_OUTPUT_TAIL_BYTES)
    val drain = Thread {
        runCatching {
            p.inputStream.use { input ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    tail.append(buf, 0, n)
                }
            }
        }
    }.apply { isDaemon = true; name = "yxi-sdktool-drain" }
    drain.start()
    try {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (true) {
            if (p.waitFor(500, TimeUnit.MILLISECONDS)) return ToolRun(p.exitValue(), tail.text())
            if (isCancelled()) {
                killTree(p)
                return ToolRun(-1, tail.text(), cancelled = true)
            }
            if (System.currentTimeMillis() > deadline) {
                killTree(p)
                return ToolRun(-1, tail.text(), timedOut = true)
            }
        }
    } finally {
        drain.join(2_000)   // 杀掉后流会 EOF，drain 随即结束；join 只为收齐最后一批输出
    }
}

/** `taskkill /F /T` 杀整棵进程树（Windows）；其余平台 destroyForcibly。之后都等退出收尸。 */
private fun killTree(p: Process) {
    val windows = (System.getProperty("os.name") ?: "").lowercase().contains("windows")
    if (windows) {
        runCatching {
            ProcessBuilder(listOf("taskkill", "/F", "/T", "/PID", p.pid().toString()))
                .start()
                .waitFor(10, TimeUnit.SECONDS)
        }
    }
    runCatching { p.destroyForcibly() }
    runCatching { p.waitFor(5, TimeUnit.SECONDS) }
}

/** 只给子进程的 JAVA_HOME：用应用自身运行时的 java.home（确有 java 可执行才设），不碰系统环境。 */
private fun javaHomeEnv(): Map<String, String> {
    val home = System.getProperty("java.home") ?: return emptyMap()
    val windows = (System.getProperty("os.name") ?: "").lowercase().contains("windows")
    val exe = if (windows) "java.exe" else "java"
    return if (File(home, "bin").resolve(exe).isFile) mapOf("JAVA_HOME" to home) else emptyMap()
}

/**
 * AndroidSdkComponentInstaller —— 在 Yxi 自有 SDK 根（`Store.dir/android-sdk`）上
 * 安装模拟器运行件（emulator / platform-tools / 用户显式选择的组件等），
 * 由 root UI 显式发起。
 *
 * **许可纪律（只接受用户点头的那些，格式对齐 AOSP License.java 源码）**：
 * - 接受记录是 `<sdkRoot>/licenses/<license-id>`（如 `android-sdk-license`），
 *   **内容**为多行 hash（同一 id 的多版文本逐行累积）——不是 `licenses/<hash>`；
 * - hash = `sha1(许可文本的 UTF-8 字节)`（License.getLicenseHash() 原文），文本是
 *   XML 解析的**原值、不 trim**（licenseType 是 simpleContent xsd:string，JAXB
 *   原样保留，含首尾换行——官方 android-sdk-license 文本本身以 `\n` 收尾）；
 *   追加格式与 License.setAccepted 一致（`"%n%s"`，首行前也有换行）；
 * - 用户按**许可 ID** 勾选，并随勾选携带**同意时看到的 hash 快照**
 *   （[install] 的 `reviewedLicenseHashes`）——安装时重新取清单再核对，官方若已
 *   换条款（hash 变了）就拒绝，防「同意的是旧条款、接受的却是新条款」；
 * - 勾选集合必须是所选包 uses-license 引用的**子集**，之外的一个不写——机制上
 *   排除「替用户 yes 全部许可」；清单（含 sys-img 描述符）核对不了的包一律先拒；
 * - `sdkmanager --licenses` 完全不跑（其互动输出无 hash、EOF 前还会挂 60s），
 *   待接受状态由本机 `licenses/<id>` 现状 + XML 解析纯本地比对（[listPendingLicenses]）。
 *
 * **命令纪律**：见 [runToolNative]——白名单校验是唯一防线，参数列表只是载体。
 */
class AndroidSdkComponentInstaller(
    /** Yxi 自有 SDK 根（Bootstrap 首次配置装 command-line tools 的同一处）。 */
    private val sdkRoot: File = File(Store.dir, "android-sdk"),
    /** sdkmanager.bat 位置，按官方布局默认在 cmdline-tools/latest/bin；测试注假路径。 */
    private val sdkmanagerBat: File = File(File(File(File(sdkRoot, "cmdline-tools"), "latest"), "bin"), "sdkmanager.bat"),
    /** 工具执行器；生产走 [runToolNative]，测试注假的，零真安装。 */
    private val runTool: (File, List<String>, Long, () -> Boolean) -> ToolRun = ::runToolNative,
    /** 官方清单获取器（主仓库 + sys-img 描述符），许可文本与镜像列表解析用；测试注假 XML。 */
    private val fetchXml: (String) -> String = ::fetchXmlNative,
) {

    /** 一个许可：ID + 全文原值（不 trim，hash 对它才算得对）+ sha1 hash（License.java 口径）。 */
    data class LicenseDoc(val id: String, val text: String, val hash: String)

    data class LicenseTexts(
        val docs: List<LicenseDoc> = emptyList(),
        /** 主清单和 sys-img 描述符里都没找到的包，许可无从核对。 */
        val missing: List<String> = emptyList(),
        val reason: String? = null,
    ) {
        val ok: Boolean get() = reason == null
    }

    /** 待接受状态 = 许可 + 本机 `licenses/<id>` 是否已含该 hash（纯本地比对，零 sdkmanager 调用）。 */
    data class LicenseStatus(val id: String, val text: String, val hash: String, val alreadyAccepted: Boolean)

    data class LicenseStatusList(
        val statuses: List<LicenseStatus> = emptyList(),
        val missing: List<String> = emptyList(),
        val reason: String? = null,
    ) {
        val ok: Boolean get() = reason == null
    }

    /** 一个可选系统镜像：官方 sys-img 描述符动态解析，版本不写死。 */
    data class SystemImage(
        /** 完整包路径，如 `system-images;android-36;default;x86_64`。 */
        val path: String,
        val apiLevel: Int,
        val abi: String,
        /** 镜像变体 tag（default / google_apis / …）。 */
        val tagId: String,
        /** 完整包下载字节数（官方清单元数据）。 */
        val sizeBytes: Long,
        /** 该镜像要求的许可 ID（UI 可同屏给出对应全文）。 */
        val licenseIds: List<String>,
    )

    data class SystemImageList(val images: List<SystemImage> = emptyList(), val reason: String? = null) {
        val ok: Boolean get() = reason == null
    }

    data class Outcome(val installed: Boolean = false, val reason: String? = null) {
        val ok: Boolean get() = installed
    }

    /**
     * 待接受许可清单：官方清单按所选包的 uses-license 引用解析许可全文/hash，
     * 再比对本机 `licenses/<id>` 现状。给 UI 展示 + 用户逐条勾选用。
     */
    fun listPendingLicenses(packages: List<String>, repoUrl: String = AndroidSdkBootstrap.REPO_URL): LicenseStatusList {
        if (packages.isEmpty()) return LicenseStatusList(reason = "没有选择组件，无从解析许可")
        val lt = licenseTexts(packages, repoUrl)
        if (!lt.ok) return LicenseStatusList(reason = lt.reason)
        return LicenseStatusList(
            lt.docs.map { LicenseStatus(it.id, it.text, it.hash, hashPresent(it.id, it.hash)) },
            lt.missing,
        )
    }

    /**
     * 从官方清单按**具体包**的 uses-license 引用解析许可全文与 hash。
     * 文本是解析原值**不 trim**（hash 对未 trim 的值才算得对）；主仓库
     * （repository2-3）没有的包再到官方 sys-img 描述符里查一轮（system-images
     * 都在那边，描述符自带同样的 license 全文）；两边都没有才进 [LicenseTexts.missing]。
     */
    fun licenseTexts(packages: List<String>, repoUrl: String = AndroidSdkBootstrap.REPO_URL): LicenseTexts {
        if (packages.isEmpty()) return LicenseTexts(reason = "没有选择组件，无从解析许可")
        val primary = try {
            val doc = AndroidSdkBootstrap.parseRepositoryXml(fetchXml(repoUrl))
            AndroidSdkBootstrap.resolveLicenseTexts(doc, packages)
        } catch (e: Exception) {
            return LicenseTexts(reason = "解析官方仓库清单失败：${e.message?.take(120)}")
        }
        if (primary.missing.isEmpty()) return primary

        // 主清单没有的包（system-images 等）：官方 sys-img 描述符再核对一轮
        val sysDoc = try {
            val url = sysImgDescriptorUrl(SYS_IMG_VENDOR_DEFAULT) ?: return primary
            AndroidSdkBootstrap.parseRepositoryXml(fetchXml(url))
        } catch (e: Exception) {
            return primary   // 描述符取不到：这些包保持 missing（install 会先拒），不给假绿灯
        }
        val secondary = AndroidSdkBootstrap.resolveLicenseTexts(sysDoc, primary.missing)
        val merged = primary.docs + secondary.docs.filter { s -> primary.docs.none { it.id == s.id } }
        return LicenseTexts(merged, secondary.missing)
    }

    /**
     * 可选系统镜像列表：先从官方站点列表（addons_list-N.xml，从最新往回探测，
     * 版本不写死）找到 vendor 的 sys-img 描述符，再解析**稳定通道**（channel-0）
     * 匹配 [abi] 的镜像：包名 / API / 架构 / 下载大小 / 要求的许可。
     * 默认 vendor=`android`（AOSP 镜像）、abi=`x86_64`。
     */
    fun systemImages(vendor: String = SYS_IMG_VENDOR_DEFAULT, abi: String = SYS_IMG_ABI_DEFAULT): SystemImageList {
        if (!SITE_SEGMENT.matches(vendor) || !SITE_SEGMENT.matches(abi)) {
            return SystemImageList(reason = "vendor/架构含不允许的字符（只允许小写字母数字_-）：${vendor.take(40)}/${abi.take(40)}")
        }
        val url = sysImgDescriptorUrl(vendor)
            ?: return SystemImageList(reason = "官方站点列表里找不到 vendor=$vendor 的系统镜像描述符")
        val doc = try {
            AndroidSdkBootstrap.parseRepositoryXml(fetchXml(url))
        } catch (e: Exception) {
            return SystemImageList(reason = "获取系统镜像描述符失败：${e.message?.take(120)}")
        }
        val images = mutableListOf<SystemImage>()
        val list = doc.getElementsByTagNameNS("*", "remotePackage")
        for (i in 0 until list.length) {
            val pkg = list.item(i) as org.w3c.dom.Element
            val path = pkg.getAttribute("path")
            if (!path.startsWith("system-images;")) continue
            // 只要稳定通道；preview/beta 通道不进可选列表
            if (pkg.childElements("channelRef").none { it.getAttribute("ref") == AndroidSdkBootstrap.STABLE_CHANNEL }) continue
            val details = pkg.childElements("type-details").firstOrNull() ?: continue
            val api = details.childElements("api-level").firstOrNull()?.textContent?.trim()?.toIntOrNull() ?: continue
            val imageAbi = details.childElements("abi").firstOrNull()?.textContent?.trim() ?: continue
            if (imageAbi != abi) continue
            val tag = details.childElements("tag").firstOrNull()?.childElements("id")?.firstOrNull()?.textContent?.trim() ?: ""
            val complete = pkg.childElements("archives").firstOrNull()?.childElements("archive")
                ?.firstOrNull()?.childElements("complete")?.firstOrNull()
            val size = complete?.childElements("size")?.firstOrNull()?.textContent?.trim()?.toLongOrNull() ?: -1L
            images += SystemImage(
                path = path,
                apiLevel = api,
                abi = imageAbi,
                tagId = tag,
                sizeBytes = size,
                licenseIds = pkg.childElements("uses-license").mapNotNull { it.getAttribute("ref").takeIf { r -> r.isNotBlank() } },
            )
        }
        // 新 API 在前，同级按包路径排序——UI 列表次序确定
        images.sortWith(compareByDescending<SystemImage> { it.apiLevel }.thenBy { it.path })
        return SystemImageList(images)
    }

    /**
     * 安装组件。[acceptedLicenseIds] 是用户逐条勾选的**许可 ID**；
     * [reviewedLicenseHashes] 是勾选时**看到的 hash 快照**（id → hash，来自
     * [listPendingLicenses] 展示的那份）——安装时重新取清单核对，官方若已换条款
     * 就拒绝。核对全过才写接受记录，最后 `--install`。
     */
    fun install(
        packages: List<String>,
        acceptedLicenseIds: Set<String>,
        reviewedLicenseHashes: Map<String, String> = emptyMap(),
        isCancelled: () -> Boolean = { false },
    ): Outcome {
        if (packages.isEmpty()) return Outcome(reason = "没有选择要安装的组件")
        packages.firstOrNull { !PACKAGE_SPEC.matches(it) }?.let {
            return Outcome(reason = "组件名含有不允许的字符（只允许字母数字._;+-）：${it.take(60)}")
        }
        if (acceptedLicenseIds.isEmpty()) {
            return Outcome(reason = "还没有接受任何许可 —— 请先查看许可全文并逐条勾选同意")
        }
        acceptedLicenseIds.firstOrNull { !LICENSE_ID.matches(it) }?.let {
            return Outcome(reason = "许可 ID 格式不对：${it.take(60)}")
        }
        if (pathUnsafe(sdkRoot)) {
            return Outcome(reason = "SDK 根路径含命令行特殊字符，无法安全调用：${sdkRoot.path.take(120)}")
        }
        // 可自定义 exe 路径——只查 sdkRoot 挡不住 bat 本身的路径注入
        if (pathUnsafe(sdkmanagerBat)) {
            return Outcome(reason = "sdkmanager 路径含命令行特殊字符，无法安全调用：${sdkmanagerBat.path.take(120)}")
        }
        if (!sdkmanagerBat.isFile) return Outcome(reason = "找不到 sdkmanager（${sdkmanagerBat.path}）—— 先完成命令行工具的首次下载")

        // hash 从同一份 XML 解析自算；清单（含 sys-img 描述符）核对不了的包**先拒**：
        // 许可要求不明，绝不盲装——这一关在一切许可核对之前
        val lt = licenseTexts(packages)
        if (!lt.ok) return Outcome(reason = lt.reason)
        if (lt.missing.isNotEmpty()) {
            return Outcome(reason = "这些组件不在官方清单里（含 sys-img 描述符），许可无法核对，拒绝安装：${lt.missing.first().take(60)}")
        }
        val referenced = lt.docs.associateBy { it.id }
        val unknown = acceptedLicenseIds.filter { it !in referenced }
        if (unknown.isNotEmpty()) {
            return Outcome(reason = "勾选的许可不在所选组件的许可清单里（不存在或与所选组件无关）：${unknown.first().take(40)}")
        }
        // consent 快照：勾选时的 hash 必须与**现在**清单里的一致——官方换了条款就停，
        // 防止用户同意的是旧文本、写下的却是新文本的接受记录
        val changed = acceptedLicenseIds.firstOrNull { reviewedLicenseHashes[it] != referenced.getValue(it).hash }
        if (changed != null) {
            return Outcome(reason = "许可全文与同意时不一致（官方可能已更新条款，或未带同意快照）——请重新查看并逐条再次同意：${changed.take(40)}")
        }
        if (isCancelled()) {
            return Outcome(reason = "已取消（未写任何许可记录）")
        }

        // 接受记录：<sdkRoot>/licenses/<id>，多行 hash 追加（License.setAccepted 的 "%n%s" 格式）
        File(sdkRoot, "licenses").mkdirs()
        acceptedLicenseIds.forEach { id -> appendAcceptance(referenced.getValue(id)) }

        val run = runTool(
            sdkmanagerBat,
            listOf("--install") + packages + listOf("--sdk_root=${sdkRoot.absolutePath}"),
            INSTALL_TIMEOUT_SECONDS,
            isCancelled,
        )
        return when {
            run.cancelled -> Outcome(reason = "已取消 —— 已同意的许可记录保留；半装组件如报损坏，重装该组件即可")
            run.timedOut -> Outcome(reason = "安装超时（${INSTALL_TIMEOUT_SECONDS / 60} 分钟），进程树已强制结束")
            run.exitCode == 0 -> Outcome(installed = true)
            run.output.contains("were not accepted") -> Outcome(
                reason = "有组件要求的许可未全部接受（如依赖包的许可不在本次勾选里），未安装：${lastLine(run.output)}",
            )
            else -> Outcome(reason = "安装失败（退出码 ${run.exitCode}）：${lastLine(run.output)}")
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    private fun licFile(id: String): File = File(File(sdkRoot, "licenses"), id)

    /** 本机记录是否已含该 hash（checkAccepted 同口径：文件存在且任一行等于 hash）。 */
    private fun hashPresent(id: String, hash: String): Boolean = runCatching {
        licFile(id).readLines().any { it == hash }
    }.getOrDefault(false)

    /** 追加一条接受记录；已含该 hash 就不动（幂等），已有其他 hash 的文件只追加、不覆盖。 */
    private fun appendAcceptance(doc: LicenseDoc) {
        val f = licFile(doc.id)
        if (f.isFile && f.readLines().any { it == doc.hash }) return
        // License.setAccepted 的写法：String.format("%n%s", hash)——首行前也有换行
        f.appendText(String.format("%n%s", doc.hash))
    }

    /**
     * 官方站点列表（addons_list-N.xml）里找 vendor 的 sys-img 描述符 URL。
     * N 从最新已知版本往回探测，第一个能取到的就是当前口径——版本不写死，
     * Google 升版后旧版仍在线，回退自然落到能用的最高版。
     */
    private fun sysImgDescriptorUrl(vendor: String): String? {
        for (n in SITES_LIST_MAX_VERSION downTo 1) {
            val xml = try {
                fetchXml("${AndroidSdkBootstrap.REPO_BASE}addons_list-$n.xml")
            } catch (e: Exception) {
                continue
            }
            val doc = try {
                AndroidSdkBootstrap.parseRepositoryXml(xml)
            } catch (e: Exception) {
                continue
            }
            val sites = doc.getElementsByTagNameNS("*", "site")
            for (i in 0 until sites.length) {
                val url = (sites.item(i) as org.w3c.dom.Element)
                    .childElements("url").firstOrNull()?.textContent?.trim() ?: continue
                val segs = url.split('/')
                val k = segs.indexOf("sys-img")
                if (k >= 0 && k + 1 < segs.size && segs[k + 1] == vendor) {
                    return if (url.startsWith("http")) url else AndroidSdkBootstrap.REPO_BASE + url.removePrefix("/")
                }
            }
        }
        return null
    }

    private fun org.w3c.dom.Element.childElements(name: String): List<org.w3c.dom.Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>()
            .filter { it.localName == name }

    companion object {
        /** 组件包名（如 platform-tools / emulator / system-images;android-36;default;x86_64）：字母数字开头 + `._;+-`。 */
        val PACKAGE_SPEC = Regex("^[A-Za-z0-9][A-Za-z0-9._;+-]{0,199}$")

        /** 许可 ID（如 android-sdk-license）：xsd:ID 即 NCName，字母/下划线开头。 */
        val LICENSE_ID = Regex("^[A-Za-z_][A-Za-z0-9._-]{0,99}$")

        /** 站点列表里 vendor/架构段的白名单（android、google_apis_playstore、x86_64、arm64-v8a…）。 */
        val SITE_SEGMENT = Regex("^[a-z0-9_-]{1,64}$")

        /** cmd 在引号内仍展开 `%VAR%`（`!var!` 要延迟展开）——路径里出现即拒绝，不做转义赌注。 */
        private val PATH_UNSAFE = Regex("[%!\"&|<>^\r\n]")

        internal fun pathUnsafe(f: File): Boolean = PATH_UNSAFE.containsMatchIn(f.absolutePath)

        const val INSTALL_TIMEOUT_SECONDS = 20L * 60

        /** 默认查 AOSP 镜像（vendor=android）的 x86_64；其他 vendor/架构由 UI 显式传。 */
        const val SYS_IMG_VENDOR_DEFAULT = "android"
        const val SYS_IMG_ABI_DEFAULT = "x86_64"

        /** 官方站点列表当前最新是 addons_list-7.xml（对齐 Studio sites-list-7 schema）。 */
        const val SITES_LIST_MAX_VERSION = 7

        private fun lastLine(output: String): String =
            output.trim().lineSequence().lastOrNull()?.take(160).takeUnless { it.isNullOrBlank() } ?: "无输出"
    }
}

/** 仓库 XML 的轻量解析入口，供许可全文解析复用（Bootstrap 的解析器保持单一实现）。 */
internal fun fetchXmlNative(url: String): String {
    val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 30_000; requestMethod = "GET"
    }
    return try {
        if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
        c.inputStream.bufferedReader().use { it.readText() }
    } finally {
        c.disconnect()
    }
}
