import Citadel
import Crypto
import Foundation
import Logging
import NIOCore
@preconcurrency import NIOSSH   // NIOSSHPublicKey 没标 Sendable，我们只读它

/// 一条 SSH 连接。分层跟安卓那版一致：**连接本身**与**在上面开什么通道**分开 ——
///   · shell channel（带 PTY） → 终端（[openShell]）
///   · exec channel（不带 PTY）→ 事件流 / `tail -f`（[openStream]）
///   · exec 一次性          → 会话枚举、抓屏、探测（[exec]）
///   · sftp                 → 文件模式、附件、自更新（[openSFTP]）
/// 四者共用同一条连接，**不重复握手**。
///
/// ## 为什么是 Citadel（选型理由，改之前先读）
///
/// 候选只有两个能同时满足「纯 Swift · iOS 能用 · Linux 上能跑测试」：
///
/// | | `apple/swift-nio-ssh` | **`orlandos-nl/Citadel`** ← 选它 |
/// |---|---|---|
/// | SFTP | **没有**。它只是协议实现，不是客户端 | ✅ 自带 SFTP v3 客户端 |
/// | 交互式 PTY / shell | 要自己拼 child channel + 事件 | ✅ `withPTY` / `withTTY` / `withExec` |
/// | 主机指纹校验 | `NIOSSHClientServerAuthenticationDelegate`（够用） | ✅ 同一个，包了一层 |
/// | Linux | ✅ | ✅ **已实测**（Swift 6.0.3 / Ubuntu 24.04 编过） |
///
/// **`P0-11 文件模式`（SFTP）是刚需**（PRD 附录 G，任何 SSH 主机不装东西就能翻文件），
/// 自己按 RFC 写一个 SFTP v3 客户端是几百行协议代码 —— 那不该是这个项目要花的成本。
/// libssh2 系（NMSSH / Shout）被排除：要么是 ObjC 且多年不维护，
/// 要么要给 iOS 交叉编译一个 C 库，**而且都不能在 Linux 上跑测试**。
///
/// ### Citadel 的已知缺口（都已核对源码，不是推测）
///
/// 1. ⚠️ **它依赖的不是 `apple/swift-nio-ssh`，是一条 fork 链**：
///    `apple` → `Joannis`（Citadel 作者，为了 RSA 等上游没有的算法）→
///    **`Wellz26`（0.12.1 里指着的那个，2026-04-02 建、0 star、外部贡献者一次提交改的）**。
///    已把 `Wellz26/0.3.4` 和 `Joannis/0.3.4` 逐文件比过：**Sources 与 Package.swift 完全一致**，
///    当前 tag 上没有夹带。风险不在代码在**控制权**（那个 tag 随时可能被挪）。
///    → `Package.resolved` **必须入库**（它钉的是 commit 哈希不是 tag）；
///    真出问题就 fork Citadel 改那一行，不需要动我们自己的代码。
/// 2. ⚠️ **没有任何 keepalive**（NIOSSH 和 Citadel 都搜不到）。
///    安卓靠 jsch 的 `serverAliveInterval` 发现切网后的「假活」（#81），
///    **这条路 iOS 上不存在**，只能应用层自己做心跳 —— 见 [Liveness] 和 [follow]。
/// 3. ⚠️ **`executeCommand` 在退出码非 0 时抛异常，并且把已经收到的输出全丢掉**
///    （它自己的文档注释写了）。而我们大量命令**本来就会非 0**
///    （`grep -q` 没匹配、没有 tmux server 时的 `list-sessions`）。见 [exec]。
/// 4. ⚠️ **`withPTY` / `withTTY` / `withExec` 是「作用域」API**（closure 里用完就关通道），
///    跟安卓 `openShell(): Shell` 那种「拿个句柄」的形状对不上。见 [openShell] 里的适配。
/// 5. ⚠️ **没有「exec + PTY」的公开入口**（内部的 `_executeCommandStream(mode:.pty)` 不 public）。
///    安卓是用 exec+PTY 直接跑 `tmux attach`，为的是**绕开登录 shell 的横幅和时序竞态**（#18）。
///    iOS 上只能开 shell 再把命令打进去 —— #18 的坑因此**回来了**，见 [openShell] 的 `command:` 参数。
/// 6. ⚠️ NIOSSH 只带 AES-GCM 一种传输保护，**也没有 strict-KEX**（`kex-strict-*` 搜不到）。
///    安卓那条「必须避开 AES-GCM」（#16）**是 jsch 自己的 bug，不是协议问题**，这里不适用 ——
///    而且这里想避也避不开，没得选。
public final class SSHSession: @unchecked Sendable {

