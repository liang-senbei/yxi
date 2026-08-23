import XCTest
@testable import YxiKit

/// 排队输入的解析。
///
/// 这一块的来历：用户在 Claude 忙的时候连打了四条，手机上一条都没出现 ——
/// 因为它们在转录里的类型是 `queue-operation` / `attachment`，不是 `user`，
/// 老解析器当不认识**静默丢掉**了（TROUBLESHOOTING #72）。
/// 然后是第二轮：气泡出来了，但**永远不消失**，而那些命令几小时前就跑完了（#76）。
///
/// ⚠️ 下面每一行 JSONL 都是从真机转录里原样抠出来的。
final class QueuedTests: XCTestCase {

    private func queued(_ lines: [String]) -> [String] {
        Transcript.parse(lines).compactMap { if case let .queued(_, t) = $0 { return t } else { return nil } }
    }
    private func users(_ lines: [String]) -> [String] {
        Transcript.parse(lines).compactMap { if case let .user(_, t) = $0 { return t } else { return nil } }
    }

    /// 防 #72：进队了还没被处理，必须**看得见**。看不见用户会以为没发出去然后重复发。
    func test_进队了还没处理就显示成排队中() {
        let q = queued([Fixture.enqueueA, Fixture.enqueueB])
        XCTAssertEqual(q.count, 2)
        XCTAssertTrue(q[0].contains("排队甲"))
        XCTAssertTrue(q[1].contains("排队乙"))
    }

    /// 防 #76：**出队的判据是「这句话有没有真的作为用户消息出现过」，不是某个出队事件。**
    /// 实测一个真实会话：35 个 enqueue 只有 29 个 remove，剩下 13 条全都后来
    /// 以普通 `user` 消息出现了 —— 命令几小时前就跑完了，界面上还挂着「排队中」。
    func test_后来以普通用户消息出现过的就不算排队() {
        let lines = [Fixture.enqueueA, Fixture.userSameTextA]
        XCTAssertEqual(queued(lines).count, 0, "它已经作为用户消息出现过了，不该还挂着")
        XCTAssertEqual(users(lines).count, 1)
    }

    /// ⚠️ **2.1.241 的出队事件是 `dequeue`，而且不带 `content`** —— 没有任何东西
    /// 能拿来匹配是哪一条出了队。这条测试钉的就是这个事实：光有出队事件，
    /// 那条排队**照样挂着**。所以出队只能靠上面那条终态规则，不能靠事件。
    /// （更早的转录里是 `remove` + content，见下一条。两种都真实存在。）
    func test_dequeue不带content所以救不了任何东西() {
        XCTAssertEqual(queued([Fixture.enqueueA, Fixture.dequeueOp]).count, 1)
    }

    /// 老版本的 `remove` + `content` 仍然要认 —— 它是同一轮内出队的快路径。
    func test_老版本的remove仍然认() {
        XCTAssertEqual(queued([Fixture.removeOpOld]).count, 0)
    }

    /// ⚠️ **`enqueue` 不一定是人打的。** 子 agent 跑完时 Claude Code 会把一整块
    /// `<task-notification>…</task-notification>` 也 enqueue 进来。照原样显示
    /// 就是在用户脸上糊一段内部 XML，而且是「你排队的输入」的口吻 —— 他没打过这句话。
    func test_系统注入的task_notification不显示成排队() {
        let q = queued([Fixture.enqueueTaskNotification])
        XCTAssertEqual(q.count, 0, "内部 XML 不该冒成用户的排队输入：\(q)")
    }

    /// ⚠️ 这条防的是 #76 换个入口再来一次。
    /// `queued_command` 里 `origin.kind != "human"` 的那些**不能显示成用户消息**（对），
    /// 但如果只用「显示出来的用户消息」当出队判据，它就**永远不会被消化** ——
    /// 于是气泡永远挂着，而那正是用户抱怨过的现象。
    /// 判据必须是「它被处理过」这个终态，跟是不是人打的无关。
    func test_非human的queued_command不显示但必须算已消化() {
        let lines = [Fixture.enqueueTaskNotification, Fixture.queuedCommandTaskNotification]
        XCTAssertEqual(queued(lines).count, 0, "被处理过就不该还挂着")
        XCTAssertEqual(users(lines).count, 0, "内部 XML 不能显示成用户说的话")
    }

    /// 人打的那条 `queued_command`（`origin.kind == "human"`）要变成正常的用户消息。
    func test_人打的queued_command变成用户消息() {
        let u = users([Fixture.queuedCommandHuman])
        XCTAssertEqual(u.count, 1)
        XCTAssertTrue(u[0].contains("排队丁"))
    }

    /// ⚠️ 最要紧的一条：用户打的字，从「排队中」到「被处理」，**全程都得在界面上看得见**。
    /// 中间断一截，用户就会以为没发出去然后重发。
    func test_排队的消息不会凭空消失() {
        let 排队时 = Transcript.parse([Fixture.enqueueA])
        let 处理后 = Transcript.parse([Fixture.enqueueA, Fixture.dequeueOp, Fixture.userSameTextA])
        XCTAssertTrue(排队时.contains { if case let .queued(_, t) = $0 { return t.contains("排队甲") } else { return false } },
                      "排队时看得见")
        XCTAssertTrue(处理后.contains { if case let .user(_, t) = $0 { return t.contains("排队甲") } else { return false } },
                      "处理后还看得见")
        XCTAssertEqual(queued([Fixture.enqueueA, Fixture.dequeueOp, Fixture.userSameTextA]).count, 0,
                       "不能既是普通消息又还挂在排队里")
    }

    /// 整段真实的一轮：两条人打的排队 + 一条系统注入的，最后全部消化干净，
    /// 界面上只留两条用户消息，一条「排队中」都不剩。
    func test_真实的一整轮排队跑完不留残留() {
        let items = Transcript.parse([
            Fixture.enqueueA, Fixture.dequeueOp, Fixture.enqueueB,
            Fixture.enqueueTaskNotification, Fixture.dequeueOp,
            Fixture.userSameTextA, Fixture.userTaskNotification,
        ])
        let q = items.compactMap { if case let .queued(_, t) = $0 { return t } else { return nil } }
        XCTAssertEqual(q, ["排队乙：这条也是"], "只剩真的还没处理的那条：\(q)")
    }
}
