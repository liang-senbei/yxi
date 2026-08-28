import Foundation

/// **实验室** —— 让内容从**服务器**读，而不是编进 App 里。
///
/// 病根：内建的 demo 是编进包里的，想给用户看个新设计就得重新发版、用户还得更新。
/// 解法：读连的那台机器 `~/.yxi/lab/` 下的 `manifest.json` + 素材。
/// 别的 agent 用 `yxi-lab add` 一行就能推进来，用户刷新即见 —— **不用更新 App**。
///
/// 跟安卓版**读同一个目录、同一份 manifest**，两端看到的东西完全一样。
public enum Lab {
    public static let dir = "$HOME/.yxi/lab"

    public struct Item: Identifiable, Equatable, Sendable {
        public let id: String
        public let title: String
        /// `image` / `svg` / `gif` / `video` / `html` / `note`
        public let type: String
        public let file: String
        public let desc: String
        /// 谁生成的（`yxi-lab add` 的第 5 个参数），如 "Gemini · nanobanana"。空 = 没标
        public let by: String
        /// 生成时间，unix 秒。0 = 老数据没记
        public let at: Double

        /// 归到哪个栏目（按 type）
        public var catKey: String {
            switch type {
            case "image", "svg", "gif", "video", "html": return type
            default: return "note"
            }
        }
        public init(id: String, title: String, type: String, file: String,
                    desc: String = "", by: String = "", at: Double = 0) {
            self.id = id; self.title = title; self.type = type; self.file = file
            self.desc = desc; self.by = by; self.at = at
        }
    }

    /// 栏目名。⚠️ 用户嫌「生图 / html」这种叫法不好，换成人话（跟安卓版一致）。
    public static func categoryName(_ key: String) -> String {
        switch key {
        case "image": return "图像"
        case "svg": return "矢量"
        case "gif": return "动图"
        case "video": return "视频"
        case "html": return "网页"
        default: return "文字"
        }
    }

    public static let manifestCommand = "cat \(dir)/manifest.json 2>/dev/null"

    public static func parse(manifest raw: String) -> [Item] {
        guard let arr = JSON.parse(line: raw) else { return [] }
        return arr.array.enumerated().compactMap { (i, o) -> Item? in
            guard o.isObject else { return nil }
            let title = o["title"].string
            return Item(
                id: o["id"].string.isEmpty ? "remote-\(i)" : o["id"].string,
                title: title.isEmpty ? "?" : title,
                type: o["type"].string.isEmpty ? "note" : o["type"].string,
                file: o["file"].string,
                desc: o["desc"].string,
                by: o["by"].string,
                at: o["at"].double
            )
        }
    }

    // 文件名只留安全字符（防注入；反正是我们自己写的 manifest）
    private static func safe(_ f: String) -> String {
        String(f.filter { $0.isLetter || $0.isNumber || "._-/".contains($0) })
    }

    /// 取文本素材（html / note）
    public static func textCommand(_ file: String) -> String { "cat \(dir)/\(safe(file)) 2>/dev/null" }
    /// 取二进制素材（图 / GIF）—— base64 经 exec 传回来。
    ///
    /// ⚠️ **`-w0` 是 GNU 的，BSD/macOS 的 base64 不认**（直接报错，一个字节都不吐）。
    /// 所以先试 GNU 写法，失败就退到 BSD 写法自己把换行去掉 —— 结果一样是一整行。
    /// 不这么写的话，服务器只要是 macOS/BSD，实验室的图就**全部空白且不报错**。
    public static func bytesCommand(_ file: String) -> String {
        // ⚠️⚠️ **不能加单引号。** `dir` 里是 `$HOME`，单引号里它**不展开** ——
        // 服务器上找的就成了一个字面量叫 `$HOME` 的目录，实验室的图和 GIF
        // 全部拿不到字节**而且不报错**（`2>/dev/null` 把话也吞了）。
        // 文本素材那条 `textCommand` 一直没加引号，所以 html/note 正常 ——
        // 这解释了为什么「只有图片空白」。
        // 文件名已经过 [safe] 只剩 `[字母数字._-/]`，没有需要引号挡的字符。
        let f = "\(dir)/\(safe(file))"
        return "base64 -w0 \(f) 2>/dev/null || base64 \(f) 2>/dev/null | tr -d '\\n'"
    }
    /// 删若干条 —— 调服务器上的 `yxi-lab rm`（它会连素材一起删、改 manifest）
    public static func removeCommand(ids: [String]) -> String? {
        let safeIds = ids.map { String($0.filter { c in c.isLetter || c.isNumber || ".-_".contains(c) }) }
            .filter { !$0.isEmpty }
        guard !safeIds.isEmpty else { return nil }
        return "for i in \(safeIds.joined(separator: " ")); do $HOME/.local/bin/yxi-lab rm \"$i\" >/dev/null 2>&1; done"
    }

    // 审核勾选：一行一个 id，存服务器，别的 agent 用 `yxi-lab approved` 读
    public static let approvalsFile = "$HOME/.yxi/lab-approvals.txt"
    public static let approvalsCommand = "cat \(approvalsFile) 2>/dev/null"
    public static func approveCommand(id: String, on: Bool) -> String {
        let safeId = String(id.filter { $0.isLetter || $0.isNumber || ".-_".contains($0) })
        return on
            ? "mkdir -p $HOME/.yxi && grep -qxF '\(safeId)' \(approvalsFile) 2>/dev/null || echo '\(safeId)' >> \(approvalsFile)"
            : "sed -i.bak '/^\(safeId)$/d' \(approvalsFile) 2>/dev/null || true"
    }
}
