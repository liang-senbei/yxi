package app.yxi.desktop

import app.yxi.agent.SupportApi
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class SupportWorkspaceTest {
    @Test fun `unknown submission cannot be resent until explicit reconciliation`(): Unit = runBlocking {
        val dir = Files.createTempDirectory("support-workspace").toFile()
        try {
            val space = SupportWorkspace(dir.resolve("drafts.json"))
            val edit = space.editor("alice").apply { text = "question"; category = "bug" }
            var calls = 0
            val api = SupportApi { _, _, _ -> calls++; if (calls == 1) 0 to "" else 200 to """{"id":"42","createdAt":"now"}""" }
            space.submit("alice", edit, api)
            assertEquals(SupportSendState.Unknown, edit.draft.state)
            assertFails { space.submit("alice", edit, api) }; assertEquals(1, calls)
            assertFails { space.submit("bob", edit, api) }; assertEquals(1, calls)
            space.drafts.reconcile(edit.id, edit.draft.revision, null)
            space.submit("alice", edit, api)
            assertEquals(SupportSendState.Confirmed, edit.draft.state); assertEquals(2, calls)
            assertTrue(space.running.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun `leaving the editor during submission still persists the receipt`(): Unit = runBlocking {
        val dir = Files.createTempDirectory("support-cancellation").toFile()
        try {
            val space = SupportWorkspace(dir.resolve("drafts.json"))
            val edit = space.editor("alice").apply { text = "question"; category = "other" }
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val api = SupportApi { _, _, _ -> entered.complete(Unit); release.await(); 200 to """{"id":"42","createdAt":"now"}""" }
            val job = launch { space.submit("alice", edit, api) }
            entered.await(); assertEquals(listOf(edit.id), space.running.toList())
            job.cancel(); release.complete(Unit); job.join()
            assertEquals(SupportSendState.Confirmed, SupportDrafts(dir.resolve("drafts.json")).entries.single().state)
            assertTrue(space.running.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun `failed save retains edited text and prevents the request`(): Unit = runBlocking {
        val dir = Files.createTempDirectory("support-save-failure").toFile()
        try {
            val file = dir.resolve("drafts.json")
            val space = SupportWorkspace(file)
            val edit = space.editor("alice").apply { text = "unsaved content"; category = "bug" }
            file.writeText("broken")
            var calls = 0
            val api = SupportApi { _, _, _ -> calls++; 200 to "{}" }
            assertFalse(edit.save()); assertTrue(edit.dirty)
            assertFails { space.submit("alice", edit, api) }
            assertEquals(0, calls); assertEquals("unsaved content", edit.text)
        } finally { dir.deleteRecursively() }
    }
}
