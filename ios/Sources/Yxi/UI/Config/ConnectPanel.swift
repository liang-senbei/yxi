import SwiftUI
import UIKit
import YxiKit

/// 「连接」面板：把 GitHub / Notion / Linear … 接给那台机器上的 agent。逻辑全在 `YxiKit.Connect`（有测试）。
///
/// 每一行：名字 + 接上能干什么 + 状态 + 一个按钮。点「连接」弹一个步骤框：
/// ① 显示码 / 打开授权页 ② 等 ③ 说结果。
/// ⚠️ 框关掉**不杀服务器那头的流程** —— 用户多半是切去浏览器了；回来刷新一下就是最新状态。
struct ConnectPanel: View {
    let runner: ShellRunner?
    @State private var status: Connect.Status?
    @State private var tick = 0
    @State private var flow: ConnectFlow?
    @State private var custom = false
    @State private var drop: Connect.Service?

    var body: some View {
        Group {
            if runner == nil {
                EmptyNote(text: "还没连上")
            } else if let st = status {
                list(st)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: "\(runner == nil)/\(tick)") {
            guard let runner else { return }
            status = Connect.parseStatus((try? await runner.run(Connect.statusCommand)) ?? "")
        }
        .sheet(item: $flow) { f in
            FlowSheet(flow: f) { flow = nil; tick += 1 }
        }
        .sheet(isPresented: $custom) {
            CustomMcpSheet { name, url in
                custom = false
                guard let runner else { return }
                let s = Connect.Service(name, name, url, url: url, transport: url.hasSuffix("/sse") ? "sse" : "http")
                flow = ConnectFlow(runner: runner, service: s)
            }
        }
        .alert(item: $drop) { s in
            Alert(
                title: Text("断开 \(s.name)？"),
                message: Text(s.kind == .gh ? "会退出 gh 的登录，git 推拉和 GitHub MCP 一起失效。" : "会从 Claude Code 的配置里删掉这个 MCP。"),
                primaryButton: .destructive(Text("断开")) {
                    Task {
                        _ = try? await runner?.run(s.kind == .gh ? Connect.ghLogout : Connect.mcpRemove(s.key))
                        tick += 1
                    }
                },
                secondaryButton: .cancel(Text("算了"))
            )
        }
    }

    private func list(_ st: Connect.Status) -> some View {
        List {
            Section {
                Text("认证一次，这台机器上的 Claude Code 就能直接调用它。全程在手机上完成。")
                    .font(.system(size: 13)).foregroundStyle(Yx.dim)
                if !st.claudeInstalled {
                    Text("这台机器上没有 claude 命令，MCP 那几项接不了；GitHub 仍然可以。")
                        .font(.system(size: 13)).foregroundStyle(Yx.amber)
                }
            }
            Section {
                ForEach(Connect.catalog) { s in
                    row(s, st.of(s), enabled: s.kind == .gh ? st.ghInstalled : (s.kind == .mcp && st.claudeInstalled))
                }
                Button { custom = true } label: {
                    Text("+ 自定义 MCP 地址…").foregroundStyle(Yx.copper)
                }
                .disabled(!st.claudeInstalled)
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .refreshable { tick += 1 }
    }

    private func row(_ s: Connect.Service, _ state: Connect.Health, enabled: Bool) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text(s.name).font(.system(size: 15, weight: .medium))
                        .foregroundStyle(s.kind == .info ? Yx.dim : Yx.onSurface)
                    switch state {
                    case .connected: chip("已连接", Yx.teal)
                    case .needsAuth: chip("要登录", Yx.amber)
                    case .failed: chip("连不上", Yx.error)
                    case .absent: EmptyView()
                    }
                }
                Text(s.what).font(.system(size: 12.5)).foregroundStyle(Yx.dim)
            }
            Spacer(minLength: 0)
            if s.kind != .info {
                let label = state == .connected ? "断开" : s.noAuth ? "加上" : state == .needsAuth ? "登录" : "连接"
                Button(label) {
                    if state == .connected { drop = s }
                    else if let runner { flow = ConnectFlow(runner: runner, service: s) }
                }
                .buttonStyle(.borderedProminent)
                .tint(state == .connected ? Yx.high : Yx.copper)
                .foregroundStyle(state == .connected ? Yx.onSurfaceVar : Yx.onCopper)
                .disabled(!enabled)
            }
        }
    }

    private func chip(_ text: String, _ color: Color) -> some View {
        Text(text).font(.system(size: 11)).foregroundStyle(color)
            .padding(.horizontal, 8).padding(.vertical, 2)
            .background(color.opacity(0.14), in: Capsule())
    }
}

