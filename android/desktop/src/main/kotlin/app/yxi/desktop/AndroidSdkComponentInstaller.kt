package app.yxi.desktop

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 一次外部工具运行的结果。exitCode=-1 且 cancelled/timedOut=false 表示没跑起来（output 里是原因）。 */
data class ToolRun(val exitCode: Int, val output: String, val cancelled: Boolean = false, val timedOut: Boolean = false)

/**
 * 真跑 SDK 工具（sdkmanager/avdmanager 的 .bat）。
 * ⚠️ **参数列表不等于安全**：Windows 上 `cmd /c` 仍会解释元字符（`%VAR%`/`!var!`/
 * 重定向/管道在引号内照样展开），所以安全靠**上游白名单**：包名/许可哈希/AVD 名
 * 全匹配 regex 白名单、路径参数查 cmd 元字符——无法安全传入的输入直接拒绝，
 * 不做转义赌注。输出重定向到临时文件（避开设管道边跑边读的死锁），
 * 轮询取消/超时后 destroyForcibly，取消/超时都在 [ToolRun] 里有回执。
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
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (true) {
            if (p.waitFor(500, TimeUnit.MILLISECONDS)) return ToolRun(p.exitValue(), out.readText())
            if (isCancelled()) {
                p.destroyForcibly(); p.waitFor(5, TimeUnit.SECONDS)
                return ToolRun(-1, out.readText(), cancelled = true)
            }
            if (System.currentTimeMillis() > deadline) {
                p.destroyForcibly(); p.waitFor(5, TimeUnit.SECONDS)
                return ToolRun(-1, out.readText(), timedOut = true)
            }
        }
    } finally {
        out.delete()
    }
}

/**
 * AndroidSdkComponentInstaller —— 在 Yxi 自有 SDK 根（`Store.dir/android-sdk`）上
 * 安装模拟器运行件（emulator / platform-tools / 用户显式选择的 system-images 等），
 * 由 root UI 显式发起。
 *
 * **许可纪律（只接受用户点头的那些）**：
 * - 待接受许可的**哈希**以本机 sdkmanager `--licenses` 输出为准（CI 同款非交互
 *   记录法：`<sdkRoot>/licenses/<hash>`）。哈希无法从许可文本离线推导（已用官方
 *   XML 实测各种归一化都对不上 sdkmanager 的命名），所以它只来自 sdkmanager 本尊；
 * - 许可**全文**供 UI 展示，来自官方仓库 XML 里具体包的 uses-license 引用
 *   （[licenseTexts]）；
 * - [install] 先取 `--licenses` 的当前待接受名单，**用户勾选集合必须是它的子集**，
 *   之外的一个都不写——绝不 --licenses 自动 yes、绝不替用户全收。
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
    /** 官方仓库清单获取器，许可全文解析用；测试注假 XML。 */
    private val fetchXml: (String) -> String = ::fetchXmlNative,
) {

    data class LicenseList(val licenses: List<String> = emptyList(), val reason: String? = null) {
        val ok: Boolean get() = reason == null
    }

    /** 一个许可的全文（来自官方 XML），给 UI 展示用。 */
    data class LicenseDoc(val id: String, val text: String)

    data class LicenseTexts(
        val docs: List<LicenseDoc> = emptyList(),
        /** 官方清单里没找到的包（system-images 不在 repository2-3 里，单独描述符）。 */
        val missing: List<String> = emptyList(),
        val reason: String? = null,
    ) {
        val ok: Boolean get() = reason == null
    }

    data class Outcome(val installed: Boolean = false, val reason: String? = null) {
        val ok: Boolean get() = installed
    }

    /** 列出当前**待接受**的许可哈希（sdkmanager 本尊口径），给 UI 展示、由用户逐条勾选。 */
    fun listLicenses(isCancelled: () -> Boolean = { false }): LicenseList {
        val unaccepted = try {
            fetchUnacceptedLicenses(isCancelled)
        } catch (e: SetupException) {
            return LicenseList(reason = e.reason)
        }
        if (unaccepted.isEmpty()) return LicenseList(reason = "待接受许可为空（可能已全部接受过）")
        return LicenseList(unaccepted)
    }

    /**
     * 从官方仓库 XML 按**具体包**的 uses-license 引用解析许可全文，给 UI 展示。
     * 只解析清单里存在的包；system-images 不在 repository2-3（单独 sys-img 描述符），
     * 会如实出现在 [LicenseTexts.missing]。
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
     * 安装组件。[acceptedLicenses] 必须是本机 sdkmanager 当前待接受名单的**子集**
     *（用户逐条勾选的结果）——先核对、只写勾选过的记录文件，再 --install。
     */
    fun install(
        packages: List<String>,
        acceptedLicenses: Set<String>,
        isCancelled: () -> Boolean = { false },
    ): Outcome {
        if (packages.isEmpty()) return Outcome(reason = "没有选择要安装的组件")
        packages.firstOrNull { !PACKAGE_SPEC.matches(it) }?.let {
            return Outcome(reason = "组件名含有不允许的字符（只允许字母数字._;+-）：${it.take(60)}")
        }
        acceptedLicenses.firstOrNull { !LICENSE_HASH.matches(it) }?.let {
            return Outcome(reason = "许可哈希格式不对（应为 40 位十六进制）：${it.take(60)}")
        }
        if (acceptedLicenses.isEmpty()) {
            return Outcome(reason = "还没有接受任何许可 —— 请先查看许可全文并逐条勾选同意")
        }
        if (pathUnsafe(sdkRoot)) {
            return Outcome(reason = "SDK 根路径含命令行特殊字符，无法安全调用：${sdkRoot.path.take(120)}")
        }
        if (!sdkmanagerBat.isFile) return Outcome(reason = "找不到 sdkmanager（${sdkmanagerBat.path}）—— 先完成命令行工具的首次下载")

        // 核对：只接受 sdkmanager 自己报出来的待接受哈希；之外的（不存在/已接受）
        // 一个都不写，从机制上排除「替用户 yes 全部许可」
        val unaccepted = try {
            fetchUnacceptedLicenses(isCancelled)
        } catch (e: SetupException) {
            return Outcome(reason = e.reason)
        }
        val unknown = acceptedLicenses.filter { it !in unaccepted }
        if (unknown.isNotEmpty()) {
            return Outcome(reason = "勾选的许可不在当前待接受名单里（可能已接受过或不存在）：${unknown.first().take(40)}…")
        }

        val licDir = File(sdkRoot, "licenses").apply { mkdirs() }
        acceptedLicenses.forEach { hash -> File(licDir, hash).writeText("$hash\n") }

        val run = runTool(
            sdkmanagerBat,
            listOf("--install") + packages + listOf("--sdk_root=${sdkRoot.absolutePath}"),
            INSTALL_TIMEOUT_SECONDS,
            isCancelled,
        )
        return when {
            run.cancelled -> Outcome(reason = "已取消 —— 未接受新许可；半装组件如报损坏，重装该组件即可")
            run.timedOut -> Outcome(reason = "安装超时（${INSTALL_TIMEOUT_SECONDS / 60} 分钟），进程已强制结束")
            run.exitCode == 0 -> Outcome(installed = true)
            else -> Outcome(reason = "安装失败（退出码 ${run.exitCode}）：${lastLine(run.output)}")
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    /** 跑 `sdkmanager --licenses`，从输出提取待接受哈希（40 位十六进制 token）。 */
    private fun fetchUnacceptedLicenses(isCancelled: () -> Boolean): List<String> {
        if (!sdkmanagerBat.isFile) {
            throw SetupException("找不到 sdkmanager（${sdkmanagerBat.path}）—— 先完成命令行工具的首次下载")
        }
        val run = runTool(sdkmanagerBat, listOf("--licenses", "--sdk_root=${sdkRoot.absolutePath}"), LICENSES_TIMEOUT_SECONDS, isCancelled)
        return when {
            run.cancelled -> throw SetupException("已取消")
            run.timedOut -> throw SetupException("列出许可超时（${LICENSES_TIMEOUT_SECONDS}s）")
            run.exitCode != 0 -> throw SetupException("列出许可失败（退出码 ${run.exitCode}）：${lastLine(run.output)}")
            else -> ANDROID_HASH.findAll(run.output).map { it.value }.distinct().toList()
        }
    }

    private class SetupException(val reason: String) : Exception(reason)

    companion object {
        /** 组件包名（如 system-images;android-35;google_apis;x86_64）：字母数字开头 + `._;+-`。 */
        val PACKAGE_SPEC = Regex("^[A-Za-z0-9][A-Za-z0-9._;+-]{0,199}$")

        /** 许可哈希：40 位十六进制（sdkmanager 的记录文件名口径）。 */
        val LICENSE_HASH = Regex("^[0-9a-fA-F]{40}$")

        /** 从工具输出提取许可哈希 token（容忍 bullet/前后缀）。 */
        private val ANDROID_HASH = Regex("[0-9a-fA-F]{40}")

        /** cmd 在引号内仍展开 `%VAR%`（`!var!` 要延迟展开）——路径里出现即拒绝，不做转义赌注。 */
        private val PATH_UNSAFE = Regex("[%!\"&|<>^\r\n]")

        internal fun pathUnsafe(f: File): Boolean = PATH_UNSAFE.containsMatchIn(f.absolutePath)

        const val LICENSES_TIMEOUT_SECONDS = 60L
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
