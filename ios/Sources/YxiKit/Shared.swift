import Foundation

/// 主 App 跟扩展（分享扩展 / 小组件）之间**唯一**的交换区。
///
/// ⚠️⚠️ **扩展是另一个进程、另一个沙盒**，`Application Support` 那份 `hosts.json`
/// 它一个字节都读不到。合法的共享通道只有 App Group，而 App Group 靠 entitlement 生效。
///
/// ⚠️ **这个文件被编进三个 target**（YxiKit + 两个扩展，见 `project.yml` 的 `sources`），
/// 不是 `import YxiKit`：扩展要链宿主的 framework 就得把 `LD_RUNPATH_SEARCH_PATHS`
/// 指回 `../../Frameworks`，配错是**启动即 dyld 崩**——而这台机器编不了 iOS，
/// 配错了得等 CI 把包装进模拟器才发现。所以它**只许依赖 Foundation**，编三遍代价为零。
public enum AppGroup {

    /// ⚠️ 改这个字符串等于换了个容器：暂存的分享和小组件状态**当场消失**。
    public static let id = "group.app.yxi"

    /// ⚠️⚠️ **随时可能是 nil，而且在当前 CI 里一定是 nil。**
    /// `.github/workflows/ios.yml` 的 xcodebuild 命令行上写着 `CODE_SIGN_ENTITLEMENTS=`
    /// （为了让 ad-hoc 签名 `CODE_SIGN_IDENTITY=-` 过关，把 entitlements 整个清空）——
    /// 没有 entitlement 就没有容器。**所以这条路在 CI 里验不了**，
    /// 每个调用点都必须能在 nil 时说人话地失败：不许崩，更不许假装成功。
    public static var container: URL? {
        #if canImport(Darwin)
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id)
        #else
        return nil   // Linux 上没这个概念；测试一律自己注入目录
        #endif
    }

    /// 附件落盘用的文件名。
    ///
    /// ⚠️ 这个名字来自**别的 App 分享进来的东西，不可信**：`../` 能把文件写到容器外，
    /// 中间的 `/` 会被当成不存在的子目录 —— 拷贝失败，而失败的表现是「分享静悄悄没了」。
    public static func safeName(_ raw: String, fallback: String = "file") -> String {
        var s = raw.split(separator: "/").last.map(String.init) ?? ""
        s = s.replacingOccurrences(of: "\0", with: "")
        while s.hasPrefix(".") { s.removeFirst() }   // ".." 和隐藏文件
        if s.isEmpty { s = fallback }
        return String(s.prefix(80))
    }
}

/// 分享扩展的**暂存箱**。
///
/// ⚠️⚠️ **扩展不自己发送，只暂存** —— 跟安卓的 `ShareActivity` 最大的差别，别当成偷懒：
///   · 发送要连 SSH，密钥和密码在钥匙串里，扩展要读得靠 `keychain-access-groups`，
///     那需要真开发者账号签名（ad-hoc 签不出来），CI 里同样验不了；
///   · 扩展进程的内存被系统掐得很死，跑一次 SSH 握手随时可能被直接杀掉，
///     而被杀掉的表现是「我明明分享了，怎么没到」。
/// 所以扩展只干一件小事：**东西落进共享容器**，主 App 下次打开时问「送进哪个会话」。
/// **代价要写在界面上**：分享完不会立刻到服务器，得开一次 Yxi。
public struct Outbox {

    public let root: URL

    public init(root: URL) { self.root = root }

    /// App Group 不可用时是 nil。调用方**必须**处理这个 nil（见 [AppGroup.container]）。
    public static var shared: Outbox? {
        AppGroup.container.map { Outbox(root: $0.appendingPathComponent("outbox")) }
    }

    public struct Item: Identifiable, Equatable, Sendable {
        public let id: String
        public let at: Date
        /// 用户在分享面板里补的那句话（可能是空的）
        public let note: String
        public let files: [URL]
    }

    private struct Manifest: Codable { var at: Date; var note: String; var files: [String] }

    private static let manifestName = "item.json"

    /// 没提交完的半成品留多久。够主 App 醒过来收一次，又不至于永远占着用户的空间。
    private static let orphanTTL: TimeInterval = 24 * 3600

    /// 领一个空目录，把附件拷进去，最后 [commit]。
    public func begin() throws -> URL {
        let dir = root.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// ⚠️ **`item.json` 最后写，它在这条才算数。**
    /// 否则「附件拷到一半扩展被系统杀掉」会变成一条内容残缺、却照样被发给 Claude 的分享。
    public func commit(_ dir: URL, note: String, at: Date = Date()) throws {
        let files = ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? [])
            .filter { $0 != Self.manifestName }.sorted()
        let data = try JSONEncoder().encode(Manifest(at: at, note: note, files: files))
        try data.write(to: dir.appendingPathComponent(Self.manifestName), options: .atomic)
    }

