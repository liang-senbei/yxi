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
        // ⚠️⚠️ **动 provider 之前，先把默认 SSLContext 建出来。** 这一行不是热身优化，是修 bug 的。
        //
        // 安卓的 `DefaultSSLContextImpl` 是**懒初始化**的：第一次有人要 HTTPS 时才构造，
        // 构造时按当时的 provider 顺序去找 `TrustManagerFactory`（PKIX）。而**它一旦构造失败，
        // 失败结果会被永久缓存**——这个进程之后所有 HTTPS 全废，报的是：
        //
        //   java.security.NoSuchAlgorithmException: Error constructing implementation
        //   (algorithm: Default, provider: AndroidOpenSSL,
        //    class: com.android.org.conscrypt.DefaultSSLContextImpl$TLSv13)
        //
        // 2026-09-04 实测的表现极其误导：App 刚起来一切正常，**连上一台主机之后**，
        // 账号接口、检查更新、会员中心全部悄悄失败 —— 因为这时候才第一次动 provider，
        // 而 HTTPS 的第一次使用又恰好排在它后面。查了很久：日志里只有一行「没拿到 token」。
        // 现在登录是强制的（[app.yxi.ui.LoginGate]），令牌续不上 = 用户被卡在门外。
        //
        // 先建一次就把好的那份缓存住了，之后怎么动 provider 都不影响它。
        runCatching { javax.net.ssl.SSLContext.getInstance("Default") }
        // 安卓自带一个阉割版的 BC，必须先摘掉再插完整版，否则算法查找命中旧的
        Security.removeProvider("BC")
        // ⚠️⚠️ **必须 addProvider（排最后），绝对不能 insertProviderAt(…, 1)。**
        //
        // 插到第 1 位会把 BC 排在 Conscrypt（AndroidOpenSSL）**前面**，于是
        // `TrustManagerFactory.getDefaultAlgorithm()`（PKIX）解析到 BC 的实现，
        // 而它读不了安卓的系统信任库 → `SSLContext.getInstance("Default")` 构造失败：
        //
        //   java.security.NoSuchAlgorithmException: Error constructing implementation
        //   (algorithm: Default, provider: AndroidOpenSSL,
        //    class: com.android.org.conscrypt.DefaultSSLContextImpl$TLSv13)
        //
        // 后果是**这个进程里所有 HTTPS 当场全废**，而且是在**第一次连 SSH 之后**才发作 ——
        // 表现极其误导：App 刚起来一切正常，连上一台主机之后，账号接口、检查更新、
        // 会员中心全部悄悄失败（2026-09-04 实测，查了很久才定位）。
        // 现在登录是强制的（[app.yxi.ui.LoginGate]），令牌续不上 = 用户被卡死在门外。
        //
        // 排最后一样能用：Ed25519 只有 BC 提供，查找会一路走到它；
        // 而 TLS 相关的算法继续由 Conscrypt 提供 —— 这本来就是每个安卓 App 的正常状态。
        Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        done = true
        // 出事的时候这一行是唯一的线索：provider 顺序 + 默认 SSLContext 还能不能建
        if (runCatching { javax.net.ssl.SSLContext.getInstance("Default") }.isFailure) {
            android.util.Log.e(
                "YxiCrypto",
                "默认 SSLContext 建不起来了 —— 这个进程的 HTTPS 会全废。provider 顺序：" +
                    Security.getProviders().joinToString(",") { it.name },
            )
        }
    }
}
