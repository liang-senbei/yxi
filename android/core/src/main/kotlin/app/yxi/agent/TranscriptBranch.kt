package app.yxi.agent

import org.json.JSONObject

/** Keeps only the selected conversation branch; the JSONL on disk is never modified. */
internal class TranscriptBranch {
    private data class Row(val line: String, val id: String?, val owner: String?, val operational: Boolean)
    private val rows = ArrayList<Row>()
    private val parents = HashMap<String, String?>()
    private var leaf: String? = null

    /** Returns a replacement history only when a new human turn branches from a known ancestor. */
    fun append(line: String): List<String>? {
        val d = runCatching { JSONObject(line) }.getOrNull() ?: return null
        if (d.optBoolean("isSidechain", false)) return null
        val type = d.optString("type")
        val id = d.optString("uuid").takeIf { it.isNotBlank() }
        val linked = id != null && d.has("parentUuid") &&
            (d.isNull("parentUuid") || d.opt("parentUuid") is String)
        val parent = d.optString("parentUuid").takeIf { it.isNotBlank() && it != "null" }
        val known = id != null && parents.containsKey(id)
        val content = d.optJSONObject("message")?.opt("content")
        val human = type == "user" && !d.optBoolean("isMeta", false) &&
            (content is String || (content is org.json.JSONArray &&
                (0 until content.length()).any { content.optJSONObject(it)?.optString("type") == "text" } &&
                (0 until content.length()).none { content.optJSONObject(it)?.optString("type") == "tool_result" }))
        val chain = when {
            !linked || known || !human -> null
            parent == null -> emptySet()
            parents.containsKey(parent) -> ancestors(parent)
            else -> null
        }
        val switched = chain != null && leaf != null && leaf !in chain
        if (switched) {
            rows.removeAll { row -> !row.operational &&
                (chain!!.isEmpty() || (row.id ?: row.owner)?.let { it !in chain } == true) }
            parents.keys.retainAll(chain!!)
        }
        // File snapshots/progress can be large. Keep their parent links, not their payloads.
        if (type in setOf("user", "assistant", "attachment", "mode", "queue-operation"))
            rows += Row(line, if (linked) id else null, leaf, type == "queue-operation")
        if (linked) {
            parents[id!!] = parent
            // Replayed chunks update text without switching back to an older leaf.
            if (!known && type in setOf("user", "assistant")) leaf = id
        }
        return if (switched) rows.map { it.line } else null
    }

    private fun ancestors(start: String): Set<String> {
        val result = HashSet<String>()
        var cursor: String? = start
        while (cursor != null && result.add(cursor)) cursor = parents[cursor]
        return result
    }
}
