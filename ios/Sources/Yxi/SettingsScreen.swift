import SwiftUI
import UIKit
import YxiKit

// ⚠️ 本文件在这台 Linux 上**一行都没编过**。

/// 设置页（决策 D23）。照安卓 `SettingsScreen.kt`。
struct SettingsScreen: View {

    @ObservedObject var app: AppState
    @State private var update: Update.Result?
    @State private var checking = false
    @State private var identity: KeyIdentity?
    @State private var confirmRegenerate = false
    @State private var diagnosis: String?
    @State private var ticketText = ""
    @State private var tickets: [Tickets.Ticket] = []
    @State private var ticketNote: String?

    var body: some View {
        NavigationStack {
            List {
                ticketSection
                versionSection
                keySection
                backgroundSection
                diagnosticSection
                aboutSection
            }
            .scrollContentBackground(.hidden)
            .background(Yx.surface)
            .navigationTitle("设置")
        }
        .task { identity = try? app.keys.identity() }
        .alert("换一把新密钥？", isPresented: $confirmRegenerate) {
            Button("换", role: .destructive) { identity = try? app.keys.regenerate() }
            Button("算了", role: .cancel) {}
        } message: {
            // ⚠️ 这是不可逆的，而且后果发生在**别的机器上** —— 必须说清楚
            Text("旧公钥会立刻失效。所有装过它的服务器都要重新装一次公钥，在那之前一台都连不上。")
        }
    }

    // MARK: 工单中心

