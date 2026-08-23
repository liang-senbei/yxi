import Foundation

/// 秘密的存放处。
///
/// ⚠️ **iOS 上不要照抄安卓那套「用 Keystore 里的 AES 密钥加密，密文落普通文件」。**
/// 安卓非那样不可，是因为 **Android Keystore 只存密钥、不存任意数据**，
/// 想保护一段密码就只能自己加一层 AES-GCM（见安卓 `Vault.kt`）。
/// **iOS 的 Keychain 本来就是存任意 secret 的地方**，条目在磁盘上已经用
/// 硬件派生的密钥加密过了。再套一层自己写的 AES-GCM
/// **不增加任何安全性，只增加一个能写错的地方**（IV 复用、tag 长度、密文格式版本）。
///
/// ⚠️ **Secure Enclave 存不了这个。** SE 只认 **ECC P-256** 密钥，
/// 既存不了 AES 密钥也存不了 ed25519 私钥 —— 所以「用 SE 保护密码」这条路根本不存在。
/// SE 真正能做的那件事见 [KeyManager] 末尾的说明。
///
/// ## 与安卓的语义差别（会影响 [Host.sealedPassword] 里存的是什么）
/// 安卓的 `seal()` 返回**密文**，`open(密文)` 解出来。
/// 这里的 [seal] 返回的是一个**随机取的钥匙牌（handle）**，真东西在 Keychain 里。
/// 存进 `hosts.json` 的因此是一串没有意义的 id ——
/// **`hosts.json` 被拷走也拿不到任何密码**，比安卓那版还干净一点。
///
/// ⚠️ **Keychain 条目在 App 被删除后仍然留着。** 用户删掉 Yxi 再装回来，
/// 旧的 SSH 私钥和主机密码会**原样回来**。对一般 App 这是特性，
/// 对一个连服务器的工具是个意外 —— 所以删主机时一定要 [discard]，
/// 换密钥时一定要真的覆盖掉旧的。
#if canImport(Security)
import Security

public enum Vault {

    /// Keychain 里所有属于我们的条目都挂在这个 service 下，方便一把清干净。
    static let service = "app.yxi.vault"

    public enum Failure: Error, Equatable {
        case keychain(OSStatus)
    }

    /// 把一段明文放进 Keychain，返回它的钥匙牌。
    public static func seal(_ plain: String) throws -> String {
        let handle = UUID().uuidString
        try write(Data(plain.utf8), account: handle)
        return handle
    }

    public static func open(_ handle: String) -> String? {
        read(account: handle).flatMap { String(data: $0, encoding: .utf8) }
    }

    /// 主机被删掉时**必须**调 —— 否则密码会一直躺在 Keychain 里，
    /// 而且熬得过卸载重装（见上面那条 ⚠️）。
    public static func discard(_ handle: String) {
        delete(account: handle)
    }

    // MARK: - 底层：给 [KeyManager] 也用

    /// ⚠️ **`AfterFirstUnlock` 而不是 `WhenUnlocked`**：锁屏状态下后台也要能连服务器
    /// （跟安卓 `setUserAuthenticationRequired(false)` 是同一个理由）。
    ///
    /// ⚠️ **`ThisDeviceOnly`**：**绝不能**跟着 iCloud Keychain 同步到别的设备、
    /// 也不能跟着加密备份还原到新手机。这是能登进用户所有服务器的凭据，
    /// 它多待在一台设备上就多一份敞口。
    static let accessibility = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

    static func write(_ data: Data, account: String) throws {
        // 先删后加：`SecItemUpdate` 要另写一套查询，而这里没有「部分更新」的需求。
        delete(account: account)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: accessibility,
        ]
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw Failure.keychain(status) }
    }

    static func read(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    static func delete(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
#endif
