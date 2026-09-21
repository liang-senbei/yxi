package app.yxi.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 本机 adb 设备桥 —— 给 root 的模拟器 UI 连真机/模拟器用（一键模拟器的「装 APK、起应用」半步）。
 * 只封装三条命令：`adb devices -l`、`adb -s <serial> install <apk>`、
 * `adb -s <serial> shell monkey -p <pkg> -c android.intent.category.LAUNCHER 1`。
 *
 * ⚠️ **serial 永远显式传入**：设备操作一律要求调用方给 serial，桥里没有
 * 「枚举后拿第一个」的路径——多设备时替用户挑设备是错的。包名同样只收显式参数，
 * 绝不猜业务包（枚举已装应用属于读业务数据，越界不做）。
 *
 * ⚠️ **外部纪律**：参数列表起进程（不经过 shell，路径带空格也不会断）、每条命令
 * 有超时（超时强杀）、输出封顶 64KB、错误码/输出行都收进来；**ADB 失败**（进程起不来、
 * 超时、退出码非 0、输出确认不了结果）与**安装被包管理器拒**（输出 `Failure [...]`）
 * 分开报——版本间 install 退出码不可靠，`Failure` 行才是裁决。
 *
 * ⚠️ **I/O 一律不在 UI 线程**：操作都是 suspend，进程等待在 [Dispatchers.IO]。
 * 只安装/启动，不启动模拟器、不读写设备上的业务数据、不改动 SDK。
 *
 * ⚠️ **Windows 适配器**：adb 按固定名 `platform-tools/adb.exe` 找（同
 * [AndroidEmulatorEnvironment] 的约定，Yxi 桌面版就是 Windows 版），其余平台只会
 * 「找不到」——有意为之，不做跨平台猜测。
 */
