package app.yxi.desktop

import kotlin.test.*

class HostTransferTest {
    @Test fun `exports exclude credentials and imported hosts require fresh authentication`() {
        val source = Host("a", "Alpha", "alpha.invalid", username = "alice", password = "private-password", keyPath = "/private/id")
        val exported = HostTransfer.export(listOf(source))
        listOf("password", "keyPath", "private-password", "/private/id").forEach { assertFalse(exported.contains(it)) }
        val result = HostTransfer.preview(exported, emptyList()).additions.single()
        assertEquals("a", result.id)
        assertEquals("alice", result.username)
        assertEquals("", result.password)
        assertEquals("", result.keyPath)
    }
    @Test fun `duplicate endpoints never replace credentials and ID conflicts create separate records`() {
        val existing = Host("same-id", "Existing", "ALPHA.invalid", username = "alice", password = "keep")
        val raw = """[{"id":"x","hostname":"alpha.invalid.","username":"alice","password":"ignore"},{"id":"same-id","hostname":"beta.invalid","username":"alice"},{"id":"third","hostname":"beta.invalid","username":"alice"}]"""
        val plan = HostTransfer.preview(raw, listOf(existing))
        assertTrue(plan.ignoredAuthentication)
        assertEquals(1, plan.additions.size)
        assertTrue(plan.rows[1].reassignedId)
        assertNotEquals(existing.id, plan.additions.single().id)
        assertEquals(existing, plan.merge(listOf(existing)).first())
        assertFails { plan.merge(listOf(existing.copy(alias = "changed"))) }
    }
    @Test fun `unsupported formats and invalid targets are rejected before merge`() {
        assertFails { HostTransfer.preview("""{"format":"yxi-dpapi-v1","data":"encrypted"}""", emptyList()) }
        assertFails { HostTransfer.preview("""[{"hostname":"https://host","username":"root"}]""", emptyList()) }
        assertFails { HostTransfer.preview("""[{"hostname":"host:22","username":"root"}]""", emptyList()) }
        assertFails { HostTransfer.preview("""[{"hostname":"host","username":"root","port":22.5}]""", emptyList()) }
    }
}
