import Combine
import SwiftUI
import YxiKit

// ⚠️ 本文件在这台 Linux 上**一行都没编过**。

/// App 的根。
///
/// 结构照安卓的 `MainActivity`（决策 D22）：
///   · 底部四栏 **会话 / 主机 / 配置 / 设置** —— **只在外层出现**（跟安卓版一致）
///   · **进工作区整屏让位，没有底部栏** —— 终端最缺竖向空间，而软键盘弹起时
///     底部栏会和键盘工具条、系统手势条挤成四层
///   · 模式切换 `[终端│对话│文件│实验室]` **留在顶部** —— 它是「看哪一面」，
///     跟底部栏的「在 app 的哪儿」是两条轴，放一起会打架
public struct RootView: View {

    @StateObject private var app = AppState()

    public init() {}

    public var body: some View {
        ZStack {
            Yx.surface.ignoresSafeArea()
            if let target = app.workspace {
                Workspace(app: app, target: target)
                    .transition(.move(edge: .trailing))
            } else {
                tabs
            }
        }
        .preferredColorScheme(.dark)
        .tint(Yx.copper)
        // 首连确认。⚠️ 指纹**变了**根本走不到这里 —— KnownHosts 直接拒，连问都不问。
        // 在这儿加一个「仍然连接」就等于把安卓 #22 那个洞原样搬过来。
        .alert(item: Binding(
            get: { app.trust.pending },
            set: { if $0 == nil { app.trust.resolve(false) } }   // 划走 = 不信任
        )) { ask in
            Alert(
                title: Text("第一次连 \(ask.target)"),
                message: Text("主机指纹\n\(ask.fingerprint)\n\n请和服务器上 `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` 的输出核对。"),
                primaryButton: .default(Text("信任并连接")) { app.trust.resolve(true) },
                secondaryButton: .cancel(Text("取消")) { app.trust.resolve(false) }
            )
        }
    }

    private var tabs: some View {
        TabView(selection: $app.tab) {
            sessions
                .tabItem { Label("会话", systemImage: "square.stack.3d.up") }
                .tag(AppState.Tab.sessions)

            HostsScreen(
                hosts: app.hosts,
                keys: app.keys,
                installer: app.installer,
                onOpen: { app.pick($0) },
                onSave: { app.save($0) },
                onDelete: { app.delete($0) },
                onSetWatch: { app.setWatch($0, on: $1) }
            )
            .tabItem { Label("主机", systemImage: "server.rack") }
            .tag(AppState.Tab.hosts)

            NavigationStack {
                ConfigScreen(app: app)
            }
            .tabItem { Label("配置", systemImage: "slider.horizontal.3") }
            .tag(AppState.Tab.config)

            SettingsScreen(app: app)
                .tabItem { Label("设置", systemImage: "gearshape") }
                .tag(AppState.Tab.settings)
        }
    }

    @ViewBuilder
    private var sessions: some View {
        if let host = app.current, let live = app.live {
            SessionsScreen(
                host: host,
                hosts: app.hosts,
                link: live.link,
                usage: live.link.service as? any UsageService,
                onPickHost: { app.pick($0) },
                onOpenChat: { app.open(.chat, session: $0, cwd: $1) },
                onOpenTerminal: { app.open(.terminal, session: $0 ?? "", cwd: $1) },
                onOpenFiles: { app.open(.files, session: "", cwd: "") },
                onSessions: { app.sessions = $0 }
            )
        } else {
            EmptyNote(text: "还没有主机。去「主机」页加一台 —— 任意 IP、任意端口、密码或密钥都行。")
        }
    }
}

// MARK: - 工作区

/// 一台主机 + 一个会话的三个面。
///
/// ⚠️ **切换不断连**：连接、终端仿真器都在 [HostLink] 上（按 host.id 缓存，
/// 活在标签页切换之上），这里只是换画面。
private struct Workspace: View {

    @ObservedObject var app: AppState
    let target: AppState.Target
    @State private var shell: SSHSession.Shell?

