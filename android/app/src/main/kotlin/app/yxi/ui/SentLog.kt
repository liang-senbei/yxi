package app.yxi.ui

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * **发过的话** —— 按会话落盘，留三天（老板 2026-09-07：「每个发的话都会记三天，三天后就清理」）。
 *
 * ⚠️ **为什么不接着从转录里滤。** 第一版是从解析好的转录里挑自己发的那几条 —— 看着"不另存一份"很省，
 * 但转录**够不到多远**：会话一长，前面的就被截掉了；`/clear` 之后更是一条不剩。
 * 而用户长按翻历史，要找的恰恰是**前面那条**。所以这份得自己存，跟转录是两回事。
 *
 * ⚠️ **存的是自己发出去的话，不是对话记录**：只记时间、正文、带了几个附件。
 * 附件本身不存（路径在服务器上，本地那份的授权活不过进程 —— 同 [Drafts] 顶部那条）。
 *
 * 一行一条 JSON（不是自己拼分隔符）：正文里带换行 / 制表符 / 引号都是常事，
 * 手写转义迟早漏一种，`JSONObject.toString()` 天然是单行且转义干净。
 */
object SentLog {

    /** 老板定的：三天。到期的在**写入和打开面板**两个时机顺手清掉，不另起后台任务。 */
    private const val KEEP_MS = 3L * 24 * 3600 * 1000

    /** [at] 是发出去那一刻的毫秒时间戳；[attachments] 是这条带了几个附件 */
    data class Entry(val at: Long, val attachments: Int, val text: String)

    /**
     * 每个「主机 + 会话」一个文件。
     *
     * ⚠️ 文件名用 Base64(URL_SAFE) 编，**不是把会话名直接拼进去**：会话名里出现 `/`、空格、中文、
     * emoji 都很正常，直接当文件名要么建不出来，要么写到别的目录去。也别用 hashCode —— 撞了就是
     * 两个会话的历史串到一起，而那种 bug 在现场根本看不出来。
     */
    private fun file(ctx: Context, hostId: String, session: String): File {
        val dir = File(ctx.filesDir, "sent").apply { mkdirs() }
        val name = android.util.Base64.encodeToString(
            "$hostId:$session".toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        return File(dir, "$name.jsonl")
    }

    /** 没过期的原始行 + 文件里原本有多少条（省一次重读，见下面 [read] 里为什么要这个数） */
    private fun alive(f: File): Pair<List<String>, Int> {
        val cut = System.currentTimeMillis() - KEEP_MS
        val all = runCatching { f.readLines() }.getOrDefault(emptyList()).filter { it.isNotBlank() }
        return all.filter { runCatching { JSONObject(it).optLong("t") }.getOrDefault(0L) >= cut } to all.size
    }

    /**
     * 记一条。[raw] 是**真正送出去的那串**（附件标记还在前面），这里自己摘干净再存。
     *
     * ⚠️⚠️ **只追加，绝不「读全表 → 过滤 → 整份写回」。** 第一版是后者，两个要命的地方：
     *   ① `writeText` 是**先清空再写**。写到一半进程被杀（国产 ROM 后台杀得很凶 —— [app.yxi.agent.TranscriptCache]
     *      顶上那段就是为这个存在的），整个会话的历史就没了。而**每发一条都开一次这个窗口**。
     *   ② 读失败时 `alive` 返回空表，紧接着就把「只有新这一条」写回去 ——
     *      一次瞬时的读错误变成**永久全丢**。
     *   追加只有一次 `O_APPEND` 写：崩了最多丢最后一条，坏行 [alive] 本来就会跳过。
     *   过期清理挪到 [read] 里做。
     */
    fun add(ctx: Context, hostId: String, session: String, raw: String) {
        val (refs, body) = app.yxi.agent.Attachments.parseRefs(raw)
        val text = body.trim()
        if (text.isBlank() && refs.isEmpty()) return
        val line = JSONObject().put("t", System.currentTimeMillis()).put("a", refs.size).put("x", text).toString()
        runCatching {
            val f = file(ctx, hostId, session)
            // ⚠️ 结尾那个 \n 不能省：不写的话下一条会**接在上一行屁股后面**，两条挤成一行、双双解析失败。
            // ⚠️ 而**上一版是整份回写、末尾不带换行**的 —— 升级上来的用户，文件里最后一行就是没换行的。
            //    所以追加前先看一眼末字节：粘着就先补一个换行。（只读 1 个字节，不整份读。）
            val glued = f.length() > 0 && runCatching {
                java.io.RandomAccessFile(f, "r").use { r -> r.seek(f.length() - 1); r.read() != '\n'.code }
            }.getOrDefault(false)
            f.appendText((if (glued) "\n" else "") + line + "\n")
        }
    }

    /** 读出来给面板用：**新的在上**（翻历史几乎总是从最近的往前找），过期的顺手清掉。 */
    fun read(ctx: Context, hostId: String, session: String): List<Entry> {
        val f = file(ctx, hostId, session)
        val (kept, had) = alive(f)
        // 有清掉东西才回写，别每次打开面板都写一遍盘
        if (kept.size != had) compact(f, kept)
        return kept.mapNotNull { l ->
            runCatching { JSONObject(l) }.getOrNull()?.let { Entry(it.optLong("t"), it.optInt("a"), it.optString("x")) }
        }.asReversed()
    }

    /**
     * 把没过期的那些整份换上去。
     *
     * ⚠️ **先写临时文件再改名**，不直接往原文件上写：`renameTo` 在同一个目录里是原子的，
     * 中途被杀最坏是留个 `.tmp`，原文件一个字没动。（同 [app.yxi.yunxi.Memos] 里那条。）
     */
    private fun compact(f: File, kept: List<String>) {
        runCatching {
            if (kept.isEmpty()) { f.delete(); return }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(kept.joinToString("\n") + "\n")
            if (!tmp.renameTo(f)) tmp.delete()
        }
    }

    /**
     * 把**所有**会话里过期的清掉。开 App 时扫一遍。
     *
     * ⚠️ 光在 [add] / [read] 里清是不够的 —— 那只清「你正在用的那个会话」。
     * 一个不再打开的会话，它的明文会**永远躺在那儿**；主机被删掉之后那个文件更是没人再认得
     * （文件名是 `hostId:会话名` 编出来的）。那样「记三天」就只是界面上说说而已。
     * 这一趟扫完，无论会话还在不在、主机还在不在，三天就是三天。
     *
     * @return 删掉了几个文件
     */
    fun sweep(ctx: Context): Int {
        val dir = File(ctx.filesDir, "sent")
        val fs = dir.listFiles() ?: return 0
        var gone = 0
        for (f in fs) {
            if (f.name.endsWith(".tmp")) { runCatching { f.delete() }; continue }   // 上次崩在半道上的
            val (kept, had) = alive(f)
            if (kept.isEmpty()) { if (runCatching { f.delete() }.getOrDefault(false)) gone++ }
            else if (kept.size != had) compact(f, kept)
        }
        return gone
    }
}
