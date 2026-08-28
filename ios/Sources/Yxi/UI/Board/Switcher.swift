import SwiftUI
import YxiKit

/// 悬浮排列的会话切换 —— 像手机后台，一张张卡片横着排（决策 D18 / D17，PRD 附录 J.3）。
///
/// ⚠️ **上滑 = 归档，不杀 tmux 会话。**
/// 安卓后台的上滑就是关闭应用，用户有肌肉记忆 —— 正因为如此才**更**不能让它杀会话：
/// 手滑一下就丢掉一个跑了两小时的构建。归档只是从这个列表里藏起来，
/// 服务器上那个会话一根毛都没动，随时能取消归档。真要杀：**长按 + 二次确认**。
///
/// ⚠️ **视差要尊重系统的「减弱动态效果」。** 对前庭功能障碍的用户视差会引发眩晕和恶心，
/// 这不是体贴，是无障碍要求。
///
/// ⚠️ **它和列表视图是并存的，不是替代。** 悬浮好看但同屏信息量少三分之一，
/// 20 个会话时还是列表能一眼扫完（D16b 里就写明了这个代价）。
@MainActor
struct Switcher: View {

    let service: (any SessionService)?
    let hostId: String
    /// 当前所在的会话名（从工作区打开时才有）；看板上打开是 nil
    let current: String?
    let onPick: (BoardSession) -> Void
    let onDismiss: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var all: [BoardSession] = []
    @State private var archived: Set<String> = []
    @State private var showArchived = false
    @State private var shots: [String: String] = [:]
    @State private var currentID: String?
    @State private var located = false
    @State private var killing: BoardSession?
    @State private var draggingID: String?
    @State private var dragY: CGFloat = 0