class AndroidDeviceBridge(
    /** 环境变量读取。测试注假表，生产读真环境。 */
    private val env: (String) -> String? = { System.getenv(it) },
    /** root 托管 SDK 的根（AndroidEmulatorEnvironment 探测到的），作为 adb 路径首选候选；可空。 */
    private val managedSdkRoot: File? = null,
    /** adb 执行器，收**参数列表**返回运行结果。测试注假的，生产走真进程。 */
    private val run: (List<String>) -> AdbRun = ::runAdbProcess,
) {

    /** 一次进程运行的原始结果。[error] 非 null = 进程没跑到有退出码那一步（超时/起不来）。 */
    data class AdbRun(val exitCode: Int?, val stdout: String, val stderr: String, val error: String? = null)

    /** `adb devices -l` 的一台设备。[state] 原样保留（device/offline/unauthorized 之外的自定义为诚实）。 */
    data class Device(val serial: String, val state: String, val model: String) {
        val online get() = state == "device"
        val offline get() = state == "offline"
        val unauthorized get() = state == "unauthorized"
    }

    /** 设备枚举结果。[error] 非 null = adb 本身没跑成，[devices] 必为空名单。 */
    data class DeviceList(val devices: List<Device>, val error: String?)

    /** 设备操作的结论。[detail] 是给人看的一句话（含输出行原文尾巴）。 */
    data class AdbOutcome(val kind: Kind, val detail: String) {
        enum class Kind {
            /** 成功（install 见 `Success` / monkey 见 `Events injected`）。 */
            Done,
            /** ADB 层失败：起不来、超时、退出码非 0、输出确认不了结果。 */
            AdbFailure,
            /** 进程跑完但被拒：install 的 `Failure [...]`、monkey 的 abort。 */
            CommandFailure,
            /** 请求本身不合法（serial 空、APK 不是现存普通 .apk、包名字符不合规）——进程根本没起。 */
            InvalidRequest,
        }

        val ok get() = kind == Kind.Done
    }

    /** adb 可执行文件路径：托管 SDK → ANDROID_HOME → ANDROID_SDK_ROOT → LocalAppData 标准位，
     *  命中第一个存在的为止；全都查过没有 → failure（带查过的路径清单，排查「为什么没找到」用）。 */
    fun resolveAdb(): Result<String> {
        val checked = mutableListOf<String>()
        for (root in adbRootCandidates()) {
            val exe = File(root, listOf("platform-tools", "adb.exe").joinToString(File.separator))
            checked += exe.absolutePath
            if (exe.isFile) return Result.success(exe.absolutePath)
        }
        return Result.failure(IllegalStateException("没找到 platform-tools/adb.exe，查过：${checked.joinToString("；")}；也可把 AndroidEmulatorEnvironment 探测到的 adb 路径直接传入"))
    }

    /** 设备枚举：`adb devices -l`，区分 device / offline / unauthorized（其余状态原样上报）。 */
    suspend fun devices(adbPath: String? = null): DeviceList = withContext(Dispatchers.IO) {
        val exe = adbPath ?: resolveAdb().getOrElse {
            return@withContext DeviceList(emptyList(), it.message ?: "没找到 adb")
        }
        val r = run(listOf(exe, "devices", "-l"))
        when {
            r.error != null -> DeviceList(emptyList(), r.error)
            r.exitCode != 0 -> DeviceList(emptyList(), "adb devices 失败（退出码 ${r.exitCode}）${r.stderr.trim().take(120)}")
            else -> DeviceList(parseDevices(r.stdout), null)
        }
    }

    /**
     * 给**指定 serial** 的设备装 APK。APK 必须是现存的普通 `.apk` 文件（存在、是文件、
     * 扩展名对得上），否则进程根本不起。区分 [AdbOutcome.Kind.AdbFailure] 与
     * [AdbOutcome.Kind.CommandFailure]（包管理器的 `Failure [...]` 行）。
     */
    suspend fun install(adbPath: String? = null, serial: String, apk: File): AdbOutcome = withContext(Dispatchers.IO) {
        if (serial.isBlank()) return@withContext AdbOutcome(AdbOutcome.Kind.InvalidRequest, "必须显式指定设备 serial，不替用户挑设备")
        if (!apk.isFile || !apk.name.endsWith(".apk", ignoreCase = true))
            return@withContext AdbOutcome(AdbOutcome.Kind.InvalidRequest, "APK 必须是现存的普通 .apk 文件：${apk.absolutePath}")
        val exe = adbPath ?: resolveAdb().getOrElse {
            return@withContext AdbOutcome(AdbOutcome.Kind.AdbFailure, it.message ?: "没找到 adb")
        }
        val r = run(listOf(exe, "-s", serial, "install", apk.absolutePath))
        classify(r, successMarker = "Success")
    }

    /**
     * 在**指定 serial** 的设备上启动**指定包名**的应用。包名只收显式参数（字符集
     * `[A-Za-z0-9_.]`、不得以 `-` 开头——参数列表起进程也架不住把包名写成 adb 的旗标），
     * 绝不猜业务包；用 monkey LAUNCHER 类目起默认入口，不需要知道 Activity 名。
     */
    suspend fun launch(adbPath: String? = null, serial: String, packageName: String): AdbOutcome = withContext(Dispatchers.IO) {
        if (serial.isBlank()) return@withContext AdbOutcome(AdbOutcome.Kind.InvalidRequest, "必须显式指定设备 serial，不替用户挑设备")
        if (!Regex("""^[A-Za-z0-9_][A-Za-z0-9_.]*$""").matches(packageName))
            return@withContext AdbOutcome(AdbOutcome.Kind.InvalidRequest, "包名不合规（只收 [A-Za-z0-9_.] 且不以 - 开头）：${packageName.take(40)}")
        val exe = adbPath ?: resolveAdb().getOrElse {
            return@withContext AdbOutcome(AdbOutcome.Kind.AdbFailure, it.message ?: "没找到 adb")
        }
        val r = run(listOf(exe, "-s", serial, "shell", "monkey", "-p", packageName,
            "-c", "android.intent.category.LAUNCHER", "1"))
        classify(r, successMarker = "Events injected")
    }

    /** 进程结果 → 结论：先看执行层 error，再看包管理器的裁决行（退出码版本间不可靠），
     *  再看退出码，最后是「跑完了但输出确认不了结果」的诚实未知。 */
    private fun classify(r: AdbRun, successMarker: String): AdbOutcome = when {
        r.error != null -> AdbOutcome(AdbOutcome.Kind.AdbFailure, r.error)
        r.stdout.lineSequence().any { it.trimStart().startsWith("Failure") } ->
            AdbOutcome(AdbOutcome.Kind.CommandFailure,
                "设备/包管理器拒绝：" + r.stdout.lineSequence().first { it.trimStart().startsWith("Failure") }.trim().take(160))
        r.exitCode != 0 -> AdbOutcome(AdbOutcome.Kind.AdbFailure,
            "adb 失败（退出码 ${r.exitCode}）${(r.stderr.ifBlank { r.stdout }).trim().take(120)}")
        successMarker in r.stdout -> AdbOutcome(AdbOutcome.Kind.Done, r.stdout.trim().take(120))
        else -> AdbOutcome(AdbOutcome.Kind.AdbFailure, "adb 跑完了但输出确认不了结果（无 ${successMarker} 标记）：${r.stdout.trim().take(80)}")
    }

    /** `adb devices -l` 输出 → 设备名单：滤表头/空行/daemon 嘴碎行，`serial 状态 model:...` 按空白切。 */
    private fun parseDevices(stdout: String): List<Device> = stdout.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("*") && !it.startsWith("List of devices attached") }
        .mapNotNull { line ->
            val tokens = line.split(Regex("""\s+"""))
            if (tokens.size < 2) return@mapNotNull null
            Device(serial = tokens[0], state = tokens[1],
                model = tokens.drop(2).firstOrNull { it.startsWith("model:") }?.removePrefix("model:").orEmpty())
        }
        .toList()

    /** adb 所在 SDK 根的候选，按可信度排列（同 [AndroidEmulatorEnvironment.rootCandidates] 的次序）。
     *  不存在的根**保留**在候选里——resolveAdb 的「查过」清单要能回答「为什么没找到」。 */
    private fun adbRootCandidates(): List<File> = listOfNotNull(
        managedSdkRoot,
        env("ANDROID_HOME")?.let(::File),
        env("ANDROID_SDK_ROOT")?.let(::File),
        env("LOCALAPPDATA")?.let { File(File(it, "Android"), "Sdk") },
    ).distinct()

    companion object {
        /** devices/launch 是毫秒级查询；install 要拷 APK，放宽到 5 分钟。超时一律强杀不让 UI 卡住。 */
        private const val DEVICES_TIMEOUT_SECONDS = 10L
        private const val LAUNCH_TIMEOUT_SECONDS = 15L
        private const val INSTALL_TIMEOUT_SECONDS = 300L
        /** 输出封顶：这几条命令的正常输出都是几十行级，64KB 管道缓冲装得下（wait-then-read 不会卡死）。 */
        private const val MAX_OUTPUT_BYTES = 64 * 1024

        /**
         * 真正起进程跑 adb。⚠️ **先 waitFor 再读输出**是故意的：输出被封顶在 64KB 管道
         * 缓冲内，不会因管道满把进程卡死；反过来读在前的话，进程一挂 readNBytes 就永远不回。
         * 超时 `destroyForcibly` 后再短暂等一把，让进程真正消失再返回。
         */
        internal fun runAdbProcess(args: List<String>): AdbRun = try {
            // 按子命令挑超时：install 要拷 APK 放宽；devices/launch 是秒级查询。
            // 不看 args[1]——带 -s 时它永远是 "-s"。
            val timeout = when {
                "install" in args -> INSTALL_TIMEOUT_SECONDS
                "monkey" in args -> LAUNCH_TIMEOUT_SECONDS
                else -> DEVICES_TIMEOUT_SECONDS
            }
            val p = ProcessBuilder(args).start()
            if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                p.waitFor(2, TimeUnit.SECONDS)
                AdbRun(null, "", "", "adb ${args.drop(1).take(2)} 超时（${timeout}s），已强制结束")
            } else AdbRun(
                p.exitValue(),
                p.inputStream.readNBytes(MAX_OUTPUT_BYTES).toString(Charsets.UTF_8),
                p.errorStream.readNBytes(MAX_OUTPUT_BYTES).toString(Charsets.UTF_8),
            )
        } catch (e: Exception) {
            AdbRun(null, "", "", "adb 执行失败：${e.message?.take(80)}")
        }
    }
}
