import XCTest
@testable import YxiKit

final class AttachmentsTests: XCTestCase {

    func test_开头几行摘出来_正文留下() {
        let t = "[图片1] /root/src/tmp/x/1-a.jpg\n[附件1] /root/src/tmp/x/2-b.pdf\n看看这个"
        let r = Attachments.parseRefs(t)
        XCTAssertEqual(r.refs.map(\.label), ["图片1", "附件1"])
        XCTAssertEqual(r.refs.map(\.isImage), [true, false])
        XCTAssertEqual(r.refs[0].name, "1-a.jpg")
        XCTAssertEqual(r.body, "看看这个")
    }

    /// 正文里长得像附件头的行不能被吃掉 —— 只认开头连续的
    func test_只认开头连续的() {
        let t = "先说一句\n[图片1] /root/x.png"
        let r = Attachments.parseRefs(t)
        XCTAssertTrue(r.refs.isEmpty)
        XCTAssertEqual(r.body, t)
    }

    /// 直接发一张图、一个字没打：有引用、正文为空。扩展名大小写不敏感。
    func test_只有附件没正文() {
        let r = Attachments.parseRefs("[Image 1] /a/b.HEIC\n")
        XCTAssertEqual(r.refs.count, 1)
        XCTAssertTrue(r.refs[0].isImage)
        XCTAssertEqual(r.body, "")
    }
}
