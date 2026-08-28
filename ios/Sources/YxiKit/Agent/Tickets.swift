import Foundation

/// **工单中心** —— 用 App 的人随手记下「哪里不好用」，落在**连着的那台服务器**上，
/// 开发那边直接 `cat ~/.yxi/tickets.jsonl` 查阅。跟安卓版同一份文件、同一种格式。
///
/// ⚠️ **为什么存服务器而不是手机本地**：存本地只有本人看得见，等于没提。
/// ⚠️ **为什么不建公网接口**：Yxi 是自托管、无云后端的；开公网 POST 要防刷、要考虑隐私，
/// 跟「不依赖任何第三方」相冲。走已有的 SSH 通道零新基建。
/// ⚠️ **代价**：APK/IPA 分享给别人后，他们的工单落在**他们自己的服务器**上，我们看不到。
///
/// 一行一条 JSON（JSONL），只追加不改写：坏了也只坏一行。
public enum Tickets {
    public static let file = "$HOME/.yxi/tickets.jsonl"

    public struct Ticket: Equatable, Identifiable, Sendable {
        public let at: Double
        public let text: String
        /// 提的时候 App 是哪个版本 —— 没这个回头根本对不上是哪版的毛病
        public let version: String
        public let device: String
        public var id: String { "\(at)-\(text.hashValue)" }
        public init(at: Double, text: String, version: String = "", device: String = "") {
            self.at = at; self.text = text; self.version = version; self.device = device
        }
    }

    /// 把一行 JSON 安全地塞进单引号 shell 字符串。
    /// ⚠️ 用户会在工单里写各种字符（引号、`$`、反引号、换行）——
    /// 不处理就要么写坏文件，要么被当命令执行。单引号里只有 `'` 需要转义。
    public static func shellSingleQuote(_ s: String) -> String {
        "'" + s.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }

    /// 生成「提一条」的命令。
    public static func addCommand(_ t: Ticket) -> String {
        let obj: [String: Any] = [
            "at": Int(t.at), "text": String(t.text.prefix(2000)),
            "version": t.version, "device": t.device, "status": "open",
        ]
        let data = (try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys]))
            ?? Data("{}".utf8)
        let line = String(decoding: data, as: UTF8.self)
        // ⚠️ printf '%s\n' 而不是 echo —— echo 对反斜杠的处理各家 shell 不一样
        return "mkdir -p $HOME/.yxi && printf '%s\\n' \(shellSingleQuote(line)) >> \(file)"
    }

    public static let listCommand = "tail -n 200 \(Tickets.file) 2>/dev/null"

    /// 读回来，最新的在前。坏行跳过。
    public static func parse(_ raw: String) -> [Ticket] {
        raw.components(separatedBy: "\n").compactMap { line -> Ticket? in
            guard let o = JSON.parse(line: line), o.isObject else { return nil }
            let text = o["text"].string
            guard !text.isEmpty else { return nil }
            return Ticket(at: o["at"].double, text: text,
                          version: o["version"].string, device: o["device"].string)
        }.reversed()
    }
}
