package app.yxi.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 首次见到某台主机时要用户拍板；指纹变了则**根本不问**，直接拒。 */
interface TrustPrompt {
    /** 返回 true = 用户确认信任这台主机。实现方负责弹 UI。 */
    fun confirmNewHost(host: String, keyType: String, fingerprint: String): Boolean
}

/**
 * 主机指纹校验。
 *
 * ⚠️ **这是 SSH 抵御中间人的唯一防线，不能图省事关掉。**
 * `StrictHostKeyChecking=no` 意味着「谁冒充这个地址我都连」——
 * 在公网上这等于把加密白做了。
 *
 * 三种结果：
 *   · **没见过** → 显示指纹，用户确认才连，确认后记下来
 *   · **对得上** → 直连
 *   · **对不上** → **直接拒绝，连问都不问**。真是服务器重装了，
 *     用户应当在主机列表里显式删掉再重加——那是一个有意识的动作。
 */
class KnownHosts(
    private val store: HostStore,
    private val hostId: String,
    private val prompt: TrustPrompt?,
) : HostKeyRepository, HostKeys {

    /** 变更时置位，供上层给出准确的错误文案。 */
    @Volatile override var changedDetected: Boolean = false
        private set

    override fun check(host: String, key: ByteArray): Int {
        val incoming = java.util.Base64.getEncoder().encodeToString(key)
        val known = store.get(hostId)?.hostKey
        return when {
            known == null -> HostKeyRepository.NOT_INCLUDED
            known == incoming -> HostKeyRepository.OK
            else -> {
                changedDetected = true
                HostKeyRepository.CHANGED
            }
        }
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        // 只有 check() 返回 NOT_INCLUDED 且用户确认后，jsch 才会走到这里。
        //
        // ⚠️ **`HostKey.getKey()` 返回的已经是 base64 字符串**，不能再 base64 一次。
        // 之前写成 `Base64.encode(hostkey.key.toByteArray())` → 双重编码，
        // 跟 check() 里的单次编码永远对不上 → 每次连都当「第一次」，
        // **CHANGED 这条路永远走不到，指纹变了检测不出来**。这是安全漏洞不是小瑕疵。
        val h = store.get(hostId) ?: return
        store.upsert(h.copy(hostKey = hostkey.key))
    }

    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "yxi:$hostId"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

    /**
     * jsch 用它来问「没见过这台主机，连不连」。
     * jsch 在自己的线程上同步调用，所以这里用闩阻塞等 UI 的答复。
     */
    override fun userInfo(): UserInfo = object : UserInfo {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?) = false
        override fun promptPassphrase(message: String?) = false
        override fun showMessage(message: String?) = Unit

        override fun promptYesNo(message: String?): Boolean {
            // ⚠️ **指纹变了：直接拒，连问都不问。**
            // jsch 的 `StrictHostKeyChecking=ask` 在 CHANGED 时**也会走到这里**
            // （我一度以为它会自己拒绝——错的）。如果照样弹窗让用户点「连」，
            // 那这道防线等于形同虚设：社工一句「服务器刚重装过」就能骗过去。
            // 真是重装了，用户应当去主机列表显式删掉再重加——那是个有意识的动作。
            if (changedDetected) return false

            val p = prompt ?: return false          // 没有 UI 可问 → 保守拒绝
            val fp = fingerprintOf(message.orEmpty())
            var answer = false
            val latch = CountDownLatch(1)
            Thread {
                answer = runCatching {
                    p.confirmNewHost(store.get(hostId)?.connectHost.orEmpty(), "ssh-ed25519", fp)
                }.getOrDefault(false)
                latch.countDown()
            }.start()
            // 超时也按拒绝算 —— 悬着不动比错连安全
            return if (latch.await(120, TimeUnit.SECONDS)) answer else false
        }

        private fun fingerprintOf(msg: String): String =
            Regex("SHA256:[A-Za-z0-9+/=]+").find(msg)?.value ?: msg.take(80)
    }

    companion object {
        /** 把公钥 blob 算成 `SHA256:…` 形式，跟 `ssh-keygen -lf` 的输出一致。 */
        fun fingerprint(keyBlob: ByteArray): String =
            "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(keyBlob))
    }
}
