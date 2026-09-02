package app.yxi.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import kotlin.concurrent.thread

/**
 * 按住说话的录音器。**出 16k 单声道 PCM**。
 *
 * ⚠️ **为什么是 `AudioRecord` 不是 `MediaRecorder`。** 识别在手机上做（[app.yxi.agent.OnDeviceAsr]），
 * 模型吃的就是 16k PCM —— 用 `MediaRecorder` 录成 m4a，再在手机上解回 PCM，
 * 是白编码一次又白解码一次，还得拖 `MediaCodec` 那套异步 API 进来。
 * `AudioRecord` 直接给采样点，中间什么都不用转。
 * （退回服务端识别那条路时才需要文件 —— [toWav] 现拼一个，wav 头就 44 字节。）
 *
 * ⚠️ **一切失败都要说出来。** 麦克风被别的 app 占着、权限被 ROM 静默收回 ——
 * 这些在真机上都会发生。语音那个按钮在这个项目上已经因为静默失败废过一次
 * （TROUBLESHOOTING #152：点了什么都不发生，一个字的解释都没有）。
 */
class Recorder {
    private var rec: AudioRecord? = null
    private var worker: Thread? = null
    private val chunks = ArrayList<ShortArray>()
    private var total = 0
    @Volatile private var running = false

    /** @return 出错原因；null = 开始录了 */
    @SuppressLint("MissingPermission")   // 调用方在按下之前已经要过权限
    fun start(): String? {
        stop()
        chunks.clear(); total = 0
        return runCatching {
            val min = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (min <= 0) error(t("这台手机不支持 16kHz 录音"))
            val r = AudioRecord(
                // ⚠️ `VOICE_RECOGNITION` 而不是 `MIC`：系统会关掉给通话调的那些处理
                // （回声消除之类），对识别来说更干净。
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                min * 4,
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release(); error(t("麦克风打不开（可能被别的应用占着）"))
            }
            r.startRecording()
            rec = r; running = true
            worker = thread(name = "yxi-rec") {
                val buf = ShortArray(min)
                while (running) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    synchronized(chunks) { chunks += buf.copyOf(n); total += n }
                }
            }
            null
        }.getOrElse {
            runCatching { rec?.release() }; rec = null; running = false
            t("录不了音：%s").format(it.message ?: it::class.simpleName.orEmpty())
        }
    }

    /**
     * 停止并交出采样点（已归一化到 [-1, 1]）。
     *
     * ⚠️ **太短的直接丢掉。** 误触、或者手指刚碰到就松开，会录出几十毫秒的片段；
     * 送去识别只会得到空结果，用户看到的是「转了半天什么都没有」。
     * 300ms 以下当没按过 —— **而且不报错**，误触不是错误。
     *
     * @return 采样点；没录到 / 太短 = null
     */
    fun stop(): FloatArray? {
        val r = rec ?: return null
        rec = null; running = false
        runCatching { r.stop() }
        runCatching { worker?.join(500) }
        worker = null
        runCatching { r.release() }
        val n: Int
        val out: FloatArray
        synchronized(chunks) {
            n = total
            if (n < RATE * 300 / 1000) { chunks.clear(); total = 0; return null }
            out = FloatArray(n)
            var i = 0
            for (c in chunks) for (v in c) { out[i++] = v / 32768f }
            chunks.clear(); total = 0
        }
        return out
    }

    val recording: Boolean get() = rec != null

    companion object {
        const val RATE = 16_000

        /**
         * 拼一个 16k 单声道的 wav 文件 —— 只有走**服务端识别**那条退路时才用得上。
         * ⚠️ wav 头就 44 字节，为它拉一个库进来不值得。
         */
        fun toWav(ctx: Context, pcm: FloatArray): File {
            val f = File(File(ctx.cacheDir, "voice").apply { mkdirs() }, "v-${System.currentTimeMillis()}.wav")
            val bytes = pcm.size * 2
            f.outputStream().buffered().use { o ->
                fun le32(v: Int) = o.write(byteArrayOf(
                    v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
                fun le16(v: Int) = o.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
                o.write("RIFF".toByteArray()); le32(36 + bytes); o.write("WAVE".toByteArray())
                o.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
                le32(RATE); le32(RATE * 2); le16(2); le16(16)
                o.write("data".toByteArray()); le32(bytes)
                for (v in pcm) {
                    val s = (v * 32767f).toInt().coerceIn(-32768, 32767)
                    le16(s)
                }
            }
            return f
        }
    }
}
