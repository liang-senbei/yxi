package app.yxi.desktop

import org.json.JSONObject

/**
 * 统一对话项（M3，PRD §5.5）：各运行器的消息先转成它，Code 风格的对话组件只认它，各面板就不用各画一套。经典风格不迁移（零回归优先）。
 * 只读视图模型：审批只带 id，动作仍走各自的控制器；不改任何控制器的状态和落盘格式。
 * 适配器（[Transcripts]）都是纯函数，按运行器各一个，有单测（TranscriptItemTest）。
 */
internal sealed interface TranscriptItem {
    /** 列表里的 key：沿用运行器给的消息 id（同一条消息流式更新时 key 不变）。 */
    val key: String

    /** 你发的消息；[images] 是随消息发出的图片（本机 Claude 的快照引用，其他运行器暂时没有）。 */
    data class User(override val key: String, val text: String, val images: List<InstructionAttachment> = emptyList()) : TranscriptItem
    /** 助手的正文（Markdown）。 */
    data class AssistantText(override val key: String, val text: String) : TranscriptItem
    /** 思考内容（弱色斜体，规格表 §3.4）。 */
    data class Thinking(override val key: String, val text: String) : TranscriptItem
    /** 一次工具调用：[title] 是一行标题（命令、工具名、改了哪些文件），[detail] 是输入 / 输出原文，[status] 是运行器给的状态（可能为空），
     *  [paths] 是涉及的文件，[kind] 是归类（摘要行按它合成短语，M3c），[command] 是执行命令时的命令本身。 */
    data class Tool(override val key: String, val title: String, val detail: String, val status: String = "", val paths: List<String> = emptyList(),
        val kind: ToolKind = ToolKind.Other, val command: String = "") : TranscriptItem
    /** 认不出的项：原样给文字，不吞掉。 */
    data class Notice(override val key: String, val text: String) : TranscriptItem
}

/** 工具归类（M3c 的摘要行按它合成短语）：执行命令、读文件、改文件、搜索、工具结果（Claude 的 tool_result，并进前一个调用）、其他。 */
internal enum class ToolKind { Command, Read, Edit, Search, Result, Other }

/** 对话里的一行（M3c）：单独一项，或者一个回合里连在一起的几次工具调用合成的一组（摘要行 + 点开的卡片）。 */
internal sealed interface TranscriptRow {
    val key: String
    data class Single(val item: TranscriptItem) : TranscriptRow { override val key get() = item.key }
    data class Tools(val tools: List<TranscriptItem.Tool>) : TranscriptRow { override val key get() = "tools:" + tools.first().key }
}

/**
 * 对话视图三档（M3g，规格表 §3.4）：普通 = 不显示思考、工具只有折叠的摘要行；思考 = 再显示思考，摘要行仍折叠；详细 = 显示思考，工具组卡片和每个条目都展开。
 * 每个会话一档（AppState.transcriptViews），没设过的取偏好 [PREF]（默认普通，同参照端）；Ctrl+O 轮换。
 */
internal enum class TranscriptView(val key: String, val label: String) {
    Normal("normal", "普通"), Thinking("thinking", "思考"), Detailed("detailed", "详细");
    fun next() = entries[(ordinal + 1) % entries.size]
    companion object {
        const val PREF = "ui.transcriptView"
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: Normal
    }
}

internal object Transcripts {
    /** 按对话视图过滤：普通档不显示思考（也不留占位）。 */
    fun visible(items: List<TranscriptItem>, view: TranscriptView): List<TranscriptItem> =
        if (view == TranscriptView.Normal) items.filterNot { it is TranscriptItem.Thinking } else items

    /** 连在一起的工具调用合成一组（规格表 §3.4）；Claude 的工具结果并进组里前一个调用的详情（没有前一个时自成一项）。 */
    fun rows(items: List<TranscriptItem>): List<TranscriptRow> {
        val rows = ArrayList<TranscriptRow>()
        var group = ArrayList<TranscriptItem.Tool>()
        fun flush() { if (group.isNotEmpty()) { rows += TranscriptRow.Tools(group); group = ArrayList() } }
        for (item in items) {
            if (item !is TranscriptItem.Tool) { flush(); rows += TranscriptRow.Single(item); continue }
            if (item.kind == ToolKind.Result && group.isNotEmpty()) {
                val last = group.removeAt(group.lastIndex)
                group += last.copy(detail = listOf(last.detail, item.detail).filter { it.isNotBlank() }.joinToString("\n\n"))
            } else group += item
        }
        flush()
        return rows
    }

