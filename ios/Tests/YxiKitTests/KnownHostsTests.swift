import Crypto
import NIOSSH
import XCTest
@testable import YxiKit

/// 主机指纹校验。
///
/// ⚠️ **这个文件的价值全在「应该失败」那几条。** 安卓的 #21 / #22 两个安全洞
/// **正向用例全绿时都静静躺着**，只有专门测反向用例才露出来。
/// #26 的结论：**绿的测试在没见它红过之前不算数** ——
/// 改这里的实现之前，先把断言故意弄坏一次，确认它真的会红。
final class KnownHostsTests: XCTestCase {

    /// 用系统 `ssh-keygen -t ed25519` 真生成的一把，连指纹一起抄下来的。
    /// **黄金值必须来自 ssh-keygen，不能是自己算出来再抄回去的** ——
    /// 那样等于用同一个错误验证同一个错误（#21 就是这么躺了很久的）。
    static let line = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIB8Rg2YyVPtYhj6aBDGKWa8F0JKcKAF996n0th//bSuN test-vector"
    static let fingerprint = "SHA256:a2JusuOSjUY/3pQ1K5bG7MwTs0gPTi+ryJc4U3+jpLU"

    func key() throws -> NIOSSHPublicKey { try NIOSSHPublicKey(openSSHPublicKey: Self.line) }

    func other() -> NIOSSHPublicKey {
        NIOSSHPrivateKey(ed25519Key: Curve25519.Signing.PrivateKey()).publicKey
    }

    // MARK: - 指纹

    func test_指纹跟ssh_keygen逐字节一致() throws {
        XCTAssertEqual(KnownHosts.fingerprint(try key()), Self.fingerprint)
    }

    func test_序列化出来就是authorized_keys那一行() throws {
        // 注释不带（那是调用方拼的），前两段必须一模一样
        XCTAssertEqual(KnownHosts.serialize(try key()), Self.line.split(separator: " ").prefix(2).joined(separator: " "))
    }

    func test_存下来再读回去还是同一把() throws {
        let k = try key()
        let again = try NIOSSHPublicKey(openSSHPublicKey: KnownHosts.serialize(k))
        XCTAssertEqual(again, k)
    }

    // MARK: - 判定表（正向）

    func test_没存过就是第一次见() throws {
        XCTAssertEqual(KnownHosts.decide(stored: nil, incoming: try key()), .unknown)
        XCTAssertEqual(KnownHosts.decide(stored: "", incoming: try key()), .unknown)
    }

    func test_记住之后同一把必须是trusted() throws {
        let k = try key()
        XCTAssertEqual(KnownHosts.decide(stored: KnownHosts.serialize(k), incoming: k), .trusted)
    }

    /// 存的那份**带注释**（比如是从 `authorized_keys` 里抄来的）也要认得出来。
    func test_带注释的存量也认() throws {
        XCTAssertEqual(KnownHosts.decide(stored: Self.line, incoming: try key()), .trusted)
    }

    // MARK: - 判定表（反向 —— 这几条才是重点）

    func test_换了一把钥匙必须是changed() throws {
        // #21：安卓上这条分支曾经**永远走不到**，因为存的和读的编码方式不一样。
        // 表现是「每次连都当第一次」，而中间人防护整个失效。
        XCTAssertEqual(
            KnownHosts.decide(stored: KnownHosts.serialize(try key()), incoming: other()),
            .changed
        )
    }

    func test_存的那份读不出来也算changed而不是unknown() throws {
        // 宁可拒，不能降级成「第一次见」—— 那正是 #21 的失效方式
        XCTAssertEqual(KnownHosts.decide(stored: "这不是一把公钥", incoming: try key()), .changed)
        XCTAssertEqual(KnownHosts.decide(stored: "ssh-ed25519 AAAA截断了", incoming: try key()), .changed)
    }

    // MARK: - 闸门：指纹变了连问都不问

    func test_指纹变了不会去问用户() async throws {
        // #22：jsch 的 `ask` 模式在 CHANGED 时**也**弹窗，用户点一下就连上了。
        // 这里要钉死：`.changed` 这条路上 prompt **一次都不能被叫到**。
        let asked = Counter()
        let gate = HostKeyGate(
            target: "root@example.com",
            stored: { KnownHostsTests.line },
            remember: { _ in XCTFail("指纹变了还去记新钥匙 —— 这就是把防线拆了") },
            prompt: CountingPrompt(answer: true, counter: asked)
        )
        let loop = TestLoop()
        let promise = loop.promise()
        gate.validateHostKey(hostKey: other(), validationCompletePromise: promise)
        await XCTAssertThrowsErrorAsync(try await promise.futureResult.get())
        XCTAssertEqual(asked.value, 0, "指纹变了却弹了确认框")
    }

    func test_第一次见问过并且记下来() async throws {
        let asked = Counter()
        let remembered = Box<String?>(nil)
        let gate = HostKeyGate(
            target: "root@example.com",
            stored: { nil },
            remember: { remembered.value = $0 },
            prompt: CountingPrompt(answer: true, counter: asked)
        )
        let loop = TestLoop()
        let promise = loop.promise()
        gate.validateHostKey(hostKey: try key(), validationCompletePromise: promise)
        try await promise.futureResult.get()
        XCTAssertEqual(asked.value, 1)
        XCTAssertEqual(remembered.value, KnownHosts.serialize(try key()))
    }

    func test_用户不点信任就断开并且不记() async throws {
        let remembered = Box<String?>(nil)
        let gate = HostKeyGate(
            target: "root@example.com",
            stored: { nil },
            remember: { remembered.value = $0 },
            prompt: CountingPrompt(answer: false, counter: Counter())
        )
        let loop = TestLoop()
        let promise = loop.promise()
        gate.validateHostKey(hostKey: try key(), validationCompletePromise: promise)
        await XCTAssertThrowsErrorAsync(try await promise.futureResult.get())
        XCTAssertNil(remembered.value)
    }

    /// 没有 UI 可问时必须是保守拒绝，而且**得是显式写出来的**（#24）。
    func test_denyEverything一律拒() async throws {
        let gate = HostKeyGate(
            target: "root@example.com",
            stored: { nil },
            remember: { _ in XCTFail("没人确认过却记下来了") },
            prompt: .denyEverything
        )
        let loop = TestLoop()
        let promise = loop.promise()
        gate.validateHostKey(hostKey: try key(), validationCompletePromise: promise)
        await XCTAssertThrowsErrorAsync(try await promise.futureResult.get())
    }

    /// 用户把 App 切走再也不回来 —— 悬着不动比错连安全，但也不能永远悬着。
    func test_用户一直不理会按拒绝算() async throws {
        let gate = HostKeyGate(
            target: "root@example.com",
            stored: { nil },
            remember: { _ in XCTFail("超时了还记下来") },
            prompt: NeverAnsweringPrompt(),
            timeout: .milliseconds(120)
        )
        let loop = TestLoop()
        let promise = loop.promise()
        gate.validateHostKey(hostKey: try key(), validationCompletePromise: promise)
        await XCTAssertThrowsErrorAsync(try await promise.futureResult.get())
    }
}
