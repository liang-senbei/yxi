package app.yxi.desktop

import app.yxi.agent.NativeRootVerification
import org.json.JSONObject
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
class NativeRootVerificationTest {
    @Test fun `root proof excludes old context and binds the exact new prompt`() {
        val root = Files.createTempDirectory("native-root-proof").toFile()
        try {
            val file = root.resolve("11111111-1111-1111-1111-111111111111.jsonl")
            fun row(id: String, parent: String?, type: String, text: String) = JSONObject()
                .put("uuid", id).put("parentUuid", parent ?: JSONObject.NULL).put("type", type)
                .put("message", JSONObject().put("role", type).put("content", text)).toString()
            val old = row("old", null, "user", "old") + "\n" + row("old-answer", "old", "assistant", "old response") + "\n"
            val fresh = row("new", null, "user", "新首轮") + "\n" + row("answer", "new", "assistant", "new response") + "\n"
            val expected = MessageDigest.getInstance("SHA-256").digest("新首轮".toByteArray()).joinToString("") { "%02x".format(it) }
            fun verifies(body: String, hash: String = expected): Boolean {
                file.writeText(body)
                val p = ProcessBuilder("python3", "-c", NativeRootVerification.chainScript, file.path, "old", hash)
                    .redirectError(root.resolve("stderr")).redirectOutput(root.resolve("stdout")).start()
                try { check(p.waitFor(10, TimeUnit.SECONDS)); return p.exitValue() == 0 && root.resolve("stdout").readText().trim() == "ok" }
                finally { if (p.isAlive) p.destroyForcibly() }
            }
            assertTrue(verifies(old + fresh))
            assertTrue(verifies(old + fresh + row("old", null, "user", "old replay") + "\n"))
            assertFalse(verifies(old))
            assertFalse(verifies(old + fresh, "0".repeat(64)))
            assertFalse(verifies(old + row("new", "old-answer", "user", "新首轮") + "\n" + row("answer", "new", "assistant", "reply") + "\n"))
            assertFalse(verifies(row("new", null, "user", "新首轮") + "\n"))
            assertFalse(verifies(row("answer", "missing", "assistant", "reply") + "\n"))
            val edited = row("new", null, "user", "新首轮") + "\n"
            val interrupted = JSONObject(row("interrupted", "new", "user", "unused"))
                .put("session_id", file.nameWithoutExtension).put("sessionId", file.nameWithoutExtension)
                .put("entrypoint", "cli").put("version", "2.1.278")
                .put("message", JSONObject().put("role", "user").put("content", org.json.JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "[Request interrupted by user]"))))
            assertTrue(verifies(old + edited + interrupted.toString() + "\n"), "Native cancellation must preserve the verified new root")
            assertFalse(verifies(old + edited + row("typed", "new", "user", "[Request interrupted by user]") + "\n"),
                "A user-typed marker is not a native cancellation")
            assertFalse(verifies(old + edited + JSONObject(interrupted.toString()).put("origin", "user").toString() + "\n"))
            assertFalse(verifies(old + edited + JSONObject(interrupted.toString()).put("session_id", "other").toString() + "\n"))
            assertFalse(verifies(old + interrupted.toString() + "\n"), "Interruption cannot hide a missing new root")
        } finally { root.deleteRecursively() }
    }
}
