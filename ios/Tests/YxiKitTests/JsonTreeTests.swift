import XCTest
@testable import YxiKit

/// JSON 折叠树。展开哪些、值印成什么样，全在这儿定死 ——
/// 界面只按行画，画错了肉眼看得出来，**逻辑错了看不出来**。
final class JsonTreeTests: XCTestCase {

    private let cfg = #"{"port": 22, "hosts": ["a", "b"], "debug": false, "ratio": 0.5, "note": null}"#

    func test_默认只展开根() {
        let rows = JsonTree.rows(cfg, expanded: [""])!
        // 根 + 5 个键，两层以下不展开
        XCTAssertEqual(rows.first?.value, "{5}")
        XCTAssertEqual(rows.first?.depth, 0)
        XCTAssertEqual(rows.count, 6)
        XCTAssertEqual(rows.dropFirst().map(\.key), ["debug", "hosts", "note", "port", "ratio"])
        XCTAssertTrue(rows.dropFirst().allSatisfy { $0.depth == 1 })
    }

    func test_全折叠只剩一行() {
        let rows = JsonTree.rows(cfg, expanded: [])!
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].path, "")
        XCTAssertTrue(rows[0].foldable)
    }

    /// ⚠️ 折的是**分支**：没展开的子树一行都不该建出来（几千行的配置全铺开等于没法看，
    /// 而且大文件上是真的会卡）
    func test_只有展开的分支才往下走() {
        let deep = #"{"a": {"b": {"c": [1, 2, 3]}}}"#
        XCTAssertEqual(JsonTree.rows(deep, expanded: [""])!.count, 2)          // 根 + a
        XCTAssertEqual(JsonTree.rows(deep, expanded: ["", "/a"])!.count, 3)    // + b
        let all = JsonTree.rows(deep, expanded: ["", "/a", "/a/b", "/a/b/c"])!
        XCTAssertEqual(all.count, 7)                                          // + c + 三个元素
        XCTAssertEqual(all.last?.path, "/a/b/c/2")
        XCTAssertEqual(all.last?.key, "2")                                    // 数组元素的 key 是下标
        XCTAssertEqual(all.last?.depth, 4)
    }

    func test_值的印法() {
        let rows = JsonTree.rows(cfg, expanded: [""])!
        let byKey = Dictionary(uniqueKeysWithValues: rows.dropFirst().map { ($0.key, $0.value) })
        XCTAssertEqual(byKey["port"], "22")          // ⚠️ 不是 22.0
        XCTAssertEqual(byKey["ratio"], "0.5")
        XCTAssertEqual(byKey["debug"], "false")      // ⚠️ 不是 0
        XCTAssertEqual(byKey["note"], "null")
        XCTAssertEqual(byKey["hosts"], "[2]")        // 折叠的数组只报个数
    }

    func test_字符串带引号中文原样() {
        let rows = JsonTree.rows(#"{"name": "张三"}"#, expanded: [""])!
        XCTAssertEqual(rows[1].value, "\"张三\"")
    }

    func test_空对象空数组不可折() {
        let rows = JsonTree.rows(#"{"a": {}, "b": []}"#, expanded: [""])!
        XCTAssertEqual(rows.dropFirst().map(\.foldable), [false, false])
        XCTAssertEqual(rows.dropFirst().map(\.value), ["{0}", "[0]"])
    }

    func test_根是数组也行() {
        let rows = JsonTree.rows(#"[{"a": 1}]"#, expanded: ["", "/0"])!
        XCTAssertEqual(rows.map(\.value), ["[1]", "{1}", "1"])
        XCTAssertEqual(rows.map(\.path), ["", "/0", "/0/a"])
    }

    /// ⚠️ 解析不了要**明确返回 nil**，界面才知道该退回纯文本。
    /// 真实的 `.json` 常常是 JSONL、带注释的 JSON5，或者被 1 MB 上限截断了半截。
    func test_解析不了返回nil() {
        XCTAssertNil(JsonTree.rows("", expanded: [""]))
        XCTAssertNil(JsonTree.rows("{\"a\": 1}\n{\"a\": 2}", expanded: [""]))   // JSONL
        XCTAssertNil(JsonTree.rows("{\"a\": 1, // 注释\n}", expanded: [""]))     // JSON5
        XCTAssertNil(JsonTree.rows(#"{"a": 1, "b": "被截"#, expanded: [""]))     // 截断
        // 光秃秃一个标量：合法 JSON，但画成一行的树没意义，当纯文本看
        XCTAssertNil(JsonTree.rows("42", expanded: [""]))
        XCTAssertNil(JsonTree.rows("\"abc\"", expanded: [""]))
    }

    /// ⚠️ 键必须**每次同序**：字典本身没有顺序，Swift 的遍历顺序还每个进程都不一样 ——
    /// 不排的话同一个文件今天这个次序、明天那个次序
    func test_键的顺序稳定() {
        let src = #"{"z": 1, "a": 2, "m": 3}"#
        let once = JsonTree.rows(src, expanded: [""])!.map(\.key)
        XCTAssertEqual(once, ["", "a", "m", "z"])
        XCTAssertEqual(JsonTree.rows(src, expanded: [""])!.map(\.key), once)
    }

    /// path 是折叠状态的键，重了就会「点一个展开两个」
    func test_path唯一() {
        let rows = JsonTree.rows(#"{"a": {"b": 1}, "a.b": 2}"#, expanded: ["", "/a"])!
        XCTAssertEqual(Set(rows.map(\.path)).count, rows.count)
    }
}