    /// 待发的，旧的在前。**只认写完了 `item.json` 的**。
    public func pending(now: Date = Date()) -> [Item] {
        let fm = FileManager.default
        let dirs = (try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        var out: [Item] = []
        for dir in dirs {
            guard let data = try? Data(contentsOf: dir.appendingPathComponent(Self.manifestName)),
                  let m = try? JSONDecoder().decode(Manifest.self, from: data) else {
                let mtime = (try? fm.attributesOfItem(atPath: dir.path))?[.modificationDate] as? Date
                if let mtime, now.timeIntervalSince(mtime) > Self.orphanTTL { try? fm.removeItem(at: dir) }
                continue
            }
            out.append(Item(id: dir.lastPathComponent, at: m.at, note: m.note,
                            files: m.files.map { dir.appendingPathComponent($0) }))
        }
        return out.sorted { $0.at < $1.at }
    }

    /// 送出去了（或者用户放弃了）就整目录删掉。
    public func drop(_ item: Item) {
        try? FileManager.default.removeItem(at: root.appendingPathComponent(item.id))
    }
}

/// 小组件看到的那点状态：几个会话在等你、几个在跑。
///
/// ⚠️⚠️ **小组件自己连不上服务器。** iOS 不给扩展开长连接，WidgetKit 一天的刷新预算
/// 也就几十次，而 SSH 握手要几秒还可能失败 —— 真去连的结果是预算烧光 + 一片空白。
/// 所以数据是**主 App 在前台时顺手写下的**，小组件只读它（跟安卓靠前台服务写 prefs
/// 是同一个套路，只是 iOS 这边没有前台服务，所以更容易旧）。
///
/// ⚠️ 于是它天生会过期。**过期就整块不显示**（[current] 返回 nil），
/// 绝不显示 0 或「未知」——「0 个等你」和「我不知道」在桌面上长得一模一样，
/// 但前者会让人放心地不去看，而那正是这个小组件要防的事。
public struct WaitingSnapshot: Codable, Equatable, Sendable {

    public var waiting: Int
    public var working: Int
    /// 等你的那几个会话的短名，小组件第二行显示
    public var names: [String]
    public var at: Date

    public init(waiting: Int, working: Int, names: [String], at: Date) {
        self.waiting = waiting; self.working = working; self.names = names; self.at = at
    }

    /// 多久之后就不认了。主 App 一进前台就重写一次，所以只要最近开过 App 就是新的。
    /// 半小时是拍的：再久，「等你」很可能已经在电脑那头被处理掉了。
    public static let maxAge: TimeInterval = 30 * 60

    static func file(in container: URL) -> URL { container.appendingPathComponent("waiting.json") }

    /// 主 App 调：把当前状态留给小组件。
    ///
    /// ⚠️ 写完还得叫一声 `WidgetCenter.shared.reloadAllTimelines()` —— **那句不能写在这里**，
    /// YxiKit 不许 import WidgetKit（它得在 Linux 上编得过，那是它唯一被验证过的方式）。
    @discardableResult
    public static func publish(
        waiting: Int, working: Int, names: [String],
        at: Date = Date(), to container: URL? = AppGroup.container
    ) -> Bool {
        guard let container else { return false }
        let snap = WaitingSnapshot(waiting: waiting, working: working,
                                   names: Array(names.prefix(4)), at: at)
        guard let data = try? JSONEncoder().encode(snap) else { return false }
        return (try? data.write(to: file(in: container), options: .atomic)) != nil
    }

    /// 小组件调。读不到 / 太旧 / 根本没有容器 → nil，**界面整块别显示**。
    public static func current(in container: URL? = AppGroup.container, now: Date = Date()) -> WaitingSnapshot? {
        guard let container,
              let data = try? Data(contentsOf: file(in: container)),
              let snap = try? JSONDecoder().decode(WaitingSnapshot.self, from: data),
              now.timeIntervalSince(snap.at) < maxAge
        else { return nil }
        return snap
    }

    /// 第一行。⚠️ 「等你」压过「在跑」—— 等你的那个是唯一需要你动手的。
    public var title: String {
        if waiting > 0 { return "\(waiting) 个会话等你" }
        if working > 0 { return "\(working) 个会话在跑" }
        return "Claude"
    }

    /// 第二行。措辞跟安卓的 `WaitingWidget` 对齐，两边桌面上说的是同一句话。
    public var subtitle: String {
        if waiting > 0, !names.isEmpty { return names.joined(separator: "、") }
        if working > 0 { return "Claude 正在干活" }
        return "盯着呢"
    }
}
