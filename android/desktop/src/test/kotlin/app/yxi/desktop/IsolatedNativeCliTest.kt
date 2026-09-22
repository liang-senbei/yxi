package app.yxi.desktop

import app.yxi.agent.Rewind
import app.yxi.agent.RewindMessageInput
import app.yxi.agent.NativeRootVerification
import app.yxi.agent.Model
import app.yxi.agent.ChatItem
import app.yxi.agent.SessionProbe
import app.yxi.agent.RewindLiveVerification
import app.yxi.ssh.Shell
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real native executable with a loopback protocol stub; requires the dedicated no-network container. */
@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class IsolatedNativeCliTest {
    @Test fun `real CLI persists a conversation and resumes it against the fake endpoint`() {
        check(File("/.dockerenv").isFile && File("/sys/class/net").list()?.toSet() == setOf("lo"))
        val native = File("/opt/native/claude")
        check(native.isFile && native.canExecute()) { "Explicit native executable mount required" }
        val root = Files.createTempDirectory(Path.of("/sandbox/tmp"), "native-cli-").toFile()
        val home = root.resolve("home").apply { mkdir() }
        val config = home.resolve(".claude").apply { mkdir() }
        val project = root.resolve("project").apply { mkdir() }
        project.resolve("retained-tool.txt").writeText("RETAINED_TOOL_CONTENT\n")
        project.resolve("discarded-tool.txt").writeText("DISCARDED_TOOL_CONTENT\n")
        val script = root.resolve("stub.py")
        javaClass.classLoader.getResourceAsStream("rewind/anthropic_stub.py")!!.use { input -> script.outputStream().use { input.copyTo(it) } }
        val server = ProcessBuilder("python3", script.path, root.path).redirectErrorStream(true).redirectOutput(root.resolve("stub.log")).start()
        val socket = root.resolve("tmux.sock")
        fun isolated(builder: ProcessBuilder): ProcessBuilder = builder.apply {
            environment().apply {
                clear()
                put("HOME", home.path); put("CLAUDE_CONFIG_DIR", config.path)
                put("PATH", "/usr/bin:/bin"); put("LANG", "C.UTF-8"); put("TERM", "xterm-256color")
                put("ANTHROPIC_BASE_URL", "http://127.0.0.1:${root.resolve("port").readText().trim()}")
                put("ANTHROPIC_API_KEY", "sk-ant-yxi-container-test-only")
                put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1")
                put("DISABLE_TELEMETRY", "1"); put("DISABLE_AUTOUPDATER", "1")
            }
        }
        fun tmux(vararg args: String): String {
            val output = root.resolve("tmux-command.log")
            val p = isolated(ProcessBuilder(listOf("/usr/bin/tmux", "-S", socket.path) + args))
                .redirectErrorStream(true).redirectOutput(output).start()
            try {
                check(p.waitFor(10, TimeUnit.SECONDS)) { "Private tmux command timed out" }
                check(p.exitValue() == 0) { output.readText() }
                return output.readText()
            } finally { if (p.isAlive) p.destroyForcibly() }
        }
        fun invoke(label: String, vararg args: String, rewind: Rewind.Plan? = null,
            structuredInput: String? = null, inputPrompt: String? = null): JSONObject {
            val output = root.resolve("$label.json")
            val error = root.resolve("$label.err")
            val command = if (rewind == null) listOf(native.path) + args else listOf("/bin/sh", "-c",
                Rewind.command(native.path, project.path, Rewind.Capture(native.path, ""), rewind))
            val prompt = inputPrompt ?: rewind?.prompt ?: args[args.indexOf("-p") + 1]
            val answer = "answer:" + prompt.substringBefore(' ').substringBefore('\n')
            val builder = isolated(ProcessBuilder(command).directory(project).redirectOutput(output).redirectError(error))
            val process = builder.start()
            try {
                process.outputStream.use { input -> structuredInput?.let { input.write((it + "\n").toByteArray(Charsets.UTF_8)) } }
                check(process.waitFor(60, TimeUnit.SECONDS)) { "Native CLI timeout: $label" }
                assertEquals(0, process.exitValue(), error.readText().takeLast(1500))
                val raw = output.readText()
                if (rewind != null) {
                    assertEquals(Rewind.Outcome.Ok(rewind.sessionId, answer), Rewind.parse(raw), raw.takeLast(1500))
                }
                val result = if (structuredInput != null) raw.lineSequence().mapNotNull {
                    runCatching { JSONObject(it) }.getOrNull()
                }.last { it.optString("type") == "result" }
                else JSONObject(if (rewind == null) raw else raw.lineSequence().last { it.startsWith("{") })
                assertFalse(result.optBoolean("is_error"), output.readText().takeLast(1000))
                assertEquals(answer, result.getString("result"))
                return result
            } finally {
                process.toHandle().descendants().use { children -> children.forEach { it.destroyForcibly() } }
                if (process.isAlive) process.destroyForcibly()
            }
        }
        try {
            repeat(50) { if (!root.resolve("port").isFile && server.isAlive) Thread.sleep(100) }
            check(root.resolve("port").isFile && server.isAlive) { "Loopback stub failed to start" }
            val first = invoke("first", "-p", "KEEP-container-first", "--output-format", "json")
            val sid = first.getString("session_id")
            val pixels = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)
            pixels.setRGB(0, 0, 0x3366ff)
            val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(pixels, "png", it) }.toByteArray()
            val imageBlock = JSONObject().put("type", "image").put("source", JSONObject()
                .put("type", "base64").put("media_type", "image/png").put("data", java.util.Base64.getEncoder().encodeToString(png)))
            pixels.setRGB(0, 0, 0x22aa66)
            val selectedPng = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(pixels, "png", it) }.toByteArray()
            val selectedImageBlock = JSONObject(imageBlock.toString()).apply {
                getJSONObject("source").put("data", java.util.Base64.getEncoder().encodeToString(selectedPng))
            }
            val imageInput = RewindMessageInput.create(JSONObject().put("role", "user")
                .put("content", org.json.JSONArray().put(imageBlock).put(JSONObject().put("type", "text").put("text", "original image caption")).put(selectedImageBlock)),
                "FOLLOWUP-container-second", keepImageIndices = setOf(1))
            val second = invoke("second", "--resume", sid, "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                structuredInput = imageInput.json, inputPrompt = "FOLLOWUP-container-second")
            assertEquals(sid, second.getString("session_id"))
            val requests = root.resolve("requests.jsonl").readLines().map(::JSONObject)
            val followup = requests.last { it.getJSONArray("messages").toString().contains("FOLLOWUP-container-second") }
            assertTrue(followup.getBoolean("fake_auth"))
            assertTrue(followup.getJSONArray("messages").toString().contains("KEEP-container-first"))
            val sentImages = (0 until followup.getJSONArray("messages").length()).flatMap { index ->
                val content = followup.getJSONArray("messages").getJSONObject(index).optJSONArray("content")
                if (content == null) emptyList() else (0 until content.length()).mapNotNull {
                    content.optJSONObject(it)?.takeIf { block -> block.optString("type") == "image" }
                }
            }
            assertEquals(1, sentImages.size, "Image must reach the API as an image block")
            assertEquals("image/png", sentImages.single().getJSONObject("source").getString("media_type"))
            assertTrue(java.util.Base64.getDecoder().decode(sentImages.single().getJSONObject("source").getString("data")).isNotEmpty())
            val receivedPng = java.util.Base64.getDecoder().decode(sentImages.single().getJSONObject("source").getString("data"))
            val receivedPixels = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(receivedPng))
            assertEquals(0x22aa66, receivedPixels.getRGB(0, 0) and 0xffffff, "Only the selected original image may be sent")

            // Select the actual persisted parent UUID, never infer a turn from its display index.
            val transcript = config.walkTopDown().single { it.isFile && it.name == "$sid.jsonl" }
            val records = transcript.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            val selected = records.last { it.optString("type") == "user" &&
                it.optJSONObject("message")?.toString()?.contains("FOLLOWUP-container-second") == true }
            val anchor = selected.getString("parentUuid")
            invoke("third", "--resume", sid, "-p", "DROP-container-third", "--output-format", "json")
            val edited = invoke("edited", rewind = Rewind.Plan(sid, anchor, selected.getString("uuid"),
                prompt = "EDIT-container-second"))
            assertEquals(sid, edited.getString("session_id"))
            invoke("after-edit", "--resume", sid, "-p", "VERIFY-container-branch", "--output-format", "json")
            val allRequests = root.resolve("requests.jsonl").readLines().map(::JSONObject)
            for (marker in listOf("EDIT-container-second", "VERIFY-container-branch")) {
                val payload = allRequests.last { it.getJSONArray("messages").toString().contains(marker) }
                assertTrue(payload.getBoolean("fake_auth"))
                val messages = payload.getJSONArray("messages").toString()
                assertTrue(messages.contains("KEEP-container-first"), "Retained ancestor missing: $marker")
                assertTrue(messages.contains("EDIT-container-second"), "Edited turn missing: $marker")
                assertFalse(messages.contains("FOLLOWUP-container-second"), "Replaced turn leaked: $marker")
                assertFalse(messages.contains("DROP-container-third"), "Abandoned descendant leaked: $marker")
                assertFalse(messages.contains("\"type\":\"image\""), "Discarded image leaked: $marker")
            }

            // The product uses the CLI's stricter drops-turn guard when editing the last turn.
            val lastTurn = transcript.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .last { it.optString("type") == "user" &&
                    it.optJSONObject("message")?.toString()?.contains("VERIFY-container-branch") == true }
            val literal = "SINGLE-container-edit 'quoted' ${'$'}(touch ${root.path}/unexpected)\n中文🙂"
            invoke("single-edit", rewind = Rewind.Plan(sid, lastTurn.getString("parentUuid"),
                lastTurn.getString("uuid"), lastTurn.getString("uuid"), literal))
            invoke("after-single-edit", "--resume", sid, "-p", "VERIFY-container-single", "--output-format", "json")
            val singlePayload = root.resolve("requests.jsonl").readLines().map(::JSONObject).last()
                .getJSONArray("messages").toString()
            assertTrue(singlePayload.contains("SINGLE-container-edit"))
            assertTrue(singlePayload.contains("EDIT-container-second"))
            assertFalse(singlePayload.contains("VERIFY-container-branch"))
            assertFalse(root.resolve("unexpected").exists(), "Prompt must remain literal shell data")

            // Only this disposable project is trusted; no permission-mode bypass is used.
            val preferences = config.resolve(".claude.json")
            val prefs = if (preferences.exists()) JSONObject(preferences.readText()) else JSONObject()
            prefs.put("hasCompletedOnboarding", true).put("lastOnboardingVersion", "2.1.278")
                .put("projects", JSONObject().put(project.path, JSONObject().put("hasTrustDialogAccepted", true)))
                .put("customApiKeyResponses", JSONObject().put("approved",
                    org.json.JSONArray().put("sk-ant-yxi-container-test-only".takeLast(20))).put("rejected", org.json.JSONArray()))
            preferences.writeText(prefs.toString())
            tmux("-f", "/dev/null", "new-session", "-d", "-s", "cc-native-check", "-x", "180", "-y", "50",
                "-c", project.path, "${Shell.q(native.path)} --resume ${Shell.q(sid)}; exec /bin/bash --noprofile --norc")
            assertEquals(socket.path, tmux("display-message", "-p", "-t", "=cc-native-check:", "#{socket_path}").trim())
            var screen = ""
            var ready = false
            repeat(100) {
                if (!ready) {
                    screen = tmux("capture-pane", "-p", "-t", "=cc-native-check:")
                    ready = Model.borrowable(screen)
                    if (!ready) Thread.sleep(100)
                }
            }
            root.resolve("interactive-screen.txt").writeText(screen)
            assertTrue(ready, "Real resumed CLI must reach an empty input prompt: $screen")
            tmux("send-keys", "-t", "=cc-native-check:", "-l", "--", "INTERACTIVE-container-followup")
            tmux("send-keys", "-t", "=cc-native-check:", "Enter")
            var interactive: JSONObject? = null
            repeat(100) {
                if (interactive == null) {
                    interactive = root.resolve("requests.jsonl").readLines().mapNotNull {
                        runCatching { JSONObject(it) }.getOrNull()
                    }.lastOrNull { it.getJSONArray("messages").toString().contains("INTERACTIVE-container-followup") }
                    if (interactive == null) Thread.sleep(100)
                }
            }
            val interactiveMessages = requireNotNull(interactive) { "Interactive prompt never reached the stub" }
                .getJSONArray("messages").toString()
            assertTrue(interactiveMessages.contains("KEEP-container-first"))
            assertTrue(interactiveMessages.contains("SINGLE-container-edit"))
            assertFalse(interactiveMessages.contains("FOLLOWUP-container-second"))
            assertFalse(interactiveMessages.contains("DROP-container-third"))
            assertFalse(interactiveMessages.contains("VERIFY-container-branch"))

            // Use the actual desktop orchestration with real SSH, real tmux and the native CLI.
            ready = false
            repeat(100) {
                if (!ready) {
                    ready = Model.borrowable(tmux("capture-pane", "-p", "-t", "=cc-native-check:"))
                    if (!ready) Thread.sleep(100)
                }
            }
            assertTrue(ready, "Follow-up must finish before application rewind")
            IsolatedSshBridge(root.resolve("ssh"), isolated(ProcessBuilder()).environment().toMap(), socket).use { bridge ->
                runBlocking { withTimeout(120_000) {
                    bridge.conn.ssh.connect()
                    val session = SessionProbe.snapshot(bridge.conn.ssh).single { it.name == "cc-native-check" }
                    val key = taskNavigationKey(bridge.conn.host, session)
                    val gate = RewindDeliveryGate(root.resolve("application-gate.json"))
                    val memory = DesktopTranscriptMemory.Entry(transcript.path, 0)
                    val lease = memory.claim().first
                    fun renderedUsers(): List<String> {
                        val data = transcript.readBytes()
                        val start = memory.view.offset.toInt()
                        val end = data.indexOfLast { it == '\n'.code.toByte() } + 1
                        check(end >= start)
                        if (end > start) {
                            val lines = data.copyOfRange(start, end).toString(Charsets.UTF_8).lineSequence()
                                .filter { it.isNotBlank() }.toList()
                            memory.append(lease, lines, (end - start).toLong())
                        }
                        return memory.view.items.filterIsInstance<ChatItem.UserText>().map { it.text }
                    }
                    renderedUsers()
                    DesktopTranscriptMemory.put(key, memory)
                    try {
                        val target = transcript.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                            .last { it.optString("type") == "user" &&
                                it.optJSONObject("message")?.toString()?.contains("EDIT-container-second") == true }
                        ConversationRewind.restore(bridge.conn, session, target.getString("uuid"),
                            "APP-container-rewind", gate) { stage ->
                            root.resolve("application-progress.log").appendText("$stage\n")
                            if (stage == "正在载入回退后的会话…") {
                                assertTrue(RewindDeliveryGate(root.resolve("application-gate.json"))
                                    .pending(key)?.verification != null, "Recovery identity must precede the model result")
                            }
                        }
                        assertFalse(gate.blocked(key), "Gate must clear only after verified native resume")
                        val appRequest = root.resolve("requests.jsonl").readLines().map(::JSONObject).last {
                            it.getJSONArray("messages").toString().contains("APP-container-rewind")
                        }.getJSONArray("messages").toString()
                        assertTrue(appRequest.contains("KEEP-container-first"))
                        assertFalse(appRequest.contains("EDIT-container-second"))
                        assertFalse(appRequest.contains("SINGLE-container-edit"))
                        assertFalse(appRequest.contains("INTERACTIVE-container-followup"))
                        assertEquals(listOf("KEEP-container-first", "APP-container-rewind"), renderedUsers(),
                            "The live conversation cache must discard the old branch")
                        assertEquals(listOf("answer:KEEP-container-first", "answer:APP-container-rewind"), memory.view.items.filterIsInstance<ChatItem.AssistantText>().map { it.markdown })
                        val liveTools = memory.view.items.filterIsInstance<ChatItem.ToolCall>()
                        assertEquals(1, liveTools.size)
                        assertEquals("Read", liveTools.single().name)
                        assertTrue(liveTools.single().result.orEmpty().contains("RETAINED_TOOL_CONTENT"))
                        assertFalse(liveTools.single().isError)
                        val secondTarget = transcript.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                            .last { it.optString("type") == "user" &&
                                it.optJSONObject("message")?.toString()?.contains("APP-container-rewind") == true }
                        val refreshed = SessionProbe.snapshot(bridge.conn.ssh).single { it.name == session.name }
                        ConversationRewind.restore(bridge.conn, refreshed, secondTarget.getString("uuid"),
                            "SECOND-app-rewind", gate) { stage ->
                            root.resolve("application-progress.log").appendText("second: $stage\n")
                            if (stage == "正在载入回退后的会话…" || stage == "正在确认运行器与历史上下文…") {
                                assertTrue(gate.blocked(key), "Delivery must remain blocked during native restart")
                            }
                        }
                        assertFalse(gate.blocked(key), "Second rewind must independently verify before clearing its gate")
                        assertFalse(RewindDeliveryGate(root.resolve("application-gate.json")).blocked(key))
                        assertEquals(listOf("KEEP-container-first", "SECOND-app-rewind"), renderedUsers())
                        assertEquals(listOf("answer:KEEP-container-first", "answer:SECOND-app-rewind"), memory.view.items.filterIsInstance<ChatItem.AssistantText>().map { it.markdown })
                        val secondRequest = root.resolve("requests.jsonl").readLines().map(::JSONObject).last {
                            it.getJSONArray("messages").toString().contains("SECOND-app-rewind")
                        }.getJSONArray("messages").toString()
                        assertTrue(secondRequest.contains("KEEP-container-first"))
                        for (discarded in listOf("APP-container-rewind", "EDIT-container-second", "SINGLE-container-edit",
                            "INTERACTIVE-container-followup", "DROP-container-third", "FOLLOWUP-container-second")) {
                            assertFalse(secondRequest.contains(discarded), "Second rewind leaked $discarded")
                        }
                        val recoveryTarget = transcript.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                            .last { it.optString("type") == "user" &&
                                it.optJSONObject("message")?.toString()?.contains("SECOND-app-rewind") == true }
                        var disconnected = false
                        try {
                            ConversationRewind.restore(bridge.conn, refreshed, recoveryTarget.getString("uuid"),
                                "RECOVERY-app-rewind", gate) { stage ->
                                root.resolve("application-progress.log").appendText("recovery: $stage\n")
                                if (stage == "正在确认运行器与历史上下文…") {
                                    bridge.conn.ssh.disconnect()
                                    disconnected = true
                                    throw java.io.IOException("Injected connection loss before verification")
                                }
                            }
                            error("Expected the injected disconnect")
                        } catch (e: java.io.IOException) { assertTrue(disconnected, e.message) }
                        val recoveredGate = RewindDeliveryGate(root.resolve("application-gate.json"))
                        assertTrue(recoveredGate.blocked(key), "Restart must preserve uncertain delivery state")
                        assertTrue(recoveredGate.pending(key)?.verification != null)
                        val requestCount = root.resolve("requests.jsonl").readLines().size
                        bridge.conn.ssh.connect()
                        val recoveredSession = SessionProbe.snapshot(bridge.conn.ssh).single { it.name == session.name }
                        recheckRewindRecovery(bridge.conn, recoveredSession, recoveredGate)
                        assertFalse(recoveredGate.blocked(key))
                        assertEquals(requestCount, root.resolve("requests.jsonl").readLines().size,
                            "Read-only recovery must never resend the edited prompt")
                        assertEquals(1, root.resolve("requests.jsonl").readLines().map(::JSONObject).count {
                            it.getJSONArray("messages").toString().contains("RECOVERY-app-rewind")
                        })
                        assertEquals(listOf("KEEP-container-first", "RECOVERY-app-rewind"), renderedUsers())
                        val cold = requireNotNull(TranscriptBranchStart.load(bridge.conn.ssh, transcript.path, 400))
                        val coldMemory = DesktopTranscriptMemory.Entry(transcript.path, cold.size)
                        val coldView = requireNotNull(coldMemory.append(coldMemory.claim().first, requireNotNull(cold.lines), 0))
                        assertEquals(renderedUsers(), coldView.items.filterIsInstance<ChatItem.UserText>().map { it.text },
                            "Reopening the conversation must show the same branch as the live cache")
                        assertEquals(listOf("answer:KEEP-container-first", "answer:RECOVERY-app-rewind"), coldView.items.filterIsInstance<ChatItem.AssistantText>().map { it.markdown },
                            "Abandoned assistant replies must not reappear when reopening")
                        val coldTools = coldView.items.filterIsInstance<ChatItem.ToolCall>()
                        assertEquals(1, coldTools.size)
                        assertTrue(coldTools.single().result.orEmpty().contains("RETAINED_TOOL_CONTENT"))
                        assertFalse(coldTools.single().result.orEmpty().contains("DISCARDED_TOOL_CONTENT"))
                        val attachmentSeed = RewindMessageInput.create(JSONObject().put("role", "user")
                            .put("content", org.json.JSONArray().put(imageBlock).put(selectedImageBlock)
                                .put(JSONObject().put("type", "text").put("text", "original"))), "FOLLOWUP-container-image-source")
                        invoke("image-source", "--resume", sid, "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                            structuredInput = attachmentSeed.json, inputPrompt = "FOLLOWUP-container-image-source")
                        fun messageUuid(marker: String) = transcript.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                            .last { it.optString("type") == "user" && it.optJSONObject("message")?.toString()?.contains(marker) == true }.getString("uuid")
                        val imageTarget = RewindTargets.inspect(bridge.conn, recoveredSession, messageUuid("FOLLOWUP-container-image-source"))
                        val beforeRejectedEdit = root.resolve("requests.jsonl").readLines().size
                        kotlin.test.assertFailsWith<IllegalStateException> {
                            ConversationRewind.restore(bridge.conn, recoveredSession, imageTarget.messageUuid, "EDIT-stale-selection",
                                recoveredGate, imageSelection = RewindImageSelection(imageTarget.copy(size = imageTarget.size - 1), setOf(1)))
                        }
                        kotlin.test.assertFailsWith<IllegalArgumentException> {
                            ConversationRewind.restore(bridge.conn, recoveredSession, imageTarget.messageUuid, "EDIT-invalid-selection",
                                recoveredGate, imageSelection = RewindImageSelection(imageTarget, setOf(2)))
                        }
                        assertFalse(recoveredGate.blocked(key), "Rejected image selection must not start a history mutation")
                        assertEquals(beforeRejectedEdit, root.resolve("requests.jsonl").readLines().size,
                            "Stale or invalid image selections must be rejected before requesting a model")
                        ConversationRewind.restore(bridge.conn, recoveredSession, imageTarget.messageUuid, "EDIT-container-image-restored",
                            recoveredGate, imageSelection = RewindImageSelection(imageTarget, setOf(1)))
                        assertFalse(recoveredGate.blocked(key))
                        val imageRequest = root.resolve("requests.jsonl").readLines().map(::JSONObject).last {
                            it.getJSONArray("messages").toString().contains("EDIT-container-image-restored")
                        }.getJSONArray("messages")
                        val retainedImages = (0 until imageRequest.length()).flatMap { i ->
                            val content = imageRequest.getJSONObject(i).optJSONArray("content")
                            if (content == null) emptyList() else (0 until content.length()).mapNotNull { j ->
                                content.optJSONObject(j)?.takeIf { it.optString("type") == "image" }
                            }
                        }
                        assertEquals(1, retainedImages.size)
                        val retainedBytes = java.util.Base64.getDecoder().decode(retainedImages.single().getJSONObject("source").getString("data"))
                        assertEquals(0x22aa66, javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(retainedBytes)).getRGB(0, 0) and 0xffffff)
                        val removeTarget = RewindTargets.inspect(bridge.conn, recoveredSession, messageUuid("EDIT-container-image-restored"))
                        ConversationRewind.restore(bridge.conn, recoveredSession, removeTarget.messageUuid, "EDIT-container-images-removed",
                            recoveredGate, imageSelection = RewindImageSelection(removeTarget, emptySet()))
                        assertFalse(recoveredGate.blocked(key))
                        val removedRequest = root.resolve("requests.jsonl").readLines().map(::JSONObject).last {
                            it.getJSONArray("messages").toString().contains("EDIT-container-images-removed")
                        }.getJSONArray("messages").toString()
                        assertFalse(removedRequest.contains("\"type\":\"image\""), "Explicitly removed images must not be sent")
                        // Capability proof for the first-turn path. Only the dedicated fixture is driven.
                        val beforeRootUsers = renderedUsers()
                        assertEquals(listOf("KEEP-container-first", "RECOVERY-app-rewind", "EDIT-container-images-removed"), beforeRootUsers)
                        fun captureUntil(label: String, predicate: (String) -> Boolean): String {
                            var captured = ""
                            repeat(100) {
                                captured = tmux("capture-pane", "-p", "-t", "=cc-native-check:")
                                if (predicate(captured)) {
                                    root.resolve("$label.txt").writeText(captured)
                                    return captured
                                }
                                Thread.sleep(100)
                            }
                            root.resolve("$label.txt").writeText(captured)
                            error("Native first-turn fixture did not reach $label")
                        }
                        captureUntil("root-ready") { Model.borrowable(it) }
                        val rootCapture = (Rewind.parseCapture(bridge.conn.ssh.exec(Rewind.captureCommand(session.name))) as Rewind.Got).capture
                        val rootTime = bridge.conn.ssh.exec("python3 -c 'import time; print(time.time())'").trim().toDouble()
                        val rootText = "ROOT-container-restarted\n第二行中文🙂，保持为同一条消息。"
                        val rootHash = java.security.MessageDigest.getInstance("SHA-256").digest(rootText.toByteArray())
                            .joinToString("") { "%02x".format(it) }
                        val rootQuery = NativeRootVerification.Query(RewindLiveVerification.RuntimeIdentity(session.name,
                            session.runtimeId, rootCapture.paneId, rootCapture.exe, rootCapture.pid, sid, rootTime),
                            transcript.path, messageUuid("KEEP-container-first"), rootHash)
                        val rootSource = RewindTargets.inspect(bridge.conn, recoveredSession, rootQuery.originalMessageUuid)
                        val sourceHash = bridge.conn.ssh.exec(NativeFirstTurnKeys.fingerprintCommand(rootSource)).trim()
                        root.resolve("root-source-hash.txt").writeText(sourceHash)
                        val unchangedRequests = root.resolve("requests.jsonl").readLines().size
                        val readyScreen = captureUntil("root-guard-ready") { Model.borrowable(it) }
                        assertFalse(NativeFirstTurnKeys.sent(bridge.conn.ssh.exec(NativeFirstTurnKeys.command(
                            rootQuery.runtime, rootSource, sourceHash, "stale screen", NativeFirstTurnKeys.Action.Open))))
                        assertFalse(NativeFirstTurnKeys.sent(bridge.conn.ssh.exec(NativeFirstTurnKeys.command(
                            rootQuery.runtime.copy(pid = "999999999"), rootSource, sourceHash, readyScreen, NativeFirstTurnKeys.Action.Open))))
                        assertFalse(NativeFirstTurnKeys.sent(bridge.conn.ssh.exec(NativeFirstTurnKeys.command(
                            rootQuery.runtime, rootSource.copy(size = rootSource.size - 1), sourceHash, readyScreen, NativeFirstTurnKeys.Action.Open))))
                        assertEquals(unchangedRequests, root.resolve("requests.jsonl").readLines().size)
                        val nativeGateFile = root.resolve("native-root-gate.json")
                        val nativeGate = RewindDeliveryGate(nativeGateFile)
                        var sawDurableRoot = false
                        NativeFirstTurnController.restore(bridge.conn, recoveredSession, rootSource, rootText, nativeGate) { message ->
                            root.resolve("root-controller-progress.txt").appendText(message + "\n")
                            if (nativeGate.blocked(key)) {
                                val persisted = RewindDeliveryGate(nativeGateFile)
                                assertTrue(persisted.pending(key)?.nativeRoot != null)
                                assertFalse(nativeGateFile.readText().contains("ROOT-container-restarted"))
                                sawDurableRoot = true
                            }
                        }
                        assertTrue(sawDurableRoot, "Controller must persist recovery before confirming restore")
                        assertFalse(nativeGate.blocked(key))
                        assertFalse(RewindDeliveryGate(nativeGateFile).blocked(key))
                        captureUntil("root-completed") { Model.borrowable(it) && "answer:ROOT-container-restarted" in it }
                        val rootRequest = root.resolve("requests.jsonl").readLines().map(::JSONObject).last {
                            it.getJSONArray("messages").toString().contains("ROOT-container-restarted")
                        }.getJSONArray("messages").toString()
                        for (old in beforeRootUsers) assertFalse(rootRequest.contains(old), "First-turn restore kept $old")
                        assertFalse(rootRequest.contains("RETAINED_TOOL_CONTENT"))
                        assertEquals(listOf(rootText), renderedUsers())
                        val rootProof = runRewindCommand(bridge.conn.ssh, NativeRootVerification.command(rootQuery))
                        root.resolve("root-proof.txt").writeText(rootProof)
                        assertTrue(NativeRootVerification.verified(rootProof), rootProof)
                        bridge.conn.ssh.exec(Rewind.cleanupCommand(rootCapture))
                        assertTrue(config.resolve("sessions").listFiles().orEmpty().any {
                            it.extension == "json" && JSONObject(it.readText()).optString("sessionId") == sid
                        }, "First-turn restore must retain the native conversation ID")
                    } catch (e: Exception) {
                        if (e is ConversationRewindFailure) root.resolve("application-failure.txt").writeText("${e.code}\n${e.detail}")
                        gate.pending(key)?.verification?.let { query ->
                            root.resolve("recovery-probe.txt").writeText(bridge.conn.ssh.exec(
                                RewindLiveVerification.command(query, regTimeoutSec = 1)))
                        }
                        root.resolve("registrations.json").writeText(org.json.JSONArray().apply {
                            config.resolve("sessions").listFiles().orEmpty().filter { it.extension == "json" }.forEach {
                                put(JSONObject(it.readText()))
                            }
                        }.toString())
                        root.resolve("pane-identity.txt").writeText(tmux("display-message", "-p", "-t", "=cc-native-check:",
                            "#{pane_pid}|#{pane_current_command}|#{session_name}:#{window_id}.#{pane_id}|#{session_name}:#{window_index}.#{pane_index}"))
                        throw e
                    } finally { DesktopTranscriptMemory.drop(key) }
                } }
            }
        } finally {
            if (socket.exists()) runCatching {
                root.resolve("final-screen.txt").writeText(tmux("capture-pane", "-p", "-t", "=cc-native-check:"))
            }
            if (socket.exists()) runCatching { tmux("kill-session", "-t", "=cc-native-check:") }
            server.destroyForcibly(); server.waitFor(5, TimeUnit.SECONDS)
            root.listFiles().orEmpty().filter { it.isFile && it.name != "stub.py" }.forEach {
                it.copyTo(File("/results/native-${it.name}"), overwrite = true)
            }
        }
    }
}
