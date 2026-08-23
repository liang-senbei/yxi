import Crypto
import Foundation
import NIOCore
@preconcurrency import NIOSSH   // NIOSSHPublicKey 没标 Sendable，这里只是读它，加这个别让告警刷屏

/// 一把 ed25519 密钥对外长什么样：`authorized_keys` 里那一行、指纹、注释。
///
/// **纯逻辑，Linux 上能直接测** —— 而且能拿 `ssh-keygen -lf` 的真实输出当黄金值对。
/// 安卓那边这块出过一个**安全洞**（#21）且正向用例全绿，所以这里的测试
/// 必须是「跟系统 ssh-keygen 对得上」，不是「跟我自己算的对得上」。
public struct KeyIdentity: Sendable {

    public let publicKey: NIOSSHPublicKey

    public init(_ privateKey: Curve25519.Signing.PrivateKey) {
        self.publicKey = NIOSSHPrivateKey(ed25519Key: privateKey).publicKey
    }

    public init(publicKey: NIOSSHPublicKey) {
        self.publicKey = publicKey
    }

    /// `SHA256:…`，跟服务器上 `ssh-keygen -lf` 一致。
    public var fingerprint: String { KnownHosts.fingerprint(publicKey) }

    /// ⚠️⚠️ **注释必须带上这把钥匙自己的标识，不能是所有设备共用的一个词。**
    ///
    /// 这条是整个项目最贵的一次事故（TROUBLESHOOTING #65）：
    /// 安卓的 `KeyManager` 给**每台设备**写的注释都是 `yxi@android`，
    /// 而开发脚本 `dev/seed.sh` 按这个注释过滤 `authorized_keys` ——
    /// 于是每跑一次开发脚本，就把**用户真手机的公钥**从服务器上删一次。
    /// 症状是他那头「连不上」，服务器这头查什么都正常，
    /// 唯一线索是 sshd 日志里他那把钥匙的指纹一次都没出现过。
    ///
    /// 这里的注释由**公钥自己**派生（同一把钥匙永远同一个后缀，两把钥匙必然不同），
    /// 于是「按注释过滤」这种脚本在结构上就不可能误伤别的设备。
    public var comment: String {
        var buf = ByteBuffer()
        publicKey.write(to: &buf)
        let digest = SHA256.hash(data: Data(buf.readableBytesView))
        let tag = digest.prefix(4).map { String(format: "%02x", $0) }.joined()
        return "yxi@ios-\(tag)"
    }

    /// 贴进 `~/.ssh/authorized_keys` 的那一行。
    public var authorizedKeysLine: String {
        "\(String(openSSHPublicKey: publicKey)) \(comment)"
    }
}

/// App 自己的那一把 SSH 密钥。
///
/// **ed25519**，用 swift-crypto 生成，私钥 32 字节存 Keychain。
///
/// ⚠️ **iOS 上没有安卓那两个坑**，对着看一眼省得以为漏了什么：
///   · 安卓要注册 BouncyCastle 才有 `Ed25519` 签名算法（#12）——
///     swift-crypto 自带 Curve25519，**什么都不用注册**。
///   · 安卓要把私钥导成 OpenSSH v1 文本才能喂给 jsch，用错 API 抛的异常
///     message 还是 null（#20）—— 这里私钥**从头到尾是个类型，不变成字符串**，
///     也就没有「不小心打进日志」的机会。
///
/// **撤销方式**跟安卓一样：服务器上删掉 `authorized_keys` 里对应那一行，App 不用改任何配置。
#if canImport(Security)
public final class KeyManager: @unchecked Sendable {

    private static let account = "app.yxi.identity.ed25519"

    private let lock = NSLock()
    private var cached: Curve25519.Signing.PrivateKey?

    public init() {}

    public var exists: Bool { Vault.read(account: Self.account) != nil }

    /// 没有就当场生成一把。
    public func privateKey() throws -> Curve25519.Signing.PrivateKey {
        try lock.withLock {
            if let cached { return cached }
            if let raw = Vault.read(account: Self.account),
               let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: raw) {
                cached = key
                return key
            }
            let fresh = Curve25519.Signing.PrivateKey()
            try Vault.write(fresh.rawRepresentation, account: Self.account)
            cached = fresh
            return fresh
        }
    }

    public func identity() throws -> KeyIdentity { KeyIdentity(try privateKey()) }

    /// 重新生成一把。
    ///
    /// ⚠️ **旧公钥立刻失效**，所有装过它的服务器都要重装 ——
    /// 而这件事可能因为清数据、换机而**悄悄发生**，现象只是「突然连不上」。
    /// 界面上必须是带后果说明的二次确认（安卓那边就是这么做的）。
    public func regenerate() throws -> KeyIdentity {
        try lock.withLock {
            let fresh = Curve25519.Signing.PrivateKey()
            try Vault.write(fresh.rawRepresentation, account: Self.account)
            cached = fresh
            return KeyIdentity(fresh)
        }
    }
}

// ponytail: 私钥是「软件密钥 + Keychain 硬件加密落盘」，跟安卓现在的水平一样。
// 真正的硬件绑定（私钥**离不开芯片**）在 iOS 上是有路的：NIOSSH 有公开的
// `NIOSSHPrivateKey(secureEnclaveP256Key:)`（已核对源码，Darwin 上可用），
// 对应 SSH 的 `ecdsa-sha2-nistp256`。代价是 Citadel 的 `SSHAuthenticationMethod`
// 没有对应的便捷构造，得自己实现一个 `NIOSSHClientUserAuthenticationDelegate`，
// 而且 **Secure Enclave 只支持 P-256，永远不可能是 ed25519**。
// 等「私钥不可导出」成为真需求时再做，别现在为它换算法。
#endif
