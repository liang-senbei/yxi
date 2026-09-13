package app.yxi.desktop

import androidx.compose.runtime.*
import app.yxi.ssh.Shell
import app.yxi.ssh.SshSession
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.sound.sampled.*
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.sqrt

internal object VoiceActivity {
    var busy by mutableStateOf(false)
    var hasDraft by mutableStateOf(false)
}

internal class DesktopRecorder {
    @Volatile var running = false; private set
    @Volatile var level = 0f; private set
    @Volatile var seconds = 0; private set
    private var line: TargetDataLine? = null
    private var worker: Thread? = null
    private var format = AudioFormat(16000f, 16, 1, true, false)
    private val audio = ByteArrayOutputStream()
    @Volatile private var problem: Throwable? = null
    fun start() {
        check(!running)
        audio.reset(); problem = null; seconds = 0
        var opened: TargetDataLine? = null
        for (rate in listOf(16000f, 48000f, 44100f)) {
            val candidate = AudioFormat(rate, 16, 1, true, false)
            val capture = runCatching { AudioSystem.getTargetDataLine(candidate) }.getOrNull() ?: continue
            try { capture.open(candidate); format = candidate; opened = capture; break }
            catch (_: Exception) { capture.close() }
        }
        val capture = opened ?: error("无法打开麦克风，请检查录音设备和系统麦克风权限")
        line = capture; running = true
        try { capture.start() } catch (e: Exception) { running = false; runCatching { capture.close() }; throw e }
        worker = thread(isDaemon = true, name = "yxi-voice-capture") {
            try {
                val chunk = ByteArray(4096)
                val limit = (format.sampleRate * 2 * 120).toInt()
                while (running && audio.size() < limit) {
                    val count = capture.read(chunk, 0, chunk.size)
                    if (count <= 0) break
                    audio.write(chunk, 0, count)
                    var sum = 0.0
                    for (i in 0 until count - 1 step 2) {
                        val sample = ((chunk[i].toInt() and 255) or (chunk[i + 1].toInt() shl 8)).toShort().toDouble() / 32768
                        sum += sample * sample
                    }
                    level = (sqrt(sum / (count / 2).coerceAtLeast(1)) * 3).toFloat().coerceIn(0f, 1f)
                    seconds = (audio.size() / (format.sampleRate * 2)).toInt()
                }
            } catch (e: Throwable) { if (running) problem = e }
            finally { running = false; level = 0f; runCatching { capture.stop() }; runCatching { capture.close() } }
        }
    }
    fun cancel() { running = false; runCatching { line?.stop() }; runCatching { line?.close() } }
    fun stopWav(): ByteArray {
        cancel(); worker?.join(2000)
        check(worker?.isAlive != true) { "麦克风尚未停止，请重试" }
        problem?.let { throw IllegalStateException("录音失败", it) }
        val pcm = audio.toByteArray(); audio.reset()
        require(pcm.size >= format.sampleRate * 2 * 0.3) { "录音太短，请重新说一遍" }
        val output = ByteArrayOutputStream()
        AudioInputStream(ByteArrayInputStream(pcm), format, pcm.size.toLong() / format.frameSize).use { AudioSystem.write(it, AudioFileFormat.Type.WAVE, output) }
        pcm.fill(0)
        return output.toByteArray()
    }
}

internal object DesktopAsr {
    suspend fun prepare(ssh: SshSession): String {
        val script = """
import os,shutil,json
if not shutil.which('yxi-asr'):
 print('__YXI_VOICE__:'+json.dumps({'error':'当前服务器未安装 yxi-asr，请安装后使用服务器语音识别'}))
else:
 base=os.path.join(os.path.expanduser('~'),'.yxi'); path=os.path.join(base,'voice')
 if os.path.islink(base) or os.path.islink(path): raise ValueError('invalid voice directory')
 os.makedirs(path,mode=0o700,exist_ok=True)
 if os.stat(path).st_uid!=os.getuid(): raise ValueError('voice directory owner mismatch')
 os.chmod(path,0o700)
 print('__YXI_VOICE__:'+json.dumps({'directory':path}))
""".trimIndent()
        val value = response(ssh.exec("python3 -c ${Shell.q(script)}"))
        check(!value.has("error")) { value.optString("error") }
        return value.getString("directory")
    }
    suspend fun transcribe(ssh: SshSession, directory: String, wav: ByteArray): String {
        val path = directory + "/" + UUID.randomUUID() + ".wav"
        try {
            withContext(Dispatchers.IO) { val sftp = ssh.openSftp(); try { sftp.write(path, wav) } finally { sftp.close() } }
            val script = """
import os,sys,json,subprocess,signal
p=sys.argv[1]
def interrupted(*args): raise SystemExit(1)
for s in (signal.SIGHUP,signal.SIGTERM,signal.SIGINT): signal.signal(s,interrupted)
try:
 os.chmod(p,0o600)
 r=subprocess.run(['yxi-asr',p],capture_output=True,text=True,timeout=180)
 result={'text':r.stdout.strip()} if r.returncode==0 else {'error':'服务器语音识别失败，请检查 yxi-asr 服务'}
except Exception:
 result={'error':'语音识别未完成，请检查连接和识别服务'}
finally:
 try: os.unlink(p)
 except FileNotFoundError: pass
print('__YXI_VOICE__:'+json.dumps(result,ensure_ascii=False))
""".trimIndent()
            val value = response(ssh.exec("python3 -c ${Shell.q(script)} ${Shell.q(path)}"))
            check(!value.has("error")) { value.optString("error") }
            return value.getString("text").also { require(it.isNotBlank()) { "没有识别到文字，请重新录音" } }
        } catch (e: Exception) {
            withContext(NonCancellable) { runCatching { ssh.exec("rm -f -- ${Shell.q(path)}") } }
            throw e
        } finally { wav.fill(0) }
    }
    private fun response(raw: String): JSONObject {
        val line = raw.lineSequence().lastOrNull { it.startsWith("__YXI_VOICE__:") } ?: error("服务器未返回语音结果，请检查连接与 Python3")
        return JSONObject(line.removePrefix("__YXI_VOICE__:"))
    }
}
