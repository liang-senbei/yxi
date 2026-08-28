import Foundation
import SwiftUI
import YxiKit

// ⚠️ 本文件在这台 Linux 上**一行都没编过**（它 import SwiftUI）。
// 凡是没把握的地方都标了「⚠️ 未验证」。

/// 首连确认的弹窗中转。
///
/// ⚠️ `HostKeyGate` 在 NIO 的 event loop 上被调用、要一个 `async` 答复，
/// 而答复只能来自界面。这个类就是这条缝：把 continuation 存住，
/// 用户点了再兑现。**指纹变了根本走不到这里**（`KnownHosts` 直接拒）。
@MainActor
final class TrustGate: ObservableObject, @unchecked Sendable, TrustPrompt {

    struct Ask: Identifiable {
        let id = UUID()
        let target: String
        let fingerprint: String
    }

    @Published var pending: Ask?
    private var answer: CheckedContinuation<Bool, Never>?

    nonisolated func confirmNewHost(target: String, fingerprint: String) async -> Bool {
        await withCheckedContinuation { cont in
            Task { @MainActor in
                // 上一个还没答完就又来一个 —— 保守拒掉旧的，不叠弹窗
                self.answer?.resume(returning: false)
                self.answer = cont
                self.pending = Ask(target: target, fingerprint: fingerprint)
            }
        }
    }

    func resolve(_ trusted: Bool) {
        answer?.resume(returning: trusted)
        answer = nil
        pending = nil
    }
}

/// 一台主机那条**活着的**连接，连同挂在它上面的服务和终端。
///
/// ⚠️⚠️ **它由 [RootView] 按 `host.id` 缓存，不由任何页面持有。**
/// 安卓 #75：连接建在 SessionsScreen 里，切一次标签页就重连一次 ——
/// TCP + 握手 + ed25519 认证，实测约 3 秒。**连接跟着主机活，不跟着界面活。**
///
/// [terminal] 放在这里也是同一个理由（ios-chat 提的）：它装着终端控件实例和读循环，
/// 挂在页面上的话切到对话模式屏幕内容就没了；更糟的是读循环死了而通道还开着、
/// tmux 还在吐数据，缓冲塞满之后**整条连接上所有通道一起卡死**。
@MainActor
final class HostLink: ObservableObject {

    let host: Host
    /// 终端的仿真器 + 读循环。跟连接同寿命。
    let terminal = TerminalSession()
    @Published private(set) var link: Link

    private let keys: KeyManager
    private let store: HostStore
    private let gate: TrustGate
    private var ssh: SSHSession?
    private var supervisor: Task<Void, Never>?

    init(host: Host, keys: KeyManager, store: HostStore, gate: TrustGate) {
        self.host = host
        self.keys = keys
        self.store = store
        self.gate = gate
        self.link = Link(id: UUID(), service: nil, error: nil, retry: {})
        start()
    }

    deinit { supervisor?.cancel() }

    /// 手动重连：把退避从头算起。用户刚把网切回来时不该干等最长 15 秒。
    func retry() { start() }

    /// 一次最便宜的往返，带自己的超时。
    ///
    /// ⚠️ **必须有超时。** 半开的 TCP 上 `exec` 会一直挂着不返回也不报错 ——
    /// 那正是我们要抓的那种「假活」，没有超时的话这个探活自己先卡死了。
    private static func roundTripOK(_ session: SSHSession) async -> Bool {
        await withTaskGroup(of: Bool.self) { group in
            group.addTask { (try? await session.exec(":")) != nil }
            group.addTask {
                try? await Task.sleep(for: .seconds(8))
                return false
            }
            let first = await group.next() ?? false
            group.cancelAll()
            return first
        }
    }

