import SwiftUI
import YxiKit
#if canImport(WidgetKit)
import WidgetKit
#endif

/// 会话看板：**等你 / 干活中 / 已完成 / 空闲** 四段（前三段是主角）。
///
/// 只有「等你」那组带操作按钮，其余安静 —— 琥珀色是全 app 唯一
/// 「需要你动手」的信号，别处不用（PRD 附录 J.1）。
///
/// ⚠️ 这个界面**不需要在服务器上装任何东西**：`tmux list-sessions` 和
/// `~/.cloud-status` 下的状态文件都是现成的（后者由 `cc-state` 写，早就在跑）。
@MainActor
enum SendError: LocalizedError {
    case notConnected
    var errorDescription: String? { "没连上" }
}

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
    /// 抓到会话表就回传一份 —— 工作区的标题下拉要拿它换会话
    var onSessions: ([BoardSession]) -> Void = { _ in }

    @State private var sessions: [BoardSession] = []
    @State private var newSession = false
    @State private var muted: Set<String> = []
    @State private var status = ""
    @State private var sendTo: BoardSession?
    @State private var floating = false
    @State private var pinned: Set<String> = []
    @State private var usageValue: Usage?
    /// 收藏了哪些。⚠️ **跟置顶各管各的** —— 置顶管位置，收藏管「还要不要它」
    @State private var faved: Set<String> = []
    /// 按什么轴看：false = 按状态（谁在等我），true = 按分组（这摊活儿都谁在干）
    @State private var grouped = false
    /// 收起来的组名（只存收起的，见 `BoardPrefs.collapsed`）
    @State private var collapsed: Set<String> = []
    /// 分组表。**在服务器上**，不在手机里 —— 读它的还有组里的 agent 自己
    @State private var groups = Groups.Table()
    /// 正在给哪个会话选组
    @State private var grouping: BoardSession?
    /// 左滑到一半的那张卡片（只是视觉反馈，松手才决定要不要弹确认框）
    @State private var swipedName: String?
    /// 要终止哪个会话（左滑或长按「终止」之后弹确认框）。nil = 没在问
    @State private var killing: BoardSession?

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

    /// 这个组里现在活着的成员。
    private func members(of group: String) -> [BoardSession] {
        let names = Set(groups.groups[group] ?? [])
        return sessions.filter { names.contains($0.name) }
    }

    /// **没编进任何组的**。⚠️ 一定要兜底列出来 —— 不兜的话，刚建完第一个组的那一刻
    /// 大部分会话会凭空消失，看着像丢了。
    private var ungrouped: [BoardSession] {
        let inAny = Set(groups.groups.values.flatMap { $0 })
        return sessions.filter { !inAny.contains($0.name) }
    }

    /// 收藏过、但现在没在跑的。看板**最后**那一段「未启用」就是它们。
    private var dormant: [String] {
        let live = Set(sessions.map(\.name))
        return faved.filter { !live.contains($0) }.sorted()
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
            // 收藏 / 视图 / 收起的组也是按主机分开存的，换主机一起换
            faved = Fav.all(host.id)
            grouped = BoardPrefs.grouped(host.id)
            collapsed = BoardPrefs.collapsed(host.id)
            groups = Groups.Table()
            sessions = []
            status = ""
            // ⚠️ **不从缓存预填。** 详情卡上不标时间，拿旧数字填等于显示假数字 ——
            // 主机列表那条细线会标「几小时前」，那儿才准用缓存（#51）
            usageValue = nil
        }
        // ⚠️ 键见 `pollKey`：外层每次连接状态变化都该换一个新的 `link.id`
        // ⚠️ 换主机要重新读 —— 静音是**按主机分开存**的
        .task(id: host.id) { muted = Mute.all(host.id) }
        .task(id: pollKey) { await poll() }
        .task(id: pollKey) { await pollUsage() }
        .sheet(isPresented: $newSession) {
            // ⚠️ 传的是「**已经开着会话的目录**」，用来把它们从候选里剔掉 ——
            // 原来传的是同一批目录、却当成「推荐去处」列出来，正好反了：
            // 点进去只会跳回同一个会话，这个入口等于什么也没做。
            NewSessionSheet(taken: sessions.map(\.cwd),
                            runner: link.service as? ShellRunner) { dir in
                newSession = false
                let svc = link.service
                Task {
                    // 有就直接开，没有才新建并在那个目录里把 claude 跑起来（幂等）
                    _ = try? await (svc as? ShellRunner)?
                        .run(SessionProbe.newSessionCommand(dir: dir))
                    onOpenChat(SessionProbe.sessionName(forDir: dir), dir)
                }
            }
        }
        .sheet(item: $sendTo) { target in
            SendSheet(target: target) { text in
                // ⚠️ **不能「弹窗关掉、错误吞掉」。** 原来是 `try?` + 立刻 `sendTo = nil`：
                // 发不出去时用户什么都看不到，以为发出去了 —— 而他正是在
                // 「Claude 等你回话」的时候按的这个按钮，最不该静默失败。
                // ⚠️ 用 `Task {}`（非结构化）：弹窗关掉了也要把话送出去。
                let svc = link.service
                sendTo = nil
                Task {
                    do {
                        guard let svc else { throw SendError.notConnected }
                        try await svc.send(session: target.name, text: text)
                        status = "已发给 \(target.short)"
                    } catch {
                        status = "没发出去（\(target.short)）：" + String(error.localizedDescription.prefix(40))
                    }
                }
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
        // 给某个会话选组。长按卡片 →「分组…」进来（长按的原生形态就是 contextMenu）
        .sheet(item: $grouping) { target in
            GroupSheet(session: target.name, table: groups) { table, joined in
                grouping = nil
                saveGroups(table, announce: joined)
            }
        }
        // 终止确认。⚠️ **杀会话 = 里面跑着的 Claude 一起没**，没存的东西不会自己保存 ——
        // 所以菜单里那一下只是把这个框弹出来，真正的决定在这儿（安卓那边滑动也只弹框）。
        .confirmationDialog(
            killTitle,
            isPresented: Binding(get: { killing != nil }, set: { if !$0 { killing = nil } }),
            titleVisibility: .visible,
            presenting: killing
        ) { s in
            Button("终止", role: .destructive) { kill(s); killing = nil }
            Button("算了", role: .cancel) { killing = nil }
        } message: { s in
            Text(killWarning(s))
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
            // ⚠️ **这儿不能再留 `Spacer`。** 可横滑的工具条自己就会把右边填满，
            // 而 Spacer 在栈里是「给多少吃多少」—— 两个都想要更多的家伙会平分剩下的宽度，
            // 结果是中间空一大块、工具条反而被压到要滑。留白改由下面那句 padding 给。
            // ⚠️ **必须 `.fixedSize()`。** 加到四颗之后这一行放不下，SwiftUI 就去压
            // 每一颗的宽度 —— 实测「悬浮」被压成竖着的两个字还被裁掉一半
            // （CI 截图里看出来的）。宁可让左边的标题截断，也不能把按钮压变形。
            //
            // ⚠️ 加上「状态/分组」是**第五颗**，窄屏（SE 那种 320pt）上肯定摆不下：
            // 五颗药丸约 320pt，加上左右留白就顶到屏幕边了，最后一颗会被裁掉。
            // 跟安卓同一个解法：**工具条自己能横向滚** —— 装不下就滑，
            // 既不压药丸也不把主机名整个挤没（安卓 0.9.35 就是只加了横滚没管宽度，
            // 用户报「主机『天亮』被挡住了」）。
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    pillButton("＋") { newSession = true }
                    // 换个轴看：按状态（谁在等我）⇄ 按分组（这摊活儿都谁在干）。
                    // 药丸上写的是**当前**在按什么看，点一下换另一个。
                    pillButton(grouped ? "分组" : "状态",
                               fill: grouped ? Yx.copperBox : Yx.container) {
                        grouped.toggle()
                        BoardPrefs.setGrouped(host.id, grouped)
                    }
                    pillButton("悬浮") { floating = true }
                    pillButton("文件", action: onOpenFiles)
                    pillButton("终端") { onOpenTerminal(nil, ".") }
                }
                .fixedSize()
            }
            .padding(.leading, 8)
            // ⚠️ 横滑容器在竖直方向也是「有多少要多少」，不钉住高度会去抢一整屏
            // （`MarkdownStyle` 里那个嵌套 ScrollView 踩的是同一个坑）
            .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(Yx.onSurface)
        .padding(.horizontal, Yx.pad)
        .padding(.top, 14)
        .padding(.bottom, 10)
    }

    private func pillButton(_ label: String, fill: Color = Yx.container,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            YxPill(fill: fill) { Text(label).font(.system(size: 14, weight: .medium)).lineLimit(1) }
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
                if grouped { groupSections } else { stateSections }
                dormantSection
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

    // MARK: 按状态看

    @ViewBuilder private var stateSections: some View {
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

    // MARK: 按分组看

    /// ⚠️ **分组视图下不把置顶单独拎出来。**
    /// 拎出来会让置顶的会话**画两遍**（上面一次、自己那个组里又一次）——
    /// 安卓 0.9.35 加分组时引进的就是这个 bug。
    /// 道理上也不该拎：切到分组视图，选的就是「按组看」这个轴，
    /// 再横插一个置顶区等于同时用两个轴。这儿置顶只当个标记（图钉亮着）。
    @ViewBuilder private var groupSections: some View {
        // 组按名字排。**空组也画头** —— 建了组还没往里放人时，得看得见它存在
        ForEach(groups.groups.keys.sorted(), id: \.self) { g in
            groupHeader(g, count: members(of: g).count)
            if !collapsed.contains(g) {
                ForEach(members(of: g)) { card($0, isPinned: pinned.contains($0.name)) }
            }
        }
        // 没编进任何组的兜底放最后（为什么一定要兜，见 `ungrouped`）
        if !ungrouped.isEmpty {
            groupHeader(ungroupedLabel, count: ungrouped.count)
            if !collapsed.contains(ungroupedLabel) {
                ForEach(ungrouped) { card($0, isPinned: pinned.contains($0.name)) }
            }
        }
    }

    /// 组头：名字 + 几个 + 收起/展开的三角。**整行可点 = 收起/展开**。
    private func groupHeader(_ name: String, count: Int) -> some View {
        let shut = collapsed.contains(name)
        return Button {
            if shut { collapsed.remove(name) } else { collapsed.insert(name) }
            BoardPrefs.setCollapsed(host.id, collapsed)
        } label: {
            HStack(spacing: 8) {
                // ⚠️ 用**旋转同一个三角**，不用 ▸/▾ 两个字符 ——
                // 后者在不同机型上宽度不一样，会让整行标题左右跳
                Text("▸")
                    .font(.system(size: 11))
                    .foregroundStyle(Yx.dim)
                    .rotationEffect(.degrees(shut ? 0 : 90))
                Text(name).font(.system(size: 12, weight: .medium)).foregroundStyle(Yx.dim)
                Text("\(count)").font(.mono(11)).foregroundStyle(Yx.dim)
                Spacer()
            }
            .padding(.leading, 4).padding(.top, 12).padding(.bottom, 2)
            // ⚠️ 得写在 padding **后面**：写前面的话点得响应的只有那几个字，
            // 留白和右边那片空白都点不动 —— 而组头是**整行**可点
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .animation(.easeInOut(duration: 0.15), value: shut)
    }

    // MARK: 未启用

    /// 收藏过、但现在没在跑的。
    ///
    /// ⚠️ 放在看板**最后**：它们不该占注意力，只是「随时能拉回来」。
    /// 放前面会让每天都在看的活会话被一堆睡着的挤下去。
    @ViewBuilder private var dormantSection: some View {
        if !dormant.isEmpty {
            sectionHeader(dot: Yx.idleDot, label: "未启用", color: Yx.dim, count: dormant.count)
            ForEach(dormant, id: \.self) { n in
                DormantCard(
                    name: n,
                    cwd: Fav.cwd(host.id, n),
                    onWake: { wake(n) },
                    onForget: {
                        faved.remove(n)
                        Fav.set(host.id, faved)
                    }
                )
            }
        }
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
            isFaved: faved.contains(s.name),
            onOpen: { onOpenChat(s.name, s.cwd) },
            onTerminal: { onOpenTerminal(s.name, s.cwd) },
            onSend: { sendTo = s },
            onTogglePin: {
                if pinned.contains(s.name) { pinned.remove(s.name) } else { pinned.insert(s.name) }
                Pinned.set(hostId: host.id, pinned)
            },
            onToggleFav: { toggleFav(s) },
            onGroup: { grouping = s },
            // ⚠️ **不直接杀**，只把确认框弹出来 —— 话术见 `killWarning`
            onKill: { killing = s },
            isMuted: muted.contains(s.name),
            onToggleMute: {
                Mute.toggle(host.id, s.name)
                muted = Mute.all(host.id)
            }
        )
        // ⚠️ **左滑终止**（用户点名要的手势）。
        //
        // ⚠️ iOS 原生的 `.swipeActions` **只在 `List` 里能用**，而这块看板是
        // `ScrollView` + `LazyVStack`（卡片是自绘的，换成 List 会把整套样式推倒重来）。
        // 所以用 `DragGesture` 自己实现：只认**向左**、位移超过 60pt 才算数。
        // ⚠️ `minimumDistance: 20` 是为了不跟 ScrollView 的纵向滚动打架 ——
        // 手指稍微斜一点就被判成滑动的话，列表根本滚不动。
        // ⚠️ 松手只是把确认框弹出来，**不直接杀** —— 杀会话 = 里面跑着的 Claude
        // 一起没，这种事不能由一个可能是误触的手势独自决定。
        .offset(x: swipedName == s.name ? -66 : 0)
        .animation(.spring(response: 0.28, dampingFraction: 0.82), value: swipedName)
        .simultaneousGesture(
            DragGesture(minimumDistance: 20)
                .onChanged { g in
                    if g.translation.width < -30 && abs(g.translation.height) < 34 {
                        swipedName = s.name
                    }
                }
                .onEnded { g in
                    swipedName = nil
                    if g.translation.width < -60 && abs(g.translation.height) < 40 { killing = s }
                }
        )
    }

    /// ⚠️ 收藏的一刻**顺手把 cwd 记下来**：会话被杀之后就没处问了，
    /// 而「未启用」里那句「在原目录拉回来」全靠它（见 `Fav.remember`）。
    private func toggleFav(_ s: BoardSession) {
        if faved.contains(s.name) {
            faved.remove(s.name)
        } else {
            faved.insert(s.name)
            Fav.remember(host.id, s.name, cwd: s.cwd)
        }
        Fav.set(host.id, faved)
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
        await loadGroups()
        while true {
            if !busyHands { await probe(svc) }
            // ⚠️ 睡眠被取消要**直接退出**。用 `try?` 吞掉的话循环会空转成死循环
            do { try await Task.sleep(nanoseconds: 5_000_000_000) } catch { return }
        }
    }

    private func probe(_ svc: any SessionService) async {
        do {
            sessions = try await svc.snapshot()
            // ⚠️ 收藏的会话**趁它活着**把 cwd 记下来 —— 死了才复活得回原地；
            // 只记名字的话 tmux 会把它开在 $HOME，不是原来干活的那个目录。
            for s in sessions where faved.contains(s.name) {
                Fav.remember(host.id, s.name, cwd: s.cwd)
            }
            status = ""
            onSessions(sessions)
            publishToWidget()
        } catch is CancellationError {
            // ⚠️ **取消不是失败。** 安卓上同一个错踩了五次（TROUBLESHOOTING #78/#79）：
            // 界面重组时任务被取消，异常被当成故障写进 status，
            // 而 status 是记住的状态 —— 那句假错误就永远钉在界面上了。
            return
        } catch {
            status = "刷新失败：\(error.localizedDescription)"
        }
    }

    /// 把「几个在等你」留给桌面小组件。
    ///
    /// ⚠️ **写完必须叫 `reloadAllTimelines()`** —— 只写文件的话小组件要等系统
    /// 下次给预算才刷新，桌面上可能挂着几十分钟前的数字。
    /// ⚠️ 这一句只能写在界面层：`YxiKit` 不许 import WidgetKit（它得在 Linux 上编得过）。
    private func publishToWidget() {
        let waiting = sessions.filter { $0.state == .needsYou }
        WaitingSnapshot.publish(
            waiting: waiting.count,
            working: sessions.filter { $0.state == .working }.count,
            names: waiting.map(\.short)
        )
        #if canImport(WidgetKit)
        WidgetCenter.shared.reloadAllTimelines()
        #endif
    }

    private func pullRefresh() async {
        if let svc = link.service {
            await probe(svc)
            // 分组表平时只在连上时读一次（它只在你自己编组时变）——
            // 别处（安卓、agent）改了组，下拉这一下就是取回来的入口
            await loadGroups()
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

    // MARK: 分组（存在**服务器**上）

    /// 读服务器上的 `~/.yxi/groups.json`。
    ///
    /// ⚠️ 读不到 / 读不懂**一律当没有分组**（`Groups.parse` 对空串和坏 JSON 都返回空表）。
    /// 为了一个坏掉的分组表让整个看板打不开，是拿主功能给附加功能陪葬。
    private func loadGroups() async {
        guard let runner = link.service as? ShellRunner else { return }
        groups = Groups.parse((try? await runner.run("cat \"$HOME/.yxi/groups.json\" 2>/dev/null")) ?? "")
    }

    /// 存回服务器，然后**给组里每个人发一句「你有队友了」**。
    ///
    /// ⚠️ 那一句才是「打通」真正发生的时刻：不发的话，分组对 agent 而言根本不存在 ——
    /// 它不会主动去读 groups.json，也就不知道自己有队友、更不知道能 `yxi-hub say` 找谁。
    private func saveGroups(_ table: Groups.Table, announce joined: String?) {
        groups = table                      // 先画出来，别让人干等一趟 SSH
        guard let svc = link.service, let runner = svc as? ShellRunner else {
            status = "没连上 —— 分组只在这台手机上，没存到服务器"
            return
        }
        Task {
            do {
                _ = try await runner.run(Groups.saveCommand(table))
            } catch {
                status = "分组没存上：" + String(error.localizedDescription.prefix(40))
                return
            }
            guard let joined else { return }
            var told = 0
            for (who, what) in Groups.announcements(table, group: joined) {
                // 某个会话正好没了就跳过，别让整组白编
                do { try await svc.send(session: who, text: what); told += 1 } catch { continue }
            }
            if told > 0 { status = "「\(joined)」组已打通，通知了 \(told) 个" }
        }
    }

    // MARK: 终止

    private var killTitle: String { "终止 \(killing?.short ?? "")？" }

    /// 确认框里那段话。
    ///
    /// ⚠️ 正在干活 / 正在等你的要**额外说一句** —— 这两种状态下杀掉最可能丢东西，
    /// 而「确定吗」这种没信息量的提示等于没问。
    private func killWarning(_ s: BoardSession) -> String {
        var lines = ["会话连同里面跑着的 Claude 一起结束，占的内存放出来。转录文件留着，不会删。"]
        switch s.state {
        case .working:  lines.append("⚠️ 它正在干活 —— 现在杀会丢掉这一轮还没写完的东西。")
        case .needsYou: lines.append("⚠️ 它正在等你回答 —— 杀掉这个问题就没了。")
        default: break
        }
        if faved.contains(s.name) {
            lines.append("它是收藏的 —— 杀掉后会进「未启用」，随时点一下就能在原目录拉回来。")
        }
        return lines.joined(separator: "\n\n")
    }

    private func kill(_ s: BoardSession) {
        // ⚠️ 杀之前把 cwd 落一次盘：会话一没，就再也问不出它原来在哪个目录了
        if faved.contains(s.name) { Fav.remember(host.id, s.name, cwd: s.cwd) }
        let svc = link.service
        Task {
            do {
                guard let svc else { throw SendError.notConnected }
                try await svc.kill(session: s.name)
                await probe(svc)     // 立刻刷一遍，别让杀掉的卡片再挂 5 秒
            } catch {
                status = "终止失败（\(s.short)）：" + String(error.localizedDescription.prefix(40))
            }
        }
    }

    // MARK: 唤起

    /// 把「未启用」里的会话在**原目录**拉回来。
    private func wake(_ name: String) {
        guard let runner = link.service as? ShellRunner else { return }
        let svc = link.service
        let cwd = Fav.cwd(host.id, name)
        status = "在拉起 \(name.hasPrefix("cc-") ? String(name.dropFirst(3)) : name)…"
        Task {
            let out = (try? await runner.run(wakeCommand(name: name, cwd: cwd))) ?? ""
            // ⚠️ **不信 tmux 的退出码**，只认命令自己回报的那一行（理由见 `wakeCommand`）
            guard out.contains(wakeTag + ":ok") else {
                status = out.contains(wakeTag + ":nodir")
                    ? "拉不起来 —— \(cwd ?? "~") 建不出来（没权限，或者上级路径不对）"
                    : "拉不起来 —— tmux 没把它开起来"
                return
            }
            status = ""
            if let svc { await probe(svc) }
            onOpenChat(name, cwd ?? ".")
        }
    }
}

/// 「唤起」命令自己回报的那一行的前缀。
private let wakeTag = "__YXI_WAKE__"

/// 「在原目录把这个会话拉回来」的命令。有就直接用，没有才新建并跑起 `claude`（幂等）。
///
/// ⚠️ **`tmux new-session -c <不存在的目录>` 会返回 0，然后跑到 `$HOME` 去** ——
/// 不报错、不非零退出，App 只会高高兴兴跳进一个开错地方的会话（安卓踩过：
/// 想开在 `/root/src/workspace/logto`，最后开在了 `/root`）。所以先 `mkdir -p`，
/// 建不出来就**什么都不做**并回报 `nodir` —— 宁可这一下不生效，也别开错地方。
///
/// ⚠️ 不复用 `SessionProbe.newSessionCommand`：那个是**从目录推名字**的，
/// 而唤起要的是「**原来那个名字** + 原来那个目录」，两者不一定对得上。
private func wakeCommand(name: String, cwd: String?) -> String {
    let n = name.replacingOccurrences(of: "'", with: "'\\''")
    // 老收藏没记住目录：`-c` 就不给，让 tmux 开在 $HOME —— 卡片上也是这么写的
    var head = "", at = ""
    if let cwd, !cwd.isEmpty {
        let d = cwd.replacingOccurrences(of: "'", with: "'\\''")
        head = "mkdir -p '\(d)' 2>/dev/null; [ -d '\(d)' ] || { echo \(wakeTag):nodir; exit 0; }; "
        at = " -c '\(d)'"
    }
    return head
        + "tmux has-session -t '\(n)' 2>/dev/null || "
        + "{ tmux new-session -d -s '\(n)'\(at) 2>/dev/null && tmux send-keys -t '\(n)' 'claude' Enter; }; "
        // ⚠️ **最后再问 tmux 一遍。** 上面那串的退出码不作数（原因见上），
        // 只有「现在真的有这个会话」才算拉起来了 —— 一次 has-session 换一句不骗人的回报。
        + "tmux has-session -t '\(n)' 2>/dev/null && echo \(wakeTag):ok || echo \(wakeTag):failed"
}

/// 分组视图里，没编进任何组的那一段的名字。**也当收起/展开的键用**（跟安卓一致）。
private let ungroupedLabel = "没编组"

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
    /// 收藏了没。
    ///
    /// ⚠️ **必须是看得见、点得着的按钮。** 安卓那版一开始只做了右滑收藏，
    /// 用户第一句话就是「我没见收藏的按钮」—— **一个没有可见入口的手势等于不存在**，
    /// 滑到一半才显出来的提示，只有已经知道要滑的人才看得到。
    let isFaved: Bool
    let onOpen: () -> Void
    let onTerminal: () -> Void
    let onSend: () -> Void
    let onTogglePin: () -> Void
    let onToggleFav: () -> Void
    let onGroup: () -> Void
    let onKill: () -> Void
    var isMuted: Bool = false
    var onToggleMute: () -> Void = {}

    var body: some View {
        YxCard {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 0) {
                    Text(s.short)
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(Yx.onSurface)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    // ★ 收藏。⚠️ **跟图钉并排**：找得着图钉的人自然就找得着它。
                    // 空心 ☆ = 没收藏，实心 ★ = 收藏了 —— 状态一眼可见，不用点开才知道。
                    // 收藏过的会话死了会进看板最后的「未启用」，随时能在原目录拉回来。
                    Button(action: onToggleFav) {
                        Text(isFaved ? "★" : "☆")
                            .font(.system(size: 12))
                            .foregroundStyle(isFaved ? Yx.onCopperBox : Yx.dim)
                            .padding(.horizontal, 10).padding(.vertical, 5)
                            .background(isFaved ? Yx.copperBox : Yx.high, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .padding(.trailing, 6)
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
                    if isMuted {
                        // 静音了要**看得见** —— 否则「怎么这个会话不提醒我」查不出原因
                        Text("🔕").font(.system(size: 11)).padding(.leading, 6)
                    }
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
            // 星星按钮已经在卡片上了，这儿只是顺手 —— 手势/菜单都只是**补**那颗按钮
            Button(isFaved ? "取消收藏" : "收藏", action: onToggleFav)
            // 编组：同组的 agent 之间能互相发消息（yxi-hub）
            Button("分组…", action: onGroup)
            // 一直在跑的部署、盯日志的那种，根本不想被它 ping
            Button(isMuted ? "取消静音" : "静音（不再提醒）", action: onToggleMute)
            // ⚠️ **点这一下不杀**，只弹确认框 —— 杀会话 = 里面跑着的 Claude 一起没
            Button("终止会话", role: .destructive, action: onKill)
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


// MARK: - 未启用

/// 收藏过、但现在没在跑的那张卡：点「唤起」在**原目录**把它拉回来。
///
/// ⚠️ 没记住 cwd 的（这版之前收藏的）要**说清楚**会开在 `~` ——
/// 不然点下去开错地方，用户会以为「唤起」坏了。
@MainActor
private struct DormantCard: View {
    let name: String
    let cwd: String?
    let onWake: () -> Void
    let onForget: () -> Void

    var body: some View {
        YxCard(fill: Yx.container) {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(name.hasPrefix("cc-") ? String(name.dropFirst(3)) : name)
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(Yx.onSurfaceVar)
                        .lineLimit(1)
                    Text(cwd ?? "不知道原来在哪个目录，会开在 ~")
                        .font(.mono(11)).foregroundStyle(Yx.dim).lineLimit(1)
                }
                Spacer(minLength: 8)
                Button(action: onWake) {
                    Text("唤起")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(Yx.onCopper)
                        .padding(.horizontal, 14).padding(.vertical, 7)
                        .background(Yx.copper, in: Capsule())
                }
                .buttonStyle(.plain)
                // 不想再看见它了 —— 只是取消收藏，服务器上什么都不动
                Button(action: onForget) {
                    Text("✕")
                        .font(.system(size: 13))
                        .foregroundStyle(Yx.dim)
                        .padding(.horizontal, 8).padding(.vertical, 7)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
        }
    }
}

// MARK: - 分组

/// 「这个会话归哪几个组」。
///
/// ⚠️ **多选**：一个会话可以同时在好几个组里（用户明确要的）——
/// 一个 agent 既在「后端」又在「上线」是常事，逼人二选一等于让分组没法用。
private struct GroupSheet: View {
    let session: String
    /// (新表, 这一轮**新加进**的组名)。组名非 nil 就要给那个组里每个人发「你有队友了」
    let onSave: (Groups.Table, String?) -> Void

    @State private var table: Groups.Table
    @State private var fresh = ""
    @State private var joined: String?
    @Environment(\.dismiss) private var dismiss

    init(session: String, table: Groups.Table, onSave: @escaping (Groups.Table, String?) -> Void) {
        self.session = session
        self.onSave = onSave
        _table = State(initialValue: table)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    YxHint("同一个组里的 agent 能互相发消息（yxi-hub）。一个会话可以同时在好几个组里。")
                }
                if !table.groups.isEmpty {
                    Section("已经有的组") {
                        ForEach(table.groups.keys.sorted(), id: \.self) { g in
                            let on = table.groups[g]?.contains(session) == true
                            Button {
                                table = on ? table.withoutMember(g, session)
                                           : table.withMember(g, session)
                                // 退组不用通知谁；**加进去**才要（见 `Groups.announcements`）
                                joined = on ? nil : g
                            } label: {
                                HStack(spacing: 8) {
                                    Text(on ? "✓" : "　")
                                        .font(.system(size: 15)).foregroundStyle(Yx.copper)
                                    Text(g).font(.system(size: 15)).foregroundStyle(Yx.onSurface)
                                    Spacer()
                                    Text("\(table.groups[g]?.count ?? 0)")
                                        .font(.mono(11)).foregroundStyle(Yx.dim)
                                }
                            }
                        }
                    }
                }
                Section {
                    TextField("新建一个组…", text: $fresh)
                        .autocorrectionDisabled()
                        .onSubmit { save() }
                }
            }
            .navigationTitle("归到哪几个组")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("存下") { save() } }
            }
        }
        .presentationDetents([.medium, .large])
    }

    /// ⚠️ **把输入框里还没提交的那个组也算上。**
    /// 安卓上用户报过「新建分组没生效」：病根是那边要点**两下** —— 先点「建 X 并放进去」，
    /// 再点「存下」。少点中间那下，打的字被静默丢掉，服务器上落的是一张空表，
    /// 而界面上没有任何提示。「输入框里有字 = 用户想要这个组」是唯一合理的解读。
    private func save() {
        let name = fresh.trimmingCharacters(in: .whitespaces)
        let out = name.isEmpty ? table : table.withMember(name, session)
        onSave(out, name.isEmpty ? joined : name)
        dismiss()
    }
}

/// 新开一个会话。**先给最近去过的目录**，手机上打路径最费劲。
private struct NewSessionSheet: View {
    /// 已经开着会话的目录 —— 这些**不出现在候选里**
    let taken: [String]
    let runner: ShellRunner?
    let onCreate: (String) -> Void

    @State private var path = ""
    @State private var dirs: [String]?          // nil = 还在找
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    YxHint("选一个还没开会话的目录，会在那儿开一个会话并把 claude 跑起来。")
                }
                if let dirs {
                    if dirs.isEmpty {
                        Section {
                            // ⚠️ 说清楚是「都开着了」还是「没找到」，别只给一句空
                            YxHint(taken.isEmpty
                                   ? "还没连上，或者那台机器上没有工作区目录 —— 下面直接填路径也行。"
                                   : "这些工作区目录都已经开着会话了 —— 下面直接填个新路径。")
                        }
                    } else {
                        Section("还没开会话的目录") {
                            ForEach(dirs, id: \.self) { d in
                                Button { onCreate(d) } label: {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(Paths.nameOf(d))
                                            .font(.system(size: 15)).foregroundStyle(Yx.onSurface)
                                        Text(d).font(.mono(11)).foregroundStyle(Yx.dim).lineLimit(1)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Section { YxHint("找目录中…") }
                }
                Section {
                    TextField("或者直接填：/opt/workspace/…", text: $path)
                        .font(.mono(14))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onSubmit { go() }
                    if !path.trimmingCharacters(in: .whitespaces).isEmpty {
                        // 会开成什么名字，当面说清 —— 免得开完在列表里找不到
                        YxHint("会话名：\(SessionProbe.sessionName(forDir: path.trimmingCharacters(in: .whitespaces)))")
                    }
                }
            }
            .navigationTitle("新会话开在哪个目录")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("开起来") { go() }
                        .disabled(path.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .task { await findDirs() }
    }

    /// 去服务器上问「工作区里还有哪些目录没开会话」。
    /// ⚠️ 从现有会话的 cwd 反推父目录 —— **不写死** `/root/src/workspace`，
    /// 换个客户、换台机器路径就不一样了。
    private func findDirs() async {
        guard let runner,
              let cmd = Dirs.listCommand(parents: Dirs.parents(of: taken)),
              let out = try? await runner.run(cmd)
        else { dirs = []; return }
        dirs = Dirs.candidates(out, taken: taken)
    }

    private func go() {
        let d = path.trimmingCharacters(in: .whitespaces)
        if !d.isEmpty { onCreate(d) }
    }
}
