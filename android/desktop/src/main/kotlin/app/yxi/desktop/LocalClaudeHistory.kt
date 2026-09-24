package app.yxi.desktop

import app.yxi.agent.ChatItem
import app.yxi.agent.Transcript
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Read-only, snapshot-based pagination; native JSONL is never rewritten. */
internal object LocalClaudeHistory {
    private const val MAX_LINE = 2 * 1024 * 1024
    private const val MAX_PAGE = 8 * 1024 * 1024
    private const val MAX_ROWS = 250_000
    private data class Line(val offset: Long, val length: Int, val id: String?, val owner: String?, val operational: Boolean)
    private data class Item(val key: String, val source: Line, val result: Line? = null, val queued: List<Line>? = null)
    private class Snapshot(val recordKey: String, val file: File, val identity: String, val size: Long,
        val digest: ByteArray, val items: List<Item>, val partial: Boolean, val context: Transcript.Ctx?)
    class Cursor internal constructor(internal val snapshot: Any, internal val end: Int)
    data class Page(val items: List<ChatItem>, val earlier: Cursor?, val partialTail: Boolean, val context: Transcript.Ctx? = null)

    fun read(record: LocalCodexTaskRecord): List<ChatItem> = page(record).items
    fun page(record: LocalCodexTaskRecord, cursor: Cursor? = null, pageSize: Int = 100): Page {
        require(pageSize in 1..500)
        val file = locate(record)
        val snapshot = cursor?.let { it.snapshot as Snapshot } ?: index(record, file)
        check(snapshot.recordKey == identityOf(record) && snapshot.file.canonicalFile == file.canonicalFile) { "历史游标不属于此会话" }
        verify(snapshot)
        val end = cursor?.end ?: snapshot.items.size
        var start = end
        val selected = linkedSetOf<Line>()
        var bytes = 0L
        while (start > 0 && end - start < pageSize) {
            val item = snapshot.items[start - 1]
            val needed = (item.queued ?: listOfNotNull(item.source, item.result)).filter { it !in selected }
            val extra = needed.sumOf { it.length.toLong() }
            if (bytes + extra > MAX_PAGE) { check(start < end) { "单条历史及工具结果超过分页容量，请在原生客户端查看" }; break }
            selected.addAll(needed); bytes += extra; start--
        }
        val text = RandomAccessFile(file, "r").use { handle -> selected.associateWith { readLine(handle, it) } }
        val values = snapshot.items.subList(start, end).map { item ->
            val lines = item.queued ?: listOfNotNull(item.source, item.result)
            checkNotNull(Transcript.parse(lines.asSequence().map { text.getValue(it) }).firstOrNull { it.key == item.key }) { "历史分页条目不一致，请重新加载" }
        }
        verify(snapshot)
        return Page(values, if (start > 0) Cursor(snapshot, start) else null, snapshot.partial, snapshot.context)
    }
    private fun locate(record: LocalCodexTaskRecord): File {
        require(record.engine == "claude" && record.hostKey.isBlank())
        require(java.util.UUID.fromString(record.threadId).toString() == record.threadId)
        val projects = File(record.runtimeHome, "projects").canonicalFile
        check(projects.isDirectory) { "Claude 原生历史目录不存在" }
        val candidates = mutableListOf<File>()
        Files.newDirectoryStream(projects.toPath()).use { entries ->
            var count = 0
            for (entry in entries) {
                check(++count <= 2048) { "原生项目目录过多，请先缩小历史范围" }
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue
                val file = entry.resolve(record.threadId + ".jsonl")
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) candidates.add(file.toFile())
            }
        }
        check(candidates.size == 1) { if (candidates.isEmpty()) "未找到原会话历史" else "原会话历史存在多个位置，请先核对" }
        return candidates.single()
    }
    private fun identityOf(record: LocalCodexTaskRecord) = record.key + "|" + File(record.directory).canonicalPath
    private fun identity(file: File): String {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(attrs.isRegularFile) { "历史文件已被替换" }
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            val kernel = com.sun.jna.platform.win32.Kernel32.INSTANCE
            val handle = kernel.CreateFile(file.absolutePath, 0, 7, null, 3, 0x80, null)
            check(handle != com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE) { "无法核对历史文件身份" }
            try {
                // FILE_ID_INFO: 64-bit volume serial followed by the 128-bit native file ID.
                com.sun.jna.Memory(24).use { info ->
                    check(kernel.GetFileInformationByHandleEx(handle, 18, info, com.sun.jna.platform.win32.WinDef.DWORD(24))) { "无法读取历史文件身份" }
                    return info.getByteArray(0, 24).joinToString("") { "%02x".format(it) }
                }
            } finally { kernel.CloseHandle(handle) }
        }
        return "${attrs.fileKey()}:${attrs.creationTime()}"
    }
    private fun verify(snapshot: Snapshot) {
        check(identity(snapshot.file) == snapshot.identity && snapshot.file.length() >= snapshot.size) { "历史文件已被替换或截断，请重新加载" }
        val digest = MessageDigest.getInstance("SHA-256")
        snapshot.file.inputStream().buffered().use { stream ->
            var left = snapshot.size
            val buffer = ByteArray(64 * 1024)
            while (left > 0) {
                val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                check(count > 0) { "历史文件已被截断，请重新加载" }
                digest.update(buffer, 0, count); left -= count
            }
        }
        check(MessageDigest.isEqual(snapshot.digest, digest.digest())) { "历史内容已改写，请重新加载" }
    }
    private fun decode(bytes: ByteArray) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private fun readLine(file: RandomAccessFile, line: Line): String {
        file.seek(line.offset)
        return decode(ByteArray(line.length).also { file.readFully(it) })
    }
    private fun index(record: LocalCodexTaskRecord, file: File): Snapshot {
        val identity = identity(file)
        val size = file.length()
        val digest = MessageDigest.getInstance("SHA-256")
        val rows = ArrayList<Line>()
        val parents = HashMap<String, String?>()
        var leaf: String? = null
        var matched = false
        var partial = false
        var rowCount = 0
        val cwd = File(record.directory).canonicalPath
        fun accept(offset: Long, raw: ByteArray) {
            val text = decode(raw)
            if (text.isBlank()) return
            check(++rowCount <= MAX_ROWS) { "历史索引超过25万行，请在原生客户端归档后再加载" }
            val row = JSONObject(text)
            if (row.has("sessionId")) check(row.optString("sessionId") == record.threadId) { "历史会话身份不匹配" }
            if (row.has("cwd")) check(File(row.getString("cwd")).canonicalPath == cwd) { "历史工作目录不匹配" }
            if (row.optString("sessionId") == record.threadId && row.has("cwd")) matched = true
            if (row.optBoolean("isSidechain", false)) return
            val type = row.optString("type")
            check(row.optString("requestId").length <= 500) { "历史请求身份超过长度限制" }
            val id = row.optString("uuid").takeIf { it.isNotBlank() }
            val parent = row.optString("parentUuid").takeIf { it.isNotBlank() && it != "null" }
            check(id.orEmpty().length <= 500 && parent.orEmpty().length <= 500) { "历史消息身份超过长度限制" }
            val linked = id != null && row.has("parentUuid") && (row.isNull("parentUuid") || row.opt("parentUuid") is String)
            val known = id != null && parents.containsKey(id)
            val content = row.optJSONObject("message")?.opt("content")
            val hasResult = content is JSONArray && (0 until content.length()).any { content.optJSONObject(it)?.optString("type") == "tool_result" }
            val human = type == "user" && !row.optBoolean("isMeta", false) && !hasResult &&
                Transcript.parse(sequenceOf(text)).any { it is ChatItem.UserText }
            val chain: Set<String>? = when {
                !linked || known || !human || parent == leaf -> null
                parent == null -> emptySet()
                parents.containsKey(parent) -> buildSet {
                    var current: String? = parent
                    while (current != null && add(current)) current = parents[current]
                }
                else -> null
            }
            if (chain != null && leaf != null && leaf !in chain) {
                rows.removeAll { !it.operational && (chain.isEmpty() || (it.id ?: it.owner)?.let { owner -> owner !in chain } == true) }
                parents.keys.retainAll(chain)
            }
            if (type in setOf("user", "assistant", "attachment", "mode", "queue-operation"))
                rows.add(Line(offset, raw.size, if (linked) id else null, leaf, type == "queue-operation"))
            if (linked) {
                parents[id!!] = parent
                if (!known && type in setOf("user", "assistant")) leaf = id
            }
        }
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            val line = ByteArrayOutputStream()
            var position = 0L
            var lineStart = 0L
            while (position < size) {
                val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), size - position).toInt())
                check(count > 0) { "历史读取期间被截断" }
                digest.update(buffer, 0, count)
                for (i in 0 until count) {
                    if (buffer[i] == 10.toByte()) { accept(lineStart, line.toByteArray()); line.reset(); lineStart = position + i + 1 }
                    else { check(line.size() < MAX_LINE) { "历史单行超过2MiB，请在原生客户端查看" }; line.write(buffer[i].toInt()) }
                }
                position += count
            }
            if (line.size() > 0) {
                val tail = line.toByteArray()
                val complete = runCatching {
                    val text = decode(tail)
                    if (text.isBlank()) true else {
                        val tokens = org.json.JSONTokener(text)
                        tokens.nextValue() is JSONObject && tokens.nextClean() == '\u0000'
                    }
                }.getOrDefault(false)
                if (complete) accept(lineStart, tail) else partial = true
            }
        }
        check(matched) { "历史缺少可核对的完整会话身份与工作目录" }
        val normalized = normalize(file, rows)
        val snapshot = Snapshot(identityOf(record), file, identity, size, digest.digest(), normalized.first, partial, normalized.second)
        verify(snapshot)
        return snapshot
    }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun normalize(file: File, rows: List<Line>): Pair<List<Item>, Transcript.Ctx?> {
        val reader = Transcript.LineReader()
        val items = LinkedHashMap<String, Item>()
        val calls = HashMap<String, String>()
        val queued = ArrayList<Pair<String, Line>>()
        val said = HashSet<String>()
        RandomAccessFile(file, "r").use { handle ->
            for (source in rows) {
                val text = readLine(handle, source)
                val row = JSONObject(text)
                val parsed = reader.parse(text)
                if (row.optString("type") == "queue-operation") {
                    val content = row.optString("content")
                    when (row.optString("operation")) {
                        "enqueue" -> queued.add(hash(content) to source)
                        "remove", "popAll" -> if (content.isNotBlank()) { val index = queued.indexOfFirst { it.first == hash(content) }; if (index >= 0) queued.removeAt(index) } else if (queued.isNotEmpty()) queued.removeAt(0)
                        "dequeue" -> if (queued.isNotEmpty()) queued.removeAt(0)
                    }
                    continue
                }
                parsed.forEach { item ->
                    check(items.size < MAX_ROWS || items.containsKey(item.key)) { "历史条目过多，请在原生客户端归档" }
                    items[item.key] = Item(item.key, source, result = if (item is ChatItem.ToolCall) items[item.key]?.result else null)
                    if (item is ChatItem.UserText) said.add(hash(item.text.trim()))
                }
                if (row.optString("type") == "attachment") row.optJSONObject("attachment")?.let { attachment ->
                    if (attachment.optString("type") == "queued_command" && attachment.optJSONObject("origin")?.optString("kind") == "human") said.add(hash(attachment.optString("prompt").trim()))
                }
                val blocks = row.optJSONObject("message")?.optJSONArray("content") ?: continue
                val uuid = row.optString("uuid", row.optString("requestId", text.hashCode().toString()))
                for (i in 0 until blocks.length()) {
                    val block = blocks.optJSONObject(i) ?: continue
                    check(block.optString("id").length <= 500 && block.optString("tool_use_id").length <= 500) { "历史工具身份超过长度限制" }
                    check(calls.size < MAX_ROWS) { "历史工具索引过大，请先归档" }
                    when (block.optString("type")) {
                        "tool_use" -> if (row.optString("type") == "assistant") calls[block.optString("id")] = "$uuid-$i"
                        "tool_result" -> if (row.optString("type") == "user") calls[block.optString("tool_use_id")]?.let { key ->
                            items[key]?.let { item -> items[key] = item.copy(result = source) }
                        }
                    }
                }
            }
            val pending = queued.map { it.second }.filter {
                val content = JSONObject(readLine(handle, it)).optString("content")
                content.isNotBlank() && hash(content.trim()) !in said
            }
            check(pending.sumOf { it.length.toLong() } <= MAX_PAGE) { "待发送队列超过分页容量，请在原生客户端处理" }
            val pendingItems = Transcript.parse(pending.asSequence().map { readLine(handle, it) })
            for (item in pendingItems) items[item.key] = Item(item.key, pending.first(), queued = pending)
        }
        return items.values.toList() to reader.ctx
    }
}
