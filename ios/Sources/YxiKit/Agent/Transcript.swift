import Foundation

/// Claude Code 转录（JSONL）的解析器。
///
/// ⚠️ **读转录文件，不刮终端屏幕**（PRD 附录 D.1）。终端里 TUI 会重绘、有 spinner、
/// 会折行，刮屏必然脆；转录是结构化的、权威的。
/// 屏幕只用来拿转录里**真的没有**的东西：状态词和「正在等你选」（见 [Prompt] / [Live]）。
public enum Transcript {

    /// 解析若干行 JSONL。工具结果会就地合并进对应的 [ToolCall]。
    ///
    /// ⚠️ **必须能在后台线程调**：一次可能几百行。这里是纯函数，没有共享状态。
    public static func parse(_ lines: [String]) -> [ChatItem] {
        var out: [ChatItem] = []
        var calls: [String: Int] = [:]        // tool_use_id → out 里的下标
        var queued: [String] = []             // 还排着队的输入，出队就删（保持进队顺序）
        var processed = Set<String>()         // 已经被消化掉的排队输入（见下面 #76 那段）

        for (index, line) in lines.enumerated() {
            if line.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { continue }
            guard let d = JSON.parse(line: line), d.isObject else { continue }
            // 侧链（子 agent 的内部对话）不进主时间线，否则会把主线淹掉。
            // ⚠️ 2.1.241 起子 agent 另存到 `<会话>/subagents/*.jsonl`，主文件里已经没有
            // `isSidechain: true` 了。这个判断留着是给**老转录**用的 —— 它一行的成本，
            // 而漏了的话老会话的主线会被子 agent 的独白淹掉。
            if d["isSidechain"].bool { continue }

            let type = d["type"].string

            // ⚠️ **排队的输入没有 message 字段**，得在下面那个 guard 之前接住。
            //   {"type":"queue-operation","operation":"enqueue","content":"…"}  进队
            //   {"type":"queue-operation","operation":"remove", "content":"…"}  出队
            //   {"type":"attachment","attachment":{"type":"queued_command","prompt":"…"}}  真正被处理
            // 处理之后才算进了对话，所以那时候才当普通用户消息发出去。见 TROUBLESHOOTING #72。
            if type == "queue-operation" {
                let c = d["content"].string
                // ⚠️ 实测 2.1.241：出队的 operation 是 **`dequeue` 而且不带 `content`**
                // （更早的转录里是 `remove` + content，两种都真实存在）。
                // `remove` 这条快路径留着，但**靠得住的出队判据是最后那条「它有没有真的
                // 进过对话」** —— 光认某一个转换事件是 #76 的翻车方式。
                if !c.isEmpty {
                    switch d["operation"].string {
                    case "enqueue":
                        // ⚠️ **enqueue 不一定是人打的。** 子 agent / 后台命令跑完时，
                        // Claude Code 会把一整块 `<task-notification>…` 也 enqueue 进来
                        // （真机转录里抓到的）。照原样显示就是在用户脸上糊一段内部 XML，
                        // 而且是「你排队的输入」的口吻 —— 他根本没打过这句话。
                        // 这个前缀是白名单式的：以后再冒出别的系统注入，往这加。
                        if !c.hasPrefix("<task-notification>") && !queued.contains(c) {
                            queued.append(c)
                        }
                    case "remove": queued.removeAll { $0 == c }
                    default: break
                    }
                }
                continue
            }
            if type == "attachment" {
                let a = d["attachment"]
                if a["type"].string == "queued_command" {
                    let p = a["prompt"].string
                    if !p.isEmpty {
                        // ⚠️ **不管是不是人打的，`queued_command` 都代表「这条排队的输入
                        // 已经被处理」**，所以一律记进「已消化」。只认 human 那些的话，
                        // 系统注入的那条会永远挂在「排队中」—— 它永远不会以 `user` 消息
                        // 出现，于是 #76 那条终态规则也救不了它。
                        processed.insert(p.trimmingCharacters(in: .whitespacesAndNewlines))
                        // origin.kind 不是 human 的是系统注入的，不该显示成用户说的话
                        if a["origin"]["kind"].string == "human" {
                            out.append(.user(id: idOf(d, index), text: p))
                        }
                    }
                }
                continue
            }

            // 非消息行（mode / file-history-snapshot / ai-title / system 之类）静默跳过
            let msg = d["message"]
            guard msg.isObject else { continue }
            let id = idOf(d, index)

            switch type {
            case "user": parseUser(msg, meta: d["toolUseResult"], id: id, calls: calls, out: &out)
            case "assistant": parseAssistant(msg, id: id, calls: &calls, out: &out)
            default: break
            }
        }

        // ⚠️ **出队的判据是「这句话有没有真的作为用户消息出现过」，不是 remove。**
        // 实测一个真实会话：35 个 enqueue 只有 29 个 remove，剩下 13 条全都后来
        // 以普通 `user` 消息出现了 —— 它们**早就被处理完了**，只是 Claude Code
        // 走的不是 remove 那条路径（remove 只在「当前这一轮里出队」时才写）。
        // 只认 remove 的话，那 13 条会永远挂在「排队中」，而对应的命令几小时前就跑完了。
        // 见 TROUBLESHOOTING #76：**状态机不要只认「关」的那个事件，要认终态本身。**
        var said = processed
        for item in out {
            if case let .user(_, text) = item {
                said.insert(text.trimmingCharacters(in: .whitespacesAndNewlines))
            }
        }
        for t in queued where !said.contains(t.trimmingCharacters(in: .whitespacesAndNewlines)) {
            // id 用文本的稳定哈希而不是下标：出队一条时后面那些的下标会整体前移，
            // 用下标的话 SwiftUI 会把「删一条」渲染成「整列全变了」。
            out.append(.queued(id: "queued-" + stableHash(t), text: t))
        }
        return out
    }

