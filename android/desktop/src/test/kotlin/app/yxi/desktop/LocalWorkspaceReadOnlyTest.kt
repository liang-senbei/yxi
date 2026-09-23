package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.condition.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class LocalWorkspaceReadOnlyTest {
    @TempDir lateinit var root: File
    @Test fun `Gemini npm discovery uses explicit Node argv and its native home override`() {
        val home = File(root, "user").apply { mkdirs() }
        val npm = File(root, "Node with spaces").apply { mkdirs() }
        val node = File(npm, "node.exe").apply { writeText("fixture") }
        val packageDir = File(npm, "node_modules/@google/gemini-cli").apply { mkdirs() }
        val entry = File(packageDir, "dist/index.js").apply { parentFile.mkdirs(); writeText("fixture") }
        File(packageDir, "package.json").writeText("""{"name":"@google/gemini-cli","bin":{"gemini":"dist/index.js"}}""")
        File(npm, "gemini.ps1").writeText("This wrapper must never execute")
        val customHome = File(root, "Gemini home")
        val environment = mapOf("PATH" to npm.path, "GEMINI_CLI_HOME" to customHome.path)
        val found = LocalRuntimeDiscovery.candidates(home, environment, windows = true).single { it.engine == "gemini" }
        assertEquals(listOf(node.canonicalPath, entry.canonicalPath), found.command)
        assertEquals(File(customHome, ".gemini").absolutePath, found.home)
        assertEquals(File(home, ".gemini").absolutePath, LocalRuntimeDiscovery.candidates(home, mapOf("PATH" to npm.path), windows = true).single { it.engine == "gemini" }.home)
        node.delete()
        assertTrue(LocalRuntimeDiscovery.candidates(home, environment, windows = true).single { it.engine == "gemini" }.problem.contains("Node.js"))
    }
    @Test fun `Windows npm manifests resolve to argv without executing wrappers or path traversal`() {
        val home = File(root, "用户 home").apply { mkdirs() }
        val npm = File(root, "Node tools").apply { mkdirs() }
        File(npm, "node.exe").writeText("fixture")
        val packageRoot = File(npm, "node_modules/@openai/codex").apply { mkdirs() }
        File(packageRoot, "bin").mkdirs(); File(packageRoot, "bin/codex.js").writeText("fixture")
        val manifest = File(packageRoot, "package.json")
        manifest.writeText("""{"name":"@openai/codex","bin":{"codex":"bin/codex.js"}}""")
        val env = mapOf("PATH" to npm.path, "CODEX_HOME" to File(home, "native codex").path)
        val found = LocalRuntimeDiscovery.candidates(home, env, windows = true).single { it.engine == "codex" }
        assertEquals(listOf(File(npm, "node.exe").canonicalPath, File(packageRoot, "bin/codex.js").canonicalPath), found.command)
        assertEquals(env["CODEX_HOME"], found.home)
        File(npm, "evil.js").writeText("fixture")
        manifest.writeText("""{"name":"@openai/codex","bin":{"codex":"../../../evil.js"}}""")
        assertTrue(LocalRuntimeDiscovery.candidates(home, env, windows = true).none { it.source == "npm" })
        val claude = File(npm, "node_modules/@anthropic-ai/claude-code").apply { mkdirs() }
        File(claude, "bin").mkdirs(); File(claude, "bin/claude.exe").writeBytes(byteArrayOf(77, 90, 0))
        File(claude, "package.json").writeText("""{"name":"@anthropic-ai/claude-code","bin":{"claude":"bin/claude.exe"}}""")
        val native = LocalRuntimeDiscovery.candidates(home, env, windows = true).single { it.engine == "claude" }
        assertEquals(listOf(File(claude, "bin/claude.exe").canonicalPath), native.command, "A native npm entry must not run through Node")
    }
    @Test fun `version detection distinguishes executable failures and bounds output`() = runBlocking {
        val script = File(root, "version-fixture").apply { writeText("#!/bin/sh\nprintf 'codex-cli 0.155.0-alpha.16\\n'\n"); setExecutable(true) }
        val candidate = LocalRuntimeInstallation("codex", "fixture", listOf(script.path), root.path)
        assertEquals("0.155.0-alpha.16", LocalRuntimeDiscovery.verify(candidate).version)
        script.writeText("#!/bin/sh\nexit 12\n")
        assertFalse(LocalRuntimeDiscovery.verify(candidate).ready)
        script.writeText("#!/bin/sh\nhead -c 20000 /dev/zero\n")
        assertFalse(LocalRuntimeDiscovery.verify(candidate).ready)
    }
    @Test fun `read-only history pages all providers and archives without resuming or refreshing login`() = runBlocking {
        val log = File(root, "requests.jsonl")
        val script = File(root, "native-fixture.py").apply { writeText("""
import sys,json
log=${JSONObject.quote(log.path)}
for line in sys.stdin:
 m=json.loads(line); method=m['method']; p=m.get('params',{})
 with open(log,'a') as f: f.write(json.dumps(m)+'\n')
 if method=='initialized': continue
 if method=='initialize': r={}
 elif method=='account/read':
  assert p['refreshToken'] is False
  r={'account':{'type':'chatgpt','planType':'pro'},'requiresOpenaiAuth':True}
 elif method=='config/read': r={'config':{'model_provider':'thirdparty'}}
 elif method=='thread/list':
  assert p['modelProviders']==[] and 'exec' in p['sourceKinds'] and 'subAgent' in p['sourceKinds']
  id='archived' if p['archived'] else 'second' if p.get('cursor') else 'first'
  r={'data':[{'id':id,'name':'历史 '+id,'cwd':'/project','modelProvider':'openai' if id=='first' else 'thirdparty','source':'exec','updatedAt':1}], 'nextCursor':'page2' if id=='first' else None}
 elif method=='thread/turns/list':
  r={'data':[{'id':'turn-'+str(p.get('cursor','new')),'items':[{'id':'message','type':'agentMessage','text':'保存的历史'}]}], 'nextCursor':'older' if not p.get('cursor') else None}
 else: raise RuntimeError('unexpected write method '+method)
 print(json.dumps({'id':m['id'],'result':r}),flush=True)
""".trimIndent()) }
        val candidate = LocalRuntimeInstallation("codex", "fixture", listOf("/usr/bin/python3", script.path), root.path, "0.155.0")
        LocalCodexHistory.connect(candidate).use { client ->
            val account = client.account(); assertTrue(account.label.contains("pro")); assertEquals("thirdparty", account.provider)
            val first = client.list(false, ""); assertEquals("openai", first.threads.single().provider)
            val second = client.list(false, "", first.next); assertEquals("second", second.threads.single().id); assertNull(second.next)
            assertEquals("archived", client.list(true, "").threads.single().id)
            val turns = client.turns("first"); assertEquals("older", turns.next)
            assertNull(client.turns("first", turns.next).next)
        }
        val methods = log.readLines().map { JSONObject(it).getString("method") }.toSet()
        assertEquals(setOf("initialize", "initialized", "account/read", "config/read", "thread/list", "thread/turns/list"), methods)
        assertTrue(methods.none { it.contains("resume") || it.contains("start") || it.contains("write") })
    }
    @Test fun `local navigation keeps server configuration out of the local workspace`() {
        val state = AppState()
        state.configurationHostId = "remote-fixture"
        state.selectLocal()
        assertTrue(state.isLocal); assertEquals(Page.LocalWorkspace, state.page)
        assertNull(state.configurationConnection()); assertEquals("", state.configurationHostId)
        assertEquals("本地", state.pluginLocation)
    }
}
