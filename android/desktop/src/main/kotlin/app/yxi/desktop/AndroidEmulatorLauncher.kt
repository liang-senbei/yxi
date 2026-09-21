package app.yxi.desktop

import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * AndroidEmulatorLauncher —— 把**已存在**的 AVD 用本机 SDK 的 emulator.exe 拉起来。
 *
 * 输入是 [AndroidEmulatorEnvironment.Result]（环境探测的产物）+ 指定的 AVD 名；
 * 职责边界刻意收得很窄：
 * - **只启动已存在的 AVD**（名字必须在 [AndroidEmulatorEnvironment.Result.avds] 里）；
 * - 只管理**自己起的**进程句柄：[stop] 不碰别的实例、更不扫系统进程表；
 * - 同一 AVD 活着就拒绝二次启动（模拟器自己也会因锁/端口冲突拒第二个，但与其让
 *   用户看到 emulator 的报错，不如在这里就给一句人话）。
 *
 * ⚠️ **不做的**：不下载系统镜像、不接受许可证、不创建/克隆 AVD、不装 SDK ——
 * SDK 安装与 AVD 创建是下一步单独接的事。**本类只是「跑已有设备」，不是完整一键模拟器交付。**
 *
 * ⚠️ **异步**：[launch] 只做起进程 + 登记句柄（毫秒级，不卡 UI）；进程退出等待、
 * 日志搬运、句柄清理全在守护线程里。强杀升级（destroy 无视后的 destroyForcibly）
 * 也丢给短命线程，[stop] 不阻塞调用方。
 *
 * ⚠️ **日志**：写 Yxi 自己的目录（`Store.dir/emulator-logs`，测试可注入），单文件有
 * 字节上限、目录有保留数量 —— emulator 的日志又多又快，不设限迟早把盘吃穿。
 * 超限后**继续读、只不再写**：管道必须读空，否则模拟器会被日志管道反向卡死。
 */
