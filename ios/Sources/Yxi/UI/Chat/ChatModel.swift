import Foundation
import SwiftUI
import YxiKit

/// 对话模式的状态。视图只读它、只调它的方法，**不碰 SSH**。
///
/// 三条数据流各司其职：
///   · 历史和排队 → 转录 JSONL（结构化、权威）
///   · 此刻在忙什么 → `tmux capture-pane`（**唯一来源**，转录里永远没有）
///   · 正在等你选什么 → 同上（`tool_use` 要等工具跑完才落盘）
@MainActor
final class ChatModel: ObservableObject {

    @Published private(set) var items: [ChatItem] = []
    /// 历史灌完了没。**这个标志是「不要一闪一闪跳」的关键**，见 [run] 里的注释。
    @Published private(set) var settled = false
    @Published private(set) var live: Live = .idle
    @Published private(set) var pending: Pending?
    /// 只在出问题时说一句。正常情况下是 nil（标题和路径由 Workspace 的头部管）
    @Published private(set) var status: String? = "连接中…"

    @Published var draft = ""
    @Published private(set) var staged: [Staged] = []
    @Published private(set) var uploading = false
    /// 正在替你送键。这期间**别抓屏**：屏幕还没重绘完，抓回来的是旧的那一屏
    @Published private(set) var answering = false

    private let backend: ChatBackend
    /// ⚠️ 会话和 cwd 是**每次 [run] 传进来的，不是 init 定死的**。
    /// `@StateObject` 只在视图 identity 变化时才重建 —— 在工作区里换个 tmux 会话时
    /// ChatScreen 是原地复用的，init 里存死就会拿着旧会话去 tail、去 send-keys。
    /// （那种错不会报错，只会**打字打到别的会话里**。）
    private var session = ""
    private var cwd = ""

    /// 收到的原始行。攒着，由下面的定时协程刷出去
    private var buffer: [String] = []
    private var dirty = false

    /// ⚠️ `nonisolated`：`StateObject(wrappedValue:)` 是在 View 的 init 里调的，
    /// 那儿不是 MainActor 上下文。只赋值存储属性，安全。
    nonisolated init(backend: ChatBackend) {
        self.backend = backend
    }

    /// 视图用 `.task(id:)` 跑这一个函数。结构化并发保证：视图一消失，下面三条循环全停。
    func run(session: String, cwd: String) async {
        // 换会话 = 从头来。旧会话的历史、排队气泡、待答提示一条都不能留下
        self.session = session
        self.cwd = cwd
        items = []
        buffer = []
        dirty = false
        settled = false
        pending = nil
        live = .idle
        status = "连接中…"

        guard !session.isEmpty else { status = "没有指定会话"; return }

        let file: String?
        do {
            file = try await backend.latestTranscript(cwd: cwd)
        } catch {
            status = reportable(error).map { "找不到转录：\($0)" }
            return
        }
        guard let file else {
            status = "这个会话里没找到 Claude Code 的转录\n（\(cwd)）"
            return
        }
        status = nil

        await withTaskGroup(of: Void.self) { group in
            group.addTask { await self.tail(file) }
            group.addTask { await self.flush() }
            group.addTask { await self.probeLoop() }
        }
    }

    // MARK: - 转录

    /// 只管收行。**一行都不解析** —— 解析归 [flush]。
    private func tail(_ file: String) async {
        for await line in backend.transcriptLines(file: file, backlog: 800) {
            buffer.append(line)
            dirty = true
        }
    }

