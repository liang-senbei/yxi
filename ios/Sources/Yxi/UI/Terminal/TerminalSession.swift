import Foundation
import SwiftUI
import UIKit
import SwiftTerm
import YxiKit

// ⚠️ 依赖：`SwiftTerm`（migueldeicaza），Package.swift 要加：
//   .package(url: "https://github.com/migueldeicaza/SwiftTerm", from: "1.20.0")   // 最新 tag v1.20.0
//   target 依赖："SwiftTerm"
// 平台最低 **iOS 14**（它 Package.swift 里写的）。
// ⚠️ 它自己的 manifest 是 `swift-tools-version:6.2`，构建它需要**够新的 Xcode**。
//
// 下面用到的 API 全部**照它 main 分支的源码核对过**（不是猜的）：
//   `open class TerminalView: UIScrollView, UITextInputTraits, UIKeyInput, …`
//   `public weak var terminalDelegate: TerminalViewDelegate?`
//   `public func feed(byteArray: ArraySlice<UInt8>)` / `feed(text:)`
//   `public override var inputAccessoryView: UIView? { get set }`   ← 可写，见下
//   `public var controlModifier: Bool` / `metaModifier`             ← 自带粘滞修饰键
//   `public var font: UIFont` / `nativeForegroundColor` / `nativeBackgroundColor`

/// 一个终端的**全部活着的状态**：控件实例、PTY 通道、读循环、最后一次量到的尺寸。
///
/// ⚠️⚠️ **这个对象必须由 Workspace 用 `@StateObject` 按 host 持有，不能建在终端页面里。**
/// 切到「对话 / 文件」模式时 SwiftUI 会销毁终端视图；如果控件和读循环跟着视图走：
///   · 屏幕内容、滚动位置全丢（切回来一片空白，要重新 `tmux attach` 才画得出来）
///   · 更糟的是读循环死了但通道还开着、tmux 还在吐数据 ——
///     缓冲塞满之后**整条 SSH 连接上所有通道都不动了**，
///     表现是「切到对话模式，一条消息都不出来」，而服务器上 `tail -f` 明明在跑。
///     安卓上这个坑查了半天才想到是终端把连接堵死了。
///
/// 所有方法都在主线程跑（UIKit 的 delegate 回调 + SwiftUI）。
final class TerminalSession: ObservableObject, TerminalViewDelegate {

    /// ⚠️ **不是子类。** 早先想 `override inputAccessoryView` 去关掉 SwiftTerm 自带的
    /// 工具条，核对源码后发现它是 `public override var`（不是 `open`）—— 跨模块**覆盖不了**。
    /// 好在它**本来就可写**（值存在内部的 `_inputAccessory` 里），直接赋 nil 即可。
    let view = SwiftTerm.TerminalView(frame: .zero)

    /// 粘滞 Ctrl 的**界面**状态。真正生效的是控件自己的 `controlModifier`（见 [armCtrl]）。
    @Published var ctrlArmed = false
    /// 终端标题（tmux 会往这里写会话名）
    @Published private(set) var title = ""

    private var channel: SSHSession.Shell?
    private var pump: Task<Void, Never>?
    private var observers: [NSObjectProtocol] = []

    /// 控件最后一次量出来的真实尺寸。
    ///
    /// ⚠️⚠️ **这是 TROUBLESHOOTING #77 的核心。** 控件量出自己多大发生在
    /// **通道建好之前**，那一刻 channel 还是 nil，resize 被静默丢掉；
    /// 而尺寸之后不再变，回调也不会再来第二次。
    /// 于是 tmux 那头永远停在写死的 80x24，控件其实只有 40 多列 ——
    /// 服务器按 80 列排版、手机按 40 列折行，**整个画面是花的**（折行错位、边框断开），
    /// 看着像连不上。修法就是把最后一次通知**存下来**，接收方就绪时自己取。
    private var lastSize: (cols: Int, rows: Int)?

    /// ⚠️ **开 PTY 之前先读这个，别写死 80x24。**
    /// Workspace 里 `ssh.openShell(cols:rows:)` 的初值就用它。
    var preferredSize: (cols: Int, rows: Int) { lastSize ?? (80, 24) }

