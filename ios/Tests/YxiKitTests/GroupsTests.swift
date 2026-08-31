import XCTest
@testable import YxiKit

final class GroupsTests: XCTestCase {

    /// ⚠️ **这串 JSON 是跟 `server/yxi-hub` 之间的契约。**
    /// 手机写、agent 读，涉及三套独立实现（Kotlin / Swift / python）——
    /// 谁单方面改了格式，分组就静悄悄失效：UI 一切正常，
    /// agent 却永远说「你不在任何组里」。
    /// `server/test_yxi.py::test_hub_only_talks_inside_the_group` 用的是**同一串**。
    private let contract = #"{"v":1,"groups":{"测试组":["yxitest-a","yxitest-b"]}}"#

    func testReadsTheContractJson() {
        let t = Groups.parse(contract)
        XCTAssertEqual(t.groups["测试组"], ["yxitest-a", "yxitest-b"])
    }

    func testEncodeKeepsTheShapeYxiHubReads() {
        let t = Groups.Table(["测试组": ["yxitest-a", "yxitest-b"]])
        // ⚠️ 不比对整串字面量 —— JSON 的键顺序不保证，那样断言会因为
        // 「v 和 groups 谁在前」这种无关紧要的事红，掩盖真正的格式漂移。
        // 契约是**结构**：顶层一个 groups 对象，值是成员名数组。
        let d = Groups.encode(t).data(using: .utf8)!
        let o = try! JSONSerialization.jsonObject(with: d) as! [String: Any]
        XCTAssertNotNil(o["groups"], "yxi-hub 读的是顶层 groups 键")
        XCTAssertEqual((o["groups"] as! [String: Any])["测试组"] as? [String], ["yxitest-a", "yxitest-b"])
    }

    func testRoundTrip() {
        let t = Groups.Table(["后端": ["cc-api", "cc-db"], "空组": []])
        XCTAssertEqual(Groups.parse(Groups.encode(t)).groups, t.groups)
    }

    /// ⚠️ **读不懂就当没有分组，绝不抛。** 这文件用户可能手改过。
    /// 为了一个坏掉的分组表让整个看板打不开，是拿主功能给附加功能陪葬。
    func testBrokenFileNeverBreaksTheBoard() {
        for bad in ["", "   ", "不是 json", "{", "[]", #"{"groups":"不是对象"}"#, #"{"v":1}"#] {
            XCTAssertTrue(Groups.parse(bad).groups.isEmpty, "「\(bad)」把它弄崩了")
        }
    }

    /// 用户明确要的：一个 agent 可以同时在好几个组里。
    func testOneSessionInSeveralGroups() {
        let t = Groups.Table(["后端": ["cc-api", "cc-db"], "上线": ["cc-api", "cc-web"]])
        XCTAssertEqual(t.groupsOf("cc-api"), ["上线", "后端"])
        // 同组的人跨它所在的全部组，且**去重**
        XCTAssertEqual(Set(t.mates(of: "cc-api")), ["cc-db", "cc-web"])
        XCTAssertEqual(t.mates(of: "cc-db"), ["cc-api"])
    }

    func testAddAndRemove() {
        var t = Groups.Table().withMember("组", "cc-a").withMember("组", "cc-b")
        XCTAssertEqual(t.groups["组"], ["cc-a", "cc-b"])
        XCTAssertEqual(t.withMember("组", "cc-a").groups["组"], ["cc-a", "cc-b"], "重复加不该变成两份")
        // ⚠️ 拿走最后一个成员，**组本身要留着** —— 组是用户建的，不该因为人走光了就没了
        t = t.withoutMember("组", "cc-a").withoutMember("组", "cc-b")
        XCTAssertNotNil(t.groups["组"], "成员空了就把组删了，用户会以为自己建的组丢了")
        XCTAssertEqual(t.groups["组"], [])
    }

    /// 用户报过：新建分组不生效。病根在界面（要点两下，少点一下静默丢掉），
    /// 这里钉住那条**唯一合理的解读**：输入框里有字 = 用户想要这个组。
    func testTypedNameMustBecomeAGroup() {
        let typed = "后端"
        let saved = typed.isEmpty ? Groups.Table() : Groups.Table().withMember(typed, "cc-api")
        XCTAssertEqual(saved.groups["后端"], ["cc-api"])
        XCTAssertFalse(saved.groups.isEmpty, "空表存下去 = 用户白填一场")
    }

    /// 写回服务器必须是**先写临时文件再原子改名** ——
    /// 直接覆盖时写到一半被打断，parse 会当成「没有分组」，用户编的组静悄悄没了。
    func testSaveIsAtomic() {
        let cmd = Groups.saveCommand(Groups.Table(["后端": ["cc-api"]]))
        XCTAssertTrue(cmd.contains("groups.json.tmp"))
        XCTAssertTrue(cmd.contains("mv "))
        XCTAssertLessThan(cmd.range(of: ".tmp")!.lowerBound, cmd.range(of: "mv ")!.lowerBound)
    }

    /// 组名里带单引号不能把写回的那条命令劈开。
    func testQuoteInGroupNameSurvives() {
        let t = Groups.Table(["it's": ["cc-a"]])
        XCTAssertEqual(Groups.parse(Groups.encode(t)).groups["it's"], ["cc-a"])
        XCTAssertTrue(Groups.saveCommand(t).contains(#"'\''"#), "单引号没转义对，命令会被劈开")
    }

    /// 「打通」那一刻：给每个成员发一句「你有队友了」。
    /// ⚠️ 组里不足两人时不发 —— 一个人的组没有队友可介绍。
    func testAnnouncementsOnlyWhenThereAreMates() {
        let solo = Groups.Table(["后端": ["cc-api"]])
        XCTAssertTrue(Groups.announcements(solo, group: "后端").isEmpty)

        let pair = Groups.Table(["后端": ["cc-api", "cc-db"]])
        let msgs = Groups.announcements(pair, group: "后端")
        XCTAssertEqual(msgs.count, 2)
        XCTAssertEqual(msgs.map(\.0).sorted(), ["cc-api", "cc-db"])
        // 每个人收到的名单里**不该有自己**
        XCTAssertTrue(msgs.first { $0.0 == "cc-api" }!.1.contains("cc-db"))
        XCTAssertFalse(msgs.first { $0.0 == "cc-api" }!.1.contains("cc-api、"))
        // 得告诉它怎么发消息，否则它知道有队友也不知道怎么找
        XCTAssertTrue(msgs[0].1.contains("yxi-hub say"))
    }
}