    var body: some View {
        VStack(spacing: 0) {
            header
            switch target.mode {
            case .terminal:
                if let live = app.live {
                    TerminalPane(session: live.terminal, connected: shell?.isOpen ?? false)
                } else {
                    EmptyNote(text: "没连上")
                }
            case .chat:
                if let backend = app.live?.link.service as? ChatBackend {
                    ChatScreen(backend: backend, session: target.session, cwd: target.cwd)
                } else {
                    EmptyNote(text: "没连上，对话模式用不了。下拉「会话」页可以重连。")
                }
            case .files:
                if let fs = app.live?.link.service as? FileService {
                    // 起点 = 这个会话的 cwd。空的话交给 FilesScreen 退到家目录
                    FilesScreen(files: fs, startDir: target.cwd.isEmpty ? "." : target.cwd)
                } else {
                    EmptyNote(text: "没连上，文件模式用不了。下拉「会话」页可以重连。")
                }
            case .lab:
                LabScreen(app: app)
            }
        }
        .background(Yx.surface)
        // ⚠️ 整屏，**没有底部栏**（D22）
        .toolbar(.hidden, for: .tabBar)
        .task(id: taskKey) { await openTerminalIfNeeded() }
    }

    private var taskKey: String { "\(app.live?.link.id.uuidString ?? "-")|\(target.session)|\(target.mode)" }

    /// 同一台机器上别的会话。**「等你」的排前面** —— 挑的时候找的就是它们。
    private var others: [BoardSession] {
        app.sessions
            .filter { $0.name != target.session }
            .sorted { a, b in
                if (a.state == .needsYou) != (b.state == .needsYou) { return a.state == .needsYou }
                return a.lastActivity > b.lastActivity
            }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button { app.workspace = nil } label: {
                Image(systemName: "chevron.left").font(.system(size: 17, weight: .semibold))
            }
            // ⚠️ **换会话是这个 app 里最高频的动作**（安卓侧的原话）。
            // 原来标题是死的 `Text`，换个会话得「退出工作区 → 看板 → 滚 → 再进」四步。
            // 现在标题本身就是入口：点开列表直接跳，连接不用重开（按 host 缓存）。
            Menu {
                ForEach(others, id: \.name) { s in
                    Button {
                        app.open(target.mode, session: s.name, cwd: s.cwd)
                    } label: {
                        // 带上「在等你 / 干活中」，好在二十个里挑一个
                        Text(s.state == .needsYou ? "\(s.short) · 等你" : s.short)
                    }
                }
                if others.isEmpty {
                    Text("这台机器上没有别的会话")
                }
            } label: {
                HStack(spacing: 4) {
                    Text(target.session.hasPrefix("cc-") ? String(target.session.dropFirst(3)) : target.session)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Yx.onSurface)
                        .lineLimit(1)
                    Text("▾").font(.system(size: 11)).foregroundStyle(Yx.muted)
                }
            }
            Spacer()
            Picker("", selection: Binding(get: { target.mode }, set: { app.switchMode($0) })) {
                Text("终端").tag(AppState.Mode.terminal)
                Text("对话").tag(AppState.Mode.chat)
                Text("文件").tag(AppState.Mode.files)
                Text("实验室").tag(AppState.Mode.lab)
            }
            .pickerStyle(.segmented)
            .frame(width: 240)
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(Yx.low)
    }

    /// ⚠️⚠️ **开 PTY 的初始尺寸必须用控件量出来的，别写死 80x24**（安卓 #77）。
    /// 写死的话 tmux 按 80 列排版、控件其实只有 40 多列，
    /// **整个画面是花的**（折行错位、边框断开），看着像连不上。
    private func openTerminalIfNeeded() async {
        guard target.mode == .terminal, let live = app.live, live.link.isConnected else { return }
        guard shell == nil || shell?.isOpen != true else { return }
        let (c, r) = live.terminal.preferredSize
        guard let ssh = app.session(of: live) else { return }
        // ⚠️ 命令走 `SSHSession.attach` —— 它带着 `set -g mouse on`。
        // 漏了那个开关的现象是「终端里怎么划都不动」，且完全看不出跟 tmux 配置有关。
        shell = try? await ssh.openShell(command: SSHSession.attach(session: target.session), cols: c, rows: r)
        if let shell { live.terminal.attach(shell) }
    }
}