    /// 通道上下来的一段字节。
    ///
    /// ⚠️ **stderr 必须单独收，不能不读。** 安卓踩过：exec 通道的 stderr 是独立一条流，
    /// 不读的话远端的报错**凭空消失** —— `tmux` 的
    /// `open terminal failed: not a terminal` 就走 stderr，
    /// 表现成「通道静默关闭」，从客户端完全看不出原因。
    public enum Chunk: Sendable {
        case stdout(Data)
        case stderr(Data)

        public var bytes: Data {
            switch self {
            case .stdout(let d), .stderr(let d): return d
            }
        }
    }

    public struct CommandResult: Sendable {
        public let stdout: String
        public let stderr: String
        /// 远端退出码。nil = 通道结束前没拿到（远端被杀之类）。
        public let exitCode: Int?

        public var succeeded: Bool { exitCode == 0 }
    }

    public enum Failure: Error {
        case notConnected
        case shellSetupFailed(underlying: Error)
    }

    private let config: HostConfig
    private let gate: HostKeyGate
    private let logger: Logger
    private let lock = NSLock()
    private var client: SSHClient?

    public init(config: HostConfig, gate: HostKeyGate, logger: Logger = Logger(label: "app.yxi.ssh")) {
        self.config = config
        self.gate = gate
        self.logger = logger
    }

    public var isConnected: Bool { lock.withLock { client }?.isConnected == true }

    /// 报错文案里用的目标。**永远是真正连的地址，不是别名**（#71）。
    public var target: String { config.target }

    // MARK: - 连接

    /// ⚠️⚠️ **默认 15 秒不够，因为首连要停下来等人核对指纹。**
    ///
    /// 第一次连一台机器时，握手中途会弹出「主机指纹是这个，信任吗」——
    /// 人得读完那串 SHA256、切去服务器上跑 `ssh-keygen -lf` 对一遍，再回来点。
    /// 15 秒**必然**不够。而超时之后报的是「连接超时（包发出去了没人应）」，
    /// 把用户支去查网络和防火墙 —— 真实原因只是「框还没点」。
    /// （真机上逮到的：网络通、密码对、指纹也对，就是连不上。）
    ///
    /// 90 秒是**给人读字的时间**，不是给网络的：网络真不通时 TCP 自己会先失败，
    /// 不会白等满 90 秒。
    public func connect(timeout: TimeAmount = .seconds(90)) async throws {
        let auth: SSHAuthenticationMethod = switch config.auth {
        case .password(let pw): .passwordBased(username: config.username, password: pw)
        case .privateKey(let key): .ed25519(username: config.username, privateKey: key)
        }

        let c = try await SSHClient.connect(
            host: config.hostname,
            port: config.port,
            authenticationMethod: auth,
            hostKeyValidator: .custom(gate),
            // ⚠️ **故意是 `.never`。** Citadel 的 `.always` 会在底下悄悄重连 ——
            // 而安卓 #81 的教训正相反：连接死了**没人知道**才是最难受的，
            // 「刷新失败」写在界面上、`ssh` 却仍非 null，连重连入口都不出现。
            // 重连要由上层驱动，因为只有上层能把「正在重连」画给用户看。
            reconnect: .never,
            connectTimeout: timeout
        )
        lock.withLock { client = c }
        // ponytail: 走的是 NIOPosix（BSD socket）—— Citadel 的这个 `connect` 里
        // 写死了 `MultiThreadedEventLoopGroup.singleton`。iOS 上苹果推荐的是
        // Network.framework（对应 NIOTransportServices），它才懂 WiFi↔蜂窝切换、
        // VPN、以及后台被挂起时的连接状态。**升级路子是现成的**：
        // Citadel 有 `SSHClient.connect(on channel:settings:)`，喂一个 NIOTS 建的
        // channel 进去即可。等真的被切网折腾到了再换，别现在为它加一层。
    }

