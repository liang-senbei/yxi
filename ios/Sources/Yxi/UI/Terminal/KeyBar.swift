import SwiftUI

/// 终端键盘工具条：手机软键盘上没有、但终端里离不开的那些键。
///
/// ⚠️ **`Ctrl` 是粘滞的，不是立刻发一个字节。** 点一下 `Ctrl` 再打 `c` 才是 `^C`。
/// 实现直接用 SwiftTerm 自带的 `controlModifier`（它每次输入后自己归零并发通知）——
/// **别自己去 `& 0x1f`**，两边都做就是加两次（见 [TerminalSession.armCtrl]）。
///
/// `^B` 单独列出来是因为它是 **tmux 前缀** —— 在终端里管 tmux（换窗口、分屏）全靠它，
/// 而软键盘上根本打不出来。`|` `~` `/` 同理：很多手机输入法要翻两页才找得到。
struct KeyBar: View {
    let ctrlArmed: Bool
    let composing: Bool
    let enabled: Bool
    let onCtrl: () -> Void
    let onKeyboard: () -> Void
    let onCompose: () -> Void
    let send: ([UInt8]) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                Cap("⌨", on: false, action: onKeyboard)
                Cap("Ctrl", on: ctrlArmed, action: onCtrl)
                // ✎ = 确认条。**要用语音就点它**：在那条里说话，看清楚了再送进终端。
                // 直接对着终端说话是**没有确认的**（见 ComposeBar 的 ⚠️⚠️）
                Cap("✎", on: composing, action: onCompose)
                ForEach(Self.keys) { k in
                    Cap(k.label, on: false) { send(k.bytes) }
                }
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
        }
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
    }

    /// ⚠️ 用结构体不用元组：Swift 没有元组的 key path，`ForEach(..., id: \.0)` 编译不过。
    struct Key: Identifiable {
        let label: String
        let bytes: [UInt8]
        var id: String { label }
        init(_ label: String, _ bytes: [UInt8]) { self.label = label; self.bytes = bytes }
    }

    /// 标签 → 真正打进 PTY 的字节。
    /// 方向键和 home/end/pgup/pgdn 都是 xterm 的序列，tmux 和 Claude Code 的 TUI 都认。
    static let keys: [Key] = [
        Key("esc",  [27]),
        Key("tab",  [9]),
        Key("⇧tab", [27, 91, 90]),
        Key("^C",   [3]),
        Key("^D",   [4]),
        Key("^Z",   [26]),
        Key("^L",   [12]),
        Key("^R",   [18]),
        Key("^B",   [2]),               // tmux 前缀
        Key("←",    [27, 91, 68]),
        Key("↓",    [27, 91, 66]),
        Key("↑",    [27, 91, 65]),
        Key("→",    [27, 91, 67]),
        Key("home", [27, 91, 72]),
        Key("end",  [27, 91, 70]),
        Key("pgup", [27, 91, 53, 126]),
        Key("pgdn", [27, 91, 54, 126]),
        Key("|",    [124]),
        Key("/",    [47]),
        Key("~",    [126]),
    ]
}

private struct Cap: View {
    let label: String
    let on: Bool
    let action: () -> Void
    init(_ label: String, on: Bool, action: @escaping () -> Void) {
        self.label = label; self.on = on; self.action = action
    }
    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.mono(13))
                .foregroundStyle(on ? Yx.onCopperBox : Yx.onSurfaceVar)
                // 触摸目标 ≥44pt（PRD 附录 J.1）—— 打字时这些键要能盲点中
                .frame(minWidth: 44, minHeight: 38)
                .padding(.horizontal, 8)
                .background(on ? Yx.copperBox : Yx.high, in: Capsule())
        }
        .buttonStyle(.plain)
    }
}