    /// ⚠️ 兜底用行号而不是 `hashValue`：后者每个进程随机加盐，
    /// 同一批行两次解析出来的 id 会不一样，SwiftUI 会把整列判成全新的然后整屏重画。
    private static func idOf(_ d: JSON, _ index: Int) -> String {
        let uuid = d["uuid"].string
        if !uuid.isEmpty { return uuid }
        let req = d["requestId"].string
        if !req.isEmpty { return req }
        return "line-\(index)"
    }

    private static func parseUser(
        _ msg: JSON, meta: JSON, id: String,
        calls: [String: Int], out: inout [ChatItem]
    ) {
        switch msg["content"] {
        case let .string(s):
            if !s.isBlank { out.append(.user(id: id, text: s)) }
        case let .array(blocks):
            for (i, b) in blocks.enumerated() {
                switch b["type"].string {
                case "text":
                    let t = b["text"].string
                    if !t.isBlank { out.append(.user(id: "\(id)-\(i)", text: t)) }
                case "tool_result":
                    // 工具结果不单独成条，合并回它对应的工具卡片
                    guard let at = calls[b["tool_use_id"].string],
                          case let .tool(call) = out[at] else { continue }
                    out[at] = .tool(ToolCall(
                        id: call.id, name: call.name, input: call.input,
                        result: flatten(b["content"]),
                        isError: b["is_error"].bool,
                        // ⚠️ 只有它确实是对象时才存 —— 它可能是字符串
                        // （`"User rejected tool use"`）或者压根不存在。
                        meta: meta.isObject ? meta : .null
                    ))
                default: break
                }
            }
        default: break
        }
    }

    private static func parseAssistant(
        _ msg: JSON, id: String,
        calls: inout [String: Int], out: inout [ChatItem]
    ) {
        for (i, b) in msg["content"].array.enumerated() {
            let key = "\(id)-\(i)"
            switch b["type"].string {
            case "text":
                let t = b["text"].string
                if !t.isBlank { out.append(.assistant(id: key, markdown: t)) }
            case "thinking":
                let t = b["thinking"].string
                if !t.isBlank { out.append(.thinking(id: key, text: t)) }
            case "tool_use":
                calls[b["id"].string] = out.count
                out.append(.tool(ToolCall(
                    id: key, name: b["name"].string,
                    input: b["input"].isObject ? b["input"] : .object([:])
                )))
            default:
                out.append(.unknown(id: key, raw: b["type"].string))
            }
        }
    }