    init() {
        view.terminalDelegate = self
        view.nativeBackgroundColor = UIColor(Yx.lowest)
        view.nativeForegroundColor = UIColor(Yx.onSurface)
        view.backgroundColor = UIColor(Yx.lowest)
        view.keyboardAppearance = .dark
        // 等宽 13pt（PRD 附录 J.1）。再小手机上认不出 `l` 和 `1`
        view.font = UIFont.monospacedSystemFont(ofSize: 13, weight: .regular)

        // ⚠️ 关掉 SwiftTerm 自带的 `TerminalAccessory`（esc / tab / ctrl / 方向键）。
        // 我们有自己的 [KeyBar]，还多了 `^B`（tmux 前缀）、pgup/pgdn、`|` `~` 这些
        // 手机输入法上很难打的字符。两条工具条叠在一起又丑又白占一行高度。
        view.inputAccessoryView = nil

        // 控件每处理完一次输入会**自己**把 controlModifier 归零并发这条通知。
        // 我们只是跟着把界面上那个高亮也熄掉 —— 「点一下只管一个键」是白送的。
        observers.append(NotificationCenter.default.addObserver(
            forName: .terminalViewControlModifierReset, object: view, queue: .main
        ) { [weak self] _ in
            self?.ctrlArmed = false
        })

        // 软键盘弹出时自动滚到底。⚠️ 挂着 tmux 时它是空转的，理由见 [scrollToBottom]。
        // `keyboardDidShow` 在键盘动画结束之后才来，那时布局已经稳了。
        observers.append(NotificationCenter.default.addObserver(
            forName: UIResponder.keyboardDidShowNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self, self.view.isFirstResponder else { return }
            self.scrollToBottom()
        })
    }

