package app.yxi.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Security.framework 的旧版钥匙串函数（SecKeychain*）：标了 deprecated 但仍导出，JDK 自己的 KeychainStore 也还在用。
 * 新的 SecItem* + 数据保护钥匙串要 keychain-access-groups 权限和描述文件，ad-hoc 签名拿不到，等 Developer ID 再换。
 * 只用 JNA 直调：不走 `security` 命令行——密码放在参数里，同机其他进程 ps 就能看到。 */
internal interface MacSecurityFramework : Library {
    fun SecKeychainFindGenericPassword(keychainOrArray: Pointer?, serviceNameLength: Int, serviceName: ByteArray, accountNameLength: Int, accountName: ByteArray,
        passwordLength: IntByReference, passwordData: PointerByReference, itemRef: PointerByReference?): Int
    fun SecKeychainAddGenericPassword(keychain: Pointer?, serviceNameLength: Int, serviceName: ByteArray, accountNameLength: Int, accountName: ByteArray,
        passwordLength: Int, passwordData: ByteArray, itemRef: PointerByReference?): Int
    fun SecKeychainItemFreeContent(attrList: Pointer?, data: Pointer?): Int
}

private val security: MacSecurityFramework by lazy { Native.load("Security", MacSecurityFramework::class.java) }
private val random = SecureRandom()

internal class KeychainException(val status: Int, message: String) : IllegalStateException(message)

/** 钥匙串里一个 generic password 条目，存 32 字节随机主密钥（service 用 bundle ID）；进程内只问一次钥匙串。
 * keychain = null 是用户默认的钥匙串搜索列表；测试传 SecKeychainCreate 建的临时钥匙串，不碰登录钥匙串。 */
internal class MacKeychainKey(private val keychain: Pointer? = null, service: String = "app.yxi.desktop", account: String = "master-key-v1") {
    private val service = service.toByteArray(Charsets.UTF_8)
    private val account = account.toByteArray(Charsets.UTF_8)
    private var cached: ByteArray? = null

    /** create = false 只读：解密时绝不新建主密钥——新钥匙解不开旧记录，只会把「钥匙串被清过」伪装成「记录坏了」。 */
    @Synchronized fun obtain(create: Boolean): ByteArray {
        cached?.let { return it.copyOf() }
        var result = find()
        if (result.first == ITEM_NOT_FOUND && create) {
            val fresh = ByteArray(KEY_BYTES).also(random::nextBytes)
            val added = try { security.SecKeychainAddGenericPassword(keychain, service.size, service, account.size, account, fresh.size, fresh, null) }
                finally { fresh.fill(0) }
            // 另一个 Yxi 进程可能刚好抢先写入：以钥匙串里实际存的为准
            if (added != 0 && added != DUPLICATE_ITEM) throw KeychainException(added, reason(added))
            result = find()
        }
        val (status, key) = result
        if (status != 0 || key == null) throw KeychainException(status, reason(status))
        if (key.size != KEY_BYTES) { key.fill(0); throw KeychainException(status, "钥匙串里的Yxi主密钥长度不对（未覆盖它）") }
        cached = key
        return key.copyOf()
    }

    internal fun find(): Pair<Int, ByteArray?> {
        val length = IntByReference()
        val data = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(keychain, service.size, service, account.size, account, length, data, null)
        if (status != 0) return status to null
        val pointer = data.value ?: return status to ByteArray(0)
        return try { status to pointer.getByteArray(0, length.value) }
        finally { pointer.clear(length.value.toLong()); security.SecKeychainItemFreeContent(null, pointer) }
    }

    companion object {
        const val ITEM_NOT_FOUND = -25300
        const val DUPLICATE_ITEM = -25299
        private const val KEY_BYTES = 32
        val shared by lazy { MacKeychainKey() }
        fun reason(status: Int) = when (status) {
            ITEM_NOT_FOUND -> "钥匙串里没有Yxi主密钥"
            -25308 -> "钥匙串已锁定或当前不允许弹出授权"
            -25293 -> "钥匙串授权失败"
            -128 -> "钥匙串授权已取消"
            -25307 -> "没有默认钥匙串"
            -25294, -25295 -> "钥匙串不存在或已损坏"
            -25291 -> "钥匙串服务不可用"
            else -> "OSStatus $status"
        }
    }
}

/** macOS 版的 DPAPI 替身：钥匙串里的主密钥 + AES-256-GCM（Chromium「Chrome Safe Storage」、Electron safeStorage 同一路数）。
 * purpose 进 AAD，账号凭据和服务器凭据互相解不开，对应 Windows 的 entropy。钥匙串不可用就抛错，绝不回退明文。
 * 密文：版本 1 字节 + IV 12 字节 + 密文‖GCM 标签 16 字节。 */
internal class MacKeychainCredentialProtector(purpose: String = "Yxi/account-credentials/v1", private val key: MacKeychainKey = MacKeychainKey.shared) : CredentialProtector {
    private val aad = purpose.toByteArray(Charsets.UTF_8)
    override val scheme: String get() = "keychain"

    override fun protect(plain: ByteArray): ByteArray {
        val secret = master(create = true) { "macOS钥匙串不可用：$it；未回退到明文保存" }
        try {
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val aes = Cipher.getInstance(TRANSFORM)
            aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(TAG_BITS, iv))
            aes.updateAAD(aad)
            return byteArrayOf(VERSION) + iv + aes.doFinal(plain)
        } finally { secret.fill(0) }
    }

    override fun unprotect(cipher: ByteArray): ByteArray {
        check(cipher.size >= 1 + IV_BYTES + TAG_BITS / 8 && cipher[0] == VERSION) { "不支持的钥匙串凭据格式，原记录已保留" }
        val secret = master(create = false) { "macOS钥匙串无法提供Yxi主密钥：$it；请解锁钥匙串后重试或重新登录，原记录已保留" }
        try {
            val aes = Cipher.getInstance(TRANSFORM)
            aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(TAG_BITS, cipher, 1, IV_BYTES))
            aes.updateAAD(aad)
            return try { aes.doFinal(cipher, 1 + IV_BYTES, cipher.size - 1 - IV_BYTES) }
            catch (e: AEADBadTagException) { throw IllegalStateException("钥匙串里的主密钥解不开这份凭据，请重新登录；原记录已保留", e) }
        } finally { secret.fill(0) }
    }

    private fun master(create: Boolean, message: (String) -> String): ByteArray = try { key.obtain(create) }
        catch (e: KeychainException) { throw IllegalStateException(message(e.message.orEmpty()), e) }
        catch (e: LinkageError) { throw IllegalStateException("macOS钥匙串组件无法加载，未回退到明文保存", e) }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