    /// 连接掉了通知上层。上层负责退避重连并把状态画出来（#79：指数退避 1s→15s，指纹变了才停）。
    public func onDisconnect(_ handler: @escaping @Sendable () -> Void) {
        lock.withLock { client }?.onDisconnect(perform: handler)
    }

    public func disconnect() async {
        let c = lock.withLock { let c = client; client = nil; return c }
        try? await c?.close()
    }

    private func requireClient() throws -> SSHClient {
        guard let c = lock.withLock({ client }), c.isConnected else { throw Failure.notConnected }
        return c
    }

    // MARK: - 一次性命令

    /// 跑一条命令拿输出就退（会话枚举、`capture-pane` 抓屏、探测都走它）。
    ///
    /// ⚠️ **不用 Citadel 的 `executeCommand`。** 它遇到非 0 退出码会抛
    /// `CommandFailed` 并且**把已经收到的 stdout 全部丢掉**（见类型头上第 3 条）。
    /// 我们这边非 0 是家常便饭 —— 没有 tmux server 时 `tmux list-sessions` 就非 0，
    /// 而它 stderr 里那句 `no server running on …` 恰恰是我们要的信息。
    /// 所以这里自己收流，**退出码当数据返回，不当异常**。
    public func exec(_ command: String) async throws -> CommandResult {
        let c = try requireClient()
        var out = Data()
        var err = Data()
        var exitCode: Int? = 0
        do {
            for try await chunk in try await c.executeCommandStream(command) {
                switch chunk {
                case .stdout(let b): out.append(contentsOf: b.readableBytesView)
                case .stderr(let b): err.append(contentsOf: b.readableBytesView)
                }
            }
        } catch let failure as SSHClient.CommandFailed {
            exitCode = failure.exitCode
        }
        return CommandResult(
            stdout: String(decoding: out, as: UTF8.self),
            stderr: String(decoding: err, as: UTF8.self),
            exitCode: exitCode
        )
    }

    // MARK: - 长期跟随的流（不带 PTY）

    /// 开一条**不带 PTY** 的 exec 流，用来跟随长期输出（`tail -f` 之类）。
    ///
    /// ⚠️ **别给它 PTY。** 安卓 #17：jsch 的 `ChannelExec` + `setPty(true)`
    /// 在没有数据可读时会**提前 EOF**（`sleep 25` 只读到 13 字节而通道还 connected）。
    /// Citadel 这边 `.command(...)` 模式本来就不带 PTY，所以这个坑天然躲过了 ——
    /// 但**别为了「想要彩色」给它加 PTY**，那是把安卓那个坑请回来。
    public func openStream(_ command: String) async throws -> Shell {
        try await open(mode: .exec(command))
    }

    // MARK: - 终端（带 PTY 的 shell）

    /// 开终端。
    ///
    /// - Parameter command: 开好之后要打进去的命令（比如 `tmux attach -t cc-mail`）。
    ///
    /// ⚠️⚠️ **这里比安卓多一个坑，因为 Citadel 没有「exec + PTY」的公开入口。**
    /// 安卓是 exec+PTY 直接把 `tmux attach` 当远端进程跑，于是
    ///   · 没有登录横幅（motd 一大堆还得等它打完）
    ///   · **没有时序竞态**
    /// iOS 上只能「开登录 shell + 把命令打进去」，于是 #18 原样复活：
    /// **登录 shell（starship 那种）初始化要时间，在它开始读 stdin 之前写进去的字节
    /// 会被 tty 回显然后冲掉** —— 表现是「命令回显了但没执行」，随后出现一个新提示符。
    ///
    /// 这里用的是安卓最后收敛出来的那个办法（#18 + #42）：
    /// **不用固定延时**（快了没用、慢了难受），而是「**等它先说话，然后等它安静下来**」。
    ///
    /// ⚠️ **未验证**：这台机器没有 macOS/iOS SDK，下面这段时序**没有在真设备上跑过**。
    /// 第一次在 Mac 上跑起来，优先验它。
    public func openShell(
        command: String? = nil,
        cols: Int = 80,
        rows: Int = 24,
        settleFor: Duration = .milliseconds(250),
        settleTimeout: Duration = .seconds(5)
    ) async throws -> Shell {
        let request = SSHChannelRequestEvent.PseudoTerminalRequest(
            wantReply: true,
            // 要彩色输出就得是 256color，不能是 dumb
            term: "xterm-256color",
            terminalCharacterWidth: cols,
            terminalRowHeight: rows,
            terminalPixelWidth: 0,
            terminalPixelHeight: 0,
            terminalModes: SSHTerminalModes([:])
        )
        let shell = try await open(mode: .pty(request))
        if let command {
            await shell.waitUntilSettled(quietFor: settleFor, timeout: settleTimeout)
            _ = await shell.write(command + "\n")
        }
        return shell
    }

