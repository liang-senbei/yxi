import SwiftUI
import PhotosUI
import YxiKit
import MarkdownUI
import UniformTypeIdentifiers

/// 对话渲染模式 —— **这是主界面**（PRD 附录 D），不是附加功能。
///
/// 数据源是 `~/.claude/projects` 下的转录 JSONL，**不刮终端屏幕**。
/// 发消息走 `tmux send-keys` 打进那个活着的会话，
/// 所以 Claude Code 的配置、权限、MCP、skills 原样生效 —— 我们不重新实现 agent 协议。
///
/// ⚠️ `backend` 由 Workspace 持有并传进来（这里只在 init 里交给 [ChatModel]）。
/// 切模式时这个视图会销毁重建，而 SSH 连接不能跟着断（TROUBLESHOOTING #75）。
struct ChatScreen: View {
    let session: String
    let cwd: String

    @StateObject private var model: ChatModel
    /// 真的到底了吗。**只用来决定 ↓ 按钮出不出现、新消息要不要跟着滚**
    @State private var atBottom = true
    /// 📎 点开的那个二选一。iOS 上照片和文件是**两个**选择器（相册不在 Files 里），
    /// 不像 Android 一个 `GetContent("*/*")` 通吃
    @State private var attaching = false
    @State private var picking = false
    @State private var photo: PhotosPickerItem?
    @State private var importing = false
    @State private var showModes = false

    private let bottomID = "yxi.chat.bottom"
    private let space = "yxi.chat.space"

    init(backend: ChatBackend, session: String, cwd: String) {
        self.session = session
        self.cwd = cwd
        _model = StateObject(wrappedValue: ChatModel(backend: backend))
    }

    var body: some View {
        VStack(spacing: 0) {
            if let s = model.status {
                Text(s)
                    .font(.system(size: 12.5))
                    .foregroundStyle(Yx.dim)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 18).padding(.vertical, 8)
            }

            // Claude 此刻在做计划里的哪一步 —— 最近一次 TodoWrite 里 in_progress 那条。
            // 一眼看清进度，不用展开卡片。
            if let doing = Todos.doingNow(model.items.compactMap {
                if case let .tool(c) = $0 { return c } else { return nil }
            }) {
                Text("▶ 正在做 · \(doing)")
                    .font(.system(size: 12))
                    .foregroundStyle(Yx.copper)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 18).padding(.bottom, 2)
            }

            transcript

            if model.live.busy { busyRow }

            pendingRow

            if !model.staged.isEmpty || model.uploading { stagedRow }

            // 常用语：没打字时才露出来，点一下填进草稿 —— 手机打字是回复的瓶颈
            if model.draft.isEmpty { snippetRow }