    /// `tool_result.content` 可能是字符串，也可能是块数组。都压成一段文本。
    private static func flatten(_ content: JSON) -> String {
        switch content {
        case let .string(s): return s
        case let .array(a): return a.map { $0["text"].string }.filter { !$0.isEmpty }.joined(separator: "\n")
        case .null: return ""
        default: return ""
        }
    }

    /// cwd → Claude Code 的项目目录名：**凡不是 ASCII 字母或数字的字符，一律换成横杠**。
    ///
    /// ⚠️ 一开始只换了斜杠，中文路径就找不到转录了 —— 实测
    /// `/opt/workspace/日常对话` 对应的目录是 `-opt-workspace-----`（四个汉字四个横杠），
    /// 不是 `-opt-workspace-日常对话`。点号、空格、横杠本身同理。见 TROUBLESHOOTING #36。
    ///
    /// ⚠️ 数的是 **UTF-16 码元**不是 Swift 的 `Character`。规则那头是 JS 的
    /// `replace(/[^a-zA-Z0-9]/g, '-')`，JS 字符串按 UTF-16 走 —— 一个 emoji 在那边是
    /// **两**个横杠。按 `Character` 数会少一个，于是路径带 emoji 的会话「找不到转录」，
    /// 而且是静默的。（汉字在 BMP 里，两种数法一样，所以这个坑要等有人用 emoji 才炸。）
    public static func projectDirOf(_ cwd: String) -> String {
        var out = ""
        out.reserveCapacity(cwd.utf16.count)
        for u in cwd.utf16 {
            let isAlnum = (u >= 97 && u <= 122) || (u >= 65 && u <= 90) || (u >= 48 && u <= 57)
            out.append(isAlnum ? Character(Unicode.Scalar(UInt8(u))) : "-")
        }
        return out
    }
}

/// 把远端的转录流回来要用的几条命令。
///
/// ⚠️ **服务器上不需要装任何东西** —— `ls` 和 `tail` 是系统自带的。
/// 这里只出命令和解析，真正跑 SSH 的是数据层（`ChatBackend`）：
/// 那样这一层才能在 Linux 上跑测试，不用起一台真机。
public enum TranscriptStream {

    /// 找某个 cwd 对应的最新转录文件。
    ///
    /// ⚠️ 通配符只有一层（`<项目>/*.jsonl`），不递归 —— 2.1.241 起子 agent 的转录在
    /// `<会话uuid>/subagents/` 下面，递归的话会挑到子 agent 那份，主线一条都不显示。
    public static func latestCommand(cwd: String) -> String {
        "ls -t \"$HOME/.claude/projects/\(projectDir(cwd))\"/*.jsonl 2>/dev/null | head -1"
    }

    /// [latestCommand] 的输出 → 文件路径。没有就是 nil（这个会话没跑过 Claude Code，
    /// 对话模式该置灰**并说明原因**，不能不明不白地消失）。
    public static func parseLatest(_ out: String) -> String? {
        let s = out.trimmingCharacters(in: .whitespacesAndNewlines)
        return (s.isEmpty || !s.hasSuffix(".jsonl")) ? nil : s
    }

    /// `tail` 出最后 backlog 行然后持续跟随：**先给历史再跟随**，
    /// 这样一打开界面就有内容，不用等 Claude 下一次说话。
    ///
    /// ⚠️ 它会**一次性把历史吐完**（几百行落在同一瞬间）然后才阻塞等新行。
    /// 消费端的节流必须有尾随刷新，否则只有第一行被处理、剩下的全丢，
    /// 表现成「忙的会话正常、闲的会话永远空白」（TROUBLESHOOTING #35）。
    public static func followCommand(file: String, backlog: Int = 800) -> String {
        "tail -n \(backlog) -f '\(file.replacingOccurrences(of: "'", with: "'\\''"))'"
    }

    private static func projectDir(_ cwd: String) -> String { Transcript.projectDirOf(cwd) }
}
