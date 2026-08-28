import Foundation

/// 极简语法高亮：**注释 / 字符串 / 数字 / 关键字**，四类，就这些。
///
/// ⚠️ **故意不上语法高亮库。** 完整的 tree-sitter / Highlightr 类方案要几 MB 依赖和一堆语法文件，
/// 而这里的目的只是「在手机上瞄一眼代码别糊成一片」。四类着色已经能把结构撑起来，
/// 认不出的语言退化成纯文本也没坏处 —— 这是**故意留的天花板**，
/// 真需要精确高亮时再换库（那时也只用改这一个文件）。
///
/// ⚠️ **住在 YxiKit 而不是界面层**：界面在这台机器上编不了，
/// 放这儿才能真的跑测试（`HighlightTests`）。界面只负责按 [Kind] 上色。
///
/// ⚠️ **不用正则。** `NSRegularExpression` 走 ICU，这仓库已经被它吃过一整段模型名
/// （见 `dev/precheck.py` 里那条规则）；而且正则要在 `NSRange` 和 `String.Index` 之间来回换，
/// 源码里一有中文就容易错位。手写一趟扫描反而短，且天然是 `String.Index`。
public enum Highlight {

    public enum Kind: Equatable, Sendable {
        case keyword, string, number, comment
    }

    /// 一段要上色的范围。范围是 [text] 自己的索引，直接拿去切原串。
    public struct Span: Equatable {
        public let range: Range<String.Index>
        public let kind: Kind
        public init(_ range: Range<String.Index>, _ kind: Kind) {
            self.range = range
            self.kind = kind
        }
    }

    /// [spans] 的相邻形式：连着排能拼回原文，`kind == nil` 的是不上色的部分。
    ///
    /// ⚠️ 有它是因为**界面那半在这台机器上编不了** —— 把「填补空隙」的索引算术留在视图里
    /// 就等于留一段没人测过的代码。视图只剩一个 for 循环。
    public struct Segment: Equatable {
        public let text: Substring
        public let kind: Kind?
    }

    private static let common: Set<String> = [
        "if", "else", "for", "while", "return", "break", "continue", "class", "function", "func",
        "def", "import", "from", "package", "new", "true", "false", "null", "nil", "none", "try",
        "catch", "except", "finally", "throw", "raise", "switch", "case", "default", "const",
        "let", "var", "val", "fun", "public", "private", "protected", "static", "void", "int",
        "string", "bool", "type", "struct", "interface", "enum", "async", "await", "this", "self",
        "and", "or", "not", "in", "is", "as", "with", "do", "then", "fi", "esac", "done", "elif",
        "export", "local", "echo", "set", "unset", "sudo", "override", "suspend", "object",
        "when", "data", "companion", "internal", "lateinit", "by", "it",
        // Swift 这边多认几个：这仓库自己就是 Swift，看自己的代码是最常见的用法
        "guard", "extension", "protocol", "where", "some", "any", "throws", "init", "deinit",
    ]

    /// 行注释的起头符号，按扩展名分。
    private static func lineComment(_ ext: String) -> [String] {
        switch ext {
        case "py", "sh", "bash", "zsh", "yml", "yaml", "toml", "ini", "conf", "cfg",
             "properties", "env", "gitignore":
            return ["#"]
        case "sql":
            return ["--"]
        // markdown / 纯文本没有注释。不排除的话 `#` 会被当注释符 ——
        // 标题变灰凑巧好看，但 `https://x/#anchor` 之后整行都会灰掉
        case "md", "txt", "csv", "log", "json":
            return []
        case "":
            return ["#"]            // 没扩展名的多半是脚本
        default:
            return ["//", "#"]      // 两种都认，认错了顶多少上一点色
        }
    }