    /// 开一条 SFTP 通道给文件模式用。一条连接上开一次，反复用。
    public func openSFTP() async throws -> SFTP {
        SFTP(try await requireClient().openSFTP())
    }

    // MARK: - 通道适配

    private enum Mode {
        case pty(SSHChannelRequestEvent.PseudoTerminalRequest)
        case exec(String)
    }

    /// 把 Citadel 的「作用域」API 适配成「拿个句柄」。
    ///
    /// ⚠️ `withPTY { inbound, outbound in … }` 一旦 closure 返回就把通道关了。
    /// 而我们的终端要活在整个 Workspace 的生命周期里（D21：换会话不重连），
    /// 所以起一个 Task 进到 closure 里，**把两头交出去之后就在里面挂着**，
    /// 直到 [Shell.close] 才放行。
    /// 这段是本文件里唯一一处「跟库的形状拧着来」的代码，改它之前先想清楚。
    private func open(mode: Mode) async throws -> Shell {
        let c = try requireClient()
        let handoff = Handoff()

        let task = Task {
            do {
                switch mode {
                case .pty(let request):
                    try await c.withPTY(request) { inbound, outbound in
                        try await handoff.serve(inbound: inbound, outbound: outbound)
                    }
                case .exec(let command):
                    try await c.withExec(command) { inbound, outbound in
                        try await handoff.serve(inbound: inbound, outbound: outbound)
                    }
                }
            } catch {
                // ⚠️ 只能 resume 一次。通道**开起来之后**才断的，不是「开失败」——
                // 那种情况 shell 已经交出去了，错误走 output 流通知，别再动这个延续。
                handoff.failIfPending(error)
            }
        }
        return try await handoff.wait(cancelling: task)
    }
}

// MARK: - Shell

extension SSHSession {

    /// 一条开着的通道。
    public final class Shell: @unchecked Sendable {

        private let writer: TTYStdinWriter
        private let state: ShellState
        /// 远端下来的字节。终端把 stdout/stderr 都画出来即可（PTY 本来就合流）；
        /// 事件流那边要分开看。
        public let output: AsyncStream<Chunk>

        init(writer: TTYStdinWriter, output: AsyncStream<Chunk>, state: ShellState) {
            self.writer = writer
            self.output = output
            self.state = state
        }

        public var isOpen: Bool { state.isOpen }

        /// 最后一次收到远端字节的时刻。应用层心跳（[Liveness]）读它。
        public var lastActivity: Date { state.lastActivity }

        /// 写失败**不抛异常**，返回 false。
        ///
        /// ⚠️ 通道断了不是异常情况，是常态（切网、远端退出、会话关闭）。
        /// 安卓那边这条是血的教训：终端控件的 resize 回调跟连接建立/断开之间有竞态，
        /// 往已关闭的通道写会抛，**异常从协程里逸出就是整个 App 崩掉**。
        @discardableResult
        public func write(_ bytes: Data) async -> Bool {
            guard state.isOpen else { return false }
            do {
                try await writer.write(ByteBuffer(bytes: bytes))
                return true
            } catch {
                return false
            }
        }

        @discardableResult
        public func write(_ text: String) async -> Bool { await write(Data(text.utf8)) }

        /// 横竖屏切换、软键盘弹出都要重发，不然远端还按老尺寸折行。
        @discardableResult
        public func resize(cols: Int, rows: Int, pixelWidth: Int = 0, pixelHeight: Int = 0) async -> Bool {
            // ⚠️ **非法尺寸会让远端的 tmux 直接退出**（安卓实测：控件首次测量给出 0）。
            // 宁可不发也不能发 0 —— 断开一个 attach 比少一次 resize 贵得多。
            guard cols > 0, rows > 0, state.isOpen else { return false }
            do {
                try await writer.changeSize(cols: cols, rows: rows, pixelWidth: pixelWidth, pixelHeight: pixelHeight)
                return true
            } catch {
                return false
            }
        }

