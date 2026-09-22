package app.yxi.desktop

import app.yxi.agent.Live
import app.yxi.agent.Prompt
import java.security.MessageDigest

/** Conservative recovery of the exact submitted text that native Escape puts back into its input. */
internal object RestoredRewindDraft {
    fun matches(screen: String, expectedHash: String): Boolean {
        if (Live.parse(screen).busy || Prompt.parse(screen) != null || Live.inputEmpty(screen) != false) return false
        val lines = screen.lines()
        val dividers = lines.indices.filter { Live.isDivider(lines[it]) }
        if (dividers.size < 2) return false
        val body = lines.subList(dividers[dividers.size - 2] + 1, dividers.last())
        val first = body.firstOrNull()?.trimStart() ?: return false
        if (!first.startsWith("❯") || first.length < 2 || first[1] !in listOf(' ', '\u00a0')) return false
        if (body.drop(1).any { !it.startsWith("  ") }) return false
        val text = (listOf(first.substring(2)) + body.drop(1).map { it.substring(2) }).joinToString("\n")
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return hash == expectedHash
    }
}
