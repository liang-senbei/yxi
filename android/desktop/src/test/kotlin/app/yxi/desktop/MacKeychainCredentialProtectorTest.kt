package app.yxi.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

/** 测试专用：建 / 删临时钥匙串文件。生产代码只找、只加条目，从不建钥匙串。 */
internal interface KeychainTestSecurity : Library {
    fun SecKeychainCreate(pathName: String, passwordLength: Int, password: ByteArray, promptUser: Byte, initialAccess: Pointer?, keychain: PointerByReference): Int
    fun SecKeychainDelete(keychain: Pointer): Int
    fun SecKeychainLock(keychain: Pointer): Int
    fun SecKeychainUnlock(keychain: Pointer, passwordLength: Int, password: ByteArray, usePassword: Byte): Int
    fun SecKeychainGetUserInteractionAllowed(state: ByteArray): Int
    fun SecKeychainSetUserInteractionAllowed(state: Byte): Int
}
internal interface KeychainTestCoreFoundation : Library { fun CFRelease(ref: Pointer) }

/** 真钥匙串的用例要 macOS + YXI_KEYCHAIN_TEST=1 才跑：SecKeychainCreate 会把临时钥匙串挂进当前用户的搜索列表（删除时摘掉），
 * 所以只在测试机的隔离 HOME 下开；每个用例一个新钥匙串文件、用完删除，全程禁止弹授权框，不碰登录钥匙串。
 * 秘密全是造出来的假值。 */
