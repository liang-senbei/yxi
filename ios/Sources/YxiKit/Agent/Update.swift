import Foundation

/// 检查有没有新版本 —— **走 SFTP，不走 HTTP。**
///
/// ⚠️ 为什么不查 GitHub Release（TROUBLESHOOTING #57）：
///   · 仓库是私有的，Release API 要 token —— 把 token 塞进包里等于公开它
///   · 这个 App 到目前为止**除了那一条 SSH 连接之外没有任何网络面**。
///     为了「查个版本号」引入 HTTP 客户端、证书校验、代理处理，不划算
///   · 你的服务器你自己控制，公司内网 / 防火墙后面照样能用
///
/// 服务器上放两个文件（`server/install.sh --publish` 会摆好）：
/// ```
/// ~/.yxi/latest.json   {"versionCode":14,"versionName":"0.5.5","file":"Yxi.apk","notes":"…"}
/// ~/.yxi/Yxi.apk
/// ```
///
/// ⚠️ **iOS 上装不了 ipa**，所以这里只做「有新版本」的提示 + 说明去哪拿，
/// 不像安卓那样能直接拉下来装。清单格式沿用同一份，两端共用服务器上那一个目录。
public struct Update: Equatable, Sendable {
    public let versionCode: Int
    public let versionName: String
    public let remotePath: String
    public let notes: String
    /// 还没确认包在不在时是 -1，见 [confirm]
    public let sizeBytes: Int64

    public init(versionCode: Int, versionName: String, remotePath: String,
                notes: String, sizeBytes: Int64) {
        self.versionCode = versionCode; self.versionName = versionName
        self.remotePath = remotePath; self.notes = notes; self.sizeBytes = sizeBytes
    }

    public var sizeText: String { String(format: "%.1f MB", Double(sizeBytes) / 1_048_576.0) }

    /// 查更新的结果。
    ///
    /// ⚠️ **「没查到」和「已是最新」必须分开。**
    /// 把连不上说成「已是最新」是在骗用户 —— 他会以为自己是最新版，
    /// 而实际上可能落后好几版、正带着已知的 bug 在用。
    /// （被动检查时把 `.upToDate` 和 `.failed` 一起当「安静」处理就行，
    /// 但**主动点了「检查更新」必须说实话**。）
    public enum Result: Equatable, Sendable {
        case newer(Update)
        case upToDate
        case failed(String)
    }

    public static let dir = "/root/.yxi"
    public static var manifestPath: String { "\(dir)/latest.json" }

    /// 第一步：只看清单。返回的 `.newer` 里 `sizeBytes == -1`，**还没确认包在不在**。
    public static func parse(manifest raw: String, currentCode: Int) -> Result {
        guard let o = JSON.parse(line: raw), o.isObject else { return .failed("更新清单格式不对") }
        let code = o["versionCode"].int
        if code <= currentCode { return .upToDate }
        let file = o["file"].string.isBlank ? "Yxi.apk" : o["file"].string
        let path = file.hasPrefix("/") ? file : "\(dir)/\(file)"
        return .newer(Update(
            versionCode: code,
            versionName: o["versionName"].string.isBlank ? "\(code)" : o["versionName"].string,
            remotePath: path,
            notes: o["notes"].string,
            sizeBytes: -1
        ))
    }

    /// 第二步：确认包真的在。
    ///
    /// ⚠️ 清单说有新版本、但包不在，就**当没有** —— 别让用户点一个必然失败的按钮。
    /// （这个坑真出过：发布脚本写的是两个不同的文件名，清单更新了包没换，
    /// 下载链接一直在发旧包，TROUBLESHOOTING #69。）
    public static func confirm(_ u: Update, sizeBytes: Int64) -> Result {
        guard sizeBytes > 0 else {
            return .failed("清单说有 \(u.versionName)，但包不在（\(u.remotePath)）")
        }
        return .newer(Update(versionCode: u.versionCode, versionName: u.versionName,
                             remotePath: u.remotePath, notes: u.notes, sizeBytes: sizeBytes))
    }
}