        public func close() { state.close() }

        /// 等远端「先说话、再安静下来」。见 [SSHSession.openShell] 里 #18 那段。
        func waitUntilSettled(quietFor: Duration, timeout: Duration) async {
            _ = await withTimeout(timeout) { [state] in
                while state.lastActivity == .distantPast, state.isOpen {
                    try? await Task.sleep(for: .milliseconds(20))
                }
                while state.isOpen {
                    if Date().timeIntervalSince(state.lastActivity) >= quietFor.seconds { return }
                    try? await Task.sleep(for: .milliseconds(20))
                }
            }
        }
    }

    /// Shell 的可变状态。单独一个类，[Shell] 才能是 `let` 属性组成的。
    final class ShellState: @unchecked Sendable {
        private let lock = NSLock()
        private var _open = true
        private var _lastActivity = Date.distantPast
        private var onClose: (@Sendable () -> Void)?

        var isOpen: Bool { lock.withLock { _open } }
        var lastActivity: Date { lock.withLock { _lastActivity } }

        func touch() { lock.withLock { _lastActivity = Date() } }

        func setOnClose(_ handler: @escaping @Sendable () -> Void) {
            lock.withLock { onClose = handler }
        }

        func close() {
            let handler: (@Sendable () -> Void)? = lock.withLock {
                guard _open else { return nil }
                _open = false
                return onClose
            }
            handler?()
        }
    }

    /// [SSHSession.open] 里那套「把 closure 两头交出来」的机关。
    final class Handoff: @unchecked Sendable {
        private let lock = NSLock()
        private var continuation: CheckedContinuation<Shell, Error>?
        private var pending: Result<Shell, Error>?
        private var resumed = false

        /// ⚠️ **通道可能比调用方先到。** 那个 Task 是在 `wait()` 之前起的，
        /// 于是 `serve` 完全可能在延续被装上之前就 `resume` —— 那一下会掉进地上，
        /// 然后 `wait()` **永远挂着**（表现是「点开终端一直转圈，什么错都没有」）。
        /// 所以先来的那个结果要存下来。
        func wait(cancelling task: Task<Void, Never>) async throws -> Shell {
            try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { cont in
                    let ready: Result<Shell, Error>? = lock.withLock {
                        if let pending { return pending }
                        continuation = cont
                        return nil
                    }
                    if let ready { cont.resume(with: ready) }
                }
            } onCancel: {
                task.cancel()
            }
        }

        /// 在 Citadel 的 closure 里跑：把 writer 和输出流交出去，然后挂着不返回。
        func serve(inbound: TTYOutput, outbound: TTYStdinWriter) async throws {
            let state = ShellState()
            let (stream, continuation) = AsyncStream<Chunk>.makeStream()
            let shell = Shell(writer: outbound, output: stream, state: state)
            resume(.success(shell))

            // 关掉 shell 时把这个 closure 放行 —— 通道随之关闭。
            //
            // ⚠️ **别用「每 100 毫秒醒一次看看关了没」那种写法。** 终端一开就是几小时，
            // 那是每秒十次无谓唤醒 —— 手机上这种东西是真的会被用户看见（电量）。
            // 一条只用来 finish 的流就够了，挂在上面**零唤醒**。
            let (closed, closeSignal) = AsyncStream<Void>.makeStream()
            state.setOnClose { closeSignal.finish() }

            let pump = Task {
                do {
                    for try await item in inbound {
                        state.touch()
                        switch item {
                        case .stdout(let b): continuation.yield(.stdout(Data(b.readableBytesView)))
                        case .stderr(let b): continuation.yield(.stderr(Data(b.readableBytesView)))
                        }
                    }
                } catch {
                    // 远端非 0 退出也会走到这儿（Citadel 用异常表示退出码）。
                    // 对一条流来说这就是「结束了」，不是需要弹给用户的错误。
                }
                continuation.finish()
                state.close()   // 远端先挂断时，把上面那条挂着的路也放开
            }

            for await _ in closed {}   // 挂到 close() 为止
            pump.cancel()
            continuation.finish()
        }

