package app.yxi.ssh

/**
 * 一台主机的连接配置。
 *
 * ⚠️ 端口不能写死 22 —— 客户那台 Windows 走 2222（见 PRD §2.4）。
 * 认证两种都要：新开的云主机常常一开始只有密码，装完公钥再切密钥。
 */
data class HostConfig(
    val alias: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val auth: Auth,
) {
    sealed interface Auth {
        data class Password(val password: String) : Auth
        /** PEM 文本。G3 会改成从 Android Keystore 取，这里先收裸文本便于 G2 验证。 */
        data class PrivateKey(val pem: String, val passphrase: String? = null) : Auth
    }
}