struct EmptyNote: View {
    let text: String
    var body: some View {
        VStack {
            Spacer()
            Text(text).font(.system(size: 15)).foregroundStyle(Yx.muted)
                .multilineTextAlignment(.center).padding(32)
            Spacer()
        }
    }
}

// MARK: - 全局状态

@MainActor
final class AppState: ObservableObject {

    enum Tab { case sessions, hosts, config, settings }
    enum Mode: Hashable { case terminal, chat, files, lab }
    struct Target: Equatable {
        var mode: Mode
        var session: String
        var cwd: String
    }

    let store = HostStore()
    let keys = KeyManager()
    let trust = TrustGate()
    /// 前台盯梢（主机页那个铃铛后面接的就是它）。见 [Watcher] 头上关于 iOS 后台的说明。
    let watcher = Watcher()

    /// ⚠️ **初始标签页可以用启动参数指定** —— 给自动化截图用：
    /// `xcrun simctl launch <udid> app.yxi --args -yxiTab config`
    /// iOS 会把 `-yxiTab config` 自动收进 `UserDefaults`（NSArgumentDomain），
    /// 所以这里不用自己解析 argv。平时没传就是「会话」，行为不变。
    @Published var tab: Tab = {
        switch UserDefaults.standard.string(forKey: "yxiTab") {
        case "hosts": return .hosts
        case "config": return .config
        case "settings": return .settings
        default: return .sessions
        }
    }()
    @Published var hosts: [Host] = []
    @Published var currentID: String?
    @Published var workspace: Target?
    /// 当前主机那条连接。⚠️ 变了要触发重绘，所以要转发它的 objectWillChange。
    @Published private(set) var live: HostLink?
    /// 最近一次抓到的会话表。**只是给工作区的标题下拉用的缓存** ——
    /// 权威来源仍然是会话页那条轮询，这里不自己去抓（免得多一条往返）。
    @Published var sessions: [BoardSession] = []

    /// ⚠️⚠️ **按 host.id 缓存，活在标签页切换之上**（安卓 #75）。
    /// 建在页面里的话切一次 tab 就重连一次，实测约 3 秒。
    private var links: [String: HostLink] = [:]
    private var forward: AnyCancellable?

    lazy var installer: PasswordInstaller = .init(keys: keys, store: store, gate: trust)

    init() {
        #if DEBUG
        seedFromLaunchArgsIfAsked()
        #endif
        hosts = store.hosts
        currentID = hosts.first?.id
        bind()
        openFromLaunchArgsIfAsked()
    }

    /// 跟 [tab] 同一个路子：让自动化能**直接落到工作区的某个模式**
    /// （`--args -yxiOpen cc-demo -yxiMode files -yxiCwd /tmp/x`），
    /// 否则截图脚本得先模拟点击列表里的某个会话 —— simctl 点不了。
    private func openFromLaunchArgsIfAsked() {
        let d = UserDefaults.standard
        guard let session = d.string(forKey: "yxiOpen") else { return }
        let mode: Mode
        switch d.string(forKey: "yxiMode") {
        case "files": mode = .files
        case "chat": mode = .chat
        case "lab": mode = .lab
        default: mode = .terminal
        }
        workspace = Target(mode: mode, session: session, cwd: d.string(forKey: "yxiCwd") ?? ".")
    }