    private var list: [BoardSession] {
        showArchived ? all.filter { archived.contains($0.name) }
                     : all.filter { !archived.contains($0.name) }
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            if list.isEmpty {
                Spacer()
                Text(showArchived ? "没有归档的会话" : "没有会话")
                    .font(.system(size: 15)).foregroundStyle(Yx.dim)
                Spacer()
            } else {
                carousel
                Text(showArchived ? "上滑取消归档 · 长按结束会话" : "上滑归档（不杀会话）· 长按结束会话")
                    .font(.system(size: 11))
                    .foregroundStyle(Yx.dim)
                    .frame(maxWidth: .infinity)
                    .padding(.bottom, 20)
            }
        }
        // fullScreenCover 自己就在安全区里，不像安卓那个整屏浮层要手动吃系统栏边距
        .background(Yx.surface)
        .task {
            archived = Archive.get(hostId: hostId)
            await pollSessions()
        }
        .task(id: currentID) { await pollShots() }
        // ⚠️ **只定位一次。** 键里带会话列表的话，列表每 5 秒刷新一次就把用户拽回当前那张，
        // 表现是「怎么滑都滑不动」，极容易误判成手势失效（安卓上就先去查手势了，#61）。
        .onChange(of: list.count) { _, _ in locateOnce() }
        .confirmationDialog(
            "结束会话？",
            isPresented: Binding(get: { killing != nil }, set: { if !$0 { killing = nil } }),
            titleVisibility: .visible,
            presenting: killing
        ) { s in
            Button("结束 \(s.short)", role: .destructive) {
                let svc = service
                Task { try? await svc?.kill(session: s.name) }
                killing = nil
            }
            Button("取消", role: .cancel) { killing = nil }
        } message: { _ in
            // ⚠️ 不可逆的操作要把后果写清楚，不是「确定吗」这种没信息量的提示
            Text("会执行 tmux kill-session —— 那个会话里正在跑的东西会被中断，没保存的内容没了，取消不了。\n\n只是不想在列表里看见它的话，上滑归档就行，那个不动服务器。")
        }
    }

    // MARK: 顶栏

    private var header: some View {
        HStack(spacing: 8) {
            Text(showArchived ? "归档的会话" : "会话")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(Yx.onSurface)
            Spacer()
            if !archived.isEmpty {
                Button { showArchived.toggle() } label: {
                    Text("归档 \(archived.count)")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(showArchived ? Yx.copper : Yx.muted)
                        .padding(.horizontal, 13).padding(.vertical, 7)
                        .background(showArchived ? Yx.high : Yx.container, in: Capsule())
                }
                .buttonStyle(.plain)
            }
            Button(action: onDismiss) {
                Text("✕")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Yx.muted)
                    .padding(.horizontal, 15).padding(.vertical, 7)
                    .background(Yx.container, in: Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, Yx.pad).padding(.top, 14).padding(.bottom, 10)
    }

    // MARK: 卡片轮播

    /// ⚠️ 未验证：这一段用的全是 iOS 17 的滚动 API
    /// （`containerRelativeFrame` / `scrollTargetBehavior` / `scrollPosition` / `scrollTransition`）。
    /// 选它们是因为**只有这条路能同时拿到「左右邻居露一角」和「连续视差」**（D17 / 附录 J.2），
    /// 用 `TabView(.page)` 的话邻居完全看不见，那个「像手机后台一样排列」的隐喻就没了。
    private var carousel: some View {
        ScrollView(.horizontal) {
            LazyHStack(spacing: 12) {
                ForEach(list) { s in
                    card(s)
                        .containerRelativeFrame(.horizontal, count: 1, span: 1, spacing: 12)
                        // ⚠️ 先把 @Environment 的值**取到局部常量**再进闭包。
                        // `scrollTransition` 的闭包是 `Sendable` 的，直接引用主线程隔离的
                        // 属性会被 Swift 6 并发检查拦下（error: main actor-isolated property
                        // 'reduceMotion' can not be referenced from a Sendable closure）。
                        .scrollTransition { [motion = !reduceMotion] content, phase in
                            // 邻居缩小压暗，跟安卓那版同样的系数（0.86 / 0.55）
                            content
                                .scaleEffect(motion ? 1 - 0.14 * abs(phase.value) : 1)
                                .opacity(motion ? 1 - 0.45 * abs(phase.value) : 1)
                        }
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.viewAligned)
        .scrollPosition(id: $currentID)
        // 左右各留 44 让邻居露出来
        .contentMargins(.horizontal, 44, for: .scrollContent)
        .scrollIndicators(.hidden)
    }

    private func card(_ s: BoardSession) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Circle().fill(dotColor(s.state)).frame(width: 7, height: 7)
                Text(s.short)
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(Yx.onSurface)
                    .lineLimit(1)
                Spacer(minLength: 4)
                if s.name == current {
                    Text("当前").font(.system(size: 11)).foregroundStyle(Yx.copper)
                } else if showArchived {
                    Text("已归档").font(.system(size: 11)).foregroundStyle(Yx.dim)
                }
            }
            Text(s.detail.isEmpty ? s.cwd : s.detail)
                .font(.system(size: 11)).foregroundStyle(Yx.muted).lineLimit(1)

            // 实时屏幕缩略 —— 直接画 capture-pane 的文本。比截图便宜得多，
            // 而且**认得出是哪个会话**靠的本来就是文字内容。
            // ⚠️ 要**不折行、超出就裁掉**：折行之后每一行都错位，
            // 那就不再是「那个会话长什么样」了，认不出来。
            // 所以一行一个 Text + `lineLimit(1)` —— 比「整块 fixedSize 再 clip」稳，
            // 后者要跟 padding / 圆角背景的裁剪顺序较劲。
            VStack(alignment: .leading, spacing: 2) {
                let lines = shots[s.name].map { $0.split(separator: "\n").map(String.init) } ?? []
                ForEach(Array((lines.isEmpty ? ["…"] : lines).enumerated()), id: \.offset) { _, l in
                    Text(l).lineLimit(1).truncationMode(.tail)
                }
            }
            .font(.mono(8))
            .foregroundStyle(Yx.dim)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .padding(10)
            .background(Yx.lowest, in: RoundedRectangle(cornerRadius: Yx.blockRadius, style: .continuous))
            // 内容比卡片慢一拍 → 纵深感（D17 的第三层）
            .scrollTransition { [motion = !reduceMotion] content, phase in
                content.offset(y: motion ? phase.value * -28 : 0)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(
            s.name == current ? Yx.high : Yx.low,
            in: RoundedRectangle(cornerRadius: Yx.cardRadius, style: .continuous)
        )
        .padding(.vertical, 12)
        .offset(y: draggingID == s.name ? dragY : 0)
        .contentShape(Rectangle())
        .onTapGesture { onPick(s); onDismiss() }
        // ⚠️ **必须 `simultaneousGesture` + 自己判方向。**
        // 独占的竖向拖拽会把横滑也吃掉 —— 卡片铺满整页，于是整个轮播都滑不动，
        // 而且不报任何错（安卓上是 `detectVerticalDragGestures` 干的，TROUBLESHOOTING #60）。
        // 「同时识别 + 只认竖向」就是那边 `draggable(orientation:)` 的方向锁。
        // ⚠️ 未验证：这条竖向拖拽、横向的分页滚动、还有下面 contextMenu 的长按，
        // 三个手势要在同一块视图上和平共处。**上真机第一件事就是试这个** ——
        // 坏起来的样子是「横滑不动」，而且不报任何错（安卓那次就是这么坏的）。
        .simultaneousGesture(
            DragGesture(minimumDistance: 20)
                .onChanged { v in
                    guard abs(v.translation.height) > abs(v.translation.width) else { return }
                    draggingID = s.name
                    dragY = min(v.translation.height, 0)   // 只准往上
                }
                .onEnded { v in
                    let vertical = abs(v.translation.height) > abs(v.translation.width)
                    // 拖够一段才算 —— 轻轻蹭一下不该把卡片弄没了
                    if vertical && v.translation.height < -120 { toggleArchive(s) }
                    draggingID = nil
                    dragY = 0
                }
        )
        .contextMenu {
            Button(archived.contains(s.name) ? "取消归档" : "归档（不动服务器）") { toggleArchive(s) }
            Button("结束会话", role: .destructive) { killing = s }
        }
    }

    // MARK: 数据

    private func pollSessions() async {
        guard let svc = service else { return }
        while true {
            if let got = try? await svc.snapshot() { all = got; locateOnce() }
            do { try await Task.sleep(nanoseconds: 5_000_000_000) } catch { return }
        }
    }

    /// ⚠️ **只抓当前页和左右邻居的缩略图。** 全抓的话 20 个会话就是 20 次往返，
    /// 每 3 秒一轮 —— 手机网络下这是自找的卡顿。
    private func pollShots() async {
        guard let svc = service else { return }
        while true {
            let center = list.firstIndex { $0.id == currentID } ?? 0
            for i in (center - 1)...(center + 1) {
                guard i >= 0, i < list.count else { continue }
                let name = list[i].name
                if let raw = try? await svc.peek(session: name, lines: 14) {
                    shots[name] = raw
                        .split(separator: "\n", omittingEmptySubsequences: false)
                        .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
                        .suffix(9)
                        .joined(separator: "\n")
                }
            }
            do { try await Task.sleep(nanoseconds: 3_000_000_000) } catch { return }
        }
    }

    private func locateOnce() {
        guard !located, !list.isEmpty else { return }
        located = true
        if let c = current, list.contains(where: { $0.name == c }) { currentID = c }
        else { currentID = list.first?.id }
    }

    private func toggleArchive(_ s: BoardSession) {
        if archived.contains(s.name) { archived.remove(s.name) } else { archived.insert(s.name) }
        Archive.set(hostId: hostId, archived)
    }
}

/// 归档只是本地的一个名单 —— **服务器上什么都没动**。
enum Archive {
    static func get(hostId: String) -> Set<String> {
        Set(UserDefaults.standard.stringArray(forKey: "archived:\(hostId)") ?? [])
    }
    static func set(hostId: String, _ v: Set<String>) {
        UserDefaults.standard.set(Array(v), forKey: "archived:\(hostId)")
    }
}
