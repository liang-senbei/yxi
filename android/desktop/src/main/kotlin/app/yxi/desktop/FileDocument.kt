package app.yxi.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.text.input.TextFieldValue
import app.yxi.ssh.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

internal const val TEXT_LIMIT = 1024 * 1024
internal fun contentHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun decodeDocument(bytes: ByteArray): String {
    require(bytes.size <= TEXT_LIMIT) { "文本超过 1 MB，请下载后使用外部编辑器" }
    require(bytes.none { it == 0.toByte() }) { "二进制文件不能作为文本编辑" }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}

internal data class FileSnapshot(val bytes: ByteArray) {
    val hash = contentHash(bytes)
}

/** Per-host, per-task state lives outside composition so switching tasks never discards unsaved edits. */
data class DocumentEndpoint(val hostname: String, val port: Int, val username: String) {
    companion object { fun of(host: Host) = DocumentEndpoint(host.hostname.trim().lowercase(), host.port, host.username) }
}

class FileDocument(val hostId: String, val task: String, val path: String, val endpoint: DocumentEndpoint? = null) {
    internal fun checkEndpoint(host: Host) {
        check(host.id == hostId && endpoint == DocumentEndpoint.of(host)) {
            "主机地址或登录用户已变化。旧文档已冻结，编辑仍保留；请另存草稿并重新打开文件。"
        }
    }
    internal val io = Mutex()
    internal var base by mutableStateOf<FileSnapshot?>(null)
    internal var incoming by mutableStateOf<FileSnapshot?>(null)
    var editor by mutableStateOf(TextFieldValue())
    var mode by mutableStateOf(if (path.endsWith(".md", true)) "预览" else "源码")
    var error by mutableStateOf("")
    var status by mutableStateOf("正在读取…")
    var busy by mutableStateOf(false)
    var loadedAt by mutableStateOf(0L)
    val image get() = path.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    val dirty get() = !image && base != null && contentHash(editor.text.toByteArray()) != base?.hash
    val conflict get() = incoming != null

    internal fun receive(snapshot: FileSnapshot) {
        val text = if (image) "" else decodeDocument(snapshot.bytes)
        if (!dirty || editor.text == text) {
            if (editor.text != text) editor = TextFieldValue(text)
            base = snapshot; incoming = null
        } else if (base?.hash != snapshot.hash) incoming = snapshot
        else incoming = null
        loadedAt = System.currentTimeMillis()
        status = if (conflict) "文件已被其他人修改，请比较后处理" else if (dirty) "本地编辑未保存" else "已同步"
        error = ""
    }

    internal fun useIncoming() {
        val next = incoming ?: return
        editor = TextFieldValue(decodeDocument(next.bytes)); base = next; incoming = null; status = "已载入服务器版本"
    }
}

/** Files are loaded through short-lived SFTP channels. Saving uses a version guard and an atomic rename. */
internal object RemoteDocuments {
    suspend fun read(conn: Conn, path: String, image: Boolean): FileSnapshot {
        val s = conn.ssh.openSftp()
        try {
            val limit = if (image) 8 * TEXT_LIMIT else TEXT_LIMIT
            require(s.size(path) <= limit) { "文件太大，请使用下载功能" }
            val bytes = s.read(path, limit + 1)
            require(bytes.size <= limit) { "文件在读取时增大，已停止预览" }
            return FileSnapshot(bytes)
        } finally { s.close() }
    }

