package app.yxi.agent

import org.json.JSONObject

/** Recognize the observed native envelope, never just a user-visible phrase. */
object NativeControlMessages {
    const val INTERRUPTION = "[Request interrupted by user]"
    fun isInterruption(record: JSONObject): Boolean {
        val parent = record.opt("parentUuid") as? String ?: return false
        val session = record.optString("sessionId")
        if (parent.isBlank() || !Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(session)) return false
        if (record.optString("type") != "user" || record.optString("session_id") != session ||
            record.optString("entrypoint") != "cli" || record.optString("version") != "2.1.278") return false
        if (listOf("origin", "promptSource", "turnOrigin").any(record::has)) return false
        val content = record.optJSONObject("message")?.optJSONArray("content") ?: return false
        if (content.length() != 1) return false
        val block = content.optJSONObject(0) ?: return false
        return block.optString("type") == "text" && block.optString("text") == INTERRUPTION
    }
}