    deinit {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    /// 滚到回滚缓冲的最底下。`toPosition` 是 0…1 的相对位置，1 就是底。
    ///
    /// ⚠️⚠️ **挂着 tmux 的时候这个函数是空转的，而且那是对的。**
    /// 核对过 SwiftTerm 的 `canScrollLocked()`：`isDisplayBufferAlternate` 为真时
    /// 直接返回 false。**tmux 跑在 alternate screen 上**，所以 SwiftTerm 自己的
    /// 回滚缓冲是空的 —— 历史归 tmux 管，不归控件管。
    /// 好在这种情况本来也不需要修：键盘一弹，pane 变矮 → `sizeChanged` → tmux 整屏重画，
    /// 画出来的就已经是最新的一屏。真正需要这个函数的是**没有 tmux 的裸 shell**
    /// （连一台没建会话的机器时），那时是普通缓冲、`canScroll` 为真、修的就是它。
    ///
    /// **「上滑看历史输出」分两条路，都不用我们写代码：**
    ///   · 裸 shell → `TerminalView` 本身就是 `UIScrollView`，手势和回滚它自带
    ///   · tmux 里 → 靠**鼠标上报**（SwiftTerm 的 `allowMouseReporting` 默认 true），
    ///     拖拽被当成鼠标事件送给 tmux，tmux 滚自己的历史。
    ///     ⚠️ 前提是服务器侧 `tmux set -g mouse on`。**别自己拼那条命令** ——
    ///     用 `YxiKit.SSHSession.attach(session:)`，它把 mouse on 和分号的反斜杠转义
    ///     都带好了（漏了反斜杠 shell 会吃掉分号，后两条 set 静默丢掉，
    ///     症状跟没写这行一模一样）。实在不行还有工具条上的 `^B`（进 copy-mode）。
    ///
    /// 这几个 API 在安卓那边**根本拿不到**：termlib 把 `ScrollController` 和
    /// `TerminalWithAccessibility` 都标成了 Kotlin `internal`（字节码上看是 public，
    /// 很容易误判）。iOS 能做是因为 SwiftTerm 开放了它们。
    func scrollToBottom() {
        guard view.canScroll else { return }
        view.scroll(toPosition: 1)
    }

    /// 接上一条开好的 PTY 通道。Workspace 在 shell 建好之后调一次。
    ///
    /// ⚠️ Workspace 那边 `openShell(cols:rows:)` 的初值要用 [preferredSize]，
    /// **别写死 80x24**（#77）。
    func attach(_ ch: SSHSession.Shell) {
        guard channel !== ch else { return }
        pump?.cancel()
        channel = ch
        let size = preferredSize
        // ⚠️ 这条读循环挂在 [TerminalSession] 上，**不挂在视图上**（见类注释）
        pump = Task { @MainActor [weak self] in
            // 开通道时应该已经用 preferredSize 开了；这里**再补一次** ——
            // 万一 sizeChanged 是在 openShell 之后才来的，就靠这一次纠正
            await ch.resize(cols: size.cols, rows: size.rows)
            // PTY 是合流的，stdout / stderr 都直接画出来
            for await chunk in ch.output {
                guard let self else { return }
                self.view.feed(byteArray: ArraySlice(chunk.bytes))
            }
        }
    }

    func detach() {
        pump?.cancel()
        pump = nil
        channel = nil
    }

    /// 点了工具条上的 `Ctrl`。
    ///
    /// ⚠️ **用控件自带的 `controlModifier`，别自己把下一个字节 `& 0x1f`。**
    /// 安卓那边没有现成的所以自己做了；iOS 上 SwiftTerm 在
    /// `insertText → commitTextInput(applyModifiers: true)` 里已经处理了 ——
    /// 两边都做就是**加两次**（`^C` 会变成别的字符），而且只有它知道
    /// 硬件键盘、kitty 键盘协议、marked text 这几条路径各自该怎么算。
    func armCtrl() {
        ctrlArmed.toggle()
        view.controlModifier = ctrlArmed
    }

    /// KeyBar / DPad / 编辑浮层都走这里。**不经过控件**，直接打进 PTY。
    ///
    /// ⚠️ 写失败**不抛异常**（`Shell.write` 返回 Bool）。通道断了是常态不是异常 ——
    /// 安卓那边往已关闭的通道写会抛，异常从协程逸出直接把 App 干崩了。
    func write(_ bytes: [UInt8]) {
        guard let channel else { return }
        Task { await channel.write(Data(bytes)) }
    }

    func write(_ text: String) { write(Array(text.utf8)) }

    /// 弹 / 收软键盘。⚠️ 控件不会自己弹 —— 得有人点。
    func toggleKeyboard() {
        if view.isFirstResponder { view.resignFirstResponder() }
        else { view.becomeFirstResponder() }
    }

    // MARK: - TerminalViewDelegate
    //
    // ⚠️ **11 个全都要实现。** 核对过源码：`TerminalViewDelegate` 没有提供默认实现的
    // extension，少一个就是「type does not conform」。`clipboardRead` 最容易漏 ——
    // 它是唯一有返回值的那个。

    func send(source: SwiftTerm.TerminalView, data: ArraySlice<UInt8>) {
        guard let channel else { return }
        // 修饰键控件已经算进去了（见 [armCtrl]），这里原样转发
        Task { await channel.write(Data(data)) }
    }

    func sizeChanged(source: SwiftTerm.TerminalView, newCols: Int, newRows: Int) {
        // ⚠️ 见 [lastSize]：这个回调**可能在通道就绪之前就来，而且只来一次**。
        // 存下来是为了通道建好时能自己取到，别指望它再发一遍。
        lastSize = (newCols, newRows)
        guard let channel else { return }
        Task { await channel.resize(cols: newCols, rows: newRows) }
    }

    func setTerminalTitle(source: SwiftTerm.TerminalView, title: String) { self.title = title }
    func hostCurrentDirectoryUpdate(source: SwiftTerm.TerminalView, directory: String?) {}
    func scrolled(source: SwiftTerm.TerminalView, position: Double) {}

    func requestOpenLink(source: SwiftTerm.TerminalView, link: String, params: [String: String]) {
        guard let url = URL(string: link), UIApplication.shared.canOpenURL(url) else { return }
        UIApplication.shared.open(url)
    }

    func bell(source: SwiftTerm.TerminalView) {}

    func clipboardCopy(source: SwiftTerm.TerminalView, content: Data) {
        UIPasteboard.general.string = String(data: content, encoding: .utf8)
    }

    func clipboardRead(source: SwiftTerm.TerminalView) -> Data? {
        UIPasteboard.general.string.map { Data($0.utf8) }
    }

    func iTermContent(source: SwiftTerm.TerminalView, content: ArraySlice<UInt8>) {}
    func rangeChanged(source: SwiftTerm.TerminalView, startY: Int, endY: Int) {}
}
