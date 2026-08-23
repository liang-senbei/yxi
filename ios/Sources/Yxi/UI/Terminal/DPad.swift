import SwiftUI

/// D-Pad 能送的键。名字是给用户看的，[bytes] 是真正打进 PTY 的。
enum TermKey: String, Identifiable {
    case up = "↑", down = "↓", left = "←", right = "→"
    case enter = "⏎", esc = "esc", tab = "tab", shiftTab = "⇧tab"
    case ctrlC = "^C", ctrlD = "^D", ctrlZ = "^Z", space = "␣"

    var id: String { rawValue }
    var label: String { rawValue }

    var bytes: [UInt8] {
        switch self {
        case .up:       return [27, 91, 65]
        case .down:     return [27, 91, 66]
        case .right:    return [27, 91, 67]
        case .left:     return [27, 91, 68]
        case .enter:    return [13]
        case .esc:      return [27]
        case .tab:      return [9]
        case .shiftTab: return [27, 91, 90]
        case .ctrlC:    return [3]
        case .ctrlD:    return [4]
        case .ctrlZ:    return [26]
        case .space:    return [32]
        }
    }

    /// 两个角能配的键。方向键不在里面 —— 它们在盘上。
    static let corner: [TermKey] = [.esc, .tab, .shiftTab, .ctrlC, .ctrlD, .ctrlZ, .space, .enter]
}

/// 方向盘：**四向 + 中央 Enter**，左右上角两个可配置键（长按换）。
///
/// ⚠️ **它是一个手势，不是五个按钮。** 按下去按方位判方向、**压住不放持续走**、
/// 手指推向别的方向就跟着换 —— 在 Claude Code 的选择器里连按七八下是常事，
/// 五个独立按钮意味着抬手落手七八次，很难受。
struct DPad: View {
    let send: ([UInt8]) -> Void

    @State private var topLeft: TermKey = .esc
    @State private var topRight: TermKey = .tab
    /// true = 在配左角，false = 右角，nil = 没在配
    @State private var picking: Bool?
    /// 当前压着的方向，给绘制用
    @State private var held: TermKey?
    @State private var repeater: Task<Void, Never>?

    private let size: CGFloat = 184

    var body: some View {
        VStack(spacing: 6) {
            HStack {
                CornerKey(topLeft, tap: { send(topLeft.bytes) }, long: { picking = true })
                Spacer()
                CornerKey(topRight, tap: { send(topRight.bytes) }, long: { picking = false })
            }
            .frame(width: size)

            wheel
        }
        .confirmationDialog(
            picking == true ? "左上角送什么键" : "右上角送什么键",
            isPresented: Binding(get: { picking != nil }, set: { if !$0 { picking = nil } })
        ) {
            ForEach(TermKey.corner) { k in
                Button(k.label) {
                    if picking == true { topLeft = k } else { topRight = k }
                    picking = nil
                }
            }
            Button("取消", role: .cancel) { picking = nil }
        }
    }

    private var wheel: some View {
        ZStack {
            Circle().fill(Yx.low.opacity(0.92))
            Circle().strokeBorder(Yx.high, lineWidth: 1.5)
            Circle().strokeBorder(Yx.high, lineWidth: 1.5)
                .frame(width: deadZone * 2, height: deadZone * 2)

            ForEach([TermKey.up, .down, .left, .right, .enter]) { k in
                ZStack(alignment: Self.place(k)) {
                    Color.clear
                    Text(k.label)
                        .font(.system(size: 17, weight: .medium))
                        // 压着的那个亮起来 —— 推着走的时候要看得见现在在往哪边走
                        .foregroundStyle(held == k ? Yx.copper : Yx.muted)
                        .padding(14)
                }
            }
        }
        .frame(width: size, height: size)
        .contentShape(Circle())
        // ⚠️ `minimumDistance: 0` 是关键：手指一落下 onChanged 就来，
        // 我们**在这个回调里直接发第一下**。
        //
        // Android 上把「按下发一次」写进了 `LaunchedEffect(held)`，结果**快点没反应** ——
        // 按下和抬起落在同一帧时 held 已经变回 nil，effect 还没轮到跑第一次 send。
        // 长按能用、快点不灵，用起来就像「D-Pad 有时候坏」，最难查（TROUBLESHOOTING #37）。
        // **「立即」的事情不要交给状态变化去触发。**
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { g in
                    let d = direction(of: g.location)
                    guard d != held else { return }
                    held = d
                    press(d)      // 第一下 + 换方向都在这儿立刻发
                }
                .onEnded { _ in
                    held = nil
                    repeater?.cancel()
                    repeater = nil
                }
        )
    }

    private var deadZone: CGFloat { size * 0.18 }

    /// 盘上五个字放哪儿。⚠️ 拆成函数是因为 Swift 没有元组的 key path，
    /// `ForEach([(键, 位置)], id: \.0)` 编译不过。
    private static func place(_ k: TermKey) -> Alignment {
        switch k {
        case .up: return .top
        case .down: return .bottom
        case .left: return .leading
        case .right: return .trailing
        default: return .center
        }
    }

    /// 落点方位 → 方向键。圆心那一小圈算 Enter。
    private func direction(of p: CGPoint) -> TermKey {
        let v = CGPoint(x: p.x - size / 2, y: p.y - size / 2)
        if abs(v.x) < deadZone && abs(v.y) < deadZone { return .enter }
        if abs(v.x) > abs(v.y) { return v.x > 0 ? .right : .left }
        return v.y > 0 ? .down : .up
    }

    /// 立刻走一格，然后起连发。换方向时同样重新计时。
    private func press(_ k: TermKey) {
        send(k.bytes)
        repeater?.cancel()
        repeater = Task {
            // 先等 400ms —— 点一下不该变成走两格
            try? await Task.sleep(nanoseconds: 400_000_000)
            while !Task.isCancelled {
                send(k.bytes)
                // ⚠️ `try?` 吞掉取消之后 sleep 会立刻返回 —— 不再查一次就是死循环
                try? await Task.sleep(nanoseconds: 110_000_000)
                if Task.isCancelled { return }
            }
        }
    }
}

private struct CornerKey: View {
    let key: TermKey
    let tap: () -> Void
    let long: () -> Void
    init(_ key: TermKey, tap: @escaping () -> Void, long: @escaping () -> Void) {
        self.key = key; self.tap = tap; self.long = long
    }
    var body: some View {
        Text(key.label)
            .font(.mono(12))
            .foregroundStyle(Yx.muted)
            .frame(width: 48, height: 48)
            .background(Yx.high, in: Circle())
            .contentShape(Circle())
            .onTapGesture(perform: tap)
            .onLongPressGesture(perform: long)
    }
}
