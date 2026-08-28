import Foundation
#if canImport(CoreFoundation)
import CoreFoundation
#endif

// 解析层 ←→ 界面层的**共享语义层**。
//
// ⚠️ 分层是抄 Lucarne 的 `agent-sessions`（PRD 附录 B.4）：Claude 专属的原始 JSON
// （`message.content[]` 里那些块）只活在解析器里，这里是跟 agent 无关的语义层。
// 将来加 Codex 只用再写一个解析器，工具卡片和气泡一行不动。
//
// ⚠️ **这些类型必须住在 YxiKit 而不是 Yxi**：Yxi 依赖 YxiKit，反过来不成立，
// 而解析器在 YxiKit 里 —— 它没法产出定义在 Yxi 里的类型。

// MARK: - 不固定结构的 JSON

/// 工具的 `input` 和 Claude Code 自己存的 `toolUseResult`，**结构随工具而变**，
/// 没法提前定成 struct。用一个最小的 JSON 树承载，取值全部有兜底（取不到就是空）。
///
/// ⚠️ 故意**不**让工具卡片依赖强类型的 per-tool struct：那样每加一个工具都要动数据层，
/// 而「这个工具的卡片长什么样」本来就是界面的事（PRD 附录 D.3）。
public enum JSON: Equatable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSON])
    case object([String: JSON])

    /// 从 `JSONSerialization.jsonObject(with:)` 的结果转过来。
    ///
    /// ⚠️ `CFGetTypeID` 在 **Linux 的 Foundation 里不自带**，必须显式 `import CoreFoundation`，
    /// 否则 `cannot find 'CFGetTypeID' in scope`。这一层要能在 Linux 上 `swift test`
    /// （解析器的测试数据是真实转录，跑测试不该需要一台 Mac），所以这个 import 不能省。
    public init(_ any: Any?) {
        switch any {
        case nil, is NSNull: self = .null
        case let n as NSNumber:
            // ⚠️ Bool 在 Foundation 里也是 NSNumber，先认它，否则 true 会变成 1
            if CFGetTypeID(n) == CFBooleanGetTypeID() { self = .bool(n.boolValue) }
            else { self = .number(n.doubleValue) }
        case let s as String: self = .string(s)
        case let a as [Any]: self = .array(a.map(JSON.init))
        case let o as [String: Any]: self = .object(o.mapValues(JSON.init))
        default: self = .null
        }
    }

    /// 解析一行 JSON 文本。坏行返回 nil —— `tail -f` 追一个正在写的文件时
    /// **一定会读到半行**，那不是错误，是常态。
    public static func parse(line: String) -> JSON? {
        guard let data = line.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        else { return nil }
        return JSON(obj)
    }

    public subscript(key: String) -> JSON {
        if case let .object(o) = self { return o[key] ?? .null }
        return .null
    }

    public subscript(index: Int) -> JSON {
        if case let .array(a) = self, a.indices.contains(index) { return a[index] }
        return .null
    }

    public var string: String { if case let .string(s) = self { return s }; return "" }
    public var int: Int { if case let .number(n) = self { return Int(n) }; return 0 }
    public var int64: Int64 { if case let .number(n) = self { return Int64(n) }; return 0 }
    public var double: Double { if case let .number(n) = self { return n }; return 0 }
    public var bool: Bool { if case let .bool(b) = self { return b }; return false }
    public var array: [JSON] { if case let .array(a) = self { return a }; return [] }
    public var exists: Bool { self != .null }
    /// 是不是一个对象。⚠️ `toolUseResult` 可能是**字符串**（用户拒绝工具时会退化成
    /// `"User rejected tool use"`），也可能整个不存在 —— 富渲染只在它是对象时才成立。
    public var isObject: Bool { if case .object = self { return true }; return false }
    /// 对象的键，顺序不保证（这里排了序，好让兜底摘要每次一样）。
    public var keys: [String] { if case let .object(o) = self { return Array(o.keys).sorted() }; return [] }
}

// MARK: - 转录里的一条