/// 一次连接流程的状态机。GitHub：起 `gh auth login` → 等一次性码 → 用户去 github.com/login/device →
/// 等 `__DONE__`。MCP：`claude mcp add` → `claude mcp login --no-browser` → 等授权 URL →
/// 用户授权、把 localhost 地址粘回来 → 等 `__DONE__`。免认证的加完就算完。
@MainActor
final class ConnectFlow: ObservableObject, Identifiable {
    enum Step: Equatable {
        case working(String)
        case code(String, String)
        case authorize(String)
        case done(Bool, String)
    }
    let service: Connect.Service
    var id: String { service.key }
    @Published private(set) var step: Step = .working("准备中…")
    @Published var opened = false
    private let runner: ShellRunner
    private var task: Task<Void, Never>?
    private var tmux: String { Connect.tmux(for: service.key) }

    init(runner: ShellRunner, service: Connect.Service) {
        self.runner = runner
        self.service = service
        task = Task { await run() }
    }

    private func sh(_ cmd: String) async -> String { (try? await runner.run(cmd)) ?? "" }

    private func run() async {
        switch service.kind {
        case .gh: await gh()
        case .mcp: await mcp()
        case .info: step = .done(false, "")
        }
    }

    private func gh() async {
        step = .working("在服务器上起 gh 登录…")
        _ = await sh(Connect.ghLoginStart())
        var code: String?
        var enterSent = false
        for _ in 0..<60 {
            try? await Task.sleep(nanoseconds: 500_000_000)
            if Task.isCancelled { return }
            let pane = await sh(Connect.peekCommand(tmux))
            if Connect.ghAsksGit(pane) { _ = await sh(Connect.enterCommand(tmux)); continue }
            if let c = Connect.ghCode(pane) {
                code = c
                if Connect.ghAsksOpen(pane), !enterSent { _ = await sh(Connect.enterCommand(tmux)); enterSent = true }
                break
            }
            if Connect.parseDone(pane) == false { step = .done(false, Connect.failReason(pane)); return }
        }
        guard let code else { step = .done(false, "gh 没给出一次性码（这台机器装了 gh 吗？）"); return }
        step = .code(code, Connect.ghDeviceURL)
        await waitDone { _ = await self.sh(Connect.ghAfterCommand); return "GitHub 接上了：git 推拉和 GitHub MCP 都能用了" }
    }

    private func mcp() async {
        step = .working("加进 Claude Code…")
        let out = await sh(Connect.mcpAdd(service))
        if out.lowercased().contains("error"), !out.lowercased().contains("already") {
            step = .done(false, String(out.trimmingCharacters(in: .whitespacesAndNewlines).prefix(160))); return
        }
        if service.noAuth { step = .done(true, "加上了，不用登录，直接就能用"); return }
        step = .working("等授权地址…")
        _ = await sh(Connect.mcpLoginStart(service.key))
        var url: String?
        for _ in 0..<40 {
            try? await Task.sleep(nanoseconds: 500_000_000)
            if Task.isCancelled { return }
            let pane = await sh(Connect.peekCommand(tmux))
            if let u = Connect.loginURL(pane) { url = u; break }
            if Connect.parseDone(pane) == false { step = .done(false, Connect.failReason(pane)); return }
        }
        guard let url else { step = .done(false, "没拿到授权地址"); return }
        step = .authorize(url)
        await waitDone { "\(self.service.name) 接上了" }
    }

