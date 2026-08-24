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
     * **不是用户说的话**，但在转录里也是 `user` 类型的东西：队友/子 agent 的消息、
     * 任务通知、系统提醒、斜杠命令的输出。
     *
     * ⚠️ 不单独认出来的话，它们会**原样当成用户气泡渲染** ——
     * 屏幕上就是一坨 `<agent-message from="ios-parsers">` 的原始 XML，
     * 而且长着「你说的话」的样子。实测一个真实会话里有 33 处这种东西。
     * 用户的原话：「为什么对话里面在显示的是这样子的图片啊」。
     */
    data class Injected(
        override val key: String,
        /** 给人看的类别，如「队友消息」「任务通知」 */
        val kind: String,
        /** 谁发的，认得出就填（`from="xxx"`） */
        val from: String?,
        val text: String,
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

    /**
     * 解析若干行 JSONL，返回渲染用的条目。
     *
     * ⚠️ 这是 [Incremental] 的一次性包装 —— **现有的全部测试都走这条路**，
     * 所以它们同时也在验增量引擎。
     */
    fun parse(lines: Sequence<String>): List<ChatItem> =
        Incremental().apply { add(lines) }.snapshot()

    /**
     * **可续解析器：只吃新来的行。**
     *
     * ⚠️ 为什么必须增量：老做法是每 300ms 把整个缓冲从头解析一遍 ——
     * 实测一个真实会话 `tail -n 800` 是 **4.17 MB**，也就是**每秒重嚼三次 4 MB**。
     * 会话越长越慢，而终端那条链一个字节都不用解析。见 TROUBLESHOOTING #86。
     *
     * ⚠️ 不能简单「解析新行然后 append」，有两处**跨行状态**：
     *   · `tool_result` 要回填**前面**那张工具卡
     *   · 排队的输入要等它「作为用户消息出现过」才算出队（#76）
     * 所以状态留在这个对象里。
     *
     * ⚠️⚠️ **回填时必须换成新实例，不能原地改。** `ChatItem.ToolCall` 里那几个
     * 是 `var`，就地改 Compose **看不见** —— 列表里还是同一个对象，
     * `key` 也没变，那张卡会永远停在「进行中」。所以存的是**下标**，回填时整条替换。
     */
    class Incremental {
        private val out = ArrayList<ChatItem>()
        private val calls = HashMap<String, Int>()          // tool_use_id → out 里的下标
        private val queued = LinkedHashSet<String>()        // 还排着队的输入，出队就删
        private val said = HashSet<String>()                // 已经作为用户消息出现过的原文

        fun add(lines: Sequence<String>) = parseInto(lines, out, calls, queued, said)

        /** 当前快照。排队的挂在最后 —— 它们还没进对话，位置就在「此刻」。 */
        fun snapshot(): List<ChatItem> = out + queued.asSequence()
            .filterNot { it.trim() in said }
            .mapIndexed { i, t ->
                // ⚠️ **排着队的也可能不是用户说的话。** 队友/子 agent 的消息是通过
                // 队列注入的，落在 `queue-operation` 里 —— 只在 parseUser 里认注入
                // 会漏掉它们，屏幕上就是一坨 `<agent-message from="…">` 顶着
                // 「排队中·你说的话」的样子。这条是真机上看出来的。
                val inj = injectedOf(t)
                if (inj == null) ChatItem.Queued("queued-$i-" + t.hashCode(), t)
                else ChatItem.Injected("queued-$i-" + t.hashCode(), inj.first + " · 排队中", inj.second, t)
            }

        /** 已经吃进去多少行 —— 上层拿它决定从哪儿接着喂。 */
        var consumed: Int = 0
            internal set
    }

    private fun parseInto(
        lines: Sequence<String>,
        out: ArrayList<ChatItem>,
        calls: HashMap<String, Int>,
        queued: LinkedHashSet<String>,
        said: HashSet<String>,
    ) {
        lines.forEach { line ->
            if (line.isBlank()) return@forEach
            val d = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            // 侧链（子 agent 的内部对话）不进主时间线，否则会把主线淹掉
            if (d.optBoolean("isSidechain", false)) return@forEach

            val type = d.optString("type")

            // ⚠️ **排队的输入没有 message 字段**，得在下面那个 return 之前接住。
            //   {"type":"queue-operation","operation":"enqueue","content":"…"}  进队
            //   {"type":"queue-operation","operation":"remove", "content":"…"}  出队
            //   {"type":"queue-operation","operation":"popAll", "content":"…"}  被收回输入框
            //
            // ⚠️ **`popAll` 是实测才发现的第四种。** 在 TUI 里按 `Up`（脚注写着
            // "Press up to edit queued messages"）会把**排队的全部**收回输入框，
            // 每收一条写一条 `popAll`。漏掉它的后果：用户在手机上撤回之后，
            // 那几条「排队中」气泡**再也不会消失** —— 转录里没有 remove，
            // 而它们永远不会被处理，所以 `said` 那条路也兜不住。
            //   {"type":"attachment","attachment":{"type":"queued_command","prompt":"…"}}  真正被处理
            // 处理之后才算进了对话，所以那时候才当普通用户消息发出去。
            if (type == "queue-operation") {
                val c = d.optString("content")
                if (c.isNotBlank()) when (d.optString("operation")) {
                    "enqueue" -> queued += c
                    "remove", "popAll" -> queued.remove(c)
                }
                return@forEach
            }
            if (type == "attachment") {
                val a = d.optJSONObject("attachment")
                if (a?.optString("type") == "queued_command") {
                    val p = a.optString("prompt")
                    // origin.kind 不是 human 的是系统注入的，不该显示成用户说的话
                    if (p.isNotBlank() && a.optJSONObject("origin")?.optString("kind") == "human") {
                        val key = uuidOf(d, line)
                        val inj = injectedOf(p)
                        out += if (inj == null) ChatItem.UserText(key, p)
                        else ChatItem.Injected(key, inj.first, inj.second, p)
                        // ⚠️ 不管是不是注入内容都要记 —— 出队判据看的是「这句话出现过没有」（#76）
                        said += p.trim()
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
                "user" -> parseUser(msg, d.optJSONObject("toolUseResult"), uuid, calls, out, said)
                "assistant" -> parseAssistant(msg, uuid, calls, out)
                else -> Unit
            }
        }
    }

    private fun uuidOf(d: JSONObject, line: String): String =
        d.optString("uuid", d.optString("requestId", line.hashCode().toString()))

    /**
     * 注入内容的标签 → 给人看的类别。
     *
     * ⚠️ 这些标签**不一定在开头** —— 前面常常还有一段引子
     * （「Another Claude session sent a message:」之类），所以是**搜**不是 `startsWith`。
     */
    private val INJECTED = listOf(
        "teammate-message" to "队友消息",
        "agent-message" to "子 agent 消息",
        "cross-session-message" to "跨会话消息",
        "task-notification" to "任务通知",
        "system-reminder" to "系统提醒",
        "local-command-caveat" to "系统提醒",
        "local-command-stdout" to "命令输出",
        "command-name" to "斜杠命令",
    )

    /**
     * 谁发的。⚠️ **属性名不止一个**：子 agent 用 `from="…"`，
     * 队友消息用 `teammate_id="…"` —— 只认 `from` 的话队友那栏永远是空的
     * （这条是测试抓出来的，我原本只写了 from）。
     */
    private val FROM = Regex("""(?:from|teammate_id|agent_id)="([^"]+)"""")

    /** 认出来就返回（类别, 谁发的），否则 null。 */
    private fun injectedOf(text: String): Pair<String, String?>? {
        for ((tag, label) in INJECTED) {
            val i = text.indexOf("<$tag")
            if (i < 0) continue
            val from = FROM.find(text, i)?.groupValues?.get(1)
            return label to from
        }
        return null
    }

    private fun parseUser(
        msg: JSONObject, meta: JSONObject?, uuid: String,
        calls: MutableMap<String, Int>, out: MutableList<ChatItem>,
        said: MutableSet<String>,
    ) {
        fun said(key: String, t: String) {
            val inj = injectedOf(t)
            if (inj == null) {
                out += ChatItem.UserText(key, t)
                said += t.trim()     // 出队判据要用（#76）
            } else {
                out += ChatItem.Injected(key, inj.first, inj.second, t)
            }
        }
        when (val c = msg.opt("content")) {
            is String -> if (c.isNotBlank()) said(uuid, c)
            is JSONArray -> for (i in 0 until c.length()) {
                val b = c.optJSONObject(i) ?: continue
                when (b.optString("type")) {
                    "text" -> b.optString("text").takeIf { it.isNotBlank() }
                        ?.let { said("$uuid-$i", it) }
                    // 工具结果不单独成条，合并回它对应的工具卡片
                    "tool_result" -> {
                        // ⚠️ **整条替换，不能就地改。** ToolCall 里那几个是 var，
                        // 就地改的话列表里还是同一个对象、key 也没变，
                        // **Compose 看不见** —— 那张卡会永远停在「进行中」。
                        val idx = calls[b.optString("tool_use_id")]
                        val card = idx?.let { out.getOrNull(it) } as? ChatItem.ToolCall
                        if (card != null && idx != null) {
                            out[idx] = card.copy(
                                result = flatten(b.opt("content")),
                                isError = b.optBoolean("is_error", false),
                                // optJSONObject 在它是字符串/null 时自然返回 null —— 正合适
                                meta = meta,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun parseAssistant(
        msg: JSONObject, uuid: String,
        calls: MutableMap<String, Int>, out: MutableList<ChatItem>,
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
                    // ⚠️ 记的是它**将要占**的下标 —— 写在 out += 之前，所以是 size 不是 size-1。
                    // 写成 size-1 会指到前一条：回填时改错卡片，而且那张真正的卡永远停在「进行中」
                    calls[b.optString("id")] = out.size
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
