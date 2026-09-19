package app.yxi.desktop

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CodexAppServerTest {

    @Test
    fun `out-of-order responses correlate by RPC id`() = runBlocking {
        FakeRunner("outoforder").use { runner ->
            handshake(runner)
            val first = async { runner.client.request("thread/start", JSONObject().put("cwd", "/tmp")) }
            delay(200) // 让假运行器先收到并扣住第一个请求
            val second = async { runner.client.request("thread/read", JSONObject().put("threadId", "thr-1")) }
            val firstResult = withTimeout(5000) { first.await() }
            val secondResult = withTimeout(5000) { second.await() }
            // 假运行器先回第二个请求；按 ID 关联后各自拿到自己的结果
            assertEquals("thread/start", firstResult.getJSONObject("result").getString("served"))
            assertEquals("thread/read", secondResult.getJSONObject("result").getString("served"))
            val replies = runner.outboundJson().filter { it.has("result") && it.optJSONObject("result")?.has("served") == true }
            assertEquals(2, replies.size)
            assertEquals("thread/read", replies.first().getJSONObject("result").getString("served"))
        }
    }

    @Test
    fun `notifications stay raw and never masquerade as responses`() = runBlocking {
        FakeRunner("notifications").use { runner ->
            handshake(runner)
            val seen = mutableListOf<JSONObject>()
            val collector = launch { runner.client.events.collect { seen.add(it) } }
            val response = withTimeout(5000) { runner.client.startTurn("thr-1", "你好") }
            assertEquals("turn-1", response.getJSONObject("result").getJSONObject("turn").getString("id"))
            withTimeout(5000) { poll { seen.any { it.optString("method") == "turn/started" } } }
            val compacted = seen.filter { it.optString("method") == "thread/compacted" }
            val started = seen.filter { it.optString("method") == "turn/started" }
            assertEquals(1, compacted.size)
            assertEquals("before-requests", compacted.first().getJSONObject("params").getString("note"))
            assertEquals("turn-1", started.first().getJSONObject("params").getJSONObject("turn").getString("id"))
            assertTrue(seen.all { !it.has("result") && !it.has("error") }, "通知不得携带 result/error")
            collector.cancel()
        }
    }

    @Test
    fun `approval responses only for ids received on this connection`() = runBlocking {
        FakeRunner("approval").use { runner ->
            handshake(runner)
            val approval = withTimeout(5000) {
                runner.client.events.first { it.has("method") && it.optString("id") == "srv-77" }
            }
            assertEquals("item/commandExecution/requestApproval", approval.getString("method"))
            // 伪造的 ID 必须被拒，且不向运行器写任何东西
            assertFailsWith<IllegalStateException> {
                runner.client.respond("srv-999", JSONObject().put("decision", "approved"))
            }
            assertTrue(runner.inbound().none { it.contains("srv-999") })
            // 收到过的 ID 可以答复一次
            runner.client.respond("srv-77", JSONObject().put("decision", "approved"))
            // 第二次答复同一 ID 必须被拒（已消费）
            assertFailsWith<IllegalStateException> {
                runner.client.respond("srv-77", JSONObject().put("decision", "approved"))
            }
            val replies = runner.inboundJson().filter { it.optString("id") == "srv-77" && it.has("result") }
            assertEquals(1, replies.size)
            assertEquals("approved", replies.first().getJSONObject("result").getString("decision"))
        }
    }

    @Test
    fun `EOF fails pending requests and refuses new ones without resend`() = runBlocking {
        FakeRunner("eof-on-request").use { runner ->
            handshake(runner)
            // async 里自行捕获：子协程失败不得取消整个测试作用域
            val pending = async { runCatching { runner.client.request("thread/start", JSONObject().put("cwd", "/tmp")) } }
            val failure = withTimeout(5000) { pending.await() }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure.message?.contains("自动重发") == true, "实际错误：${failure.message}")
            // 未决请求不得自动重发：假运行器只收到一次
            assertEquals(1, runner.inbound().count { it.contains("thread/start") })
            val refused = runCatching {
                withTimeout(3000) { runner.client.request("thread/read", JSONObject().put("threadId", "thr-1")) }
            }.exceptionOrNull()
            assertNotNull(refused)
            assertTrue(refused.message?.contains("运行器连接已关闭") == true, "实际错误：${refused.message}")
        }
    }

    @Test
    fun `timeout does not resend and surfaces as failure`() = runBlocking {
        FakeRunner("noreply").use { runner ->
            handshake(runner)
            assertFailsWith<TimeoutCancellationException> {
                runner.client.request("thread/start", JSONObject().put("cwd", "/tmp"), timeoutMs = 150)
            }
            assertEquals(1, runner.inbound().count { it.contains("thread/start") }, "超时后不得自动重发")
        }
    }

    @Test
    fun `init failure closes the channel for further requests`() = runBlocking {
        FakeRunner("init-error").use { runner ->
            val failure = runCatching {
                runner.client.request("initialize", JSONObject().put("clientInfo", JSONObject().put("name", "yxi-test").put("title", "T").put("version", "0")))
            }.exceptionOrNull()
            assertTrue(failure is CodexAppServer.RpcFailure, "实际错误：$failure")
            assertEquals(402, (failure as CodexAppServer.RpcFailure).code)
            // connect() 的失败路径等效：失败即 close，通道从此拒绝新请求
            runner.client.close()
            val refused = runCatching {
                runner.client.request("thread/start", JSONObject().put("cwd", "/tmp"))
            }.exceptionOrNull()
            assertTrue(refused?.message?.contains("运行器连接已关闭") == true, "实际错误：${refused?.message}")
        }
    }
}
