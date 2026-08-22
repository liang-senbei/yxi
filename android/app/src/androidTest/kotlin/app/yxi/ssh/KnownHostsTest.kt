package app.yxi.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository.CHANGED
import com.jcraft.jsch.HostKeyRepository.NOT_INCLUDED
import com.jcraft.jsch.HostKeyRepository.OK
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [KnownHosts] 的分支表 —— **这是 SSH 抵御中间人的唯一防线**，所以它有测试而别处没有。
 *
 * 为什么非得在真机上跑：它依赖 `android.util.Base64`，而 TROUBLESHOOTING #21 那个洞
 * 正是**双重 base64** 造成的 —— 换成 JVM 的 `java.util.Base64` 就测不出原样的行为了。
 *
 * 为什么值得写：#21 和 #22 两个洞**正向用例全绿时都静静躺着**，
 * 只有专门去测「应该失败」的用例才露出来。这个文件就是把那几次手工反向测试钉住。
 *
 * 跑：`./gradlew connectedDebugAndroidTest`（要有设备/模拟器）
 */
@RunWith(AndroidJUnit4::class)
class KnownHostsTest {

    private lateinit var store: HostStore
    private val id = "test-knownhosts"
    private val keyA = ByteArray(32) { it.toByte() }
    private val keyB = ByteArray(32) { (it + 1).toByte() }

    /** 一个「用户点了同意」的 prompt —— 用它才能证明 CHANGED 时**压根没问到用户**。 */
    private val alwaysYes = object : TrustPrompt {
        var asked = 0
        override fun confirmNewHost(host: String, keyType: String, fingerprint: String): Boolean {
            asked++
            return true
        }
    }

    @Before fun setUp() {
        store = HostStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.upsert(Host(id = id, alias = "t", hostname = "example.invalid", username = "u"))
    }

    @After fun tearDown() = store.remove(id)

    private fun kh(prompt: TrustPrompt? = alwaysYes) = KnownHosts(store, id, prompt)

    private fun hostKey(blob: ByteArray) = HostKey("example.invalid", HostKey.ED25519, blob)

    @Test fun 没见过的主机是NOT_INCLUDED() {
        assertEquals(NOT_INCLUDED, kh().check("example.invalid", keyA))
    }

    /**
     * ⚠️ **TROUBLESHOOTING #21 的回归测试。**
     * `HostKey.getKey()` 返回的已经是 base64；曾经在 add() 里又编码了一次，
     * 于是存下来的值和 check() 里的单次编码**永远对不上** —— 每次连都当「第一次」，
     * CHANGED 那条路永远走不到。这个断言一红就是那个洞回来了。
     */
    @Test fun 记住之后同一把钥匙必须OK() {
        val k = kh()
        k.add(hostKey(keyA), null)
        assertEquals(OK, k.check("example.invalid", keyA))
        // 换个实例（模拟下次开 App）照样认得
        assertEquals(OK, kh().check("example.invalid", keyA))
    }

    @Test fun 换了钥匙必须CHANGED() {
        val k = kh()
        k.add(hostKey(keyA), null)
        assertEquals(CHANGED, k.check("example.invalid", keyB))
        assertTrue(k.changedDetected)
    }

    /**
     * ⚠️ **TROUBLESHOOTING #22 的回归测试 —— 四条里最要紧的一条。**
     * jsch 的 `StrictHostKeyChecking=ask` 在 CHANGED 时**也会走 promptYesNo**
     * （我一度以为它自己会拒绝，错的）。要是照样弹窗，用户点一下「连」就真连上了，
     * 社工一句「服务器刚重装过」就能骗过去 —— 这道防线等于形同虚设。
     */
    @Test fun 指纹变了连问都不问直接拒() {
        val k = kh()
        k.add(hostKey(keyA), null)
        k.check("example.invalid", keyB)          // → CHANGED
        assertFalse(k.userInfo().promptYesNo("SHA256:whatever"))
        assertEquals("指纹变了不该去问用户", 0, alwaysYes.asked)
    }

    /** 没有 UI 可问 → 保守拒绝（不是「默默放行」）。 */
    @Test fun 没有prompt时保守拒绝() {
        assertFalse(kh(prompt = null).userInfo().promptYesNo("SHA256:whatever"))
    }
}

