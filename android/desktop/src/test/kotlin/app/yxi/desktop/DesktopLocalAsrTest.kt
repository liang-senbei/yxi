package app.yxi.desktop

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 假 CLI + 合成 WAV：参数含空格、输出读取/清理、取消终止进程。零真实录音/模型/下载，不触达 5 分钟超时。 */
class DesktopLocalAsrTest {
    private val tmpdir = File(System.getProperty("java.io.tmpdir"))

    /** 合成 16kHz 单声道 16-bit WAV，格式与产品目标一致，免转换路径。 */
    private fun wavBytes(): ByteArray {
        val format = AudioFormat(16000f, 16, 1, true, false)
        val stream = AudioInputStream(ByteArrayInputStream(ByteArray(1600 * 2)), format, 1600L)
        val out = ByteArrayOutputStream()
        AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out)
        return out.toByteArray()
    }

    private fun fakeCli(dir: File, name: String, body: String): File =
        File(dir, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }

    /** 记录逐条 argv 并按 -of 前缀写出带首尾空白的识别结果。 */
    private val writerBody = """
        dir=${'$'}(dirname "${'$'}0")
        : > "${'$'}dir/args.txt"
        prefix=""
        prev=""
        for a in "${'$'}@"; do
          if [ "${'$'}prev" = "-of" ]; then prefix="${'$'}a"; fi
          printf '%s\n' "${'$'}a" >> "${'$'}dir/args.txt"
          prev="${'$'}a"
        done
        printf '  你好离线识别\n\n' > "${'$'}prefix.txt"
    """.trimIndent() + "\n"

    /** 挂起式轮询：不能阻塞 runBlocking 事件循环，否则 launch 的协程无法启动。 */
    private suspend fun poll(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "等待超时" }
            delay(50)
        }
    }

    private fun leakedDirs(before: Set<String>): List<File> =
        tmpdir.listFiles { f -> f.isDirectory && f.name.startsWith("yxi-local-asr-") && f.name !in before }?.toList() ?: emptyList()

    private fun existingDirs(): Set<String> =
        tmpdir.listFiles { f -> f.isDirectory && f.name.startsWith("yxi-local-asr-") }?.mapTo(mutableSetOf()) { it.name } ?: emptySet()

    @Test fun `arguments keep spaced program and model paths as single entries`() {
        val root = Files.createTempDirectory("yxi-asr-test").toFile()
        try {
            val tools = File(root, "my tools").apply { mkdirs() }
            val script = fakeCli(tools, "whisper-cli", writerBody)
            val model = File(File(root, "my models").apply { mkdirs() }, "ggml base.bin").apply { writeText("weights") }
            val wav = wavBytes()
            val text = runBlocking {
                DesktopLocalAsr.transcribe(script.absolutePath, model.absolutePath, wav)
            }
            assertEquals("你好离线识别", text)
            // 空格路径作为单一 argv 逐项传入，无 shell 拆词；固定参数顺序完整
            val args = File(script.parentFile, "args.txt").readLines()
            assertEquals(
                listOf("-m", model.absolutePath, "-f", args[3], "-l", "auto", "-otxt", "-of", args[8], "-np"),
                args, "argv 应逐项传递：$args")
            assertTrue(args[3].endsWith("audio.wav"), "音频输入应为临时 wav：$args")
            assertTrue(args[7].endsWith("result"), "输出前缀应为临时 result：$args")
        } finally { root.deleteRecursively() }
    }

    @Test fun `output text is trimmed and temp artifacts and audio buffer are cleaned`() {
        val root = Files.createTempDirectory("yxi-asr-test").toFile()
        val before = existingDirs()
        try {
            val script = fakeCli(root, "whisper-cli", writerBody)
            val model = File(root, "weights.bin").apply { writeText("weights") }
            val wav = wavBytes()
            val text = runBlocking {
                DesktopLocalAsr.transcribe(script.absolutePath, model.absolutePath, wav)
            }
            // 结果文件带首尾空白，读取时已 trim
            assertEquals("你好离线识别", text)
            // 音频缓冲用后清零
            assertTrue(wav.all { it == 0.toByte() }, "录音缓冲应在结束后清零")
            // 临时目录（wav/结果/日志）随 finally 清理，无残留
            assertEquals(emptyList(), leakedDirs(before), "yxi-local-asr-* 临时目录应清理")
        } finally { root.deleteRecursively() }
    }

    @Test fun `cancellation terminates the process and still cleans up`() {
        val root = Files.createTempDirectory("yxi-asr-test").toFile()
        val before = existingDirs()
        try {
            val script = fakeCli(root, "whisper-cli",
                "echo ${'$'}${'$'} > \"${'$'}(dirname \"${'$'}0\")/pid.txt\"\nsleep 30\n")
            val model = File(root, "weights.bin").apply { writeText("weights") }
            val wav = wavBytes()
            var failure: Throwable? = null
            runBlocking {
                val job = launch {
                    try { DesktopLocalAsr.transcribe(script.absolutePath, model.absolutePath, wav) }
                    catch (t: Throwable) { failure = t }
                }
                poll { File(script.parentFile, "pid.txt").isFile } // 假进程已启动
                job.cancel()
                job.join()
                // 取消以 CancellationException 结束（不等 5 分钟超时），且 finally 仍执行清理
                assertIs<CancellationException>(failure)
                val pid = File(script.parentFile, "pid.txt").readText().trim().toLong()
                poll { !ProcessHandle.of(pid).map { handle -> handle.isAlive() }.orElse(false) }
            }
            assertTrue(wav.all { it == 0.toByte() }, "取消后录音缓冲也应清零")
            assertEquals(emptyList(), leakedDirs(before), "取消后临时目录也应清理")
        } finally { root.deleteRecursively() }
    }
}
