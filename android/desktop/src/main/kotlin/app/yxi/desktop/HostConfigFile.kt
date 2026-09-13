package app.yxi.desktop

import java.io.File
import java.util.Base64
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal data class HostRecoveryCopy(val name: String, val fingerprint: String, val count: Int?, val summary: List<String>, val valid: Boolean)

/** Encrypt the host list and its active backup; retain encrypted migration copies
 * before clearing legacy plaintext, including an older backup or damaged input. */
internal class HostConfigFile(private val file: File, private val protector: CredentialProtector?, private val validate: (String) -> Unit) {
    private val legacy = DurableFile(file, validate)
    private val encrypted = DurableFile(File(file.parentFile, file.name + ".protected")) { validate(decode(it)) }
    var recovered = false; private set
    private fun copies(): List<File> {
        val directory = file.absoluteFile.parentFile
        if (!directory.exists()) return emptyList()
        val exact = setOf(file.name + ".migration-copy.protected", file.name + ".bak.migration-copy.protected", file.name + ".damaged.migration-copy.protected")
        val imported = Regex(Regex.escape(file.name) + "\\.import-[a-f0-9]{64}\\.protected")
        return (directory.listFiles() ?: error("无法读取服务器副本目录")).filter { it.name in exact || imported.matches(it.name) }.sortedBy { it.name }
    }
    fun needsRecovery() = protector != null && !encrypted.file.exists() && !File(file.parentFile, file.name + ".protected.bak").exists() && copies().isNotEmpty()
    private fun fingerprint(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    @Synchronized fun recoveryCopies(): List<HostRecoveryCopy> {
        require(protector != null) { "请使用原Windows账号恢复服务器配置" }
        return copies().map { copy ->
            runCatching {
                val cipher = copy.readText()
                val raw = decode(cipher).also(validate)
                val hosts = JSONArray(raw)
                HostRecoveryCopy(copy.name, fingerprint(cipher), hosts.length(), (0 until hosts.length()).map {
                    val host = hosts.getJSONObject(it)
                    "${host.optString("alias").ifBlank { host.optString("hostname", "未命名主机") }} · ${host.optString("hostname")} : ${host.optInt("port", 22)}"
                }, true)
            }.getOrElse { HostRecoveryCopy(copy.name, "", null, emptyList(), false) }
        }
    }
    @Synchronized fun restoreMissing(name: String, expectedFingerprint: String): String {
        check(needsRecovery()) { "当前主配置或活动备份仍存在，未覆盖它们" }
        val source = copies().singleOrNull { it.name == name } ?: error("所选副本不存在")
        val cipher = source.readText()
        check(fingerprint(cipher) == expectedFingerprint) { "副本已经变化，请重新选择" }
        val raw = decode(cipher).also(validate)
        val old = if (file.exists()) readPlain(file).also(validate) else null
        check(old == null || JSONArray(old).length() == 0 || JSONArray(old).similar(JSONArray(raw))) { "本地存在不同的明文记录，已保留双方" }
        val protected = encode(raw)
        check(needsRecovery() && source.readText() == cipher) { "恢复目标已变化，未覆盖" }
        encrypted.write(protected)
        check(decode(encrypted.read()!!) == raw)
        clearPlaintext(old)
        return raw
    }
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
    private fun plainFiles(base: File) = listOf(base, File(base.parentFile, base.name + ".bak"), File(base.parentFile, base.name + ".damaged"))
    private fun readPlain(source: File): String {
        val bytes = source.readBytes()
        val raw = bytes.toString(Charsets.UTF_8)
        check(bytes.contentEquals(raw.toByteArray(Charsets.UTF_8))) { "旧服务器副本不是有效UTF-8，原文件已保留" }
        return raw
    }
    private fun archiveAndClear(source: File, copy: File, expected: String? = null) {
        if (!source.exists()) return
        val raw = readPlain(source)
        check(expected == null || expected == raw) { "旧服务器记录在迁移期间变化，原文件已保留" }
        if (raw.trim() == "[]") return
        if (copy.exists()) check(decode(copy.readText()) == raw) { "旧服务器副本发生变化，已保留所有文件" }
        else DurableFile.replace(copy, encode(raw))
        check(decode(copy.readText()) == raw && readPlain(source) == raw) { "服务器迁移记录已变化，未清除原文件" }
        DurableFile.replace(source, "[]")
    }
    private fun clearPlaintext(expectedMain: String?) {
        plainFiles(file).forEach { source -> archiveAndClear(source, File(source.parentFile, source.name + ".migration-copy.protected"), if (source == file) expectedMain else null) }
    }
    @Synchronized fun importLegacy(source: File) {
        require(protector != null) { "旧服务器配置需要Windows系统保护" }
        if (source.canonicalFile == file.canonicalFile) return
        val snapshots = plainFiles(source).filter { it.exists() }.associateWith(::readPlain)
        if (snapshots.isEmpty()) return
        val primary = snapshots[source]
        val backup = snapshots[File(source.parentFile, source.name + ".bak")]
        var fromBackup = primary == null && backup != null
        val candidate = if (primary != null) runCatching { primary.also(validate) }.getOrElse { problem -> fromBackup = true; backup?.also(validate) ?: throw problem } else backup?.also(validate)
        val current = read()
        recovered = recovered || fromBackup
        if (candidate != null) {
            check(current == null || JSONArray(candidate).length() == 0 || JSONArray(current).similar(JSONArray(candidate))) { "漫游和本地服务器记录不同，已保留双方" }
            if (current == null) {
                encrypted.write(encode(candidate))
                check(decode(encrypted.read()!!) == candidate) { "服务器迁移保护验证失败" }
            }
        }
        snapshots.forEach { (path, raw) ->
            val identity = MessageDigest.getInstance("SHA-256").digest(path.canonicalPath.toByteArray()).joinToString("") { "%02x".format(it) }
            archiveAndClear(path, File(file.parentFile, file.name + ".import-" + identity + ".protected"), raw)
        }
    }
    @Synchronized fun read(): String? {
        recovered = false
        if (protector == null) {
            check(!encrypted.file.exists() && !File(file.parentFile, file.name + ".protected.bak").exists() && copies().isEmpty()) { "请在原Windows账号下读取受保护的服务器配置" }
            return legacy.read().also { recovered = legacy.recovered }
        }
        val saved = encrypted.read()
        recovered = encrypted.recovered
        if (saved != null) {
            val raw = decode(saved)
            val old = if (file.exists()) readPlain(file).also(validate) else null
            if (old != null) {
                check(JSONArray(old).length() == 0 || JSONArray(old).similar(JSONArray(raw))) { "明文和受保护服务器记录不同，未覆盖任何记录" }
            }
            clearPlaintext(old)
            return raw
        }
        check(!needsRecovery()) { "受保护服务器主配置缺失，发现迁移副本；请先选择恢复，不会保存为空列表" }
        val raw = legacy.read() ?: return null
        recovered = legacy.recovered
        encrypted.write(encode(raw))
        check(decode(encrypted.read()!!) == raw)
        clearPlaintext(raw)
        return raw
    }
    @Synchronized fun write(raw: String) {
        validate(raw)
        if (protector == null) { read(); legacy.write(raw); return }
        read() // Complete or reject migration before accepting a replacement list.
        encrypted.write(encode(raw))
    }
}
