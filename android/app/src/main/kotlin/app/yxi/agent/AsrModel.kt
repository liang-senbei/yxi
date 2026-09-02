package app.yxi.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * 手机端语音模型的下载与安装。
 *
 * ⚠️ **不打进 APK。** 模型解开 229MB，塞进去 APK 会从 28MB 变成 260MB ——
 * 而语音不是人人都用。所以：APK 里只有原生库（arm64 那套，约 30MB），
 * 模型第一次用语音时下（压缩包 153MB）。
 *
 * ⚠️ **下载跑在 app scope 上，不挂在界面上。** 153MB 在手机网络下要好几分钟，
 * 挂在 composable 的 scope 上的话，用户切个页面就断了 —— 自更新那条路
 * 已经因为同一个原因栽过一次（见 [app.yxi.ui.UpdateDownloader] 的注释）。
 */
object AsrModel {

    /** 换模型就换文件名 —— 新 URL，CDN 结构上不可能给旧的（跟带版本号的 APK 同理）。 */
    private const val NAME = "sensevoice-2024-07-17.tgz"
    private const val URL = "https://yxi.keuury.com/asr/$NAME"
    private const val SHA256 = "bfda7e83d49db99ba68d452e4feb52cae0df6f2d7ced6e0bd42ae285ff16ea25"
    const val SIZE_MB = 153

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** -1 = 没在下；0..1 = 进度 */
    var progress by mutableFloatStateOf(-1f); private set
    var error by mutableStateOf<String?>(null); private set
    /** 下好并装好了没（下完会翻成 true，界面据此重画） */
    var installed by mutableStateOf(false); private set

    fun refresh(ctx: Context) { installed = OnDeviceAsr.ready(ctx) }

    fun start(ctx: Context) {
        if (progress >= 0f) return                     // 已经在下了
        val app = ctx.applicationContext
        progress = 0f; error = null
        scope.launch {
            val r = runCatching { download(app) }
            r.onSuccess { installed = true; error = null }
                .onFailure { error = (it.message ?: "下载失败").take(60) }
            progress = -1f
        }
    }

    /** 删掉模型，把那 230MB 还给用户。 */
    fun remove(ctx: Context) {
        OnDeviceAsr.release()
        OnDeviceAsr.dir(ctx).deleteRecursively()
        installed = false
    }

    private suspend fun download(ctx: Context) = withContext(Dispatchers.IO) {
        val dir = OnDeviceAsr.dir(ctx).apply { mkdirs() }
        val tmp = File(dir, "$NAME.part")
        val conn = (java.net.URL(URL).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.coerceAtLeast(1)
            val md = MessageDigest.getInstance("SHA-256")
            var got = 0L
            conn.inputStream.use { ins ->
                tmp.outputStream().buffered().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); got += n
                        progress = (got.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
            // ⚠️ **校验和不对就整个丢掉。** 一个下坏的模型加载时才报错，
            // 那时候用户已经按住说完话了 —— 失败要发生在这儿，不是那儿。
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (hex != SHA256) { tmp.delete(); error("下下来的文件对不上（可能被网络劫持了）") }
            unpack(tmp, dir)
        } finally {
            runCatching { conn.disconnect() }
            runCatching { tmp.delete() }
        }
        if (!OnDeviceAsr.ready(ctx)) error("解压后模型不完整")
    }

    /**
     * 解开 `.tgz`。里面就两个文件：`model.int8.onnx` 和 `tokens.txt`。
     *
     * ⚠️ **只认这两个名字，别的一概不写。** 压缩包是从网上下的 ——
     * 照着包里的路径写文件，一个 `../../` 就能把东西写到 App 目录外面去
     * （Zip Slip）。白名单是这里唯一安全的做法。
     */
    private fun unpack(tgz: File, into: File) {
        val want = setOf("model.int8.onnx", "tokens.txt")
        GZIPInputStream(tgz.inputStream().buffered()).use { gz ->
            val hdr = ByteArray(512)
            while (true) {
                if (gz.readNBytes(hdr, 0, 512) < 512) break
                if (hdr.all { it == 0.toByte() }) break
                // ⚠️ tar 的字段是用 **NUL** 补齐的，不是空格。
                // ⭐ 而且这里必须写转义 `\u0000`：上一版我把一个**真的 NUL 字节**
                //    写进了源文件，能编过但肇不见、grep 不到、一 diff 就乱。
                val name = String(hdr, 0, 100).trim('\u0000', ' ').substringAfterLast('/')
                val size = String(hdr, 124, 12).trim('\u0000', ' ')
                    .ifBlank { "0" }.toLong(8)
                val pad = ((size + 511) / 512 * 512 - size).toInt()
                // typeflag: '0' = 普通文件，NUL = 很老的 tar 也用它表示普通文件
                if (name in want && hdr[156].toInt().toChar() in "0\u0000") {
                    File(into, name).outputStream().buffered().use { o ->
                        var left = size
                        val buf = ByteArray(1 shl 16)
                        while (left > 0) {
                            val n = gz.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n <= 0) break
                            o.write(buf, 0, n); left -= n
                        }
                    }
                } else {
                    var left = size
                    while (left > 0) {
                        val n = gz.skip(left)
                        if (n <= 0) break
                        left -= n
                    }
                }
                if (pad > 0) gz.skipNBytes(pad.toLong())
            }
        }
    }
}
