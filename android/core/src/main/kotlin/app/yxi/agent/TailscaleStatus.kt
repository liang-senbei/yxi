package app.yxi.agent

import app.yxi.ssh.SshSession
import org.json.JSONObject

object TailscaleStatus {

    data class Device(
        val hostName: String,
        val ip: String,
        val os: String,
        val online: Boolean,
        val isSelf: Boolean,
        val lastSeen: String?,
    )

    /**
     * ⚠️ **macOS 上 `tailscale` 不在非交互 shell 的 PATH 里**（App Store 版根本没装 `/usr/local/bin`
     * 那个软链，CLI 只在 app 包里）。只敲 `tailscale` 的话，明明装着也会被报成「没装 Tailscale」——
     * 实测 Mac mini 当主机时就是这样。所以先补 PATH，再退回去试 app 包里的那个。
     * （同一个坑 [Slave.probeCommand] 也踩过。）
     */
    internal const val CMD =
        "export PATH=\"\$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:\$PATH\"; " +
            "for t in tailscale /Applications/Tailscale.app/Contents/MacOS/Tailscale; do " +
            "\"\$t\" status --json 2>/dev/null && break; done"

    suspend fun fetch(ssh: SshSession): List<Device>? {
        val raw = ssh.exec(CMD)
        if (raw.isBlank() || !raw.trimStart().startsWith("{")) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null

        val out = mutableListOf<Device>()

        val self = json.optJSONObject("Self")
        if (self != null) {
            out += Device(
                hostName = self.optString("HostName", "?"),
                ip = self.optJSONArray("TailscaleIPs")?.optString(0).orEmpty(),
                os = self.optString("OS", "?"),
                online = true,
                isSelf = true,
                lastSeen = null,
            )
        }

        val peers = json.optJSONObject("Peer")
        if (peers != null) {
            for (k in peers.keys()) {
                val p = peers.optJSONObject(k) ?: continue
                out += Device(
                    hostName = p.optString("HostName", "?"),
                    ip = p.optJSONArray("TailscaleIPs")?.optString(0).orEmpty(),
                    os = p.optString("OS", "?"),
                    online = p.optBoolean("Online", false),
                    isSelf = false,
                    lastSeen = p.optString("LastSeen").ifEmpty { null },
                )
            }
        }

        return out.sortedWith(compareByDescending<Device> { it.isSelf }.thenByDescending { it.online }.thenBy { it.hostName })
    }
}
