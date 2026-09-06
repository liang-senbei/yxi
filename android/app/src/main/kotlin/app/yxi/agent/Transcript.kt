package app.yxi.agent

import app.yxi.ui.t
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

    /**
     * API 报错（`API Error: 529 Overloaded…`）。
     *
     * ⚠️ **它在转录里是一条正常的 assistant 消息**，不加处理就会被当成
     * Claude 说的话，用 markdown 正文渲染 —— 屏幕上看起来就像它一本正经地
     * 跟你解释「服务器过载」。用户的原话是「这些报错不要用正文来渲染」。
     *
     * ⚠️ **判据用转录自己的 `isApiErrorMessage` 标志，不要去匹配文本。**
     * 匹配 "API Error" 会误伤真正在讨论这个错误的对话
     * （比如你问「API Error 529 是什么意思」，它的回答里也有这几个字）。
     */
    data class ApiError(override val key: String, val text: String) : ChatItem
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
    /**
     * 这个会话**此刻**占了多少上下文，以及跑在哪个模型上。
     *
     * ⚠️ **来源是最后一条 assistant 消息的 `usage`，不是自己数字数。**
     * 那一条记的是这一轮真正发给模型的量：
     * `input_tokens + cache_creation_input_tokens + cache_read_input_tokens`。
     * 实测同一个会话连着四轮是 654665 → 655610 → 655751 → 656203，跟着涨，正是我们要的。
     *
     * ⚠️ **不给百分比。** 算百分比要知道这个模型的上下文窗口，而转录里没有 ——
     * 实测 `claude-opus-4-6` 的会话上下文已经 656K，远超常说的 200K，
     * 说明同一个模型名下有不同窗口。按 200K 算会显示成 328%，
     * 那比不显示危险得多（跟 [Usage] 那条「宁可不显示也不显示假的」是同一条规矩）。
     */
    data class Ctx(
        val tokens: Long,
        val model: String,
        /** 思考强度：`max` / `high` / `mid`。⚠️ 在转录行的**顶层**（不是 message 里）。空 = 转录没记。 */
        val effort: String = "",
        /** 会话模式：`normal` / `plan` 之类，来自 `{"type":"mode",…}` 行。空或 normal = 不用显示。 */
        val mode: String = "",
        /**
         * ponytail 插件的强度（`lite`/`full`/`ultra`）。来自它每次注入的
         * `PONYTAIL MODE ACTIVE — level: x`（钩子输出，落在转录里）。
         * ⚠️ **不一定读得到**：它只在会话开始/换模式/提交提示时注入，
         * 中间隔几千行都可能。读不到就**空着不显示**（宁可不显示也不显示假的）。
         */
        val ponytail: String = "",
    )

    class Incremental {
        private val out = ArrayList<ChatItem>()
        private val calls = HashMap<String, Int>()          // tool_use_id → out 里的下标
        // ⚠️ **有序 List 不是 Set。** 队列就是队列：`dequeue` 不带 content，
        // 只能按**先进先出**弹队头，所以顺序是判据的一部分。
        // 用 Set 还会把「同一句话排了两次」合成一条，那是真的丢消息。
        private val queued = ArrayList<String>()
        private val said = HashSet<String>()                // 已经作为用户消息出现过的原文

        /** 最后一条 assistant 消息报的上下文用量。⚠️ 顺带解析，**不额外跑一趟服务器**。 */
        var ctx: Ctx? = null
            private set

        // ⚠️ 最后那个 lambda 不能省。`onCtx` 有默认值 `{}`，漏了它**编译照样通过**，
        // 只是 [ctx] 永远是 null —— 界面上表现为「上下文那一格永远不出现」，不报错。
        fun add(lines: Sequence<String>) =
            parseInto(lines, out, calls, queued, said, ctx) { ctx = it }

        /** 当前快照。排队的挂在最后 —— 它们还没进对话，位置就在「此刻」。 */
        fun snapshot(): List<ChatItem> {
            // ⚠️⚠️ **key 里不能有队列下标。** 原来是 `"queued-$i-…"`，`i` 是在整个队列里的位置 ——
            //    队头一出队，**后面每一条的 key 全变**，LazyColumn 只能把它们销毁重建
            //    （屏幕上是「一现即隐」），而且 head 的最后一条要是排队条目，
            //    `headLastKey` 就永远匹配不上、历史判不出「灌完了」（见 ChatScreen 的 caughtUp）。
            //    现在按**内容**编号：同样的话出现第几次。出队只会影响它后面同内容的那几条，
            //    不同内容的一律不受牵连 —— 而重复排同一句话本来就少见。
            val seen = HashMap<Int, Int>()
            return out + queued.asSequence()
                .filter { it.isNotBlank() }              // 老格式的空占位不显示
                .filterNot { it.trim() in said }
                .map { t ->
                    val h = t.hashCode()
                    val nth = seen.merge(h, 1, Int::plus)!! - 1
                    val k = "queued-$h-$nth"
                    // ⚠️ **排着队的也可能不是用户说的话。** 队友/子 agent 的消息是通过
                    // 队列注入的，落在 `queue-operation` 里 —— 只在 parseUser 里认注入
                    // 会漏掉它们，屏幕上就是一坨 `<agent-message from="…">` 顶着
                    // 「排队中·你说的话」的样子。这条是真机上看出来的。
                    val inj = injectedOf(t)
                    if (inj == null) ChatItem.Queued(k, t)
                    else ChatItem.Injected(k, inj.first + t(" · 排队中"), inj.second, t)
                }
                .toList()
                // ⚠️ **兜住重复 key。** 重连时 `LaunchedEffect(sessionName, ssh)` 会换键重启，
                //    Compose 只 cancel 不 join —— 旧那轮的刷新协程可能再喂一批进**同一个**
                //    解析器（entry 是 ChatMemory 里的全局对象），同一行就解了两遍。
                //    LazyColumn 遇到重复 key 是**直接抛**，不是画错 —— 宁可多这一趟去重。
                .distinctBy { it.key }
        }

        /** 已经吃进去多少行 —— 上层拿它决定从哪儿接着喂。 */
        var consumed: Int = 0
            internal set
    }

    private fun parseInto(
        lines: Sequence<String>,
        out: ArrayList<ChatItem>,
        calls: HashMap<String, Int>,
        queued: ArrayList<String>,
        said: HashSet<String>,
        /**
         * 上一批解析到哪儿了（[Incremental] 传它自己的 [Incremental.ctx]）。
         * ⚠️ **必须跨批传下来**：转录是一段一段追加解析的，而 `/model` 的回执
         * 常常单独落在一批里（那一批只有一条 user 消息）。它是 null 的话，
         * 下面「切了模型但还没回话」那一支直接被丢掉 —— 表现就是
         * 「已经切到 Opus 5 了，顶栏还写着 fable-5-1」（老板 2026-09-06 报的）。
         */
        startCtx: Ctx? = null,
        onCtx: (Ctx) -> Unit = {},
    ) {
        var lastCtx: Ctx? = startCtx   // 最近一次报出来的用量，给「切了模型但还没回话」时套用
        var lastMode = startCtx?.mode.orEmpty()   // 最近一条 {"type":"mode"} 行
        var lastPony = startCtx?.ponytail.orEmpty()  // 最近一次 ponytail 注入报的强度
        lines.forEach { line ->
            if (line.isBlank()) return@forEach
            val d = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            // 侧链（子 agent 的内部对话）不进主时间线，否则会把主线淹掉
            if (d.optBoolean("isSidechain", false)) return@forEach

            // ponytail 的强度只在它注入的那段文字里（钩子输出，包在 attachment 里）。
            // ⚠️ 直接在**原始行**上正则，别去钻 JSON 结构 —— 那个结构是插件的实现细节，会变。
            if ("PONYTAIL MODE" in line) {
                PONYTAIL.find(line)?.groupValues?.get(1)?.lowercase()?.let { lv ->
                    if (lv != lastPony) {
                        lastPony = lv
                        lastCtx?.let { onCtx(it.copy(ponytail = lv)) }
                    }
                }
            }

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
                when (d.optString("operation")) {
                    // ⚠️ **空 content 也要占个位**（老格式的 enqueue 就没有 content）——
                    // 不占位的话下面 dequeue 弹队头会弹错人。展示时再把空的滤掉。
                    "enqueue" -> queued += c
                    // 带 content 的，按内容精确删
                    "remove", "popAll" -> if (c.isNotBlank()) queued.remove(c) else queued.removeFirstOrNull()
                    // ⚠️ **`dequeue` 从来不带 content**（实测 1094 条，一条都没有）。
                    // 所以只能按先进先出弹队头 —— 这也正是队列本来的语义。
                    //
                    // ⚠️ 漏掉这一支的后果是**排队气泡永远不消失**：
                    // 斜杠命令（比如打错的 `/modle`）被本地消化掉，
                    // 既不写 remove、也**永远不会作为 user 消息出现**，
                    // 于是 #76 那个「出现过就算说过」的兜底也救不了它。
                    "dequeue" -> queued.removeFirstOrNull()
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

            // ⚠️ 在 `when (type)` 之前拦下来 —— 它的 type 就是 "assistant"，
            // 放过去就会走进正文渲染
            if (d.optBoolean("isApiErrorMessage", false)) {
                val txt = d.optJSONObject("message")?.optJSONArray("content")
                    ?.optJSONObject(0)?.optString("text").orEmpty()
                if (txt.isNotBlank()) out += ChatItem.ApiError(uuidOf(d, line), txt)
                return@forEach
            }

            // ⚠️ 模式行**没有 message 字段**，得在下面那个 return 之前接住。
            //   {"type":"mode","mode":"normal","sessionId":"…"}   计划模式之类
            // 顶栏要显示「这个会话在什么模式」，而它只在这种行里。
            if (type == "mode") {
                val m = d.optString("mode")
                if (m.isNotBlank() && m != lastMode) {
                    lastMode = m
                    lastCtx?.let { onCtx(it.copy(mode = m)) }   // 已经有用量的话立刻反映到顶栏
                }
                return@forEach
            }

            val msg = d.optJSONObject("message") ?: run {
                // 非消息行（file-history-snapshot / ai-title 之类）静默跳过
                return@forEach
            }
            val uuid = d.optString("uuid", d.optString("requestId", line.hashCode().toString()))

            when (type) {
                "user" -> {
                    parseUser(
                        msg, d.optJSONObject("toolUseResult"), uuid, calls, out, said,
                        // ⚠️ Claude Code 给「不是用户打的」消息打了 isMeta —— 见 [parseUser]
                        isMeta = d.optBoolean("isMeta", false),
                    )
                    // ⚠️ **切完模型、但它还没回话时，标签也得跟着变。**
                    // 顶栏那个模型名来自「最后一条 assistant 消息」的 model —— 切换不会改写旧消息，
                    // 所以不认这一步的话，用户切了模型还看见旧名字，会以为没切成（用户报过）。
                    // `/model` 的回执落在命令输出里（`Set model to …`），照它覆盖。
                    modelSwitchOf(msg)?.let { name -> lastCtx?.let { onCtx(it.copy(model = name)) } }
                }
                "assistant" -> {
                    // ⚠️ 顺路取，不额外跑一趟服务器 —— 这些行本来就在手上
                    // ⚠️ `effort` 在**转录行顶层**（`d`），不在 message 里 —— 找错地方就永远是空
                    ctxOf(msg)?.copy(effort = d.optString("effort"), mode = lastMode, ponytail = lastPony)
                        ?.let { lastCtx = it; onCtx(it) }
                    parseAssistant(msg, uuid, calls, out)
                }
                else -> Unit
            }
        }
    }

    /**
     * 从一条 assistant 消息里读出「这一轮发给模型多少上下文」。
     *
     * ⚠️ 三项都要加：`input_tokens` 是这轮新增的、`cache_creation` 是这轮写进缓存的、
     * `cache_read` 是命中缓存复用的 —— **合起来才是模型这轮实际看到的量**。
     * 只看 `input_tokens` 的话，一个 65 万 token 的会话会显示成「1」（真的，实测就是 1）。
     */
    private fun ctxOf(msg: JSONObject): Ctx? {
        val u = msg.optJSONObject("usage") ?: return null
        val n = u.optLong("input_tokens") +
            u.optLong("cache_creation_input_tokens") +
            u.optLong("cache_read_input_tokens")
        return if (n > 0) Ctx(n, msg.optString("model")) else null
    }

    /**
     * `/model` 的回执：`Set model to Opus 5 (1M context) for this session only`
     * / `… and saved as your default for new sessions`。取中间那个模型名。
     * ⚠️ 先 [clean] 掉 ANSI —— 回执里带 `\u001b[1m` 加粗序列（见 [ANSI] 那条注释）。
     */
    /**
     * `PONYTAIL MODE ACTIVE — level: full` / `… CHANGED — level: ultra`。
     * ⚠️ 破折号是 em dash，别写死；等级偶尔是空的（实测有 29 条），那就匹配不上，正好跳过。
     */
    private val PONYTAIL = Regex("""PONYTAIL MODE [A-Z]+[^:]*level:\s*([A-Za-z]+)""")

    // ⚠️ `<` 也是终止符：回执被包在 `<local-command-stdout>…</local-command-stdout>` 里，
    // 不拦的话闭合标签会被吃进模型名（测试抓出来的）。模型名里不会有 `<`。
    private val SET_MODEL = Regex("""Set model to\s+(.+?)\s*(?:for this session|and saved|<|$)""")

    private fun modelSwitchOf(msg: JSONObject): String? {
        val c = msg.opt("content")
        val text = when (c) {
            is String -> c
            is org.json.JSONArray -> (0 until c.length()).joinToString(" ") { i ->
                c.optJSONObject(i)?.optString("text").orEmpty()
            }
            else -> return null
        }
        if ("Set model to" !in text) return null
        // ⚠️ **在原文上匹配，别先 [clean]。** 别名形式 `claude-opus-5[1m]` 里的 `[1m`
        // 跟 ANSI 加粗序列长得一模一样，先清一遍会把它吃掉 → 显示成 `claude-opus-5]`。
        // 所以只在取出来的名字上摘掉首尾那对加粗标记（ESC 有无都兼容）。
        val raw = SET_MODEL.find(text)?.groupValues?.get(1)?.trim()?.replace("\u001B", "") ?: return null
        // ⚠️ 回执把模型名包在**反引号**里，后面还常跟一个 `(default)`（那是「存成账号默认了」
        //    这件事，不是模型名的一部分）。不摘掉的话顶栏会显示成
        //    `` `Opus 5 (1M context) (default)` `` —— 带引号、还比别的名字长一截。
        return raw.removePrefix("[1m").removeSuffix("[22m").trim()
            .trim('`').trim()
            .removeSuffix("(default)").trim()
            .takeIf { it.isNotBlank() }?.take(40)
    }

    private fun uuidOf(d: JSONObject, line: String): String =
        d.optString("uuid", d.optString("requestId", line.hashCode().toString()))

    /**
     * 注入内容的标签 → 给人看的类别。
     *
     * ⚠️ 这些标签**不一定在开头** —— 前面常常还有一段引子
     * （「Another Claude session sent a message:」之类），所以是**搜**不是 `startsWith`。
     */
    // ⚠️ `get()` 不是 `=`：一次性求值的话，换语言后这些卡片标题不跟着变
    private val INJECTED: List<Pair<String, String>> get() = listOf(
        "teammate-message" to t("队友消息"),
        "agent-message" to t("子 agent 消息"),
        "cross-session-message" to t("跨会话消息"),
        "task-notification" to t("任务通知"),
        "system-reminder" to t("系统提醒"),
        "local-command-caveat" to t("系统提醒"),
        "local-command-stdout" to t("命令输出"),
        "command-name" to t("斜杠命令"),
    )

    /**
     * 谁发的。⚠️ **属性名不止一个**：子 agent 用 `from="…"`，
     * 队友消息用 `teammate_id="…"` —— 只认 `from` 的话队友那栏永远是空的
     * （这条是测试抓出来的，我原本只写了 from）。
     */
    /**
     * ANSI 转义序列。**命令输出里会原样带着它们。**
     *
     * ⚠️ 真实例子：`Set model to \u001b[1mFable 5\u001b[22m for this session only`
     * —— `[1m` / `[22m` 是加粗开关。终端会解释它们，而对话卡片是纯文本渲染，
     * 于是屏幕上直接冒出 `[1mFable 5[22m`。浅色主题下尤其扎眼（我就是这么发现的）。
     *
     * ⚠️ **`\u001b` 有时会被吃掉只剩 `[1m`**（转录里两种都见过），所以 ESC 是可选的。
     * 代价是理论上会误伤正文里真的写着 `[1m` 的情况 —— 那种极少，
     * 而漏掉的话每条命令输出都带着乱码。
     */
    private val ANSI = Regex("""\u001B?\[[0-9;]*[A-Za-z]""")

    /** 去掉 ANSI 控制序列。⚠️ 只在**展示**前用，别改动原文用于比对的地方（去重靠原文）。 */
    internal fun clean(s: String): String = ANSI.replace(s, "")

    private val FROM = Regex("""(?:from|teammate_id|agent_id)="([^"]+)"""")

    /** 认出来就返回（类别, 谁发的），否则 null。 */
    /**
     * 读图之后 Claude Code 追加的坐标注解，形如
     * `[Image: original 1264x2800, displayed at 903x2000. Multiply coordinates by 1.40 to map to original image.]`。
     * 纯粹是给模型换算坐标用的，**对话里一点意义都没有** —— 整条丢掉。
     */
    private val IMAGE_NOTE = Regex("""^\[Image: original \d+x\d+[^\n]*]${'$'}""")

    private fun injectedOf(text: String): Pair<String, String?>? {
        for ((tag, label) in INJECTED) {
            val i = text.indexOf("<$tag")
            if (i < 0) continue
            val from = FROM.find(text, i)?.groupValues?.get(1)
            return label to from
        }
        return null
    }

    /**
     * ⚠️ **`isMeta` 的消息不是用户打的，绝不能画成用户气泡。**
     *
     * Claude Code 把一批「role 是 user、但人没说过」的东西也写成 user 消息，
     * 统一带 `isMeta: true`：读图后的坐标注解、Stop 钩子回执、目标复查、
     * skill 载入说明、`<local-command-caveat>`……
     * 照直渲染就是**凭空替用户说话** —— 用户报的就是这个：
     * 他从没打过那句 `[Image: original 1264x2800, displayed at 903x2000. …]`，
     * 手机上却整整齐齐一个蓝气泡。这份转录里 91 条 isMeta，62 条是图片注解。
     *
     * 处理分三档：
     *  · 带 `<agent-message>` 之类标签的 → 照旧走 [ChatItem.Injected]（那些**有内容**，
     *    比如别的会话发来的消息，用户是要看的）；
     *  · 图片坐标注解 → **直接丢**。它是给模型看的渲染参数，对话里没有任何意义；
     *  · 其余 isMeta → 当系统消息画，别混进用户说的话里。
     *
     * ⚠️ 而且 isMeta 的文本**不能进 `said`**：那是排队消息的出队判据（#76），
     * 拿系统文本去配对会把用户真正排队的那条误判成「已经说过了」。
     */
    private fun parseUser(
        msg: JSONObject, meta: JSONObject?, uuid: String,
        calls: MutableMap<String, Int>, out: MutableList<ChatItem>,
        said: MutableSet<String>,
        isMeta: Boolean = false,
    ) {
        fun said(key: String, t: String) {
            val inj = injectedOf(t)
            if (inj != null) {
                out += ChatItem.Injected(key, inj.first, inj.second, t)
                return
            }
            if (isMeta) {
                if (IMAGE_NOTE.matches(t.trim())) return
                out += ChatItem.Injected(key, t("系统消息"), null, t)
                return
            }
            out += ChatItem.UserText(key, t)
            said += t.trim()     // 出队判据要用（#76）
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
