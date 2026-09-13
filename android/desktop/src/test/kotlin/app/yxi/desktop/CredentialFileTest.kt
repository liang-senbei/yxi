package app.yxi.desktop

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import kotlin.test.*

class CredentialFileTest {
    // Test codec only; Windows native DPAPI is exercised by --credential-smoke.
    private val codec = object : CredentialProtector {
        override fun protect(plain: ByteArray) = byteArrayOf(42) + plain.map { (it.toInt() xor 91).toByte() }.toByteArray()
        override fun unprotect(cipher: ByteArray): ByteArray { require(cipher.first() == 42.toByte()); return cipher.drop(1).map { (it.toInt() xor 91).toByte() }.toByteArray() }
    }
    private fun fixture(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("credential-file").toFile()
        try { block(dir.resolve("auth.json")) } finally { dir.deleteRecursively() }
    }
    private val raw = """{"access":"dummy-access","refresh":"dummy-refresh","exp":123}"""
    @Test fun `migration verifies protected record before removing plaintext`() = fixture { file ->
        file.writeText(raw)
        val vault = CredentialFile(file, codec)
        assertTrue(vault.read().similar(JSONObject(raw)))
        assertEquals("{}", file.readText())
        assertFalse(vault.protectedFile.readText().contains("dummy-refresh"))
        assertTrue(CredentialFile(file, codec).read().similar(JSONObject(raw)))
    }
    @Test fun `failed roundtrip leaves original credentials untouched`() = fixture { file ->
        file.writeText(raw)
        val broken = object : CredentialProtector {
            override fun protect(plain: ByteArray) = byteArrayOf(1)
            override fun unprotect(cipher: ByteArray): ByteArray = error("wrong user")
        }
        val vault = CredentialFile(file, broken)
        assertFails { vault.read() }
        assertEquals(raw, file.readText()); assertFalse(vault.protectedFile.exists())
    }
    @Test fun `corrupt encrypted record cannot fall back to a legacy refresh token`() = fixture { file ->
        file.writeText(raw)
        val vault = CredentialFile(file, codec)
        vault.protectedFile.writeText("broken")
        assertFails { vault.read() }
        assertEquals(raw, file.readText())
    }
    @Test fun `changed legacy record during migration is preserved`() = fixture { file ->
        file.writeText(raw)
        val newer = """{"refresh":"another-account"}"""
        val vault = CredentialFile(file, codec) { target, text ->
            DurableFile.replace(target, text)
            if (target.name.endsWith(".protected")) file.writeText(newer)
        }
        assertFails { vault.read() }
        assertEquals(newer, file.readText())
    }
    @Test fun `conflicting records require a fresh login instead of picking one silently`() = fixture { file ->
        val vault = CredentialFile(file, codec)
        vault.write(JSONObject(raw))
        file.writeText("""{"refresh":"different"}""")
        assertFails { vault.read() }
        vault.write(JSONObject(raw)) // Explicit new-login write can replace the old credential set.
        assertEquals("{}", file.readText())
        vault.clear()
        assertEquals(0, vault.read().length())
    }
    @Test fun `invalid data written to destination cannot clear the original`() = fixture { file ->
        file.writeText(raw)
        val vault = CredentialFile(file, codec) { target, value ->
            DurableFile.replace(target, if (target.name.endsWith(".protected")) "broken destination" else value)
        }
        assertFails { vault.read() }
        assertEquals(raw, file.readText())
    }
    @Test fun `interrupted plaintext cleanup can resume after encrypted verification`() = fixture { file ->
        file.writeText(raw)
        val vault = CredentialFile(file, codec) { target, value ->
            if (target == file) error("legacy file locked")
            DurableFile.replace(target, value)
        }
        assertFails { vault.read() }
        assertEquals(raw, file.readText())
        assertTrue(vault.protectedFile.exists())
        assertTrue(CredentialFile(file, codec).read().similar(JSONObject(raw)))
        assertEquals("{}", file.readText())
    }
}
