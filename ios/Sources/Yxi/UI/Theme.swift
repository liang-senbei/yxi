import SwiftUI

/// 全 app 的**唯一**配色 / 形状来源。取值和安卓的 `ui/theme/Color.kt` 逐个对齐
/// （PRD 附录 J.1，方向 B · 暖/铜色源）。**照搬，别自己调。**
///
/// **只有深色。** 终端必然是深色（浅底读 ANSI 彩色输出很吃力），
/// 做「浅色对话 + 深色终端」来回切会闪眼 —— 所以这里全是写死的值，
/// 不用 asset catalog 的动态色、也不看 `colorScheme`。
///
/// M3 的核心是**用面的明度分层代替描边** —— 全 app 不用 1px 边框，
/// 靠 surface 逐级提亮拉开层次。
///
/// ⚠️ 成员名沿用对话/终端那边先起的那套（`low` / `high` / `copperBox` …），
/// **不是**因为它更好，而是因为它已经铺在五六个文件里了 —— 一份配色只能有一个名字。
enum Yx {
    // MARK: 面：逐级提亮
    static let surface   = Color(hex: 0x16130F)   // 页面底
    static let lowest    = Color(hex: 0x100E0B)   // 终端底、代码块
    static let low       = Color(hex: 0x1E1B17)   // 卡片
    static let container = Color(hex: 0x221F1B)   // 控件、输入框
    static let high      = Color(hex: 0x2D2925)   // 行内代码、抬起
    static let highest   = Color(hex: 0x383430)

    // MARK: 字
    static let onSurface    = Color(hex: 0xEBE1D9)
    static let onSurfaceVar = Color(hex: 0xD0C4B8)
    static let muted        = Color(hex: 0xA89B8F)
    static let dim          = Color(hex: 0x8A7D72)

    // MARK: 强调：三个同亮度同彩度、只变色相
    static let copper      = Color(hex: 0xFFB787)   // 主操作、你的消息
    static let onCopper    = Color(hex: 0x4D2600)
    static let copperBox   = Color(hex: 0x6D3A10)
    static let onCopperBox = Color(hex: 0xFFDCC4)
    static let teal        = Color(hex: 0x8FD8C6)   // 干活中、Edit、SFTP
    /// ⚠️ 琥珀**只在「需要你动手」时出现**。挪作它用（比如当第三个装饰色）
    /// 就等于把这个信号稀释掉了 —— 用户扫一眼找的就是这个颜色。
    static let amber       = Color(hex: 0xFFC46B)

    // MARK: diff
    static let addFg = Color(hex: 0x9CE0A8), addBg = Color(hex: 0x1F3B2C)
    static let delFg = Color(hex: 0xFFB4A6), delBg = Color(hex: 0x3D2320)

    /// 错误文字 / 错误条底色。值跟 diff 的删除色是同一个（安卓也是），
    /// 但**语义不同所以另起名** —— 哪天要把「报错」和「删掉的行」分开时不用满仓库改。
    static let error    = delFg
    static let errorBox = delBg

    /// 状态圆点里「既不是等你、也不是干活中」的那个灰
    static let idleDot = Color(hex: 0x4A443D)

    // MARK: 形状 / 尺寸（PRD 附录 J.1）
    /// 卡片
    static let cardRadius: CGFloat = 28
    /// 内嵌块
    static let blockRadius: CGFloat = 22
    /// 控件一律药丸。SwiftUI 里直接用 `Capsule()`，这个数值给需要 `RoundedRectangle` 的地方
    static let pill: CGFloat = 100

    /// 页面留白
    static let pad: CGFloat = 18
    /// 块间距
    static let gap: CGFloat = 22
    /// ⚠️ 触摸目标下限。控件普遍抬到 48 / 52
    static let tap: CGFloat = 44
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red:   Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >>  8) & 0xFF) / 255,
            blue:  Double( hex        & 0xFF) / 255,
            opacity: 1
        )
    }
}

extension Font {
    /// 代码 / 路径 / 时间 / 指纹。
    /// ⚠️ PRD 附录 J.1 写的是 JetBrains Mono，但字体文件要进 SPM 资源、
    /// 而 `Package.swift` 不归 UI 这边改 —— 先用系统等宽。
    /// 字体缺席时 `Font.custom` 是**静默**回落的（看起来"没生效"却不报错），
    /// 与其留一个不知道有没有生效的调用，不如先明确用系统的。换字体只改这一处。
    static func mono(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .monospaced)
    }
}

// MARK: - 小件

/// 药丸控件：全 app 的按钮 / 标签统一长这样。高度默认 44（触摸目标下限）。
struct YxPill<Content: View>: View {
    var fill: Color = Yx.container
    var height: CGFloat = Yx.tap
    var hPad: CGFloat = 16
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(.horizontal, hPad)
            .frame(height: height)
            .background(fill, in: Capsule())
    }
}

/// 卡片：`low` + 28 圆角。列表里每一项都是它。
struct YxCard<Content: View>: View {
    var fill: Color = Yx.low
    var radius: CGFloat = Yx.cardRadius
    @ViewBuilder var content: Content

    var body: some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(fill, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

/// 说明文字。表单里到处都是。
struct YxHint: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text)
            .font(.system(size: 13))
            .foregroundStyle(Yx.dim)
            .fixedSize(horizontal: false, vertical: true)
    }
}
