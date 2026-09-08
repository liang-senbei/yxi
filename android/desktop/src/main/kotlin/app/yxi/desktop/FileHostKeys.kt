package app.yxi.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.yxi.ssh.HostKeys
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.Base64

/**
 * 主机指纹校验，存 [Store.knownHostsFile]，一行一台：`host keytype base64key`
 * （host 照 jsch 的写法：非 22 端口是 `[host]:port`，跟 OpenSSH 的 known_hosts 同格式）。
 * 逻辑照手机端 KnownHosts：没见过 → 弹窗让用户核对指纹；对得上 → 直连；对不上 → **直接拒，连问都不问**。
 * 这是 SSH 抵御中间人的唯一防线，不能图省事关掉。一个实例服务所有主机（一个文件、一个弹窗）。
 */
class FileHostKeys : HostKeys {
    /** 等界面确认的首次连接；弹窗往 [answer] 里填 true/false。 */
    class Prompt(val host: String, val keyType: String, val fingerprint: String, val answer: CompletableDeferred<Boolean>)

    var pending by mutableStateOf<Prompt?>(null)
    @Volatile override var changedDetected = false
        private set
    /** check() 见到的没记过的键，留给 promptYesNo 算指纹用 */
    @Volatile private var unknown: Pair<String, ByteArray>? = null

    private val file get() = Store.knownHostsFile
    private fun lines() = runCatching { file.readLines() }.getOrDefault(emptyList()).filter { it.isNotBlank() }
    private fun keysOf(host: String) = lines().map { it.split(' ') }.filter { it.size >= 3 && it[0] == host }.map { it[2] }

    override fun check(host: String, key: ByteArray): Int {
        val known = keysOf(host)
        val incoming = Base64.getEncoder().encodeToString(key)
        changedDetected = known.isNotEmpty() && incoming !in known   // 每次连都重算：上一台变了不该赖到下一台头上
        unknown = if (known.isEmpty()) host to key else null
        return when {
            known.isEmpty() -> HostKeyRepository.NOT_INCLUDED
            changedDetected -> HostKeyRepository.CHANGED
            else -> HostKeyRepository.OK
        }
    }

    // 只有 check() 说没见过、用户又点了确认，jsch 才会走到这里。
    // ⚠️ hostkey.key 已经是 base64，别再编一次（手机端踩过：双重编码 → 永远对不上 → CHANGED 永远检测不到）。
    override fun add(hostkey: HostKey, ui: UserInfo?) { file.appendText("${hostkey.host} ${hostkey.type} ${hostkey.key}\n") }

    override fun remove(host: String?, type: String?) { file.writeText(lines().filterNot { it.startsWith("$host ") }.joinToString("") { it + "\n" }) }
    override fun remove(host: String?, type: String?, key: ByteArray?) = remove(host, type)
    override fun getKnownHostsRepositoryID(): String = file.path
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

    /** 删掉一台主机记过的指纹（服务器真重装了 / 删主机时一起清） */
    fun forget(h: Host) = remove(if (h.port == 22) h.hostname else "[${h.hostname}]:${h.port}", null)

    override fun userInfo(): UserInfo = object : UserInfo {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?) = false
        override fun promptPassphrase(message: String?) = false
        override fun showMessage(message: String?) = Unit

        override fun promptYesNo(message: String?): Boolean {
            // ⚠️ 指纹变了：直接拒。jsch 的 StrictHostKeyChecking=ask 在 CHANGED 时**也会**问到这里，
            // 照样弹窗就等于防线形同虚设（「服务器刚重装过」一句话就能骗过去）。
            if (changedDetected) return false
            val (host, key) = unknown ?: return false
            val p = Prompt(host, HostKey(host, key).type, fingerprint(key), CompletableDeferred())
            pending = p
            // jsch 在 IO 线程上同步等答案；超时按拒绝算 —— 悬着比错连安全
            // ⚠️ finally 只清自己的 prompt：快速切主机时新主机的 promptYesNo 可能已经把 pending 换成它的了，
            //    无条件 `pending = null` 会把新弹窗灭掉 → 新连接默默超时拒绝。
            return try { runBlocking { withTimeoutOrNull(120_000) { p.answer.await() } ?: false } } finally { if (pending === p) pending = null }
        }
    }

    companion object {
        /** `SHA256:…`，跟 `ssh-keygen -lf` 的输出一致（同手机端 KnownHosts.fingerprint） */
        fun fingerprint(key: ByteArray): String =
            "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
    }
}
