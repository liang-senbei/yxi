package app.yxi.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap
import java.util.concurrent.Executors

/**
 * **扫一扫** —— 「我的」标题行右边那个图标点进来的整页（老板 2026-09-06：「以后扫东西就不用用微信扫」）。
 *
 * 取景用 CameraX，解码用 **ZXing 的 core**（纯 Java）。
 *
 * ⚠️⚠️ **不许改成 ML Kit / `play-services-code-scanner`。** 看着更省事，但在我们这儿是死路：
 *   **主力测试机兼第一用户（老板的荣耀 Magic7）默认关着 GMS**，任何依赖 Google Play 服务的方案
 *   在他手机上直接不工作 —— 语音识别已经踩过一次（`pm query-activities` 返回 0 个，按钮是死的）。
 *   ZXing 的 core 是纯 Java 解码器、CameraX 是 androidx，两者都不碰 Play 服务，离线照样认。
 *   **2026-09-06 实测证据**（依赖树 + 成品包五项全零，cc-Yxi 要求留档）：
 *     · `:app:dependencies --configuration releaseRuntimeClasspath` 里 play-services / gms / mlkit / firebase **一个都没有**
 *     · release 包 dex 里 `com/google/android/gms`、`mlkit`、`firebase` 类 **0 处**
 *     · 合并后的 manifest 里 gms metadata **0 处**；包内 gms/mlkit 资源与 so **0 个**
 *   代价：release 40.9 MB → 42.95 MB（+2.05 MB）。这个价换「在没有 GMS 的手机上能用」，明确划算。
 * ⚠️ 相机权限**进来才申请**，不在 App 启动时要 —— 一开 App 就弹相机权限很劝退。
 *
 * 扫到之后不自作主张：**先把内容摆出来**，是网址给「打开」，其它给「复制」，另有「再扫一次」。
 */