            inputRow
        }
        .background(Yx.surface)
        // 会话或 cwd 一变就整条重来。`.task(id:)` 的取消是结构化的 ——
        // 视图一消失，tail / 节流 / 抓屏三条循环全停，不用自己记 Task
        // ⚠️ 会话/cwd 是**参数**不是 init 存的：换会话时 ChatScreen 原地复用，
        // `@StateObject` 不会重建，init 里存死就会拿着旧会话去 send-keys
        .task(id: session + "\u{1}" + cwd) { await model.run(session: session, cwd: cwd) }
        .photosPicker(isPresented: $picking, selection: $photo, matching: .images)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item]) { result in
            guard case let .success(url) = result else { return }
            // ⚠️ 文件选择器给的是**沙箱外**的 URL，不开安全作用域读不到（会静默拿到空数据）
            let ok = url.startAccessingSecurityScopedResource()
            defer { if ok { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else { return }
            model.attach(fileName: url.lastPathComponent, data: data, isImage: false)
        }
        .onChange(of: photo) { item in
            guard let item else { return }
            Task {
                guard let data = try? await item.loadTransferable(type: Data.self) else { return }
                model.attach(fileName: "image.png", data: data, isImage: true)
                photo = nil
            }
        }
    }

    // MARK: - 转录列表

    private var transcript: some View {
        GeometryReader { outer in
            let viewport = outer.size.height
            ScrollViewReader { proxy in
                ZStack(alignment: .bottomTrailing) {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 18) {
                            ForEach(model.items) { ItemView(item: $0) }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.top, 6)
                        .padding(.bottom, 16)

                        // 末尾哨兵。**两个用途**：
                        //   ① 滚动目标 —— `anchor: .bottom` 把它对齐视口底边，那才是真的到底
                        //   ② 到底检测 —— 它离视口底边还有多远
                        //
                        // ⚠️ 为什么不用「最后一条可见」判到底：那条可能**比一屏还长**
                        // （长回复很常见），它可见不等于你看到了它的结尾（#80①）。
                        // 也不要自己拿 contentOffset/contentSize 算 —— 要跟内边距较劲，
                        // 差几个像素就永远判不到底，**↓ 按钮赖着不走**（#80②）。
                        // 哨兵在内容的最末尾、下面没有任何 padding，所以它的底边就是内容的底边。
                        Color.clear.frame(height: 1).id(bottomID)
                            .background(GeometryReader { g in
                                Color.clear.preference(
                                    key: BottomEdge.self,
                                    value: g.frame(in: .named(space)).maxY)
                            })
                    }
                    .coordinateSpace(name: space)
                    // ⚠️ 未验证：Swift 6 严格并发下 onPreferenceChange 的闭包是 @Sendable，
                    // 可能需要 MainActor.assumeIsolated 才能改 @State。真机编译时若报错，
                    // 包一层即可，逻辑不用动。
                    .onPreferenceChange(BottomEdge.self) { y in
                        // 只在**翻面**时才写 state —— 每滚一帧都写会把整个 ChatScreen 重画一遍
                        let now = y <= viewport + 12
                        if now != atBottom { atBottom = now }
                    }
                    // ⚠️ 用的是 iOS 16 那个单参数版本（17 起标了 deprecated）。
                    // 换成新签名会把最低版本顶到 17，不值得为一个警告付这个代价。
                    .onChange(of: model.items.count) { _ in reposition(proxy) }
                    .onChange(of: model.settled) { done in
                        // ⚠️ **灌完那一刻无条件再定位一次。**
                        // `tail -n 800` 是分批灌的，最后一次定位发生在「布局还在变」的时候，
                        // 滚到一半列表又长高了 —— 结果永远差最后一屏（#80④）。
                        // 这一次布局是稳的。settled 只会翻一次面，不会打扰在翻旧消息的用户。
                        if done { jump(proxy) }
                    }

                    // ⚠️ **只在没在底部时才出现。** 一直挂着的话它就是块永久的遮挡 ——
                    // 而绝大多数时候你本来就在底部（新消息自动跟着走），那时它毫无用处。
                    if !atBottom && !model.items.isEmpty {
                        Button {
                            // 瞬移不做动画：几百条的列表上动画要滚好几秒，
                            // 而这个按钮的意思就是「**立刻**到底」
                            jump(proxy)
                        } label: {
                            Text("↓").font(.system(size: 17, weight: .medium))
                                .foregroundStyle(Yx.onCopperBox)
                                .frame(width: 44, height: 44)
                                .background(Yx.copperBox, in: Circle())
                                .shadow(radius: 4, y: 2)
                        }
                        .buttonStyle(.plain)
                        .padding(16)
                        .transition(.opacity)
                    }
                }
                .animation(.easeInOut(duration: 0.15), value: atBottom)
            }
        }
    }

    private func jump(_ proxy: ScrollViewProxy) {
        Task { @MainActor in proxy.scrollTo(bottomID, anchor: .bottom) }
    }

    private func reposition(_ proxy: ScrollViewProxy) {
        guard !model.items.isEmpty else { return }
        // ⚠️ **等一帧再定位。** 数据刚变、布局还没重新量，这一刻算出来的位置是上一帧的
        // （Android 上同一个坑：`canScrollForward` 读到旧 layoutInfo，#80③）。
        // `Task { @MainActor }` 让出一次 runloop，回来时这批变更已经布局完了。
        Task { @MainActor in
            if !model.settled {
                // 还在灌历史：**瞬移，不做动画**。用户看到的是「一进来就在最新的地方」，
                // 而不是眼睁睁看它滚。首屏定位要的是结果，不是过程（#73）。
                proxy.scrollTo(bottomID, anchor: .bottom)
            } else if atBottom {
                // ⚠️ 只有本来就在底部才跟着走。用户往上翻着看旧消息时，
                // 新消息把他拽回底部比不滚更烦（#61）
                withAnimation(.easeOut(duration: 0.25)) {
                    proxy.scrollTo(bottomID, anchor: .bottom)
                }
            }
        }
    }

    // MARK: - 附件与输入

    private var stagedRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(model.staged) { a in
                    Button { model.drop(a) } label: {
                        Text(a.label + "  ✕")
                            .font(.system(size: 12.5))
                            .foregroundStyle(Yx.onSurfaceVar)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(Yx.high, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
                if model.uploading {
                    Text("传着…").font(.system(size: 12.5)).foregroundStyle(Yx.dim).padding(8)
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 6)
        }
    }

    private var inputRow: some View {
        HStack(spacing: 10) {
            // ⚠️ **没有麦克风按钮，这是故意的。**
            // iOS 没有 Android `RecognizerIntent` 那种「叫一个系统识别界面回来」的东西；
            // 自己接 Speech.framework 要麦克风+语音两个权限、还要往 Info.plist 加两条 ——
            // 而 Info.plist 不归 UI 这层改。
            // 系统键盘自带的听写麦克风在任何输入框里都能用，**而且识别结果落在输入框里、
            // 要你自己按发送** —— 恰好就是 PRD 附录 E.2 要求的「先显示、确认才发」。
            // 零代码、零权限、零风险。真要做服务器端 whisper（E.3）再说。
            Button { attaching = true } label: {
                Text("📎").font(.system(size: 17))
                    .frame(width: 46, height: 46)
                    .background(Yx.container, in: Circle())
            }
            .buttonStyle(.plain)
            .confirmationDialog("加点什么", isPresented: $attaching) {
                Button("照片") { picking = true }
                Button("文件") { importing = true }
                Button("取消", role: .cancel) {}
            }

            TextField("说一句…", text: $model.draft, axis: .vertical)
                .lineLimit(1...6)
                .font(.system(size: 15))
                .foregroundStyle(Yx.onSurface)
                .tint(Yx.copper)
                .padding(.horizontal, 20).padding(.vertical, 15)
                .background(Yx.container, in: RoundedRectangle(cornerRadius: 26, style: .continuous))

            Button { showModes = true } label: {
                Text("⚡").font(.system(size: 17))
                    .frame(width: 46, height: 46)
                    .background(Yx.container, in: Circle())
            }
            .buttonStyle(.plain)

            Button { model.send() } label: {
                Text("↑").font(.system(size: 17, weight: .medium))
                    .foregroundStyle(model.canSend ? Yx.onCopper : Yx.dim)
                    .frame(width: 52, height: 52)
                    .background(model.canSend ? Yx.copper : Yx.container, in: Circle())
            }
            .buttonStyle(.plain)
            .disabled(!model.canSend)
        }
        .padding(.horizontal, 14).padding(.top, 6).padding(.bottom, 12)
        .sheet(isPresented: $showModes) {
            ModeSheet { cmd in model.sendMode(cmd) }
        }
    }

    /// 忙的时候那条状态 + 一键「停」。
    ///
    /// ⚠️ **单独抽出来不是为了好看** —— 全塞在 `body` 里会让 SwiftUI 的类型检查器
    /// 直接放弃（CI 报 `failed to produce diagnostic for expression`）。
    /// 这类「一个 body 里堆七八个条件分支」的写法在 SwiftUI 里必须拆。
    private var busyRow: some View {
        HStack(spacing: 10) {
            LiveStatusRow(status: model.live.status)
            Spacer(minLength: 0)
            Button { model.interrupt() } label: {
                Text("■ 停").font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Yx.error)
                    .padding(.horizontal, 12).padding(.vertical, 5)
                    .background(Yx.errorBox, in: Capsule())
            }
            .buttonStyle(.plain)
            .padding(.trailing, 16)
        }
    }

    @ViewBuilder
    private var pendingRow: some View {
        if let p = model.pending {
            // ⚠️ **必须写明类型。** 直接写 `p.tabs.count > 1 ? model.goPrevQuestion : nil`
            // 让类型检查器去推 `(() -> Void)?`，它会直接放弃
            // （CI 报 failed to produce diagnostic for expression）。
            let multi = p.tabs.count > 1
            let prev: (() -> Void)? = multi ? { model.goPrevQuestion() } : nil
            let next: (() -> Void)? = multi ? { model.goNextQuestion() } : nil
            PendingCard(pending: p, busy: model.answering,
                        onPick: model.pick, onSubmit: model.submitMultiSelect,
                        onPrev: prev, onNext: next)
                .padding(.horizontal, 14).padding(.bottom, 8)
        }
    }

    /// 常用语 —— 你自己的短语库，跟斜杠命令菜单不是一回事（那是 Claude 的命令，这是你的话）。
    private var snippetRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Snippets.defaults, id: \.self) { s in
                    Button { model.draft = s } label: {
                        Text(s).font(.system(size: 13))
                            .padding(.horizontal, 14).padding(.vertical, 8)
                            .background(Yx.high, in: Capsule())
                            .foregroundStyle(Yx.onSurface)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 14)
        }
        .padding(.bottom, 4)
    }
}

