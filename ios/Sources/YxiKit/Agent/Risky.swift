import Foundation

/// 一次待批准的操作**危不危险**。
///
/// ponytail: 纯启发式正则，宁可多问一次；漏了就往下加词。
/// 故意**不含**裸 `git push` / `deploy`（太常见，会天天挡）。
public enum Risky {

    /// 命令本身危险的那些。
    /// ⚠️ `(?i)` 后面整段用 `(?: )` 包起来 —— 不包的话，忽略大小写到底管不管
    /// 后面每一个 `|` 分支，要看引擎脾气；包上就没得猜了。
    private static let commands = try! Regex(#"(?i)(?:rm\s+-[rf]|rm\s+-rf|--force\b|force[-\s]?push|--hard\b|reset\s+--hard|drop\s+table|truncate\s+table|dangerously-skip|sudo\s+rm|mkfs|>\s*/dev/|chmod\s+-R|chown\s+-R|kubectl\s+delete|docker\s+system\s+prune|:\s*>|shutdown|reboot)"#)

    /// 命中就该多一道确认（指纹 / 生物识别）。
    public static func matches(_ text: String) -> Bool {
        !text.isBlank && text.contains(commands)
    }

    /// **动到「以后还能不能连上这台机器」的东西** —— 比命令危险更该拦。
    ///
    /// ⚠️ 真踩过：一个脚本把用户手机的公钥从 `authorized_keys` 里删掉了（安卓 #65）。
    /// 那类操作最不该在锁屏上随手批 —— 批错了，你连补救都进不去。
    private static let paths = try! Regex(#"(?i)(?:authorized_keys|/\.ssh/|known_hosts|/etc/(?:ssh|sudoers|passwd|shadow)|\.claude/settings|\.credentials\.json|/etc/nginx|systemd/system)"#)

    /// 这一条**能不能在通知上直接批**。
    ///
    /// ⚠️ 防的是「批准疲劳」：原来只防「提示变了」（安卓 #53 加的指纹校验），
    /// 那是**机器侧的过期**；没防**人麻木了**。锁屏上连点几次「批准」是肌肉记忆，
    /// 而攻击者 / 事故靠的从来不是绕过校验，是**你正忙着，顺手点了**。
    ///
    /// 危险的那些**不给按钮**，只给「去看看」—— 强制看到内容再决定。
    /// 这不是不信任用户，是不给「没看清就批了」这件事留路径。
    public static func oneTapOk(tool: String, arg: String) -> Bool {
        let text = "\(tool) \(arg)"
        return !matches(text) && !text.contains(paths)
    }
}
