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
    @Test fun `missing protected records block empty writes and require explicit unchanged copy selection`() {
        val root = Files.createTempDirectory("host-recovery").toFile()
        val file = File(root, "hosts.json")
        val raw = """[{"id":"a","alias":"Test","hostname":"host.test","password":"private-fixture"}]"""
        file.writeText(raw)
        store(file).read()
        File(root, "hosts.json.protected").delete()
        val missing = store(file)
        assertTrue(missing.needsRecovery())
        assertFails { missing.read() }
        assertFails { missing.write("[]") }
        assertFails { store(file, null).write("[]") }
        val copy = missing.recoveryCopies().single()
        assertEquals(1, copy.count)
        assertFalse(copy.summary.joinToString().contains("private-fixture"))
        assertFails { missing.restoreMissing(copy.name, "changed") }
        assertEquals(raw, missing.restoreMissing(copy.name, copy.fingerprint))
        assertEquals(raw, store(file).read())
        assertFails { missing.restoreMissing(copy.name, copy.fingerprint) }
    }
    @Test fun `roaming import keeps encrypted copies locally and resumes after cleared markers`() {
        val root = Files.createTempDirectory("host-roaming").toFile()
        val roaming = File(root, "roaming").apply { mkdirs() }
        val local = File(root, "local").apply { mkdirs() }
        val source = File(roaming, "hosts.json")
        source.writeText("broken fixture")
        val raw = """[{"id":"a","password":"fixture-password"}]"""
        File(roaming, "hosts.json.bak").writeText(raw)
        val target = store(File(local, "hosts.json"))
        target.importLegacy(source)
        assertTrue(target.recovered)
        assertEquals(raw, target.read())
        assertTrue(roaming.listFiles()!!.all { it.readText() == "[]" })
        assertEquals(2, local.listFiles()!!.count { it.name.contains(".import-") })
        store(File(local, "hosts.json")).importLegacy(source)
        assertEquals(raw, target.read())
        source.writeText("""[{"id":"other"}]""")
        assertFails { target.importLegacy(source) }
        assertTrue(source.readText().contains("other"))
    }
    @Test fun `roaming source changed during protection is not cleared`() {
        val root = Files.createTempDirectory("host-roaming-race").toFile()
        val source = File(root, "old.json").apply { writeText("""[{"id":"old"}]""") }
        var changed = false
        val changing = object : CredentialProtector {
            override fun protect(plain: ByteArray): ByteArray {
                if (!changed) { changed = true; source.writeText("""[{"id":"new"}]""") }
                return codec.protect(plain)
            }
            override fun unprotect(cipher: ByteArray) = codec.unprotect(cipher)
        }
        assertFails { store(File(root, "local/hosts.json"), changing).importLegacy(source) }
        assertEquals("""[{"id":"new"}]""", source.readText())
    }
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
