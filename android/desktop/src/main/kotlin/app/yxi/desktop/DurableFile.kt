package app.yxi.desktop

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Validate before replacing, keep the last readable version, and never turn corruption into empty data. */
internal class DurableFile(val file: File, private val validate: (String) -> Unit) {
    private val backup get() = File(file.parentFile, file.name + ".bak")
    var recovered = false
        private set

    @Synchronized fun read(): String? {
        recovered = false
        if (!file.exists() && !backup.exists()) return null
        return try {
            file.readText().also(validate)
        } catch (original: Exception) {
            val saved = try { backup.readText().also(validate) } catch (_: Exception) { throw original }
            // Preserve the damaged original for diagnosis; do not replace the known-good backup.
            if (file.isFile) Files.copy(file.toPath(), File(file.parentFile, file.name + ".damaged").toPath(), REPLACE_EXISTING)
            replace(file, saved)
            recovered = true
            saved
        }
    }

    @Synchronized fun write(text: String) {
        validate(text)
        // A broken existing file must first be recovered, never silently overwritten.
        val previous = read()
        if (previous != null) replace(backup, previous)
        replace(file, text)
    }

    companion object {
        internal fun replace(target: File, text: String) {
            val parent = target.absoluteFile.parentFile
            Files.createDirectories(parent.toPath())
            val temp = Files.createTempFile(parent.toPath(), target.name + ".", ".tmp").toFile()
            try {
                temp.setReadable(false, false); temp.setReadable(true, true)
                temp.setWritable(false, false); temp.setWritable(true, true)
                FileOutputStream(temp).use { out -> out.write(text.toByteArray(Charsets.UTF_8)); out.fd.sync() }
                // Fail closed when atomic replacement is unavailable; leave the old file intact.
                Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { temp.delete() }
        }

        internal fun migrate(source: File, target: File, validate: (String) -> Unit) {
            if (!source.isFile || source.canonicalFile == target.canonicalFile) return
            val text = source.readText().also(validate)
            if (target.exists()) {
                val current = target.readText().also(validate)
                // Different valid versions need manual reconciliation; preserve both.
                check(current == text) { "新旧配置不同，已保留旧文件：${source.path}" }
            } else replace(target, text)
            check(target.readText() == text) { "配置迁移校验失败，旧文件已保留" }
            check(source.delete()) { "配置已迁移，但旧文件未能清理：${source.path}" }
        }
    }
}
