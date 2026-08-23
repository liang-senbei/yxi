import XCTest
@testable import YxiKit

/// 地址输入的归一与拆分。**这一整个文件对应 TROUBLESHOOTING #58 / #59。**
final class HostInputTests: XCTestCase {

    // MARK: - normalize

    func test_全角句点会被换成半角() {
        // 用户在真机上填的就是这个，他盯着看觉得完全正确（#58）
        XCTAssertEqual(HostInput.normalize("２１６．３６．１０８．１４７"), "216.36.108.147")
    }

    func test_全角冒号at符和破折号() {
        XCTAssertEqual(HostInput.normalize("ｒｏｏｔ＠ｈｏｓｔ－ａ：２２"), "root@host-a:22")
    }

    func test_中文句号和半角片假名句号也当点() {
        XCTAssertEqual(HostInput.normalize("10。0。0。1"), "10.0.0.1")
        XCTAssertEqual(HostInput.normalize("10｡0｡0｡1"), "10.0.0.1")
    }

    func test_零宽字符被清掉() {
        // 肉眼完全看不见的那一类。输入法会插，复制粘贴也会带
        XCTAssertEqual(HostInput.normalize("1.2.\u{200B}3.\u{FEFF}4"), "1.2.3.4")
    }

    func test_空白和全角空格都清掉() {
        XCTAssertEqual(HostInput.normalize("  1.2.3.4\n"), "1.2.3.4")
        XCTAssertEqual(HostInput.normalize("1.2.\u{3000}3.4"), "1.2.3.4")
    }

    func test_normalize是幂等的() {
        // 它会被放进每次按键的回调里，不幂等就会在用户手底下改他打的东西（#59）
        for raw in ["２１６．３６．１０８．１４７", "root@1.2.3.4:2222", "ssh://a@b:1", "普通"] {
            let once = HostInput.normalize(raw)
            XCTAssertEqual(HostInput.normalize(once), once, "normalize 不幂等：\(raw)")
        }
    }

    // MARK: - parse

    func test_拆出用户名端口() {
        let p = HostInput.parse("root@216.36.108.147:2222")
        XCTAssertEqual(p.host, "216.36.108.147")
        XCTAssertEqual(p.user, "root")
        XCTAssertEqual(p.port, 2222)
    }

    func test_裸地址不猜端口和用户名() {
        let p = HostInput.parse("216.36.108.147")
        XCTAssertEqual(p.host, "216.36.108.147")
        XCTAssertNil(p.port)
        XCTAssertNil(p.user)
    }

    func test_ssh协议头和多余路径都去掉() {
        let p = HostInput.parse("ssh://root@example.com:22/some/path")
        XCTAssertEqual(p.host, "example.com")
        XCTAssertEqual(p.user, "root")
        XCTAssertEqual(p.port, 22)
    }

    func test_裸IPv6不会被当成带端口() {
        // ⚠️ 只有**一个**冒号才当端口。这条拆错的话地址会被切碎，
        // 而报错完全看不出是这个原因
        let p = HostInput.parse("fe80::1")
        XCTAssertEqual(p.host, "fe80::1")
        XCTAssertNil(p.port)
    }

    func test_方括号形式的IPv6带端口() {
        let p = HostInput.parse("[fe80::1]:2222")
        XCTAssertEqual(p.host, "fe80::1")
        XCTAssertEqual(p.port, 2222)
    }

    func test_不合法端口不拆() {
        // `host:notanumber` 整串还是 host，别把它切成一半
        let p = HostInput.parse("example.com:0")
        XCTAssertEqual(p.host, "example.com:0")
        XCTAssertNil(p.port)
    }

    func test_全角输入也能拆() {
        let p = HostInput.parse("ｒｏｏｔ＠２１６．３６．１０８．１４７：２２２２")
        XCTAssertEqual(p.host, "216.36.108.147")
        XCTAssertEqual(p.user, "root")
        XCTAssertEqual(p.port, 2222)
    }

    // MARK: - 可疑字符

    func test_指出具体是哪个字符() {
        // 只说「地址解析不了」等于没说 —— 用户看着那个地址觉得它是对的
        let msg = HostInput.suspiciousCharacter(in: "天亮")
        XCTAssertEqual(msg, "「天」(U+5929)")
    }

    func test_纯ASCII没有可疑字符() {
        XCTAssertNil(HostInput.suspiciousCharacter(in: "216.36.108.147"))
    }

    // MARK: - 预览（告知但不动用户的输入）

    func test_有可拆的东西才给预览() {
        XCTAssertNil(HostInput.splitPreview("216.36.108.147"))
        let preview = HostInput.splitPreview("root@1.2.3.4:2222")
        XCTAssertEqual(preview, "保存时会拆成：地址 1.2.3.4 · 用户名 root · 端口 2222")
    }
}
