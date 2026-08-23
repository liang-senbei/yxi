import Citadel
import Foundation
import NIOCore

/// 把异常翻译成**用户能照着做点什么**的话。
///
/// ⚠️ 这不是「顺手做的润色」，是安卓那边花了好几天换来的一条纪律：
///
/// · **#27**：用户在真机上报 `UnknownHostException: 天亮`。代码没错 ——
///   「天亮」是他在笔电 `~/.ssh/config` 里起的 SSH 别名，而**手机上没有那个文件**。
///   错在表单标签写着「主机名 / IP」，那个词在**邀请**用户填别名。
///   教训：**能被误填的字段，标签就得写成没法误填的样子；报错要说「怎么办」不是说「是什么」。**
///
/// · **#71**：错误信息报的是用户起的**名字**而不是真正连的地址，
///   把排查整个带进了端口/防火墙的死胡同 —— 而 App 从来没连过那个 IP。
///   所以下面每个函数都强制要求传 `target`（`user@host:port`），**不给别名留位置**。
///
/// · **#58**：「地址解析不了」的真正原因多半是**全角字符**，用户盯着看觉得是对的。
///   所以解析失败时要**指出是哪一个字**。
///
/// · **#66**：密码打错几次之后 fail2ban 会封整个 IP，
///   症状从「认证失败」变成「连接超时」—— **换了一副面孔**，最容易误判成 App 的 bug。
public enum Explain {

    /// 连接/认证阶段的失败。
    /// - Parameter target: 真正连的目标（`user@host:port`）。**别传别名。**
    public static func connection(_ error: Error, target: String, hostname: String) -> String {
        // 指纹变了 —— 这条最要紧，得说清楚下一步该干什么
        if case KnownHosts.Rejection.fingerprintChanged(_, let expected, let got) = error {
            return """
            \(target) 的主机指纹变了，已拒绝连接。
            记着的：\(expected)
            这次的：\(got)
            服务器真重装过就在主机列表里删掉这条再重加；否则**不要**继续。
            """
        }
        if case KnownHosts.Rejection.notTrusted = error {
            return "没有确认信任 \(target)，已断开。"
        }

        // 认证失败
        if error is AuthenticationFailed || isAuthFailure(error) {
            return """
            \(target) 认证被拒。检查用户名、密码/公钥是否对得上。
            ⚠️ 连着试错几次之后，服务器上的 fail2ban 可能把这个 IP 封掉 ——
            那时症状会从「认证失败」变成「连不上/超时」，别以为是网络坏了。
            """
        }

        // 地址解析不了：多半是全角或零宽字符（#58）
        let text = String(describing: error).lowercased()
        if text.contains("nameresolution") || text.contains("unknownhost")
            || text.contains("nodename") || text.contains("hostname") && text.contains("not") {
            var msg = "解析不了这个地址：\(hostname)。这一栏要填 **IP 或真实域名**，"
                + "SSH 别名（`~/.ssh/config` 里那种）在手机上不解析。"
            if let bad = HostInput.suspiciousCharacter(in: hostname) {
                msg += "\n地址里有个连不上的字符：\(bad)"
            }
            return msg
        }

        // 超时 / 拒绝
        if text.contains("timeout") || text.contains("timedout") {
            return """
            连 \(target) 超时（包发出去了没人应）。常见的三种：
            · 移动网络下 22 端口出站被运营商挡了 —— 换到备用端口试试
            · 服务器上的 fail2ban 把这个 IP 封了（#66）
            · 那台机器不在线
            """
        }
        if text.contains("refused") {
            return "\(target) 拒绝连接：端口通到了机器，但那个端口上没有 sshd 在听。端口号填对了吗？"
        }

        return "连 \(target) 失败：\(error)"
    }

    /// 文件模式（SFTP）的失败。
    public static func sftp(_ error: Error) -> String {
        if case SFTPError.errorStatus(let status) = error {
            switch status.errorCode {
            case .noSuchFile: return "没有这个文件或目录"
            case .permissionDenied: return "没有权限"
            default: return "SFTP 出错：\(status.errorCode)"
            }
        }
        return "\(error)"
    }

    private static func isAuthFailure(_ error: Error) -> Bool {
        if case SSHClientError.allAuthenticationOptionsFailed = error { return true }
        return false
    }
}

/// `Task` 取消**不是失败**，别把它显示给用户。
///
/// ⚠️⚠️ 安卓上同一个错**前后踩了五次**（TROUBLESHOOTING #78 / #79）：
/// `runCatching` 捕获的是 `Throwable`，**包括协程的取消信号**。
/// 于是「用户切个页面 → effect 被取消 → 取消异常被当成失败 →
/// 界面上留下一句『连不上：JobCancellationException』**而且永远不消失**」。
/// 每次表现都不一样：「终端起不来」「连不上」「去不了这个目录」。
///
/// Swift 里对应的写法是 `try? await …` 和 `Result { try await … }` ——
/// **`CancellationException` 换了个名字叫 `CancellationError`，坑一模一样。**
///
/// 规矩：**凡是「失败要显示给用户」的地方，一律用这个。**
@inlinable
public func catching<T>(_ work: () async throws -> T) async rethrows -> Result<T, Error> {
    do {
        return .success(try await work())
    } catch is CancellationError {
        throw CancellationError()
    } catch {
        return .failure(error)
    }
}
