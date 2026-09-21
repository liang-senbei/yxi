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
    @org.junit.jupiter.api.Disabled("Host SSH fixture is quarantined pending dedicated container isolation; see design/windows-test-isolation-incident.md")
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
            // Disposable runtimes and a private tmux socket: never start real agents.
            suspend fun launch(plan: DesktopLaunchPlan) = ssh.exec(plan.command()).lineSequence().last { it.startsWith(app.yxi.agent.Dirs.TAG + ":") }.substringAfter(':')
            val launchA = DesktopLaunchPlan("$home/one/项目 ' \$(touch INJECTED)/same", "claude", DesktopLaunchPlan.newRequestId())
            val launchB = DesktopLaunchPlan("$home/two/same", "codex", DesktopLaunchPlan.newRequestId())
            assertEquals("ok", launch(launchA))
            assertEquals("exists", launch(launchA))
            assertEquals("ok", launch(launchB))
            assertEquals("2", ssh.exec("tmux list-sessions -F '#{session_name}' | wc -l").trim())
            assertFalse(root.resolve("home/INJECTED").exists())
            assertEquals("conflict", launch(launchA.copy(directory = "$home/elsewhere/same")))
            root.resolve("home/.local/bin/codex").delete()
            val missing = DesktopLaunchPlan("$home/must-not-create", "codex", DesktopLaunchPlan.newRequestId())
            assertEquals("missing-runtime", launch(missing))
            assertFalse(File(missing.directory).exists())
            root.resolve("home/.local/bin/codex").apply { writeText("#!/bin/sh\nexit 1\n"); setExecutable(true) }
            assertEquals("exited", launch(missing))
            val receiver = root.resolve("home/receiver.py")
            receiver.writeText("import sys,pathlib\np=pathlib.Path(__file__).with_suffix('.txt')\nprint('READY',flush=True)\nwhile True:\n line=sys.stdin.readline()\n if not line: break\n with p.open('a') as f: f.write(line)\n")
            ssh.exec("tmux new-session -d -s cc-delivery-fixture -x 100 -y 24 python3 ${app.yxi.ssh.Shell.q(receiver.absolutePath)}")
            kotlinx.coroutines.delay(400)
            val runtime = ssh.exec("tmux display-message -p -t '=cc-delivery-fixture:' '#{pid}:#{session_id}:#{session_created}'").trim()
            val target = app.yxi.agent.Session("cc-delivery-fixture", 1, false, home, 0, app.yxi.agent.SessionState.Idle, "", 0.0, runtimeId = runtime)
            val screen = ssh.exec("tmux capture-pane -p -t '=cc-delivery-fixture:'")
            val instruction = QueuedInstruction("delivery", "fixture", "literal ' dollar \$value")
            assertTrue(ssh.exec(instructionDeliveryCommand(target.copy(runtimeId = "1:\$99:1"), instruction, screen)).contains("__YXI_DELIVERY__:blocked"))
            assertTrue(ssh.exec(instructionDeliveryCommand(target, instruction, "changed screen")).contains("__YXI_DELIVERY__:blocked"))
            assertTrue(ssh.exec(instructionDeliveryCommand(target, instruction.copy(attachments = listOf(InstructionAttachment("missing", "$home/missing-file"))), screen)).contains("__YXI_DELIVERY__:attachment"))
            assertFalse(root.resolve("home/receiver.txt").exists())
            assertTrue(ssh.exec(instructionDeliveryCommand(target, instruction, screen)).contains("__YXI_DELIVERY__:terminal"))
            kotlinx.coroutines.delay(400)
            assertEquals(instruction.text + "\n", root.resolve("home/receiver.txt").readText())
            val remotePort = root.resolve("http-port").readText().trim().toInt()
            val normalSessions = ssh.exec("tmux list-sessions -F '#{session_name}'").trim()
            val freePort = java.net.ServerSocket(0).use { it.localPort }
            val plan = PreviewServicePlan("a".repeat(64), "$home/project", "printf 'DEV_READY\\n'; exec sleep 600", freePort)
            suspend fun service(command: String) = PreviewServicePlan.result(ssh.exec(command))
            val started = service(plan.startCommand())
            assertEquals("running", started.getString("state"), started.toString())
            val previewRuntime = started.getString("runtime")
            assertEquals(previewRuntime, service(plan.startCommand()).getString("runtime"))
            assertTrue(service(plan.startCommand()).getBoolean("reused"))
            kotlinx.coroutines.withTimeout(5000) { while (!service(plan.statusCommand()).getString("log").contains("DEV_READY")) kotlinx.coroutines.delay(100) }
            assertEquals("not-listening", service(plan.statusCommand()).getString("readiness"))
            val unrelatedRequests = java.util.concurrent.atomic.AtomicInteger()
            val unrelated = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", freePort), 0)
            unrelated.createContext("/") { exchange -> unrelatedRequests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
            unrelated.start()
            try {
                assertEquals("unmatched-listener", service(plan.statusCommand()).getString("readiness"))
                assertEquals(0, unrelatedRequests.get())
            } finally { unrelated.stop(0) }
            val changedPlan = plan.copy(command = "exec sleep 600")
            assertEquals("configuration-conflict", service(changedPlan.startCommand()).getString("state"))
            assertEquals("stopped", service(plan.stopCommand(previewRuntime)).getString("state"))
            val newer = service(changedPlan.startCommand())
            assertNotEquals(previewRuntime, newer.getString("runtime"))
            assertEquals("changed", service(changedPlan.stopCommand(previewRuntime)).getString("state"))
            assertEquals("running", service(changedPlan.statusCommand()).getString("state"))
            assertEquals("stopped", service(changedPlan.stopCommand(newer.getString("runtime"))).getString("state"))
            val failedPlan = plan.copy(command = "printf 'DEV_FAILED\\n'; exit 7")
            service(failedPlan.startCommand())
            var failed = service(failedPlan.statusCommand())
            kotlinx.coroutines.withTimeout(5000) { while (failed.getString("state") != "exited") { kotlinx.coroutines.delay(100); failed = service(failedPlan.statusCommand()) } }
            assertEquals(7, failed.getInt("exitCode")); assertTrue(failed.getString("log").contains("DEV_FAILED"))
            service(failedPlan.stopCommand(failed.getString("runtime")))
            assertEquals("port-busy", service(plan.copy(port = remotePort).startCommand()).getString("state"))
            val healthScript = root.resolve("home/project/health-server.py")
            healthScript.writeText("import http.server,sys\nclass H(http.server.BaseHTTPRequestHandler):\n def do_GET(self):\n  self.send_response(200 if self.path=='/health/ready?check=one' else 503)\n  self.send_header('Content-Length','0')\n  self.end_headers()\nhttp.server.HTTPServer(('127.0.0.1',int(sys.argv[1])),H).serve_forever()\n")
            val healthPort = java.net.ServerSocket(0).use { it.localPort }
            val healthPlan = plan.copy(port = healthPort, command = "python3 health-server.py $healthPort")
            val healthStart = service(healthPlan.startCommand())
            var rootHealth = service(healthPlan.statusCommand())
            kotlinx.coroutines.withTimeout(10000) { while (rootHealth.optString("readiness") != "http-response") { kotlinx.coroutines.delay(100); rootHealth = service(healthPlan.statusCommand()) } }
            assertEquals(503, rootHealth.getInt("httpStatus"))
            val customHealth = service(healthPlan.statusCommand("/health/ready?check=one"))
            assertEquals("ready", customHealth.getString("readiness"))
            assertEquals("/health/ready?check=one", customHealth.getString("probePath"))
            assertEquals(healthStart.getString("runtime"), customHealth.getString("runtime"))
            assertEquals(503, service(healthPlan.statusCommand()).getInt("httpStatus"))
            service(healthPlan.stopCommand(customHealth.getString("runtime")))
            val ipv6Port = runCatching { java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("::1")).use { it.localPort } }.getOrNull()
            if (ipv6Port != null) {
                val ipv6Plan = plan.copy(port = ipv6Port, command = "python3 -m http.server $ipv6Port --bind ::1")
                service(ipv6Plan.startCommand())
                var ready6 = service(ipv6Plan.statusCommand())
                kotlinx.coroutines.withTimeout(10000) { while (ready6.optString("readiness") != "ready") { kotlinx.coroutines.delay(100); ready6 = service(ipv6Plan.statusCommand()) } }
                assertEquals("::1", ready6.getString("probeHost"))
                assertEquals(200, ready6.getInt("httpStatus"))
                service(ipv6Plan.stopCommand(ready6.getString("runtime")))
                println("preview readiness: IPv6 ownership and HTTP verified")
            } else println("preview readiness: IPv6 loopback unavailable in fixture")
            val foreignPlan = plan.copy(project = "b".repeat(64))
            val socketArg = app.yxi.ssh.Shell.q("$home/.yxi/preview-runtime/tmux.sock")
            val foreignName = "pv-" + foreignPlan.project
            ssh.exec("tmux -f /dev/null -S $socketArg new-session -d -s $foreignName /bin/sleep 600")
            val foreignRuntime = ssh.exec("tmux -S $socketArg display-message -p -t '=$foreignName:' '#{pid}:#{session_id}:#{session_created}'").trim()
            val foreignResult = service(foreignPlan.startCommand())
            assertEquals("unowned", foreignResult.getString("state"))
            assertFalse(foreignResult.has("reused"))
            assertEquals("changed", service(foreignPlan.stopCommand(foreignRuntime)).getString("state"))
            assertEquals("present", ssh.exec("tmux -S $socketArg has-session -t '=$foreignName' && printf present").trim())
            assertEquals(normalSessions, ssh.exec("tmux list-sessions -F '#{session_name}'").trim())
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