        func failIfPending(_ error: Error) {
            resume(.failure(SSHSession.Failure.shellSetupFailed(underlying: error)))
        }

        private func resume(_ result: Result<Shell, Error>) {
            let cont: CheckedContinuation<Shell, Error>? = lock.withLock {
                guard !resumed else { return nil }
                resumed = true
                if continuation == nil { pending = result }
                return continuation
            }
            cont?.resume(with: result)
        }
    }
}

// MARK: - 心跳与「假活」

/// 应用层心跳。
///
/// ⚠️⚠️ **NIOSSH 和 Citadel 里没有任何 keepalive**（已 grep 过整个源码树）。
/// 安卓靠 jsch 的 `serverAliveInterval` 发现切网后的「假活」——
/// 手机从 WiFi 切 4G、进电梯，**TCP 不会立刻报错**，这条连接会看着正常很久，
/// 界面上一切如常而敲什么都没反应（#81 的核心症状）。**iOS 上没有这个机制，得自己来。**
///
/// 办法是白捡的：[SSHSession.follow] 让远端**每 20 秒吐一个空行**，
/// 于是「多久没收到字节」就是一个可靠的死活判据 —— 而且那个空行两边的解析器都会跳过。
///
/// ⚠️ **别把阈值调得太小。** 安卓 #81：终端那条 2s×2=4s 判死是对的（用户正盯着看），
/// **常驻那条照抄就是灾难** —— 手机被调度出去一下（息屏、系统限流）就丢两拍，
/// 连接被干掉而上层没人管，表现是「进工作区再返回来就连不上，重启 App 才好」。
/// iOS 的后台限制比安卓更狠，常驻那条只会更需要宽松的阈值。
public struct Liveness: Sendable {
    /// 多久没收到字节就算死。
    public let deadline: Duration

    public init(deadline: Duration) { self.deadline = deadline }

    /// 终端：用户正盯着屏幕，掉线要立刻发现、立刻重连。
    public static let terminal = Liveness(deadline: .seconds(6))
    /// 常驻（事件流 / 看板）：宁可慢，不能误杀。
    public static let background = Liveness(deadline: .seconds(45))

    public func isDead(lastActivity: Date, now: Date = Date()) -> Bool {
        now.timeIntervalSince(lastActivity) > deadline.seconds
    }
}

extension SSHSession {

    /// 包一条**跟着通道一起死**的长期跟随命令。**整段抄安卓，一个字没改** ——
    /// 它是服务器侧的 shell 技巧，跟客户端是 jsch 还是 NIOSSH 无关。
    ///
    /// ⚠️ `tail -f` 只有在**写**的时候才会因为管道断掉收到 SIGPIPE。事件流大部分时间是空的，
    /// 它永远不写，于是通道关了它还活着 —— 每次重连都在服务器上留一个僵尸 `tail`。
    ///
    /// ⚠️ **靠 stdin EOF 判断也不行**（安卓实测过：关掉通道之后远端的 `cat` 并没有 EOF，
    /// 进程照样活着）。所以改成**主动探活**：每 20 秒往 stdout 写一个空行，
    /// 通道断了这一写就会失败 → 外壳退出 → `trap` 把 tail 带走。
    ///
    /// 那个空行两边的解析器都会跳过，所以它同时还是 [Liveness] 要的**心跳**，白送的。
    public static func follow(_ command: String) -> String {
        "\(command) & __p=$!; "
            // ⚠️ 两个 trap 要分开写。合成一个 `trap 'kill …' EXIT PIPE …` 的话，
            // 收到 SIGPIPE 会执行完 handler **继续跑循环** —— tail 是杀掉了，
            // 外壳自己却永远不退，于是每次重连在服务器上留一个空壳进程。
            + "trap 'kill $__p 2>/dev/null' EXIT; "
            + "trap 'exit' PIPE HUP TERM INT; "
            // ⚠️⚠️ **必须查后台那个进程还活着没有。**
            // 少了 `kill -0` 这一句，被包起来的命令死了（tmux 太老、会话被 kill、
            // 权限不对）外壳照样每 20 秒吐心跳，通道**永远不关** ——
            // 于是上层的 `for await` 永不返回，兜底的轮询是死代码。
            // 表现最坏：待答卡片和「正在忙」永远不出现**且不报错**，
            // 服务器那头 Claude 停在等审批，手机上看起来一切正常。
            // 每 2 秒查一次活、每 10 次（20 秒）吐一次心跳 —— 心跳频率不变。
            + "__n=0; while :; do sleep 2; "
            + "kill -0 $__p 2>/dev/null || exit; "
            + "__n=$((__n+1)); "
            + "if [ $__n -ge 10 ]; then printf '\\n' || exit; __n=0; fi; done"
    }

