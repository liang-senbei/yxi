package app.yxi.desktop

import app.yxi.agent.RewindMessageInput
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Base64

internal data class RewindImageDraft(val originalIndex: Int, val attachment: DraftAttach)

internal fun rewindImageDrafts(messageUuid: String, message: JSONObject): List<RewindImageDraft> {
    val prepared = RewindMessageInput.create(message, "preview")
    val content = JSONObject(prepared.json).getJSONObject("message").getJSONArray("content")
    val images = mutableListOf<RewindImageDraft>()
    for (i in 0 until content.length()) {
        val block = content.getJSONObject(i)
        if (block.optString("type") != "image") continue
        val source = block.getJSONObject("source")
        val bytes = Base64.getDecoder().decode(source.getString("data"))
        val index = images.size
        val extension = when (source.getString("media_type")) {
            "image/jpeg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "png"
        }
        images += RewindImageDraft(index, DraftAttach("图片${index + 1}.$extension", true,
            bytes.size.toLong(), "$messageUuid:image:$index") { ByteArrayInputStream(bytes) })
    }
    return images
}
