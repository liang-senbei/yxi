package app.yxi.desktop

import app.yxi.agent.Lines
import app.yxi.agent.ConfigRemote
import app.yxi.ssh.HostConfig
import app.yxi.ssh.HostKeys
import app.yxi.ssh.SshSession
import com.jcraft.jsch.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.Base64
import kotlin.test.*

/** Only runs against dev/test-remote-routes.sh's disposable localhost SSH server. */
class RemoteRoutesTest {
    @Test fun `catalog scopes and provider restoration use the real SSH transport`(): Unit = runBlocking {
        val fixture = System.getenv("YXI_ROUTE_FIXTURE")
        assumeTrue(fixture != null, "Requires the isolated SSH fixture")
        val root = File(fixture!!)
        val hostKey = root.resolve("host.pub").readText().trim().split(' ')[1]
        val keys = object : HostKeys {
            override var changedDetected = false
            override fun check(host: String?, key: ByteArray?): Int {
                val matches = key != null && Base64.getEncoder().encodeToString(key) == hostKey
                changedDetected = !matches
                return if (matches) HostKeyRepository.OK else HostKeyRepository.CHANGED
            }
            override fun add(key: HostKey?, ui: UserInfo?) = Unit
            override fun remove(host: String?, type: String?) = Unit
            override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
            override fun getKnownHostsRepositoryID() = "isolated-fixture"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
            override fun userInfo() = object : UserInfo {
                override fun getPassphrase(): String? = null
                override fun getPassword(): String? = null
                override fun promptPassword(message: String?) = false
                override fun promptPassphrase(message: String?) = false
                override fun promptYesNo(message: String?) = false
                override fun showMessage(message: String?) = Unit
            }
        }
        val home = root.resolve("home").absolutePath
        val ssh = SshSession(HostConfig("fixture", "127.0.0.1", root.resolve("port").readText().trim().toInt(), "root", HostConfig.Auth.PrivateKey(root.resolve("client").readText())), keys)
        try {
            ssh.connect()
            assertEquals(home, ssh.exec("printf %s \"\$HOME\"").trim())
            val remotePort = root.resolve("http-port").readText().trim().toInt()
            val first = ssh.forwardPreview(remotePort)
            val second = ssh.forwardPreview(remotePort)
            fun forwardedText(port: Int): String = java.net.URI("http://127.0.0.1:$port/").toURL().openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }.getInputStream().bufferedReader().use { it.readText() }
            try {
                assertNotEquals(first.localPort, second.localPort)
                assertEquals("preview-forward-fixture", forwardedText(first.localPort))
                first.close(); first.close()
                assertFalse(first.active)
                assertEquals("preview-forward-fixture", forwardedText(second.localPort))
            } finally { first.close(); second.close() }
            assertEquals(emptyList(), Lines.list(ssh))
            val user = editedRoute(Lines.Line("u", "User"), "User", "https://user.invalid/v1", "dummy-u", "model-u")
            val project = editedRoute(Lines.Line("p", "Project"), "Project", "https://project.invalid/v1", "", "model-p", "dummy-token")
            val codex = editedRoute(Lines.Line("c", "Codex", agent = Lines.CODEX), "Codex", "https://codex.invalid/v1", "dummy-c", "model-c")
            val all = listOf(user, project, codex)
            assertNull(Lines.saveList(ssh, all))
            assertTrue(routeCatalogEqual(all, Lines.list(ssh)!!))
            val staleRevision = app.yxi.agent.RemoteAtomicJson.read(ssh, "$home/.yxi/lines.json").revision
            val revised = listOf(user.copy(name = "Updated"), project, codex)
            assertNull(Lines.saveList(ssh, revised, expected = all))
            assertNotNull(Lines.saveList(ssh, all, expected = all))
            assertTrue(routeCatalogEqual(revised, Lines.list(ssh)!!))
            assertNotNull(app.yxi.agent.RemoteAtomicJson.write(ssh, "$home/.yxi/lines.json", "[]", staleRevision))
            assertTrue(routeCatalogEqual(revised, Lines.list(ssh)!!))
            assertNotNull(app.yxi.agent.RemoteAtomicJson.write(ssh, "$home/.yxi/lines.json", "broken-json", app.yxi.agent.RemoteAtomicJson.read(ssh, "$home/.yxi/lines.json").revision))
            assertTrue(routeCatalogEqual(revised, Lines.list(ssh)!!))
            assertTrue(ssh.exec("find \"\$HOME/.yxi/backups\" -name 'route-catalog-*' -type f | wc -l").trim().toInt() > 0)
            assertEquals("600", ssh.exec("stat -c %a \"\$HOME/.yxi/lines.json\"").trim())
            assertNull(Lines.saveList(ssh, all, expected = revised))
            assertNull(Lines.apply(ssh, user, all = all).err)
            assertTrue(Lines.matches(user, Lines.active(ssh)!!.env))
            assertNull(Lines.apply(ssh, project, "$home/project", all).err)
            assertTrue(Lines.active(ssh, "$home/project")!!.fromProject)
            assertTrue(Lines.matches(project, Lines.active(ssh, "$home/project")!!.env))
            assertNull(Lines.clearProject(ssh, "$home/project"))
            assertTrue(Lines.matches(user, Lines.active(ssh, "$home/project")!!.env))
            assertEquals("Read", JSONObject(ConfigRemote.readFile(ssh, "$home/.claude/settings.json")!!).getJSONObject("permissions").getJSONArray("allow").getString(0))
            assertNull(Lines.applyCodex(ssh, codex))
            assertTrue(Lines.matchesCodex(codex, Lines.currentCodex(ssh)!!))
            assertNull(Lines.applyCodex(ssh, null))
            assertNull(Lines.currentCodex(ssh))
            assertTrue(ConfigRemote.readFile(ssh, "$home/.codex/config.toml")!!.contains("original-model"))
            assertNull(Lines.apply(ssh, null, all = all).err)
            assertTrue(Lines.active(ssh)!!.env.isDefault)
            val configBeforeRemoval = ConfigRemote.readFile(ssh, "$home/.claude/settings.json")
            assertNull(Lines.saveList(ssh, all.filterNot { it.id == codex.id }, expected = all))
            assertEquals(listOf(user.id, project.id), Lines.list(ssh)!!.map { it.id })
            assertEquals(configBeforeRemoval, ConfigRemote.readFile(ssh, "$home/.claude/settings.json"))
            val catalogPath = "$home/.yxi/lines.json"
            val prior = app.yxi.agent.RemoteAtomicJson.read(ssh, catalogPath)
            val malformed = """[{"id":"u"},"unexpected"]"""
            assertNull(app.yxi.agent.RemoteAtomicJson.write(ssh, catalogPath, malformed, prior.revision))
            assertNull(Lines.list(ssh))
            assertNotNull(Lines.saveList(ssh, all))
            assertEquals(malformed, app.yxi.agent.RemoteAtomicJson.read(ssh, catalogPath).text)
            ssh.exec("mv \"\$HOME/.yxi\" \"\$HOME/.yxi-saved\" && printf blocked > \"\$HOME/.yxi\"")
            assertNotNull(Lines.saveList(ssh, all))
            Unit
        } finally { ssh.disconnect() }
    }
}
