package app.yxi.agent

import app.yxi.ui.t
import app.yxi.ssh.Sftp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** SFTP 路径（服务器上那份）。走公网时为空。 */
    val remotePath: String,
    val notes: String,
    val sizeBytes: Long,
    /** 公网直链。非空 = 走 HTTP 下载（[Source.Public]）。 */
    val url: String = "",
) {
    val fromPublic: Boolean get() = url.isNotBlank()

    val sizeText: String get() = "%.1f MB".format(sizeBytes / 1048576.0)

    /**
     * 主动查更新的结果。
     *
     * ⚠️ **「没查到」和「已是最新」必须分开。**
     * 把连不上说成「已是最新」是在骗用户 —— 他会以为自己是最新版，
     * 而实际上可能落后好几版、正带着已知的 bug 在用。
     */
    sealed interface Result {
        data class Newer(val update: Update) : Result
        data object UpToDate : Result
        data class Failed(val why: String) : Result
    }

    companion object {
        private const val DIR = "/root/.yxi"

        /**
         * **公网下载页**。用户要求「App 更新走公网下载页」——
         * 这样**任何客户**都能在 App 里更新，不用他自己的服务器上放包（那要我们能登他机器）。
         *
         * ⚠️ **HTTPS**（Let's Encrypt，certbot 自动续期）。所以 App 里**没有**任何明文 HTTP 豁免。
         * ⚠️ 根目录 `/latest.json`、`/Yxi.apk` 由 nginx 别名指向当前发布目录，永远是最新的，
         * 所以这里**不需要带 token** —— 少一个写死在包里的东西。
         * ⚠️ 老的 `http://64.90.25.56:8899/<token>/` 仍然保留，别断了已装旧版的人。
         */
        // ⚠️ **改域名要留后路。** 已经装出去的 0.9.24~0.9.27 里这一行写死的是
        // `dl.keuury.com` —— 它们只会去问那个地址。所以 `dl` **不能停**，
        // 至少要留到「用户手机上装的是切过来之后的版本」为止；
        // 停早了 = 那些版本永远收不到更新提示，也就永远升不上来。
        // 现在 dl 那头：页面 301 到新域名，但 `latest.json` / `Yxi.apk` 仍然直供 200。
        const val PUBLIC_BASE = "https://yxi.keuury.com"

        /** 从公网下载页查 —— 读 `<base>/latest.json`。读不到返回 null（安静）。 */
        suspend fun checkPublic(currentCode: Int): Update? =
            (publicVerbose(currentCode) as? Result.Newer)?.update

        /** 公网查更新，三种结果分清楚（主动点「检查更新」用）。 */
        suspend fun publicVerbose(currentCode: Int): Result = withContext(Dispatchers.IO) {
            val raw = runCatching { httpGet("$PUBLIC_BASE/latest.json") }
                .getOrElse { return@withContext Result.Failed(t("连不上下载页：%s").format(it.message ?: "")) }
                ?: return@withContext Result.Failed(t("下载页没返回内容"))
            val o = runCatching { JSONObject(raw) }
                .getOrElse { return@withContext Result.Failed(t("更新清单格式不对")) }
            val code = o.optInt("versionCode", 0)
            if (code <= currentCode) return@withContext Result.UpToDate
            val file = o.optString("file").ifBlank { "Yxi.apk" }
            val url = "$PUBLIC_BASE/$file"
            // ⚠️ 先 HEAD 一下拿大小：拿不到大小就没法校验下全没下全（半个 APK 比不更新糟）
            val size = runCatching { httpSize(url) }.getOrDefault(-1L)
            if (size <= 0) return@withContext Result.Failed(t("下载页上没有安装包"))
            Result.Newer(
                Update(code, o.optString("versionName", code.toString()), "", o.optString("notes"), size, url)
            )
        }

        private fun httpGet(url: String): String? {
            val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000; requestMethod = "GET"
            }
            return try {
                if (c.responseCode !in 200..299) null
                else c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
        }

        private fun httpSize(url: String): Long {
            val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000; requestMethod = "HEAD"
            }
            return try {
                if (c.responseCode !in 200..299) -1L else c.contentLengthLong
            } finally { c.disconnect() }
        }

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

        /**
         * 设置页里主动点「检查更新」走这条 —— 它要把**三种**结果分清楚。
         * [check] 那个把「没有更新」和「读不到」都返回 null，用在被动检查上没问题
         * （安静即可），但主动点了按钮却告诉你「已是最新」就是骗人。
         */
        suspend fun checkVerbose(sftp: Sftp, currentCode: Int): Result {
            val raw = runCatching { sftp.read("$DIR/latest.json", 64 * 1024).decodeToString() }
                .getOrElse { return Result.Failed(t("这台机器上没放更新包（%s/latest.json 读不到）").format(DIR)) }
            val o = runCatching { JSONObject(raw) }
                .getOrElse { return Result.Failed(t("更新清单格式不对")) }
            val code = o.optInt("versionCode", 0)
            if (code <= currentCode) return Result.UpToDate
            val file = o.optString("file").ifBlank { "Yxi.apk" }
            val path = if (file.startsWith("/")) file else "$DIR/$file"
            val size = sftp.size(path)
            if (size <= 0) return Result.Failed(t("清单说有 %s，但包不在（%s）").format(o.optString("versionName"), path))
            return Result.Newer(
                Update(code, o.optString("versionName", code.toString()), path, o.optString("notes"), size)
            )
        }
    }
}
