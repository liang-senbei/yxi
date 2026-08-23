package app.yxi.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 转录里的一条内容，已经归一成「界面能直接渲染的东西」。
 *
 * **分层是抄 Lucarne 的 `agent-sessions`（PRD 附录 B.4）**：
 * 原始的 agent 专属结构（Claude 的 `message.content[]` 块）与这里的共享语义层分开。
 * 好处是将来加 Codex 只用写一个新的解析器，渲染层一行不动。
 */
sealed interface ChatItem {
    val key: String

    data class UserText(override val key: String, val text: String) : ChatItem
    data class AssistantText(override val key: String, val markdown: String) : ChatItem
    /** 默认折叠——实测一个会话里 128 条，全展开会把内容淹掉 */
    data class Thinking(override val key: String, val text: String) : ChatItem
    data class ToolCall(
        override val key: String,
        val name: String,
        val input: JSONObject,
        /** 对应的结果，来自后面某条 user 消息里的 `tool_result` */
        var result: String? = null,
        var isError: Boolean = false,
        /**
         * Claude Code 自己存的**结构化结果**（转录行顶层的 `toolUseResult`，不是 API 内容）。
         * 富渲染基本都靠它：Edit 的 `structuredPatch`、Bash 分开的 stdout/stderr、
         * Read 的 `file.numLines`、AskUserQuestion 的 `answers`、ExitPlanMode 的最终 `plan`。
         *
         * ⚠️ **它可能是字符串（用户拒绝时会退化）或 null（老转录、子 agent 转录里很常见）。**
         * 这里只在它确实是对象时才存，别处就不用再判类型了。
         */
        var meta: JSONObject? = null,
    ) : ChatItem
    /**
     * **已提交、还排着队**的用户输入 —— 你在它忙的时候打的字。
     *
     * ⚠️ 这个必须显示出来。转录里它的类型不是 `user` 而是 `queue-operation`，
     * 老解析器当成不认识**静默丢掉**了，表现是「我连打了好几条，App 里一条都没有」，
     * 用户会以为没发出去、然后重复发。见 TROUBLESHOOTING #72。
     */
    data class Queued(override val key: String, val text: String) : ChatItem
    /** 解析不出来的东西。⚠️ 这是**兼容兜底不是正常终点**——见类注释 */
    data class Unknown(override val key: String, val raw: String) : ChatItem
}

/**
 * Claude Code 转录（JSONL）的解析器。
 *
 * ⚠️ **`Unknown` 的纪律**（抄 Lucarne 的规矩，PRD 附录 B.4 第 2 条）：
 * 它是兼容兜底，不是正常归宿。真实样本里一旦出现 `Unknown`，
 * **应当在同一次改动里把它提升成强类型**，而不是让它烂在那。
 * Claude Code 会不断加新的 content block 类型（`thinking` 就是后加的）。
 */
object Transcript {

    /** 解析若干行 JSONL，返回渲染用的条目。工具结果会就地合并进对应的 [ChatItem.ToolCall]。 */
    fun parse(lines: Sequence<String>): List<ChatItem> {
        val out = ArrayList<ChatItem>()
        val calls = HashMap<String, ChatItem.ToolCall>()   // tool_use_id → 卡片
        val queued = LinkedHashSet<String>()               // 还排着队的输入，出队就删

        lines.forEach { line ->
            if (line.isBlank()) return@forEach
            val d = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            // 侧链（子 agent 的内部对话）不进主时间线，否则会把主线淹掉
            if (d.optBoolean("isSidechain", false)) return@forEach

            val type = d.optString("type")

            // ⚠️ **排队的输入没有 message 字段**，得在下面那个 return 之前接住。
            //   {"type":"queue-operation","operation":"enqueue","content":"…"}  进队
            //   {"type":"queue-operation","operation":"remove", "content":"…"}  出队
            //   {"type":"attachment","attachment":{"type":"queued_command","prompt":"…"}}  真正被处理
            // 处理之后才算进了对话，所以那时候才当普通用户消息发出去。
            if (type == "queue-operation") {
                val c = d.optString("content")
                if (c.isNotBlank()) when (d.optString("operation")) {
                    "enqueue" -> queued += c
                    "remove" -> queued.remove(c)
                }
                return@forEach
            }
            if (type == "attachment") {
                val a = d.optJSONObject("attachment")
                if (a?.optString("type") == "queued_command") {
                    val p = a.optString("prompt")
                    // origin.kind 不是 human 的是系统注入的，不该显示成用户说的话
                    if (p.isNotBlank() && a.optJSONObject("origin")?.optString("kind") == "human") {
                        out += ChatItem.UserText(uuidOf(d, line), p)
                    }
                }
                return@forEach
            }

            val msg = d.optJSONObject("message") ?: run {
                // 非消息行（mode / file-history-snapshot / ai-title 之类）静默跳过
                return@forEach
            }
            val uuid = d.optString("uuid", d.optString("requestId", line.hashCode().toString()))

            when (type) {
                "user" -> parseUser(msg, d.optJSONObject("toolUseResult"), uuid, calls, out)
                "assistant" -> parseAssistant(msg, uuid, calls, out)
                else -> Unit
            }
        }
        // ⚠️ **出队的判据是「这句话有没有真的作为用户消息出现过」，不是 remove。**
        // 实测一个真实会话：35 个 enqueue 只有 29 个 remove，剩下 13 条全都后来
        // 以普通 `user` 消息出现了 —— 它们**早就被处理完了**，只是 Claude Code
        // 走的不是 remove 那条路径。只认 remove 的话，那 13 条会永远挂在
        // 「排队中」，而对应的命令几小时前就跑完了。见 TROUBLESHOOTING #76。
        val said = out.asSequence()
            .filterIsInstance<ChatItem.UserText>()
            .mapTo(HashSet()) { it.text.trim() }
        queued.asSequence()
            .filterNot { it.trim() in said }
            .forEachIndexed { i, t -> out += ChatItem.Queued("queued-$i-" + t.hashCode(), t) }
        return out
    }

