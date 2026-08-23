import Crypto
import Foundation
import NIOCore
import NIOSSH

/// 首次见到某台主机时要用户拍板。
///
/// ⚠️ **指纹变了不会走到这里** —— 那种情况直接拒（见 [KnownHosts] 的判定表）。
public protocol TrustPrompt: Sendable {
    /// 返回 true = 用户确认信任这台主机。实现方负责弹 UI。
    /// `target` 是 `user@host:port`，**不是别名**（#71）。
    func confirmNewHost(target: String, fingerprint: String) async -> Bool
}

/// 没有 UI 可问时用它。
///
/// ⚠️ **这个类型存在的唯一理由是把「没人可问」变成一个要动手写出来的词。**
/// 安卓上出过 #24：图省事写了 `KnownHosts(store, id, null)`，
/// 于是「没见过这台主机」时无从询问只能保守拒绝 —— 而那个失败的表现
/// **跟真的中间人攻击一模一样**，差点往「服务器被劫持了」的方向查。
/// 所以这里 prompt 不给 optional：要拒就 `.denyEverything`，grep 得到。
public struct DenyingTrustPrompt: TrustPrompt {
    public init() {}
    public func confirmNewHost(target: String, fingerprint: String) async -> Bool { false }
}

extension TrustPrompt where Self == DenyingTrustPrompt {
    public static var denyEverything: DenyingTrustPrompt { DenyingTrustPrompt() }
}

/// 主机指纹校验 —— **SSH 抵御中间人的唯一防线**。
///
/// | 存过的 | 收到的 | 结论 |
/// |---|---|---|
/// | 没有 | 任意 | `.unknown` → 显示指纹，用户拍板才连，连上后记下来 |
/// | 有，且对得上 | 同一把 | `.trusted` → 直连 |
/// | 有，但对不上 | 换了一把 | `.changed` → **直接拒，连问都不问** |
/// | 有，但读不出来 | 任意 | `.changed` → 也拒。见下面第三条 |
///
/// ⚠️ **一：`.changed` 绝不能弹「要不要继续」。**
/// 安卓踩过（TROUBLESHOOTING #22）：jsch 的 `StrictHostKeyChecking=ask`
/// 在指纹**变了**时也走同一个确认弹窗，用户点一下就连上了 ——
/// 社工一句「服务器刚重装过」就能骗过这道防线。
/// 真是重装了，用户应当去主机列表**显式删掉再重加**，那是一个有意识的动作。
///
/// ⚠️ **二：iOS 上这个坑换了张脸，但同样在。**
/// Citadel 的 `SSHHostKeyValidator` 只有三种：`.trustedKeys(Set)` /
/// `.acceptAnything()` / `.custom(delegate)`。
/// **`.acceptAnything()` 是最省事的那个，而它就是 `StrictHostKeyChecking=no`**
/// —— 谁冒充这个地址都连，加密白做。**这个 App 里任何地方都不许出现它。**
/// `.trustedKeys` 也不行：它没有「第一次见」这个概念，没法做首连确认。
/// 只能走 `.custom`，并且把「问用户」**只接在 `.unknown` 这一条分支上**。
///
/// ⚠️ **三：存的东西读不出来 = 拒，不是「当没见过」。**
/// 安卓的 #21 就死在这个语义上：`add()` 里把已经是 base64 的
/// `HostKey.getKey()` 又编码了一次，存进去的跟读出来的永远对不上，
/// **于是 `CHANGED` 这条分支永远走不到**，中间人防护完全失效 ——
/// 而所有正向用例全绿。#26 的结论：**绿的测试在没见它红过之前不算数。**
public enum KnownHosts {

    public enum Verdict: Equatable, Sendable {
        case unknown
        case trusted
        case changed
    }

    public enum Rejection: Error, Equatable {
        /// 指纹变了。文案里带上真正连的目标（`user@host:port`），别用别名（#71）。
        case fingerprintChanged(target: String, expected: String, got: String)
        /// 用户没点「信任」，或者等超时了。
        case notTrusted(target: String)
    }

    /// 判定表本体。**纯逻辑，可以单独测**，而且必须专门测「应该失败」那几条（#26）。
    public static func decide(stored: String?, incoming: NIOSSHPublicKey) -> Verdict {
        guard let stored, !stored.isEmpty else { return .unknown }
        guard let known = try? NIOSSHPublicKey(openSSHPublicKey: stored) else {
            // 存过东西但解不出来 —— 见上面第三条。宁可拒，不能降级成「第一次见」。
            return .changed
        }
        return known == incoming ? .trusted : .changed
    }