    /// 等服务器那头出结果，最多 10 分钟
    private func waitDone(_ after: @escaping () async -> String) async {
        for _ in 0..<400 {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            if Task.isCancelled { return }
            let pane = await sh(Connect.peekCommand(tmux))
            switch Connect.parseDone(pane) {
            case true?: let m = await after(); await finish(); step = .done(true, m); return
            case false?: await finish(); step = .done(false, Connect.failReason(pane)); return
            default: break
            }
        }
        await finish()
        step = .done(false, "等了 10 分钟没结果，服务器那头的流程已经停了")
    }

    func paste(_ redirectURL: String) {
        Task { _ = await sh(Connect.pasteCommand(key: service.key, redirectURL: redirectURL)) }
    }

    private func finish() async { _ = await sh(Connect.killCommand(tmux)) }

    /// 关框：停轮询，**不杀服务器那头**
    func cancelPolling() { task?.cancel() }
}

private struct FlowSheet: View {
    @ObservedObject var flow: ConnectFlow
    let onClose: () -> Void
    @Environment(\.openURL) private var openURL
    @State private var pasted = ""

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                switch flow.step {
                case let .working(what):
                    HStack(spacing: 10) { ProgressView(); Text(what) }
                case let .code(code, url):
                    Text("GitHub 页面会要一个一次性码。点下面按钮：码已复制、页面已打开，粘进去按确认就行。")
                    Text(code).font(.mono(28, .bold)).foregroundStyle(Yx.copper)
                    Text(flow.opened ? "等 GitHub 那边确认…（确认完这里会自己变）" : "码 15 分钟内有效")
                        .font(.system(size: 13)).foregroundStyle(Yx.dim)
                    Button("复制码并打开 GitHub") {
                        UIPasteboard.general.string = code
                        flow.opened = true
                        if let u = URL(string: url) { openURL(u) }
                    }
                    .buttonStyle(.borderedProminent).tint(Yx.copper)
                case let .authorize(url):
                    Text("在浏览器里登录并同意。同意之后浏览器会停在一个打不开的 localhost 页面 —— 把地址栏那串地址复制过来粘到下面。")
                    Button("去浏览器授权") {
                        flow.opened = true
                        if let u = URL(string: url) { openURL(u) }
                    }
                    .buttonStyle(.borderedProminent).tint(Yx.copper)
                    TextField("把 localhost 开头的地址粘这儿", text: $pasted)
                        .textFieldStyle(.roundedBorder).autocorrectionDisabled().textInputAutocapitalization(.never)
                    if pasted.hasPrefix("http") {
                        Button("交上去") { flow.paste(pasted.trimmingCharacters(in: .whitespaces)); pasted = "" }
                    }
                    if flow.opened { Text("等浏览器那边授权…").font(.system(size: 13)).foregroundStyle(Yx.dim) }
                case let .done(ok, message):
                    Text(message).foregroundStyle(ok ? Yx.teal : Yx.error)
                }
                Spacer()
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Yx.surface)
            .navigationTitle("连接 \(flow.service.name)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(flow.step.isDone ? "好" : "先关掉") { flow.cancelPolling(); onClose() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

private extension ConnectFlow.Step {
    var isDone: Bool { if case .done = self { return true } else { return false } }
}

private struct CustomMcpSheet: View {
    let onAdd: (String, String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var url = "https://"
    private var ok: Bool {
        name.range(of: "^[A-Za-z0-9_-]{1,40}$", options: .regularExpression) != nil && url.hasPrefix("https://") && url.count > 10
    }
    var body: some View {
        NavigationStack {
            Form {
                TextField("名字（字母数字）", text: $name).autocorrectionDisabled().textInputAutocapitalization(.never)
                TextField("地址（https://…/mcp 或 /sse）", text: $url).autocorrectionDisabled().textInputAutocapitalization(.never)
                Text("加进去之后会走一遍登录；不需要登录的服务登录那步会自己过。").font(.system(size: 13)).foregroundStyle(Yx.dim)
            }
            .navigationTitle("自定义 MCP")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("算了") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("连接") { onAdd(name, url) }.disabled(!ok) }
            }
        }
        .presentationDetents([.medium])
    }
}
