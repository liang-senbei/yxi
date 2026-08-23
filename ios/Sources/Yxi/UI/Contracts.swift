import Foundation
import YxiKit

// ============================================================================
// 界面和数据层之间的**唯一**约定面。
//
// ⚠️ 值类型（`BoardSession` / `SessionState` / `Usage` / `Host` / `HostInput`…）
// 全在 `YxiKit` 里，**这里一个都不重复定义** —— 重复一份就等于埋一个「两边慢慢漂开」的雷。
// 这个文件只剩三个 protocol 和一个 `Link`：都是「把界面和那条活着的 SSH 连接隔开」的缝。
//
// ⚠️ **视图全是纯值 + 闭包**，不吃 ObservableObject、不吃泛型：
// 安卓那版就是这个形状（`SessionsScreen(ssh:connectError:onRetry:…)`），
// 跑过一轮真机验证，照搬省得再踩一遍。
// ============================================================================

/// 会话看板要的四件事，**一台主机一个实例**。
///
/// `YxiKit.SessionProbe` 提供的是命令串和解析器（纯函数），这一层负责把它们
/// 真的跑在那条连接上。分开是为了让界面不用抱着一个 `SSHSession` 才能预览。
///
/// ⚠️ `snapshot()` 必须是**一次 SSH 往返拿全部**，不是一个会话一个请求
/// （抄 Moshi，PRD §1.2）—— 手机网络下往返成本高，20 个会话 20 次往返会卡死。
public protocol SessionService {
    func snapshot() async throws -> [BoardSession]
    /// `tmux capture-pane`，给悬浮卡片当缩略图
    func peek(session: String, lines: Int) async throws -> String
    /// `tmux send-keys` —— **不用先 attach**，这是我们比 Moshi 强的地方（PRD §1.6）
    func send(session: String, text: String) async throws
    /// `tmux kill-session`。⚠️ 不可逆，界面上必须长按 + 二次确认才准调
    func kill(session: String) async throws
}

public protocol UsageService {
    /// ⚠️ **探不到 `ccusage` 必须返回 nil。**
    /// 界面会把整块藏掉：不显示 0、不显示「未知」、不画空进度条。
    /// 额度这种数字你会照着它安排今天开不开大活 —— 一个假的 0 比看不见糟得多。
    func probe() async -> Usage?
}

public protocol KeyInstalling {
    /// 用密码连一次，把公钥追加进 `~/.ssh/authorized_keys`，之后免密（等价 `ssh-copy-id`）。
    /// - Returns: 装完之后 `authorized_keys` 里现有几行 yxi 公钥
    func installPublicKey(host: Host, password: String) async throws -> Int
}

/// 一台主机当前那条连接的状态，**由外层（持有连接的那一层）传进界面**。
///
/// ⚠️ 连接要挂在标签页切换**之上**：挂在页面里的话切走再切回来页面重建，
/// TCP + 握手 + ed25519 认证再来一遍（安卓实测 ~3 秒）。
/// 连接跟着**主机**活，不跟着界面活（TROUBLESHOOTING #75）。
public struct Link {
    /// ⚠️ **每次连接状态变化都要换一个新的 UUID**（连上 / 断开 / 换主机都算）。
    /// 界面用 `.task(id:)` 重启轮询循环 —— id 不变，循环就不重启。
    public let id: UUID
    /// 连上了就非 nil
    public let service: (any SessionService)?
    /// 连不上时的人话原因
    public let error: String?
    /// **手动重连。** 自动重连是指数退避的，最长要等 15 秒；
    /// 用户刚把网切回来时不该干等 —— 下拉刷新会调它，顺便把退避从头算起。
    public let retry: () -> Void

    public init(id: UUID, service: (any SessionService)?, error: String?, retry: @escaping () -> Void) {
        self.id = id; self.service = service; self.error = error; self.retry = retry
    }

    public var isConnected: Bool { service != nil }
}
