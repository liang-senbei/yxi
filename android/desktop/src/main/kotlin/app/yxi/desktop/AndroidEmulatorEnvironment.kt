package app.yxi.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Windows 本地 Android SDK/AVD 环境发现 —— 一键模拟器的第一步：**只「看」，不「装」**。
 *
 * 给 root（首次安装向导 / 运行 UI）回答三个问题：
 * 1. SDK 在哪（环境变量 → LocalAppData 标准路径，逐个候选试过去）；
 * 2. 四样工具齐不齐（emulator / adb / sdkmanager / avdmanager，齐了 [Result.usable] 才 true）；
 * 3. 有哪些 AVD 可跑（优先 `emulator -list-avds`，失败退回扫描 avd 目录的 .ini）。
 *
 * ⚠️ **只探测，绝不越界**：不安装组件、不启动模拟器、不接受许可证、不写任何文件
 * （连临时文件都不写——探测是纯读）。sdkmanager/avdmanager 只查存在性：
 * 「装」和「接受许可」是改动系统的动作，必须由 root 明确发起，这里只报告缺什么。
 *
 * ⚠️ **外部命令只有 `emulator -list-avds` 一个**：参数列表起进程（不经过 shell，
 * 路径带空格也不会断）、10 秒超时强杀、stderr 整路丢弃（SDK 工具爱把进度写 stderr，
 * 混进 stdout 会污染 AVD 名单）。
 *
 * ⚠️ **Windows 适配器**：工具文件名固定 `.exe` / `.bat`。在别的平台上跑，
 * 结果只会是「全缺」——这是有意的（Yxi 桌面版就是 Windows 版），不做跨平台猜测。
 */
