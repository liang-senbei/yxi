import Crypto
import XCTest
@testable import YxiKit

final class HostStoreTests: XCTestCase {

    private func tempURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("yxi-test-\(UUID().uuidString)")
            .appendingPathComponent("hosts.json")
    }

    func test_存了再读回来还是那些主机() {
        let url = tempURL()
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let a = HostStore(url: url)
        a.upsert(Host(id: "1", alias: "站长机", hostname: "38.244.50.31", username: "root"))
        a.upsert(Host(id: "2", alias: "客户 Windows", hostname: "100.80.3.73", port: 2222, username: "14157", watch: true))

        let b = HostStore(url: url)
        XCTAssertEqual(b.hosts.count, 2)
        XCTAssertEqual(b.get("2")?.port, 2222)
        XCTAssertEqual(b.get("2")?.watch, true)
    }

    func test_同一个id是覆盖不是追加() {
        let url = tempURL()
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let s = HostStore(url: url)
        s.upsert(Host(id: "1", alias: "旧", hostname: "1.2.3.4", username: "root"))
        s.upsert(Host(id: "1", alias: "新", hostname: "1.2.3.4", username: "root"))
        XCTAssertEqual(s.hosts.count, 1)
        XCTAssertEqual(s.hosts.first?.alias, "新")
    }

    /// ⚠️ 老版本写下的 JSON 少字段也要能读回来。
    /// 少一个 `watch` 就整份主机列表读不出来 —— 用户看到的是「我的机器全没了」。
    func test_少字段的老JSON照样读得出来() throws {
        let url = tempURL()
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let legacy = #"[{"id":"1","alias":"老","hostname":"1.2.3.4","username":"root"}]"#
        try Data(legacy.utf8).write(to: url)

        let s = HostStore(url: url)
        XCTAssertEqual(s.hosts.count, 1)
        XCTAssertEqual(s.hosts.first?.port, 22)
        XCTAssertEqual(s.hosts.first?.useKey, true)
        XCTAssertEqual(s.hosts.first?.watch, false)
    }

    func test_文件坏了当空列表而不是崩掉() throws {
        let url = tempURL()
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("{ 半截".utf8).write(to: url)
        XCTAssertEqual(HostStore(url: url).hosts.count, 0)
    }

    /// ⚠️ 界面和后台各拿一个 store 实例，各自构造时读一次就不读了 ——
    /// 于是「把铃铛关掉，那台机器照样在被盯着」，而且不报任何错。
    func test_reload能看见别人写的改动() {
        let url = tempURL()
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let ui = HostStore(url: url)
        let background = HostStore(url: url)

        ui.upsert(Host(id: "1", alias: "a", hostname: "1.2.3.4", username: "root", watch: true))
        XCTAssertEqual(background.hosts.count, 0, "构造之后不该自己再读盘")
        background.reload()
        XCTAssertEqual(background.get("1")?.watch, true)
    }

    // MARK: - 改了地址就得忘掉指纹（#68）

    /// ⚠️ 不清的话下次连接会报「主机指纹变了」—— 那是中间人攻击的措辞，
    /// 会把一次正常的编辑吓成一次安全事件。
    func test_改地址会清掉存着的指纹() {
        let url = tempURL()
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let s = HostStore(url: url)
        s.upsert(Host(id: "1", alias: "a", hostname: "1.2.3.4", username: "root", hostKey: "ssh-ed25519 AAAA"))
        s.upsert(Host(id: "1", alias: "a", hostname: "5.6.7.8", username: "root", hostKey: "ssh-ed25519 AAAA"))
        XCTAssertNil(s.get("1")?.hostKey)
    }

    func test_改端口也清() {
        let url = tempURL()
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let s = HostStore(url: url)
        s.upsert(Host(id: "1", alias: "a", hostname: "1.2.3.4", username: "root", hostKey: "ssh-ed25519 AAAA"))
        s.upsert(Host(id: "1", alias: "a", hostname: "1.2.3.4", port: 8443, username: "root", hostKey: "ssh-ed25519 AAAA"))
        XCTAssertNil(s.get("1")?.hostKey)
    }

    /// 只改别名/用户名不该把指纹丢掉 —— 那会让用户下次连接白白再确认一遍。
    func test_只改别名不清指纹() {
        let url = tempURL()
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let s = HostStore(url: url)
        s.upsert(Host(id: "1", alias: "旧", hostname: "1.2.3.4", username: "root", hostKey: "ssh-ed25519 AAAA"))
        s.upsert(Host(id: "1", alias: "新", hostname: "1.2.3.4", username: "root", hostKey: "ssh-ed25519 AAAA"))
        XCTAssertEqual(s.get("1")?.hostKey, "ssh-ed25519 AAAA")
    }

    // MARK: - 认证方式的选择

    func test_装过公钥的走密钥() {
        let key = Curve25519.Signing.PrivateKey()
        let h = Host(id: "1", alias: "a", hostname: "1.2.3.4", username: "root", useKey: true)
        let cfg = h.config(privateKey: { key }, unseal: { _ in nil })
        guard case .privateKey = cfg?.auth else { return XCTFail("该走密钥") }
    }

    func test_没公钥就用记住的密码() {
        let h = Host(id: "1", alias: "a", hostname: "1.2.3.4", username: "root",
                     useKey: false, sealedPassword: "handle-x")
        let cfg = h.config(privateKey: { nil }, unseal: { $0 == "handle-x" ? "s3cret" : nil })
        guard case .password(let pw) = cfg?.auth else { return XCTFail("该走密码") }
        XCTAssertEqual(pw, "s3cret")
    }

    func test_两条都没有就是nil而不是裸连() {
        let h = Host(id: "1", alias: "a", hostname: "1.2.3.4", username: "root", useKey: false)
        XCTAssertNil(h.config(privateKey: { nil }, unseal: { _ in nil }))
    }

    /// ⚠️ 报错文案只许用真正连的目标（#71）。别名是用户随手起的，
    /// 它可以是任何东西 —— 包括一个格式正确、会把排查带偏的 IP。
    func test_display用的是真地址不是别名() {
        let h = Host(id: "1", alias: "216.36.108.147", hostname: "天亮", port: 2222, username: "root")
        XCTAssertEqual(h.display, "root@天亮:2222")
        XCTAssertEqual(h.suspiciousCharacter, "「天」(U+5929)")
    }

    func test_默认端口不出现在display里() {
        let h = Host(id: "1", alias: "x", hostname: "1.2.3.4", username: "root")
        XCTAssertEqual(h.display, "root@1.2.3.4")
    }
}
