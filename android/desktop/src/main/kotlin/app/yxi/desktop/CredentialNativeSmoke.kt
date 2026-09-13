package app.yxi.desktop

import java.io.File
import java.util.Base64
import org.json.JSONObject

internal fun runCredentialNativeSmoke(reopen: Boolean) {
    check(System.getProperty("os.name").startsWith("Windows"))
    val root = File(System.getProperty("yxi.credential.smokeDir") ?: error("An isolated fixture directory is required"))
    val marker = root.resolve("synthetic-fixture")
    val legacy = root.resolve("auth.json")
    val protector = WindowsCredentialProtector()
    val store = AuthSessionStore(legacy, protector)
    if (!reopen) {
        check(!root.exists()) { "Use a fresh fixture directory" }; check(root.mkdirs())
        marker.writeText("yxi synthetic credentials")
        legacy.writeText("""{"access":"synthetic-access","refresh":"synthetic-refresh","exp":123}""")
        check(store.read(store.generation).getString("refresh") == "synthetic-refresh")
        check(legacy.readText() == "{}")
        store.save(store.generation, JSONObject().put("access_token", "synthetic-rotated-access").put("refresh_token", "synthetic-rotated-refresh"))
        val encrypted = root.resolve("auth.json.protected").readText()
        check(!encrypted.contains("synthetic-rotated-refresh"))
        val data = Base64.getDecoder().decode(JSONObject(encrypted).getString("data"))
        data[data.size / 2] = (data[data.size / 2].toInt() xor 1).toByte()
        check(runCatching { protector.unprotect(data) }.isFailure) { "Tampered DPAPI blob was accepted" }
        println("credential native migration rotation and tamper checks ok")
    } else {
        check(marker.readText() == "yxi synthetic credentials")
        check(store.read(store.generation).getString("refresh") == "synthetic-rotated-refresh")
        check(store.signOut { } == null)
        val fresh = AuthSessionStore(legacy, protector)
        check(fresh.read(fresh.generation).length() == 0)
        val generation = fresh.begin()
        fresh.save(generation, JSONObject().put("access_token", "synthetic-new-access").put("refresh_token", "synthetic-new-refresh"), fresh = true)
        check(fresh.read(generation).getString("refresh") == "synthetic-new-refresh")
        println("credential native cross-process reopen logout and login ok")
    }
}
