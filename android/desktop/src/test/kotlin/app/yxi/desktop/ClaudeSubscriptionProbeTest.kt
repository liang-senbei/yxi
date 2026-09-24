package app.yxi.desktop

import kotlinx.coroutines.*
import java.io.File
import kotlin.test.*

class ClaudeSubscriptionProbeTest {
    @Test fun `probe rejects malformed oversized and nonzero responses and cancels its own process`(): Unit = runBlocking {
        check(!System.getenv("YXI_ISOLATED_TEST_RUN").isNullOrBlank())
        check(File("/.dockerenv").exists())
        val root = File("/sandbox/tmp/subscription-probe").apply { mkdirs() }
        val script = File(root, "fake.py").apply { writeText("""
import json,os,pathlib,sys,time
mode=sys.argv[1]
assert sys.argv[2]=='--settings'
assert sys.argv[4:]==['auth','status','--json']
assert 'ANTHROPIC_API_KEY' not in os.environ
pathlib.Path(sys.argv[0]+'.'+mode+'.pid').write_text(str(os.getpid()))
if mode=='malformed': print('synthetic-private-invalid-response')
elif mode=='large':
    print('x'*70000,flush=True)
    time.sleep(60)
elif mode=='cancel': time.sleep(60)
else:
    print(json.dumps({'loggedIn':True,'authMethod':'oauth_token','apiProvider':'firstParty'}))
    sys.exit(7 if mode=='nonzero' else 0)
""".trimIndent()) }
        val unrelated = ProcessBuilder("/bin/sleep", "60").start()
        try {
            for (mode in listOf("success", "malformed", "large", "nonzero", "cancel")) {
                val command = listOf("/usr/bin/python3", script.path, mode)
                val environment = mapOf("PATH" to "/usr/bin:/bin", "HOME" to root.path, "ANTHROPIC_API_KEY" to "fixture-conflict")
                if (mode == "success") ClaudeSubscriptionProbe.verify(command, root, environment)
                else if (mode == "cancel") {
                    val work = launch { ClaudeSubscriptionProbe.verify(command, root, environment) }
                    withTimeout(5000) { while (!File(script.path + ".$mode.pid").exists()) delay(20) }
                    work.cancelAndJoin()
                    assertTrue(work.isCancelled)
                } else {
                    val failure = assertFailsWith<IllegalStateException> { withTimeout(5000) { ClaudeSubscriptionProbe.verify(command, root, environment) } }
                    assertFalse(failure is CancellationException, "A test timeout must not count as a native response rejection")
                    assertFalse(failure.message.orEmpty().contains("synthetic-private"))
                }
                val pid = File(script.path + ".$mode.pid").readText().toLong()
                withTimeout(5000) { while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(20) }
                assertTrue(unrelated.isAlive)
            }
        } finally { unrelated.destroyForcibly(); unrelated.waitFor() }
    }
}
