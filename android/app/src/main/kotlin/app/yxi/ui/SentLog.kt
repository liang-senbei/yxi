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

    /** 没过期的原始行。文件不存在 / 读坏了都当空表 —— 历史丢了不该把发送流程带崩。 */
    private fun alive(f: File): List<String> {
        val cut = System.currentTimeMillis() - KEEP_MS
        return runCatching { f.readLines() }.getOrDefault(emptyList())
            .filter { it.isNotBlank() && runCatching { JSONObject(it).optLong("t") }.getOrDefault(0L) >= cut }
    }

    /**
     * 记一条。[raw] 是**真正送出去的那串**（附件标记还在前面），这里自己摘干净再存。
     *
     * ⚠️ 顺手把过期的清掉再整份写回 —— 三天的量很小（几百行顶天），
     * 与其起个定时任务，不如在有人用的时候顺路收拾。
     */
    fun add(ctx: Context, hostId: String, session: String, raw: String) {
        val (refs, body) = app.yxi.agent.Attachments.parseRefs(raw)
        val text = body.trim()
        if (text.isBlank() && refs.isEmpty()) return
        val f = file(ctx, hostId, session)
        val line = JSONObject().put("t", System.currentTimeMillis()).put("a", refs.size).put("x", text).toString()
        runCatching { f.writeText((alive(f) + line).joinToString("\n")) }
    }

    /** 读出来给面板用：**新的在上**（翻历史几乎总是从最近的往前找），过期的顺手清掉。 */
    fun read(ctx: Context, hostId: String, session: String): List<Entry> {
        val f = file(ctx, hostId, session)
        val kept = alive(f)
        // 有清掉东西才回写，别每次打开面板都写一遍盘
        if (kept.size != runCatching { f.readLines().count { it.isNotBlank() } }.getOrDefault(kept.size)) {
            runCatching { if (kept.isEmpty()) f.delete() else f.writeText(kept.joinToString("\n")) }
        }
        return kept.mapNotNull { l ->
            runCatching { JSONObject(l) }.getOrNull()?.let { Entry(it.optLong("t"), it.optInt("a"), it.optString("x")) }
        }.asReversed()
    }
}
