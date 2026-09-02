package app.yxi.agent

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **在手机上**做语音识别。不联网、不经过服务器、不依赖 Google 服务。
 *
 * ⚠️ **为什么非要做在手机上。** 前两版都不满足「装了 App 就能用」：
 *  · 系统 `RecognizerIntent` —— 它自己不识别，只是把活转给手机上的识别器 App，
 *    而那多半是 GMS 的一部分。用户那台荣耀 **GMS 是关的，一个识别器都没有**
 *    （实测 `pm query-activities` 返回 0，见 TROUBLESHOOTING #152）。
 *    有 GMS 的手机能用，但准不准由 Google / 厂商说了算，我们改不了。
 *  · 服务端识别（`server/yxi-asr`）—— 准，但要用户先在自己服务器上装 360MB。
 *
 * 手机上算把两个问题一起解决：不依赖 GMS、不依赖服务器、离线可用、准确度可控。
 *
 * 模型是 SenseVoice-Small（阿里 FunAudioLLM，Apache-2.0），中英粤日韩都认、自带标点。
 * **不进 APK**（229MB）—— 首次用语音时下 153MB 的压缩包，解开放进 App 私有目录。
 *
 * ⚠️ **识别器很贵，必须缓存。** 装载一次实测在服务器上要 6~10 秒，手机上同量级。
 * 每次按住说话都重新建 = 每句话多等十秒。所以这里持一个单例，
 * 由 [release] 在明确不用了的时候放掉。
 */
object OnDeviceAsr {

    /** 模型放这儿。⚠️ App 私有目录 —— 卸载即净，不留 230MB 在用户手机上。 */
    fun dir(ctx: Context) = File(ctx.filesDir, "asr")

    private fun modelFile(ctx: Context) = File(dir(ctx), "model.int8.onnx")
    private fun tokensFile(ctx: Context) = File(dir(ctx), "tokens.txt")

    /** 模型下好了没。 */
    fun ready(ctx: Context): Boolean =
        // ⚠️ 光看文件在不在不够 —— 下到一半被杀掉也会留下一个残缺的文件。
        // 真模型 229MB，用一个宽松的下界拦住半截文件（校验和在下载那头做）。
        modelFile(ctx).length() > 200_000_000 && tokensFile(ctx).length() > 1_000

    /** 这台设备的 CPU 架构支持吗（我们只打包了 arm64-v8a）。 */
    val supported: Boolean
        get() = android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    @Volatile private var rec: OfflineRecognizer? = null

    /**
     * 识别一段 16k 单声道的 PCM。
     *
     * @param pcm 归一化到 [-1, 1] 的采样点。[app.yxi.ui.Recorder] 直接给这个格式 ——
     *   **不走 m4a**：手机上算的话没有服务器帮忙转码，而 `AudioRecord` 本来就出 PCM，
     *   中间再编码一次纯属自找麻烦。
     * @return 识别出的文字；出错返回 null，[why] 里是原因。
     */
    suspend fun transcribe(ctx: Context, pcm: FloatArray, why: (String) -> Unit): String? =
        withContext(Dispatchers.Default) {
            if (!supported) { why(app.yxi.ui.t("这台设备的 CPU 架构不支持（只打包了 arm64）")); return@withContext null }
            if (!ready(ctx)) { why(app.yxi.ui.t("语音模型还没下好")); return@withContext null }
            val r = runCatching { recognizer(ctx) }.getOrElse {
                why(app.yxi.ui.t("语音模型装载失败：%s").format(it.message ?: it::class.simpleName.orEmpty()))
                return@withContext null
            }
            runCatching {
                val s = r.createStream()
                s.acceptWaveform(pcm, 16_000)
                r.decode(s)
                val txt = r.getResult(s).text
                s.release()
                clean(txt)
            }.getOrElse {
                why(app.yxi.ui.t("识别失败：%s").format(it.message ?: it::class.simpleName.orEmpty()))
                null
            }
        }

    private fun recognizer(ctx: Context): OfflineRecognizer = rec ?: synchronized(this) {
        rec ?: OfflineRecognizer(
            // ⚠️ `assetManager = null` = 从**文件路径**读，不从 assets 读。
            // 模型是下载来的，不在 APK 里。
            assetManager = null,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = modelFile(ctx).absolutePath,
                        // ⚠️ 打开逆文本归一化 —— 没有它出来的是「九点至下午五点」，
                        // 有它是「9点至下午5点」，而且带标点。
                        useInverseTextNormalization = true,
                    ),
                    tokens = tokensFile(ctx).absolutePath,
                    // ⚠️ 手机上别开满线程：用户按住说话的时候前台还在画界面，
                    // 抢光核心会让录音界面卡顿。2 条在实测里已经比实时快好几倍。
                    numThreads = 2,
                ),
            ),
        ).also { rec = it }
    }

    /**
     * 放掉识别器，释放那几百 MB。
     * ⚠️ 退出对话页时调 —— 常驻着的话，用户切走之后内存还压在那儿，
     * 而安卓在内存紧张时会直接把整个 App 杀掉（那比慢一点糟得多）。
     */
    fun release() = synchronized(this) {
        runCatching { rec?.release() }
        rec = null
    }

    /**
     * SenseVoice 会在前面挂 `<|zh|><|NEUTRAL|><|Speech|><|woitn|>` 这种标签。
     * ⚠️ 不剥掉的话那几个尖括号会原样进用户的输入框，
     * 而输入框里的东西是要发到服务器上当命令的。
     */
    internal fun clean(raw: String): String {
        var t = raw
        while (t.startsWith("<|")) {
            val i = t.indexOf("|>")
            if (i < 0) break
            t = t.substring(i + 2)
        }
        return t.trim()
    }
}
