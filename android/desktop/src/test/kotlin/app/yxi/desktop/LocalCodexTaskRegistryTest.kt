package app.yxi.desktop

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class LocalCodexTaskRegistryTest {
    @Test fun `OpenCode records preserve provider and stay separate from Codex identities`() {
        val file = File(directory, "mixed.json")
        val codex = record()
        val openCode = codex.copy(engine = "opencode", provider = "custom")
        val registry = LocalCodexTaskRegistry(file)
        registry.save(codex); registry.save(openCode)
        assertEquals(listOf(codex, openCode), LocalCodexTaskRegistry(file).records.toList())
        assertNotEquals(codex.key, openCode.key)
        val remote = openCode.copy(hostKey = "server-a")
        registry.save(remote)
        assertNotEquals(openCode.key, remote.key)
        assertNotEquals(remote.key, remote.copy(hostKey = "server-b").key)
        assertEquals(remote, LocalCodexTaskRegistry(file).records.last())
    }
    @TempDir lateinit var directory: File
    private fun record() = LocalCodexTaskRecord("native-thread", "fixture-user", "fixture-os", "/native/home", "/project", "Task", "native-model", 1)
    @Test fun `native identity survives reload and separates different data homes and users`() {
        val file = File(directory, "tasks.json")
        val original = record()
        LocalCodexTaskRegistry(file).save(original)
        assertEquals(original, LocalCodexTaskRegistry(file).records.single())
        assertNotEquals(original.key, original.copy(runtimeHome = "/another/home").key)
        assertNotEquals(original.key, original.copy(user = "another-user").key)
        assertNotEquals(original.key, original.copy(platform = "another-os").key)
    }
    @Test fun `effort survives disk reload without changing task identity`() {
        val file = File(directory, "effort.json")
        val saved = record().copy(engine = "claude", provider = "official:claude", effort = "low")
        LocalCodexTaskRegistry(file).save(saved)
        assertEquals(saved, LocalCodexTaskRegistry(file).records.single())
        assertEquals(saved.key, saved.copy(effort = "high").key)
    }
    @Test fun `legacy missing effort and explicit null preserve default behavior`() {
        for (missing in listOf(true, false)) {
            val row = record().json()
            if (missing) row.remove("effort")
            val file = File(directory, "legacy-$missing.json").apply { writeText(org.json.JSONArray().put(row).toString()) }
            val registry = LocalCodexTaskRegistry(file)
            assertEquals("", registry.problem)
            assertNull(registry.records.single().effort)
            assertEquals(record(), registry.records.single())
        }
    }
    @Test fun `malformed effort is retained and cannot silently fall back to default`() {
        for ((index, value) in listOf<Any>("unsupported", "", 3, true, org.json.JSONObject()).withIndex()) {
            val raw = org.json.JSONArray().put(record().json().put("effort", value)).toString()
            val file = File(directory, "invalid-effort-$index.json").apply { writeText(raw) }
            val registry = LocalCodexTaskRegistry(file)
            assertTrue(registry.problem.isNotBlank(), "Must reject effort $value")
            assertTrue(registry.records.isEmpty())
            assertFailsWith<IllegalStateException> { registry.save(record()) }
            assertEquals(raw, file.readText())
        }
    }
    @Test fun `damaged native task index cannot be silently replaced by an empty list`() {
        val file = File(directory, "tasks.json").apply { writeText("not json") }
        val registry = LocalCodexTaskRegistry(file)
        assertTrue(registry.problem.isNotBlank())
        assertFailsWith<IllegalStateException> { registry.save(record()) }
        assertEquals("not json", file.readText())
    }
    @Test fun `unconfirmed creation is retained across app restart until manually reviewed`() {
        val file = File(directory, "tasks.json")
        File(directory, "tasks.json.creation.json").writeText("""{"pending":true,"threadId":"native-unconfirmed"}""")
        LocalCodexTasks(InstructionQueue(File(directory, "queue.json")), file).use { tasks ->
            assertEquals("native-unconfirmed", tasks.recoveryThreadId)
            tasks.confirmCreationReviewed()
        }
        LocalCodexTasks(InstructionQueue(File(directory, "queue.json")), file).use { tasks -> assertEquals("", tasks.recoveryThreadId) }
    }
    @Test fun `closed local workspace does not create another native process`() = kotlinx.coroutines.runBlocking {
        val tasks = LocalCodexTasks(InstructionQueue(File(directory, "queue.json")), File(directory, "tasks.json"))
        tasks.connect = { error("Must not start a process after close") }
        tasks.close()
        val failure = assertFailsWith<IllegalStateException> {
            tasks.create(LocalRuntimeInstallation("codex", "fixture", listOf("unused"), directory.path, "1.0.0"), directory.path, "Task", "model")
        }
        assertTrue(failure.message.orEmpty().contains("已关闭"))
    }
    @Test fun `backup recovery remains blocked after another restart until explicit review`() {
        val file = File(directory, "tasks.json")
        LocalCodexTaskRegistry(file).apply { save(record()); save(record().copy(title = "newer")) }
        file.writeText("damaged")
        assertTrue(LocalCodexTaskRegistry(file).recoveryReviewRequired)
        val reopened = LocalCodexTaskRegistry(file)
        assertTrue(reopened.recoveryReviewRequired)
        assertFailsWith<IllegalStateException> { reopened.save(record()) }
        reopened.confirmRecoveryReviewed()
        reopened.save(record().copy(title = "reviewed"))
        assertEquals("reviewed", LocalCodexTaskRegistry(file).records.single().title)
    }
}
