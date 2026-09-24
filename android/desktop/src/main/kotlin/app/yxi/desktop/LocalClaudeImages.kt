package app.yxi.desktop

import org.json.JSONObject
import java.io.File
import java.util.Base64

/** Queue stores an opaque local reference, never a source path that can change before sending. */
internal class LocalClaudeImages(private val directory: File) {
    fun capture(source: File): InstructionAttachment {
        require(source.isFile) { "图片文件不存在" }
        val bytes = source.inputStream().use { it.readNBytes(MAX_BYTES + 1) }
        return capture(source.name, bytes)
    }
    fun capture(name: String, bytes: ByteArray): InstructionAttachment {
        require(name.isNotBlank() && name.length <= 500 && name.none { it < ' ' })
        ClaudeImageInput.fromBytes(bytes)
        val digest = contentHash(bytes)
        val target = File(directory, "$digest.json")
        val reference = InstructionAttachment(name, PREFIX + digest)
        val storage = DurableFile(target) { decode(it, digest) }
        if (target.exists()) load(reference) else storage.write(JSONObject().put("data", Base64.getEncoder().encodeToString(bytes)).toString())
        return reference
    }
    fun load(attachment: InstructionAttachment): ClaudeImageInput {
        require(attachment.remotePath.startsWith(PREFIX)) { "不是本地 Claude 图片快照" }
        val digest = attachment.remotePath.removePrefix(PREFIX)
        require(Regex("[a-f0-9]{64}").matches(digest)) { "图片快照引用无效" }
        val target = File(directory, "$digest.json")
        check(target.isFile && target.length() <= 7 * 1024 * 1024) { "图片快照不存在或过大" }
        check(!java.nio.file.Files.isSymbolicLink(target.toPath())) { "图片快照不能是符号链接" }
        return ClaudeImageInput.fromBytes(decode(target.inputStream().use { it.readNBytes(7 * 1024 * 1024 + 1) }.toString(Charsets.UTF_8), digest))
    }
    fun preview(attachment: InstructionAttachment): DraftAttach {
        val image = load(attachment)
        val bytes = Base64.getDecoder().decode(image.block().getJSONObject("source").getString("data"))
        return DraftAttach(attachment.name, true, bytes.size.toLong(), attachment.remotePath) { bytes.inputStream() }
    }
    private fun decode(raw: String, digest: String): ByteArray {
        require(raw.length <= 7 * 1024 * 1024)
        val encoded = JSONObject(raw).getString("data")
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= MAX_BYTES && contentHash(bytes) == digest) { "图片快照内容不匹配，请重新选择图片" }
        ClaudeImageInput.fromBytes(bytes)
        return bytes
    }
    companion object {
        private const val MAX_BYTES = 5 * 1024 * 1024
        private const val PREFIX = "/yxi-local-image/"
    }
}