/// **模式快切** —— 点一下把对应的斜杠命令发进会话。
/// 这些模式是**各自独立的斜杠命令**，所以**能叠加**：连点几个就都生效（1M + 最大思考）。
private struct ModeSheet: View {
    let onPick: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(Modes.defaults) { m in
                        Button { onPick(m.command) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(m.label).font(.system(size: 15, weight: .medium))
                                    .foregroundStyle(Yx.onSurface)
                                Text(m.command).font(.mono(11)).foregroundStyle(Yx.dim)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                } footer: {
                    Text("点一下就发进会话；能叠加的连着点，比如先 1M 再 最大思考。")
                }
            }
            .navigationTitle("切模式")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
        }
        .presentationDetents([.medium])
    }
}

/// 哨兵离视口顶边有多远。滚到底时它等于视口高度。
private struct BottomEdge: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

// MARK: - 一条内容

private struct ItemView: View {
    let item: ChatItem

    var body: some View {
        switch item {
        case let .user(_, text):
            UserBubble(text: text)
        case let .queued(_, text):
            QueuedBubble(text: text)
        case let .assistant(_, md):
            // ⚠️ `.markdownTheme(.yxi)` 一定要传 —— 默认主题的 h1 是正文的两倍，
            // 聊天气泡里一个 `##` 就占掉半屏。见 [Theme.yxi]
            Markdown(md)
                .markdownTheme(.yxi)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        case let .thinking(_, text):
            ThinkingRow(text: text)
        case let .tool(call):
            ToolCardView(call: call)
        case .unknown:
            UnknownRow()
        }
    }
}

