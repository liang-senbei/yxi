package app.yxi

import app.yxi.ui.decodeLuminance
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 扫一扫的**解码链路**。
 *
 * ⚠️ 为什么不用界面测：模拟器的虚拟摄像头里摆不进一张二维码，`ScanScreen` 那条路只能验到
 * 「权限弹了 / 取景出画」。所以把解码抽成了不依赖相机的 [decodeLuminance]，这里**自己生成一张真码**
 * 再喂进去 —— 编码器和解码器是 zxing 里两套独立实现，能互相当判据。
 */
class ScanDecodeTest {

    /** BitMatrix → 灰度平面（黑 0 / 白 255），跟相机给的 Y 平面同一种数据 */
    private fun luma(m: BitMatrix): Triple<ByteArray, Int, Int> {
        val data = ByteArray(m.width * m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            data[y * m.width + x] = if (m.get(x, y)) 0 else 255.toByte()
        }
        return Triple(data, m.width, m.height)
    }

    /**
     * ⚠️ 生成时**必须指定 UTF-8**：zxing 的写入器默认按 ISO-8859-1 编，中文在**生成那一步**就成了 `?`，
     *   跑出来像是解码器不认中文（第一版就这么误判过）。真实世界的中文码（微信、支付宝那些）都是 UTF-8。
     */
    private fun roundTrip(text: String, format: BarcodeFormat, w: Int = 400, h: Int = 400): String? {
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
        val (data, mw, mh) = luma(MultiFormatWriter().encode(text, format, w, h, hints))
        return decodeLuminance(data, mw, mh)
    }

    @Test fun 二维码_网址() {
        assertEquals("https://yxi.app/hello", roundTrip("https://yxi.app/hello", BarcodeFormat.QR_CODE))
    }

    /** 中文码（微信/支付宝那类）走 UTF-8。zxing 解码端靠启发式猜字符集，这条就是钉住「猜对了」。 */
    @Test fun 二维码_中文() {
        assertEquals("云曦 扫一扫 测试", roundTrip("云曦 扫一扫 测试", BarcodeFormat.QR_CODE))
    }

    @Test fun 条形码_EAN13() {
        assertEquals("6901234567892", roundTrip("6901234567892", BarcodeFormat.EAN_13, 600, 300))
    }

    @Test fun 条形码_CODE128() {
        assertEquals("YXI-2026-0906", roundTrip("YXI-2026-0906", BarcodeFormat.CODE_128, 600, 300))
    }

    /** 纯白一片：不该瞎猜出东西来（误报比扫不出更糟 —— 用户会照着一个错的网址点） */
    @Test fun 白板_解不出就是解不出() {
        val w = 200; val h = 200
        assertNull(decodeLuminance(ByteArray(w * h) { 255.toByte() }, w, h))
    }
}
