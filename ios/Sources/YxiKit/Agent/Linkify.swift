import Foundation

/// 把对话正文里的**文件路径**改写成可点的链接，点了进文件查看器。
/// 跟安卓 `agent/Linkify.kt` 逐条对齐 —— 那边的每条注释都是踩出来的，别简化。
public enum Linkify {

    public static let scheme = "yxi-file://"

    /// 一段路径里允许出现的字符。
    ///
    /// ⚠️ **必须带上中文**：用户机器上真有 `/opt/workspace/诗歌` 这样的目录。
    /// 只认 ASCII 的话 `/a/b/跳转样例.md` 会在 `/b/` 处被切断，
    /// 点开的是**上一级目录**，看起来像「跳错地方了」。
    ///
    /// ⚠️ **只收汉字本体（U+4E00–U+9FFF），不能顺手把 U+3000–U+303F 也放进来** ——
    /// 那一段是「。，、《》」这些标点，收进来的话 `/a/b。后面这句话` 会被整条吃掉。
    /// 中文句子里本来就不写空格，一旦吃穿就是一整句变成假链接。
    private static let seg = "[A-Za-z0-9._+@\u{4E00}-\u{9FFF}-]+"

    /// 认路径。**故意认得保守** —— 认错的代价是把正文里一段普通文字变成假链接，
    /// 点了还会跳走，比漏认难受得多。
    ///
    /// 两条硬规矩：要么 `~` 开头；要么 `/` 开头且**至少两段**（`/a/b`）。
    ///
    /// ⚠️ **「至少两段」不是随便定的。** 只要一段的话，
    /// 「整个 /22 段注册给一家代理商」里的 `/22` 就会被认成路径 —— 这句真出现过。
    ///
    /// ⚠️ 前面那个 lookbehind 挡的是 `http://`、`git@x:/a/b` 这种。
    /// ⚠️ 末尾那个可选的 `/` 不能省：目录常写成 `` `/a/b/` ``，
    /// 而行内代码那一支要求**整段**都是路径，少了它这种就整段不认。
    private static var pathPattern: String {
        "(?<![\\w:/~.-])(~(?:/\(seg))+|(?:/\(seg)){2,})/?"
    }

    private static let linkPattern = "\\[[^\\]\\n]*\\]\\([^)\\n]*\\)"
    private static let codePattern = "`[^`\\n]+`"

    /// 把 [md] 里认出来的路径改写成链接。三种上下文分开处理：
    /// - **围栏代码块**里的一律不动 —— 那是给人照抄的原文，插链接就毁了
    /// - **行内代码**整段是路径时，写成 ``[`/a/b.md`](…)``：markdown 允许链接文字里
    ///   放行内代码，所以点得动、还是等宽的。
    ///   ⚠️ 不能只替换反引号**里面** —— 代码段里的 `[]()` 不会被解析，
    ///   屏幕上会原样冒出一串 `[/a/b](yxi-file:///a/b)`
    /// - **普通正文**里直接替换，但先把已有的 markdown 链接摘出去，别套两层
    public static func apply(_ md: String) -> String {
        var fenced = false
        return md.components(separatedBy: "\n").map { line -> String in
            let t = line.trimmingCharacters(in: .whitespaces)
            if t.hasPrefix("```") || t.hasPrefix("~~~") { fenced.toggle(); return line }
            if fenced { return line }
            return split(line, codePattern) { piece, isCode in
                if isCode {
                    let inner = String(piece.dropFirst().dropLast())
                    return wholeIsPath(inner) ? "[\(piece)](\(scheme)\(inner))" : piece
                }
                return split(piece, linkPattern) { p, isLink in
                    isLink ? p : replacePaths(p)
                }
            }
        }.joined(separator: "\n")
    }

    /// 整段就是一条路径（行内代码那一支要用）
    static func wholeIsPath(_ s: String) -> Bool {
        guard let re = try? NSRegularExpression(pattern: "^(?:\(pathPattern))$") else { return false }
        let ns = s as NSString
        return re.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)) != nil
    }

    private static func replacePaths(_ s: String) -> String {
        guard let re = try? NSRegularExpression(pattern: pathPattern) else { return s }
        let ns = s as NSString
        var out = ""
        var at = 0
        for m in re.matches(in: s, range: NSRange(location: 0, length: ns.length)) {
            if m.range.location > at {
                out += ns.substring(with: NSRange(location: at, length: m.range.location - at))
            }
            let raw = ns.substring(with: m.range)
            // 句末标点会被 seg 里的 `.` 吃进去：`见 /a/b/c.` → 把尾巴那个点还回去
            let path = String(raw.reversed().drop { $0 == "." || $0 == "," }.reversed())
            out += "[\(path)](\(scheme)\(path))" + String(raw.dropFirst(path.count))
            at = m.range.location + m.range.length
        }
        if at < ns.length { out += ns.substring(from: at) }
        return out
    }

    /// 按正则切开，命中的片段和没命中的各自交给 `f`，再拼回去。
    private static func split(_ s: String, _ pattern: String,
                              _ f: (String, Bool) -> String) -> String {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return f(s, false) }
        let ns = s as NSString
        var out = ""
        var at = 0
        for m in re.matches(in: s, range: NSRange(location: 0, length: ns.length)) {
            if m.range.location > at {
                out += f(ns.substring(with: NSRange(location: at, length: m.range.location - at)), false)
            }
            out += f(ns.substring(with: m.range), true)
            at = m.range.location + m.range.length
        }
        if at < ns.length { out += f(ns.substring(from: at), false) }
        return out
    }

    /// 从 `yxi-file://` 链接里取回路径。不是我们的链接就返回 nil。
    public static func pathOf(_ url: String) -> String? {
        guard url.hasPrefix(scheme) else { return nil }
        let p = String(url.dropFirst(scheme.count))
        return p.isEmpty ? nil : p.removingPercentEncoding ?? p
    }
}
