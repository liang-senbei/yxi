package app.yxi.agent

import app.yxi.ssh.SshSession
import org.json.JSONArray

/**
 * **在线实验** —— 让实验室的内容从**服务器**读，而不是编进 app 里。
 *
 * 病根：内建的 demo 是编译进 APK 的，我想给用户看个新设计就得重新发版、用户还得更新。
 * 解法：读连的那台机器 `~/.yxi/lab/` 下的 `manifest.json` + 素材。我往那儿丢东西
 * （图片 / GIF / 网页），用户点一下刷新就出来 —— **不用更新 App**。
 *
 * manifest.json 格式（一个数组）：
 * ```
 * [ {"id":"x1","title":"新加载态","type":"html","file":"loader.html","desc":"一句话"},
 *   {"id":"x2","title":"某动效","type":"gif","file":"demo.gif","desc":"…"} ]
 * ```
 * type：`html`（WebView 渲染，JS 开）/ `gif` / `image` / `note`（只看 desc 文字）。
 *
 * ⚠️ 图 / GIF 用 **base64 经 exec 传回来**，不走 SFTP —— 省得处理 `~` / `$HOME` 的路径展开
 * （exec 是 shell，`$DIR` 自己会展开；SFTP 得先 realpath）。大文件截断，别把 GIF 做太大。
 */
object LabRemote {
    private const val DIR = "\$HOME/.yxi/lab"

    class Item(
        val id: String, val title: String, val type: String, val file: String, val desc: String,
        /** 谁生成的（yxi-lab add 的第 5 个参数），如 "Gemini (nanobanana)"。空 = 没标 */
        val by: String = "",
        /** 生成时间，unix 秒。0 = 老数据没记 */
        val at: Long = 0L,
    ) {
        /** 归到哪个栏目（按 type）。key 用于分组，name 是显示名。 */
        val catKey: String get() = when (type) {
            "image" -> "image"; "svg" -> "svg"; "gif" -> "gif"; "video" -> "video"; "html" -> "html"; else -> "note"
        }
    }

    /** 读 manifest。读不到 / 格式不对就空列表（= 界面显示「还没有在线实验」）。 */
    suspend fun load(ssh: SshSession?): List<Item> {
        val raw = ssh?.exec("cat $DIR/manifest.json 2>/dev/null").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Item(
                    o.optString("id", "remote-$i"), o.optString("title", "?"),
                    o.optString("type", "note"), o.optString("file"), o.optString("desc"),
                    by = o.optString("by"), at = o.optLong("at", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    // 文件名只留安全字符（防注入；反正是我自己写的 manifest）
    private fun safe(f: String) = f.filter { it.isLetterOrDigit() || it in "._-/" }

    /** 删掉若干条 —— 直接调服务器上的 `yxi-lab rm`（那边会连素材文件一起删、改 manifest）。 */
    suspend fun delete(ssh: SshSession?, ids: List<String>) {
        ssh ?: return
        val safe = ids.map { id -> id.filter { it.isLetterOrDigit() || it in ".-_" } }.filter { it.isNotEmpty() }
        if (safe.isEmpty()) return
        ssh.exec("for i in ${safe.joinToString(" ")}; do \$HOME/.local/bin/yxi-lab rm \"\$i\" >/dev/null 2>&1; done")
    }

    /** 取一个文本文件（html / note）。 */
    suspend fun text(ssh: SshSession?, file: String): String =
        ssh?.exec("cat $DIR/${safe(file)} 2>/dev/null").orEmpty()

    /**
     * 把一个素材**原文件流式**下到本地（存到相册/下载用）。走 SFTP —— base64 经 exec 会把大文件（视频）截断。
     * 相对路径 `.yxi/lab/xxx` 由 jsch 自己解析到远端 home，不用 realpath。
     * @return 实际下到的字节数；<=0 = 没下成。onFrac 给 0..1 的进度（取不到大小就不回调）。
     */
    suspend fun download(ssh: SshSession?, file: String, into: java.io.File, onFrac: (Float) -> Unit = {}): Long {
        val s = ssh ?: return -1
        val path = ".yxi/lab/${safe(file)}"
        val sftp = s.openSftp()
        return try {
            val total = runCatching { sftp.size(path) }.getOrDefault(-1L)
            sftp.download(path, into) { n -> if (total > 0) onFrac((n.toFloat() / total).coerceIn(0f, 1f)) }
        } finally { runCatching { sftp.close() } }
    }

    /** 取一个二进制文件（图 / GIF）的字节。base64 → 解码。 */
    suspend fun bytes(ssh: SshSession?, file: String): ByteArray? {
        // ⚠️ `-w0` 是 GNU 的，BSD/macOS 的 base64 不认（直接报错，一个字节都不吐）。
        // 先试 GNU 写法，失败退到 BSD 写法自己去掉换行。不这么写的话，
        // 服务器只要是 macOS/BSD，实验室的图就**全部空白且不报错**。
        // ⚠️⚠️ **不能加单引号**：DIR 里是 `$HOME`，单引号里它不展开，
        // 服务器上找的就成了一个字面量叫 `$HOME` 的目录 —— 图和 GIF 全部
        // 拿不到字节**而且不报错**。文件名已经过 safe() 只剩安全字符。
        val f = "$DIR/${safe(file)}"
        val b64 = ssh?.exec("base64 -w0 $f 2>/dev/null || base64 $f 2>/dev/null | tr -d '\n'")
            .orEmpty().trim()
        if (b64.isBlank()) return null
        return runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull()
    }
}
