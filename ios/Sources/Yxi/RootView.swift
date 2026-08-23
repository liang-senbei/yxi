import Combine
import SwiftUI
import YxiKit

// ⚠️ 本文件在这台 Linux 上**一行都没编过**。

/// App 的根。
///
/// 结构照安卓的 `MainActivity`（决策 D22）：
///   · 底部三栏 **会话 / 主机 / 设置** —— **只在外层出现**
///   · **进工作区整屏让位，没有底部栏** —— 终端最缺竖向空间，而软键盘弹起时
///     底部栏会和键盘工具条、系统手势条挤成四层
///   · 模式切换 `[终端│对话│文件]` **留在顶部** —— 它是「看哪一面」，
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
                onOpenFiles: { app.open(.files, session: "", cwd: "") }
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
                // ponytail: 文件模式还没人写（SFTP 那半 `YxiKit.SFTP` 已经就绪且有测试）。
                // 先说实话而不是给个点了没反应的按钮 —— 补的时候换掉这一块即可。
                EmptyNote(text: "文件模式还没做。SFTP 那层（YxiKit.SFTP）已经好了，缺的是界面。")
            }
        }
        .background(Yx.surface)
        // ⚠️ 整屏，**没有底部栏**（D22）
        .toolbar(.hidden, for: .tabBar)
        .task(id: taskKey) { await openTerminalIfNeeded() }
    }

    private var taskKey: String { "\(app.live?.link.id.uuidString ?? "-")|\(target.session)|\(target.mode)" }

    private var header: some View {
        HStack(spacing: 12) {
            Button { app.workspace = nil } label: {
                Image(systemName: "chevron.left").font(.system(size: 17, weight: .semibold))
            }
            Text(target.session.hasPrefix("cc-") ? String(target.session.dropFirst(3)) : target.session)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Yx.onSurface)
                .lineLimit(1)
            Spacer()
            Picker("", selection: Binding(get: { target.mode }, set: { app.switchMode($0) })) {
                Text("终端").tag(AppState.Mode.terminal)
                Text("对话").tag(AppState.Mode.chat)
                Text("文件").tag(AppState.Mode.files)
            }
            .pickerStyle(.segmented)
            .frame(width: 190)
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

    enum Tab { case sessions, hosts, settings }
    enum Mode: Hashable { case terminal, chat, files }
    struct Target: Equatable {
        var mode: Mode
        var session: String
        var cwd: String
    }

    let store = HostStore()
    let keys = KeyManager()
    let trust = TrustGate()

    @Published var tab: Tab = .sessions
    @Published var hosts: [Host] = []
    @Published var currentID: String?
    @Published var workspace: Target?
    /// 当前主机那条连接。⚠️ 变了要触发重绘，所以要转发它的 objectWillChange。
    @Published private(set) var live: HostLink?

    /// ⚠️⚠️ **按 host.id 缓存，活在标签页切换之上**（安卓 #75）。
    /// 建在页面里的话切一次 tab 就重连一次，实测约 3 秒。
    private var links: [String: HostLink] = [:]
    private var forward: AnyCancellable?

    lazy var installer: PasswordInstaller = .init(keys: keys, store: store, gate: trust)

    init() {
        hosts = store.hosts
        currentID = hosts.first?.id
        bind()
    }

    var current: Host? { hosts.first { $0.id == currentID } }

    func session(of link: HostLink) -> SSHSession? {
        (link.link.service as? RemoteHost)?.ssh
    }

    func pick(_ host: Host) {
        currentID = host.id
        tab = .sessions
        bind()
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
    }

    private func bind() {
        guard let host = current else { live = nil; forward = nil; return }
        let link = links[host.id] ?? HostLink(host: host, keys: keys, store: store, gate: trust)
        links[host.id] = link
        live = link
        // HostLink 是自己的 ObservableObject —— 不转发的话它 publish 新的 Link，
        // 这一层不会重绘，界面就停在「正在连接」不动。
        forward = link.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
    }
}
