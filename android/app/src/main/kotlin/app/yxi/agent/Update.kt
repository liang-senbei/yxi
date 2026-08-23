package app.yxi.agent

import app.yxi.ssh.Sftp
import org.json.JSONObject

/**
 * 检查有没有新版本 —— **走 SFTP，不走 HTTP。**
 *
 * ⚠️ 为什么不查 GitHub Release：
 *   · 仓库是私有的，Release 的 API 要 token —— 把 token 塞进 APK 等于公开它
 *   · 这个 App 到目前为止**除了那一条 SSH 连接之外没有任何网络面**。
 *     为了「查个版本号」引入 HTTP 客户端、证书校验、代理处理，不划算
 *   · 你的服务器你自己控制，公司内网 / 防火墙后面照样能用
 *
 * 服务器上放两个文件（`server/install.sh --publish <apk>` 会摆好）：
 * ```
 * ~/.yxi/latest.json   {"versionCode":2,"versionName":"0.2.0","file":"Yxi.apk","notes":"…"}
 * ~/.yxi/Yxi.apk
 * ```
 */
data class Update(
    val versionCode: Int,
    val versionName: String,
    val remotePath: String,
    val notes: String,
    val sizeBytes: Long,
) {
    val sizeText: String get() = "%.1f MB".format(sizeBytes / 1048576.0)

    companion object {
        private const val DIR = "/root/.yxi"

        /**
         * @param currentCode 本机装的 versionCode
         * @return null = 没有更新 / 服务器上没放 / 读不到。**一律安静，不打扰**
         */
        suspend fun check(sftp: Sftp, currentCode: Int): Update? = runCatching {
            val raw = sftp.read("$DIR/latest.json", 64 * 1024).decodeToString()
            val o = JSONObject(raw)
            val code = o.optInt("versionCode", 0)
            if (code <= currentCode) return null          // 不比现在的新就当没有
            val file = o.optString("file").ifBlank { "Yxi.apk" }
            val path = if (file.startsWith("/")) file else "$DIR/$file"
            val size = sftp.size(path)
            // ⚠️ 清单说有新版本、但包不在，就当没有 —— 别让用户点一个必然失败的按钮
            if (size <= 0) return null
            Update(code, o.optString("versionName", code.toString()), path, o.optString("notes"), size)
        }.getOrNull()
    }
}