private struct UserBubble: View {
    let text: String
    var body: some View {
        HStack {
            Spacer(minLength: 40)
            Text(text)
                .font(.system(size: 15))
                .foregroundStyle(Yx.onCopperBox)
                .textSelection(.enabled)
                .padding(.horizontal, 18).padding(.vertical, 14)
                .background(Yx.copperBox, in: BubbleShape())
        }
    }
}

/// 排队中的输入 —— 已经送到那台机器上了，但 Claude 还在忙，还没轮到它。
///
/// ⚠️ 长得像用户气泡但**必须一眼看出不一样**（半透明 + 虚线边 + 「排队中」）。
/// 做成一模一样的话，用户以为已经在处理了；一点不显示的话，
/// 用户以为压根没发出去，然后重复发一遍 —— 后者在 Android 上真发生过（#72）。
///
/// ⚠️ 虚线边是全 app 唯一的描边（其余一律靠面的明度分层）。就是要它显得**没落定**。
private struct QueuedBubble: View {
    let text: String
    var body: some View {
        HStack {
            Spacer(minLength: 40)
            VStack(alignment: .leading, spacing: 4) {
                Text("排队中 · 它忙完就轮到这条")
                    .font(.system(size: 11.5))
                    .foregroundStyle(Yx.copper)
                Text(text)
                    .font(.system(size: 15))
                    .foregroundStyle(Yx.onSurface.opacity(0.75))
            }
            .padding(.horizontal, 18).padding(.vertical, 12)
            .background(Yx.copperBox.opacity(0.30), in: BubbleShape())
            .overlay(
                BubbleShape().strokeBorder(
                    Yx.copper.opacity(0.45),
                    style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
            )
        }
    }
}

/// 用户气泡的形状：右下角收一个小角，指向自己。
private struct BubbleShape: InsettableShape {
    var inset: CGFloat = 0
    func path(in rect: CGRect) -> Path {
        Path(roundedRect: rect.insetBy(dx: inset, dy: inset),
             cornerSize: CGSize(width: 22, height: 22), style: .continuous)
    }
    func inset(by amount: CGFloat) -> Self { var c = self; c.inset += amount; return c }
}

/// 思考。
///
/// ⚠️ **当前版本的 Claude Code 根本不落 thinking 正文，所以这里基本不会被走到。**
/// 2026-08-23 实测（本机 `~/.claude/projects/` 全部转录）：
/// **4329 个 thinking block，正文非空的 0 个** —— 只剩加密的 `signature`。
///
/// 所以**没有做展开/折叠**。PRD 附录 D.2 当初要折叠，是因为一个会话 128 条、
/// 全展开会把正文淹掉；那个担心建立在「有正文」上，而正文并不存在。
/// **不为不存在的内容做交互设计。** 哪天 Claude Code 又开始写正文了，
/// 再把折叠加回来（那时才知道该折叠成什么样）。
///
/// 分支保留 + 空正文不渲染：解析器那边已经过滤了，这里再兜一次，别冒空气泡。
private struct ThinkingRow: View {
    let text: String
    var body: some View {
        if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            Text(text)
                .font(.system(size: 14))
                .foregroundStyle(Yx.dim)
                .textSelection(.enabled)
                .padding(.leading, 4)
        }
    }
}