    suspend fun refresh(conn: Conn, doc: FileDocument) = doc.io.withLock {
        try { doc.checkEndpoint(conn.host); doc.receive(read(conn, doc.path, doc.image)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { doc.error = e.message ?: "读取失败"; doc.status = if (doc.base == null) "未载入" else "显示缓存，尚未同步" }
    }

    suspend fun save(conn: Conn, doc: FileDocument) = doc.io.withLock {
        val expected = doc.base?.hash ?: return@withLock
        if (doc.conflict || !doc.dirty) return@withLock
        doc.busy = true
        val text = doc.editor.text
        val bytes = text.toByteArray()
        var temporary: String? = null
        try {
            doc.checkEndpoint(conn.host)
            require(bytes.size <= TEXT_LIMIT) { "文本超过 1 MB，不能保存" }
            val s = conn.ssh.openSftp()
            try {
                val canonical = s.realpath(doc.path)
                require(canonical == doc.path) { "文件路径已变化，请重新打开" }
                temporary = doc.path.substringBeforeLast('/') + "/.yxi-edit-" + UUID.randomUUID()
                check(conn.ssh.exec("(umask 077; set -C; : > " + Shell.q(temporary!!) + ") && printf YXI_TEMP_CREATED").trim() == "YXI_TEMP_CREATED") { "无法创建受保护的临时文件" }
                s.write(temporary!!, bytes)
                val result = conn.ssh.exec(saveCommand(doc.path, temporary!!, expected, contentHash(bytes)))
                val json = runCatching { JSONObject(result.trim()) }.getOrElse { error("未收到保存确认，请刷新核对；编辑内容已保留") }
                if (json.optString("status") == "conflict") {
                    doc.receive(read(conn, doc.path, false))
                    error("服务器文件已变化，未覆盖。请比较两个版本")
                }
                check(json.optString("status") == "saved") { json.optString("error", "保存未确认") }
                // Do not discard any typing that happened while this save was in flight.
                doc.base = FileSnapshot(bytes); doc.incoming = null
                doc.status = if (doc.dirty) "已保存上一版本，仍有新编辑" else "已保存"
                doc.error = ""; doc.loadedAt = System.currentTimeMillis()
            } finally { s.close() }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { doc.error = e.message ?: "保存失败，编辑内容已保留" }
        finally {
            doc.busy = false
            // Cleanup is best effort and never touches the original document.
            temporary?.let { tmp -> runCatching { conn.ssh.exec("rm -f -- " + Shell.q(tmp)) } }
        }
    }

    internal fun saveCommand(path: String, temp: String, expected: String, wanted: String): String {
        val args = JSONObject().put("path", path).put("temp", temp).put("expected", expected).put("wanted", wanted)
        val encoded = Base64.getEncoder().encodeToString(args.toString().toByteArray())
        return "python3 -c " + Shell.q(SAVE_SCRIPT) + " " + Shell.q(encoded)
    }

    internal val SAVE_SCRIPT = """
import os,sys,json,base64,hashlib,stat,fcntl
a=json.loads(base64.b64decode(sys.argv[1])); p=a['path']; t=a['temp']
def digest(path):
    with open(path,'rb') as f: return hashlib.sha256(f.read(1048577)).hexdigest()
try:
    # Cooperating Yxi editors serialize the version check. Other editors are checked optimistically.
    with open(p,'rb') as original:
        fcntl.flock(original,fcntl.LOCK_EX)
        before=os.fstat(original.fileno())
        if os.path.realpath(p)!=p or digest(p)!=a['expected']:
            print(json.dumps({'status':'conflict'})); sys.exit(0)
        if digest(t)!=a['wanted']: raise ValueError('Uploaded content did not verify')
        os.chmod(t,stat.S_IMODE(before.st_mode))
        current=os.stat(t)
        if (current.st_uid,current.st_gid)!=(before.st_uid,before.st_gid): os.chown(t,before.st_uid,before.st_gid)
        with open(t,'rb') as prepared: os.fsync(prepared.fileno())
        now=os.stat(p)
        if (now.st_dev,now.st_ino)!=(before.st_dev,before.st_ino) or digest(p)!=a['expected']:
            print(json.dumps({'status':'conflict'})); sys.exit(0)
        os.replace(t,p)
        print(json.dumps({'status':'saved'}))
except Exception as e: print(json.dumps({'status':'error','error':str(e)}))
""".trimIndent()
}