/// 已经归一成「界面能直接渲染的东西」的一条内容。
public enum ChatItem: Identifiable, Equatable, Sendable {
    case user(id: String, text: String)
    case assistant(id: String, markdown: String)
    /// 默认折叠 —— 实测一个会话 128 条，全展开会把正文淹掉（PRD 附录 D.2）。
    ///
    /// ⚠️ 实测 Claude Code 2.1.241：本机 `~/.claude/projects/` 全部转录里 4329 个
    /// `thinking` 块，**正文非空的 0 个**（只剩加密的 `signature`）。所以这一支
    /// 当前版本上永远拿不到内容 —— 解析器照旧过滤掉空正文，不冒空气泡。
    case thinking(id: String, text: String)
    case tool(ToolCall)
    /// **已提交、还排着队**的用户输入 —— 你趁它忙的时候打的字。
    ///
    /// ⚠️ 这个必须显示。转录里它的类型不是 `user` 而是 `queue-operation` /
    /// `attachment.queued_command`，老解析器当不认识**静默丢掉**了，
    /// 表现是「我连打了四条，手机上一条都没有」，用户会以为没发出去然后重复发。
    /// 见 TROUBLESHOOTING #72。
    case queued(id: String, text: String)
    /// 解析不出来的 content block。
    ///
    /// ⚠️ **这是兼容兜底，不是正常归宿**（Lucarne 的规矩，PRD 附录 B.4 第 2 条）。
    /// 真实样本里一旦冒出 `unknown`，**应当在同一次改动里把它提升成强类型**，
    /// 而不是让它烂在那 —— Claude Code 会不断加新块类型（`thinking` 就是后加的）。
    /// 之所以不直接丢掉：丢掉就没人知道有新类型了，而那正是 #72 的翻车方式。
    case unknown(id: String, raw: String)

    public var id: String {
        switch self {
        case let .user(id, _), let .assistant(id, _), let .thinking(id, _),
             let .queued(id, _), let .unknown(id, _):
            return id
        case let .tool(t): return t.id
        }
    }
}

public struct ToolCall: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String
    /// `tool_use.input`
    public let input: JSON
    /// 对应的 `tool_result`，来自后面某条 user 消息。还没跑完就是 nil
    public let result: String?
    public let isError: Bool
    /// Claude Code 自己存的**结构化结果**（转录行顶层的 `toolUseResult`，不是 API 内容）。
    ///
    /// ⚠️ 富渲染基本全靠它：Edit 的 `structuredPatch`、Bash 分开的 stdout/stderr、
    /// Read 的 `file.numLines`、AskUserQuestion 的 `answers`、ExitPlanMode 的最终 `plan`。
    /// 但它**可能不存在**（老转录、子 agent 转录里很常见），也可能退化成一个字符串
    /// （用户拒绝时就是 `"User rejected tool use"`）—— 所以每条渲染路径都要有降级。
    /// **解析器只在它确实是对象时填这里**，别处就不用再判类型了。
    public let meta: JSON

    public init(id: String, name: String, input: JSON, result: String? = nil,
                isError: Bool = false, meta: JSON = .null) {
        self.id = id; self.name = name; self.input = input
        self.result = result; self.isError = isError; self.meta = meta
    }
}

// MARK: - 只有屏幕知道的两件事

/// 此刻它在忙什么。
///
/// ⚠️ **状态词只有屏幕有。** `✽ Scampering… (4m 48s · ↓ 10.2k tokens)` 纯粹是 TUI 渲染，
/// 永远不落转录。而它恰恰是「Claude 还活着、正在干活」的唯一信号 ——
/// 没有它，一个跑了五分钟的工具调用在手机上看起来就是界面卡死了。
public struct Live: Equatable, Sendable {
    public let busy: Bool
    /// 状态词原文，如 `Scampering… (4m 48s · ↓ 10.2k tokens)`。不忙时 nil。
    public let status: String?
    public static let idle = Live(busy: false, status: nil)
    public init(busy: Bool, status: String?) { self.busy = busy; self.status = status }
}