    /// 要上色的那些范围，按位置从前到后，互不重叠。
    public static func spans(_ text: String, ext: String) -> [Span] {
        let marks = lineComment(ext)
        var out: [Span] = []
        for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
            scanLine(line, marks: marks, into: &out)
        }
        return out
    }

    /// 拼得回原文的分段形式，界面直接按顺序上色。
    public static func segments(_ text: String, ext: String) -> [Segment] {
        var out: [Segment] = []
        var cursor = text.startIndex
        for s in spans(text, ext: ext) {
            if cursor < s.range.lowerBound {
                out.append(Segment(text: text[cursor..<s.range.lowerBound], kind: nil))
            }
            out.append(Segment(text: text[s.range], kind: s.kind))
            cursor = s.range.upperBound
        }
        if cursor < text.endIndex {
            out.append(Segment(text: text[cursor...], kind: nil))
        }
        return out
    }

    /// 一行一趟扫完：字符串、注释、词，谁先出现算谁的。
    ///
    /// ⚠️ **跟安卓那版有一处**故意**不一样**：安卓是「先找注释符、再切字符串」，
    /// 于是 `url = "http://x"` 里的 `//` 被当成注释，半行变灰。
    /// 这里字符串先被吃掉，注释符只在字符串外才算数 —— 合成一趟扫描顺手就修掉了，
    /// 代码还更短。带 URL 的行在配置文件里到处都是，不是罕见情况。
    private static func scanLine(_ line: Substring, marks: [String], into out: inout [Span]) {
        var i = line.startIndex
        while i < line.endIndex {
            let c = line[i]
            if c == "\"" || c == "'" || c == "`" {
                let end = endOfString(line, from: i, quote: c)
                out.append(Span(i..<end, .string))
                i = end
            } else if marks.contains(where: { line[i...].hasPrefix($0) }) {
                // 注释吃到行尾，里面不再分词
                out.append(Span(i..<line.endIndex, .comment))
                return
            } else if isWordStart(c) {
                let end = endOfWord(line, from: i)
                let word = line[i..<end]
                if common.contains(word.lowercased()) { out.append(Span(i..<end, .keyword)) }
                i = end
            } else if c.isASCII, c.isNumber {
                let end = endOfNumber(line, from: i)
                out.append(Span(i..<end, .number))
                i = end
            } else {
                i = line.index(after: i)
            }
        }
    }

    /// 收尾引号之后的位置；没有收尾引号就到行尾（跨行的字符串**不跟**，见类型头上那条天花板）。
    private static func endOfString(_ line: Substring, from start: String.Index, quote: Character) -> String.Index {
        var i = line.index(after: start)
        while i < line.endIndex {
            if line[i] == "\\" {
                // 转义：跳过反斜杠和它后面那个字符。⚠️ 行尾的孤立 `\` 不能再往后走
                i = line.index(after: i)
                if i >= line.endIndex { return line.endIndex }
            } else if line[i] == quote {
                return line.index(after: i)
            }
            i = line.index(after: i)
        }
        return line.endIndex
    }

    /// ⚠️ 只认 ASCII 词：中文注释里的字不该被当成标识符去查关键字表
    /// （查了也查不中，但白跑一遍 `lowercased()`）。
    private static func isWordStart(_ c: Character) -> Bool {
        c.isASCII && (c.isLetter || c == "_")
    }

    private static func endOfWord(_ line: Substring, from start: String.Index) -> String.Index {
        var i = line.index(after: start)
        while i < line.endIndex, line[i].isASCII, line[i].isLetter || line[i].isNumber || line[i] == "_" {
            i = line.index(after: i)
        }
        return i
    }

    /// `12` / `3.14`。小数点后面必须还有数字，否则 `range.map` 里的那个点会被吞掉。
    private static func endOfNumber(_ line: Substring, from start: String.Index) -> String.Index {
        var i = start
        while i < line.endIndex, line[i].isASCII, line[i].isNumber { i = line.index(after: i) }
        guard i < line.endIndex, line[i] == "." else { return i }
        let afterDot = line.index(after: i)
        guard afterDot < line.endIndex, line[afterDot].isASCII, line[afterDot].isNumber else { return i }
        i = afterDot
        while i < line.endIndex, line[i].isASCII, line[i].isNumber { i = line.index(after: i) }
        return i
    }
}
