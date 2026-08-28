import SwiftUI
import YxiKit

/// 会话看板：**等你 / 干活中 / 已完成 / 空闲** 四段（前三段是主角）。
///
/// 只有「等你」那组带操作按钮，其余安静 —— 琥珀色是全 app 唯一
/// 「需要你动手」的信号，别处不用（PRD 附录 J.1）。
///
/// ⚠️ 这个界面**不需要在服务器上装任何东西**：`tmux list-sessions` 和
/// `~/.cloud-status` 下的状态文件都是现成的（后者由 `cc-state` 写，早就在跑）。
@MainActor
struct SessionsScreen: View {

    let host: Host
    /// 主机下拉：换主机不用退出去（D22）。只有一台时不显示 ▾
    var hosts: [Host] = []
    /// ⚠️ 连接由**外层**持有 —— 切标签页时这个视图会销毁，连接不能跟着断（#75）
    let link: Link
    var usage: (any UsageService)? = nil

    var onPickHost: (Host) -> Void = { _ in }
    /// (会话名, cwd)。⚠️ **cwd 必须一起传** —— 对话模式靠它找转录文件
    var onOpenChat: (String, String) -> Void
    /// 会话名可以是 nil = 开一个裸终端
    var onOpenTerminal: (String?, String) -> Void
    var onOpenFiles: () -> Void

    @State private var sessions: [BoardSession] = []
    @State private var status = ""
    @State private var sendTo: BoardSession?
    @State private var floating = false
    @State private var pinned: Set<String> = []
    @State private var usageValue: Usage?

    // 手指在不在列表上。见下面 `busyHands` 的注释。
    @State private var fingerDown = false
    @State private var fingerDownAt = Date.distantPast
    @State private var fingerUpAt = Date.distantPast

    private var tops: [BoardSession] { sessions.filter { pinned.contains($0.name) } }

    /// ⚠️ 只有「刷新失败」才进 `status`；「连接中 / 连不上」是**算出来**的。
    /// 把连接状态也写进 status 的话，那句话会一直钉在那儿不动 —— 它是记住的状态，没人负责清。
    private var subtitle: String {
        if !status.isEmpty { return status }
        if !link.isConnected { return link.error == nil ? "连接中…" : "连不上" }
        return "\(sessions.count) 个会话 · 点读对话 · 长按更多"
    }

    /// 轮询循环的重启键。
    ///
    /// ⚠️ **故意不只用 `link.id`。** 约定是「连接状态一变就换个新 id」，
    /// 而**凡是靠每个调用点自觉遵守的约定，就会有人漏**（安卓上同一个形状踩过三次：#24 / #25 / #32）。
    /// 漏了的后果是看板永远不刷新、还看不出为什么 —— 把 `isConnected` 也拌进键里，
    /// 至少「连上了却不开始刷」这一类不会发生。
    private var pollKey: String { "\(link.id)|\(link.isConnected)" }

    private func group(_ st: SessionState) -> [BoardSession] {
        sessions.filter { $0.state == st && !pinned.contains($0.name) }
    }

    var body: some View {
        VStack(spacing: 0) {
            header

            // 用量卡固定在列表上方 —— 它是「今天还能干多少」的背景信息，
            // 不该跟着会话列表一起滚走
            if let u = usageValue {
                UsageCard(u).padding(.horizontal, 14).padding(.bottom, 8)
            }

            if !link.isConnected, let err = link.error {
                errorBar(err).padding(.horizontal, 14).padding(.bottom, 8)
            }

            list
        }
        .background(Yx.surface)
        // ⚠️ 键是 host.id：外层换主机时这个视图**不重建**（同一个身份），
        // 不跟着换的话会拿上一台机器的置顶名单和会话列表接着显示
        .task(id: host.id) {
            pinned = Pinned.get(hostId: host.id)
            sessions = []
            status = ""
            // ⚠️ **不从缓存预填。** 详情卡上不标时间，拿旧数字填等于显示假数字 ——
            // 主机列表那条细线会标「几小时前」，那儿才准用缓存（#51）
            usageValue = nil
        }
        // ⚠️ 键见 `pollKey`：外层每次连接状态变化都该换一个新的 `link.id`
        .task(id: pollKey) { await poll() }
        .task(id: pollKey) { await pollUsage() }
        .sheet(item: $sendTo) { target in
            SendSheet(target: target) { text in
                let svc = link.service
                Task { try? await svc?.send(session: target.name, text: text) }
                sendTo = nil
            }
        }
        .fullScreenCover(isPresented: $floating) {
            Switcher(
                service: link.service,
                hostId: host.id,
                current: nil,
                onPick: { onOpenChat($0.name, $0.cwd) },
                onDismiss: { floating = false }
            )
        }
    }

