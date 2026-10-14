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
     *  [paths] 是涉及的文件（目前只有 Codex 的文件改动给）。 */
    data class Tool(override val key: String, val title: String, val detail: String, val status: String = "", val paths: List<String> = emptyList()) : TranscriptItem
    /** 认不出的项：原样给文字，不吞掉。 */
    data class Notice(override val key: String, val text: String) : TranscriptItem
}

internal object Transcripts {
    /** 本机 Claude（ClaudeTaskController）：role 是 User / Assistant / Tool，kind 是 Claude 的 content block 类型（text / tool_use / tool_result）。 */
    fun claude(m: ClaudeMessage): TranscriptItem = when {
        m.role == "User" -> TranscriptItem.User(m.id, m.text, m.attachments)
        m.kind == "tool_use" -> m.text.substringBefore('\n').let { name -> TranscriptItem.Tool(m.id, name.ifBlank { "工具" }, m.text.substringAfter('\n', "")) }
        m.kind == "tool_result" -> TranscriptItem.Tool(m.id, "工具结果", m.text)
        m.role == "Assistant" -> TranscriptItem.AssistantText(m.id, m.text)
        else -> TranscriptItem.Notice(m.id, m.text)
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
        m.kind == "command" -> TranscriptItem.Tool(m.id, m.text.substringBefore('\n'), m.text.substringAfter('\n', ""), m.status)
        m.kind == "files" -> TranscriptItem.Tool(m.id, "改动 " + m.paths.joinToString("、") { it.substringAfterLast('/').substringAfterLast('\\') }.ifBlank { m.author },
            m.text, m.status, m.paths)
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
                    val state = part.optJSONObject("state")
                    TranscriptItem.Tool(key, part.optString("tool").ifBlank { "工具" },
                        state?.let { s -> listOfNotNull(s.optJSONObject("input")?.toString(2), s.opt("output")?.toString()).joinToString("\n") }.orEmpty(),
                        state?.optString("status").orEmpty())
                }
                "step-start", "step-finish" -> null
                else -> TranscriptItem.Notice(key, part.toString(2))
            }
        }
    }
}
