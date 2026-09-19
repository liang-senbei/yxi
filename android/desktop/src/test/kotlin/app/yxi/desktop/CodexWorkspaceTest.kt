package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class CodexWorkspaceTest {

    private fun fixture(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("yxi-workspace").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    private fun conn() = Conn(Host("h1", "别名", "srv.example"), NoHostKeys)

    /** 真实登记文件 + 假运行器工厂；返回的清理函数关闭所有假进程。 */
    private fun workspace(dir: File, mode: String, threadId: String = "thr-1"): Pair<CodexWorkspace, () -> Unit> {
        val runners = mutableListOf<FakeRunner>()
        val ws = CodexWorkspace(InstructionQueue(File(dir, "queue.json")), File(dir, "registry.json"))
        ws.clientFactory = { _ -> FakeRunner(mode, threadId).also { runners += it }.client }
        ws.connected = { true }
        return ws to { runners.forEach { runCatching { it.close() } } }
    }

    @Test
    fun `registry roundtrip persists task records`() = fixture { dir ->
        val file = File(dir, "registry.json")
        val record = CodexTaskRecord("host/srv", "thr-9", "/srv/demo", "标题", 123L)
        val first = CodexTaskRegistry(file)
        first.save(record)
        assertEquals(listOf(record), CodexTaskRegistry(file).records)
    }

    @Test
    fun `create registers thread then reopen resumes it and disconnect keeps records`() = fixture { dir ->
        val (ws, cleanup) = workspace(dir, "create-ok")
        try {
            val c = conn()
            val record = runBlocking { ws.create(c, "/srv/demo", "标题") }
            assertEquals("thr-1", record.threadId)
            assertEquals("/srv/demo", record.directory)
            assertEquals(projectKey(c.host, "/"), record.hostKey)
            assertTrue(ws.controllers.containsKey(record.key), "创建后应挂上控制器")
            assertEquals("", ws.recoveryThreadId)
            assertEquals(listOf(record), ws.registry.records)
            ws.close()

            // 重开：同登记文件恢复同一线程
            val (reopened, cleanup2) = workspace(dir, "create-ok")
            try {
                val stored = reopened.registry.records.single()
                assertEquals(record, stored)
                val reopenedConn = conn()
                val controller = runBlocking { reopened.open(reopenedConn, stored) }
                assertTrue(controller.ready)
                assertTrue(reopened.controllers.containsKey(stored.key))
                // 断开连接（同一连接对象）：控制器清空，登记保留
                reopened.disconnect(reopenedConn)
                assertTrue(reopened.controllers.isEmpty())
                assertEquals(listOf(record), reopened.registry.records)
            } finally { cleanup2() }
        } finally { cleanup() }
    }

    @Test
    fun `reopen issues thread resume not thread start`() = fixture { dir ->
        val (ws, cleanup) = workspace(dir, "create-ok")
        try {
            val c = conn()
            val record = runBlocking { ws.create(c, "/srv/demo", "标题") }
            ws.close()
            val (reopened, cleanup2) = workspace(dir, "create-ok")
            try {
                runBlocking { reopened.open(conn(), reopened.registry.records.single()) }
                val resumed = reopened.controllers.getValue(record.key)
                assertTrue(resumed.ready)
            } finally { cleanup2() }
            // 第二个假运行器只应看到 resume/read，不应看到 start
        } finally { cleanup() }
    }

    @Test
    fun `open refuses records from another host`() = fixture { dir ->
        val (ws, cleanup) = workspace(dir, "create-ok")
        try {
            val foreign = CodexTaskRecord("other/host", "thr-1", "/srv", "外来任务", 1L)
            val error = assertFailsWith<IllegalStateException> { runBlocking { ws.open(conn(), foreign) } }
            assertEquals("任务不属于当前服务器配置", error.message)
            assertTrue(ws.controllers.isEmpty(), "拒绝时不得创建控制器或发起连接")
        } finally { cleanup() }
    }

    @Test
    fun `registry write failure keeps recoveryThreadId for manual recovery`() = fixture { dir ->
        val (ws, cleanup) = workspace(dir, "create-ok")
        try {
            val regFile = File(dir, "registry.json")
            regFile.delete(); regFile.mkdirs() // 登记位置变成目录：保存必然失败
            val failure = runCatching { runBlocking { ws.create(conn(), "/srv/demo", "标题") } }.exceptionOrNull()
            assertNotNull(failure, "登记写失败必须让创建失败")
            // 服务器线程 ID 已产生但本地没存上：必须保留出来供人工恢复
            assertEquals("thr-1", ws.recoveryThreadId)
            assertFalse(ws.busy)
            assertTrue(ws.registry.error.contains("未保存"), "实际 error：${ws.registry.error}")
        } finally { cleanup() }
    }
}
