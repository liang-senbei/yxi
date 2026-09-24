package app.yxi.desktop

import app.yxi.agent.ChatItem
import app.yxi.agent.Transcript
import org.json.JSONObject
import java.io.File
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer

/** Bounded read-only native history. Never picks a session by title or modification time. */
internal object LocalClaudeHistory {
    private const val MAX_BYTES = 8 * 1024 * 1024
    fun read(record: LocalCodexTaskRecord): List<ChatItem> {
        require(record.engine == "claude" && record.hostKey.isBlank())
        require(java.util.UUID.fromString(record.threadId).toString() == record.threadId)
        val projects = File(record.runtimeHome, "projects").canonicalFile
        check(projects.isDirectory) { "Claude 原生历史目录不存在" }
        val candidates = mutableListOf<File>()
        java.nio.file.Files.newDirectoryStream(projects.toPath()).use { entries ->
            var count = 0
            for (entry in entries) {
                check(++count <= 2048) { "原生项目目录过多，请先缩小历史范围" }
                if (!java.nio.file.Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue
                val file = entry.resolve(record.threadId + ".jsonl")
                if (java.nio.file.Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) candidates.add(file.toFile())
            }
        }
        check(candidates.size == 1) { if (candidates.isEmpty()) "未找到原会话历史" else "原会话历史存在多个位置，请先核对" }
        val bytes = candidates.single().inputStream().use { it.readNBytes(MAX_BYTES + 1) }
        check(bytes.size <= MAX_BYTES) { "历史超过8MiB，尚需分页读取；未截断显示" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        var matched = false
        val cwd = File(record.directory).canonicalPath
        for (line in lines) {
            val row = JSONObject(line)
            if (row.has("sessionId")) check(row.optString("sessionId") == record.threadId) { "历史会话身份不匹配" }
            if (row.has("cwd")) check(File(row.getString("cwd")).canonicalPath == cwd) { "历史工作目录不匹配" }
            if (row.optString("sessionId") == record.threadId && row.has("cwd")) matched = true
        }
        check(matched) { "历史缺少可核对的会话身份与工作目录" }
        return Transcript.parse(lines.asSequence())
    }
}
