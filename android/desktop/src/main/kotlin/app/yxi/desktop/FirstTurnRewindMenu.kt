package app.yxi.desktop

/** Read-only parser for the native layout verified in the isolated 2.1.278 fixture. */
internal object FirstTurnRewindMenu {
    const val VERSION = "2.1.278"
    data class View(val rows: List<String>, val selectedIndex: Int) {
        fun matches(expected: List<String>): Boolean = expected.isNotEmpty() && rows.size == expected.size &&
            rows.zip(expected).all { (actual, source) -> normalize(actual) == normalize(source) }
        val currentSelected: Boolean get() = selectedIndex == rows.size
    }
    private fun normalize(value: String) = value.trim().replace(Regex("\\s+"), " ")
    private fun lines(capture: String): List<String> = capture
        .replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")
        .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    fun parse(capture: String, version: String): View? {
        if (version != VERSION || capture.length > 100_000) return null
        val all = lines(capture)
        val start = all.indices.lastOrNull { all[it] == "Rewind" &&
            all.getOrNull(it + 1) == "Restore the code and/or conversation to the point before…" } ?: return null
        val end = (start + 2 until all.size).firstOrNull { all[it] == "Enter to continue · Esc to cancel" } ?: return null
        val body = all.subList(start + 2, end)
        // Until scrolling has its own verified count protocol, never infer hidden rows.
        if (body.any { "more above" in it || "more below" in it }) return null
        val rows = mutableListOf<String>()
        var selected: Int? = null
        var i = 0
        while (i < body.size) {
            val marked = body[i].startsWith("❯ ")
            val row = if (marked) body[i].removePrefix("❯ ").trim() else body[i]
            if (marked) {
                if (selected != null) return null
                selected = rows.size
            }
            if (row == "(current)" && i == body.lastIndex) {
                return View(rows, selected ?: return null)
            }
            if (row.isBlank() || body.getOrNull(i + 1) !in setOf("No code changes", "⚠ No code restore")) return null
            rows += row
            i += 2
        }
        return null
    }

    fun confirmsConversationOnly(capture: String, version: String, expectedText: String): Boolean {
        if (version != VERSION || capture.length > 100_000) return false
        val all = lines(capture)
        val header = "Confirm you want to restore the conversation to the point before you sent this message:"
        val start = all.indexOfLast { it == header }
        if (start < 0) return false
        val body = all.drop(start + 1)
        if (body.size != 8 || !body[0].startsWith("│ ")) return false
        if (normalize(body[0].removePrefix("│ ")) != normalize(expectedText)) return false
        if (!Regex("│ \\(\\d+[smhd] ago\\)").matches(body[1])) return false
        return body.drop(2) == listOf("The conversation will be forked.", "The code will be unchanged.",
            "❯ 1. Restore conversation", "2. Summarize from here", "3. Summarize up to here", "4. Never mind")
    }

    /** Both known navigation screens can be dismissed with Escape; neither implies restore ran. */
    fun canCancelNavigation(capture: String, version: String, expectedText: String): Boolean =
        parse(capture, version) != null || confirmsConversationOnly(capture, version, expectedText)
}
