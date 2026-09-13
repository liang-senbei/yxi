package app.yxi.desktop

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.test.*

class AuthSessionStoreTest {
    private fun response(name: String) = JSONObject().put("access_token", "access-$name").put("refresh_token", "refresh-$name").put("expires_in", 3600)
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("auth-session").toFile()
        try { block(root.resolve("auth.json")) } finally { root.deleteRecursively() }
    }
    @Test fun `late refresh cannot restore tokens or profile after logout`() = fixture { file -> runBlocking {
        val store = AuthSessionStore(file)
        val old = store.begin(); store.save(old, response("old"), fresh = true)
        val requested = CompletableDeferred<Unit>(); val responseReady = CompletableDeferred<Unit>()
        var profile: String? = "old"
        val refresh = async(Dispatchers.Default) {
            store.read(old); requested.complete(Unit); responseReady.await()
            assertFailsWith<StaleAuthSession> { store.save(old, response("late")) }
            assertFailsWith<StaleAuthSession> { store.guarded(old) { profile = "late" } }
        }
        requested.await()
        store.signOut { profile = null }
        responseReady.complete(Unit); refresh.await()
        assertNull(profile)
        val restarted = AuthSessionStore(file)
        assertEquals(0, restarted.read(restarted.generation).length())
        assertEquals("{}", file.readText())
    } }
    @Test fun `new login cannot inherit old refresh token and old response cannot replace new account`() = fixture { file ->
        val store = AuthSessionStore(file)
        val old = store.begin(); store.save(old, response("old"), fresh = true)
        val current = store.begin()
        assertEquals(0, store.read(current).length())
        assertFails { store.save(current, JSONObject().put("access_token", "new-access"), fresh = true) }
        store.save(current, response("new"), fresh = true)
        assertFailsWith<StaleAuthSession> { store.save(old, response("old-late")) }
        assertEquals("refresh-new", store.read(current).getString("refresh"))
    }
    @Test fun `rotated credentials do not recover stale backups`() = fixture { file ->
        File(file.parentFile, "auth.json.bak").writeText("""{"refresh":"obsolete"}""")
        file.writeText("corrupted")
        val store = AuthSessionStore(file)
        assertFails { store.read(store.generation) }
        assertEquals("corrupted", file.readText())
    }
    @Test fun `failed persistence cannot publish logged in state`() = fixture { file ->
        val store = AuthSessionStore(file) { _, _ -> error("simulated storage failure") }
        var signedIn = false
        val generation = store.begin()
        assertFails { store.guarded(generation) { store.save(generation, response("new"), fresh = true); signedIn = true } }
        assertFalse(signedIn)
    }
    @Test fun `logout marker survives a failed token cleanup and fresh login replaces it`() = fixture { file ->
        val store = AuthSessionStore(file) { target, content ->
            if (target == file && content == "{}") error("token file locked")
            DurableFile.replace(target, content)
        }
        val old = store.begin(); store.save(old, response("old"), fresh = true)
        assertNotNull(store.signOut { })
        assertTrue(file.readText().contains("refresh-old"))
        val restarted = AuthSessionStore(file)
        assertEquals(0, restarted.read(restarted.generation).length())
        val fresh = restarted.begin(); restarted.save(fresh, response("new"), fresh = true)
        assertEquals("refresh-new", restarted.read(fresh).getString("refresh"))
        assertFalse(File(file.parentFile, "auth.json.signed-out").exists())
    }
    @Test fun `unwritable logout is reported and cannot reload within the current process`() = fixture { file ->
        var writable = true
        val store = AuthSessionStore(file) { target, content -> check(writable); DurableFile.replace(target, content) }
        val initial = store.begin(); store.save(initial, response("old"), fresh = true)
        writable = false
        val warning = store.signOut { }
        assertTrue(warning!!.contains("重启前"))
        assertEquals(0, store.read(store.generation).length())
    }
}
