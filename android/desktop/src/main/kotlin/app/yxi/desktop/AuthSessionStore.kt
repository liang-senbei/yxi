package app.yxi.desktop

import androidx.compose.runtime.*
import org.json.JSONObject
import java.io.File

internal class StaleAuthSession : IllegalStateException("登录会话已变化，已忽略旧请求")

/** Short critical sections only: callers perform network IO outside guarded().
 * Rotating refresh tokens must never be recovered from an older backup. */
internal class AuthSessionStore(private val file: File, private val replace: (File, String) -> Unit = { target, text -> DurableFile.replace(target, text) }) {
    private val lock = Any()
    private val signedOut = File(file.parentFile, file.name + ".signed-out")
    private var blocked = false
    private var loggingIn = false
    private var epoch by mutableStateOf(0L)
    val generation: Long get() = synchronized(lock) { epoch }
    fun begin(action: () -> Unit = {}): Long = synchronized(lock) { epoch++; loggingIn = true; action(); epoch }
    fun <T> guarded(expected: Long, action: () -> T): T = synchronized(lock) {
        if (epoch != expected) throw StaleAuthSession()
        action()
    }
    fun read(expected: Long): JSONObject = guarded(expected) {
        if (blocked || loggingIn || signedOut.exists() || !file.exists()) JSONObject() else JSONObject(file.readText())
    }
    fun save(expected: Long, response: JSONObject, fresh: Boolean = false) = guarded(expected) {
        val next = if (fresh) JSONObject() else read(expected)
        val access = response.getString("access_token")
        require(access.isNotBlank()) { "服务端未返回有效访问令牌" }
        val refresh = if (response.isNull("refresh_token")) next.optString("refresh") else response.getString("refresh_token")
        require(refresh.isNotBlank()) { "服务端未返回有效刷新令牌" }
        val ttl = response.optLong("expires_in", 3600)
        require(ttl > 0)
        next.put("access", access).put("refresh", refresh).put("exp", Math.addExact(System.currentTimeMillis(), Math.multiplyExact(ttl, 1000)))
        replace(file, next.toString())
        if (fresh) {
            check(!signedOut.exists() || signedOut.delete()) { "无法解除本机退出标记，登录尚未完成" }
            blocked = false
            loggingIn = false
        }
    }
    /** Invalidate first. A marker also blocks restart recovery if clearing an
     * existing credential file fails. Report failure if neither write succeeds. */
    fun signOut(onNotice: (String?) -> Unit = {}, action: () -> Unit): String? = synchronized(lock) {
        epoch++; blocked = true; loggingIn = false; action()
        val marker = runCatching { replace(signedOut, "signed-out") }.isSuccess
        val erased = runCatching { replace(file, "{}") }.isSuccess
        val notice = when {
            !marker && !erased -> "已退出当前会话，但本地登录记录无法清除；请检查存储权限，重启前清理登录记录"
            !erased -> "已退出并阻止自动恢复，但旧登录文件未能清理，请检查存储权限"
            else -> null
        }
        onNotice(notice)
        notice
    }
}
