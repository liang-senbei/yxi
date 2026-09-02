import SwiftUI
import UIKit

/// 全 app 的**唯一**配色 / 形状来源。取值跟安卓的 `ui/theme/Palette.kt` 逐个对齐。**照搬，别自己调。**
///
/// **浅色 + 深色两套，默认浅色**（跟安卓一致：新用户进来看见的就是浅色）。
/// 每个名字都是一对值，按 `UIColor` 的动态色跟着 `colorScheme` 走 ——
/// 全 app 两百多处 `Yx.xxx` 一个字不用改。切换在 [Appearance]。
///
/// ⚠️ **终端不跟着变浅。** ANSI 彩色输出是按深底配的，浅底上黄色/亮绿几乎看不见，
/// 所以终端永远用写死的 [terminalBg] / [terminalFg]，代价是浅色下切到终端有一下明暗跳变 ——
/// 这是自觉的取舍，不是 bug。
///
/// M3 的核心是**用面的明度分层代替描边** —— 全 app 不用 1px 边框，靠 surface 逐级提亮拉开层次。
enum Yx {
    // MARK: 面：逐级提亮（浅色 / 深色）
    static let surface   = dyn(0xFFFFFF, 0x16130F)   // 页面底
    static let lowest    = dyn(0xF8FAFD, 0x100E0B)   // 代码块
    static let low       = dyn(0xF0F4F9, 0x1E1B17)   // 卡片
    static let container = dyn(0xF0F4F9, 0x221F1B)   // 控件、输入框
    static let high      = dyn(0xE9EEF6, 0x2D2925)   // 行内代码、抬起
    static let highest   = dyn(0xDDE3EA, 0x383430)

    // MARK: 字
    static let onSurface    = dyn(0x1F1F1F, 0xEBE1D9)
    static let onSurfaceVar = dyn(0x3C4043, 0xD0C4B8)
    // ⚠️ 浅色的这两档别照抄白底上的取值：卡片底是 #F0F4F9，浅了对比度不够，提示文字直接糊掉
    static let muted        = dyn(0x3C4043, 0xA89B8F)
    static let dim          = dyn(0x5F6368, 0x8A7D72)

    // MARK: 强调。名字沿用「铜」—— 浅色里它其实是蓝，改名要动两百处，不值
    static let copper      = dyn(0x0B57D0, 0xFFB787)   // 主操作、你的消息
    static let onCopper    = dyn(0xFFFFFF, 0x4D2600)
    static let copperBox   = dyn(0xD3E3FD, 0x6D3A10)   // 你的消息气泡、选中的胶囊
    static let onCopperBox = dyn(0x041E49, 0xFFDCC4)
    static let teal        = dyn(0x0B8043, 0x8FD8C6)   // 干活中、Edit、SFTP
    /// ⚠️ 琥珀**只在「需要你动手」时出现**。挪作它用就等于把这个信号稀释掉了。
    static let amber       = dyn(0xE37400, 0xFFC46B)

    // MARK: diff
    static let addFg = dyn(0x0B8043, 0x9CE0A8), addBg = dyn(0xE6F4EA, 0x1F3B2C)
    static let delFg = dyn(0xC5221F, 0xFFB4A6), delBg = dyn(0xFCE8E6, 0x3D2320)

    /// 错误文字 / 错误条底色。值跟 diff 的删除色是同一个，**语义不同所以另起名**。
    static let error    = delFg
    static let errorBox = delBg

    /// 状态圆点里「既不是等你、也不是干活中」的那个灰
    static let idleDot = dyn(0xC4C7CC, 0x4A443D)

    /// 终端永远深底（见文件头）
    static let terminalBg = Color(hex: 0x100E0B)
    static let terminalFg = Color(hex: 0xEBE1D9)

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

    private static func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }
}

/// 浅色 / 深色 / 跟随系统。**默认浅色。** 存在 UserDefaults，设置页改、RootView 读。
enum Appearance {
    static let key = "appearance"
    static let light = "light", dark = "dark", system = "system"
    static func scheme(_ v: String) -> ColorScheme? {
        v == dark ? .dark : v == system ? nil : .light
    }
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
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
