package app.yxi.desktop

import app.yxi.agent.ConfigRemote
import app.yxi.agent.Lines
import app.yxi.agent.SessionState
import app.yxi.ssh.HostKeys
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0cb6030 延后线路切换 + d43449e 执行层抽取：直接 launch 调 internal suspend [runDeferredRoute]，
 * 不经 Compose。断线/取消两例不依赖外部资源；其余各阶段经 dev/test-remote-routes.sh 的
 * 隔离 SSH fixture 跑（YXI_ROUTE_FIXTURE 门控）。
 * 「等待运行器输入状态可确认」门（Model.borrowable 要真 Claude Code 输入框）伪不出，属 D 组观察点。
 * 注：本回归跑在 review 源（ee677ae）上；首包产物仍是 3152af0 快照，执行层变更属下一批产物。
 */
class DeferredRouteRunnerTest {
    private suspend fun await(timeoutMs: Long = 20000, describe: suspend () -> String = { "" }, cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                val d = describe()
                error("等待超时（${timeoutMs}ms）${if (d.isEmpty()) "" else "：$d"}")
            }
            delay(50)
        }
    }

    private fun claudeLine(id: String, name: String, url: String) =
        Lines.Line(id = id, name = name, baseUrl = url, token = "tok-$id", agent = Lines.CLAUDE)

    private fun codexLine() = Lines.Line(
        id = "codex-1", name = "Codex中转", baseUrl = "https://codex.invalid/v1", apiKey = "sk-dt", agent = Lines.CODEX,
    )

    /** 只认 fixture 自己的 host key（同 RemoteRoutesTest），其它一律拒——不接受任意主机的键。 */
    private fun fixtureKeys(root: File) = object : HostKeys {
        private val expected = root.resolve("host.pub").readText().trim().split(' ')[1]
        override var changedDetected = false
        override fun check(host: String?, key: ByteArray?): Int {
            val matches = key != null && Base64.getEncoder().encodeToString(key) == expected
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

    /** 断线分支 + 两种取消：不需要任何外部资源。 */
    @Test fun `unreachable host keeps waiting and cancelling leaves no trace`() = runBlocking {
        val state = AppState()
        val conn = Conn(Host("h", "离线主机", "127.0.0.1", 1), FileHostKeys())  // 端口 1 永远连不上
        state.conns.add(conn)
        val l = claudeLine("L1", "线A", "https://a.invalid/v1")

        // 断线：循环持续等待，不失败、不写入、无错误通知
        val request = DeferredRoute(conn, l, null, listOf(l))
        state.deferredRoute = request
        val job: Job = launch { runDeferredRoute(state, request) }
        await { request.status == "等待原服务器重新连接" }
        delay(2500)  // 再过至少一个 tick：仍在等待
        assertEquals("等待原服务器重新连接", request.status)
        assertFalse(request.applying)
        assertEquals("", state.deferredRouteNotice, "断线等待不是失败，不应给错误通知")

        // 取消其一：把请求换掉（用户点「取消等待」的效果）→ 循环在下一个检查点自行退出
        state.deferredRoute = null
        await(5000) { job.isCompleted }
        assertEquals("", state.deferredRouteNotice, "取消是用户主动行为，不应产生失败通知")

        // 取消其二：直接取消协程（离开组合）→ CancellationException 静默结束，不污染状态
        val request2 = DeferredRoute(conn, l, null, listOf(l))
        state.deferredRoute = request2
        val job2 = launch { runDeferredRoute(state, request2) }
        await { request2.status == "等待原服务器重新连接" }
        job2.cancel()
        await(5000) { job2.isCompleted }
        assertEquals("", state.deferredRouteNotice)
        state.deferredRoute = null
    }

    /**
     * a1fcdb8（D1 观察的落地）：主机被**移除**（而非掉线）→ 下一个 tick 内取消等待，
     * 通知说明「未写入」，不再无限等待。仅 ssh 断开、conn 还在列表的行为由上一条用例守住。
     */
    @Test fun `removing the connection cancels the wait with a not written notice`() = runBlocking {
        val state = AppState()
        val conn = Conn(Host("h", "离线主机", "127.0.0.1", 1), FileHostKeys())  // 端口 1：连不上，也不需要连上
        state.conns.add(conn)
        val l = claudeLine("L1", "线A", "https://a.invalid/v1")

        val request = DeferredRoute(conn, l, null, listOf(l))
        state.deferredRoute = request
        val job = launch { runDeferredRoute(state, request) }
        await { request.status == "等待原服务器重新连接" }  // 先确认走的是「等重连」（conn 仍在列表）
        state.conns.remove(conn)  // 用户删除主机
        await(6000) { job.isCompleted }  // 两个 tick 内自行退出 = 不再等待
        assertTrue(state.deferredRouteNotice.contains("连接已移除"), "应说明取消原因：${state.deferredRouteNotice}")
        assertTrue(state.deferredRouteNotice.contains("未写入配置"), "应说明未写入：${state.deferredRouteNotice}")
        assertNull(state.deferredRoute)
        assertFalse(request.applying)
        assertEquals("等待原服务器重新连接", request.status, "请求停在等待态，未进入写入")
    }

    /** 其余分支需要能连、能写、能造 tmux 会话的隔离环境。 */
    @Test fun `deferred route over real ssh waits cancels recovers and writes`() = runBlocking {
        val fixture = System.getenv("YXI_ROUTE_FIXTURE")
        assumeTrue(fixture != null, "Requires the isolated SSH fixture")
        val root = File(fixture!!)
        val home = root.resolve("home").absolutePath
        val settings = "$home/.claude/settings.json"

        fun connOf(label: String) = Conn(
            Host("dt", label, "127.0.0.1", root.resolve("port").readText().trim().toInt(), "root", root.resolve("client").path),
            fixtureKeys(root),
        )
        suspend fun currentBaseUrl(c: Conn): String? =
            ConfigRemote.readFile(c.ssh, settings)?.let { JSONObject(it).optJSONObject("env")?.optString("ANTHROPIC_BASE_URL")?.takeIf { v -> v.isNotEmpty() } }

        val conn = connOf("回归主机")
        conn.ssh.connect()
        val state = AppState()
        state.conns.add(conn)
        var phase = "准备"
        suspend fun diag(request: DeferredRoute?) = "phase=$phase connected=${conn.ssh.isConnected} " +
            "sessions=${conn.sessions.map { "${it.name}/${it.state}" }} " +
            "request=${request?.let { "${it.status}${if (it.applying) "/applying" else ""}" }} notice=${state.deferredRouteNotice}"
        val l1 = claudeLine("L1", "线A", "https://a.invalid/v1")
        val l3 = claudeLine("L3", "线C", "https://c.invalid/v1")

        try {
            // fixture 是全新 HOME：确认没有遗留清单，再放一份解析不了的（Lines.list：文件在但解析不了 = 「拿不到」= null）
            assertTrue(conn.ssh.exec("test -e \"\$HOME/.yxi/lines.json\" && printf y").isEmpty())
            conn.ssh.exec("mkdir -p \"\$HOME/.yxi\" && printf '%s\\n' '{broken' > \"\$HOME/.yxi/lines.json\"")
            // P1 失败不重试：清单拿不到 → 取消并给明确通知；循环终止、不再重试、不写任何配置
            phase = "P1清单损坏"
            val req1 = DeferredRoute(conn, l1, null, listOf(l1))
            state.deferredRoute = req1
            val job1 = launch { runDeferredRoute(state, req1) }
            await(describe = { diag(req1) }) { state.deferredRouteNotice.contains("无法读取线路清单") }
            await(5000) { job1.isCompleted }
            assertNull(state.deferredRoute)
            val notice1 = state.deferredRouteNotice
            delay(3000)  // 至少一个 tick：通知不再变化 = 没有任何东西在重试
            assertEquals(notice1, state.deferredRouteNotice)
            assertNull(currentBaseUrl(conn), "写失败路径不得碰 settings.json")

            // P2 断线 → 等待重连 → 重连后写入成功
            phase = "P2断线重连"
            val conn2 = connOf("重连主机")
            state.conns.add(conn2)
            val req2 = DeferredRoute(conn2, l1, null, listOf(l1))
            state.deferredRoute = req2
            val job2 = launch { runDeferredRoute(state, req2) }
            await(describe = { diag(req2) }) { req2.status == "等待原服务器重新连接" }
            // saveList 会拒绝覆盖损坏的清单（保护手改文件）；先恢复成有效空表再保存 [L1]
            conn.ssh.exec("printf '[]\\n' > \"\$HOME/.yxi/lines.json\"")
            assertNull(Lines.saveList(conn.ssh, listOf(l1)))
            conn2.ssh.connect()  // 「重连」
            await(describe = { diag(req2) }) { state.deferredRouteNotice.contains("配置已写入") }
            await(5000) { job2.isCompleted }
            assertNull(state.deferredRoute)
            assertTrue(state.deferredRouteNotice.contains("线A"))
            assertEquals("https://a.invalid/v1", currentBaseUrl(conn))
            val noticeAfterP2 = state.deferredRouteNotice  // notice 是持久的，P3 拿它当「没有新通知」的基线
            conn2.close()

            // P3 busy 门控 + 取消取证：真 tmux 会话 + Claude 状态文件（SessionProbe 的解析链路）
            conn.ssh.exec("tmux new-session -d -s busytask 'sleep 600'")
            conn.ssh.exec("mkdir -p \"\$HOME/.claude/sessions\" && printf '%s\\n' '{\"tmux\":\"busytask:0.0\",\"status\":\"busy\",\"statusUpdatedAt\":1700000000000}' > \"\$HOME/.claude/sessions/cc.json\"")
            phase = "P3 busy探测"
            await(describe = { diag(null) }) {
                conn.refresh()
                conn.sessions.any { it.name == "busytask" && it.state == SessionState.Working }
            }
            val req3 = DeferredRoute(conn, l3, null, listOf(l1))  // 写入目标是 L3：写没写一眼可辨
            state.deferredRoute = req3
            val job3 = launch { runDeferredRoute(state, req3) }
            await(describe = { diag(req3) }) { req3.status == "等待生成或待处理交互结束" }
            state.deferredRoute = null  // 用户取消
            await(5000) { job3.isCompleted }
            conn.ssh.exec("printf '%s\\n' '{\"tmux\":\"busytask:0.0\",\"status\":\"idle\"}' > \"\$HOME/.claude/sessions/cc.json\"")
            delay(6000)  // 两个 tick：若取消失败，循环会在变闲后把 L3 写进配置
            assertEquals(noticeAfterP2, state.deferredRouteNotice, "取消不应触发任何新通知（含失败通知）")
            assertEquals("https://a.invalid/v1", currentBaseUrl(conn), "取消后不得继续写入")
            assertNull(state.deferredRoute)

            // P4 等待任务空闲 → 放行：重建 busy 现场（P3 收尾已翻成 idle），busy 期间停在忙门，
            // 任务结束（变闲+会话消失）后放行写入。
            // （伪面板通不过 borrowable 的输入框判据，所以用「范围内没有任务了」这条放行路径）
            phase = "P4等待空闲"
            conn.ssh.exec("tmux kill-session -t busytask 2>/dev/null; tmux new-session -d -s busytask 'sleep 600'; printf '%s\\n' '{\"tmux\":\"busytask:0.0\",\"status\":\"busy\",\"statusUpdatedAt\":1700000002000}' > \"\$HOME/.claude/sessions/cc.json\"")
            val req4 = DeferredRoute(conn, l1, null, listOf(l1))
            state.deferredRoute = req4
            val job4 = launch { runDeferredRoute(state, req4) }
            await(describe = { diag(req4) }) { req4.status == "等待生成或待处理交互结束" }
            conn.ssh.exec("tmux kill-session -t busytask 2>/dev/null; rm -f \"\$HOME/.claude/sessions/cc.json\"")
            // P2 的成功通知还挂着（notice 是持久的），这里认「回归主机」这条新的，别被旧通知糊弄
            await(25000, describe = { diag(req4) }) { state.deferredRouteNotice.contains("回归主机 · 线A 配置已写入") }
            await(5000) { job4.isCompleted }
            assertNull(state.deferredRoute)
            assertEquals("https://a.invalid/v1", currentBaseUrl(conn))

            // P5 配置列表变化 → 取消等待，不写入
            phase = "P5清单变化"
            val l2 = claudeLine("L2", "线B", "https://b.invalid/v1")
            val req5 = DeferredRoute(conn, l1, null, listOf(l1, l2))  // 请求快照里有清单里没有的线
            state.deferredRoute = req5
            val job5 = launch { runDeferredRoute(state, req5) }
            await(describe = { diag(req5) }) { state.deferredRouteNotice.contains("线路清单已改变") }
            await(5000) { job5.isCompleted }
            assertNull(state.deferredRoute)
            assertEquals("https://a.invalid/v1", currentBaseUrl(conn), "清单变化取消后不得写入")

            // P6 Codex 分支：写入 + 重开提示 + 还原（还原即 F-A 的钥匙清理路径）
            val codex = codexLine()
            val catalogNow = Lines.list(conn.ssh)!!
            val req6 = DeferredRoute(conn, codex, null, catalogNow)
            phase = "P6 Codex写入"
            state.deferredRoute = req6
            val job6 = launch { runDeferredRoute(state, req6) }
            await(describe = { diag(req6) }) { state.deferredRouteNotice.contains("配置已写入") }
            assertTrue(state.deferredRouteNotice.contains("Codex 需重开任务后生效"))
            assertTrue(ConfigRemote.readFile(conn.ssh, "$home/.codex/config.toml")!!.contains("https://codex.invalid/v1"))
            await(5000) { job6.isCompleted }
            assertNull(Lines.applyCodex(conn.ssh, null))
            assertTrue(ConfigRemote.readFile(conn.ssh, "$home/.codex/config.toml")!!.contains("original-model"))
            assertTrue(conn.ssh.exec("test -e \"\$HOME/.yxi/codex-key\" && printf y").isEmpty(), "还原后钥匙文件应被删（F-A）")
        } finally {
            runCatching { conn.ssh.exec("tmux kill-session -t busytask 2>/dev/null; rm -f \"\$HOME/.claude/sessions/cc.json\"") }
            runCatching { conn.ssh.disconnect() }
        }
        Unit
    }
}
