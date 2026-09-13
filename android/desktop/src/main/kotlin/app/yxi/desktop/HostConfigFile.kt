package app.yxi.desktop

import java.io.File
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Encrypt the host list and its active backup; retain encrypted migration copies
 * before clearing legacy plaintext, including an older backup or damaged input. */
internal class HostConfigFile(private val file: File, private val protector: CredentialProtector?, private val validate: (String) -> Unit) {
    private val legacy = DurableFile(file, validate)
    private val encrypted = DurableFile(File(file.parentFile, file.name + ".protected")) { validate(decode(it)) }
    var recovered = false; private set
    private fun decode(raw: String): String {
        val value = JSONObject(raw)
        require(value.getString("format") == "yxi-hosts-dpapi-v1") { "服务器保护格式无法识别" }
        val plain = protector!!.unprotect(Base64.getDecoder().decode(value.getString("data")))
        return try { plain.toString(Charsets.UTF_8) } finally { plain.fill(0) }
    }
    private fun encode(raw: String): String {
        val plain = raw.toByteArray(Charsets.UTF_8)
        val cipher = try { protector!!.protect(plain) } finally { plain.fill(0) }
        val result = JSONObject().put("format", "yxi-hosts-dpapi-v1").put("data", Base64.getEncoder().encodeToString(cipher)).toString()
        check(decode(result) == raw) { "服务器凭据保护验证失败" }
        return result
    }
    private fun clearPlaintext() {
        listOf(file, File(file.parentFile, file.name + ".bak"), File(file.parentFile, file.name + ".damaged")).forEach { source ->
            if (!source.exists()) return@forEach
            val raw = source.readText()
            check(source.readBytes().contentEquals(raw.toByteArray(Charsets.UTF_8))) { "旧服务器副本不是有效UTF-8，原文件已保留" }
            if (raw.trim() == "[]") return@forEach
            val copy = File(source.parentFile, source.name + ".migration-copy.protected")
            if (copy.exists()) check(decode(copy.readText()) == raw) { "旧服务器副本发生变化，已保留所有文件" }
            else DurableFile.replace(copy, encode(raw))
            check(decode(copy.readText()) == raw && source.readText() == raw) { "服务器迁移记录已变化，未清除原文件" }
            DurableFile.replace(source, "[]")
        }
    }
    @Synchronized fun read(): String? {
        recovered = false
        if (protector == null) {
            check(!encrypted.file.exists() && !File(file.parentFile, file.name + ".protected.bak").exists()) { "请在原Windows账号下读取受保护的服务器配置" }
            return legacy.read().also { recovered = legacy.recovered }
        }
        val saved = encrypted.read()
        recovered = encrypted.recovered
        if (saved != null) {
            val raw = decode(saved)
            if (file.exists()) {
                val old = file.readText().also(validate)
                check(JSONArray(old).length() == 0 || JSONArray(old).similar(JSONArray(raw))) { "明文和受保护服务器记录不同，未覆盖任何记录" }
            }
            clearPlaintext()
            return raw
        }
        val raw = legacy.read() ?: return null
        recovered = legacy.recovered
        encrypted.write(encode(raw))
        check(decode(encrypted.read()!!) == raw)
        clearPlaintext()
        return raw
    }
    @Synchronized fun write(raw: String) {
        validate(raw)
        if (protector == null) { read(); legacy.write(raw); return }
        read() // Complete or reject migration before accepting a replacement list.
        encrypted.write(encode(raw))
    }
}
