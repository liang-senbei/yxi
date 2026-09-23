package app.yxi.desktop

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class LocalCodexTaskRegistryTest {
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
}
