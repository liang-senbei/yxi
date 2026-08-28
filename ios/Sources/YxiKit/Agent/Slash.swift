import Foundation

/// 斜杠命令的提示条。
///
/// 命令**原样打进那个活着的 Claude Code 会话**，由它自己的命令面板处理。
/// 所以这份清单**不是白名单** —— 没列进来的（你自己写在 `~/.claude/commands`
/// 下的、插件带的）照打照样送得出去，只是没有提示而已。
/// 它存在的唯一理由是：**手机上一个字母一个字母敲 `/compact` 太难受了。**
///
/// ⚠️ 顺序 = 手机上的常用度，不是字母序。只打了一个 `/` 时整份显示，
/// 前几个就是你八成想按的那几个。
public enum Slash {

    public struct Cmd: Equatable, Sendable, Identifiable {
        public let name: String
        public let hint: String
        public var id: String { name }
    }

    public static let all: [Cmd] = [
        Cmd(name: "compact", hint: "压掉上下文，留个摘要接着聊"),
        Cmd(name: "context", hint: "现在上下文占了多少"),
        Cmd(name: "usage", hint: "用量和额度还剩多少"),
        Cmd(name: "cost", hint: "这段会话花了多少"),
        Cmd(name: "clear", hint: "清空重开（⚠️ 当前对话就没了）"),
        Cmd(name: "model", hint: "换模型"),
        Cmd(name: "status", hint: "版本、账号、连接状态"),
        Cmd(name: "todos", hint: "看它手头的待办"),
        Cmd(name: "rewind", hint: "退回之前的检查点"),
        Cmd(name: "resume", hint: "挑一段历史会话接着聊"),
        Cmd(name: "agents", hint: "管子代理"),
        Cmd(name: "export", hint: "导出这段对话"),
        Cmd(name: "memory", hint: "改记忆文件"),
        Cmd(name: "permissions", hint: "权限规则"),
        Cmd(name: "mcp", hint: "看 MCP 服务器"),
        Cmd(name: "config", hint: "设置"),
        Cmd(name: "init", hint: "给这个项目生成 CLAUDE.md"),
        Cmd(name: "review", hint: "审代码"),
        Cmd(name: "doctor", hint: "自检安装"),
        Cmd(name: "bug", hint: "报 bug"),
        Cmd(name: "help", hint: "全部命令"),
    ]

    /// 正在打的这段草稿要不要弹提示。不要就给空数组。
    ///
    /// 四条规矩，都是为了**别在不该弹的时候挡住输入框**：
    /// - 必须**整条**以 `/` 开头 —— 正文里提到 `/usr/bin` 不算
    /// - 打了空格就收起来 —— 那时候在填参数（`/model opus`），提示没用了
    /// - 多行也收起来 —— 粘贴进来的长文本很可能第一行就是个路径
    /// - **打全了也收起来** —— 已经是 `/usage` 了就没什么好补的，
    ///   再挂着只是挡住输入框（点一下候选之后正是这个情形）
    ///
    /// ⚠️ 最后一条成立的前提是**没有哪个命令名是另一个的前缀**
    /// （compact/context/cost/config 互不为前缀，model/memory/mcp 也是）。
    /// 真加了这种一对，「打全了」就不等于「选好了」，得换别的收起法 ——
    /// `SlashTests.没有命令名是另一个的前缀` 盯着这件事。
    public static func suggest(_ draft: String) -> [Cmd] {
        guard draft.hasPrefix("/") else { return [] }
        let word = String(draft.dropFirst())
        guard !word.contains(where: { $0.isWhitespace }) else { return [] }
        let lower = word.lowercased()
        guard !all.contains(where: { $0.name == lower }) else { return [] }
        return all.filter { $0.name.hasPrefix(lower) }
    }
}