    /// 建或接一个 tmux 会话，顺便把它调成适合手机的样子。
    ///
    /// ⚠️⚠️ **`set -g mouse on` 不能漏。** tmux 跑在 alternate screen 上，
    /// 终端控件自己的回滚缓冲是空的 —— 在 tmux 里「上滑看历史输出」
    /// **只能靠鼠标上报**（拖拽被当成鼠标事件送给 tmux，tmux 滚它自己的历史）。
    /// 漏了这一个开关，用户的现象是「怎么划都不动」，
    /// 而且**完全看不出跟 tmux 配置有关**。
    ///
    /// 其余几个抄 Moshi 逆向所得（PRD §1.2）：清掉右侧状态栏、开标题 —— 都是给手机窄屏让路。
    ///
    /// ⚠️ 没有 `setEnv("YXI_CLIENT", "1")`（安卓那边有）：**sshd 默认
    /// `PermitUserEnvironment no`，这个请求会被静默拒绝**，
    /// 也就是说安卓那行大概率一直没生效。要让主机侧知道是手机在开，
    /// 得走别的路（比如命令里带标记），别再往这儿加一个看着有用其实没用的东西。
    public static func attach(session name: String) -> String {
        let safe = name.replacingOccurrences(of: "'", with: "")
        return "tmux has-session -t '\(safe)' 2>/dev/null || tmux new-session -d -s '\(safe)'; "
            + "tmux set -g set-titles on \\; set -g mouse on \\; set -g status-right '' ; "
            // ⚠️ **`-d` 不能漏。** 不把别的客户端踢下去的话，电脑上也开着同一个会话时
            // 两边共用一块画布，tmux 按**最小的那个**排版 —— 手机一接上，
            // 桌面那边整屏花掉，手机这边也是错位的。而「电脑上开着 + 手机遥控」
            // 正是这个 app 的典型用法，触发率接近 100%。
            + "tmux attach -d -t '\(safe)'"
    }

    /// 把一行公钥装进远端的 `~/.ssh/authorized_keys`，相当于 `ssh-copy-id`（PRD §2.4 P0-14）。
    ///
    /// 幂等：已经有同一行就不重复追加。权限也一并修对 ——
    /// **`.ssh` 必须 700、`authorized_keys` 必须 600，否则 sshd 会拒绝使用它**
    /// （这是「装了公钥还是要密码」最常见的原因）。
    ///
    /// ⚠️⚠️ **数的是「我这一行」，不是「所有 yxi 的行」。**
    /// 安卓那版最后 `grep -c -F 'yxi@android'`，而**每台安卓设备写的注释都是这个词** ——
    /// 于是开发脚本按注释过滤时把用户真手机的钥匙删了（#65，全项目最贵的一次事故）。
    /// 这里注释由公钥自己派生（[KeyIdentity.comment]），且这条命令只认完整那一行。
    public func installPublicKey(_ line: String) async throws -> CommandResult {
        // 公钥行里本不该有单引号，去掉防注入
        let safe = line.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "'", with: "")
        return try await exec(
            "mkdir -p ~/.ssh && chmod 700 ~/.ssh && "
                + "touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && "
                + "grep -qxF '\(safe)' ~/.ssh/authorized_keys || echo '\(safe)' >> ~/.ssh/authorized_keys; "
                + "grep -c -x -F '\(safe)' ~/.ssh/authorized_keys"
        )
    }
}


extension Duration {
    /// `Duration` 没有现成的秒数出口，而我们两处都要拿它跟 `Date` 的间隔比。
    var seconds: TimeInterval {
        TimeInterval(components.seconds) + TimeInterval(components.attoseconds) / 1e18
    }
}
