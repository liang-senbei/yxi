import Crypto
import Foundation

/// 一台主机的持久化记录。
///
/// ⚠️ [sealedPassword] 是 [Vault] 封好的**密文**，不是明文。
/// 明文密码只在「用户刚输入」和「正要认证」这两个瞬间存在于内存。
public struct Host: Codable, Identifiable, Hashable, Sendable {

    public var id: String
    /// 用户随手起的名字，**只用来指哪一条记录**。绝不能进诊断文案（见 [HostConfig.target] / #71）。
    public var alias: String
    public var hostname: String
    public var port: Int
    public var username: String
    /// 用 App 自己那把密钥认证（[KeyManager]）。装过公钥的主机走这条。
    public var useKey: Bool
    /// 记住的密码（密文）。新开的云主机一开始常常只有密码。
    public var sealedPassword: String?
    /// 最后一次成功连接时记下的主机公钥，`ssh-ed25519 AAAA…` 形式。见 [KnownHosts]。
    public var hostKey: String?
    /// 让手机为这台机器主动响。⚠️ 见 [HostStore] 头上关于 iOS 后台的那段说明。
    public var watch: Bool

    public init(
        id: String = UUID().uuidString,
        alias: String,
        hostname: String,
        port: Int = 22,
        username: String,
        useKey: Bool = true,
        sealedPassword: String? = nil,
        hostKey: String? = nil,
        watch: Bool = false
    ) {
        self.id = id
        self.alias = alias
        self.hostname = hostname
        self.port = port
        self.username = username
        self.useKey = useKey
        self.sealedPassword = sealedPassword
        self.hostKey = hostKey
        self.watch = watch
    }

    /// 老版本的 JSON 少字段也要能读回来 —— 少一个 `watch` 就整份主机列表读不出来，
    /// 用户看到的是「我的机器全没了」。
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? UUID().uuidString
        alias = try c.decode(String.self, forKey: .alias)
        hostname = try c.decode(String.self, forKey: .hostname)
        port = try c.decodeIfPresent(Int.self, forKey: .port) ?? 22
        username = try c.decode(String.self, forKey: .username)
        useKey = try c.decodeIfPresent(Bool.self, forKey: .useKey) ?? true
        sealedPassword = try c.decodeIfPresent(String.self, forKey: .sealedPassword)
        hostKey = try c.decodeIfPresent(String.self, forKey: .hostKey)
        watch = try c.decodeIfPresent(Bool.self, forKey: .watch) ?? false
    }

    /// 列表和错误文案统一用它。跟 [HostConfig.target] 是同一个值。
    public var display: String {
        "\(username)@\(hostname)" + (port == 22 ? "" : ":\(port)")
    }

    /// 地址栏里混进了非 ASCII 字符吗？有就返回是哪一个。
    ///
    /// ⚠️ `root@天亮` 在主机列表里躺了好几天没人发现，因为它看着就是一行灰字（#71）。
    /// 列表里要**标红并写明是哪个字**。
    public var suspiciousCharacter: String? {
        HostInput.suspiciousCharacter(in: hostname)
    }

    /// 把这条记录变成能连的 [HostConfig]。
    ///
    /// 拿私钥和解密密码这两件事都由调用方注入 —— 这样 [HostStore] 本身**不碰 Keychain**，
    /// 整个文件在 Linux 上能直接跑测试。
    /// 两条路都没有 → nil，界面该提示补认证信息。
    public func config(
        privateKey: () -> Curve25519.Signing.PrivateKey?,
        unseal: (String) -> String?
    ) -> HostConfig? {
        let auth: HostConfig.Auth?
        if useKey {
            auth = privateKey().map(HostConfig.Auth.privateKey)
        } else if let sealed = sealedPassword {
            auth = unseal(sealed).map(HostConfig.Auth.password)
        } else {
            auth = nil
        }
        return auth.map {
            HostConfig(alias: alias, hostname: hostname, port: port, username: username, auth: $0)
        }
    }
}

/// 主机列表的存储。
///
/// **不是预置列表** —— 必须能随时加「以后才有的」服务器（PRD §2.4）：
/// 任意 IP、任意端口、任意用户名、密码或密钥。
///
/// ⚠️⚠️ **iOS 上「盯梢」（[Host.watch]）跟安卓不是一回事，别照抄。**
/// 安卓靠前台服务常驻一条 SSH 通道 `tail -f` 事件流（G10）。
/// **iOS 没有前台服务**：App 进后台大约 30 秒内所有 socket 就会被收走，
/// `BGProcessingTask` 由系统决定什么时候跑、几分钟到几小时不等，也不保证有网络。
/// 所以 iOS 上 `watch` 只能是「**App 在前台时盯着**」，
/// 「Claude 需要你时手机主动响」这件事在 iOS 上要么接受延迟，要么必须引入推送
/// —— 而 PRD §2.7 / D8 已经明确否掉了 FCM 那条路（它要求我们长期跑一台中转服务器）。
/// **这是 iOS 版最大的一处功能落差，不是实现细节，得单独拍板。**
public final class HostStore: @unchecked Sendable {

    private let url: URL
    private let lock = NSLock()
    private var _hosts: [Host] = []

    public var hosts: [Host] { lock.withLock { _hosts } }

    public init(url: URL) {
        self.url = url
        _hosts = Self.read(url)
    }

    /// 默认落在 Application Support 下（不进 iCloud 备份的 Caches 会被系统清掉，不能用）。
    public convenience init() {
        let dir = (try? FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        )) ?? URL(fileURLWithPath: NSTemporaryDirectory())
        self.init(url: dir.appendingPathComponent("hosts.json"))
    }

    public func upsert(_ h: Host) {
        lock.withLock {
            _hosts.removeAll { $0.id == h.id }
            _hosts.append(h)
        }
        write()
    }

    public func remove(id: String) {
        lock.withLock { _hosts.removeAll { $0.id == id } }
        write()
    }

    public func get(_ id: String) -> Host? { hosts.first { $0.id == id } }

    /// 从磁盘重新读一遍。
    ///
    /// ⚠️ 安卓那边这个方法是**为一个真 bug 加的**：前台服务和界面各有一个 store 实例，
    /// 各自构造时读一次就再也不读了 —— 界面上把铃铛关掉，服务那份还是旧的，
    /// 表现是「关了它还在盯着」而且**不报任何错**。
    /// iOS 上只要出现第二个进程/扩展（比如以后加 App Intent、Widget），同样会中招。
    public func reload() {
        let fresh = Self.read(url)
        lock.withLock { _hosts = fresh }
    }

    private static func read(_ url: URL) -> [Host] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        return (try? JSONDecoder().decode([Host].self, from: data)) ?? []
    }

    private func write() {
        // ⚠️ `.atomic`：写到临时文件再 rename。半截写坏的 hosts.json =
        // 用户下次打开「我的机器全没了」，而这在手机上（随时被杀）是常态不是意外。
        // 文件保护不用手设：iOS 上 Application Support 默认就是
        // `完全保护（首次解锁之后）`，正好是我们要的 —— 锁屏时后台也读得到。
        guard let data = try? JSONEncoder().encode(hosts) else { return }
        try? data.write(to: url, options: .atomic)
    }
}