    // MARK: 顶栏

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                if hosts.count > 1 {
                    // 原生 Menu 就是安卓 DropdownMenu 的对应物，不用自己搭
                    Menu {
                        ForEach(hosts) { h in
                            Button {
                                onPickHost(h)
                            } label: {
                                Text(h.id == host.id ? "\(h.alias)  ✓" : h.alias)
                            }
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Text(host.alias).font(.system(size: 24, weight: .semibold))
                            Text("▾").font(.system(size: 17)).foregroundStyle(Yx.dim)
                        }
                    }
                    .tint(Yx.onSurface)
                } else {
                    Text(host.alias).font(.system(size: 24, weight: .semibold))
                }

                Text(subtitle)
                    .font(.mono(12))
                    .foregroundStyle(Yx.dim)
                    .lineLimit(1)          // 窄屏上会折成两行把下面顶下去
                    .truncationMode(.tail)
            }
            Spacer(minLength: 8)
            HStack(spacing: 8) {
                pillButton("悬浮") { floating = true }
                pillButton("文件", action: onOpenFiles)
                pillButton("终端") { onOpenTerminal(nil, ".") }
            }
        }
        .foregroundStyle(Yx.onSurface)
        .padding(.horizontal, Yx.pad)
        .padding(.top, 14)
        .padding(.bottom, 10)
    }

    private func pillButton(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            YxPill { Text(label).font(.system(size: 14, weight: .medium)) }
        }
        .buttonStyle(.plain)
        .foregroundStyle(Yx.onSurface)
    }

    // MARK: 连不上时那条

    /// ⚠️ **不放按钮：下拉就是刷新 / 重连。**
    /// 多一个按钮 = 多一个要解释的东西，而下拉是这类列表上人人都会先试的手势。
    /// 这里只负责**告诉他能拉**。
    private func errorBar(_ err: String) -> some View {
        HStack(alignment: .top) {
            Text(err.split(separator: "\n").first.map(String.init) ?? err)
                .font(.system(size: 13))
                .frame(maxWidth: .infinity, alignment: .leading)
            Text("↓ 下拉重连").font(.system(size: 12, weight: .medium))
        }
        .foregroundStyle(Yx.error)
        .padding(.horizontal, 16).padding(.vertical, 12)
        .background(Yx.errorBox, in: RoundedRectangle(cornerRadius: Yx.cardRadius, style: .continuous))
    }

    // MARK: 列表

    private var list: some View {
        ScrollView {
            LazyVStack(spacing: 9) {
                // ⚠️ 空列表也要**占住高度**。不占的话 ScrollView 的内容几乎为零，
                // 下拉手势没地方使 —— 而「没连上时下拉 = 重连」恰恰是最需要它的那一刻。
                if sessions.isEmpty {
                    Text(link.isConnected ? "这台机器上没有 tmux 会话" : "下拉重连")
                        .font(.system(size: 15))
                        .foregroundStyle(Yx.dim)
                        .frame(maxWidth: .infinity, minHeight: 280)
                }
                // ⚠️ 置顶的**从原来的组里拿出来**单独放最上面。留在原组只加个图标的话，
                // 会话一多（安卓上实测 22 个）照样要翻半天才找到 —— 那就等于没置顶
                if !tops.isEmpty {
                    sectionHeader(icon: "📌", label: "置顶", color: Yx.dim, count: tops.count)
                    ForEach(tops) { card($0, isPinned: true) }
                }
                ForEach([SessionState.needsYou, .working, .done, .idle], id: \.self) { st in
                    let g = group(st)
                    if !g.isEmpty {
                        sectionHeader(
                            dot: dotColor(st), label: st.label,
                            color: st == .needsYou ? Yx.amber : Yx.dim, count: g.count
                        )
                        ForEach(g) { card($0, isPinned: false) }
                    }
                }
            }
            .padding(.horizontal, 14)
            .padding(.top, 4)
            .padding(.bottom, 20)
            // 换组时滑过去而不是瞬移 —— 至少让用户看见「它动了」
            .animation(.default, value: sessions)
        }
        // ⚠️ **下拉的两种含义合成一个手势**：没连上就是重连，连上了就是立刻刷一遍。
        // 分成两个入口（按钮 + 下拉）只会让人猜该按哪个。
        // ⚠️ 未验证两点：① `.refreshable` 挂在 ScrollView 上要 iOS 16+（挂 List 才是 15+）；
        // ② 它的 action 是 `@Sendable`，而这个 View 是 `@MainActor` 的 —— Swift 5 语言模式下
        // 这么写到处都是、能过；**要是项目开了 Swift 6 严格并发，这里会报捕获非 Sendable**。
        .refreshable { await pullRefresh() }
        // ⚠️ 未验证：靠一个并行的 DragGesture 来知道「手指在不在列表上」。
        // iOS 上没有 `LazyListState.isScrollInProgress` 的对应物（`onScrollPhaseChange` 要 iOS 18），
        // `.simultaneousGesture` 的意思是「我也听，但不抢」，滚动照常归 ScrollView。
        .simultaneousGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    if !fingerDown { fingerDownAt = Date() }
                    fingerDown = true
                }
                .onEnded { _ in fingerDown = false; fingerUpAt = Date() }
        )
    }

    private func sectionHeader(icon: String? = nil, dot: Color? = nil, label: String, color: Color, count: Int) -> some View {
        HStack(spacing: 8) {
            if let icon { Text(icon).font(.system(size: 12)) }
            if let dot { Circle().fill(dot).frame(width: 7, height: 7) }
            Text(label).font(.system(size: 12, weight: .medium)).foregroundStyle(color)
            Text("\(count)").font(.mono(11)).foregroundStyle(Yx.dim)
            Spacer()
        }
        .padding(.leading, 4).padding(.top, 12).padding(.bottom, 2)
    }

    private func card(_ s: BoardSession, isPinned: Bool) -> some View {
        SessionCard(
            s: s,
            isPinned: isPinned,
            onOpen: { onOpenChat(s.name, s.cwd) },
            onTerminal: { onOpenTerminal(s.name, s.cwd) },
            onSend: { sendTo = s },
            onTogglePin: {
                if pinned.contains(s.name) { pinned.remove(s.name) } else { pinned.insert(s.name) }
                Pinned.set(hostId: host.id, pinned)
            }
        )
    }

    // MARK: 刷新

    /// **手指在列表上（或刚松开还在惯性滑）的时候不要刷。**
    ///
    /// ⚠️ 会话换组（干活中 → 等你）会让下面的卡片整体上移，而刷新和点击之间
    /// 只有几十毫秒 —— 安卓上就因此点进过别人的会话。
    /// **用户看到的位置和点下去的位置必须是同一个。**
    ///
    /// 最后那个 8 秒是**保险**：万一 `onEnded` 因为被 ScrollView 抢走而没送到，
    /// `fingerDown` 会永远挂在 true 上 —— 那就成了「刷新永久停掉」，比刷早了糟得多。
    private var busyHands: Bool {
        let now = Date()
        if fingerDown && now.timeIntervalSince(fingerDownAt) < 8 { return true }
        return now.timeIntervalSince(fingerUpAt) < 1.2
    }

    private func poll() async {
        guard let svc = link.service else { return }
        while true {
            if !busyHands { await probe(svc) }
            // ⚠️ 睡眠被取消要**直接退出**。用 `try?` 吞掉的话循环会空转成死循环
            do { try await Task.sleep(nanoseconds: 5_000_000_000) } catch { return }
        }
    }

    private func probe(_ svc: any SessionService) async {
        do {
            sessions = try await svc.snapshot()
            status = ""
        } catch is CancellationError {
            // ⚠️ **取消不是失败。** 安卓上同一个错踩了五次（TROUBLESHOOTING #78/#79）：
            // 界面重组时任务被取消，异常被当成故障写进 status，
            // 而 status 是记住的状态 —— 那句假错误就永远钉在界面上了。
            return
        } catch {
            status = "刷新失败：\(error.localizedDescription)"
        }
    }

    private func pullRefresh() async {
        if let svc = link.service {
            await probe(svc)
            // 转一下让人看见它确实动了 —— 一闪而过的刷新等于没反馈
            try? await Task.sleep(nanoseconds: 400_000_000)
        } else {
            link.retry()
            try? await Task.sleep(nanoseconds: 900_000_000)
        }
    }

    /// 用量单独一条慢节奏 —— 它 5 小时才变一格，没必要跟着 5 秒刷。
    private func pollUsage() async {
        guard link.isConnected, let u = usage else { return }
        while true {
            if let got = await u.probe() {
                usageValue = got
                UsageCache.put(hostId: host.id, got)
            }
            do { try await Task.sleep(nanoseconds: 120_000_000_000) } catch { return }
        }
    }
}

