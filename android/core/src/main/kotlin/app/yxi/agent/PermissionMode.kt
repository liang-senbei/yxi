package app.yxi.agent

/** Native mode IDs are distinct from sandbox configuration. */
enum class PermissionMode(val nativeId: String, val title: String, val description: String) {
    Manual("manual", "请求批准", "需要权限时由你确认"),
    Auto("auto", "自动审批", "由运行器评估操作并请求必要的确认"),
    Bypass("bypassPermissions", "完全访问", "跳过一般权限审批，仍遵守运行器限制"),
    Plan("plan", "计划模式", "先分析和制定计划"),
    Edits("acceptEdits", "允许编辑", "自动允许文件编辑，其他操作按需确认"),
    DontAsk("dontAsk", "仅预授权", "拒绝需要额外批准的操作");

    companion object {
        fun fromScreen(screen: String): PermissionMode? {
            val lines = screen.lines()
            val prompt = lines.indexOfLast { it.trimStart().startsWith("❯") }
            if (prompt < 0) return null
            val footer = lines.drop(prompt + 1).joinToString(" ").replace(Regex("\\s+"), " ")
            val states = listOf(
                "manual mode on" to Manual, "auto mode on" to Auto,
                "bypass permissions on" to Bypass, "plan mode on" to Plan,
                "accept edits on" to Edits, "don't ask on" to DontAsk,
            ).filter { (text, _) -> text in footer }
            return states.singleOrNull()?.second
        }
    }
}
