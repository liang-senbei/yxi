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

    /**
     * 列一个目录。目录在前、再按名字排；`.` 和 `..` 不返回。
     *
     * ⚠️ **大目录不能慢。** 原来每个符号链接都 `stat` 一次解引用（看它指不指向目录），
     * 一次 stat 一个来回 —— `/tmp` 五千多项、几百个链接，列一次要几分钟，而且整个通道被锁住，
     * 后面的所有操作都排队（用户看到的是「点面包屑没反应」，#213）。
     * 现在：只解引用前 [MAX_LINK_STAT] 个链接，其余当文件、标 isLink，点开时再判（[isDir]）；
     * 超过 [MAX_ENTRIES] 项截断，界面提示。
     */
    suspend fun list(path: String): List<Entry> = withContext(Dispatchers.IO) {
        lock.withLock {
            // ⚠️ 用 selector 边收边数，到上限就 BREAK —— 五千项的目录要几十个来回，
            // `ls(path)` 一次性收全等于把这些来回全走完；停在 3000 项能省掉一半以上。
            val raw = ArrayList<ChannelSftp.LsEntry>(512)
            ch.ls(path, ChannelSftp.LsEntrySelector { e ->
                if (e.filename != "." && e.filename != "..") raw += e
                if (raw.size >= MAX_ENTRIES) ChannelSftp.LsEntrySelector.BREAK else ChannelSftp.LsEntrySelector.CONTINUE
            })
            var statLeft = MAX_LINK_STAT
            raw.asSequence()
                .filter { it.filename != "." && it.filename != ".." }
                .map { e ->
                    val a = e.attrs
                    val dir = if (a.isLink) {
                        if (statLeft > 0) { statLeft--; runCatching { ch.stat("$path/${e.filename}").isDir }.getOrDefault(false) }
                        else false
                    } else a.isDir
                    Entry(e.filename, dir, a.size, a.mTime, a.isLink)
                }
                .sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
                .take(MAX_ENTRIES)
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
    /**
     * 写一个文件。[progress] 每传一块回调一次 `(已传, 总数)`，**返回 false 就中止**（jsch 的
     * `SftpProgressMonitor.count` 语义）—— 附件条上那个 ✕ 靠它把正在传的那一个停下来。
     * ⚠️ 中止是抛 SftpException 出来的（jsch 没有干净的取消），调用方按 cancelled 标记区分。
     */
    suspend fun write(path: String, bytes: ByteArray, progress: ((Long, Long) -> Boolean)? = null) =
        write(path, java.io.ByteArrayInputStream(bytes), bytes.size.toLong(), progress)

    /**
     * **流式**写：从 [input] 边读边传，**不把整个文件读进内存**。
     *
     * ⚠️⚠️ 这是「上传视频经常失败」的根（用户 2026-09-05）：原来所有上传都先 `readBytes()`
     * 把整个文件读成一个 ByteArray —— 一段 200MB 的视频在手机上就是一次 200MB 的分配
     * （App 堆一共才 256~512MB，读的过程还会再复制一遍），要么直接 OOM，要么被系统杀掉，
     * 表现就是「传不上去」而且没有明白的原因。jsch 的 `put` 本来就吃 InputStream，
     * 中间那份 ByteArray 纯属多余。图片小、看不出来；视频一上就炸。
     *
     * @param total 文件总字节数，只用来算进度；不知道就给 -1（进度回调里 total 为 -1）。
     */
    suspend fun write(
        path: String, input: java.io.InputStream, total: Long,
        progress: ((Long, Long) -> Boolean)? = null,
        /**
         * **续传**：服务器上已经有半个文件时，从它的大小接着传。jsch 的 RESUME 模式会自己
         * 读远端大小、在 [input] 上 `skip` 掉那么多字节 —— 所以调用方只管**重新开一个流**传进来。
         * ⚠️ 视频「要点击重试多次」的根就在这：原来每次失败都 `rm` 掉半个文件从头来，
         * 手机网络一抖就白传 —— 大文件在抖动的链路上永远传不完（用户 2026-09-05）。
         */
        resume: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        lock.withLock {
            val mode = if (resume) ChannelSftp.RESUME else ChannelSftp.OVERWRITE
            input.use { inp ->
                if (progress == null) ch.put(inp, path, mode)
                else {
                    // RESUME 时 jsch 会从「已传到的字节」起报 count；这里的 done 也从远端大小起算，进度条才不倒退
                    var done = if (resume) runCatching { ch.stat(path).size }.getOrDefault(0L) else 0L
                    val mon = object : com.jcraft.jsch.SftpProgressMonitor {
                        override fun init(op: Int, src: String?, dest: String?, max: Long) {}
                        override fun count(n: Long): Boolean { done += n; return progress(done, total) }
                        override fun end() {}
                    }
                    ch.put(inp, path, mon, mode)
                }
            }
        }
    }

    /** 删一个文件（取消上传时把传了一半的那个收掉）。不存在也不报错。 */
    suspend fun rm(path: String) = withContext(Dispatchers.IO) {
        lock.withLock { runCatching { ch.rm(path) } }
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
        /** 列目录时最多解引用几个符号链接（每个一趟往返） */
        const val MAX_LINK_STAT = 24
        /** 一个目录最多列几项 —— 手机上翻不完，列全了只会卡 */
        const val MAX_ENTRIES = 3000

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
