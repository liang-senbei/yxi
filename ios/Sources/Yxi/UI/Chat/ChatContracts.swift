import Foundation
import YxiKit

// 对话模式和终端，**在 `Yxi/UI/Contracts.swift` 之外还需要的那点东西**。
//
// ⚠️ 渲染用的值类型（`ChatItem` / `ToolCall` / `JSON` / `Live` / `Pending`）**不在这里** ——
// 它们住在 `YxiKit/Agent/Model.swift`。理由：Yxi 依赖 YxiKit、反过来不成立，
// 而解析器在 YxiKit 里，它没法产出定义在 Yxi 里的类型。这个文件只放
// **界面对数据层提的要求**（protocol），跟 `Contracts.swift` 里那几个 Service 同一个性质。
//
// ⚠️ 终端那条 PTY 通道**没有再包一层 protocol**：`YxiKit.SSHSession.Shell` 已经正好是
// 那个形状（`write(Data)` / `resize(cols:rows:)` / `output: AsyncStream<Chunk>`），
// 再抽一个只有一个实现的 protocol 是白写。[TerminalSession] 直接吃 `Shell`。

// MARK: - 附件

/// 已经传上去、等着贴进消息正文的附件。
///
/// ⚠️ 附件**不进对话内容，走路径映射**（TROUBLESHOOTING #52）：
/// 正文前面贴一行「图片1 = /root/src/tmp/…/xxx.png」，Claude 自己去读那个文件。
/// 好处是不受消息长度限制，也不用把图片编码进文本。
public struct Staged: Equatable, Identifiable, Sendable {
    /// 界面上那个小药丸显示的名字，如「图片1」
    public let label: String
    public let remotePath: String
    public let isImage: Bool
    public var id: String { remotePath }
    public init(label: String, remotePath: String, isImage: Bool) {
        self.label = label; self.remotePath = remotePath; self.isImage = isImage
    }
}

// MARK: - 对话模式要数据层做的事

/// 对话模式要的一切。**一个实例对应一台主机上的一条活着的 SSH 连接。**
///
/// 继承 `SessionService` 是故意的：`send`（打字进会话）和 `peek`（抓屏）看板那边
/// 已经要了一模一样的两件事，**同一件事只该有一个方法**。
///
/// ⚠️ 连接的生命周期归 Workspace，不归这些视图：切「终端 / 对话 / 文件」时视图会
/// 销毁重建，而连接必须原样活着（TROUBLESHOOTING #75：挂在页面里的话切走再切回来
/// 就是 TCP + 握手 + ed25519 认证重来一遍，安卓实测 ~3 秒）。
protocol ChatBackend: SessionService, Sendable {

    /// 找这个 cwd 对应的最新转录文件。命令和解析 `YxiKit.TranscriptStream` 都给好了，
    /// 这里只负责跑 SSH。
    ///
    /// 返回 nil = 这个会话里没跑过 Claude Code，对话模式该置灰**并说明原因** ——
    /// 灰着不说话最气人。
    func latestTranscript(cwd: String) async throws -> String?

    /// `tail -n backlog -f` 那个文件，一行一个元素。
    ///
    /// ⚠️ 它**先一次性把历史吐完**（几百行落在同一瞬间），然后才阻塞等新行。
    /// 界面那边的节流已经带尾随刷新了（[ChatModel.flush]），
    /// **数据层不要再加一层节流** —— 两层节流叠起来，丢的还是最后一批（#35）。
    func transcriptLines(file: String, backlog: Int) -> AsyncStream<String>

    /// 送一个**按键**（不是一行文本，不带回车）。
    ///
    /// 实测过的协议（TROUBLESHOOTING #29）：
    ///   · 单选：送数字 → **直接选中并确认**，不用再送 Enter
    ///   · 多选：送数字 → 切换勾选；`Right` → 跳到 Submit 页；再送 `1` → 提交
    ///
    /// ⚠️ 实现里要有白名单（`^([0-9]{1,2}|Up|Down|Left|Right|Enter|Escape)$`）——
    /// 这个参数会原样变成打进别人服务器的按键。
    func sendKey(session: String, key: String) async throws

    /// 传一个附件到暂存区（PRD 附录 F.1 的路径规则）。
    func upload(session: String, fileName: String, data: Data, isImage: Bool) async throws -> Staged

    /// 把 staged 拼成贴在正文最前面的那几行。
    /// 在数据层是因为路径规则是**服务器侧约定**，不是界面的事。
    nonisolated func attachmentHeader(_ staged: [Staged]) -> String
}
