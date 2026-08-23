import XCTest
@testable import YxiKit

/// 路径解析。
///
/// ⚠️ 这块错了在界面上只表现为「图裂了」，**很难定位** ——
/// 安卓 G7 的验收标准就是「在没装任何东西的机器上打开一个带图的 README.md，
/// 图片能显示出来」，靠的就是这几个函数。
final class PathsTests: XCTestCase {

    func test_dirOf() {
        XCTAssertEqual(Paths.dirOf("/a/b/c.md"), "/a/b")
        XCTAssertEqual(Paths.dirOf("/a"), "/")
        XCTAssertEqual(Paths.dirOf("/a/b/"), "/a")
        XCTAssertEqual(Paths.dirOf("c.md"), ".")
    }

    func test_nameOf() {
        XCTAssertEqual(Paths.nameOf("/a/b/c.md"), "c.md")
        XCTAssertEqual(Paths.nameOf("/a/b/"), "b")
        XCTAssertEqual(Paths.nameOf("c.md"), "c.md")
    }

    func test_extOf() {
        XCTAssertEqual(Paths.extOf("/a/README.MD"), "md")
        XCTAssertEqual(Paths.extOf("/a/Makefile"), "")
        // 点开头的隐藏文件不该被当成「扩展名是 bashrc」
        XCTAssertEqual(Paths.extOf("/a/.bashrc"), "")
    }

    func test_markdown里的相对图片路径() {
        // README 在 /srv/proj 下，图写的是 docs/shot.png
        XCTAssertEqual(Paths.resolve(base: "/srv/proj", ref: "docs/shot.png"), "/srv/proj/docs/shot.png")
        XCTAssertEqual(Paths.resolve(base: "/srv/proj/docs", ref: "../shot.png"), "/srv/proj/shot.png")
        XCTAssertEqual(Paths.resolve(base: "/srv/proj", ref: "./a/./b.png"), "/srv/proj/a/b.png")
    }

    func test_绝对路径原样返回() {
        XCTAssertEqual(Paths.resolve(base: "/srv/proj", ref: "/etc/hosts"), "/etc/hosts")
    }

    func test_normalize折叠点和重复斜杠() {
        XCTAssertEqual(Paths.normalize("/a//b/./c"), "/a/b/c")
        XCTAssertEqual(Paths.normalize("/a/b/../c"), "/a/c")
        // 绝对路径爬不到根以上
        XCTAssertEqual(Paths.normalize("/../../a"), "/a")
        // 相对路径可以留着 ..
        XCTAssertEqual(Paths.normalize("../a"), "../a")
        XCTAssertEqual(Paths.normalize(""), ".")
    }

    func test_面包屑() {
        let c = Paths.crumbs("/a/b/c")
        XCTAssertEqual(c.map(\.name), ["/", "a", "b", "c"])
        XCTAssertEqual(c.map(\.path), ["/", "/a", "/a/b", "/a/b/c"])
    }
}