class AndroidEmulatorLauncher(
    private val env: AndroidEmulatorEnvironment.Result,
    /** 日志目录。默认 Yxi 自有目录下的 emulator-logs；测试注临时目录。 */
    private val logDir: File = File(Store.dir, "emulator-logs"),
    /** 进程启动器。生产用 ProcessBuilder 参数列表起 emulator；测试注假的，绝不写真进程。 */
    private val startProcess: (List<String>) -> EmuProcess = ::startNative,
    /** stop 后等多久再强杀（秒）。测试注 0 免等。 */
    private val stopGraceSeconds: Long = 5,
) {

    /**
     * 进程的最小接口 —— 只暴露启动器真正用到的东西。
     * 抽这层是为了「不启动真模拟器」也能测满全部路径：java.lang.Process 没法造假的。
     */
    interface EmuProcess {
        val pid: Long
        fun isAlive(): Boolean
        fun destroy()
        fun destroyForcibly()
        /** 等退出，返回退出码。 */
        fun waitFor(): Int
        /** 限时等待，true = 在时限内退出了。 */
        fun waitFor(seconds: Long): Boolean
        /** 合并后的 stdout+stderr（日志从这里搬）。 */
        val output: InputStream
    }

    /** 一次启动的结果。[started] false 时 [reason] 说人话，直接可以摆到界面上。 */
    data class LaunchOutcome(val avd: String, val started: Boolean, val pid: Long? = null, val reason: String? = null)

    private class Running(val process: EmuProcess, val log: File)

    /** 自己起的句柄，只增于此、只清于此 —— 「stop 只停自己的」物理上由这张表保证。 */
    private val running = ConcurrentHashMap<String, Running>()

    @Synchronized fun launch(avdName: String): LaunchOutcome {
        val emu = env.tools.firstOrNull { it.name == "emulator" }
            ?: return LaunchOutcome(avdName, false, reason = "SDK 里没有 emulator，先按环境探测的缺失项补齐")
        if (avdName !in env.avds)
            return LaunchOutcome(avdName, false, reason = "AVD「$avdName」不在已发现名单里 —— 本启动器只跑已存在的 AVD，创建设备是下一步的事")
        // 同名还活着就不二次起；退了但回调没来得及清的残留句柄顺手摘掉
        val existing = running[avdName]
        if (existing != null && existing.process.isAlive())
            return LaunchOutcome(avdName, false, pid = existing.process.pid, reason = "已在运行（pid=${existing.process.pid}），不再重复启动")
        if (existing != null) running.remove(avdName, existing)

        pruneOldLogs()
        val log = try { newLogFile(avdName) } catch (e: Exception) {
            return LaunchOutcome(avdName, false, reason = "无法准备模拟器日志目录：${e.message?.take(80)}")
        }
        val proc = try {
            // 参数列表，不经过 shell：路径带空格、AVD 名带空格都不断
            startProcess(listOf(emu.path, "-avd", avdName))
        } catch (e: Exception) {
            return LaunchOutcome(avdName, false, reason = "启动失败：${e.message?.take(80)}")
        }
        val h = Running(proc, log)
        running[avdName] = h
        watch(avdName, h)
        return LaunchOutcome(avdName, true, pid = proc.pid)
    }

    /** 停掉自己起的某个 AVD。false = 那个 AVD 不是这里起的（或已退出），什么也不做。 */
    fun stop(avdName: String): Boolean {
        val h = running[avdName] ?: return false
        h.process.destroy()
        Thread {
            // 优雅窗口内没退就强杀；放进短命线程，调用方（可能在 UI 线程）不等这一下
            if (!h.process.waitFor(stopGraceSeconds)) h.process.destroyForcibly()
        }.apply { isDaemon = true; name = "yxi-emulator-stop-$avdName" }.start()
        return true
    }

    /** 退出时全停（应用关闭时调用）。只碰自己的句柄。 */
    fun stopAll() {
        running.keys.toList().forEach { stop(it) }
    }

    /** 此刻还活着的、由本启动器起的 AVD 名单。 */
    fun runningAvds(): List<String> =
        running.entries.filter { it.value.process.isAlive() }.map { it.key }.sorted()

    // ── 日志 ────────────────────────────────────────────────────────────────

    private fun newLogFile(avdName: String): File {
        logDir.mkdirs()
        val safe = avdName.map { c -> if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_' }.joinToString("")
        return File(logDir, "$safe-${System.currentTimeMillis()}.log")
    }

    /** 只留最近 [KEEP_LOG_FILES] 个 .log，更老的直接删 —— 每次启动时清一次，不挂后台任务。 */
    private fun pruneOldLogs() {
        logDir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.filter { file -> running.values.none { it.log == file } }
            ?.drop((KEEP_LOG_FILES - running.size - 1).coerceAtLeast(0))
            ?.forEach { it.delete() }
    }

    private fun watch(avdName: String, h: Running) {
        Thread {
            drainLog(h)
            h.process.waitFor()
            // remove(k, v) 带值比较：退出期间同名被重新起过的话，别把新句柄误删
            running.remove(avdName, h)
        }.apply { isDaemon = true; name = "yxi-emulator-watch-$avdName" }.start()
    }

    /**
     * 把合并输出搬进日志文件，超过 [MAX_LOG_BYTES] 就只读不写。
     * ⚠️ **必须一直读到 EOF**：emulator 往管道里写，管道满了它就写不动了 ——
     * 日志可以丢，进程不能被日志卡死。
     */
    private fun drainLog(h: Running) {
        var out: java.io.OutputStream? = runCatching { h.log.outputStream() }.getOrNull()
        try {
                val buf = ByteArray(8192)
                var written = 0L
                while (true) {
                    val n = h.process.output.read(buf)
                    if (n < 0) break
                    if (written < MAX_LOG_BYTES) {
                        val take = minOf(n.toLong(), MAX_LOG_BYTES - written).toInt()
                        try { out?.write(buf, 0, take) }
                        catch (_: Exception) {
                            runCatching { out?.close() }; out = null
                        }
                        written += take
                    }
                }
        } catch (_: Exception) {
            // A broken input stream ends draining; file failures above keep consuming stdout.
        } finally { runCatching { out?.close() } }
    }

    companion object {
        /** 单个日志文件上限。emulator 冷启动就能喷几 MB，2MB 够看排错开头。 */
        const val MAX_LOG_BYTES = 2L * 1024 * 1024
        /** 目录里最多留几个日志。 */
        const val KEEP_LOG_FILES = 10

        private fun startNative(args: List<String>): EmuProcess {
            // redirectErrorStream：模拟器的诊断信息大半在 stderr，合并进同一份日志才看得懂
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            return object : EmuProcess {
                override val pid: Long get() = p.pid()
                override fun isAlive(): Boolean = p.isAlive
                override fun destroy() { p.destroy() }
                override fun destroyForcibly() { p.destroyForcibly() }
                override fun waitFor(): Int = p.waitFor()
                override fun waitFor(seconds: Long): Boolean = p.waitFor(seconds, TimeUnit.SECONDS)
                override val output: InputStream get() = p.inputStream
            }
        }
    }
}
