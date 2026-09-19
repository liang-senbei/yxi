package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class CodexWorkspaceTest {

    private val runners = mutableListOf<FakeRunner>()

    private fun fixture(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("yxi-workspace").toFile()
        try { block(dir) } finally { dir.deleteRecursively(); runners.clear() }
    }

    private fun conn() = Conn(Host("h1", "别名", "srv.example"), NoHostKeys)

    /** 真实登记文件 + 假运行器工厂；返回的清理函数关闭所有假进程。 */
    private fun workspace(dir: File, mode: String, threadId: String = "thr-1"): Pair<CodexWorkspace, () -> Unit> {
        assumeFakeRunner()
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

    @Test
    fun `create records connection configuration from the top-level result`() = fixture { dir ->
        val (ws, cleanup) = workspace(dir, "create-ok")
        try {
            val record = runBlocking { ws.create(conn(), "/srv/demo", "标题") }
            val controller = ws.controllers.getValue(record.key)
            // 6b362f6：start 顶层配置进 configured 字段，与线程元数据（prov-recorded）分层
            assertEquals("prov-live", controller.configuredProvider, "应取顶层 modelProvider 而非线程元数据")
            assertEquals("gpt-fake", controller.configuredModel, "应取顶层 model 而非线程元数据")
            // 元数据仍只进 reported 字段，两条线互不混淆
            assertEquals("prov-recorded", controller.reportedProvider)
            assertEquals("gpt-recorded", controller.reportedModel)
        } finally { cleanup() }
    }

    @Test
    fun `resume records configuration and history reconcile keeps it`() = fixture { dir ->
        val (first, cleanup1) = workspace(dir, "create-ok")
        try {
            runBlocking { first.create(conn(), "/srv/demo", "标题") }
            first.close()
            val (reopened, cleanup2) = workspace(dir, "create-ok")
            try {
                val controller = runBlocking { reopened.open(conn(), reopened.registry.records.single()) }
                // 恢复接线：resume 顶层配置同样进 configured
                assertEquals("prov-live", controller.configuredProvider)
                assertEquals("gpt-fake", controller.configuredModel)
                // 历史 reconcile 只改 reported，不得覆盖 configured
                runBlocking { controller.reconcile() }
                assertEquals("prov-live", controller.configuredProvider, "历史读取不得覆盖 configuredProvider")
                assertEquals("gpt-fake", controller.configuredModel, "历史读取不得覆盖 configuredModel")
                assertEquals("prov-recorded", controller.reportedProvider, "线程元数据应落到 reported")
                assertEquals("gpt-recorded", controller.reportedModel)
            } finally { cleanup2() }
        } finally { cleanup1() }
    }

    @Test
    fun `create initializes from the start snapshot without reading history`() = fixture { dir ->
        val (ws, cleanup) = workspace(dir, "create-snapshot")
        try {
            val c = conn()
            val record = runBlocking { ws.create(c, "/srv/demo", "标题") }
            assertEquals("thr-1", record.threadId)
            assertEquals("", ws.recoveryThreadId)
            val controller = ws.controllers.getValue(record.key)
            assertTrue(controller.ready, "实际 note：${controller.note}")
            assertTrue(controller.messages.isEmpty())
            // 新建路径信任 thread/start 的 idle+空轮次快照：全程不得发起 thread/read
            // （该模式里 resume 前的 read 必然回 -32601，真走了读取这里就会失败）
            val methods = runners.flatMap { it.inboundJson() }.map { it.optString("method") }
            assertTrue("thread/start" in methods, "实际 RPC：$methods")
            assertTrue("thread/read" !in methods, "新建后不得读取历史：$methods")
        } finally { cleanup() }
    }

    @Test
    fun `reopen after snapshot creation still resumes and reads history`() = fixture { dir ->
        val (first, cleanup1) = workspace(dir, "create-snapshot")
        try {
            runBlocking { first.create(conn(), "/srv/demo", "标题") }
            first.close()
        } finally { cleanup1(); runners.clear() } // 只核对恢复进程的 RPC
        val (ws, cleanup) = workspace(dir, "create-snapshot")
        try {
            val stored = ws.registry.records.single()
            val controller = runBlocking { ws.open(conn(), stored) }
            assertTrue(controller.ready, "实际 note：${controller.note}")
            // 原恢复路径不变：resume 之后仍要读取并核对历史
            assertEquals(listOf("msg-h0", "msg-h1"), controller.messages.map { it.id })
            val methods = runners.flatMap { it.inboundJson() }.map { it.optString("method") }
            assertTrue("thread/resume" in methods, "恢复必须走 resume：$methods")
            assertTrue("thread/read" in methods, "恢复必须读取历史：$methods")
        } finally { cleanup() }
    }
}
