package app.yxi.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 秘密的加解密：密钥本身放 **Android Keystore**（硬件支持时导不出来），
 * 我们只拿它加解密，密文落普通文件。
 *
 * 用来保护两样东西：
 *   · App 自己的 SSH 私钥（[KeyManager]）
 *   · 每台主机记住的登录密码（[HostStore]）
 *
 * ⚠️ **绝不明文落盘。** 手机丢了别人捡到，翻文件也拿不到密码和私钥
 * （Keystore 的密钥拿不出设备）。
 */
object Vault {
    private const val ALIAS = "yxi.vault.v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // 不要求解锁才能用：前台服务要在锁屏时也能连服务器发通知（G10）
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    /** 密文格式：base64(iv ‖ ciphertext)。iv 每次随机，绝不复用。 */
    fun seal(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun open(sealed: String): String? = runCatching {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN))
        }
        String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN))
    }.getOrNull()
}
