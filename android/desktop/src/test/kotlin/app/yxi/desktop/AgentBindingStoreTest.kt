package app.yxi.desktop

import app.yxi.agent.RemoteAtomicJson
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AgentBindingStoreTest {
    private class Storage(var text: String? = null) : AgentBindingStore.BindingStorage {
        var writes = 0
        var conflict = false
        override suspend fun read() = RemoteAtomicJson.Snapshot(text, "revision")
        override suspend fun write(text: String, expected: String): String? {
            assertEquals("revision", expected)
            if (conflict) return "conflict"
            this.text = text; writes++; return null
        }
    }
    @Test fun `independent selections preserve other agents and old applied receipt`() = runBlocking {
        val store = Storage()
        val a = AgentBindingStore.Desired("claude", "glm")
        val b = AgentBindingStore.Desired("codex", "deepseek")
        assertNull(AgentBindingStore.setDesired(store, "agent-a", a))
        assertNull(AgentBindingStore.markApplied(store, "agent-a", a, "process-instance-a", 1.0))
        assertNull(AgentBindingStore.setDesired(store, "agent-b", b))
        assertNull(AgentBindingStore.setDesired(store, "agent-a", a.copy(profileId = "other")))
        val saved = AgentBindingStore.list(store)!!
        assertEquals(b, saved.getValue("agent-b").desired)
        assertEquals("glm", saved.getValue("agent-a").applied!!.profileId)
        assertTrue(saved.getValue("agent-a").pending)
        assertFalse(store.text!!.contains("apiKey"))
    }
    @Test fun `malformed and future documents cannot be overwritten`() = runBlocking {
        val valid = AgentBindingStore.encode(mapOf("a" to AgentBindingStore.Binding(AgentBindingStore.Desired("claude", "one"), null)))
        for (raw in listOf("", "broken", "{\"version\":2,\"bindings\":{}}",
            valid.replace("\"desired\":", "\"applied\":false,\"desired\":"))) {
            val store = Storage(raw)
            assertNotNull(AgentBindingStore.setDesired(store, "a", AgentBindingStore.Desired("codex", "two")))
            assertEquals(raw, store.text); assertEquals(0, store.writes)
        }
    }
    @Test fun `stale snapshot and stale receipts fail without mutation`() = runBlocking {
        val store = Storage()
        val a = AgentBindingStore.Desired("claude", "one")
        assertNull(AgentBindingStore.setDesired(store, "a", a))
        val before = AgentBindingStore.list(store)!!
        assertNull(AgentBindingStore.setDesired(store, "a", a.copy(profileId = "two")))
        val current = store.text
        assertNotNull(AgentBindingStore.setDesired(store, "b", a, before))
        assertNotNull(AgentBindingStore.markApplied(store, "a", a, "old-process", 4.0))
        assertEquals(current, store.text)
        store.conflict = true
        assertNotNull(AgentBindingStore.setDesired(store, "b", a))
        assertEquals(current, store.text)
    }
}