// MARK: - 卡片

func dotColor(_ st: SessionState) -> Color {
    switch st {
    case .needsYou: return Yx.amber      // 琥珀：全 app 只在需要你动手时出现
    case .working:  return Yx.teal
    default:        return Yx.idleDot
    }
}

@MainActor
private struct SessionCard: View {
    let s: BoardSession
    let isPinned: Bool
    let onOpen: () -> Void
    let onTerminal: () -> Void
    let onSend: () -> Void
    let onTogglePin: () -> Void

    var body: some View {
        YxCard {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 0) {
                    Text(s.short)
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(Yx.onSurface)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    // 图钉一直在（不是只在置顶时才出现）—— 只在置顶时显示的话，
                    // 用户根本不知道有这个功能
                    Button(action: onTogglePin) {
                        Text("📌")
                            .font(.system(size: 11))
                            .opacity(isPinned ? 1 : 0.45)
                            .padding(.horizontal, 10).padding(.vertical, 5)
                            .background(isPinned ? Yx.copperBox : Yx.high, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    if s.attached {
                        Text("已连").font(.mono(11)).foregroundStyle(Yx.dim).padding(.leading, 8)
                    }
                }
                if !s.detail.isEmpty {
                    Text(s.detail)
                        .font(.system(size: 13))
                        .foregroundStyle(Yx.onSurfaceVar)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                }
                HStack(spacing: 8) {
                    Text(s.cwd).font(.mono(11)).foregroundStyle(Yx.dim).lineLimit(1)
                    Spacer(minLength: 4)
                    // 「上次动过是多久以前」—— 解析早就对了（转录 mtime，#130），
                    // 但一直没显示出来。二十个会话里挑一个，这是最有用的一条线索。
                    Text(ago(s.lastActivity)).font(.mono(11)).foregroundStyle(Yx.dim)
                }

                // 只有「等你」那组带按钮 —— 其余安静
                if s.state == .needsYou {
                    HStack(spacing: 8) {
                        Button(action: onSend) {
                            Text("回它一句")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(Yx.onCopper)
                                .frame(maxWidth: .infinity).frame(height: Yx.tap)
                                .background(Yx.copper, in: Capsule())
                        }
                        Button(action: onTerminal) {
                            Text("开终端")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(Yx.onSurface)
                                .frame(maxWidth: .infinity).frame(height: Yx.tap)
                                .background(Yx.high, in: Capsule())
                        }
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 4)
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 14)
        }
        // 点卡片 = 对话模式（主界面）。
        // ⚠️ 未验证：卡片本身挂 `onTapGesture`，里头还有图钉和两个按钮 ——
        // 靠的是「子 Button 先吃掉点击」。在 ScrollView 里这是常规写法，但没编译过。
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
        // 安卓那版是「长按 = 发消息」。iOS 上长按的原生形态是 contextMenu，
        // 顺手把「开终端」和置顶也放进来 —— 一个隐藏动作换三个看得见的
        .contextMenu {
            Button("回它一句", action: onSend)
            Button("开终端", action: onTerminal)
            Button(isPinned ? "取消置顶" : "置顶", action: onTogglePin)
        }
    }
}

// MARK: - 发消息

/// 不进终端就能给任意会话发一句话 —— 这是我们比 Moshi 强的地方（PRD §1.6）。
@MainActor
private struct SendSheet: View {
    let target: BoardSession
    let onSend: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        // ⚠️ 表要能滚 + 让开键盘。SwiftUI 的 ScrollView 默认就吃 keyboard safe area，
        // 但外面必须真是个 ScrollView，否则「发送」会被键盘盖住（安卓踩过，#68）
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("发给 \(target.short)").font(.system(size: 22, weight: .semibold))
                YxHint("不用先 attach —— 直接送进那个会话（tmux send-keys）。")
                TextEditor(text: $text)
                    .font(.system(size: 15))
                    .frame(minHeight: 96)
                    .scrollContentBackground(.hidden)   // iOS 16+：不然 TextEditor 是白底
                    .padding(8)
                    .background(Yx.container, in: RoundedRectangle(cornerRadius: Yx.blockRadius, style: .continuous))
                    .focused($focused)
                Button {
                    let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !t.isEmpty { onSend(t); dismiss() }
                } label: {
                    Text("发送")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Yx.onCopper)
                        .frame(maxWidth: .infinity).frame(height: 52)
                        .background(
                            text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                ? Yx.high : Yx.copper,
                            in: Capsule()
                        )
                }
                .buttonStyle(.plain)
                .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(.horizontal, Yx.pad).padding(.top, 20).padding(.bottom, 28)
        }
        .background(Yx.low)
        .foregroundStyle(Yx.onSurface)
        .presentationDetents([.medium, .large])
        .onAppear { focused = true }
    }
}

// MARK: - 置顶

/// 置顶的会话名。**按主机分开存** —— 换台机器同名会话未必是同一件事。
///
/// ⚠️ 只存在手机本地，不写进服务器。置顶是「我关心哪几个」，
/// 是这台手机的偏好，不是那台机器的状态 —— 写过去会污染别人的视图。
enum Pinned {
    static func get(hostId: String) -> Set<String> {
        Set(UserDefaults.standard.stringArray(forKey: "pinned:\(hostId)") ?? [])
    }
    static func set(hostId: String, _ v: Set<String>) {
        UserDefaults.standard.set(Array(v), forKey: "pinned:\(hostId)")
    }
}
