package app.yxi.desktop

import java.nio.file.Files
import java.io.File
import org.json.JSONArray
import kotlin.test.*

class HostConfigFileTest {
    private val codec = object : CredentialProtector {
        override fun protect(plain: ByteArray) = byteArrayOf(42) + plain.reversedArray()
        override fun unprotect(cipher: ByteArray): ByteArray { require(cipher.first() == 42.toByte()); return cipher.drop(1).toByteArray().reversedArray() }
    }
    private fun store(file: File, protector: CredentialProtector? = codec) = HostConfigFile(file, protector) { JSONArray(it) }
    @Test fun `migration protects current and old copies before clearing plaintext`() {
        val dir = Files.createTempDirectory("host-protection").toFile()
        val file = File(dir, "hosts.json")
        val current = """[{"id":"a","password":"synthetic-current"}]"""
        file.writeText(current)
        File(dir, "hosts.json.bak").writeText("""[{"password":"synthetic-old"}]""")
        File(dir, "hosts.json.damaged").writeText("broken synthetic-secret")
        assertEquals(current, store(file).read())
        listOf("hosts.json", "hosts.json.bak", "hosts.json.damaged").forEach {
            assertEquals("[]", File(dir, it).readText())
            assertTrue(File(dir, "$it.migration-copy.protected").isFile)
        }
        assertEquals(current, store(file).read())
        val changed = """[{"id":"b","password":"replacement"}]"""
        store(file).write(changed)
        File(dir, "hosts.json.protected").writeText("broken")
        val recovered = store(file)
        assertEquals(current, recovered.read())
        assertTrue(recovered.recovered)
        assertFails { store(file, null).write("[]") }
    }
    @Test fun `failed protection and divergent legacy records remain intact`() {
        val dir = Files.createTempDirectory("host-protection-failure").toFile()
        val file = File(dir, "hosts.json")
        val raw = """[{"password":"synthetic"}]"""
        file.writeText(raw)
        val broken = object : CredentialProtector {
            override fun protect(plain: ByteArray): ByteArray = error("fixture failure")
            override fun unprotect(cipher: ByteArray): ByteArray = error("fixture failure")
        }
        assertFails { store(file, broken).read() }
        assertEquals(raw, file.readText())
        store(file).read()
        file.writeText("""[{"password":"different"}]""")
        assertFails { store(file).write("[]") }
        assertTrue(file.readText().contains("different"))
    }
}
