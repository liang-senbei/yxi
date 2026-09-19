package app.yxi.desktop

import app.yxi.ssh.Shell
import org.json.JSONObject

internal object ProviderIdentity {
    suspend fun claude(conn: Conn): String {
        val script = """
import json, subprocess
result = subprocess.run(['claude', 'auth', 'status', '--json'], capture_output=True, text=True, timeout=20)
if result.returncode not in (0, 1): raise RuntimeError('Authentication query unsupported')
data = json.loads(result.stdout)
if type(data.get('loggedIn')) is not bool: raise RuntimeError('Unknown authentication response')
print('__YXI_AUTH__:' + json.dumps({'loggedIn': data['loggedIn']}))
""".trimIndent()
        val raw = conn.ssh.exec("python3 -c " + Shell.q(script))
        val line = raw.lineSequence().lastOrNull { it.startsWith("__YXI_AUTH__:") } ?: error("未读到认证状态")
        val loggedIn = JSONObject(line.removePrefix("__YXI_AUTH__:")).getBoolean("loggedIn")
        return if (loggedIn) "Claude 报告当前服务器用户环境已配置认证" else "Claude 报告当前服务器用户环境未登录"
    }
}
