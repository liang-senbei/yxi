package app.yxi.desktop

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** 一次外部工具运行的结果。exitCode=-1 且 cancelled/timedOut=false 表示没跑起来（output 里是原因）。 */
data class ToolRun(val exitCode: Int, val output: String, val cancelled: Boolean = false, val timedOut: Boolean = false)

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
 * - 取消/超时杀 **进程树**：`cmd /c` 的父进程死了 Java 子进程不一定死，
 *   Windows 上 `taskkill /F /T /PID` 杀整棵，其余平台 destroyForcibly 兜底；
 * - 输出重定向临时文件（避开设管道边跑边读的死锁），只回读**末尾 64KB**
 *   （诊断信息都在结尾，回执只需要它），临时文件不设上限的旧实现已收紧。
 */
internal fun runToolNative(exe: File, args: List<String>, timeoutSeconds: Long, isCancelled: () -> Boolean): ToolRun {
    val out = File.createTempFile("yxi-sdktool-", ".log")
    try {
        val p = try {
            ProcessBuilder(listOf("cmd", "/c", exe.absolutePath) + args)
                .redirectErrorStream(true)
                .redirectOutput(out)
                .start()
        } catch (e: IOException) {
            return ToolRun(-1, "无法启动 ${exe.name}：${e.message?.take(120)}（cmd /c 仅在 Windows 上可用）")
        }
        runCatching { p.outputStream.close() }   // stdin EOF：任何交互提示都走「拒绝」，绝不盲 yes
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (true) {
            if (p.waitFor(500, TimeUnit.MILLISECONDS)) return ToolRun(p.exitValue(), tailOf(out))
            if (isCancelled()) {
                killTree(p)
                return ToolRun(-1, tailOf(out), cancelled = true)
            }
            if (System.currentTimeMillis() > deadline) {
                killTree(p)
                return ToolRun(-1, tailOf(out), timedOut = true)
            }
        }
    } finally {
        out.delete()
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

/** 只回读输出**末尾**，防无上限读入；截断点可能切在多字节字符中间，容错解码。 */
private fun tailOf(out: File, maxBytes: Long = 64L * 1024): String = runCatching {
    val len = out.length()
    if (len <= maxBytes) return@runCatching out.readText()
    RandomAccessFile(out, "r").use { raf ->
        raf.seek(len - maxBytes)
        val bytes = ByteArray(maxBytes.toInt())
        val n = raf.read(bytes)
        String(bytes, 0, if (n < 0) 0 else n, Charsets.UTF_8)
    }
}.getOrDefault("")

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
 * - 用户按**许可 ID** 勾选，hash 由同一份官方 XML 解析自算——不接受外部传 hash
 *   （裸 40-hex 证明不了它对应哪段文本）；勾选集合必须是所选包 uses-license
 *   引用的**子集**，之外的一个不写——机制上排除「替用户 yes 全部许可」；
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
    /** 官方仓库清单获取器，许可文本解析用；测试注假 XML。 */
    private val fetchXml: (String) -> String = ::fetchXmlNative,
) {

    /** 一个许可：ID + 全文原值（不 trim，hash 对它才算得对）+ sha1 hash（License.java 口径）。 */
    data class LicenseDoc(val id: String, val text: String, val hash: String)

    data class LicenseTexts(
        val docs: List<LicenseDoc> = emptyList(),
        /** 官方清单里没找到的包（system-images 不在 repository2-3 里，单独 sys-img 描述符）。 */
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

    data class Outcome(val installed: Boolean = false, val reason: String? = null) {
        val ok: Boolean get() = installed
    }

    /**
     * 待接受许可清单：官方 XML 按所选包的 uses-license 引用解析许可全文/hash，
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
     * 从官方仓库 XML 按**具体包**的 uses-license 引用解析许可全文与 hash。
     * 文本是解析原值**不 trim**（hash 对未 trim 的值才算得对）；system-images 等
     * 不在 repository2-3 的包（单独 sys-img 描述符）如实进 [LicenseTexts.missing]。
     */
    fun licenseTexts(packages: List<String>, repoUrl: String = AndroidSdkBootstrap.REPO_URL): LicenseTexts {
        if (packages.isEmpty()) return LicenseTexts(reason = "没有选择组件，无从解析许可")
        val xml = try {
            fetchXml(repoUrl)
        } catch (e: Exception) {
            return LicenseTexts(reason = "获取官方仓库清单失败：${e.message?.take(120)}")
        }
        val doc = try {
            AndroidSdkBootstrap.parseRepositoryXml(xml)
        } catch (e: Exception) {
            return LicenseTexts(reason = "仓库清单解析失败：${e.message?.take(120)}")
        }
        return AndroidSdkBootstrap.resolveLicenseTexts(doc, packages)
    }

    /**
     * 安装组件。[acceptedLicenseIds] 是用户逐条勾选的**许可 ID**（[listPendingLicenses]
     * 给出的那些）——hash 从同一份官方 XML 自算、勾选集合必须是其 uses-license 引用的
     * 子集，核对通过才写接受记录，最后 `--install`。
     */
    fun install(
        packages: List<String>,
        acceptedLicenseIds: Set<String>,
        isCancelled: () -> Boolean = { false },
        reviewedLicenseHashes: Map<String, String> = emptyMap(),
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
        if (!sdkmanagerBat.isFile) return Outcome(reason = "找不到 sdkmanager（${sdkmanagerBat.path}）—— 先完成命令行工具的首次下载")

        // hash 从同一份 XML 解析自算；勾选的 id 必须是所选包引用过的——无关/不存在的许可一个不写
        val lt = licenseTexts(packages)
        if (!lt.ok) return Outcome(reason = lt.reason)
        val referenced = lt.docs.associateBy { it.id }
        if (lt.missing.isNotEmpty()) return Outcome(reason = "尚未取得这些组件的许可信息：${lt.missing.joinToString()}，未安装")
        val unknown = acceptedLicenseIds.filter { it !in referenced }
        if (unknown.isNotEmpty()) {
            return Outcome(reason = "勾选的许可不在所选组件的许可清单里（不存在或与所选组件无关）：${unknown.first().take(40)}")
        }
        if (acceptedLicenseIds.any { reviewedLicenseHashes[it] != referenced[it]?.hash })
            return Outcome(reason = "许可内容未确认或已变化，请重新阅读并确认后再安装")
        if (isCancelled()) return Outcome(reason = "已取消，未写入许可记录")

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
                reason = "有组件要求的许可未全部接受（system-images 等清单外包需另行核对），未安装：${lastLine(run.output)}",
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

    companion object {
        /** 组件包名（如 platform-tools / emulator）：字母数字开头 + `._;+-`。 */
        val PACKAGE_SPEC = Regex("^[A-Za-z0-9][A-Za-z0-9._;+-]{0,199}$")

        /** 许可 ID（如 android-sdk-license）：xsd:ID 即 NCName，字母/下划线开头。 */
        val LICENSE_ID = Regex("^[A-Za-z_][A-Za-z0-9._-]{0,99}$")

        /** cmd 在引号内仍展开 `%VAR%`（`!var!` 要延迟展开）——路径里出现即拒绝，不做转义赌注。 */
        private val PATH_UNSAFE = Regex("[%!\"&|<>^\r\n]")

        internal fun pathUnsafe(f: File): Boolean = PATH_UNSAFE.containsMatchIn(f.absolutePath)

        const val INSTALL_TIMEOUT_SECONDS = 20L * 60

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