    /// ⚠️⚠️ **节流必须有尾随刷新。**
    ///
    /// Android 上第一版写成「距上次解析超过 250ms 才解析」，结果是：
    /// `tail` 一上来把几百行历史**一次性吐完**（全落在同一个窗口里），
    /// 只有第一行触发了解析，剩下的全被吞掉，然后 tail 阻塞等新内容 ——
    /// **界面永远停在第一行的解析结果上**。
    /// 现象是「忙的会话正常、闲的会话永远空白」，最容易被当成网络或时序问题。
    /// 见 TROUBLESHOOTING #35。教训：凡是「攒一批再处理」，
    /// 都要问一句「最后那批谁来收」—— 丢的永远是最后一批，而最后一批往往就是全部。
    private func flush() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 300_000_000)
            if Task.isCancelled { return }

            if !dirty {
                // ⚠️ **一个空转的周期 = 历史灌完了。**
                // 在它之前每次刷新都瞬移到底（不做动画），之后才允许动画。
                // `tail -n 800` 是分批吐的，每批都播一次滚动动画 ——
                // 上一个还没走完下一批又来，看起来就是一闪一闪地跳（#73）。
                if !items.isEmpty { settled = true }
                continue
            }
            dirty = false
            let snapshot = buffer
            // 几百行不能在主线程解 —— 会明显卡住输入框
            items = await Task.detached(priority: .userInitiated) {
                Transcript.parse(snapshot)
            }.value
        }
    }

    // MARK: - 屏幕

    private func probeLoop() async {
        while !Task.isCancelled {
            if !answering {
                do {
                    // ⚠️ **一次抓屏解两件事。** 分两次抓会看到不一致的瞬间
                    // （比如「已经不忙了」但「还挂着一个待答」），界面会闪。
                    let screen = try await backend.peek(session: session, lines: 200)
                    pending = Prompt.parse(screen)
                    live = Live.parse(screen)
                } catch {
                    // 抓屏失败不值得打扰用户：下一轮就重试了。
                    // ⚠️ 但取消要原样退出，别当成一次失败继续循环
                    if error is CancellationError { return }
                }
            }
            // 忙的时候抓快一点 —— 状态行是给人看「它还活着」的，
            // 2.5 秒一跳就不像在动了；闲的时候没必要这么勤
            let ns: UInt64 = live.busy ? 900_000_000 : 2_500_000_000
            try? await Task.sleep(nanoseconds: ns)
        }
    }

    // MARK: - 输入侧

    var canSend: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !staged.isEmpty }

    func send() {
        guard canSend else { return }
        // 附件的路径映射贴在正文前面 —— Claude 自己去读那些文件（PRD 附录 F）
        let text = (backend.attachmentHeader(staged)
                    + draft.trimmingCharacters(in: .whitespacesAndNewlines))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        draft = ""
        staged = []
        Task { [backend = self.backend, session = self.session] in
            do { try await backend.send(session: session, text: text) }
            catch { if let m = reportable(error) { status = "发不出去：\(m)" } }
        }
    }

    /// 点了待答选项。
    ///
    /// ⚠️ **卡片上显示几号，就送几号。** 不要改成按下标送键：
    /// 一旦屏幕顺序和列表顺序对不上，就会**点 A 选中 B 且不报错**。
    func pick(_ option: Pending.Option) {
        answer { [backend = self.backend, session = self.session] in
            try await backend.sendKey(session: session, key: String(option.number))
        }
    }

    /// 多选交卷：数字只是切换勾选，要 `Right` 跳到 Submit 页再送 `1`（实测，#29）
    func submitMultiSelect() {
        answer { [backend = self.backend, session = self.session] in
            try await backend.sendKey(session: session, key: "Right")
            try await Task.sleep(nanoseconds: 300_000_000)
            try await backend.sendKey(session: session, key: "1")
        }
    }

    private func answer(_ work: @escaping @Sendable () async throws -> Void) {
        guard !answering else { return }
        answering = true
        Task { [backend = self.backend, session = self.session] in
            defer { answering = false }
            do {
                try await work()
                // 等 TUI 重绘完再抓，否则拿回来的还是刚才那一屏
                try await Task.sleep(nanoseconds: 500_000_000)
                let screen = try await backend.peek(session: session, lines: 200)
                pending = Prompt.parse(screen)
                live = Live.parse(screen)
            } catch {
                if let m = reportable(error) { status = "送不过去：\(m)" }
            }
        }
    }

    // MARK: - 附件

    func attach(fileName: String, data: Data, isImage: Bool) {
        uploading = true
        Task { [backend = self.backend, session = self.session] in
            defer { uploading = false }
            do {
                staged.append(try await backend.upload(
                    session: session, fileName: fileName, data: data, isImage: isImage))
            } catch {
                if let m = reportable(error) { status = "传不上去：\(m)" }
            }
        }
    }

    func drop(_ s: Staged) { staged.removeAll { $0.id == s.id } }
}

/// 该不该把这个错误显示给用户。**取消不算失败** → 返回 nil。
///
/// ⚠️ Android 上同一个错踩了**五次**（TROUBLESHOOTING #78 / #79）：
/// `runCatching { … }.onFailure { status = "…" }` 把协程的取消信号也捕获了，
/// 于是切个模式就在界面上留下一句「终端起不来：The coroutine scope left the composition」，
/// 而且再进来也不消失。Swift 这边形状一模一样 —— `try?` 和 `catch {}` 会把
/// `CancellationError` 一并吃掉。**凡是「把失败显示给用户」的地方，先问一句「取消算失败吗」。**
func reportable(_ error: Error) -> String? {
    if error is CancellationError { return nil }
    // URLError/NSError 的取消也算取消
    if (error as NSError).code == NSUserCancelledError { return nil }
    return error.localizedDescription
}