    /// 哪里不好用随手记一条，落在**连着的那台服务器** `~/.yxi/tickets.jsonl`，
    /// 开发那边 `cat` 一下就看得全。
    ///
    /// ⚠️ **为什么存服务器不是本地**：存本地只有本人看得见，等于没提。
    /// ⚠️ 自动带上版本号和机型 —— 不带的话回头对不上是哪版的毛病。
    private var ticketSection: some View {
        Section("工单中心") {
            Text("哪里不好用，随手写一条。存在这台服务器上（~/.yxi/tickets.jsonl），开发直接查阅；会自动带上版本号和机型。")
                .font(.system(size: 12)).foregroundStyle(Yx.dim)
            TextField("比如：点了发送切出去，消息没发出去", text: $ticketText, axis: .vertical)
                .lineLimit(2...5)
                .font(.system(size: 14))
            Button {
                Task { await submitTicket() }
            } label: {
                Text("提一条").font(.system(size: 15, weight: .medium))
                    .frame(maxWidth: .infinity, minHeight: 40)
            }
            .disabled(ticketText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            if let ticketNote {
                Text(ticketNote).font(.system(size: 12)).foregroundStyle(Yx.teal)
            }
            if !tickets.isEmpty {
                Text("已提 \(tickets.count) 条").font(.system(size: 12)).foregroundStyle(Yx.dim)
                ForEach(tickets.prefix(5)) { t in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t.text).font(.system(size: 13)).lineLimit(3)
                        if !t.version.isEmpty {
                            Text(t.version).font(.mono(11)).foregroundStyle(Yx.dim)
                        }
                    }
                }
            }
        }
        .task { await loadTickets() }
    }

    private var runner: ShellRunner? { app.live?.link.service as? ShellRunner }

    private func loadTickets() async {
        guard let runner else { return }
        if let raw = try? await runner.run(Tickets.listCommand) { tickets = Tickets.parse(raw) }
    }

    private func submitTicket() async {
        let body = ticketText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return }
        guard let runner else { ticketNote = "没连上，提不了"; return }
        let t = Tickets.Ticket(
            at: Date().timeIntervalSince1970,
            text: body,
            version: "\(Self.versionName)(\(Self.versionCode))",
            device: "\(UIDevice.current.model) / iOS \(UIDevice.current.systemVersion)"
        )
        if (try? await runner.run(Tickets.addCommand(t))) != nil {
            ticketText = ""
            ticketNote = "记下了"
            await loadTickets()
        } else {
            ticketNote = "没提上去"
        }
    }

    // MARK: 版本与更新

    private var versionSection: some View {
        Section("版本") {
            LabeledContent("当前版本", value: "\(Self.versionName) (\(Self.versionCode))")

            Button {
                Task { await check() }
            } label: {
                HStack {
                    Text("检查更新")
                    Spacer()
                    if checking { ProgressView() }
                }
            }
            .disabled(checking)

            // ⚠️⚠️ **三种结果必须分清**（D23）。把「没查到」显示成「已是最新」是在骗用户 ——
            // 他会以为自己是最新版，而实际上可能落后好几版、正带着已知的 bug 在用。
            if let update {
                switch update {
                case .newer(let u):
                    VStack(alignment: .leading, spacing: 4) {
                        Text("有新版本 \(u.versionName)（\(u.sizeText)）").foregroundStyle(Yx.amber)
                        if !u.notes.isEmpty { Text(u.notes).font(.caption).foregroundStyle(Yx.muted) }
                        // ⚠️ **iOS 装不了自己下的包** —— 安卓那条「App 内下载并安装」整条路不存在。
                        // 所以这里只报信，不给下载按钮。别做一个点了必然失败的东西。
                        Text("iOS 不能由 App 自己安装，去 AltStore / SideStore 更新（见 docs/分发.md）。")
                            .font(.caption).foregroundStyle(Yx.dim)
                    }
                case .upToDate:
                    Text("✓ 已是最新").foregroundStyle(Yx.teal)
                case .failed(let why):
                    Text("✗ 没查到：\(why)").foregroundStyle(Yx.error)
                }
            }
        }
    }

    /// ⚠️ 「连不上」要落到 `.failed`，**不能落到 `.upToDate`**。
    private func check() async {
        checking = true
        defer { checking = false }
        guard let live = app.live, let ssh = app.session(of: live) else {
            update = .failed("没连上 \(app.current?.display ?? "任何主机")")
            return
        }
        do {
            let sftp = try await ssh.openSFTP()
            defer { Task { await sftp.close() } }
            let raw = String(decoding: try await sftp.read(Update.manifestPath, max: 64 * 1024), as: UTF8.self)
            switch Update.parse(manifest: raw, currentCode: Self.versionCode) {
            case .newer(let u):
                // ⚠️ 清单说有、包却不在，就当没有（安卓 #69：发布脚本和下载链接写的是
                // 两个不同的文件名，清单更新了包没换，链接一直在发旧包）
                update = Update.confirm(u, sizeBytes: await sftp.size(u.remotePath))
            case let other:
                update = other
            }
        } catch {
            update = .failed(Explain.sftp(error))
        }
    }

    // MARK: 公钥

    private var keySection: some View {
        Section("这台设备的 SSH 公钥") {
            if let identity {
                Button {
                    UIPasteboard.general.string = identity.authorizedKeysLine
                } label: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(identity.fingerprint)
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Yx.onSurface)
                        Text("点这里复制整行 · 注释 \(identity.comment)")
                            .font(.caption).foregroundStyle(Yx.muted)
                    }
                }
                // ⚠️ 注释里那个后缀由公钥自己派生，**每台设备都不一样**。
                // 安卓上所有设备共用 `yxi@android`，开发脚本按注释过滤 authorized_keys
                // 时把用户真手机的钥匙删了（#65）。服务器上要认哪一行，就看这个词。
                Button("换一把…", role: .destructive) { confirmRegenerate = true }
            } else {
                Text("还没生成").foregroundStyle(Yx.muted)
            }
        }
    }

    // MARK: 后台

    private var backgroundSection: some View {
        Section("通知与后台") {
            // ⚠️ 说实话。iOS 没有前台服务：App 进后台约 30 秒内 socket 就被系统收走，
            // `BGProcessingTask` 由系统决定什么时候跑、也不保证有网。
            // 而 PRD §2.7 / D8 已经否掉了推送那条路（会逼我们长期跑一台中转服务器）。
            // 写「已开启」之类的话是骗人 —— 用户会真的指望它在锁屏时叫醒他。
            Text("iOS 上「主动响」只在 App 开着时有效。\n进后台约 30 秒后连接会被系统收走，重新打开会自动重连。")
                .font(.caption).foregroundStyle(Yx.muted)
            Button("打开系统通知设置") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
        }
    }

    // MARK: 诊断

    private var diagnosticSection: some View {
        Section("诊断") {
            Button("跑一次诊断") { diagnosis = report() }
            if let diagnosis {
                Text(diagnosis)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(Yx.onSurfaceVar)
                Button("复制") { UIPasteboard.general.string = diagnosis }
            }
        }
    }

    /// ⚠️ **报告要报告「被抱怨的那个东西」此刻的状态**（安卓 #79），
    /// 不是它自己另测一遍的结果 —— 另测一遍全绿而用户还是连不上，是最浪费时间的一种。
    ///
    /// ⚠️ 每一行都用**真正连的地址**，不用别名（安卓 #71：错误信息报的是用户起的名字，
    /// 把排查整个带进了端口/防火墙的死胡同，而 App 从来没连过那个 IP）。
    private func report() -> String {
        guard let h = app.current else { return "还没有主机。" }
        let auth: String
        if h.useKey { auth = "公钥 " + (identity?.fingerprint ?? "?") }
        else if h.sealedPassword != nil { auth = "密码" }
        else { auth = "无（既没密码也没公钥）" }

        let shown = app.live?.link.isConnected == true
            ? "已连上"
            : "✗ 此刻显示：" + (app.live?.link.error ?? "正在连接…")

        var lines = [
            "Yxi \(Self.versionName) (\(Self.versionCode)) · iOS \(UIDevice.current.systemVersion)",
            "目标   \(h.display)",
            "认证   \(auth)",
            "指纹   \(h.hostKey == nil ? "还没记住（首次连接会问）" : "已记住")",
            "界面   \(shown)",
        ]
        if let bad = h.suspiciousCharacter {
            // #58：全角句点和半角长得几乎一样，用户盯着看觉得完全正确
            lines.append("⚠️ 地址里有个连不上的字符：\(bad)")
        }
        lines.append("⚠️ 诊断不含密码和私钥，可以直接发出来。")
        return lines.joined(separator: "\n")
    }

    // MARK: 关于

    private var aboutSection: some View {
        Section("关于") {
            Text("Yxi —— 手机上的 Claude Code 指挥台。全部走 SSH，不经过任何第三方服务器。")
                .font(.caption).foregroundStyle(Yx.muted)
        }
    }

    /// ⚠️ 从 `Bundle` 读，**别写死** —— 安卓那边同样的坑：写死之后发了新版，
    /// 设置页还显示旧版本号，而用户是靠这个数判断自己装没装上的。
    static var versionName: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
    }
    static var versionCode: Int {
        Int(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "") ?? 0
    }
}
