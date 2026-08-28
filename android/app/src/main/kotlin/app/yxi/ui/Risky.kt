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
}
