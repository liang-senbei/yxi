package app.yxi.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.coroutines.coroutineContext

/** Optional user-configured whisper.cpp CLI. No shell, downloads, or network transport. */
internal object DesktopLocalAsr {
    fun validate(program: String, model: String): Pair<File, File> {
        val executable = File(program.trim().removeSurrounding("\"")).canonicalFile
        val weights = File(model.trim().removeSurrounding("\"")).canonicalFile
        require(executable.isFile && executable.canExecute()) { "请选择可执行的 whisper-cli 程序" }
        if (System.getProperty("os.name").startsWith("Windows")) require(executable.extension.equals("exe", true)) { "Windows 离线识别需要 whisper-cli.exe" }
        require(weights.isFile && weights.canRead()) { "请选择可读取的 whisper.cpp 模型文件" }
        return executable to weights
    }

    suspend fun transcribe(program: String, model: String, wav: ByteArray): String = withContext(Dispatchers.IO) {
        var directory: Path? = null
        var process: Process? = null
        try {
            val (executable, weights) = validate(program, model)
            val temp = Files.createTempDirectory("yxi-local-asr-").also { directory = it }
            val input = temp.resolve("audio.wav")
            val prefix = temp.resolve("result")
            val result = temp.resolve("result.txt")
            val log = temp.resolve("process.log")
            val target = AudioFormat(16000f, 16, 1, true, false)
            AudioSystem.getAudioInputStream(ByteArrayInputStream(wav)).use { source ->
                if (source.format.matches(target)) AudioSystem.write(source, AudioFileFormat.Type.WAVE, input.toFile())
                else {
                    check(AudioSystem.isConversionSupported(target, source.format)) { "当前录音格式无法转换为离线识别所需的16kHz格式，请更换麦克风" }
                    AudioSystem.getAudioInputStream(target, source).use { converted -> AudioSystem.write(converted, AudioFileFormat.Type.WAVE, input.toFile()) }
                }
            }
            val child = ProcessBuilder(executable.absolutePath, "-m", weights.absolutePath, "-f", input.toString(),
                "-l", "auto", "-otxt", "-of", prefix.toString(), "-np")
                .directory(executable.parentFile).redirectErrorStream(true).redirectOutput(log.toFile()).start().also { process = it }
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
            while (!child.waitFor(200, TimeUnit.MILLISECONDS)) {
                coroutineContext.ensureActive()
                check(System.nanoTime() < deadline) { "离线识别超时，可改用较小模型后重试" }
            }
            coroutineContext.ensureActive()
            check(child.exitValue() == 0) {
                val detail = RandomAccessFile(log.toFile(), "r").use { file ->
                    val size = minOf(file.length(), 2048L).toInt()
                    file.seek(file.length() - size)
                    ByteArray(size).also { file.readFully(it) }.toString(Charsets.UTF_8)
                }
                "离线识别失败：$detail"
            }
            check(Files.isRegularFile(result) && Files.size(result) <= 1024 * 1024) { "未得到可读取的离线识别文字" }
            Files.readString(result).trim().also { check(it.isNotBlank()) { "没有识别到文字，可重新录音或更换模型" } }
        } finally {
            wav.fill(0)
            process?.let { runCatching { if (it.isAlive) { it.destroyForcibly(); it.waitFor(2, TimeUnit.SECONDS) } } }
            directory?.let { temp ->
                listOf("audio.wav", "result.txt", "process.log").forEach { name -> runCatching { Files.deleteIfExists(temp.resolve(name)) }.onFailure { temp.resolve(name).toFile().deleteOnExit() } }
                runCatching { Files.deleteIfExists(temp) }
            }
        }
    }
}
