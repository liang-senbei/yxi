package app.yxi.desktop

import org.json.JSONObject
import java.util.Base64

/** Immutable encoded image snapshot; file paths and later source edits never change the submitted bytes. */
internal class ClaudeImageInput private constructor(private val encoded: String, val mediaType: String, val size: Int) {
    fun block(): JSONObject = JSONObject().put("type", "image").put("source", JSONObject()
        .put("type", "base64").put("media_type", mediaType).put("data", encoded))
    companion object {
        fun fromBytes(bytes: ByteArray): ClaudeImageInput {
            require(bytes.isNotEmpty() && bytes.size <= 5 * 1024 * 1024) { "单张图片需小于或等于5MiB" }
            val mime = when {
                bytes.size >= 8 && bytes.take(8).map { it.toInt() and 255 } == listOf(137,80,78,71,13,10,26,10) -> "image/png"
                bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
                bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
                bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
                else -> error("仅支持PNG、JPEG、GIF或WebP图片内容")
            }
            return ClaudeImageInput(Base64.getEncoder().encodeToString(bytes), mime, bytes.size)
        }
    }
}