/// 解析不出来的 content block。
///
/// ⚠️ **必须看得见，不许静默丢掉。** 安卓上「排队的消息一条都不显示」（#72）
/// 就是这么翻的车：解析器遇到不认识的类型直接 return，界面上什么都没有，
/// 用户以为消息没发出去、于是重复发。**没显示出来的东西没人会去查。**
/// 一行灰字很丑，但丑是对的 —— 它在催人把这个块提升成强类型。
private struct UnknownRow: View {
    var body: some View {
        Text("未识别的块（这条没渲染出来，请报一下）")
            .font(.system(size: 12.5))
            .foregroundStyle(Yx.dim)
            .padding(.horizontal, 12).padding(.vertical, 7)
            .background(Yx.low, in: Capsule())
    }
}

/// 「它正在忙」那一条。
///
/// 文案直接用 Claude Code 自己的状态词（Scampering… / Crafting… / Searching…），
/// **不翻译也不归一** —— 那些词是它自己在屏幕上说的话，
/// 换成「处理中…」反而丢了信息（词本身 + 耗时 + token 数都在里面）。
private struct LiveStatusRow: View {
    let status: String?
    @State private var pulse = false

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(Yx.teal.opacity(pulse ? 1 : 0.35)).frame(width: 6, height: 6)
            Text(status ?? "在忙…")
                // ⚠️ **它显得大不是因为字号大**（Android 上量过：12sp，比正文还小），
                // 是视觉重量：加粗 + 强调色 + 独占一行。所以这里压的是**字重和颜色**，
                // 不是一味调小 —— 它还得看得见。
                // 单行截断：状态里带 token 数，长的会折成两行，那时候才是真的一大块。
                .font(.system(size: 12, weight: .regular))
                .foregroundStyle(Yx.teal.opacity(0.8))
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20).padding(.top, 2).padding(.bottom, 6)
        .onAppear {
            withAnimation(.easeInOut(duration: 0.75).repeatForever(autoreverses: true)) {
                pulse = true
            }
        }
    }
}
