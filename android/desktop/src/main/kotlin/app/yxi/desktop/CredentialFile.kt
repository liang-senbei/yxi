package app.yxi.desktop

import java.io.File
import java.util.Base64
import org.json.JSONObject

internal interface CredentialProtector {
    fun protect(plain: ByteArray): ByteArray
    fun unprotect(cipher: ByteArray): ByteArray
}

/** No LOCAL_MACHINE flag: DPAPI uses the signed-in Windows user's protection. */
internal class WindowsCredentialProtector(purpose: String = "Yxi/account-credentials/v1") : CredentialProtector {
    private val entropy = purpose.toByteArray()
    override fun protect(plain: ByteArray): ByteArray = native {
        com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(plain, entropy, com.sun.jna.platform.win32.WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "Yxi credentials", null)
    }
    override fun unprotect(cipher: ByteArray): ByteArray = native {
        com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(cipher, entropy, com.sun.jna.platform.win32.WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null)
    }
    private fun native(action: () -> ByteArray): ByteArray = try { action() }
        catch (e: Exception) { throw IllegalStateException("Windows凭据保护失败，请使用原Windows账号或重新登录", e) }
        catch (e: LinkageError) { throw IllegalStateException("Windows凭据保护组件无法加载，未回退到明文保存", e) }
    companion object {
        fun forPlatform(purpose: String = "Yxi/account-credentials/v1"): CredentialProtector? = if (System.getProperty("os.name").startsWith("Windows")) WindowsCredentialProtector(purpose) else null
    }
}

/** Never fall back from an unreadable encrypted record to plaintext. Migration
 * verifies the encrypted file before clearing the application's legacy file. */
internal class CredentialFile(private val legacy: File, private val protector: CredentialProtector?,
    private val replace: (File, String) -> Unit = { file, text -> DurableFile.replace(file, text) }) {
    val protectedFile = File(legacy.parentFile, legacy.name + ".protected")
    private fun decode(raw: String): JSONObject {
        val envelope = JSONObject(raw)
        if (envelope.length() == 0) return envelope
        require(envelope.getString("format") == "yxi-dpapi-v1") { "不支持的登录凭据格式" }
        val plain = protector!!.unprotect(Base64.getDecoder().decode(envelope.getString("data")))
        return try { JSONObject(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
    }
    private fun encode(value: JSONObject): String {
        val plain = value.toString().toByteArray(Charsets.UTF_8)
        val cipher = try { protector!!.protect(plain) } finally { plain.fill(0) }
        val envelope = JSONObject().put("format", "yxi-dpapi-v1").put("data", Base64.getEncoder().encodeToString(cipher)).toString()
        check(decode(envelope).similar(value)) { "凭据保护验证失败，原记录已保留" }
        return envelope
    }
    private fun clearLegacy(expected: String?) {
        if (legacy.exists()) {
            check(expected != null && legacy.readText() == expected) { "旧登录记录在迁移期间发生变化，已保留" }
            if (expected.trim() == "{}") return
            replace(legacy, "{}")
            check(JSONObject(legacy.readText()).length() == 0) { "旧明文登录记录尚未清除" }
        }
    }
    fun read(): JSONObject {
        if (protector == null) return if (legacy.exists()) JSONObject(legacy.readText()) else JSONObject()
        if (protectedFile.exists()) {
            val value = decode(protectedFile.readText())
            if (legacy.exists()) {
                val raw = legacy.readText()
                val previous = JSONObject(raw)
                check(previous.length() == 0 || previous.similar(value)) { "本机存在不同的登录记录，请重新登录；原记录均已保留" }
                if (previous.length() != 0) clearLegacy(raw)
            }
            return value
        }
        if (!legacy.exists()) return JSONObject()
        val raw = legacy.readText()
        val value = JSONObject(raw)
        if (value.length() == 0) return value
        write(value, raw)
        return value
    }
    fun write(value: JSONObject, expectedLegacy: String? = if (legacy.exists()) legacy.readText() else null) {
        if (protector == null) { replace(legacy, value.toString()); return }
        replace(protectedFile, encode(value))
        check(decode(protectedFile.readText()).similar(value)) { "已写入凭据无法验证，旧记录未清除" }
        clearLegacy(expectedLegacy)
    }
    fun clear() {
        val protectedCleared = runCatching { if (protector != null || protectedFile.exists()) replace(protectedFile, "{}") }
        val legacyCleared = runCatching { replace(legacy, "{}") }
        protectedCleared.getOrThrow(); legacyCleared.getOrThrow()
    }
}