    /// ⚠️ **连不上要自动重试**（安卓 #79）。此前失败一次就把「连不上」钉死在界面上、
    /// 再也不会自己清 —— 手机上网络时断时续，等于把一次抖动变成一次永久故障。
    ///
    /// ⚠️ **连上之后也要一直守着**（安卓 #81）。`rememberHostSession` 那版连上就 return，
    /// 之后连接死了没人管，表现是「进工作区再返回来就连不上，只能重启 App」。
    private func start() {
        supervisor?.cancel()
        supervisor = Task { [weak self] in
            var delay = Duration.seconds(1)
            while !Task.isCancelled {
                guard let self else { return }
                do {
                    let session = try await self.connect()
                    self.ssh = session
                    self.publish(.init(id: UUID(), service: RemoteHost(ssh: session), error: nil,
                                       retry: { [weak self] in self?.retry() }))
                    delay = .seconds(1)
                    // 守着。掉了就往下走去重连。
                    //
                    // ⚠️⚠️ **不能只看 `isConnected`。** 手机切网（WiFi→流量、进电梯）之后
                    // TCP 是**半开**的：本地这头的 channel 仍然 active，`isConnected`
                    // 一直是 true，于是「连接断了」永远不显示、也永远不重连 ——
                    // 用户看到的是「点什么都没反应，也不报错」（安卓 #81 就是这个）。
                    //
                    // 所以隔一阵子**主动打一次最便宜的往返**（`:` 是 shell 内建的空命令）。
                    // 打不通 = 真死了，往下走重连。
                    // ⚠️ 用 `background` 那档（45 秒）不是 `terminal`：宁可慢一点，
                    // 也不能在网络抖一下的时候误杀一条好连接。
                    var lastOK = Date()
                    while session.isConnected, !Task.isCancelled {
                        try? await Task.sleep(for: .seconds(3))
                        guard Liveness.background.isDead(lastActivity: lastOK) else { continue }
                        if await Self.roundTripOK(session) { lastOK = Date() } else { break }
                    }
                    self.terminal.detach()
                    self.publish(.init(id: UUID(), service: nil, error: "连接断了，正在重连…",
                                       retry: { [weak self] in self?.retry() }))
                } catch is CancellationError {
                    return
                } catch {
                    // ⚠️ 指纹变了**不重试** —— 那不是网络抖动，是要用户拿主意的事。
                    // 一直重试只会把那句吓人的话每秒刷一遍。
                    let fatal = (error as? KnownHosts.Rejection).map {
                        if case .fingerprintChanged = $0 { return true } else { return false }
                    } ?? false
                    self.publish(.init(
                        id: UUID(), service: nil,
                        error: Explain.connection(error, target: self.host.display, hostname: self.host.hostname),
                        retry: { [weak self] in self?.retry() }
                    ))
                    if fatal { return }
                }
                try? await Task.sleep(for: delay)
                delay = min(delay * 2, .seconds(15))
            }
        }
    }

    private func publish(_ l: Link) { link = l }

    private func connect() async throws -> SSHSession {
        let id = host.id
        guard let cfg = host.config(
            privateKey: { try? self.keys.privateKey() },
            unseal: { Vault.open($0) }
        ) else {
            throw MissingCredentials(target: host.display)
        }
        let gate = HostKeyGate(
            target: cfg.target,
            stored: { [store] in store.get(id)?.hostKey },
            remember: { [store] key in
                guard var h = store.get(id) else { return }
                h.hostKey = key
                store.upsert(h)
            },
            prompt: self.gate
        )
        let session = SSHSession(config: cfg, gate: gate)
        try await session.connect()
        return session
    }
}

/// 认证信息一条都没有。**不是网络问题，别让它进重试循环。**
struct MissingCredentials: LocalizedError {
    let target: String
    var errorDescription: String? { "\(target) 还没填密码，也没装公钥 —— 去主机页补一个。" }
}

/// 把 `YxiKit` 的命令串和解析器真的跑在一条连接上。
///
/// ⚠️ **这一层只搬运，不做业务判断。** 命令怎么写、输出怎么解析全在
/// `YxiKit.SessionProbe` / `Usage` / `TranscriptStream` 里 —— 那边是纯函数，
/// **Linux 上有测试盯着**；搬到这里就再也测不到了。
struct RemoteHost: ChatBackend, UsageService, ShellRunner, FileService {