class AndroidEmulatorEnvironment(
    /** 环境变量读取。测试注假表，生产读真环境。 */
    private val env: (String) -> String? = { System.getenv(it) },
    /** `emulator -list-avds` 的执行器，返回 (stdout 原始行, 错误)。测试注假的，生产走真进程。 */
    private val listAvds: (File) -> Pair<List<String>, String?> = ::runEmulatorListAvds,
) {

    /** 找到的一件工具。 */
    data class Tool(val name: String, val path: String)

    /**
     * 结构化探测结果 —— 调用方（root）照着它决定：装什么（[Result.missing]）、
     * 装到哪算成功（[Result.sdkRoot]）、能跑哪些 AVD（[Result.avds]）。
     */
    data class Result(
        /** 选定的 SDK 根；null = 候选路径一个都不存在。 */
        val sdkRoot: String?,
        /** 检查过的候选根，按优先级排列（含不存在的）—— 排查「为什么没找到」用。 */
        val rootsChecked: List<String>,
        /** 找到的工具（绝对路径）。 */
        val tools: List<Tool>,
        /** 缺的工具名，emulator / adb / sdkmanager / avdmanager 的固定顺序。 */
        val missing: List<String>,
        /** AVD 名称列表（可能为空 = 一个都没建过，不是探测失败——区别看 [Result.errors]）。 */
        val avds: List<String>,
        /** AVD 名单的来源（`emulator -list-avds` / avd 目录扫描）；null = 没找到 AVD。 */
        val avdSource: String?,
        /** 非致命问题（命令超时、执行失败等）—— 探测本身不打断，但要说给 root 听。 */
        val errors: List<String>,
    ) {
        /** 四样工具齐 = 能装能跑，剩下的（建 AVD）是 root 自己的事。 */
        val usable: Boolean get() = sdkRoot != null && missing.isEmpty()
    }

    /**
     * 工具在 SDK 根下的候选位置，按新旧排列，命中第一个存在的为止。
     * ⚠️ cmdline-tools（现在的标准）查不到再退 tools/bin（老版 SDK 布局）——
     *    老布局的 sdkmanager 在 JDK 17+ 下本来也跑不动了，但「查得到、报上去」
     *    比「报缺失、让 root 重装」诚实。
     */
    private val toolSpecs = listOf(
        "emulator" to listOf(listOf("emulator", "emulator.exe")),
        "adb" to listOf(listOf("platform-tools", "adb.exe")),
        "sdkmanager" to listOf(
            listOf("cmdline-tools", "latest", "bin", "sdkmanager.bat"),
            listOf("tools", "bin", "sdkmanager.bat"),
        ),
        "avdmanager" to listOf(
            listOf("cmdline-tools", "latest", "bin", "avdmanager.bat"),
            listOf("tools", "bin", "avdmanager.bat"),
        ),
    )

    fun discover(): Result {
        val roots = rootCandidates()
        var chosen: File? = null
        val tools = mutableListOf<Tool>()
        // 首个「存在且至少有一件工具」的根胜出；全是空目录时退而记第一个存在的，
        // 让 missing 报在那个根上，而不是把真实存在的 SDK 根报成 null。
        for (root in roots.map(::File)) {
            if (!root.isDirectory) continue
            val found = toolSpecs.mapNotNull { (name, candidates) ->
                candidates.firstOrNull { rel -> File(root, rel.joinToString(File.separator)).isFile }
                    ?.let { Tool(name, File(root, it.joinToString(File.separator)).absolutePath) }
            }
            if (found.isNotEmpty()) { chosen = root; tools += found; break }
            if (chosen == null) chosen = root
        }
        val missing = toolSpecs.map { it.first }.filter { name -> name !in tools.map { it.name } }

        // AVD：优先问 emulator 本尊（它认 ANDROID_AVD_HOME 等一整套规则，比我们猜的准）；
        // 它没给（没装/超时/一个都没有）再扫 .ini 目录兜底。
        val errors = mutableListOf<String>()
        var avds: List<String> = emptyList()
        var avdSource: String? = null
        tools.firstOrNull { it.name == "emulator" }?.let { emu ->
            val (raw, err) = listAvds(File(emu.path))
            err?.let { errors += it }
            // 清洗（去空白/空行/重复）放在 discover 这一层做，而不是 runner 里——
            // runner 是注入点，返回什么不该影响名单的干净程度
            // 清洗（trim 掉 Windows 输出的 \r、滤空行、去重）放在 discover 这一层做，
            // 而不是 runner 里——runner 是注入点，返回什么不该影响名单的干净程度
            val names = raw.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (names.isNotEmpty()) { avds = names; avdSource = "emulator -list-avds" }
        }
        if (avds.isEmpty()) {
            val scanned = scanAvdInis()
            if (scanned.isNotEmpty()) { avds = scanned; avdSource = "avd 目录扫描" }
        }

        return Result(
            sdkRoot = chosen?.absolutePath,
            rootsChecked = roots,
            tools = tools,
            missing = missing,
            avds = avds,
            avdSource = avdSource,
            errors = errors,
        )
    }

    /**
     * SDK 根的候选，按可信度排列：ANDROID_HOME（Android Studio 自己设的）、
     * ANDROID_SDK_ROOT（老变量，还在文档里）、LocalAppData 标准安装位
     * （Android Studio 默认就装 `%LOCALAPPDATA%\Android\Sdk`）。
     * LOCALAPPDATA 缺了再从 USERPROFILE 拼——命令行环境有时只有后者。
     */
    private fun rootCandidates(): List<String> = listOfNotNull(
        env("ANDROID_HOME"),
        env("ANDROID_SDK_ROOT"),
        env("LOCALAPPDATA")?.let { File(File(it, "Android"), "Sdk").absolutePath },
        env("USERPROFILE")?.let { File(File(File(it, "AppData"), "Local"), "Android").let { d -> File(d, "Sdk") }.absolutePath },
    ).filter { it.isNotBlank() }.distinct()

    /**
     * 扫 AVD 目录兜底。`.ini` 文件名即 AVD 名（内容 `avd.ini.path=` 指向真身，
     * 这里只报名单不解析内容——解析它就要碰用户文件结构，没必要）。
     */
    private fun scanAvdInis(): List<String> = avdDirs().firstOrNull { it.isDirectory }
        ?.listFiles { f -> f.isFile && f.name.endsWith(".ini") }
        ?.map { it.name.removeSuffix(".ini") }
        ?.filter { it.isNotBlank() }
        ?.sorted()
        ?: emptyList()

    /** AVD 目录候选：ANDROID_AVD_HOME 是官方变量；其余两个是默认位（`%LOCALAPPDATA%\.android\avd`）。 */
    private fun avdDirs(): List<File> = listOfNotNull(
        env("ANDROID_AVD_HOME")?.let(::File),
        env("LOCALAPPDATA")?.let { File(File(it, ".android"), "avd") },
        env("USERPROFILE")?.let { File(File(it, ".android"), "avd") },
    ).distinct()

    companion object {
        /** `-list-avds` 是毫秒级的查询，10 秒还回不来的一律当挂了强杀，不让探测卡住 UI。 */
        private const val LIST_TIMEOUT_SECONDS = 10L

        /**
         * 真正起进程跑 `emulator -list-avds`。
         * ⚠️ **先 waitFor 再读输出**是故意的：名单撑死几十行，64KB 管道缓冲装得下，
         * 不会因管道满把进程卡死；反过来读在前的话，进程一挂 readBytes 就永远不回。
         * 超时 `destroyForcibly` 后再短暂等一把，让进程真正消失再返回。
         */
        internal fun runEmulatorListAvds(exe: File): Pair<List<String>, String?> = try {
            val p = ProcessBuilder(exe.absolutePath, "-list-avds")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!p.waitFor(LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                p.waitFor(2, TimeUnit.SECONDS)
                emptyList<String>() to "emulator -list-avds 超时（${LIST_TIMEOUT_SECONDS}s），已强制结束"
            } else {
                val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
                // 只拆行；清洗（trim/空行/去重）统一在 discover() 做
                out.lineSequence().toList() to null
            }
        } catch (e: Exception) {
            emptyList<String>() to "emulator -list-avds 执行失败：${e.message?.take(80)}"
        }
    }
}