    /** 摘要行的短语（规格表 §3.4「逗号连接的过去式短语」，中文自拟）：按归类第一次出现的顺序，每类一个短语，用「，」连起来。 */
    fun summary(tools: List<TranscriptItem.Tool>): String {
        fun names(kind: ToolKind) = tools.filter { it.kind == kind }.flatMap { it.paths }.map { it.substringAfterLast('/').substringAfterLast('\\') }.filter { it.isNotBlank() }.distinct()
        return tools.map { it.kind }.distinct().mapNotNull { kind ->
            val count = tools.count { it.kind == kind }
            when (kind) {
                ToolKind.Command -> if (count == 1) "运行了一条命令" else "运行了 $count 条命令"
                ToolKind.Read -> names(kind).let { if (it.isEmpty()) "读了文件" else if (it.size <= 2) "读了 " + it.joinToString("、") else "读了 ${it.size} 个文件" }
                ToolKind.Edit -> names(kind).let { if (it.isEmpty()) "改了文件" else if (it.size <= 2) "改了 " + it.joinToString("、") else "改了 ${it.size} 个文件" }
                ToolKind.Search -> if (count == 1) "搜索了一次" else "搜索了 $count 次"
                ToolKind.Other -> tools.filter { it.kind == kind }.map { it.title }.distinct().let { if (it.size == 1) it.single() else "用了 ${it.size} 个工具" }
                ToolKind.Result -> if (count == 1) "拿到一份工具结果" else "拿到 $count 份工具结果"
            }
        }.joinToString("，")
    }

    /** 按工具名归类（Claude 的 Bash / Read…、OpenCode 的 bash / read…，大小写都认）。 */
    internal fun kindOf(name: String): ToolKind = when (name.lowercase()) {
        "bash", "shell", "command", "exec" -> ToolKind.Command
        "read", "view", "cat" -> ToolKind.Read
        "edit", "write", "multiedit", "notebookedit", "patch", "apply_patch" -> ToolKind.Edit
        "grep", "glob", "list", "ls", "search", "find" -> ToolKind.Search
        else -> ToolKind.Other
    }

    /** 工具输入里常见的「命令」「文件」字段（Claude：command / file_path；OpenCode：command / filePath / path）。 */
    private fun inputOf(json: String): JSONObject? = runCatching { JSONObject(json) }.getOrNull()
    private fun pathsOf(input: JSONObject?): List<String> =
        listOfNotNull(input?.optString("file_path"), input?.optString("filePath"), input?.optString("path"), input?.optString("notebook_path")).filter { it.isNotBlank() }.distinct()

    /** 本机 Claude（ClaudeTaskController）：role 是 User / Assistant / Tool，kind 是 Claude 的 content block 类型（text / tool_use / tool_result）。 */
    fun claude(m: ClaudeMessage): TranscriptItem = when {
        m.role == "User" -> TranscriptItem.User(m.id, m.text, m.attachments)
        m.kind == "tool_use" -> m.text.substringBefore('\n').let { name ->
            val raw = m.text.substringAfter('\n', ""); val input = inputOf(raw)
            TranscriptItem.Tool(m.id, name.ifBlank { "工具" }, raw, paths = pathsOf(input), kind = kindOf(name), command = input?.optString("command").orEmpty())
        }
        m.kind == "tool_result" -> TranscriptItem.Tool(m.id, "工具结果", m.text, kind = ToolKind.Result)
        m.role == "Assistant" -> TranscriptItem.AssistantText(m.id, m.text)
        else -> TranscriptItem.Notice(m.id, m.text)
    }

