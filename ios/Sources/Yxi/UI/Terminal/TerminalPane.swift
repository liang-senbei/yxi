import SwiftUI
import UIKit

/// 终端模式的整块界面：真终端画面 + 键盘工具条 + 方向盘 + 中文/语音的编辑浮层。
///
/// ⚠️ [session] **必须由 Workspace 用 `@StateObject` 持有并传进来**（见 [TerminalSession]）。
/// 这个 View 会随着模式切换销毁重建，终端的画面和读循环不能跟着没。
struct TerminalPane: View {
    @ObservedObject var session: TerminalSession
    /// 有没有开着的 PTY。没有就把工具条置灰 —— 点了没反应比灰着更让人困惑
    var connected: Bool = true

    @State private var showBar = true
    @State private var showDPad = false
    /// 编辑浮层开着没。见 [ComposeBar]
    @State private var composing = false
    @State private var draft = ""

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            TerminalHost(session: session)

            if showDPad {
                DPad { session.write($0) }
                    .padding(14)
            }
        }
        .background(Yx.lowest)
        // ⚠️ **这里绝对不能加 `.ignoresSafeArea(.keyboard)`。**
        // Android 上同一件事踩过（TROUBLESHOOTING #62）：edge-to-edge 之下软键盘
        // 把整条键盘工具条盖住了 —— 而 esc / tab / ^C 恰恰是打字时最需要的几个键。
        // iOS 的 SwiftUI **默认**就会为键盘让位，`safeAreaInset` 的内容自动浮在键盘上方，
        // 白送的。只要没人手贱去 ignoresSafeArea 就不会重演。
        //
        // 顺带：键盘弹起会让终端变矮 → `sizeChanged` 触发 → tmux 跟着 resize。
        // 这是**对的**，真终端就该这样，tmux 会自己重排。
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 0) {
                if composing {
                    ComposeBar(text: $draft) { line in
                        session.write(line + "\n")
                        draft = ""
                        composing = false
                    } onCancel: {
                        draft = ""
                        composing = false
                    }
                }
                if showBar {
                    KeyBar(
                        ctrlArmed: session.ctrlArmed,
                        composing: composing,
                        enabled: connected,
                        onCtrl: { session.armCtrl() },
                        onKeyboard: { session.toggleKeyboard() },
                        onCompose: { composing.toggle() },
                        send: { session.write($0) }
                    )
                }
            }
            .background(Yx.surface)
        }
        // 两个开关放在这里而不是 Workspace 的头部：它们只在终端模式有意义
        .overlay(alignment: .topTrailing) { toggles }
    }

    private var toggles: some View {
        HStack(spacing: 6) {
            PillToggle("⌨", on: showBar) { showBar.toggle() }
            PillToggle("✛", on: showDPad) { showDPad.toggle() }
        }
        .padding(10)
    }
}

private struct PillToggle: View {
    let icon: String
    let on: Bool
    let tap: () -> Void
    init(_ icon: String, on: Bool, _ tap: @escaping () -> Void) {
        self.icon = icon; self.on = on; self.tap = tap
    }
    var body: some View {
        Button(action: tap) {
            Text(icon).font(.system(size: 14))
                .foregroundStyle(on ? Yx.copper : Yx.muted)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(on ? Yx.high : Yx.container.opacity(0.8), in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// SwiftTerm 的控件塞进 SwiftUI。
///
/// ⚠️ 控件实例来自 [TerminalSession]，**这里只是把它挂上去**。
/// `makeUIView` 里 `TerminalView()` 新建一个的话，每次切模式回来都是一块空白屏。
private struct TerminalHost: UIViewRepresentable {
    let session: TerminalSession

    func makeUIView(context: Context) -> UIView {
        let box = UIView()
        let t = session.view
        t.translatesAutoresizingMaskIntoConstraints = false
        box.addSubview(t)
        NSLayoutConstraint.activate([
            t.leadingAnchor.constraint(equalTo: box.leadingAnchor),
            t.trailingAnchor.constraint(equalTo: box.trailingAnchor),
            t.topAnchor.constraint(equalTo: box.topAnchor),
            t.bottomAnchor.constraint(equalTo: box.bottomAnchor),
        ])
        return box
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}

/// **语音的确认条**（顺带也是中文输入的退路）：先在这里编好一整段，再整段提交进 PTY。
///
/// **① 它主要是为语音存在的。** PRD 附录 E.2 是硬规矩：
/// **命令行模式的识别结果必须先显示、确认才发** —— 识别错一个字，
/// 在服务器上就是另一条命令，没有反悔的机会。
/// 在这条里用系统键盘上的听写麦克风说话，结果落进这个输入框，
/// **这个输入框本身就是那道确认**。不用接 Speech.framework，不用申请麦克风权限。
///
/// ⚠️⚠️ **但它挡不住用户直接对着终端说话。** 核对过 SwiftTerm 源码：
/// `iOS/iOSTextInput.swift` 是一份完整的 `UITextInput` 实现，
/// 文件头的测试清单里**明确列了听写**（"Enable dictation… say Hello world"）——
/// 也就是说光标在终端里时按系统键盘的麦克风，识别结果会**直接打进 PTY，没有任何确认**。
/// iOS 没有「禁用听写」这个输入特征，**技术上关不掉**。
/// 现在只能靠引导（工具条上给了 ✎，文案说清楚）。这条要写进 TROUBLESHOOTING。
///
/// **② 中文其实不需要它。** 安卓上中文是硬伤（终端控件的 `inputType` 是
/// `VISIBLE_PASSWORD`，中文输入法见到「像密码框」就不给候选词）。
/// iOS 上**没有这个问题**：`iOSTextInput.swift` 实现了完整的 marked text，
/// 文件头的测试清单里就写着简体拼音「dddd → 点点滴滴」，而且
/// `isSecureTextEntry = false`、`autocorrectionType = .no` —— 不是密码框。
/// ⚠️ 未验证：以上是**读源码**得出的，没有真机跑过。真机第一次跑时试一下
/// 「打 dddd 看候选栏、选词后终端里有没有留半成品」，不行就把这条当主力输入通道。
private struct ComposeBar: View {
    @Binding var text: String
    let onSubmit: (String) -> Void
    let onCancel: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 8) {
            TextField("说或打，确认后再进终端", text: $text, axis: .vertical)
                .lineLimit(1...4)
                .font(.mono(14))
                .foregroundStyle(Yx.onSurface)
                .tint(Yx.copper)
                .focused($focused)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(Yx.container, in: RoundedRectangle(cornerRadius: 18, style: .continuous))

            Button { onSubmit(text) } label: {
                Text("送").font(.system(size: 14, weight: .medium))
                    .foregroundStyle(text.isEmpty ? Yx.dim : Yx.onCopper)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(text.isEmpty ? Yx.container : Yx.copper, in: Capsule())
            }
            .buttonStyle(.plain)
            .disabled(text.isEmpty)

            Button(action: onCancel) {
                Text("✕").font(.system(size: 14)).foregroundStyle(Yx.muted).padding(6)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
        .onAppear { focused = true }
    }
}
