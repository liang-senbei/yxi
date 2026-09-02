import SwiftUI

/// 对话页的背景光 —— **跟安卓 `ThinkingGlow.kt` 同一套参数**（帧表见 TROUBLESHOOTING #191）。
///
/// 三种状态、两次迁移：
///  · **待机 / 打字**：一团很淡的蓝光挂在**底部**、输入框上方（输入区的聚光）。
///  · **发送 → 思考**：那团光脱离底部往上迁，650ms 铺满顶部，然后色相循环
///    蓝→青→绿→黄→橙→粉，6 秒一圈。
///  · **回答到了**：光退到三四成，让位给正文。
///
/// ⚠️ **等你拍板那一档不循环，定在琥珀** —— 它是这个 App 里最要紧的信号，抄观感不抄掉信息层。
/// ⚠️ 四团光各自独立的相位、周期互不成整数倍（#186），不然是一块色板在平移。
/// ⚠️ 系统开了「减弱动态效果」：不迁移、不循环、不流动。
///
/// 实现上**所有插值都从时间算**（`TimelineView`），不走 SwiftUI 的属性动画 ——
/// `Canvas` 闭包里读 `@State` 是拿不到中间帧的。
struct ThinkingGlow: View {
    let busy: Bool
    let waiting: Bool
    /// 回答正在到达（最后一条是助手的、且还在忙）—— 光该退下去让位给正文
    let streaming: Bool

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// 上一次状态切换：从哪个值、在什么时候、要去哪。
    @State private var lift = Tween(from: 0, to: 0, at: 0, ms: 650)
    @State private var amount = Tween(from: 0.55, to: 0.55, at: 0, ms: 900)

    private var active: Bool { busy || waiting }
    private var targetAmount: Double {
        if waiting { return 1 }
        if busy && streaming { return 0.35 }
        if busy { return 1 }
        return 0.55
    }

    var body: some View {
        TimelineView(.animation(paused: reduceMotion && !lift.running && !amount.running)) { tl in
            let t = tl.date.timeIntervalSinceReferenceDate
            Canvas { ctx, size in draw(&ctx, size: size, t: t) }
        }
        .allowsHitTesting(false)
        .ignoresSafeArea()
        .onChange(of: active, initial: true) { _, on in
            let now = Date().timeIntervalSinceReferenceDate
            lift = Tween(from: lift.value(at: now), to: on ? 1 : 0, at: now, ms: reduceMotion ? 0 : 650)
        }
        .onChange(of: targetAmount, initial: true) { _, v in
            let now = Date().timeIntervalSinceReferenceDate
            amount = Tween(from: amount.value(at: now), to: v, at: now, ms: active ? 900 : 1400)
        }
    }

    private func draw(_ ctx: inout GraphicsContext, size: CGSize, t: TimeInterval) {
        let w = size.width, h = size.height
        let lf = lift.value(at: t)
        let am = amount.value(at: t)
        let motion = !reduceMotion
        // 四个三角波，周期互不成整数倍
        func wave(_ ms: Double) -> Double {
            guard motion else { return 0.5 }
            let p = (t * 1000).truncatingRemainder(dividingBy: 2 * ms) / ms
            return p <= 1 ? p : 2 - p
        }
        let pa = wave(7300), pb = wave(9100), pc = wave(11700), pd = wave(13900)
        let hueT = t.truncatingRemainder(dividingBy: 6) / 6

        let hues: [Color]
        if waiting {
            hues = [Color(hex: 0xFFC46B), Color(hex: 0xFFAF9B), Color(hex: 0xFFE0A3), Color(hex: 0xFFD1B0)]
        } else if busy && motion {
            // 蓝(210)→青→绿→黄→橙→粉：色相**递减**着走 300°。方向别弄反（#191）
            let base = 210 - 300 * hueT
            hues = (0..<4).map { pastel((base + Double($0) * 22).truncatingRemainder(dividingBy: 360)) }
        } else if busy {
            hues = [Color(hex: 0x8FD8C6), Color(hex: 0x9EC8F0), Color(hex: 0xC9E6D8), Color(hex: 0xA8D8E8)]
        } else {
            hues = [Color(hex: 0x9EC8F0), Color(hex: 0xB4D6F5), Color(hex: 0xC9E0F7), Color(hex: 0xA8D8E8)]
        }

        // 顶部布局（思考）和底部布局（待机）各一套团心，按 lift 插值
        func y(_ top: Double, _ bottom: Double) -> Double { h * (bottom + (top - bottom) * lf) }
        func r(_ top: Double, _ bottom: Double) -> Double { w * (bottom + (top - bottom) * lf) }
        let blobs: [(CGPoint, Double, Double)] = [
            (CGPoint(x: w * (0.10 + 0.55 * pa), y: y(0.04 + 0.10 * pc, 0.98)), r(1.05 + 0.12 * pb, 0.70), 0.28),
            (CGPoint(x: w * (0.95 - 0.60 * pb), y: y(0.16 + 0.14 * pd, 1.02)), r(0.90 + 0.12 * pc, 0.55), 0.20),
            (CGPoint(x: w * (0.30 + 0.50 * pc), y: y(0.30 + 0.12 * pa, 1.00)), r(0.78 + 0.14 * pd, 0.45), 0.15),
            (CGPoint(x: w * (0.70 - 0.55 * pd), y: y(0.10 + 0.16 * pb, 1.04)), r(0.85 + 0.10 * pa, 0.50), 0.13),
        ]
        let rect = Path(CGRect(origin: .zero, size: size))
        for (i, b) in blobs.enumerated() {
            let (c, radius, alpha) = b
            ctx.fill(rect, with: .radialGradient(
                Gradient(colors: [hues[i].opacity(alpha * am), .clear]),
                center: c, startRadius: 0, endRadius: radius))
        }
    }

    /// HSL → 粉彩色。饱和 0.62、亮度 0.80：「有颜色但不脏」的档位。
    private func pastel(_ hue: Double) -> Color {
        let hue = hue < 0 ? hue + 360 : hue
        let s = 0.62, l = 0.80
        let c = (1 - abs(2 * l - 1)) * s
        let x = c * (1 - abs((hue / 60).truncatingRemainder(dividingBy: 2) - 1))
        let m = l - c / 2
        let (r1, g1, b1): (Double, Double, Double)
        switch hue {
        case ..<60: (r1, g1, b1) = (c, x, 0)
        case ..<120: (r1, g1, b1) = (x, c, 0)
        case ..<180: (r1, g1, b1) = (0, c, x)
        case ..<240: (r1, g1, b1) = (0, x, c)
        case ..<300: (r1, g1, b1) = (x, 0, c)
        default: (r1, g1, b1) = (c, 0, x)
        }
        return Color(.sRGB, red: r1 + m, green: g1 + m, blue: b1 + m, opacity: 1)
    }
}

/// 一段从时间算的过渡：`from → to`，从 `at` 起走 `ms` 毫秒，ease-in-out。
private struct Tween {
    let from: Double, to: Double, at: TimeInterval, ms: Double
    var running: Bool { Date().timeIntervalSinceReferenceDate - at < ms / 1000 }
    func value(at t: TimeInterval) -> Double {
        guard ms > 0 else { return to }
        let p = min(max((t - at) * 1000 / ms, 0), 1)
        let e = p < 0.5 ? 2 * p * p : 1 - pow(-2 * p + 2, 2) / 2
        return from + (to - from) * e
    }
}
