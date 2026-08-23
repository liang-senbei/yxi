@preconcurrency import Crypto   // Curve25519 的私钥类型没标 Sendable
import Foundation

/// 一台主机的连接配置 —— 连接那一刻真正用到的东西。
///
/// ⚠️ **端口不能写死 22**：客户那台 Windows 走 2222，而且手机在移动网络下
/// 22 出站常被运营商屏蔽，这台服务器为此专门开了 8443（见 handover「SSH 接入」）。
///
/// ⚠️ **两种认证都要**：新开的云主机一开始**只有密码**，装完公钥才能切密钥。
/// 少了密码认证，「在手机上现加一台刚开的机器」这条路就断了（PRD §2.4）。
public struct HostConfig: Sendable {

    public enum Auth: Sendable {
        case password(String)
        /// ⚠️ **直接拿 `Curve25519.Signing.PrivateKey`，不走 PEM。**
        /// 安卓那边被迫在内存里生成 OpenSSH v1 格式的私钥文本再喂给 jsch
        /// （TROUBLESHOOTING #20：`writePrivateKey()` 对 ed25519 直接抛异常，
        /// 而且 message 是 null），iOS 上这一整类问题不存在 ——
        /// swift-crypto 的私钥就是 32 字节 `rawRepresentation`，
        /// Citadel 的 `.ed25519(username:privateKey:)` 直接收这个类型。
        /// **私钥全程不变成字符串，也就没有「不小心打进日志」的机会。**
        case privateKey(Curve25519.Signing.PrivateKey)
    }

    public var alias: String
    public var hostname: String
    public var port: Int
    public var username: String
    public var auth: Auth

    public init(alias: String, hostname: String, port: Int = 22, username: String, auth: Auth) {
        self.alias = alias
        self.hostname = hostname
        self.port = port
        self.username = username
        self.auth = auth
    }

    /// 报错文案里**只许用这个**，绝不许用 [alias]。
    ///
    /// ⚠️ TROUBLESHOOTING #71 是这个项目最贵的一条：错误信息报的是用户起的**名字**
    /// 而不是它真正连的地址。那台主机名字栏填的是 `216.36.108.147`、地址栏填的是
    /// SSH 别名 `天亮` —— 于是「连不上 216.36.108.147」把排查整个带进了
    /// 端口/防火墙的死胡同，而 App 从来没连过那个 IP。
    /// **用户能自己起名的字段，永远不能出现在诊断信息里当事实。**
    public var target: String {
        "\(username)@\(hostname)" + (port == 22 ? "" : ":\(port)")
    }
}