class MacKeychainCredentialProtectorTest {
    private class Temp(val ref: Pointer, val password: ByteArray, val security: KeychainTestSecurity)
    private fun keychain(block: (Temp) -> Unit) {
        assumeTrue(System.getProperty("os.name").startsWith("Mac") && System.getenv("YXI_KEYCHAIN_TEST") == "1",
            "需要 macOS 且 YXI_KEYCHAIN_TEST=1（会临时改动当前用户的钥匙串搜索列表，只在隔离 HOME 的测试机上开）")
        val security = Native.load("Security", KeychainTestSecurity::class.java)
        val dir = Files.createTempDirectory("yxi-keychain").toFile()
        val password = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }.toByteArray()
        val interaction = ByteArray(1)
        assertEquals(0, security.SecKeychainGetUserInteractionAllowed(interaction))
        assertEquals(0, security.SecKeychainSetUserInteractionAllowed(0))
        val ref = PointerByReference()
        try {
            assertEquals(0, security.SecKeychainCreate(File(dir, "yxi-test.keychain").absolutePath, password.size, password, 0, null, ref))
            block(Temp(ref.value, password, security))
        } finally {
            ref.value?.let { security.SecKeychainDelete(it); Native.load("CoreFoundation", KeychainTestCoreFoundation::class.java).CFRelease(it) }
            security.SecKeychainSetUserInteractionAllowed(interaction[0])
            dir.deleteRecursively()
        }
    }
    private fun data(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("yxi-keychain-data").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    private val hostSecret = "fixture-host-secret-7f3a"
    private val refreshSecret = "fixture-refresh-token-91c2"

    @Test fun `主密钥存在钥匙串里，换个实例也能解开`() = keychain { k ->
        val protector = MacKeychainCredentialProtector(key = MacKeychainKey(k.ref))
        val plain = """{"refresh":"$refreshSecret"}""".toByteArray()
        val first = protector.protect(plain)
        val second = protector.protect(plain)
        assertFalse(first.contentEquals(second))   // 每次新 IV
        assertFalse(String(first, Charsets.ISO_8859_1).contains(refreshSecret))
        assertEquals("keychain", protector.scheme)
        val restarted = MacKeychainCredentialProtector(key = MacKeychainKey(k.ref))
        assertContentEquals(plain, restarted.unprotect(first))
        assertContentEquals(plain, restarted.unprotect(second))
        assertEquals(32, MacKeychainKey(k.ref).find().second?.size)
    }

    @Test fun `用途不同、被改过、截短、版本不认识的密文都解不开`() = keychain { k ->
        val key = MacKeychainKey(k.ref)
        val sealed = MacKeychainCredentialProtector("Yxi/host-credentials/v1", key).protect(hostSecret.toByteArray())
        assertFails { MacKeychainCredentialProtector("Yxi/account-credentials/v1", key).unprotect(sealed) }
        val host = MacKeychainCredentialProtector("Yxi/host-credentials/v1", key)
        assertFails { host.unprotect(sealed.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }) }
        assertFails { host.unprotect(sealed.copyOf(20)) }
        assertFails { host.unprotect(sealed.copyOf().also { it[0] = 2 }) }
        assertEquals(hostSecret, host.unprotect(sealed).toString(Charsets.UTF_8))
    }

    @Test fun `解密时找不到主密钥就报错，不会新建一把`() = keychain { k ->
        val key = MacKeychainKey(k.ref)
        val error = assertFailsWith<IllegalStateException> { MacKeychainCredentialProtector(key = key).unprotect(byteArrayOf(1) + ByteArray(28)) }
        assertTrue(error.message!!.startsWith("macOS钥匙串无法提供Yxi主密钥"), error.message)
        assertEquals(MacKeychainKey.ITEM_NOT_FOUND, key.find().first)
    }

    @Test fun `明文 hosts 和 auth 迁进钥匙串保护后数据目录里找不到秘密`() = keychain { k ->
        data { dir ->
            val key = MacKeychainKey(k.ref)
            val hostsRaw = """[{"id":"h1","hostname":"fixture.invalid","port":22,"password":"$hostSecret"}]"""
            val authRaw = """{"refresh":"$refreshSecret"}"""
            File(dir, "hosts.json").writeText(hostsRaw)
            File(dir, "hosts.json.bak").writeText(hostsRaw)
            File(dir, "auth.json").writeText(authRaw)
            val hosts = HostConfigFile(File(dir, "hosts.json"), MacKeychainCredentialProtector("Yxi/host-credentials/v1", key)) { JSONArray(it) }
            assertEquals(hostsRaw, hosts.read())
            assertTrue(CredentialFile(File(dir, "auth.json"), MacKeychainCredentialProtector(key = key)).read().similar(JSONObject(authRaw)))
            dir.walk().filter { it.isFile }.forEach { file ->
                val text = String(file.readBytes(), Charsets.ISO_8859_1)
                assertFalse(text.contains(hostSecret) || text.contains(refreshSecret), "明文残留：${file.name}")
            }
            assertEquals("[]", File(dir, "hosts.json").readText())
            assertEquals("[]", File(dir, "hosts.json.bak").readText())
            assertEquals("{}", File(dir, "auth.json").readText())
            assertTrue(File(dir, "hosts.json.protected").readText().contains("yxi-hosts-keychain-v1"))
            assertTrue(File(dir, "hosts.json.migration-copy.protected").exists())
            // 重启后（新实例重新从钥匙串取主密钥）照样读得出来
            val again = MacKeychainKey(k.ref)
            assertEquals(hostsRaw, HostConfigFile(File(dir, "hosts.json"), MacKeychainCredentialProtector("Yxi/host-credentials/v1", again)) { JSONArray(it) }.read())
            assertEquals(refreshSecret, CredentialFile(File(dir, "auth.json"), MacKeychainCredentialProtector(key = again)).read().getString("refresh"))
        }
    }

    @Test fun `钥匙串锁着时不落盘、原记录不动，也不回退明文`() = keychain { k ->
        data { dir ->
            assertEquals(0, k.security.SecKeychainLock(k.ref))
            val protector = MacKeychainCredentialProtector(key = MacKeychainKey(k.ref))
            val error = assertFailsWith<IllegalStateException> { protector.protect("x".toByteArray()) }
            assertTrue(error.message!!.startsWith("macOS钥匙串不可用"), error.message)
            val legacy = File(dir, "auth.json").apply { writeText("""{"refresh":"$refreshSecret"}""") }
            val vault = CredentialFile(legacy, protector)
            assertFails { vault.read() }
            assertEquals("""{"refresh":"$refreshSecret"}""", legacy.readText())
            assertFalse(vault.protectedFile.exists())
            assertEquals(0, k.security.SecKeychainUnlock(k.ref, k.password.size, k.password, 1))
            assertEquals(MacKeychainKey.ITEM_NOT_FOUND, MacKeychainKey(k.ref).find().first)
        }
    }

    // 以下不碰钥匙串，任何平台都跑
    private class FakeDpapi : CredentialProtector {
        override fun protect(plain: ByteArray) = plain.reversedArray()
        override fun unprotect(cipher: ByteArray) = cipher.reversedArray()
    }
    private class NeverCalledKeychain : CredentialProtector {
        override val scheme = "keychain"
        override fun protect(plain: ByteArray): ByteArray = fail("不该走到钥匙串")
        override fun unprotect(cipher: ByteArray): ByteArray = fail("格式不对就该挡住，不该拿钥匙串去解")
    }

    @Test fun `按平台选保护方案，构造时不碰钥匙串`() {
        val os = System.getProperty("os.name")
        val expected = when { os.startsWith("Windows") -> "dpapi"; os.startsWith("Mac") -> "keychain"; else -> null }
        assertEquals(expected, WindowsCredentialProtector.forPlatform()?.scheme)
    }

    @Test fun `Windows 的保护记录拿到 Mac 上被格式挡住，不会拿错密钥去解`() = data { dir ->
        val auth = CredentialFile(File(dir, "auth.json"), FakeDpapi())
        auth.write(JSONObject().put("refresh", "dummy"))
        assertTrue(auth.protectedFile.readText().contains("\"yxi-dpapi-v1\""))
        assertFailsWith<IllegalArgumentException> { CredentialFile(File(dir, "auth.json"), NeverCalledKeychain()).read() }
        val hosts = File(dir, "hosts.json")
        HostConfigFile(hosts, FakeDpapi()) { JSONArray(it) }.write("[]")
        assertTrue(File(dir, "hosts.json.protected").readText().contains("\"yxi-hosts-dpapi-v1\""))
        assertFailsWith<IllegalArgumentException> { HostConfigFile(hosts, NeverCalledKeychain()) { JSONArray(it) }.read() }
    }
}
