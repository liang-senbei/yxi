package app.yxi.agent

import app.yxi.ssh.SshSession
import org.json.JSONObject

/**
 * **工单中心** —— 用 App 的人随手记下「哪里不好用」，落在**连着的那台服务器**上，
 * 我们（开发）直接在服务器上查阅：`cat ~/.yxi/tickets.jsonl`。
 *
 * ⚠️ **为什么存服务器而不是手机本地**：存本地只有本人看得见，等于没提。
 * ⚠️ **为什么不建公网接口**：Yxi 是自托管、无云后端的；开个公网 POST 要防刷、要考虑隐私，
 * 而且和「不依赖任何第三方」这条相冲。走已有的 SSH 通道零新基建。
 * ⚠️ **代价**：APK 分享给别人后，他们的工单落在**他们自己的服务器**上，我们看不到。
 * 真要收集外部反馈，得另外在下载机上开一个收集端点 —— 那是另一件事。
 *
 * 一行一条 JSON（JSONL），只追加不改写：坏了也只坏一行。
 */
object Tickets {
    private const val FILE = "\$HOME/.yxi/tickets.jsonl"

    data class Ticket(
        val at: Long,
        val text: String,
        /** 提的时候 App 是哪个版本 —— 没这个的话回头根本对不上是哪版的毛病 */
        val version: String = "",
        val device: String = "",
        val session: String = "",
    )

    /**
     * 把一行 JSON 安全地塞进单引号 shell 字符串。
     * ⚠️ 用户会在工单里写各种字符（引号、`$`、反引号、换行）——
     * 不处理就要么写坏文件，要么被当命令执行。单引号里只有 `'` 需要转义。
     */
    internal fun shellSingleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 提一条。@return null = 成功，否则是失败原因。 */
    suspend fun add(ssh: SshSession?, t: Ticket): String? {
        val s = ssh ?: return "没连上"
        val line = JSONObject()
            .put("at", t.at)
            .put("text", t.text.take(2000))
            .put("version", t.version)
            .put("device", t.device)
            .put("session", t.session)
            .put("status", "open")
            .toString()
        // ⚠️ printf '%s\n' 而不是 echo —— echo 对反斜杠的处理各家 shell 不一样
        s.exec("mkdir -p \$HOME/.yxi && printf '%s\\n' ${shellSingleQuote(line)} >> $FILE")
        return if (s.isConnected) null else "没发出去"
    }

    /** 读回来，最新的在前。读不到就空列表。 */
    suspend fun load(ssh: SshSession?): List<Ticket> {
        val raw = ssh?.exec("tail -n 200 $FILE 2>/dev/null").orEmpty()
        return parse(raw).asReversed()
    }

    internal fun parse(raw: String): List<Ticket> = raw.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { ln ->
            runCatching {
                val o = JSONObject(ln)
                Ticket(
                    at = o.optLong("at"),
                    text = o.optString("text"),
                    version = o.optString("version"),
                    device = o.optString("device"),
                    session = o.optString("session"),
                )
            }.getOrNull()
        }
        .filter { it.text.isNotBlank() }
        .toList()
}