    let ssh: SSHSession

    // MARK: SessionService

    func snapshot() async throws -> [BoardSession] {
        SessionProbe.parseSnapshot(try await ssh.exec(SessionProbe.snapshotScript).stdout)
    }

    func peek(session: String, lines: Int) async throws -> String {
        try await ssh.exec(SessionProbe.peekCommand(target: session, lines: lines)).stdout
    }

    func send(session: String, text: String) async throws {
        // ⚠️ 两条，不能合成一条：文本里含 `Enter` 这类词会被 send-keys 当按键名解析
        for cmd in SessionProbe.sendCommands(target: session, text: text) {
            _ = try await ssh.exec(cmd)
        }
    }

    func kill(session: String) async throws {
        _ = try await ssh.exec("tmux kill-session -t '\(session.replacingOccurrences(of: "'", with: ""))'")
    }

    // MARK: ShellRunner

    func run(_ command: String) async throws -> String {
        try await ssh.exec(command).stdout
    }

    // MARK: UsageService

    /// ⚠️ 探不到返回 nil，界面整块藏掉。**绝不返回一个 0** —— 额度这种数字
    /// 你会照着它安排今天开不开大活，一个假的 0 比看不见糟得多。
    func probe() async -> Usage? {
        guard let out = try? await ssh.exec(Usage.probeCommand).stdout else { return nil }
        return Usage.parse(out)
    }

    // MARK: ChatBackend

    func latestTranscript(cwd: String) async throws -> String? {
        TranscriptStream.parseLatest(try await ssh.exec(TranscriptStream.latestCommand(cwd: cwd)).stdout)
    }

    /// ⚠️ **不在这里加节流。** 界面那边的 `ChatModel.flush` 已经带尾随刷新了，
    /// 两层叠起来丢的还是最后一批（安卓 #35：忙的会话正常、闲的会话永远空白）。
    ///
    /// ⚠️ 命令要用 `SSHSession.follow` 包一层：`tail -f` 只有在**写**的时候才会
    /// 因为管道断掉收到 SIGPIPE，而转录大部分时间是空的 ——
    /// 不包的话每次重连都在服务器上留一个僵尸 tail。
    func transcriptLines(file: String, backlog: Int) -> AsyncStream<String> {
        AsyncStream { continuation in
            let task = Task {
                guard let shell = try? await ssh.openStream(
                    SSHSession.follow(TranscriptStream.followCommand(file: file, backlog: backlog))
                ) else { return continuation.finish() }
                var buffer = Data()
                for await chunk in shell.output {
                    buffer.append(chunk.bytes)
                    // 按行切。⚠️ 一条 SSH 读回来的块**不保证落在行边界上**，
                    // 半行留在 buffer 里等下一块 —— 不这么做的话 JSON 会被从中间劈开。
                    while let nl = buffer.firstIndex(of: 0x0A) {
                        let line = buffer[buffer.startIndex..<nl]
                        buffer.removeSubrange(buffer.startIndex...nl)
                        continuation.yield(String(decoding: line, as: UTF8.self))
                    }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func screenStream(session: String, lines: Int) -> AsyncStream<String> {
        AsyncStream { continuation in
            let task = Task {
                guard let shell = try? await ssh.openStream(SSHSession.follow(
                    SessionProbe.watchScreenCommand(target: session, lines: lines))
                ) else { return continuation.finish() }
                var buffer = ""
                let marker = "\n" + SessionProbe.screenMarker + "\n"
                for await chunk in shell.output {
                    buffer += String(decoding: chunk.bytes, as: UTF8.self)
                    // 一屏一屏地切。⚠️ 跟 transcriptLines 一样，SSH 的块不落在边界上 ——
                    // 半屏留着等下一块，否则会把屏幕从中间劈开，解析出半个待答框
                    while let r = buffer.range(of: marker) {
                        continuation.yield(String(buffer[buffer.startIndex..<r.lowerBound]))
                        buffer.removeSubrange(buffer.startIndex..<r.upperBound)
                    }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func sendKey(session: String, key: String) async throws {
        // 白名单在 YxiKit 里，不在白名单返回 nil —— 这里什么都不做
        guard let cmd = SessionProbe.keyCommand(target: session, key: key) else { return }
        _ = try await ssh.exec(cmd)
    }

    // MARK: FileService

    func listDir(_ path: String) async throws -> [SFTP.Entry] {
        let sftp = try await ssh.openSFTP()
        defer { Task { await sftp.close() } }
        return try await sftp.list(path)
    }

    func readFile(_ path: String, max: Int) async throws -> Data {
        let sftp = try await ssh.openSFTP()
        defer { Task { await sftp.close() } }
        return try await sftp.read(path, max: max)
    }

    func resolve(_ path: String) async throws -> String {
        let sftp = try await ssh.openSFTP()
        defer { Task { await sftp.close() } }
        return try await sftp.realpath(path)
    }

    func writeFile(_ path: String, bytes: Data) async throws {
        let sftp = try await ssh.openSFTP()
        defer { Task { await sftp.close() } }
        try await sftp.write(path, bytes: bytes)
    }

    // MARK: 附件（PRD 附录 F.1）

    func upload(session: String, fileName: String, data: Data, isImage: Bool) async throws -> Staged {
        let project = session.hasPrefix("cc-") ? String(session.dropFirst(3)) : session
        let dir = "/root/src/tmp/\(project)"
        let safe = fileName.replacingOccurrences(of: "/", with: "_")
        let path = "\(dir)/\(Int(Date().timeIntervalSince1970))-\(safe)"
        let sftp = try await ssh.openSFTP()
        defer { Task { await sftp.close() } }
        try await sftp.mkdirs(dir)
        try await sftp.write(path, bytes: data)
        return Staged(label: isImage ? "图片" : "附件", remotePath: path, isImage: isImage)
    }

    /// ⚠️ **只贴路径，不把内容塞进对话。** 让那边的 Claude 自己去 Read ——
    /// 一张截图的 base64 会把上下文撑爆，而它本来就有读文件的能力（安卓 #52）。
    nonisolated func attachmentHeader(_ staged: [Staged]) -> String {
        guard !staged.isEmpty else { return "" }
        var imageN = 0, fileN = 0
        return staged.map { s -> String in
            if s.isImage { imageN += 1; return "[图片\(imageN)] \(s.remotePath)" }
            fileN += 1
            return "[附件\(fileN)] \(s.remotePath)"
        }.joined(separator: "\n") + "\n"
    }
}

/// 一键装公钥。**自己开一条密码连接**，不复用那条活着的 —— 活着的那条要么已经免密了、
/// 要么根本连不上（正是要装公钥的时候）。
struct PasswordInstaller: KeyInstalling {
    let keys: KeyManager
    let store: HostStore
    let gate: TrustGate

    func installPublicKey(host: Host, password: String) async throws -> Int {
        let cfg = HostConfig(alias: host.alias, hostname: host.hostname, port: host.port,
                             username: host.username, auth: .password(password))
        let id = host.id
        let session = SSHSession(config: cfg, gate: HostKeyGate(
            target: cfg.target,
            stored: { store.get(id)?.hostKey },
            remember: { key in
                guard var h = store.get(id) else { return }
                h.hostKey = key
                store.upsert(h)
            },
            prompt: gate
        ))
        try await session.connect()
        defer { Task { await session.disconnect() } }
        let out = try await session.installPublicKey(try keys.identity().authorizedKeysLine)
        return Int(out.stdout.trimmingCharacters(in: .whitespacesAndNewlines)) ?? 0
    }
}
