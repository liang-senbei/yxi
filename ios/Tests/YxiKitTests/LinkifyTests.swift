import XCTest
@testable import YxiKit

/// 路径识别。**每条都对应安卓侧一个真实踩过的坑**，别删。
final class LinkifyTests: XCTestCase {

    private func links(_ s: String) -> [String] {
        // 抽出所有 (yxi-file://…) 里的路径
        let out = Linkify.apply(s)
        var found: [String] = []
        var rest = Substring(out)
        while let a = rest.range(of: "(\(Linkify.scheme)") {
            rest = rest[a.upperBound...]
            guard let b = rest.firstIndex(of: ")") else { break }
            found.append(String(rest[rest.startIndex..<b]))
            rest = rest[b...]
        }
        return found
    }

    func testAbsolutePathTwoSegments() {
        XCTAssertEqual(links("看 /opt/workspace 这个目录"), ["/opt/workspace"])
    }

    /// ⚠️ 真实句子：「整个 /22 段注册给一家代理商」—— 一段的不能认
    func testSingleSegmentNotAPath() {
        XCTAssertEqual(links("整个 /22 段注册给一家代理商"), [])
    }

    /// ⚠️ 用户机器上真有中文目录，只认 ASCII 会在 /b/ 处切断
    func testChinesePathSurvives() {
        XCTAssertEqual(links("打开 /opt/workspace/诗歌/春天.md 看看"),
                       ["/opt/workspace/诗歌/春天.md"])
    }

    /// ⚠️ 中文标点不能收进路径，否则一整句变成假链接
    func testChinesePunctuationStopsPath() {
        XCTAssertEqual(links("见 /a/b/c。后面这句话还在"), ["/a/b/c"])
    }

    /// 句末的英文句号要还回去
    func testTrailingPeriodGivenBack() {
        let out = Linkify.apply("见 /a/b/c.md.")
        XCTAssertTrue(out.hasSuffix("."), out)
        XCTAssertEqual(links("见 /a/b/c.md."), ["/a/b/c.md"])
    }

    func testTildePathOneSegmentIsEnough() {
        XCTAssertEqual(links("放在 ~/src 里"), ["~/src"])
    }

    /// http:// 和 git@host:/a/b 都不是本地路径
    func testUrlsNotLinkified() {
        XCTAssertEqual(links("见 http://example.com/a/b"), [])
        XCTAssertEqual(links("git@github.com:/a/b"), [])
    }

    /// and/or、读/写 不以 / 开头，天然不中
    func testSlashInsideWordIgnored() {
        XCTAssertEqual(links("and/or 读/写 都不是路径"), [])
    }

    /// 围栏代码块里的一律不动 —— 那是给人照抄的原文
    func testFencedBlockUntouched() {
        let md = "正文 /a/b\n```\ncd /x/y\n```\n尾 /c/d"
        let out = Linkify.apply(md)
        XCTAssertTrue(out.contains("cd /x/y\n"), "围栏里被改了：\(out)")
        XCTAssertEqual(links(md), ["/a/b", "/c/d"])
    }

    /// 行内代码整段是路径 → 连反引号一起包进链接文字，仍然是等宽的
    func testInlineCodePathBecomesLink() {
        let out = Linkify.apply("改 `/a/b.md` 这个")
        XCTAssertTrue(out.contains("[`/a/b.md`](\(Linkify.scheme)/a/b.md)"), out)
    }

    /// 行内代码不是路径就别动
    func testInlineCodeNonPathUntouched() {
        XCTAssertEqual(Linkify.apply("跑 `git status` 看看"), "跑 `git status` 看看")
    }

    /// 已经是 markdown 链接的不能套两层
    func testExistingLinkNotDoubleWrapped() {
        let md = "[说明](/a/b/readme.md)"
        XCTAssertEqual(Linkify.apply(md), md)
    }

    func testPathOfRoundTrip() {
        XCTAssertEqual(Linkify.pathOf("\(Linkify.scheme)/a/b"), "/a/b")
        XCTAssertNil(Linkify.pathOf("https://example.com"))
        XCTAssertNil(Linkify.pathOf(Linkify.scheme))
    }
}
