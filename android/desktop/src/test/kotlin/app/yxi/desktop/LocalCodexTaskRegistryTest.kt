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
