package app.yxi.desktop

import org.json.JSONObject

/** Process exit and native terminal acknowledgement are separate evidence. */
internal data class LocalAgentOutcome(val status: String, val sessionId: String?, val detail: String = "") {
    companion object {
        fun parse(engine: String, lines: Sequence<String>, exitCode: Int, expectedSession: String?): LocalAgentOutcome {
            val ids = mutableSetOf<String>()
            var terminal: Boolean? = null
            var detail = ""
            for (line in lines) {
                val event = runCatching { JSONObject(line) }.getOrNull() ?: continue
                val type = event.optString("type")
                val id = when {
                    engine == "codex" && type == "thread.started" -> event.optString("thread_id")
                    engine == "claude" && (type == "result" || type == "system" && event.optString("subtype") == "init") -> event.optString("session_id")
                    else -> ""
                }
                if (LocalAgents.validSessionId(id)) ids.add(id)
                if (engine == "codex") when (type) {
                    "turn.completed" -> { terminal = true; detail = "" }
                    "turn.failed" -> { terminal = false; detail = event.optJSONObject("error")?.optString("message").orEmpty() }
                    "error" -> detail = event.optString("message")
                }
                if (engine == "claude" && type == "result") {
                    terminal = event.opt("is_error") == false && event.optString("subtype") == "success"
                    detail = if (terminal == true) "" else event.optString("result").ifBlank {
                        event.optJSONArray("errors")?.let { a -> (0 until a.length()).joinToString("\n") { a.optString(it) } }.orEmpty()
                    }
                }
            }
            val id = ids.singleOrNull()
            if (ids.size > 1 || expectedSession != null && id != expectedSession)
                return LocalAgentOutcome("结果未确认", expectedSession, "运行器未确认预期会话，请核对完整日志")
            if (exitCode != 0 || terminal == false)
                return LocalAgentOutcome(if (exitCode == 0) "运行器报告失败" else "运行失败 ($exitCode)", id ?: expectedSession, detail.take(4000))
            if (terminal != true || id == null)
                return LocalAgentOutcome("结果未确认", id, detail.ifBlank { "进程已退出，但未收到完整的运行器完成回执" }.take(4000))
            return LocalAgentOutcome("已完成", id)
        }
    }
}
