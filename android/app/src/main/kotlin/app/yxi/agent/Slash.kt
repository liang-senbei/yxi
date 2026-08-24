package app.yxi.agent

/**
 * 对话输入框里的**斜杠命令提示**。
 *
 * ⚠️ **这里不执行任何东西。** `/compact` 走的还是老路：`tmux send-keys` 把这几个字
 * 原样打进那个活着的 Claude Code 会话，由它自己的命令面板处理。
 * 所以这份清单**不是白名单** —— 没列进来的（你自己写的 `~/.claude/commands` 下的、
 * 插件带的斜杠命令）照打照样送得出去，只是没有提示而已。
 * 它存在的唯一理由是：**手机上一个一个字母敲 `/compact` 太难受了。**
 *
 * ⚠️ 顺序 = 手机上的常用度，不是字母序。列表在只打了一个 `/` 时整份显示，
 * 前几个就是你八成想按的那几个。
 */
object Slash {
    data class Cmd(val name: String, val hint: String)

    val ALL = listOf(
        Cmd("compact", "压掉上下文，留个摘要接着聊"),
        Cmd("context", "现在上下文占了多少"),
        Cmd("usage", "用量和额度还剩多少"),
        Cmd("cost", "这段会话花了多少"),
        Cmd("clear", "清空重开（⚠️ 当前对话就没了）"),
        Cmd("model", "换模型"),
        Cmd("status", "版本、账号、连接状态"),
        Cmd("todos", "看它手头的待办"),
        Cmd("rewind", "退回之前的检查点"),
        Cmd("resume", "挑一段历史会话接着聊"),
        Cmd("agents", "管子代理"),
        Cmd("export", "导出这段对话"),
        Cmd("memory", "改记忆文件"),
        Cmd("permissions", "权限规则"),
        Cmd("mcp", "看 MCP 服务器"),
        Cmd("config", "设置"),
        Cmd("init", "给这个项目生成 CLAUDE.md"),
        Cmd("review", "审代码"),
        Cmd("doctor", "自检安装"),
        Cmd("bug", "报 bug"),
        Cmd("help", "全部命令"),
    )

    /**
     * 正在打的这段草稿要不要弹提示。不要就给空列表。
     *
     * 三条规矩，都是为了**别在不该弹的时候挡住输入框**：
     *   · 必须**整条**以 `/` 开头 —— 正文里提到 `/usr/bin` 不算
     *   · 一旦打了空格就收起来 —— 那时候在填参数（`/model opus`），提示没用了
     *   · 多行也收起来 —— 粘贴进来的长文本很可能第一行就是个路径
     *   · **打全了也收起来** —— 已经是 `/usage` 了就没什么好补的，
     *     再挂着提示条只是挡住输入框（点一下候选之后正是这个情形）
     *
     * ⚠️ 最后那条成立的前提是**没有哪个命令名是另一个的前缀**
     * （compact/context/cost/config 互不为前缀，model/memory/mcp 也是）——
     * 真加了这种一对，「打全了」就不等于「选好了」，得改成别的收起法。
     * [SlashTest.没有命令名是另一个的前缀] 盯着这件事。
     */
    fun suggest(draft: String): List<Cmd> {
        if (!draft.startsWith("/")) return emptyList()
        val word = draft.drop(1)
        if (word.any { it.isWhitespace() }) return emptyList()
        if (ALL.any { it.name.equals(word, ignoreCase = true) }) return emptyList()
        return ALL.filter { it.name.startsWith(word, ignoreCase = true) }
    }
}
