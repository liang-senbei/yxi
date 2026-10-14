package app.yxi.desktop

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * 本机 Codex 的图片快照。队列里只存以 / 开头的不透明引用（instructions.json 全局共用，附件路径必须以 / 开头），
 * 真实文件按内容哈希存在 directory 下，发送时才换成绝对路径交给 localImage；原图之后改了或删了都不影响已排队的指令。
 */
internal class LocalCodexImages(private val directory: File) {
    fun capture(source: File): InstructionAttachment {
        require(source.isFile) { "图片文件不存在" }
        val bytes = source.inputStream().use { it.readNBytes(MAX_BYTES + 1) }
        return capture(source.name, bytes)
    }

    fun capture(name: String, bytes: ByteArray): InstructionAttachment {
        require(name.isNotBlank() && name.length <= 500 && name.none { it < ' ' }) { "图片名称无效" }
        // Codex 的 localImage 只认这三种；GIF 交给运行器会变成纯文本引用，所以在入口就拒掉。
        val extension = extension(bytes) ?: error("仅支持PNG、JPEG或WebP图片")
        ClaudeImageInput.fromBytes(bytes)
        val digest = contentHash(bytes)
        val target = File(directory, "$digest.$extension")
        val reference = InstructionAttachment(name, PREFIX + target.name)
        if (runCatching { resolve(reference) }.isFailure) write(target, bytes)
        return reference
    }

    /** 引用换成真实绝对路径；文件缺失、被换成链接或内容和哈希对不上都拒绝，不把别的文件交给运行器。 */
    fun resolve(attachment: InstructionAttachment): String = snapshot(attachment).first.absolutePath

    /**
     * 草稿合并（同 [LocalClaudeImages.mergeDraft]）：按引用去重、最多 4 张，每张都要还能核对。不设总大小上限：单张已限 5MiB，
     * 发给运行器的只是路径（Claude 那边要把图编进消息体，所以才限总量）。
     */
    fun mergeDraft(existing: List<InstructionAttachment>, additions: List<InstructionAttachment>): List<InstructionAttachment> {
        val merged = (existing + additions).distinctBy { it.remotePath }
        require(merged.size <= 4) { "每次最多4张图片" }
        merged.forEach { snapshot(it) }
        return merged
    }

    /** 只补核对过的新引用；读图期间被移除的不复活（同 [LocalClaudeImages.commitDraft]）。 */
    fun commitDraft(current: MutableList<InstructionAttachment>, before: List<InstructionAttachment>, validated: List<InstructionAttachment>) {
        val previous = before.map { it.remotePath }.toSet()
        validated.filter { it.remotePath !in previous }.forEach { addition ->
            if (current.none { it.remotePath == addition.remotePath }) current.add(addition)
        }
    }

    /** 托盘缩略图读快照本身（发出去的就是这份），stamp 是引用，移除时按它找回草稿。 */
    fun preview(attachment: InstructionAttachment): DraftAttach {
        val bytes = snapshot(attachment).second
        return DraftAttach(attachment.name, true, bytes.size.toLong(), attachment.remotePath) { bytes.inputStream() }
    }

    private fun snapshot(attachment: InstructionAttachment): Pair<File, ByteArray> {
        require(attachment.remotePath.startsWith(PREFIX)) { "不是本机 Codex 图片快照，请重新添加图片" }
        val name = attachment.remotePath.removePrefix(PREFIX)
        require(NAME.matches(name)) { "图片快照引用无效" }
        val target = File(directory, name)
        check(!Files.isSymbolicLink(target.toPath())) { "图片快照不能是符号链接" }
        check(target.isFile && target.length() <= MAX_BYTES) { "图片快照不存在或过大，请重新添加图片" }
        val bytes = target.inputStream().use { it.readNBytes(MAX_BYTES + 1) }
        check(contentHash(bytes) == name.substringBefore('.')) { "图片快照内容不匹配，请重新添加图片" }
        return target to bytes
    }

    private fun write(target: File, bytes: ByteArray) {
        Files.createDirectories(directory.toPath())
        val temp = Files.createTempFile(directory.toPath(), target.name + ".", ".tmp").toFile()
        try {
            temp.setReadable(false, false); temp.setReadable(true, true)
            temp.setWritable(false, false); temp.setWritable(true, true)
            FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }
            Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    companion object {
        private const val MAX_BYTES = 5 * 1024 * 1024
        internal const val PREFIX = "/yxi-local-codex-image/"
        private val NAME = Regex("[a-f0-9]{64}\\.(png|jpg|webp)")

        private fun extension(bytes: ByteArray): String? = when {
            bytes.size >= 8 && bytes.take(8).map { it.toInt() and 255 } == listOf(137, 80, 78, 71, 13, 10, 26, 10) -> "png"
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "jpg"
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            else -> null
        }
    }
}
