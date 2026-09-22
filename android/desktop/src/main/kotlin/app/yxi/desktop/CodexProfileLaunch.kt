package app.yxi.desktop

import app.yxi.agent.Lines
import app.yxi.agent.RemoteAtomicJson
import app.yxi.ssh.Shell
import app.yxi.ssh.SshSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** A custom provider belongs to this app-server process, never ~/.codex/config.toml. */
internal object CodexProfileLaunch {
    data class Prepared(val command: String, val path: String, val overrides: CodexResumeOverrides)

    internal fun configuration(line: Lines.Line, id: String): Pair<JSONObject, CodexResumeOverrides> {
        require(line.agent == Lines.CODEX) { "请选择 Codex 的已保存配置" }
        require(id.matches(Regex("[a-f0-9]{32}")))
        val url = URI(line.baseUrl.trim())
        require(url.scheme in listOf("https", "http") && !url.host.isNullOrBlank() && url.userInfo == null && url.fragment == null && url.query == null) {
            "请求地址必须为 HTTP/HTTPS，不能包含账号、查询参数或片段；密钥请填写在 API Key 中"
        }
        require(line.apiKey.isNotBlank() && line.apiKey.length <= 32768 && line.apiKey.none { it < ' ' || it == '\u007f' }) { "API Key 格式无效" }
        val model = line.extra.optString("model").takeIf { it.isNotBlank() }
        require(model == null || app.yxi.agent.Model.selectableId(model)) { "模型 ID 格式无效" }
        val effort = line.extra.optString("model_reasoning_effort").takeIf { it.isNotBlank() }
        require(effort == null || effort.matches(Regex("[a-z]{1,20}"))) { "思考强度格式无效" }
        require(line.name.length <= 200 && line.name.none { it < ' ' }) { "配置名称格式无效" }
        val provider = "yxi_agent_$id"
        val definition = "{ name = ${JSONObject.quote(line.name)}, base_url = ${JSONObject.quote(url.toString())}, " +
            "env_key = \"YXI_AGENT_API_KEY\", wire_api = \"responses\", requires_openai_auth = false }"
        val args = mutableListOf("-c", "model_providers.$provider=$definition", "-c", "model_provider=${JSONObject.quote(provider)}")
        model?.let { args += listOf("-c", "model=${JSONObject.quote(it)}") }
        effort?.let { args += listOf("-c", "model_reasoning_effort=${JSONObject.quote(it)}") }
        args += "app-server"
        require(args.none { it.contains(line.apiKey) }) { "请求地址、模型或配置名称中不能包含 API Key" }
        val payload = JSONObject().put("args", JSONArray(args)).put("key", line.apiKey)
        return payload to CodexResumeOverrides(provider, model, effort)
    }

    suspend fun prepare(ssh: SshSession, line: Lines.Line, scopeId: String? = null): Prepared {
        val id = UUID.randomUUID().toString().replace("-", "")
        val (payload, overrides) = configuration(line, scopeId ?: id)
        val home = ssh.exec("printf %s \"\$HOME\"").trim()
        check(home.startsWith('/') && home.none { it < ' ' }) { "无法确认服务器家目录" }
        val path = "$home/.yxi/launch-contexts/codex-$id.json"
        val text = payload.toString()
        require(text.toByteArray().size <= 65536) { "独立配置过大，请缩短配置内容" }
        RemoteAtomicJson.write(ssh, path, text, "missing")?.let { error(it) }
        return Prepared("python3 -c ${Shell.q(worker)} ${Shell.q(path)} ${Shell.q(RemoteAtomicJson.hash(text.toByteArray()))}", path, overrides)
    }

    suspend fun cleanup(ssh: SshSession, prepared: Prepared) = withContext(NonCancellable) {
        runCatching { ssh.exec("rm -f -- ${Shell.q(prepared.path)}") }; Unit
    }

    private val worker = """
import hashlib,json,os,pathlib,shutil,stat,sys
path,wanted=sys.argv[1:]
fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
with os.fdopen(fd,'rb') as source:
 info=os.fstat(source.fileno())
 if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.getuid() or info.st_mode & 0o077: raise ValueError('Invalid private launch file')
 raw=source.read(65537)
if len(raw)>65536 or hashlib.sha256(raw).hexdigest()!=wanted: raise ValueError('Launch file changed')
data=json.loads(raw)
binary=shutil.which('codex') or str(pathlib.Path.home()/'.local/bin/codex')
if not os.path.isfile(binary) or not os.access(binary,os.X_OK): raise ValueError('Codex is not installed')
environment=os.environ.copy();environment['YXI_AGENT_API_KEY']=data['key']
os.unlink(path)
os.execve(binary,[binary,*data['args']],environment)
""".trimIndent()
}
