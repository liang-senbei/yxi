package app.yxi.desktop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
class RewindMessageSourceTest {
    @Test fun `snapshot-bound source preserves attachments and rejects changed or unrelated history`() {
        val root = Files.createTempDirectory("rewind-source-").toFile()
        try {
            val file = root.resolve("history.jsonl")
            val message = JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64")
                    .put("media_type", "image/png").put("data", "AQID")))
                .put(JSONObject().put("type", "text").put("text", "图片说明 中文")))
            val record = JSONObject().put("type", "user").put("uuid", "target").put("message", message)
            file.writeText(record.toString() + "\n")
            val size = file.length()
            val modified = Files.getLastModifiedTime(file.toPath()).to(TimeUnit.NANOSECONDS)
            fun run(target: String = "target", expectedSize: Long = size, expectedTime: Long = modified): Pair<Int, String> {
                val output = root.resolve("output")
                val error = root.resolve("error")
                val p = ProcessBuilder("python3", "-c", RewindTargets.messageScript, file.path,
                    target, expectedSize.toString(), expectedTime.toString()).redirectOutput(output).redirectError(error).start()
                try {
                    check(p.waitFor(10, TimeUnit.SECONDS))
                    return p.exitValue() to output.readText()
                } finally { if (p.isAlive) p.destroyForcibly() }
            }
            val loaded = run()
            assertEquals(0, loaded.first)
            assertTrue(message.similar(JSONObject(loaded.second)))
            assertNotEquals(0, run(target = "different").first)
            assertNotEquals(0, run(expectedTime = modified - 1).first)
            file.appendText("{}\n")
            assertNotEquals(0, run().first)
            record.put("isMeta", true)
            file.writeText(record.toString() + "\n")
            assertNotEquals(0, run(expectedSize = file.length(), expectedTime = Files.getLastModifiedTime(file.toPath()).to(TimeUnit.NANOSECONDS)).first)
        } finally { root.deleteRecursively() }
    }
}
