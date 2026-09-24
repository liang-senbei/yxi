package app.yxi.desktop

/** User-reviewed fields only; no inference that a transcript action actually completed. */
internal data class RunnerHandoffSummary(
    val requirements: String = "", val completed: String = "", val remaining: String = "",
    val files: String = "", val assumptions: String = "", val risks: String = "",
    val sourceRange: String,
) {
    fun render(): String {
        require(sourceRange.isNotBlank() && sourceRange.length <= 1000 && sourceRange.none { it == '\u0000' }) { "请说明交接摘要覆盖的历史范围" }
        val sections = listOf("用户要求" to requirements, "已完成事项" to completed, "未完成事项" to remaining,
            "关键文件" to files, "假设" to assumptions, "风险与限制" to risks)
        require(sections.any { it.second.isNotBlank() }) { "请先填写或审阅交接摘要" }
        require(sections.all { it.second.length <= 16000 && '\u0000' !in it.second }) { "摘要过长或包含无效字符" }
        val result = buildString {
            append("以下是用户审阅的前序对话交接摘要。它是历史上下文，不代表新运行器已完成这些工作；附件未自动转移。\n\n")
            append("历史范围：").append(sourceRange.trim()).append("\n")
            sections.forEach { (title, content) ->
                append("\n## ").append(title).append("\n").append(content.trim().ifBlank { "未填写" }).append("\n")
            }
        }
        require(result.length <= 100000) { "交接摘要超过单条指令长度限制" }
        return result
    }
}