    /** 本机 Claude 的原生历史（core 的 ChatItem，LocalClaudeHistory 读出来的）：工具调用带着结果；注入的（队友消息、系统提醒等）、
     *  原生队列里待处理的、接口报错和认不出的都给成 [TranscriptItem.Notice]，前面写明是什么，不冒充你说的话或助手的正文。 */
    fun claudeHistory(item: app.yxi.agent.ChatItem): TranscriptItem = when (item) {
        is app.yxi.agent.ChatItem.UserText -> TranscriptItem.User(item.key, item.text)
        is app.yxi.agent.ChatItem.AssistantText -> TranscriptItem.AssistantText(item.key, item.markdown)
        is app.yxi.agent.ChatItem.Thinking -> TranscriptItem.Thinking(item.key, item.text)
        is app.yxi.agent.ChatItem.ToolCall -> TranscriptItem.Tool(item.key, item.name.ifBlank { "工具" },
            listOfNotNull(item.input.toString(2), item.result?.takeIf { it.isNotBlank() }).joinToString("\n\n"),
            if (item.isError) "failed" else if (item.result != null) "completed" else "", pathsOf(item.input), kindOf(item.name), item.input.optString("command"))
        is app.yxi.agent.ChatItem.Queued -> TranscriptItem.Notice(item.key, "原生队列 · 待处理：" + item.text)
        is app.yxi.agent.ChatItem.Injected -> TranscriptItem.Notice(item.key, listOfNotNull(item.kind, item.from).joinToString(" · ") + "\n" + app.yxi.agent.Transcript.clean(item.text))
        is app.yxi.agent.ChatItem.ApiError -> TranscriptItem.Notice(item.key, "接口报错：" + item.text)
        is app.yxi.agent.ChatItem.Unknown -> TranscriptItem.Notice(item.key, "未识别的历史内容\n" + item.raw)
    }

    /** ACP（AcpTaskController，本机和服务器共用）：工具卡 kind = tool，author 是工具标题；其余 author 是 User / Assistant。 */
    fun acp(m: AcpMessage): TranscriptItem = when {
        m.kind == "tool" -> TranscriptItem.Tool(m.id, m.author.ifBlank { "工具" }, m.text, m.status)
        m.author == "User" -> TranscriptItem.User(m.id, m.text)
        m.author == "Assistant" -> TranscriptItem.AssistantText(m.id, m.text)
        else -> TranscriptItem.Notice(m.id, m.text)
    }

    /** Codex app-server（CodexTaskController.decodeMessage 已解好）：「你」/「Codex」是消息，kind = command / files 是执行命令和文件改动。
     *  命令的第一行是命令本身，其余（目录、输出、退出码）是详情；文件改动的标题列出文件名。 */
    fun codex(m: CodexMessage): TranscriptItem = when {
        m.kind == "command" -> m.text.substringBefore('\n').let { command ->
            TranscriptItem.Tool(m.id, command, m.text.substringAfter('\n', ""), m.status, kind = ToolKind.Command, command = command)
        }
        m.kind == "files" -> TranscriptItem.Tool(m.id, "改动 " + m.paths.joinToString("、") { it.substringAfterLast('/').substringAfterLast('\\') }.ifBlank { m.author },
            m.text, m.status, m.paths, ToolKind.Edit)
        m.author == "你" -> TranscriptItem.User(m.id, m.text)
        m.author == "Codex" -> TranscriptItem.AssistantText(m.id, m.text)
        else -> TranscriptItem.Notice(m.id, m.text)
    }

    /** OpenCode（原生 HTTP 的消息，info + parts）：一条消息拆成几项。用户消息合成一项（只取非合成的 text 段）；助手的 text 段是正文、
     *  reasoning 段是思考、tool 段是工具（标题 = 工具名，详情 = 状态里的输入输出）；step-start / step-finish 只是分段标记，不显示；
     *  别的类型原样给 JSON（同经典）。key = 消息 id（用户）或「消息 id:段号」（助手）。 */
    fun openCode(message: JSONObject): List<TranscriptItem> {
        val info = message.getJSONObject("info")
        val id = info.getString("id")
        val parts = message.optJSONArray("parts")?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }.orEmpty()
        if (info.optString("role") == "user") {
            val text = parts.filter { it.optString("type") == "text" && !it.optBoolean("synthetic") }.joinToString("\n") { it.optString("text") }
            return listOf(TranscriptItem.User(id, text))
        }
        return parts.mapIndexedNotNull { index, part ->
            val key = "$id:$index"
            when (part.optString("type")) {
                "text" -> TranscriptItem.AssistantText(key, part.optString("text"))
                "reasoning" -> part.optString("text").takeIf { it.isNotBlank() }?.let { TranscriptItem.Thinking(key, it) }
                "tool" -> {
                    val state = part.optJSONObject("state"); val input = state?.optJSONObject("input"); val name = part.optString("tool")
                    TranscriptItem.Tool(key, name.ifBlank { "工具" },
                        state?.let { s -> listOfNotNull(input?.toString(2), s.opt("output")?.toString()).joinToString("\n") }.orEmpty(),
                        state?.optString("status").orEmpty(), pathsOf(input), kindOf(name), input?.optString("command").orEmpty())
                }
                "step-start", "step-finish" -> null
                else -> TranscriptItem.Notice(key, part.toString(2))
            }
        }
    }
}