    /// `SHA256:…` 形式，**跟服务器上 `ssh-keygen -lf` 的输出逐字节一致**
    /// （已用真实 ed25519 密钥对过，见 `KnownHostsTests`）。
    public static func fingerprint(_ key: NIOSSHPublicKey) -> String {
        var buf = ByteBuffer()
        key.write(to: &buf)
        let digest = SHA256.hash(data: Data(buf.readableBytesView))
        // OpenSSH 的指纹不带 base64 的 `=` 填充
        return "SHA256:" + Data(digest).base64EncodedString().replacingOccurrences(of: "=", with: "")
    }

    /// 存进 [Host.hostKey] 的形式：`ssh-ed25519 AAAA…`（跟 `authorized_keys` 同一种写法）。
    public static func serialize(_ key: NIOSSHPublicKey) -> String {
        String(openSSHPublicKey: key)
    }
}

/// 接在 Citadel `SSHHostKeyValidator.custom(...)` 上的那道闸。
///
/// ⚠️ **fail-closed 靠的是结构，不是靠代码写对**（#49）：
/// 下面那个 `switch` 没有 `default`，`.changed` 在自己那一支里立刻 `fail`，
/// **「问用户」这段代码在语法上就只长在 `.unknown` 下面**。
/// 想把它接到 `.changed` 上得先把结构改了 —— 那不是「写错一行」能达成的。
public final class HostKeyGate: NIOSSHClientServerAuthenticationDelegate, @unchecked Sendable {

    private let target: String
    private let stored: @Sendable () -> String?
    private let remember: @Sendable (String) -> Void
    private let prompt: TrustPrompt
    private let timeout: Duration

    /// - Parameters:
    ///   - target: `user@host:port`。**只用于给用户看和写进错误里**，别传别名（#71）。
    ///   - stored: 现在记着的主机公钥（`ssh-ed25519 AAAA…`），没有就 nil。
    ///   - remember: 用户确认之后把新公钥存起来。
    ///   - prompt: 首连时问谁。没有 UI 就传 `.denyEverything`（#24）。
    ///   - timeout: 用户一直不理会 → 按拒绝算。**悬着不动比错连安全。**
    public init(
        target: String,
        stored: @escaping @Sendable () -> String?,
        remember: @escaping @Sendable (String) -> Void,
        prompt: TrustPrompt,
        timeout: Duration = .seconds(120)
    ) {
        self.target = target
        self.stored = stored
        self.remember = remember
        self.prompt = prompt
        self.timeout = timeout
    }

    public func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise: EventLoopPromise<Void>) {
        let incoming = KnownHosts.serialize(hostKey)
        switch KnownHosts.decide(stored: stored(), incoming: hostKey) {

        case .trusted:
            validationCompletePromise.succeed(())

        case .changed:
            // ⚠️ **连问都不问。** 这里一旦加上「要不要继续」，#22 就在 iOS 上复活了。
            validationCompletePromise.fail(
                KnownHosts.Rejection.fingerprintChanged(
                    target: target,
                    expected: stored().map { (try? NIOSSHPublicKey(openSSHPublicKey: $0)).map(KnownHosts.fingerprint) ?? "（存的那份读不出来）" } ?? "?",
                    got: KnownHosts.fingerprint(hostKey)
                )
            )

        case .unknown:
            // ⚠️ 这个回调跑在 NIO 的 event loop 上，**不能阻塞**。
            // 安卓那边是开线程 + CountDownLatch 等 UI；这里用 Task，
            // 但同样要有超时 —— 用户把 App 切走再也不回来，promise 不兑现的话
            // 这条连接会一直挂着，界面上是「转圈转到天荒地老」。
            let prompt = self.prompt
            let remember = self.remember
            let target = self.target
            let fingerprint = KnownHosts.fingerprint(hostKey)
            let timeout = self.timeout
            Task {
                let ok = await withTimeout(timeout) {
                    await prompt.confirmNewHost(target: target, fingerprint: fingerprint)
                } ?? false
                if ok {
                    remember(incoming)
                    validationCompletePromise.succeed(())
                } else {
                    validationCompletePromise.fail(KnownHosts.Rejection.notTrusted(target: target))
                }
            }
        }
    }
}

/// 超时就返回 nil。写在这里是因为只有这一处用得上，没必要单开一个文件。
func withTimeout<T: Sendable>(
    _ duration: Duration,
    _ work: @escaping @Sendable () async -> T
) async -> T? {
    await withTaskGroup(of: T?.self) { group in
        group.addTask { await work() }
        group.addTask {
            try? await Task.sleep(for: duration)
            return nil
        }
        let first = await group.next() ?? nil
        group.cancelAll()
        return first
    }
}
