package app.yxi.desktop

import app.yxi.agent.Rewind
import app.yxi.ssh.HostKeys
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * 回退控制器 × 持久发送闸的接线（老板令）：begin 只准发生在**第一条可能改转录的 exec 之前**
 * —— 反过来讲，连会话都探不到的时候就**一张票都不能建**（建了就是来历不明的阻塞，
 * 用户还要凭空去「恢复」一条根本没发生的回退）。这里用一条**从不出门**的 Conn
 * （exec 见 session 为空立刻返回空串，不打网络）钉住 fail-closed 的顺序；
 * Target 三 UUID 的顺序单独钉死，防 anchor/message 装反。
 */
class RewindControllerGateTest {

    /** 空壳指纹库：测试里的连接从不开口，jsch 的 HostKeyRepository 一个方法都不会被走到。 */
    private val noKeys = object : HostKeys {
        override fun userInfo(): UserInfo = object : UserInfo {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String? = null
            override fun promptPassword(message: String?): Boolean = false
            override fun promptPassphrase(message: String?): Boolean = false
            override fun promptYesNo(message: String?): Boolean = false
            override fun showMessage(message: String?) = Unit
        }
        override val changedDetected: Boolean = false
        override fun check(host: String, key: ByteArray): Int = HostKeyRepository.NOT_INCLUDED
        override fun add(hostkey: HostKey, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = ""
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private val sid = "11111111-1111-4111-8111-111111111111"
    private val anchor = "22222222-2222-4222-8222-222222222222"
    private val target = "33333333-3333-4333-8333-333333333333"
    private val plan = Rewind.Plan(sid, anchor, target, prompt = "接着这儿继续")

    @Test fun `探不到会话就不登记发送闸_闸保持空`() {
        val dir = Files.createTempDirectory("yxi-rewind-ctrl-gate")
        try {
            val gate = RewindDeliveryGate(dir.resolve("gate.json").toFile())
            val conn = Conn(Host(id = "t", alias = "t", hostname = "127.0.0.1", port = 1, username = "nobody"), noKeys)
            val report = runBlocking { RewindController(conn, gate).rewind("cc-nowhere", plan) }
            // 门禁探不到会话 → 在任何登记之前就 fail-closed，代号如实是 probe
            assertEquals("probe", (report.outcome as? Rewind.Outcome.Failed)?.code)
            assertNull(report.ticket)
            assertFalse(gate.blocked("any-task"))
            assertNull(gate.pending("any-task"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `闸的Target就是Plan的三UUID_顺序钉死`() {
        val t = rewindGateTarget(plan)
        assertEquals(sid, t.sessionId)
        assertEquals(anchor, t.anchorUuid)
        assertEquals(target, t.messageUuid)   // message 装的是目标轮，不是 anchor —— 装反恢复就找错地方
    }
}
