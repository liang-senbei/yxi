package app.yxi.agent

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * 语音模型的 `.tgz` 解包（[AsrModel.unpack]）。
 *
 * ⚠️ 守两件事：① 长度不是 512 整数倍的条目（要跳补齐）和白名单外的条目（要整段跳过）都能正确走过去 ——
 *    这两处原来用的是 API 33/34 才有的 readNBytes / skipNBytes，8.0～13 上一跑就 NoSuchMethodError；
 * ② 白名单外的名字一个字节都不写（Zip Slip）。
 */
class AsrUnpackTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 一个 ustar 条目：512 字节头（名字 / 八进制长度 / 类型）+ 内容 + 补齐到 512 */
    private fun entry(name: String, body: ByteArray, type: Char = '0'): ByteArray {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        "%011o\u0000".format(body.size).toByteArray().copyInto(h, 124)
        h[156] = type.code.toByte()
        return h + body + ByteArray((512 - body.size % 512) % 512)
    }

    private fun gzip(b: ByteArray): ByteArray =
        ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    @Test fun 补齐和白名单外的条目都能跳过() {
        val root = File(ctx.cacheDir, "asr-unpack-test").apply { deleteRecursively(); mkdirs() }
        val into = File(root, "out").apply { mkdirs() }
        val tokens = "a 1\nb 2\n".toByteArray()                       // 8 字节 → 补 504
        val evil = ByteArray(700) { 7 }                               // 白名单外：整段跳过
        val model = ByteArray(1500) { (it % 251).toByte() }          // 跨三个块
        val tgz = File(root, "m.tgz")
        tgz.writeBytes(gzip(
            entry("pkg/tokens.txt", tokens) +
                entry("../evil.txt", evil) +
                entry("pkg/model.int8.onnx", model) +
                ByteArray(1024),                                      // tar 结尾的两个空块
        ))

        AsrModel.unpack(tgz, into)

        assertArrayEquals(tokens, File(into, "tokens.txt").readBytes())
        assertArrayEquals(model, File(into, "model.int8.onnx").readBytes())
        assertFalse(File(into, "evil.txt").exists())
        assertFalse(File(root, "evil.txt").exists())
        assertEquals(setOf("tokens.txt", "model.int8.onnx"), into.list()!!.toSet())
        root.deleteRecursively()
    }
}
