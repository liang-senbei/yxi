package app.yxi.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.json.JSONObject
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class LocalClaudeTasksTest {
    @TempDir lateinit var root: File
    private class Fixture(val reject: Boolean = false, val hold: Boolean = false, val declaredLevels: List<String> = listOf("low", "high")) : ClaudeControlTransport {
        override val output = PipedInputStream(65536)
        private val producer = PipedOutputStream(output)
        val writes = CopyOnWriteArrayList<JSONObject>()
        var closed = false
        var model = "claude-fixture"
        var effort = "high"
        override suspend fun write(text: String): Boolean {
            val request = JSONObject(text); writes.add(request)
            assertEquals("control_request", request.getString("type"))
            if (hold) return true
            val control = request.getJSONObject("request")
            val subtype = control.getString("subtype")
            if (subtype == "set_model") model = control.getString("model")
            if (subtype == "apply_flag_settings") effort = control.getJSONObject("settings").getString("effortLevel")
            val value = if (subtype == "initialize") JSONObject().put("account", JSONObject()
                .put("tokenSource", "claude.ai").put("apiProvider", if (reject) "bedrock" else "firstParty"))
                .put("models", org.json.JSONArray(listOf("claude-fixture", "claude-saved").map { name ->
                    JSONObject().put("resolvedModel", name).put("supportsEffort", true)
                        .put("supportedEffortLevels", org.json.JSONArray(declaredLevels))
                }))
            else JSONObject().put("effective", ClaudeSubscriptionSettings.overlay())
                .put("applied", JSONObject().put("model", model).put("effort", effort))
            producer.write((JSONObject().put("type", "control_response").put("response", JSONObject().put("subtype", "success")
                .put("request_id", request.getString("request_id")).put("response", value)).toString() + "\n").toByteArray()); producer.flush()
            return true
        }
        override fun close() { closed = true; producer.close(); output.close() }
    }
    private fun runtime() = LocalRuntimeInstallation("claude", "fixture", listOf("fixture"), root.resolve("native-home").path, "fixture")
    @Test fun `explicit resume reuses one controller and does not replay local instructions`(): Unit = runBlocking(Dispatchers.Swing) {
        val index = File(root, "resume-tasks.json"); val queue = InstructionQueue(File(root, "resume-queue.json"))
        val record = LocalCodexTaskRecord(UUID.randomUUID().toString(), System.getProperty("user.name"), System.getProperty("os.name"),
            File(runtime().home).canonicalPath, root.canonicalPath, "Resume", "claude-fixture", 1L, "claude", "official:claude")
        LocalCodexTaskRegistry(index).save(record)
        val pending = queue.enqueue(record.key, "must stay local")
        var launches = 0
        val fixtures = mutableListOf<Fixture>()
        val subscription = LocalClaudeSubscription { _, _ -> error("must not create") }
        LocalClaudeTasks(queue, index, subscription, resumeConnection = { selected, saved ->
            launches++
            subscription.resume(selected, saved) { _, _, id ->
                ClaudeControlClient(Fixture().also { fixtures.add(it) }, id)
            }
        }).use { tasks ->
            val delivering = queue.beginDelivery(pending.id, pending.revision)
            queue.markUnknown(delivering.id, delivering.revision, "test interruption")
            assertFailsWith<IllegalStateException> { tasks.resume(runtime(), record.key) }
            assertEquals(0, launches)
        }
        val emptyQueue = InstructionQueue(File(root, "safe-queue.json"))
        emptyQueue.enqueue(record.key, "local only")
        LocalClaudeTasks(emptyQueue, index, subscription, readHistory = { listOf(app.yxi.agent.ChatItem.UserText("old", "previous question")) }, resumeConnection = { selected, saved ->
            launches++
            subscription.resume(selected, saved) { _, _, id -> ClaudeControlClient(Fixture().also { fixtures.add(it) }, id) }
        }).use { tasks ->
            val first = tasks.resume(runtime(), record.key)
            assertEquals("previous question", (first.history.single() as app.yxi.agent.ChatItem.UserText).text)
            assertSame(first, tasks.resume(runtime(), record.key))
            assertEquals(1, launches); assertEquals("claude-fixture", tasks.registry.records.single().model)
            assertEquals(InstructionStatus.Local, emptyQueue.entries.single().status)
            assertTrue(fixtures.single().writes.all { it.getString("type") == "control_request" })
        }
        assertTrue(fixtures.all { it.closed })
        LocalClaudeTasks(emptyQueue, index, subscription, readHistory = { error("history unavailable") },
            resumeConnection = { _, _ -> error("must not launch when history cannot be verified") }).use { tasks ->
            assertFailsWith<IllegalStateException> { tasks.resume(runtime(), record.key) }
            assertTrue(tasks.controllers.isEmpty()); assertFalse(tasks.busy)
            assertEquals(InstructionStatus.Local, emptyQueue.entries.single().status)
        }
    }
    @Test fun `selected effort persists and is restored without sending a user message`(): Unit = runBlocking(Dispatchers.Swing) {
        val index = File(root, "effort-tasks.json")
        val queue = InstructionQueue(File(root, "effort-queue.json"))
        val created = Fixture()
        val subscription = LocalClaudeSubscription { _, _ -> ClaudeControlClient(created, UUID.randomUUID().toString()) }
        lateinit var saved: LocalCodexTaskRecord
        LocalClaudeTasks(queue, index, subscription).use { tasks ->
            val record = tasks.create(runtime(), root.path, "Saved effort")
            tasks.controllers.getValue(record.key).selectEffort("low")
            saved = LocalCodexTaskRegistry(index).records.single()
            assertEquals("low", saved.effort)
        }
        val fixture = Fixture()
        LocalClaudeTasks(queue, index, subscription, readHistory = { emptyList() }, resumeConnection = { selected, record ->
            subscription.resume(selected, record) { _, _, id -> ClaudeControlClient(fixture, id) }
        }).use { tasks ->
            val controller = tasks.resume(runtime(), saved.key)
            assertEquals("low", controller.effort)
            assertEquals(saved, LocalCodexTaskRegistry(index).records.single())
            assertEquals(listOf("initialize", "get_settings", "apply_flag_settings", "get_settings"),
                fixture.writes.map { it.getJSONObject("request").getString("subtype") })
            assertTrue(fixture.writes.all { it.getString("type") == "control_request" })
            assertTrue(queue.entries.isEmpty())
        }
        assertTrue(fixture.closed)
    }
    @Test fun `resume restores saved model before optional effort`(): Unit = runBlocking(Dispatchers.Swing) {
        for (savedEffort in listOf(null, "low")) {
            val index = File(root, "model-$savedEffort-tasks.json")
            val saved = LocalCodexTaskRecord(UUID.randomUUID().toString(), System.getProperty("user.name"), System.getProperty("os.name"),
                File(runtime().home).canonicalPath, root.canonicalPath, "Saved model", "claude-saved", 1L, "claude", "official:claude", effort = savedEffort)
            LocalCodexTaskRegistry(index).save(saved)
            val fixture = Fixture()
            val subscription = LocalClaudeSubscription { _, _ -> error("must not create") }
            LocalClaudeTasks(InstructionQueue(File(root, "model-$savedEffort-queue.json")), index, subscription, readHistory = { emptyList() },
                resumeConnection = { selected, record -> subscription.resume(selected, record) { _, _, id -> ClaudeControlClient(fixture, id) } }).use { tasks ->
                val controller = tasks.resume(runtime(), saved.key)
                assertEquals(saved.model, controller.model)
                if (savedEffort != null) assertEquals(savedEffort, controller.effort)
                val expected = listOf("initialize", "get_settings", "set_model", "get_settings") +
                    if (savedEffort == null) emptyList() else listOf("apply_flag_settings", "get_settings")
                assertEquals(expected, fixture.writes.map { it.getJSONObject("request").getString("subtype") })
                assertTrue(fixture.writes.all { it.getString("type") == "control_request" })
                assertEquals(saved, LocalCodexTaskRegistry(index).records.single())
            }
            assertTrue(fixture.closed)
        }
    }
    @Test fun `unsupported saved effort closes recovery without altering index or queued instructions`(): Unit = runBlocking(Dispatchers.Swing) {
        val index = File(root, "unsupported-tasks.json")
        val saved = LocalCodexTaskRecord(UUID.randomUUID().toString(), System.getProperty("user.name"), System.getProperty("os.name"),
            File(runtime().home).canonicalPath, root.canonicalPath, "Unsupported", "claude-fixture", 1L, "claude", "official:claude", effort = "low")
        LocalCodexTaskRegistry(index).save(saved)
        val original = index.readBytes()
        val queue = InstructionQueue(File(root, "unsupported-queue.json"))
        queue.enqueue(saved.key, "not sent")
        val fixture = Fixture(declaredLevels = listOf("high"))
        val subscription = LocalClaudeSubscription { _, _ -> error("must not create") }
        LocalClaudeTasks(queue, index, subscription, readHistory = { emptyList() }, resumeConnection = { selected, record ->
            subscription.resume(selected, record) { _, _, id -> ClaudeControlClient(fixture, id) }
        }).use { tasks ->
            assertFailsWith<IllegalStateException> { tasks.resume(runtime(), saved.key) }
            assertTrue(fixture.closed); assertTrue(tasks.controllers.isEmpty()); assertFalse(tasks.busy)
            assertContentEquals(original, index.readBytes())
            assertEquals(InstructionStatus.Local, queue.entries.single().status)
            assertEquals(listOf("initialize", "get_settings"), fixture.writes.map { it.getJSONObject("request").getString("subtype") })
        }
    }
    @Test fun `resume verifies native identity and rejects foreign targets before launch`(): Unit = runBlocking(Dispatchers.Swing) {
        val selected = runtime()
        val record = LocalCodexTaskRecord(UUID.randomUUID().toString(), System.getProperty("user.name"), System.getProperty("os.name"),
            File(selected.home).canonicalPath, root.canonicalPath, "Resume", "claude-fixture", 1L, "claude", "official:claude")
        val subscription = LocalClaudeSubscription { _, _ -> error("must not create a new session") }
        for (invalid in listOf(record.copy(user = "another-user"), record.copy(hostKey = "remote"),
            record.copy(provider = "third-party"), record.copy(runtimeHome = root.resolve("other").path),
            record.copy(directory = root.resolve("missing").path), record.copy(threadId = "invalid"))) {
            assertFailsWith<IllegalArgumentException> { subscription.resume(selected, invalid) { _, _, _ -> error("must not launch") } }
        }
        val fixture = Fixture()
        subscription.resume(selected, record) { _, directory, id ->
            assertEquals(root.canonicalFile, directory); assertEquals(record.threadId, id)
            ClaudeControlClient(fixture, id)
        }.use { assertEquals(record.threadId, it.client.requestedSessionId) }
        assertTrue(fixture.closed)
        assertTrue(fixture.writes.all { it.getString("type") == "control_request" })
        val wrong = Fixture()
        assertFailsWith<IllegalStateException> { subscription.resume(selected, record) { _, _, _ -> ClaudeControlClient(wrong, UUID.randomUUID().toString()) } }
        assertTrue(wrong.closed); assertTrue(wrong.writes.isEmpty())
    }
    @Test fun `new checked sessions persist distinct identities and reopening never launches them`(): Unit = runBlocking(Dispatchers.Swing) {
        val index = File(root, "tasks.json"); val queue = InstructionQueue(File(root, "queue.json")); val fixtures = mutableListOf<Fixture>()
        LocalClaudeTasks(queue, index, LocalClaudeSubscription { _, _ ->
            val fixture = Fixture().also { fixtures.add(it) }; ClaudeControlClient(fixture, UUID.randomUUID().toString())
        }).use { tasks ->
            val first = tasks.create(runtime(), root.path, "同名任务")
            val second = tasks.create(runtime(), root.path, "同名任务")
            assertNotEquals(first.key, second.key)
            assertEquals(2, tasks.controllers.size)
            assertEquals("official:claude", first.provider)
            assertTrue(queue.entries.isEmpty())
            assertEquals(tasks.registry.records.toList(), LocalCodexTaskRegistry(index).records.toList())
        }
        assertTrue(fixtures.all { it.closed })
        LocalClaudeTasks(queue, index, LocalClaudeSubscription { _, _ -> error("Index loading must not launch") }).use { reopened ->
            assertEquals(2, reopened.registry.records.size); assertTrue(reopened.controllers.isEmpty())
        }
    }
    @Test fun `rejected configuration creates no task and closes its connection`(): Unit = runBlocking(Dispatchers.Swing) {
        val fixture = Fixture(reject = true)
        LocalClaudeTasks(InstructionQueue(File(root, "queue.json")), File(root, "tasks.json"),
            LocalClaudeSubscription { _, _ -> ClaudeControlClient(fixture, UUID.randomUUID().toString()) }).use { tasks ->
            assertFailsWith<IllegalStateException> { tasks.create(runtime(), root.path, "Rejected") }
            assertTrue(tasks.registry.records.isEmpty()); assertTrue(tasks.controllers.isEmpty()); assertTrue(fixture.closed)
        }
    }
    @Test fun `closing during preparation cancels only the creation and leaves no task`(): Unit = runBlocking(Dispatchers.Swing) {
        val fixture = Fixture(hold = true)
        val tasks = LocalClaudeTasks(InstructionQueue(File(root, "queue.json")), File(root, "tasks.json"),
            LocalClaudeSubscription { _, _ -> ClaudeControlClient(fixture, UUID.randomUUID().toString()) })
        val creating = async { runCatching { tasks.create(runtime(), root.path, "Cancelled") } }
        withTimeout(2000) { while (fixture.writes.isEmpty()) delay(10) }
        tasks.close()
        assertTrue(withTimeout(2000) { creating.await() }.exceptionOrNull() is CancellationException)
        assertTrue(fixture.closed); assertTrue(tasks.registry.records.isEmpty()); assertTrue(tasks.controllers.isEmpty())
    }
}
