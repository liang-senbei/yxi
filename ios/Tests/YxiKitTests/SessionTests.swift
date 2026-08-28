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
        // 那个空行两边的解析器都会跳过，所以它同时是 Liveness 要的心跳，白送的。
        // ⚠️ 循环改成 2 秒一轮（为了查后台进程还活着没有，见 FollowShellTests），
        // **心跳仍然是 20 秒**：2 × 10。
        let s = SSHSession.follow("cat")
        XCTAssertTrue(s.contains("sleep 2;"), s)
        XCTAssertTrue(s.contains("-ge 10"), "2 秒 × 10 = 20 秒心跳：\(s)")
        XCTAssertTrue(s.contains(#"printf '\n' || exit"#), s)
    }

    // MARK: - tmux

    /// ⚠️ 漏了 `mouse on` 的现象是「终端里怎么划都不动」，
    /// 而且完全看不出跟 tmux 配置有关 —— 所以钉住它。
    func test_attach带上鼠标上报() {
        let cmd = SSHSession.attach(session: "cc-mail")
        XCTAssertTrue(cmd.contains("set -g mouse on"), "漏了 mouse on，tmux 里滚不动历史")
        XCTAssertTrue(cmd.contains("has-session -t 'cc-mail'"))
        // ⚠️ `-d` 把别的客户端踢下去。不踢的话，电脑上也开着同一个会话时
        // 两边共用一块画布、按最小的那个排版 —— 桌面整屏花掉，手机也是错位的。
        // 「电脑上开着 + 手机遥控」正是本 app 的典型用法，触发率接近 100%。
        XCTAssertTrue(cmd.contains("attach -d -t 'cc-mail'"), "漏了 -d：\(cmd)")
        // ⚠️ 分号要转义给 tmux，不是给 shell。漏了反斜杠的话 shell 自己吃掉分号，
        // tmux 只收到第一条 set，后两条**静默丢掉** —— 包括 mouse on。
        XCTAssertTrue(cmd.contains(#"on \; set -g mouse on \;"#), "分号没转义：\(cmd)")
    }

    func test_attach不带YXI_CLIENT那类假动作() {
        // sshd 默认 PermitUserEnvironment no，setEnv 会被静默拒绝
        XCTAssertFalse(SSHSession.attach(session: "x").contains("YXI_CLIENT"))
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
