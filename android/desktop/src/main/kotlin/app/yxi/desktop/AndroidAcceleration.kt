package app.yxi.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** Read-only emulator preflight; never changes Windows optional features. */
internal object AndroidAcceleration {
    data class Result(val ready: Boolean, val detail: String)
    suspend fun inspect(emulatorPath: String): Result = withContext(Dispatchers.IO) {
        if (!File(emulatorPath).isFile) return@withContext Result(false, "未找到模拟器程序，请先完成组件安装。")
        val process = try { ProcessBuilder(emulatorPath, "-accel-check").redirectErrorStream(true).start() }
        catch (e: Exception) { return@withContext Result(false, "无法检查虚拟化：${e.message.orEmpty()}") }
        val output = ByteArrayOutputStream()
        val reader = Thread {
            runCatching { process.inputStream.use { stream ->
                val bytes = ByteArray(4096)
                while (true) {
                    val n = stream.read(bytes)
                    if (n < 0) break
                    synchronized(output) {
                        val keep = minOf(n, 16 * 1024 - output.size())
                        if (keep > 0) output.write(bytes, 0, keep)
                    }
                }
            } }
        }.apply { isDaemon = true; name = "yxi-emulator-preflight"; start() }
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@withContext Result(false, "检查超时，请检查 Android Emulator 是否可以正常运行。")
            }
            reader.join(1000)
            val detail = synchronized(output) { output.toString(Charsets.UTF_8).trim() }
            Result(process.exitValue() == 0, detail.ifBlank { "检查结束，退出码 ${process.exitValue()}" })
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
        }
    }
}