/// **此刻正在等你答**的那个选择器。
///
/// ⚠️ 为什么不读转录：实测 Claude Code 的 `tool_use` 块**要等工具跑完才写进 JSONL**，
/// 也就是「问题挂在那儿等你」的那段时间转录里什么都没有（TROUBLESHOOTING #28）。
/// 分工：**转录是权威的历史，屏幕是唯一的「此刻」。**
public struct Pending: Equatable, Sendable {
    public let title: String
    public let options: [Option]
    /// 多选：数字只是切换勾选，要 `Right` + `1` 才算交卷
    public let multiSelect: Bool
    /// 整块提示的指纹（问题正文 + 上面几行上下文 + 所有选项）。
    ///
    /// ⚠️ 只比「几号 + 选项文案」挡不住过期：两个不同的权限提示选项**一模一样**
    /// （`1. Yes / 2. Yes, and always… / 3. No`，标题也都是 `Do you want to proceed?`）。
    /// 你在终端里答掉了 A、屏幕换成了 B，那种比对照样放行 —— 你以为在批 A，实际批的是 B。
    /// 见 TROUBLESHOOTING #53。
    public let fingerprint: String
    /// 多问题时顶上那条标签栏 `←  ☐ 名字  ☒ 配色  ✔ Submit  →`。只有一个问题时为空。
    /// 有它就说明**可以用 ←/→ 在问题之间来回走**（含回上一题改选择）。
    public let tabs: [Tab]
    /// 当前是不是「Review your answers / Submit answers」那一页。
    public let review: Bool

    /// 标签栏里的一格。`answered` 来自 ☒（答过）/ ☐（还没答）。
    public struct Tab: Equatable, Sendable {
        public let label: String
        public let answered: Bool
        public let submit: Bool
        public init(label: String, answered: Bool, submit: Bool = false) {
            self.label = label; self.answered = answered; self.submit = submit
        }
    }

    public struct Option: Equatable, Identifiable, Sendable {
        /// 屏幕上那个数字。**送键就送它**，不是列表下标 —— 一旦两者对不上就会
        /// **点 A 选中 B 且不报错**（TROUBLESHOOTING #47：「拒绝」是 3 不是 2）。
        public let number: Int
        public let label: String
        public let description: String
        /// 多选时当前是否已勾上
        public let checked: Bool
        public var id: Int { number }
        public init(number: Int, label: String, description: String = "", checked: Bool = false) {
            self.number = number; self.label = label
            self.description = description; self.checked = checked
        }
    }

    public init(title: String, options: [Option], multiSelect: Bool, fingerprint: String,
                tabs: [Tab] = [], review: Bool = false) {
        self.title = title; self.options = options
        self.multiSelect = multiSelect; self.fingerprint = fingerprint
        self.tabs = tabs; self.review = review
    }
}

// MARK: - 稳定哈希

/// FNV-1a，16 进制。
///
/// ⚠️ **不能用 Swift 自带的 `hashValue`。** 它每个进程随机加盐，同一段文本
/// 这次启动和下次启动算出来不一样。而 [Pending.fingerprint] 会跟着通知一路带到
/// 「用户几分钟后点了通知上的按钮」那一刻 —— 那时候 App 很可能已经被系统杀过一轮。
/// 用带盐的哈希，重启之后**每一个提示都会判成「提示变了」**，按钮永远按不动。
@inlinable
func stableHash(_ s: String) -> String {
    var h: UInt64 = 0xcbf2_9ce4_8422_2325
    for b in s.utf8 {
        h ^= UInt64(b)
        h = h &* 0x0000_0100_0000_01B3
    }
    return String(h, radix: 16)
}

extension String {
    /// 空串或者全是空白。转录里 `text` / `thinking` 块是空串的情况很常见，
    /// 冒成一个空气泡比不显示更糟。
    /// （YxiKit 内部可见 —— 别在这个 target 里再定义一份同名的。）
    var isBlank: Bool { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}
