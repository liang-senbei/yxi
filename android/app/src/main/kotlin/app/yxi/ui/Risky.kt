package app.yxi.ui

/**
 * 一次审批**危不危险** —— 命中就该多一道确认（指纹）。
 * ponytail: 纯启发式正则，宁可多问一次；漏了就往下加词。故意不含裸 `git push`/`deploy`（太常见会天天挡）。
 */
object Risky {
    private val P = Regex(
        "rm\\s+-[rf]|rm\\s+-rf|--force\\b|force[-\\s]?push|--hard\\b|reset\\s+--hard|" +
            "drop\\s+table|truncate\\s+table|dangerously-skip|sudo\\s+rm|mkfs|>\\s*/dev/|" +
            "chmod\\s+-R|chown\\s+-R|kubectl\\s+delete|docker\\s+system\\s+prune|:\\s*>|shutdown|reboot",
        RegexOption.IGNORE_CASE,
    )
    fun matches(text: String): Boolean = text.isNotBlank() && P.containsMatchIn(text)

    /**
     * **动到「以后还能不能连上这台机器」的东西** —— 比命令危险更该拦。
     *
     * ⚠️ 真踩过：一个脚本把用户手机的公钥从 `authorized_keys` 里删掉了（#65）。
     * 那类操作最不该在锁屏上随手批 —— 批错了，你连补救都进不去。
     */
    private val PATHS = Regex(
        "authorized_keys|/\\.ssh/|known_hosts|/etc/(ssh|sudoers|passwd|shadow)|" +
            "\\.claude/settings|\\.credentials\\.json|/etc/nginx|systemd/system",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 这一条**能不能在通知上直接批**。
     *
     * ⚠️ 防的是「批准疲劳」：我们原来只防「提示变了」（#53 加指纹校验），
     * 那是**机器侧的过期**；没防**人麻木了**。锁屏上连点几次「批准」是肌肉记忆，
     * 而攻击者/事故靠的从来不是绕过校验，是**你正忙着，顺手点了**。
     *
     * 危险的那些**不给按钮**，只给「去看看」——强制看到内容再决定。
     * 这不是不信任用户，是不给「没看清就批了」这件事留路径。
     */
    fun oneTapOk(tool: String, arg: String): Boolean {
        val text = "$tool $arg"
        return !matches(text) && !PATHS.containsMatchIn(text)
    }
}
