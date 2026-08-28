import Foundation

/// **模式快切** —— 点一下就把对应的斜杠命令发进会话，切模型 / 思考强度 / ponytail。
///
/// 关键：这些模式（`/model`、`/effort`、`/ponytail`…）是**各自独立的斜杠命令**，
/// 所以**能叠加** —— 连点几个就都生效（1M + 最大思考）。
///
/// ⚠️ 命令**做成可编辑**：不同 Claude Code 版本 / 各人习惯，具体命令可能不一样。
/// 给的是安卓侧**从真转录里核过**的默认值，用户可以在界面里改。
public enum Modes {
    public struct Mode: Equatable, Identifiable, Sendable {
        public let label: String
        public let command: String
        public var id: String { label }
        public init(label: String, command: String) { self.label = label; self.command = command }
    }

    /// ⚠️ 别凭感觉写：安卓侧实测 `/model opus[1m]` **不认**（回「Kept model as …」等于没切），
    /// 认的是**全名带后缀** `/model claude-opus-5[1m]`；`/effort max|high|mid` 转录里用过很多次。
    public static let defaults: [Mode] = [
        .init(label: "1M 上下文", command: "/model claude-opus-5[1m]"),
        .init(label: "最大思考", command: "/effort max"),
        .init(label: "高强度", command: "/effort high"),
        .init(label: "中等", command: "/effort mid"),
        .init(label: "ultracode", command: "/ponytail ultra"),
        .init(label: "普通", command: "/ponytail"),
    ]

    /// 存成「名字|命令」一行一条 —— 跟安卓版同一种格式，两端能互相看懂。
    public static func encode(_ list: [Mode]) -> String {
        list.map { "\($0.label)|\($0.command)" }.joined(separator: "\n")
    }

    public static func decode(_ raw: String) -> [Mode] {
        let list = raw.components(separatedBy: "\n").compactMap { line -> Mode? in
            guard let i = line.firstIndex(of: "|") else { return nil }
            let label = String(line[line.startIndex..<i]).trimmingCharacters(in: .whitespaces)
            let cmd = String(line[line.index(after: i)...]).trimmingCharacters(in: .whitespaces)
            return label.isEmpty || cmd.isEmpty ? nil : Mode(label: label, command: cmd)
        }
        return list.isEmpty ? defaults : list
    }
}

/// **常用语 / 罐头回复** —— 你自己的短语库，一点就塞进回复框。
/// 跟斜杠命令菜单不同：那是 Claude 的命令，这是**你的话**。手机打字是回复的瓶颈。
public enum Snippets {
    public static let defaults = [
        "继续", "好的，按你说的做", "先给方案再动手", "跑一下测试", "commit 并推一下", "停一下，我看看",
    ]
    public static func decode(_ raw: String) -> [String] {
        let list = raw.components(separatedBy: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        return list.isEmpty ? defaults : list
    }
    public static func encode(_ list: [String]) -> String { list.joined(separator: "\n") }
}
