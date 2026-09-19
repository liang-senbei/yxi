package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ba15113 CodexWorkspace.recover（按线程 ID 恢复）定向回归。按收紧后的范围只覆盖：
 * 成功路径（登记落盘、不发 turn/start）、同主机重复登记不重复、错误 ID 保留数据、不跨 host。
 * 复用主干 CodexTestSupport（FakeRunner/handshake/NoHostKeys）与 com.jcraft.jsch.NullChannel：
 * 进程管道桩，零真实模型、零网络、零登录。
 */
class CodexWorkspaceRecoverTest {
    private fun newWorkspace(): Pair<CodexWorkspace, File> {
        val dir = Files.createTempDirectory("yxi-recover-test").toFile()
        val ws = CodexWorkspace(InstructionQueue(File(dir, "queue.json")), File(dir, "registry.json"))
        return ws to dir
    }

    private fun conn(alias: String) = Conn(Host("h-$alias", alias, "127.0.0.1", 1), NoHostKeys)  // 端口 1，永不需要真连
    private fun seededRecord(conn: Conn, threadId: String) =
        CodexTaskRecord(projectKey(conn.host, "/"), threadId, "/projA", "原标题", 1_700_000_000_000)

    /** 同主机重复登记：直接返回已有记录（ dedup 在连通检查之前），标题等数据原样保留。 */
    @Test fun `duplicate registration on the same host returns the existing record`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val c = conn("主机A")
        val seeded = seededRecord(c, "thr-1")
        ws.registry.save(seeded)
        try {
            val got = ws.recover(c, "  thr-1  ", "新标题")  // 输入会 trim；重复命中
            assertEquals(seeded, got, "应返回已有记录本身")
            assertEquals("原标题", got.title, "已有记录不得被新标题覆盖")
            assertEquals(1, ws.registry.records.size, "不得产生第二条登记")
            assertFalse(ws.busy, "重复登记不应进入写入流程")
            assertEquals("", ws.recoveryThreadId)
        } finally { ws.close(); dir.deleteRecursively() }
    }

    /** 错误 ID（空白/超长/控制字符）：在登记与连接之前就拒绝，登记数据原样保留。 */
    @Test fun `invalid thread ids are rejected and the registry keeps its data`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val c = conn("主机A")
        val seeded = seededRecord(c, "thr-1")
        ws.registry.save(seeded)
        try {
            for (bad in listOf("", "   ", "a b", "a\tb", "\u0001", "x".repeat(257))) {
                try { ws.recover(c, bad, "t"); fail("ID「$bad」应被拒绝") }
                catch (e: Exception) { assertTrue(e.message!!.contains("请输入有效的任务编号"), "「$bad」→ ${e.message}") }
            }
            assertEquals(1, ws.registry.records.size)
            assertEquals(seeded, ws.registry.records.single(), "登记数据不得有任何变化")
            assertFalse(ws.busy)
        } finally { ws.close(); dir.deleteRecursively() }
    }

    /** 不跨 host：同 threadId 在另一台主机上不得复用记录——走到连通检查即证明 dedup 没跨主机命中。 */
    @Test fun `same thread id on another host does not reuse the record`() = runBlocking {
        val (ws, dir) = newWorkspace()
        val a = conn("主机A")
        val b = conn("主机B")
        ws.registry.save(seededRecord(a, "thr-1"))
        try {
            try { ws.recover(b, "thr-1", "t"); fail("跨主机不应复用记录") }
            catch (e: Exception) { assertTrue(e.message!!.contains("请先连接服务器"), "应走到连通检查而非返回 A 的记录：${e.message}") }
            assertEquals(1, ws.registry.records.size, "不得给主机B 生成登记")
            assertEquals(projectKey(a.host, "/"), ws.registry.records.single().hostKey, "仅剩主机A 的原记录")
        } finally { ws.close(); dir.deleteRecursively() }
    }

    /** 恢复成功路径：登记落盘、目录取服务器返回值、标题回退目录名；全程无 turn/start。 */
    @Test fun `recover registers the thread and sends no turn or start`() = runBlocking {
        assumeFakeRunner()
        FakeRunner("happy", "thr-rec").use { runner ->
            handshake(runner)
            val (ws, dir) = newWorkspace()
            val c = conn("主机C")
            ws.clientFactory = { runner.client }   // 测试缝（主干 CodexWorkspace internal var）：注入假运行器
            ws.connected = { true }
            try {
                val rec = ws.recover(c, "thr-rec", "")
                assertEquals("thr-rec", rec.threadId)
                assertEquals("/srv/demo", rec.directory, "目录应是服务器返回值")
                assertEquals(projectKey(c.host, "/"), rec.hostKey)
                assertEquals("demo", rec.title, "空标题回退目录名")
                assertEquals(1, ws.registry.records.size)
                assertFalse(ws.busy)
                assertEquals("", ws.recoveryThreadId, "登记成功后不留恢复线索")
                // 全程只应有过：initialize、initialized（通知）、thread/resume、thread/read（reconcile）
                val methods = runner.inboundJson().map { it.optString("method") }
                assertTrue("thread/resume" in methods, "应有 thread/resume：$methods")
                val forbidden = methods.filter { it in setOf("turn/start", "turn/steer", "turn/interrupt", "thread/start") }
                assertTrue(forbidden.isEmpty(), "恢复不得创建线程或发送轮次，却发了：$forbidden")
            } finally { ws.close(); dir.deleteRecursively() }
        }
    }

}
