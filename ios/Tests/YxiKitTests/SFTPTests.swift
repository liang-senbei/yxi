import XCTest
@testable import YxiKit

// MARK: - 文件大小

final class HumanSizeTests: XCTestCase {
    func testBoundaries() {
        XCTAssertEqual(humanSize(0), "0 B")
        XCTAssertEqual(humanSize(1023), "1023 B")
        XCTAssertEqual(humanSize(1024), "1 K")
        XCTAssertEqual(humanSize(1024 * 1024 - 1), "1024 K")
        XCTAssertEqual(humanSize(1024 * 1024), "1.0 M")
        XCTAssertEqual(humanSize(1024 * 1024 * 1024), "1.0 G")
    }
    /// 安卓那份对 5.5M 显示 "5.5 M" —— 两端必须一致，否则同一个文件两个数
    func testMatchesAndroid() {
        XCTAssertEqual(humanSize(5_767_168), "5.5 M")
        XCTAssertEqual(humanSize(2048), "2 K")
    }
}

// MARK: - git diff

final class GitDiffCommandTests: XCTestCase {
    func testQuotesPathWithApostrophe() {
        let c = SessionProbe.gitDiffCommand(cwd: "/home/it's/repo")
        XCTAssertTrue(c.contains(#"cd '/home/it'\''s/repo'"#), c)
    }
    /// 两种「没东西看」的情况都必须有话说，不能是空白
    func testAlwaysSaysSomething() {
        let c = SessionProbe.gitDiffCommand(cwd: "/x")
        XCTAssertTrue(c.contains("（没有未提交的改动）"))
        XCTAssertTrue(c.contains("（这里不是 git 仓库）"))
    }
    func testCapsOutput() {
        XCTAssertTrue(SessionProbe.gitDiffCommand(cwd: "/x").contains("head -c 60000"))
    }
}

final class AgoTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private func at(_ secondsAgo: Int) -> Date { now.addingTimeInterval(-Double(secondsAgo)) }

    func testBuckets() {
        XCTAssertEqual(ago(at(5), now: now), "刚刚")
        XCTAssertEqual(ago(at(59), now: now), "刚刚")
        XCTAssertEqual(ago(at(60), now: now), "1 分钟前")
        XCTAssertEqual(ago(at(3599), now: now), "59 分钟前")
        XCTAssertEqual(ago(at(3600), now: now), "1 小时前")
        XCTAssertEqual(ago(at(86400), now: now), "1 天前")
        XCTAssertEqual(ago(at(86400 * 40), now: now), "很久以前")
    }

    /// ⚠️ 服务器时钟可能比手机快一点点 —— 别显示成「-3 秒前」
    func testFutureIsNotNegative() {
        XCTAssertEqual(ago(now.addingTimeInterval(30), now: now), "刚刚")
    }
}

final class NewSessionTests: XCTestCase {
    func testNameFromDir() {
        XCTAssertEqual(SessionProbe.sessionName(forDir: "/opt/workspace/Yxi"), "cc-Yxi")
        XCTAssertEqual(SessionProbe.sessionName(forDir: "/opt/workspace/Yxi/"), "cc-Yxi")
        XCTAssertEqual(SessionProbe.sessionName(forDir: "/"), "cc-work")
    }
    /// ⚠️ tmux 的 target 语法用 `:` 做分隔，名字里带它会很难伺候；空格同理。
    ///
    /// ⚠️ **中文要留着。** 用户机器上真有 `cc-诗歌` / `cc-文件传` 这种会话，
    /// 滤掉中文会让手机新建的会话跟他现有的一批取名规则不一致。
    /// （`isLetter` 对汉字返回 true，跟安卓的 `isLetterOrDigit` 行为一致。）
    func testNameIsSafe() {
        XCTAssertEqual(SessionProbe.sessionName(forDir: "/a/我的项目"), "cc-我的项目")
        XCTAssertFalse(SessionProbe.sessionName(forDir: "/a/b:c d").contains(":"))
        XCTAssertFalse(SessionProbe.sessionName(forDir: "/a/b:c d").contains(" "))
    }

    /// 全是符号的目录名滤空之后不能产出一个光秃秃的 `cc-`
    func testAllSymbolsFallsBack() {
        XCTAssertEqual(SessionProbe.sessionName(forDir: "/a/@@@"), "cc-work")
    }
    /// **幂等**：已经有那个会话就直接用，不能把里面正在干的活打断
    func testCommandIsIdempotent() {
        let c = SessionProbe.newSessionCommand(dir: "/opt/workspace/Yxi")
        XCTAssertTrue(c.hasPrefix("tmux has-session -t 'cc-Yxi'"), c)
        XCTAssertTrue(c.contains("||"), "没有「已存在就跳过」这一层：\(c)")
        XCTAssertTrue(c.contains("-c '/opt/workspace/Yxi'"), "没在那个目录开：\(c)")
    }
    /// 目录里有单引号也不能把命令拼断
    func testQuoting() {
        XCTAssertTrue(SessionProbe.newSessionCommand(dir: "/a/it's").contains(#"'/a/it'\''s'"#))
    }
}

/// 「新会话开在哪个目录」的候选。跟安卓 `DirsTest` 同一批用例。
final class DirsTests: XCTestCase {

    func testParentsFromCwds() {
        XCTAssertEqual(Dirs.parents(of: ["/root/src/workspace/Yxi", "/root/src/workspace/mail/"]),
                       ["/root/src/workspace"])
    }

    /// ⚠️ 这条是这个功能存在的理由：**已经开着会话的目录不能再出现在候选里**
    func testTakenAreExcluded() {
        let out = "/root/src/workspace/Yxi\n/root/src/workspace/mail\n/root/src/workspace/新项目"
        XCTAssertEqual(Dirs.candidates(out, taken: ["/root/src/workspace/Yxi", "/root/src/workspace/mail"]),
                       ["/root/src/workspace/新项目"])
    }

    /// 末尾斜杠不该让同一个目录被当成两个
    func testTrailingSlashDoesNotDefeatComparison() {
        XCTAssertEqual(Dirs.candidates("/a/b/\n/a/c", taken: ["/a/b"]), ["/a/c"])
    }

    func testHiddenDirsSkipped() {
        XCTAssertEqual(Dirs.candidates("/a/.git\n/a/proj", taken: []), ["/a/proj"])
    }

    func testCommandIsShallowAndQuoted() {
        let c = Dirs.listCommand(parents: ["/a/it's"])!
        XCTAssertTrue(c.contains("-maxdepth 1"), c)
        XCTAssertTrue(c.contains(#"'/a/it'\''s'"#), c)
    }

    func testNoParentsNoCommand() {
        XCTAssertNil(Dirs.listCommand(parents: []))
        XCTAssertNil(Dirs.listCommand(parents: ["相对路径"]))
    }
}