@Composable
fun ScanScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    /** 扫到的内容。非空 = 停下来给用户看，不再继续扫 */
    var hit by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok; denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp, 12.dp, 14.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(100.dp),
                modifier = Modifier.clip(RoundedCornerShape(100.dp)).clickable(onClick = onBack)) {
                Text("←", Modifier.padding(15.dp, 8.dp), style = MaterialTheme.typography.titleSmall)
            }
            Text(t("扫一扫"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                !granted -> Column(
                    Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(if (denied) t("没有相机权限，扫不了") else t("要用相机才能扫"),
                        style = MaterialTheme.typography.titleSmall)
                    Text(t("只在这一页用相机，扫完就关。图像不离开手机，也不上传。"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    if (denied) Pill(t("去设置里开")) {
                        runCatching {
                            ctx.startActivity(
                                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    } else Pill(t("给权限")) { ask.launch(Manifest.permission.CAMERA) }
                }
                else -> {
                    CameraPreview(paused = hit != null, onText = { if (hit == null) hit = it }, onError = { err = it })
                    // 取景框：只是个视觉引导，解码用的是整帧
                    Box(
                        Modifier.size(230.dp).clip(RoundedCornerShape(20.dp))
                            .background(Color.Transparent),
                    )
                    err?.let {
                        Text(it, Modifier.align(Alignment.BottomCenter).padding(24.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (hit == null) Text(
                        t("对准二维码 / 条形码"),
                        Modifier.align(Alignment.BottomCenter).padding(28.dp)
                            .clip(RoundedCornerShape(100.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(14.dp, 7.dp),
                        style = MaterialTheme.typography.labelMedium, color = Color.White,
                    )
                }
            }
        }
    }

    hit?.let { text ->
        val url = text.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
        AlertDialog(
            onDismissRequest = { hit = null },
            title = { Text(if (url != null) t("扫到一个网址") else t("扫到的内容")) },
            text = {
                Text(text.take(1000), style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = if (url == null) FontFamily.Monospace else null))
            },
            confirmButton = {
                if (url != null) TextButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { android.widget.Toast.makeText(ctx, t("打不开浏览器：%s").format(it.message ?: ""), android.widget.Toast.LENGTH_LONG).show() }
                }) { Text(t("打开")) } else TextButton(onClick = {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("scan", text))
                    android.widget.Toast.makeText(ctx, t("复制好了"), android.widget.Toast.LENGTH_SHORT).show()
                }) { Text(t("复制")) }
            },
            dismissButton = { TextButton(onClick = { hit = null }) { Text(t("再扫一次")) } },
        )
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(100.dp),
        modifier = Modifier.clip(RoundedCornerShape(100.dp)).clickable(onClick = onClick)) {
        Text(label, Modifier.padding(18.dp, 9.dp), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary)
    }
}

/**
 * 取景 + 逐帧解码。
 *
 * ⚠️ **一帧只解一次、解不出就丢**（`STRATEGY_KEEP_ONLY_LATEST`）：手机上一帧解码几十毫秒，
 *   排队会越积越多，表现是画面卡住、扫到了也慢半拍。
 * ⚠️ 解码器**复用一个** [MultiFormatReader]：每帧新建一个会疯狂 GC。
 * ⚠️ [paused] 为真（已经扫到、正在给用户看）时**不再解码**，但相机不停 —— 用户点「再扫一次」就立刻能用。
 */
@Composable
private fun CameraPreview(paused: Boolean, onText: (String) -> Unit, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val exec = remember { Executors.newSingleThreadExecutor() }
    val reader = remember { newReader() }
    /** 用 state 传给分析回调 —— 回调是长命的，直接捕获 paused 会读到进来那一刻的旧值 */
    val pausedNow by rememberUpdatedState(paused)
    DisposableEffect(Unit) { onDispose { exec.shutdown() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            val view = PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val future = ProcessCameraProvider.getInstance(c)
            future.addListener({
                runCatching {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(exec) { proxy ->
                        try {
                            if (!pausedNow) decode(proxy, reader)?.let { text ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post { onText(text) }
                            }
                        } finally { proxy.close() }   // ⚠️ 不 close 就再也收不到下一帧，画面直接冻住
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }.onFailure { onError(t("相机打不开：%s").format(it.message ?: "")) }
            }, ContextCompat.getMainExecutor(c))
            view
        },
    )
}

/**
 * 一帧 YUV → 文本；解不出返回 null。
 *
 * ⚠️ **要按 rowStride 逐行拷贝**：相机给的 Y 平面每行末尾常有填充（rowStride > width），
 *   整块直接拷会把填充也当成像素，图像逐行斜掉、条码永远解不出来。
 */
private fun decode(proxy: ImageProxy, reader: MultiFormatReader): String? {
    val plane = proxy.planes.firstOrNull() ?: return null
    val w = proxy.width
    val h = proxy.height
    val buf = plane.buffer
    val stride = plane.rowStride
    val data = ByteArray(w * h)
    if (stride == w) {
        buf.get(data, 0, minOf(buf.remaining(), data.size))
    } else {
        val row = ByteArray(stride)
        var out = 0
        for (y in 0 until h) {
            if (buf.remaining() < stride) break
            buf.get(row, 0, stride)
            System.arraycopy(row, 0, data, out, w)
            out += w
        }
    }
    return decodeLuminance(data, w, h, reader)
}

/**
 * 灰度平面 → 文本；解不出返回 null。**跟相机无关**，所以能直接喂一张生成的码来测
 * （模拟器的虚拟摄像头里摆不进二维码，这是唯一能证明解码链路真的管用的办法）。
 */
internal fun decodeLuminance(data: ByteArray, w: Int, h: Int, reader: MultiFormatReader = newReader()): String? {
    val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
    return runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }
        .getOrNull()
        .also { reader.reset() }
}

/** 配好格式白名单的解码器。界面复用一个（每帧新建会疯狂 GC），测试各建各的。 */
internal fun newReader(): MultiFormatReader = MultiFormatReader().apply {
    setHints(EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
        put(DecodeHintType.POSSIBLE_FORMATS, listOf(
            BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC,
            BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
            BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.ITF,
        ))
        put(DecodeHintType.TRY_HARDER, true)
    })
}
