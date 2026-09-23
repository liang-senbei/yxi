package app.yxi.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.*

class OpenCodeTaskControllerTest {
    @Test fun `submission is unknown until native history matches and tool steps do not complete a turn`() = runBlocking {
        val history = JSONArray()
        var sent: JSONObject? = null
        var writes = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val response = when (path) {
                "/session/ses_test" -> """{"id":"ses_test","directory":"/fixture"}"""
                "/session/ses_test/message" -> history.toString()
                "/session/status" -> "{}"
                "/permission", "/question" -> "[]"
                "/provider" -> """{"connected":["p"],"all":[{"id":"p","models":{"m":{"id":"m","name":"Model"}}}]}"""
                "/session/ses_test/prompt_async" -> {
                    writes++; sent = JSONObject(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)); ""
                }
                else -> error(path)
            }
            if (response.isEmpty()) exchange.sendResponseHeaders(204, -1) else {
                val bytes = response.toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
            }
            exchange.close()
        }
        server.start()
        val dir = Files.createTempDirectory("opencode-outbox-").toFile()
        val file = java.io.File(dir, "queue.json")
        try {
            val queue = InstructionQueue(file)
            val client = OpenCodeClient(server.address.port, "fixture", "/fixture")
            val controller = OpenCodeTaskController("task", "ses_test", "/fixture", "p", "m", client, queue)
            val first = controller.enqueue("first")
            controller.sendNext()
            assertEquals(InstructionStatus.Unknown, queue.entries.single().status)
            assertEquals(first.id, sent!!.getString("messageID"))
            controller.enqueue("second")
            assertFailsWith<IllegalStateException> { controller.sendNext() }
            assertEquals(1, writes, "Unknown delivery must block the next submission")
            history.put(JSONObject().put("info", JSONObject().put("id", first.id).put("sessionID", "ses_test").put("role", "user")
                .put("model", JSONObject().put("providerID", "p").put("modelID", "m")))
                .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", "first"))))
            controller.refresh()
            assertEquals(InstructionStatus.Accepted, queue.entries.first().status)
            val assistant = JSONObject().put("id", "assistant").put("sessionID", "ses_test").put("role", "assistant")
                .put("parentID", first.id).put("finish", "tool-calls").put("time", JSONObject().put("created", 1).put("completed", 2))
            history.put(JSONObject().put("info", assistant).put("parts", JSONArray()))
            controller.refresh()
            assertEquals(RuntimeTurnState.InProgress, queue.entries.first().runtimeTurnState)
            assertFailsWith<IllegalStateException> { controller.sendNext() }
            assistant.put("finish", "stop")
            controller.refresh()
            assertEquals(RuntimeTurnState.Completed, queue.entries.first().runtimeTurnState)
            controller.sendNext()
            assertEquals(2, writes)
            assertEquals(InstructionStatus.Unknown, InstructionQueue(file).entries.last().status, "Unconfirmed submission survives restart")
        } finally {
            server.stop(0)
            dir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }; dir.delete()
        }
    }
}
