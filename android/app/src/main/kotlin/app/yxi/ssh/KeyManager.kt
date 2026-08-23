package app.yxi.ssh

import android.content.Context
import android.util.Base64
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * App 自己的那一把 SSH 密钥。
 *
 * **ed25519** —— Android 的 JCA 本身不支持它，靠注册 BouncyCastle 撑起来
 * （见 [SshSession] 的 `registerBouncyCastle` 和 TROUBLESHOOTING #12）。
 *
 * 私钥用 [Vault] 加密后落盘，明文只在内存里存在。
 * **撤销方式**：在服务器上删掉 `authorized_keys` 里对应那一行即可，不用改任何 App 配置。
 */
class KeyManager(private val ctx: Context) {

    /** 进程级：公钥日志每次启动只打一行，别刷屏 */
    private var announced = false

    private val file get() = File(ctx.filesDir, "id_ed25519.sealed")

    /** 公钥的 `ssh-ed25519 AAAA… comment` 形式，贴进 `authorized_keys` 用。 */
    fun publicKeyLine(): String = load().let { kp ->
        // 便于自动化验证时取出来；不含私钥，打日志安全
        android.util.Log.i("YxiKey", "pub=" + Base64.encodeToString(kp.publicKeyBlob, Base64.NO_WRAP))
        val blob = Base64.encodeToString(kp.publicKeyBlob, Base64.NO_WRAP)
        "ssh-ed25519 $blob yxi@android"
    }

    /** 私钥（OpenSSH v1 格式），交给 jsch 做认证。**不写日志、不出内存**。 */
    fun privateKeyPem(): String = load().let { kp -> exportPem(kp) }

    /** 指纹，给用户核对用（跟服务器上 `ssh-keygen -lf` 的输出对得上）。 */
    fun fingerprint(): String = load().getFingerPrint()

    fun exists(): Boolean = file.exists()

    /** 重新生成一把。⚠️ 旧公钥立刻失效——所有装过它的服务器都要重装。 */
    fun regenerate() { file.delete(); load() }

    private fun load(): KeyPair = loadRaw().also { announceOnce(it) }

    /**
     * 每个进程报一次「这次用的是哪把公钥」。
     *
     * ⚠️ 公钥不是秘密（它本来就要贴到服务器上去），但**它变了就是大事** ——
     * 所有机器上那行 `authorized_keys` 会同时失效，而现象只是「突然连不上」。
     * 有这一行日志，`adb logcat -s YxiKey` 一眼就能对上号。
     */
    private fun announceOnce(kp: KeyPair) {
        if (announced) return
        announced = true
        android.util.Log.i("YxiKey", "本次使用 pub=" + Base64.encodeToString(kp.publicKeyBlob, Base64.NO_WRAP))
    }

    private fun loadRaw(): KeyPair {
        Crypto.ensureProviders()   // ⚠️ 没它 ED25519 生成会抛异常，且 message 为 null
        val jsch = JSch()
        if (file.exists()) {
            Vault.open(file.readText())?.let { pem ->
                return KeyPair.load(jsch, pem.toByteArray(), null)
            }
            // 解不开（换机、清数据、Keystore 被重置）→ 重新生成，不要卡死
            file.delete()
        }
        val kp = KeyPair.genKeyPair(jsch, KeyPair.ED25519)
        file.writeText(Vault.seal(exportPem(kp)))
        // ⚠️ **生成新密钥是件大事**：所有服务器上原来那行 authorized_keys 立刻失效。
        // 它可能因为清数据、换机、Keystore 被重置而悄悄发生 ——
        // 记一行日志，下次查「为什么突然连不上了」时一眼就看到。
        android.util.Log.w(
            "YxiKey",
            "生成了新密钥（旧的已失效）pub=" + Base64.encodeToString(kp.publicKeyBlob, Base64.NO_WRAP),
        )
        return kp
    }

    /**
     * ⚠️ **ed25519 私钥必须写成 OpenSSH v1 格式。**
     * `writePrivateKey()` 走的是传统 PEM，对 ed25519 直接抛
     * `UnsupportedOperationException`（而且 **message 是 null**，看异常什么也看不出来）。
     * 正确的 API 是 `writeOpenSSHv1PrivateKey`。见 TROUBLESHOOTING #20。
     */
    private fun exportPem(kp: KeyPair): String =
        ByteArrayOutputStream().also { kp.writeOpenSSHv1PrivateKey(it, null) }.toString()
}
