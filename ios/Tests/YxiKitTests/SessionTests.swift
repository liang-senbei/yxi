import Crypto
import NIOSSH
import XCTest
@testable import YxiKit

/// 不需要真服务器就能钉住的那些东西：心跳判据、远端 shell 片段、公钥行的形状。
final class SessionTests: XCTestCase {

    // MARK: - 心跳（NIOSSH 没有 keepalive，这是我们自己的那套）

    func test_刚收到字节不算死() {
        XCTAssertFalse(Liveness.terminal.isDead(lastActivity: Date()))
    }

    func test_超过阈值算死() {
        let long_ago = Date().addingTimeInterval(-10)
        XCTAssertTrue(Liveness.terminal.isDead(lastActivity: long_ago))
    }

    /// ⚠️ #81：终端那条 2s×2 判死是对的（用户正盯着看），
    /// **常驻那条照抄就是灾难** —— 手机被调度出去一下就丢两拍，
    /// 连接被干掉而上层没人管。所以两个阈值必须差一个量级。
    func test_常驻那条明显比终端宽松() {
        let idle = Date().addingTimeInterval(-20)
        XCTAssertTrue(Liveness.terminal.isDead(lastActivity: idle))
        XCTAssertFalse(Liveness.background.isDead(lastActivity: idle))
    }

    // MARK: - follow()：让远端进程跟着通道一起死

    func test_follow把两个trap分开写() {
        // ⚠️ 合成一个 `trap 'kill …' EXIT PIPE …` 的话，收到 SIGPIPE 会执行完 handler
        // **继续跑循环** —— tail 是杀掉了，外壳自己却永远不退，
        // 于是每次重连都在服务器上留一个空壳进程。
        let s = SSHSession.follow("tail -n 300 -f ~/.yxi/events.jsonl")
        XCTAssertTrue(s.contains("trap 'kill $__p 2>/dev/null' EXIT;"))
        XCTAssertTrue(s.contains("trap 'exit' PIPE HUP TERM INT;"))
        XCTAssertFalse(s.contains("EXIT PIPE"), "两个 trap 被合并了")
    }

    func test_follow每20秒吐一个空行当心跳() {
        // 那个空行两边的解析器都会跳过，所以它同时是 Liveness 要的心跳，白送的
        let s = SSHSession.follow("cat")
        XCTAssertTrue(s.contains("sleep 20"))
        XCTAssertTrue(s.contains(#"printf '\n' || exit"#))
    }

    // MARK: - 公钥行

    /// ⚠️⚠️ 全项目最贵的一次事故（#65）：安卓给**每台设备**写的注释都是 `yxi@android`，
    /// 开发脚本按这个注释过滤 `authorized_keys`，于是把用户真手机的钥匙删了。
    func test_两把钥匙的注释必须不一样() {
        let a = KeyIdentity(Curve25519.Signing.PrivateKey()).comment
        let b = KeyIdentity(Curve25519.Signing.PrivateKey()).comment
        XCTAssertNotEqual(a, b, "注释在所有设备上都一样 —— #65 就是这么发生的")
        XCTAssertTrue(a.hasPrefix("yxi@ios-"))
        XCTAssertNotEqual(a, "yxi@android", "别再撞安卓那个标签")
    }

    func test_同一把钥匙的注释是稳定的() throws {
        let key = Curve25519.Signing.PrivateKey()
        XCTAssertEqual(KeyIdentity(key).comment, KeyIdentity(key).comment)
    }

    func test_公钥行能被解析回同一把钥匙() throws {
        let key = Curve25519.Signing.PrivateKey()
        let id = KeyIdentity(key)
        let line = id.authorizedKeysLine
        XCTAssertEqual(line.split(separator: " ").count, 3, "应当是 `算法 base64 注释` 三段")
        XCTAssertEqual(try NIOSSHPublicKey(openSSHPublicKey: line), id.publicKey)
    }

    func test_指纹跟公钥对得上() throws {
        let id = KeyIdentity(publicKey: try NIOSSHPublicKey(openSSHPublicKey: KnownHostsTests.line))
        XCTAssertEqual(id.fingerprint, KnownHostsTests.fingerprint)
    }
}
