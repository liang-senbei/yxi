import XCTest
@testable import YxiKit

/// 扩展跟主 App 之间那点共享逻辑。
/// ⚠️ 这是分享扩展 / 小组件里**唯一能在 Linux 上验的部分** ——
/// 它俩的界面代码只有 CI 的 macOS runner 编得动，所以逻辑能挪进来的都挪进来了。
final class SharedTests: XCTestCase {

    private func tempDir() -> URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("yxi-outbox-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    // MARK: - 文件名

    /// ⚠️ 名字是别的 App 递进来的，`../` 必须出不去容器。
    func test_附件名字跑不出容器() {
        XCTAssertEqual(AppGroup.safeName("../../etc/passwd"), "passwd")
        XCTAssertEqual(AppGroup.safeName("子目录/截图.png"), "截图.png")
        XCTAssertEqual(AppGroup.safeName(".."), "file")
        XCTAssertEqual(AppGroup.safeName("/"), "file")
        XCTAssertEqual(AppGroup.safeName(""), "file")
        XCTAssertEqual(AppGroup.safeName(String(repeating: "a", count: 300)).count, 80)
    }

    // MARK: - 暂存箱

    func test_存进去再读出来还是那一条() throws {
        let box = Outbox(root: tempDir())
        let dir = try box.begin()
        try Data("hi".utf8).write(to: dir.appendingPathComponent("截图.png"))
        try box.commit(dir, note: "这个报错看一下")

        let items = box.pending()
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items[0].note, "这个报错看一下")
        XCTAssertEqual(items[0].files.map(\.lastPathComponent), ["截图.png"])
        XCTAssertEqual(try Data(contentsOf: items[0].files[0]), Data("hi".utf8))

        box.drop(items[0])
        XCTAssertTrue(box.pending().isEmpty)
    }

    /// ⚠️ 没写 `item.json` 的目录 = 扩展拷到一半被系统杀了。
    /// 认了它就等于把一条**内容残缺**的分享发给 Claude。
    func test_没提交完的不算数() throws {
        let box = Outbox(root: tempDir())
        let dir = try box.begin()
        try Data("半个".utf8).write(to: dir.appendingPathComponent("a.bin"))
        XCTAssertTrue(box.pending().isEmpty)
    }

    /// 半成品也不能永远留着占用户的空间。
    func test_超过一天的半成品会被清掉() throws {
        let box = Outbox(root: tempDir())
        let dir = try box.begin()
        XCTAssertEqual(box.pending(now: Date().addingTimeInterval(3600)).count, 0)
        XCTAssertTrue(FileManager.default.fileExists(atPath: dir.path))

        _ = box.pending(now: Date().addingTimeInterval(48 * 3600))
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.path))
    }

    func test_旧的排前面() throws {
        let box = Outbox(root: tempDir())
        let old = try box.begin(); try box.commit(old, note: "先", at: Date(timeIntervalSince1970: 100))
        let new = try box.begin(); try box.commit(new, note: "后", at: Date(timeIntervalSince1970: 200))
        XCTAssertEqual(box.pending().map(\.note), ["先", "后"])
    }

    // MARK: - 小组件状态

    func test_刚写的读得回来() {
        let dir = tempDir()
        XCTAssertTrue(WaitingSnapshot.publish(waiting: 2, working: 1, names: ["yxi", "站长机"], to: dir))
        let s = WaitingSnapshot.current(in: dir)
        XCTAssertEqual(s?.waiting, 2)
        XCTAssertEqual(s?.names, ["yxi", "站长机"])
    }

    /// ⚠️⚠️ 本项目的硬规矩：**读不到就整块不显示，不许显示 0 或「未知」。**
    /// 这里守的是「不显示」那一半 —— 三种读不到都必须是 nil，不是一个 0 值的快照。
    func test_读不到就是nil而不是零() {
        XCTAssertNil(WaitingSnapshot.current(in: nil))                    // 没有 App Group
        XCTAssertNil(WaitingSnapshot.current(in: tempDir()))              // 主 App 还没写过

        let dir = tempDir()
        let long = Date().addingTimeInterval(-WaitingSnapshot.maxAge - 60)
        XCTAssertTrue(WaitingSnapshot.publish(waiting: 3, working: 0, names: ["x"], at: long, to: dir))
        XCTAssertNil(WaitingSnapshot.current(in: dir))                    // 太旧了
    }

    func test_没有容器就写不下去而且要说实话() {
        XCTAssertFalse(WaitingSnapshot.publish(waiting: 1, working: 0, names: [], to: nil))
    }

    /// 措辞跟安卓的 `WaitingWidget` 对齐。
    func test_两行字跟安卓说的一样() {
        let now = Date()
        XCTAssertEqual(WaitingSnapshot(waiting: 2, working: 1, names: ["a", "b"], at: now).title, "2 个会话等你")
        XCTAssertEqual(WaitingSnapshot(waiting: 2, working: 1, names: ["a", "b"], at: now).subtitle, "a、b")
        XCTAssertEqual(WaitingSnapshot(waiting: 0, working: 3, names: [], at: now).title, "3 个会话在跑")
        XCTAssertEqual(WaitingSnapshot(waiting: 0, working: 3, names: [], at: now).subtitle, "Claude 正在干活")
        XCTAssertEqual(WaitingSnapshot(waiting: 0, working: 0, names: [], at: now).title, "Claude")
        XCTAssertEqual(WaitingSnapshot(waiting: 0, working: 0, names: [], at: now).subtitle, "盯着呢")
    }

    func test_会话名只留前几个免得挤爆小组件() {
        let dir = tempDir()
        WaitingSnapshot.publish(waiting: 9, working: 0, names: ["a", "b", "c", "d", "e", "f"], to: dir)
        XCTAssertEqual(WaitingSnapshot.current(in: dir)?.names.count, 4)
    }
}
