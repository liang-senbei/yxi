import Foundation

/// 把用户在「地址」栏里敲进来的东西弄成能连的形式。
///
/// ⚠️ **这不是吹毛求疵，是真的连不上。** 安卓那边为这件事单开过一条坑
/// （TROUBLESHOOTING #58）：用户填 `216.36.108.147`，App 报「地址解析不了」，
/// 而他盯着那串数字**觉得完全正确** —— 因为中文输入法出的是全角 `．`，
/// 跟半角 `.` 长得几乎一样。
///
/// **iOS 上这个坑一模一样，甚至更容易踩**：系统中文键盘在中文模式下同样出全角标点，
/// 第三方键盘（搜狗/百度）还会插零宽字符；从「备忘录」「微信」复制过来的地址
/// 常常带 `root@`、`:22`、`ssh://`，以及前后的空格换行。
///
/// ⚠️ **打字过程中只做 [normalize]，[parse] 留到「保存」那一下。**
/// 安卓 #59 踩过：边输边把 `user@host:port` 拆进三个栏位，用户打到
/// `216.36.108.147:22` 那一刻 `22` 被切走当端口，剩下的落回地址栏变成
/// `216.36.108.14722`。**iOS 上更躲不掉** —— 中文候选词是整词上屏的，
/// `onChange` 拿到的永远是成块文本，「是不是粘贴」这种启发式根本不成立。
/// 规矩：**打字时只做幂等且不改结构的归一，拆分等到保存。**
public enum HostInput {

    public struct Parsed: Equatable, Sendable {
        public let host: String
        public let port: Int?
        public let user: String?

        public init(host: String, port: Int?, user: String?) {
            self.host = host
            self.port = port
            self.user = user
        }
    }

    /// 全角 → 半角；顺带清掉零宽字符和所有空白。
    ///
    /// **幂等**（`normalize(normalize(x)) == normalize(x)`），所以放进每次按键的回调里也安全。
    public static func normalize(_ raw: String) -> String {
        var out = String.UnicodeScalarView()
        for u in raw.unicodeScalars {
            switch u.value {
            // 零宽字符：**肉眼完全看不见**，只能靠这里扫掉，否则用户永远查不出为什么
            case 0x200B, 0x200C, 0x200D, 0xFEFF:
                continue
            case _ where CharacterSet.whitespacesAndNewlines.contains(u):
                continue   // 含全角空格 U+3000
            // 全角 ASCII 区（！U+FF01 … ～U+FF5E）整段平移 0xFEE0：
            // ．：＠－ 和全角数字字母**一次全覆盖**，不用一个个列
            case 0xFF01...0xFF5E:
                out.append(Unicode.Scalar(UInt8(u.value - 0xFEE0)))
            // 下面这几个不在那一段里，得单列
            case 0x3002, 0xFF61:                  // 。｡
                out.append(".")
            case 0x2010, 0x2013, 0x2014, 0x2212:  // ‐ – — −
                out.append("-")
            default:
                out.append(u)
            }
        }
        return String(out)
    }

    /// 从一坨输入里拆出 host / port / user。
    /// 认 `ssh://user@host:port`、`user@host:port`、`host:port`、裸 host；
    /// IPv6 用 `[::1]:22` 这种写法。
    public static func parse(_ raw: String) -> Parsed {
        var s = normalize(raw)
        for scheme in ["ssh://", "SSH://", "sftp://"] where s.hasPrefix(scheme) {
            s = String(s.dropFirst(scheme.count))
            break
        }
        s = String(s.prefix { $0 != "/" })   // 有人会把路径也带上

        var user: String?
        if let at = s.lastIndex(of: "@") {
            let u = String(s[s.startIndex..<at])
            user = u.isEmpty ? nil : u
            s = String(s[s.index(after: at)...])
        }

        var port: Int?
        if s.hasPrefix("["), let close = s.firstIndex(of: "]") {
            let after = s[s.index(after: close)...]
            if after.hasPrefix(":") { port = Int(after.dropFirst()) }
            s = String(s[s.index(after: s.startIndex)..<close])
        } else if s.filter({ $0 == ":" }).count == 1, let colon = s.firstIndex(of: ":") {
            // ⚠️ **只有一个冒号才当端口。** 裸 IPv6（`fe80::1`）冒号不止一个，
            // 按端口去拆会把地址切碎，而报错完全看不出是这个原因。
            let tail = String(s[s.index(after: colon)...])
            if let p = Int(tail), (1...65535).contains(p) {
                port = p
                s = String(s[s.startIndex..<colon])
            }
        }
        return Parsed(host: s, port: port, user: user)
    }

    /// 还有没有解析不了的字符？有就返回**具体是哪一个**。
    ///
    /// 只说「地址解析不了」等于没说 —— 用户看着那个地址觉得它是对的。
    /// 报错文案要能指着屏幕说「就是这个字」。
    public static func suspiciousCharacter(in host: String) -> String? {
        guard let bad = host.unicodeScalars.first(where: { $0.value > 127 }) else { return nil }
        let hex = String(bad.value, radix: 16, uppercase: true)
        let padded = String(repeating: "0", count: max(0, 4 - hex.count)) + hex
        return "「\(Character(bad))」(U+\(padded))"
    }

    /// 保存前给用户看的一行预览：**告知，但不动他的输入**（#59）。
    /// 返回 nil 表示这串东西拆出来跟他填的没区别，没必要多一行字。
    public static func splitPreview(_ raw: String) -> String? {
        let p = parse(raw)
        guard p.port != nil || p.user != nil else { return nil }
        var parts = ["地址 \(p.host)"]
        if let u = p.user { parts.append("用户名 \(u)") }
        if let n = p.port { parts.append("端口 \(n)") }
        return "保存时会拆成：" + parts.joined(separator: " · ")
    }
}
