package app.yxi.ssh

import app.yxi.ui.t
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 远端文件浏览。
 *
 * ⚠️ **走 SFTP，不在服务器上装任何东西** —— SFTP 是 sshd 自带的子系统。
 * 这跟整个项目的取向一致：能连 SSH 就能用，目标机可能什么都没装（PRD 附录 H）。
 *
 * ⚠️ **SFTP 是一条通道上的请求/应答协议，两个协程同时用会串包。**
 * 所以每个操作都上锁 —— 跟 [SshSession] 里 `chanLock` 是同一类问题
 * （TROUBLESHOOTING #16：jsch 的写包路径不是线程安全的）。
 */
/**
 * @param lock ⚠️ **这把锁必须是 [SshSession] 级的，不能每条通道自己 new 一把。**
 *   原来是 `private val lock = Mutex()` —— 而每次上传都新开一条通道
 *   （见 [SshSession.openSftp] 的调用方），于是两次并发上传拿到**两把不同的锁**，
 *   等于没锁。而 jsch 的 Session 写包路径**不是线程安全**的（TROUBLESHOOTING #16，
 *   当年只给 Shell 修了，SFTP 一直漏着）：两条通道同时往一条连接写就把包流写坏，
 *   表现是「第一张还没传完就点第二张，第二张失败」。
 */
class Sftp internal constructor(private val ch: ChannelSftp, private val lock: Mutex) {


    data class Entry(
        val name: String,
        val isDir: Boolean,
        val size: Long,
        /** 秒级时间戳 */
        val mtime: Int,
        /** 符号链接（isDir 已经是解引用之后的结果） */
        val isLink: Boolean = false,
    )

    /** 列一个目录。目录在前、再按名字排；`.` 和 `..` 不返回。 */
    suspend fun list(path: String): List<Entry> = withContext(Dispatchers.IO) {
        lock.withLock {
            @Suppress("UNCHECKED_CAST")
            val raw = ch.ls(path) as java.util.Vector<ChannelSftp.LsEntry>
            raw.asSequence()
                .filter { it.filename != "." && it.filename != ".." }
                .map { e ->
                    val a = e.attrs
                    // 符号链接指向目录时，lstat 说它是链接不是目录 —— 点进去才对，
                    // 所以额外 stat 一次解引用。解不开（悬空链接）就当普通文件
                    val dir = if (a.isLink) {
                        runCatching { ch.stat("$path/${e.filename}").isDir }.getOrDefault(false)
                    } else a.isDir
                    Entry(e.filename, dir, a.size, a.mTime, a.isLink)
                }
                .sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
                .toList()
        }
    }

    /**
     * 读一个文件。
     * @param max 上限。手机上没必要把几百兆的日志拉回来 —— 超了就截断，界面负责说明。
     */
    suspend fun read(path: String, max: Int = 2 shl 20): ByteArray = withContext(Dispatchers.IO) {
        lock.withLock {
            val out = ByteArrayOutputStream()
            ch.get(path).use { input ->
                val buf = ByteArray(32 * 1024)
                while (out.size() < max) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        }
    }

    /**
     * 把远端文件**流式**写到本地文件。更新包 30 多 MB，没必要整个读进内存。
     * @return 实际字节数
     */
    suspend fun download(path: String, into: java.io.File, onProgress: (Long) -> Unit = {}): Long =
        withContext(Dispatchers.IO) {
            lock.withLock {
                into.parentFile?.mkdirs()
                var n = 0L
                ch.get(path).use { input ->
                    into.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r); n += r
                            onProgress(n)
                        }
                    }
                }
                n
            }
        }

    /** 写一个文件（目录要先存在）。附件上传用。 */
    suspend fun write(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        lock.withLock { ch.put(java.io.ByteArrayInputStream(bytes), path, ChannelSftp.OVERWRITE) }
    }

    suspend fun mkdirs(path: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val parts = path.trim('/').split('/')
            var cur = ""
            for (seg in parts) {
                cur += "/$seg"
                runCatching { ch.mkdir(cur) }   // 已存在就抛，忽略即可
            }
        }
    }

    /** 把 `~`、`.`、`..` 这些解析成绝对路径。目标不存在会抛。 */
    suspend fun realpath(path: String): String = withContext(Dispatchers.IO) {
        lock.withLock { ch.realpath(path) }
    }

    suspend fun isDir(path: String): Boolean = withContext(Dispatchers.IO) {
        lock.withLock { runCatching { ch.stat(path).isDir }.getOrDefault(false) }
    }

    suspend fun size(path: String): Long = withContext(Dispatchers.IO) {
        lock.withLock { runCatching { ch.stat(path).size }.getOrDefault(-1L) }
    }

    fun close() = runCatching { ch.disconnect() }.let { }

    companion object {
        /** 报错文案：SFTP 的异常消息经常只有个错误码。 */
        fun explain(e: Throwable): String = when {
            e is SftpException && e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE -> t("没有这个文件或目录")
            e is SftpException && e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED -> t("没有权限")
            else -> e.message ?: e::class.simpleName.orEmpty()
        }
    }
}

/**
 * 路径工具。**纯字符串运算，可以单独测** —— 相对路径解析错了图片就显示不出来，
 * 而那种错误在界面上只表现为「图裂了」，很难定位。
 */
object Paths {
    /** 目录部分（不含末尾斜杠）。`/a/b/c.md` → `/a/b`；没有斜杠时返回 `.` */
    fun dirOf(path: String): String {
        val i = path.trimEnd('/').lastIndexOf('/')
        return when {
            i < 0 -> "."
            i == 0 -> "/"
            else -> path.substring(0, i)
        }
    }

    fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

    fun extOf(path: String): String = nameOf(path).substringAfterLast('.', "").lowercase()

    /**
     * 把 [ref] 按 [base] 目录解析成绝对路径，并把 `.` / `..` 折叠掉。
     * markdown 里的相对图片路径就靠它。绝对路径原样返回。
     */
    fun resolve(base: String, ref: String): String {
        if (ref.startsWith("/")) return normalize(ref)
        return normalize(base.trimEnd('/') + "/" + ref)
    }

    /** 折叠 `.` 和 `..`，去掉重复斜杠。 */
    fun normalize(path: String): String {
        val abs = path.startsWith("/")
        val out = ArrayDeque<String>()
        path.split('/').forEach { seg ->
            when (seg) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeLast()
                        else if (!abs) out.addLast("..")
                else -> out.addLast(seg)
            }
        }
        val body = out.joinToString("/")
        return if (abs) "/$body" else body.ifEmpty { "." }
    }

    /** 面包屑：`/a/b/c` → [("/", "/"), ("a", "/a"), ("b", "/a/b"), ("c", "/a/b/c")] */
    fun crumbs(path: String): List<Pair<String, String>> {
        val out = mutableListOf("/" to "/")
        var acc = ""
        path.split('/').filter { it.isNotEmpty() }.forEach { seg ->
            acc += "/$seg"
            out += seg to acc
        }
        return out
    }
}
