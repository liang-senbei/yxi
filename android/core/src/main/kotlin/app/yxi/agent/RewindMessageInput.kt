package app.yxi.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Creates native stream-json input while retaining the selected message's actual images. */
object RewindMessageInput {
    const val MAX_IMAGES = 10
    const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
    private val mediaTypes = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    data class Prepared(val json: String, val imageCount: Int, val imageBytes: Int)

    fun create(message: JSONObject, editedText: String): Prepared {
        require(message.optString("role") == "user") { "Only user messages can be edited" }
        require(editedText.isNotBlank() && editedText.length <= 100_000 && '\u0000' !in editedText) { "Invalid edited message" }
        val source = when (val value = message.opt("content")) {
            is String -> JSONArray().put(JSONObject().put("type", "text").put("text", value))
            is JSONArray -> value
            else -> error("Unsupported message content")
        }
        val content = JSONArray()
        var images = 0
        var imageBytes = 0
        var texts = 0
        for (index in 0 until source.length()) {
            val block = source.optJSONObject(index) ?: error("Unsupported message block")
            when (block.optString("type")) {
                "text" -> {
                    require(++texts == 1 && block.opt("text") is String) { "Multiple text blocks require explicit editing support" }
                    content.put(JSONObject().put("type", "text").put("text", editedText))
                }
                "image" -> {
                    require(++images <= MAX_IMAGES) { "Too many images" }
                    val image = block.optJSONObject("source") ?: error("Missing image source")
                    require(image.optString("type") == "base64") { "Only embedded images can be restored" }
                    val media = image.optString("media_type")
                    require(media in mediaTypes) { "Unsupported image format" }
                    val data = image.opt("data") as? String ?: error("Missing image data")
                    require(data.isNotEmpty() && data.length <= ((MAX_IMAGE_BYTES + 2) / 3) * 4) { "Image size limit exceeded" }
                    val decoded = Base64.getDecoder().decode(data)
                    require(decoded.isNotEmpty() && decoded.size <= MAX_IMAGE_BYTES - imageBytes) { "Combined image size limit exceeded" }
                    imageBytes += decoded.size
                    content.put(JSONObject().put("type", "image").put("source", JSONObject()
                        .put("type", "base64").put("media_type", media).put("data", data)))
                }
                else -> error("Unsupported message block: ${block.optString("type")}")
            }
        }
        if (texts == 0) content.put(JSONObject().put("type", "text").put("text", editedText))
        val request = JSONObject().put("type", "user").put("message", JSONObject()
            .put("role", "user").put("content", content))
        return Prepared(request.toString(), images, imageBytes)
    }
}
