import XCTest
@testable import YxiKit

final class SlashTests: XCTestCase {

    func test只打一个斜杠时整份显示() {
        XCTAssertEqual(Slash.suggest("/").count, Slash.all.count)
    }

    func test按前缀筛() {
        XCTAssertEqual(Slash.suggest("/co").map(\.name), ["compact", "context", "cost", "config"])
    }

    /// ⚠️ 正文里提到 `/usr/bin` 不算 —— 必须**整条**以 `/` 开头才弹
    func test正文里的路径不弹() {
        XCTAssertTrue(Slash.suggest("看看 /usr/bin").isEmpty)
    }

    /// 打了空格 = 在填参数了，提示没用还挡着输入框
    func test填参数时收起来() {
        XCTAssertTrue(Slash.suggest("/model opus").isEmpty)
    }

    /// 多行八成是粘进来的长文本，第一行可能正好是个路径
    func test多行收起来() {
        XCTAssertTrue(Slash.suggest("/compact\n第二行").isEmpty)
    }

    /// 打全了就没什么好补的了（点一下候选之后正是这个情形）
    func test打全了收起来() {
        XCTAssertTrue(Slash.suggest("/usage").isEmpty)
        XCTAssertTrue(Slash.suggest("/USAGE").isEmpty, "大小写也算打全了")
    }

    /// ⚠️ **「打全了就收起来」成立的前提**：没有哪个命令名是另一个的前缀。
    /// 真加了这样一对（比如同时有 `model` 和 `models`），
    /// 打完 `/model` 会把 `/models` 的提示一起收掉，用户就永远选不到后者。
    func test没有命令名是另一个的前缀() {
        for a in Slash.all {
            for b in Slash.all where a.name != b.name {
                XCTAssertFalse(b.name.hasPrefix(a.name),
                               "「\(b.name)」以「\(a.name)」开头 —— 打全 \(a.name) 会把它收掉")
            }
        }
    }

    func test命令名不重复() {
        XCTAssertEqual(Set(Slash.all.map(\.name)).count, Slash.all.count)
    }
}
