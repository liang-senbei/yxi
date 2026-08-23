package app.yxi.ssh

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一台主机的持久化记录。
 *
 * ⚠️ [sealedPassword] 是 [Vault] 加密后的密文，**不是明文**。
 * 明文密码只在「用户刚输入」和「正要认证」这两个瞬间存在于内存。
 */
data class Host(
    val id: String,
    val alias: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    /** 用 App 的密钥认证（[KeyManager]）。装过公钥的主机走这条。 */
    val useKey: Boolean = true,
    /** 记住的密码（密文）。新开的云主机常常一开始只有密码。 */
    val sealedPassword: String? = null,
    /** 最后一次成功连接时看到的主机指纹，用于 [KnownHosts] 校验。 */
    val hostKey: String? = null,
    /**
     * 让手机为这台机器主动响（前台服务常驻一条通道 tail 事件流）。
     * ⚠️ 需要这台机器上装了 `yxi-hook`；没装就一直静悄悄，不会报错。
     */
    val watch: Boolean = false,
) {
    val display get() = "$username@$hostname" + if (port != 22) ":$port" else ""
}

/**
 * 主机列表的存储。
 *
 * **不是预置列表** —— 必须能随时加「以后才有的」服务器（PRD §2.4）：
 * 任意 IP、任意端口、任意用户名、密码或密钥。
 */
class HostStore(ctx: Context) {

    private val file = File(ctx.filesDir, "hosts.json")
    private val _hosts = MutableStateFlow(read())
    val hosts: StateFlow<List<Host>> = _hosts

    fun upsert(h: Host) {
        _hosts.value = _hosts.value.filterNot { it.id == h.id } + h
        write()
    }

    fun remove(id: String) {
        _hosts.value = _hosts.value.filterNot { it.id == id }
        write()
    }

    fun get(id: String): Host? = _hosts.value.firstOrNull { it.id == id }

    /**
     * 从磁盘重新读一遍。
     *
     * ⚠️ **前台服务和界面各有一个 [HostStore] 实例**（一个在 Service 里、一个在 Activity 里），
     * 各自在构造时读一次文件就再也不读了。界面上改了开关，服务那份是**旧的** ——
     * 表现是「把铃铛关掉，那台机器照样在被盯着」，而且不报任何错。
     * 所以服务每次 `onStartCommand` 都要先 reload。
     */
    fun reload() { _hosts.value = read() }

    /** 认证方式：装过公钥就走密钥，否则用记住的密码。两条都没有 → null，UI 该提示补认证信息。 */
    fun authFor(h: Host, keys: KeyManager): HostConfig.Auth? = when {
        h.useKey -> HostConfig.Auth.PrivateKey(keys.privateKeyPem())
        h.sealedPassword != null -> Vault.open(h.sealedPassword)?.let { HostConfig.Auth.Password(it) }
        else -> null
    }

    fun configFor(h: Host, keys: KeyManager): HostConfig? =
        authFor(h, keys)?.let { HostConfig(h.alias, h.hostname, h.port, h.username, it) }

    private fun read(): List<Host> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Host(
                id = o.getString("id"),
                alias = o.getString("alias"),
                hostname = o.getString("hostname"),
                port = o.optInt("port", 22),
                username = o.getString("username"),
                useKey = o.optBoolean("useKey", true),
                sealedPassword = o.optString("sealedPassword").ifEmpty { null },
                hostKey = o.optString("hostKey").ifEmpty { null },
                watch = o.optBoolean("watch", false),
            )
        }
    }.getOrDefault(emptyList())

    private fun write() {
        val arr = JSONArray()
        _hosts.value.forEach { h ->
            arr.put(
                JSONObject().apply {
                    put("id", h.id); put("alias", h.alias); put("hostname", h.hostname)
                    put("port", h.port); put("username", h.username); put("useKey", h.useKey)
                    put("watch", h.watch)
                    h.sealedPassword?.let { put("sealedPassword", it) }
                    h.hostKey?.let { put("hostKey", it) }
                }
            )
        }
        file.writeText(arr.toString())
    }
}