    #if DEBUG
    /// **CI 用的一次性播种**：把一台主机塞进 store，让模拟器能真连上云端 runner 上
    /// 现起的那台 sshd —— 从而在 CI 里跑**真的** SSH/SFTP，而不是喂假数据截图。
    ///
    /// ```
    /// xcrun simctl launch <udid> app.yxi --args -yxiDumpPubKey YES   # ① 吐公钥
    /// #  Mac 上：simctl get_app_container … data → Documents/pubkey.txt → authorized_keys
    /// xcrun simctl launch <udid> app.yxi --args \
    ///     -yxiSeedHost 127.0.0.1:22 -yxiSeedUser runner -yxiSeedHostKey "ssh-ed25519 AAAA…"
    /// ```
    ///
    /// ⚠️ **走密钥、不走密码**：密码那条路要先改 runner 账号的登录密码
    /// （`dscl -passwd` 要旧密码，CI 上没有），而且密钥本来就是用户真正在用的认证方式 ——
    /// 测的是真路径，不是为测试另开的后门。
    ///
    /// ⚠️ **`#if DEBUG` 不是装饰**：这是一个「用启动参数往主机列表里写一台机器」的口子，
    /// 发布版里必须根本不存在。CI 编的就是 Debug。
    private func seedFromLaunchArgsIfAsked() {
        let d = UserDefaults.standard
        // ① 把本机公钥吐到 Documents，CI 拿去写进 runner 的 authorized_keys。
        //    容器目录在 Mac 上直接可读（`simctl get_app_container`）。
        if d.bool(forKey: "yxiDumpPubKey") {
            // ⚠️ 失败也要**写点东西出去**：CI 那头只能看到「文件在不在」，
            // 空手而归的话根本不知道是钥匙串挂了还是别的。把原因写进同一个文件。
            var out: String
            do { out = try keys.identity().authorizedKeysLine }
            catch { out = "ERROR: \(error)" }
            if let dir = try? FileManager.default.url(
                for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true) {
                try? out.write(to: dir.appendingPathComponent("pubkey.txt"),
                               atomically: true, encoding: .utf8)
            }
        }
        // ② 播种一台主机
        guard let hostPort = d.string(forKey: "yxiSeedHost"),
              let user = d.string(forKey: "yxiSeedUser") else { return }
        let parts = hostPort.split(separator: ":")
        let name = String(parts.first ?? "127.0.0.1")
        let port = parts.count > 1 ? Int(parts[1]) ?? 22 : 22
        // 固定 id：重复启动只更新同一台，不会攒出一堆重复主机
        var h = Host(id: "ci-seed", alias: "CI", hostname: name, port: port,
                     username: user, useKey: true)
        // 主机公钥提前塞进去，省掉首次连接的信任弹窗 —— 它挡住的正是要截的界面
        h.hostKey = d.string(forKey: "yxiSeedHostKey")
        store.upsert(h)
    }
    #endif

    var current: Host? { hosts.first { $0.id == currentID } }

    func session(of link: HostLink) -> SSHSession? {
        (link.link.service as? RemoteHost)?.ssh
    }

    func pick(_ host: Host) {
        currentID = host.id
        tab = .sessions
        bind()
    }

    /// ⚠️ 铃铛开着才盯。**关掉要真的停** —— 否则用户关了它还在耗电、还在响。
    func refreshWatch() {
        guard let id = currentID, store.get(id)?.watch == true, let live else {
            watcher.stop()
            return
        }
        watcher.start(live, hostID: id)
    }

    func open(_ mode: Mode, session: String, cwd: String) {
        workspace = Target(mode: mode, session: session, cwd: cwd)
    }

    func switchMode(_ mode: Mode) { workspace?.mode = mode }

    func save(_ host: Host) {
        store.upsert(host)      // 地址/端口变了会自动忘掉旧指纹（#68）
        hosts = store.hosts
        if currentID == nil { currentID = host.id }
        // 连接信息变了 → 那条旧连接作废
        links[host.id] = nil
        bind()
    }

    func delete(_ id: String) {
        if let sealed = store.get(id)?.sealedPassword { Vault.discard(sealed) }
        store.remove(id: id)
        links[id] = nil
        hosts = store.hosts
        if currentID == id { currentID = hosts.first?.id }
        bind()
    }

    func setWatch(_ id: String, on: Bool) {
        guard var h = store.get(id) else { return }
        h.watch = on
        store.upsert(h)
        hosts = store.hosts
        refreshWatch()      // ⚠️ 开关要**立刻**生效：关了还在响 = 关不掉
    }

    private func bind() {
        guard let host = current else { live = nil; forward = nil; return }
        let link = links[host.id] ?? HostLink(host: host, keys: keys, store: store, gate: trust)
        links[host.id] = link
        live = link
        // HostLink 是自己的 ObservableObject —— 不转发的话它 publish 新的 Link，
        // 这一层不会重绘，界面就停在「正在连接」不动。
        forward = link.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        refreshWatch()      // 换主机 = 换盯梢对象
    }
}
