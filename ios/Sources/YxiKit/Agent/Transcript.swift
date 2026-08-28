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
                switch d["operation"].string {
                case "enqueue":
                    // ⚠️ **enqueue 不一定是人打的。** 子 agent / 后台命令跑完时，
                    // Claude Code 会把一整块 `<task-notification>…` 也 enqueue 进来
                    // （真机转录里抓到的）。照原样显示就是在用户脸上糊一段内部 XML，
                    // 而且是「你排队的输入」的口吻 —— 他根本没打过这句话。
                    // 这个前缀是白名单式的：以后再冒出别的系统注入，往这加。
                    // ⚠️ 系统注入的那些**照样占位**（跟空 content 一个道理），
                    // 只在展示时滤掉 —— 在入队时就扔掉会让 FIFO 错位，dequeue 弹错人。
                    // ⚠️⚠️ **不能去重。** 队列就是队列：连着排两条一样的话是两条，
                    // 合成一条**就是真的丢消息**。而且下面 `dequeue` 是按先进先出弹队头，
                    // 少一个位置就会弹错人。
                    // ⚠️ **空 content 也要占位**（老格式的 enqueue 就没有 content），
                    // 展示时再把空的滤掉。
                    queued.append(c)
                case "remove", "popAll":
                    // ⚠️ `popAll` 是实测才发现的第四种：在 TUI 里按 ↑ 会把排队的
                    // **全部收回输入框**，每收一条写一条 popAll。漏掉它的后果是
                    // 用户撤回之后那几条「排队中」气泡**再也不会消失**。
                    if c.isEmpty { if !queued.isEmpty { queued.removeFirst() } }
                    else { if let i = queued.firstIndex(of: c) { queued.remove(at: i) } }
                case "dequeue":
                    // ⚠️ **`dequeue` 从来不带 content**（安卓侧实测 1094 条，一条都没有），
                    // 所以只能按先进先出弹队头 —— 这也正是队列本来的语义。
                    //
                    // ⚠️ 漏掉这一支的后果是**排队气泡永远不消失**：斜杠命令
                    // （比如打错的 `/modle`）被本地消化掉，既不写 remove、
                    // 也永远不会作为 user 消息出现，「出现过就算说过」那条兜底也救不了它。
                    if !queued.isEmpty { queued.removeFirst() }
                default: break
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
                        //
                        // ⚠️ 变异测试显示这一句去掉之后测试**不会红** —— 因为上面
                        // 「`<task-notification>` 不进队」那条把当前版本已知的唯一一种
                        // 系统注入挡在门外了。别据此删掉它：那条是**字符串白名单**，
                        // 下一个版本换个标签名就失效；这一句是**结构性**的，
                        // 任何「被处理过」的排队都会被它清掉。#76 的教训就是
                        // 「别只认某一个转换事件」，留着这层兜底是有意的。
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

            // ⚠️ **API 报错不能当正文渲染。** 它走的是普通消息那条路，
            // 不认的话屏幕上会冒出一段像是 Claude 说的话（其实是
            // 「Request timed out」之类），用户会当成回答（安卓 #105）。
            if d["isApiErrorMessage"].bool {
                let t = flatten(msg["content"])
                if !t.isBlank { out.append(.apiError(id: id, text: t)) }
                continue
            }

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
        // ⚠️ 空的那些是**占位用的**（老格式的 enqueue 不带 content，
        // 少了它们 dequeue 弹队头会弹错人），到这一步才滤掉，别在入队时滤。
        // id 里带上序号：**同一句话可以排两次**，不带序号两条会撞成一条。
        for (i, t) in queued.enumerated()
        where !t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !t.hasPrefix("<task-notification>")
            && !said.contains(t.trimmingCharacters(in: .whitespacesAndNewlines)) {
            out.append(.queued(id: "queued-\(i)-" + stableHash(t), text: t))
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

    /// 这条「用户消息」到底是不是人打的。
    ///
    /// ⚠️ 队友消息、子 agent 回报、系统提醒、斜杠命令的输出**都走用户消息这条路**。
    /// 不分开的话，屏幕上会顶着「你说的话」的气泡显示一坨 XML ——
    /// 而用户根本没打过那句话（安卓 #87：实测一个会话里 33 处）。
    private static func userOrInjected(id: String, text: String) -> ChatItem {
        guard let inj = injectedOf(text) else { return .user(id: id, text: text) }
        return .injected(id: id, label: inj.label, from: inj.from, text: clean(text))
    }

    /// 注入进来的那段里常常带 ANSI 转义（命令输出尤其），照原样显示是一串乱码。
    /// ⚠️ 用普通字符串让 Swift 先把 ESC 插进去 —— raw string 会把 `\u{1B}`
    /// 原样交给 ICU，按它自己的语法解释会吃掉整段（TROUBLESHOOTING #136）。
    static func clean(_ s: String) -> String {
        s.replacingOccurrences(of: "\u{1B}\\[[0-9;?]*[A-Za-z]", with: "",
                               options: .regularExpression)
    }

    private static func parseUser(
        _ msg: JSON, meta: JSON, id: String,
        calls: [String: Int], out: inout [ChatItem]
    ) {
        switch msg["content"] {
        case let .string(s):
            if !s.isBlank { out.append(userOrInjected(id: id, text: s)) }
        case let .array(blocks):
            for (i, b) in blocks.enumerated() {
                switch b["type"].string {
                case "text":
                    let t = b["text"].string
                    if !t.isBlank { out.append(userOrInjected(id: "\(id)-\(i)", text: t)) }
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

// MARK: - 本对话的模型 / 模式 / 上下文用量

extension Transcript {

    /// 顶栏那一行「模型 · 模式 · 上下文」。跟安卓 `Transcript.Ctx` 逐条对齐。
    public struct Ctx: Equatable, Sendable {
        public let tokens: Int64
        /// ⚠️ `var`：`/model` 的回执会覆盖它（切了模型但还没回话的那一段）
        public var model: String
        /// 思考强度：`max` / `high` / `mid`。⚠️ 在转录行的**顶层**，不在 message 里。
        public var effort: String = ""
        /// 会话模式，来自 `{"type":"mode",…}` 行。空或 normal = 不用显示。
        public var mode: String = ""
        /// ponytail 插件的强度（`lite`/`full`/`ultra`）。
        /// ⚠️ **不一定读得到**：它只在会话开始/换模式时注入。读不到就空着 ——
        /// 宁可不显示也不显示假的。
        public var ponytail: String = ""

        public init(tokens: Int64, model: String, effort: String = "",
                    mode: String = "", ponytail: String = "") {
            self.tokens = tokens; self.model = model
            self.effort = effort; self.mode = mode; self.ponytail = ponytail
        }
    }

    /// 顺路从同一批转录行里读出来，**不额外跑一趟服务器**。
    public static func context(_ lines: [String]) -> Ctx? {
        var last: Ctx?
        var lastMode = ""
        var lastPony = ""

        for line in lines where !line.trimmingCharacters(in: .whitespaces).isEmpty {
            // ponytail 的强度只在它注入的那段文字里。
            // ⚠️ 直接在**原始行**上找，别去钻 JSON 结构 —— 那是插件的实现细节，会变。
            if line.contains("PONYTAIL MODE"), let lv = ponytailLevel(line) {
                lastPony = lv
                if var l = last { l.ponytail = lv; last = l }
            }
            guard let d = try? JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any] else { continue }
            // 侧链（子 agent 的内部对话）不算主线
            if d["isSidechain"] as? Bool == true { continue }
            let type = d["type"] as? String ?? ""
            if type == "mode" { lastMode = d["mode"] as? String ?? lastMode; continue }
            guard let msg = d["message"] as? [String: Any] else { continue }

            switch type {
            case "user":
                // ⚠️ **切完模型、但它还没回话时，标签也得跟着变。** 顶栏的模型名来自
                // 最后一条 assistant 消息，切换不会改写旧消息 —— 不认这一步的话，
                // 用户切了模型还看见旧名字，会以为没切成（安卓那边用户真报过）。
                if let name = modelSwitch(msg), var l = last { l.model = name; last = l }
            case "assistant":
                guard var c = usageCtx(msg) else { break }
                // ⚠️ `effort` 在**转录行顶层**（`d`），不在 message 里 —— 找错地方永远是空
                c.effort = d["effort"] as? String ?? ""
                c.mode = lastMode
                c.ponytail = lastPony
                last = c
            default: break
            }
        }
        return last
    }

    /// ⚠️ **三项都要加**：`input_tokens` 是这轮新增的、`cache_creation` 是写进缓存的、
    /// `cache_read` 是命中缓存复用的 —— 合起来才是模型这轮实际看到的量。
    /// 只看 `input_tokens` 的话，一个 65 万 token 的会话会显示成「1」（实测就是 1）。
    private static func usageCtx(_ msg: [String: Any]) -> Ctx? {
        guard let u = msg["usage"] as? [String: Any] else { return nil }
        func n(_ k: String) -> Int64 { (u[k] as? NSNumber)?.int64Value ?? 0 }
        let total = n("input_tokens") + n("cache_creation_input_tokens") + n("cache_read_input_tokens")
        guard total > 0 else { return nil }
        return Ctx(tokens: total, model: msg["model"] as? String ?? "")
    }

    /// `PONYTAIL MODE ACTIVE — level: full` / `… CHANGED — level: ultra`
    /// ⚠️ 破折号是 em dash，别写死；等级偶尔是空的，那就匹配不上，正好跳过。
    private static func ponytailLevel(_ line: String) -> String? {
        guard let re = try? NSRegularExpression(
            pattern: #"PONYTAIL MODE [A-Z]+[^:]*level:\s*([A-Za-z]+)"#) else { return nil }
        let ns = line as NSString
        guard let m = re.firstMatch(in: line, range: NSRange(location: 0, length: ns.length)),
              m.numberOfRanges > 1 else { return nil }
        return ns.substring(with: m.range(at: 1)).lowercased()
    }

    /// `/model` 的回执：`Set model to Opus 5 (1M context) for this session only`。
    ///
    /// ⚠️ `<` 也是终止符：回执包在 `<local-command-stdout>…</local-command-stdout>` 里，
    /// 不拦的话闭合标签会被吃进模型名（安卓的测试抓出来的）。模型名里不会有 `<`。
    /// ⚠️ 结果还要去掉 `[1m]` 这类 ANSI 残留。
    static func modelSwitch(_ msg: [String: Any]) -> String? {
        let text: String
        if let s = msg["content"] as? String { text = s }
        else if let arr = msg["content"] as? [[String: Any]] {
            text = arr.compactMap { $0["text"] as? String }.joined(separator: " ")
        } else { return nil }
        guard text.contains("Set model to"),
              let re = try? NSRegularExpression(
                pattern: #"Set model to\s+(.+?)\s*(?:for this session|and saved|<|$)"#)
        else { return nil }
        let ns = text as NSString
        guard let m = re.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)),
              m.numberOfRanges > 1 else { return nil }
        let raw = ns.substring(with: m.range(at: 1))
        // ⚠️ **这里绝不能用 raw string**（`#"\u{1B}…"#`）：那样 ICU 拿到的是字面量
        // `\u{1B}`，它会按自己的语法解释，结果**把整个模型名都吃掉**（实测返回空串）。
        // 必须让 Swift 先把 ESC 插进去，正则里只留 `\[[0-9;]*m`。
        let cleaned = raw.replacingOccurrences(
            of: "\u{1B}?\\[[0-9;]*m", with: "", options: .regularExpression)
        let out = cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
        return out.isEmpty ? nil : out
    }
}

// MARK: - 注入内容 / API 报错

extension Transcript {

    /// 这些标签都是**别人塞进来的**，不是用户打的。跟安卓 `INJECTED` 同一张表。
    static let injectedTags: [(tag: String, label: String)] = [
        ("teammate-message", "队友消息"),
        ("agent-message", "子 agent 消息"),
        ("cross-session-message", "跨会话消息"),
        ("task-notification", "任务通知"),
        ("system-reminder", "系统提醒"),
        ("local-command-caveat", "系统提醒"),
        ("local-command-stdout", "命令输出"),
        ("command-name", "斜杠命令"),
    ]

    /// 认出来就返回（这是什么、谁发的）。
    ///
    /// ⚠️ **「谁发的」属性名不止一个**：子 agent 用 `from=`，队友消息用 `teammate_id=`，
    /// 跨会话用 `agent_id=`。只认 `from` 的话队友那栏永远是空的
    /// （安卓侧这条是测试抓出来的）。
    public static func injectedOf(_ text: String) -> (label: String, from: String?)? {
        for (tag, label) in injectedTags {
            guard let i = text.range(of: "<" + tag) else { continue }
            let rest = text[i.upperBound...]
            return (label, firstAttribute(in: String(rest)))
        }
        return nil
    }

    private static let fromAttr = try! Regex(#"(?:from|teammate_id|agent_id)="([^"]+)""#)

    private static func firstAttribute(in s: String) -> String? {
        guard let m = s.firstMatch(of: fromAttr), let v = m[1].substring else { return nil }
        return String(v)
    }
}