    private fun uuidOf(d: JSONObject, line: String): String =
        d.optString("uuid", d.optString("requestId", line.hashCode().toString()))

    private fun parseUser(
        msg: JSONObject, meta: JSONObject?, uuid: String,
        calls: MutableMap<String, ChatItem.ToolCall>, out: MutableList<ChatItem>,
    ) {
        when (val c = msg.opt("content")) {
            is String -> if (c.isNotBlank()) out += ChatItem.UserText(uuid, c)
            is JSONArray -> for (i in 0 until c.length()) {
                val b = c.optJSONObject(i) ?: continue
                when (b.optString("type")) {
                    "text" -> b.optString("text").takeIf { it.isNotBlank() }
                        ?.let { out += ChatItem.UserText("$uuid-$i", it) }
                    // 工具结果不单独成条，合并回它对应的工具卡片
                    "tool_result" -> {
                        val id = b.optString("tool_use_id")
                        calls[id]?.apply {
                            result = flatten(b.opt("content"))
                            isError = b.optBoolean("is_error", false)
                            // optJSONObject 在它是字符串/null 时自然返回 null —— 正合适
                            this.meta = meta
                        }
                    }
                }
            }
        }
    }

    private fun parseAssistant(
        msg: JSONObject, uuid: String,
        calls: MutableMap<String, ChatItem.ToolCall>, out: MutableList<ChatItem>,
    ) {
        val c = msg.optJSONArray("content") ?: return
        for (i in 0 until c.length()) {
            val b = c.optJSONObject(i) ?: continue
            val key = "$uuid-$i"
            when (b.optString("type")) {
                "text" -> b.optString("text").takeIf { it.isNotBlank() }
                    ?.let { out += ChatItem.AssistantText(key, it) }
                "thinking" -> b.optString("thinking").takeIf { it.isNotBlank() }
                    ?.let { out += ChatItem.Thinking(key, it) }
                "tool_use" -> {
                    val call = ChatItem.ToolCall(
                        key, b.optString("name"), b.optJSONObject("input") ?: JSONObject()
                    )
                    calls[b.optString("id")] = call
                    out += call
                }
                else -> out += ChatItem.Unknown(key, b.optString("type"))
            }
        }
    }

    /** `tool_result.content` 可能是字符串，也可能是块数组。都压成一段文本。 */
    private fun flatten(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> (0 until content.length()).mapNotNull { i ->
            content.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
        }.joinToString("\n")
        else -> content?.toString().orEmpty()
    }

    /**
     * cwd → Claude Code 的项目目录名：**凡不是 ASCII 字母或数字的字符，一律换成横杠**。
     *
     * ⚠️ 一开始只换了斜杠，中文路径就找不到转录了 —— 实测
     * `/opt/workspace/日常对话` 对应的目录是 `-opt-workspace-----`（四个汉字四个横杠），
     * 不是 `-opt-workspace-日常对话`。点号、空格同理。
     */
    fun projectDirOf(cwd: String): String =
        cwd.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') it else '-' }.joinToString("")
}
