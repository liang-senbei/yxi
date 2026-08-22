package app.yxi.ssh

import java.security.Security

/**
 * **Android 的 JCA 不提供 `Ed25519` 签名算法。**
 * 无论是【生成】密钥对（[KeyManager]）还是【用它认证】（[SshSession]），
 * 都要先把完整版 BouncyCastle 插进来，否则 jsch 认为 ed25519 不可用：
 *   · 认证时日志是 `ssh-ed25519 not available for identity …`
 *   · 生成时直接抛异常，而且 **message 是 null**，光看异常什么也看不出来
 *
 * 见 TROUBLESHOOTING #12 / #20。
 */
object Crypto {
    @Volatile private var done = false

    @Synchronized
    fun ensureProviders() {
        if (done) return
        // 安卓自带一个阉割版的 BC，必须先摘掉再插完整版，否则算法查找命中旧的
        Security.removeProvider("BC")
        Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
        done = true
    }
}
