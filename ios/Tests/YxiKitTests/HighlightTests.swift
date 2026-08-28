import XCTest
@testable import YxiKit

/// 语法高亮。**界面那半在这台机器上编不了**，所以这里测的是全部能测的东西：
/// 哪一段被认成什么、以及分段能不能拼回原文。
final class HighlightTests: XCTestCase {

    /// 取某一类的所有片段原文，按出现顺序
    private func pieces(_ text: String, _ ext: String, _ kind: Highlight.Kind) -> [String] {
        Highlight.spans(text, ext: ext)
            .filter { $0.kind == kind }
            .map { String(text[$0.range]) }
    }

    // MARK: 拼得回原文 —— 这条挂了界面上就是「代码少了几个字」

    private func assertRoundTrip(_ text: String, _ ext: String, file: StaticString = #filePath, line: UInt = #line) {
        let joined = Highlight.segments(text, ext: ext).map { String($0.text) }.joined()
        XCTAssertEqual(joined, text, file: file, line: line)
    }

    func test_分段拼得回原文() {
        assertRoundTrip("let a = 1\n// 说明\nprint(\"你好\")\n", "swift")
        assertRoundTrip("", "swift")
        assertRoundTrip("\n\n\n", "py")
        assertRoundTrip("#!/bin/sh\nset -e\necho '完成'", "sh")
        assertRoundTrip("没有换行也没有代码", "md")
        // 未闭合的引号（正在编辑的文件很常见）不能吞掉后面的内容
        assertRoundTrip("s = \"没关的引号", "py")
        assertRoundTrip("s = \"结尾一个反斜杠 \\", "py")
    }

    // MARK: 四类各自认得对

    func test_关键字() {
        XCTAssertEqual(pieces("if x { return 1 }", "swift", .keyword), ["if", "return"])
        // 大小写不敏感（SQL / 老代码里全大写的关键字）
        XCTAssertEqual(pieces("SELECT IF FROM t", "sql", .keyword), ["IF", "FROM"])
        // 词的一部分不算：`iffy` 不是 `if`
        XCTAssertEqual(pieces("iffy notif if_", "swift", .keyword), [])
    }

    func test_字符串里的关键字不算关键字() {
        XCTAssertEqual(pieces("s = \"if else\"", "py", .keyword), [])
        XCTAssertEqual(pieces("s = \"if else\"", "py", .string), ["\"if else\""])
    }

    func test_数字() {
        XCTAssertEqual(pieces("a = 3.14 + 42", "py", .number), ["3.14", "42"])
        // 结尾的点是语法不是小数点：`range(1..)` 里那两个点不能被吃进数字
        XCTAssertEqual(pieces("for i in 0..<3 {", "swift", .number), ["0", "3"])
        // 标识符里的数字不单独算
        XCTAssertEqual(pieces("v2 = x1", "py", .number), [])
    }

    func test_注释按扩展名分() {
        XCTAssertEqual(pieces("x = 1  # 说明", "py", .comment), ["# 说明"])
        XCTAssertEqual(pieces("let x = 1  // 说明", "swift", .comment), ["// 说明"])
        XCTAssertEqual(pieces("select 1 -- 说明", "sql", .comment), ["-- 说明"])
    }

    /// ⚠️ markdown / 纯文本**没有注释**。不排除的话 `#` 标题之后整行变灰，
    /// 而 `https://x/#anchor` 会把半句话吃掉。
    func test_markdown没有注释() {
        XCTAssertEqual(pieces("# 标题\n看 https://x/#anchor", "md", .comment), [])
        XCTAssertEqual(pieces("列一列 a, b", "txt", .comment), [])
    }

    /// ⚠️ **跟安卓那版故意不一样**：安卓先找注释符再切字符串，
    /// `url = "http://x"` 里的 `//` 会被当注释，半行变灰。这里字符串先被吃掉。
    func test_url里的双斜杠不是注释() {
        XCTAssertEqual(pieces("url = \"http://x/y\"", "js", .comment), [])
        XCTAssertEqual(pieces("url = \"http://x/y\"", "js", .string), ["\"http://x/y\""])
        // 井号同理：shebang 在字符串里
        XCTAssertEqual(pieces("s = \"#!/bin/sh\"", "sh", .comment), [])
    }

    func test_注释里不再分词() {
        // 注释整段是一个 span，里面的 `if` 和 `42` 不另外着色
        XCTAssertEqual(pieces("// if 42", "swift", .keyword), [])
        XCTAssertEqual(pieces("// if 42", "swift", .number), [])
        XCTAssertEqual(pieces("// if 42", "swift", .comment), ["// if 42"])
    }

    /// 转义的引号不算收尾
    func test_转义引号() {
        XCTAssertEqual(pieces(#"s = "a\"b" + t"#, "swift", .string), [#""a\"b""#])
    }

    /// ⚠️ 中文注释 / 中文字符串在这仓库里到处都是，索引一错就整段错位
    func test_中文不错位() {
        let src = "名字 = \"张三\"  # 这是中文注释\nx = 1"
        XCTAssertEqual(pieces(src, "py", .string), ["\"张三\""])
        XCTAssertEqual(pieces(src, "py", .comment), ["# 这是中文注释"])
        XCTAssertEqual(pieces(src, "py", .number), ["1"])
        assertRoundTrip(src, "py")
    }

    /// ⚠️ css / html 里 `#fff` 和裸 URL 到处都是，认行注释符会**整行变灰**。
    /// 它们只有块注释，而块注释我们不认 —— 那就一个都别认。
    func test_css和html没有行注释() {
        XCTAssertEqual(pieces("a { color: #fff; }", "css", .comment), [])
        XCTAssertEqual(pieces("<a href=http://x>文字</a>", "html", .comment), [])
        XCTAssertEqual(pieces("<!-- 注释 -->", "xml", .comment), [])
    }

    /// 没扩展名的多半是脚本（Makefile / Dockerfile / 裸 shell）
    func test_没扩展名按脚本认() {
        XCTAssertEqual(pieces("PORT = 22  # 端口", "", .comment), ["# 端口"])
    }

    /// span 必须**从前到后、互不重叠** —— 界面靠这个顺序拼字符串
    func test_范围有序且不重叠() {
        let src = "let s = \"a\" // c\nif 1 {}\n"
        var last = src.startIndex
        for s in Highlight.spans(src, ext: "swift") {
            XCTAssertLessThanOrEqual(last, s.range.lowerBound)
            XCTAssertLessThan(s.range.lowerBound, s.range.upperBound)
            last = s.range.upperBound
        }
    }
}
