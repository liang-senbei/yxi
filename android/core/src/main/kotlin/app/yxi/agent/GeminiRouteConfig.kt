package app.yxi.agent

import app.yxi.ssh.Shell
import app.yxi.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/**
 * **Gemini CLI 线路适配（最小独立模块）** —— 把一条「线路」（baseUrl / apiKey / model）落到
 * 那台机器的 `~/.gemini/.env` 与 `~/.gemini/settings.json`。**不改 Lines / UI 主文件**，接线由 root 负责。
 *
 * 语义参照 cc-switch（MIT）`src-tauri/src/gemini_config.rs` 的真实行为（本文件为原创实现，未复制其代码）：
 *  - `.env` 写 `GEMINI_API_KEY` / `GOOGLE_GEMINI_BASE_URL` / `GEMINI_MODEL` 三个键；
 *    **model 走 env，settings.json 顶层的 `model` 字段不写**（参照实现同样不写；官方确认前不动用户的手写值）。
 *  - `settings.json` 只动 `security.auth.selectedType = "gemini-api-key"`（这是让 key 真正生效的开关），
 *    其余字段一律保留。
 *
 * 安全红线：
 *  - `.env` 按行手术：注释、未知键、缩进/`export` 前缀、顺序全保留；**绝不整文件清空**。
 *    null = 不动、"" = 显式清空（Lines 的教训：省略 = 旧值残留 = 界面静默说谎，TROUBLESHOOTING #254）。
 *  - read/compare/replace：expected-content hash 防并发覆盖（协议同 [RemoteAtomicJson]，
 *    复用其 [RemoteAtomicJson.read] 与 hash）。
 *  - 文件 0600、临时文件 + `os.replace` 原子替换、**备份进 `~/.yxi/backups/`，绝不放原文件旁**
 *    （gitignore 匹配不上 `<file>.bak-*` 会把钥匙带进 git 历史，见 ConfigRemote.save 的教训）。
 *  - 回读（[status]）只给脱敏 fingerprint 与状态，**密钥原文不出服务器**。
 *  - 测试走临时目录 fake：不写用户真实配置、不发任何模型请求。
 */
object GeminiRouteConfig {
    const val KEY_API = "GEMINI_API_KEY"
    const val KEY_BASE = "GOOGLE_GEMINI_BASE_URL"
    const val KEY_MODEL = "GEMINI_MODEL"

    /** 让 `.env` 里的 key 生效的认证类型（cc-switch 写法；Google 官方 OAuth 是另一条路，不在本模块范围）。 */
    const val SELECTED_TYPE = "gemini-api-key"

    private const val BACKUP_DIR = "~/.yxi/backups"

    /** 一条待落的配置。[baseUrl] / [apiKey] / [model] 传 null = 保持原样；传 "" = 显式清空。 */
    data class Patch(val baseUrl: String? = null, val apiKey: String? = null, val model: String? = null)

    data class Status(
        val envExists: Boolean,
        val baseUrl: String?,
        val model: String?,
        /** 形如 `sha256:<前10位>#<长度>`；未设 = null。**绝不含密钥原文**。 */
        val apiKeyFingerprint: String?,
        val settingsAuthType: String?,
        /** 用户手写在 settings.json 顶层的 model（只读展示；本模块从不写它）。 */
        val settingsModel: String?,
        /** settings.json 读不了/不是严格 JSON 时给原因，其余 settings 字段为 null。 */
        val settingsError: String?,
    )

    // ---------- 纯函数层（本地可单测，不碰 SSH） ----------

    private val ENV_LINE = Regex("""^(\s*)(export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$""")

    /** 非法值（换行/引号/控制符）直接拒绝，绝不静默清洗。 */
    private fun safeValue(key: String, value: String): String {
        require(value.none { it < ' ' || it == '\u007F' || it == '"' || it == '\'' }) { "$key 含换行/引号/控制符，未写入" }
        return value
    }

    /** 写入前校验；baseUrl 只收不带用户信息的 http(s) URL（空串 = 显式清空，放行）。 */
    fun validate(patch: Patch) {
        patch.baseUrl?.let {
            if (it.isNotEmpty()) {
                val uri = runCatching { URI(it) }
                    .getOrElse { throw IllegalArgumentException("baseUrl 不是合法 URI：${it.message?.take(60)}") }
                require(uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank()) {
                    "baseUrl 需为 http(s) URL：${it.take(80)}"
                }
                require(uri.userInfo == null) { "baseUrl 不应带用户信息（user:pass@），拒绝写入" }
            }
        }
        patch.apiKey?.let { safeValue(KEY_API, it) }
        patch.model?.let { safeValue(KEY_MODEL, it) }
    }

    /**
     * `.env` 按行手术：命中的键**原位**换值（保留缩进与 `export` 前缀）；注释、未知键、空行、顺序一字不动；
     * 缺的键按 API/BASE/MODEL 顺序补到文件尾。文本末尾没有换行时会补上（.env 无歧义，可接受）。
     */
    fun patchEnv(text: String?, patch: Patch): String {
        validate(patch)
        val updates = linkedMapOf<String, String>()
        patch.apiKey?.let { updates[KEY_API] = it }
        patch.baseUrl?.let { updates[KEY_BASE] = it }
        patch.model?.let { updates[KEY_MODEL] = it }
        if (updates.isEmpty()) return text.orEmpty()
        val out = StringBuilder()
        val hit = mutableSetOf<String>()
        var first = true
        // lineSequence() 对 "\n" 结尾的文本会多吐一个空行：行间补 '\n'（join 语义）才能原样保留换行
        for (line in text.orEmpty().lineSequence()) {
            if (!first) out.append('\n')
            first = false
            val m = ENV_LINE.matchEntire(line)
            val key = m?.groups?.get(3)?.value
            if (key != null && updates.containsKey(key)) {
                hit += key
                out.append(m.groups[1]!!.value).append(m.groups[2]?.value.orEmpty())
                    .append(key).append('=').append(updates[key]!!)
            } else out.append(line)
        }
        for ((k, v) in updates) if (k !in hit) {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
            out.append(k).append('=').append(v).append('\n')
        }
        return out.toString()
    }

    /**
     * settings.json 只动 `security.auth.selectedType`，其余字段全保留。
     * 注释/非严格 JSON、security/auth 被用户写成了非对象 —— 一律报错不重建（泛 JSON 编辑器才做「清了重写」）。
     */
    fun patchSettings(text: String?): String {
        val root = if (text.isNullOrBlank()) JSONObject()
        else runCatching { JSONObject(text) }
            .getOrElse { throw IllegalArgumentException("settings.json 不是严格 JSON（${it.message?.take(60)}），未改动") }
        val sec = root.opt("security")
        require(sec == null || sec is JSONObject) { "settings.json 的 security 不是对象，未改动" }
        val security = sec ?: JSONObject().also { root.put("security", it) }
        val au = security.opt("auth")
        require(au == null || au is JSONObject) { "settings.json 的 security.auth 不是对象，未改动" }
        val auth = au ?: JSONObject().also { security.put("auth", it) }
        auth.put("selectedType", SELECTED_TYPE)
        return root.toString(2) + "\n"
    }

    /** 脱敏 fingerprint：SHA-256 前 10 位 + 长度。稳定、可对账、不含原文。 */
    fun fingerprint(secret: String?): String? {
        if (secret.isNullOrBlank()) return null
        return "sha256:" + RemoteAtomicJson.hash(secret.toByteArray(Charsets.UTF_8)).take(10) + "#" + secret.length
    }

    /** 反读 .env 成键值表：后写覆盖先写（dotenv 语义）；成对引号剥掉。值只进 [Status]，密钥只出 fingerprint。 */
    fun parseEnv(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (line in text.lineSequence()) {
            val m = ENV_LINE.matchEntire(line) ?: continue
            var v = m.groups[4]!!.value.trim()
            if (v.length >= 2 && ((v.first() == '"' && v.last() == '"') || (v.first() == '\'' && v.last() == '\''))) {
                v = v.substring(1, v.length - 1)
            }
            out[m.groups[3]!!.value] = v
        }
        return out
    }

    // ---------- 远端层（协议同 RemoteAtomicJson：SFTP 传内容，argv 只传 hash） ----------

    suspend fun status(ssh: SshSession, geminiDir: String): Status {
        val env = runCatching { RemoteAtomicJson.read(ssh, "$geminiDir/.env") }.getOrNull()
        val settings = runCatching { RemoteAtomicJson.read(ssh, "$geminiDir/settings.json") }.getOrNull()
        val kv = parseEnv(env?.text)
        var authType: String? = null
        var sModel: String? = null
        var sErr: String? = if (settings == null) "settings.json 读取失败" else null
        settings?.text?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { JSONObject(raw) }
                .onSuccess { root ->
                    authType = root.optJSONObject("security")?.optJSONObject("auth")
                        ?.optString("selectedType")?.ifBlank { null }
                    val m = root.opt("model")
                    sModel = if (m == null || m == JSONObject.NULL) null else m.toString()
                }
                .onFailure { sErr = "settings.json 不是严格 JSON：${it.message?.take(60)}" }
        }
        return Status(
            envExists = env?.text != null,
            baseUrl = kv[KEY_BASE]?.ifBlank { null },
            model = kv[KEY_MODEL]?.ifBlank { null },
            apiKeyFingerprint = fingerprint(kv[KEY_API]),
            settingsAuthType = authType,
            settingsModel = sModel,
            settingsError = sErr,
        )
    }

    /**
     * 落一条 Gemini 线路。read → 本地手术 → compare-and-swap：`.env` 先行，
     * settings.json 失败则尽力把 `.env` 回滚到原样（expected = 自己刚写的 hash；期间被第三方改过就不再动，如实上报）。
     * @return null = 成功；否则中文错误说明（哪些文件动了/没动，绝不静默）。
     */
    suspend fun apply(ssh: SshSession, geminiDir: String, patch: Patch): String? {
        validate(patch)
        if (patch.baseUrl == null && patch.apiKey == null && patch.model == null) return "Gemini 线路没有任何要改的字段"
        val envPath = "$geminiDir/.env"
        val setPath = "$geminiDir/settings.json"
        val envOld = RemoteAtomicJson.read(ssh, envPath)
        val setOld = RemoteAtomicJson.read(ssh, setPath)
        val envNew = patchEnv(envOld.text, patch)
        val setNew = try { patchSettings(setOld.text) } catch (e: IllegalArgumentException) { return e.message }
        commit(ssh, envPath, envNew, envOld.revision, jsonMode = false)?.let {
            return "Gemini .env 未写入：$it（settings.json 未动）"
        }
        commit(ssh, setPath, setNew, setOld.revision, jsonMode = true)?.let { err2 ->
            val rolled = if (envOld.text == null) {
                ".env 原不存在，保留新写内容"
            } else if (commit(ssh, envPath, envOld.text, RemoteAtomicJson.hash(envNew.toByteArray()), jsonMode = false) == null) {
                ".env 已回滚"
            } else {
                ".env 回滚失败，保留新值，请人工核对"
            }
            return "Gemini settings.json 未写入：$err2；$rolled（请刷新后重试）"
        }
        return null
    }

    /** 服务器侧提交：SFTP 传临时文件 → python3 锁内 hash 比对 → 0600 + 备份 + 原子替换。null = 成功。 */
    private suspend fun commit(ssh: SshSession, path: String, text: String, expected: String, jsonMode: Boolean): String? {
        val parent = path.substringBeforeLast('/')
        val temp = "$parent/.yxi-upload-${UUID.randomUUID()}"
        try {
            check(ssh.exec("(umask 077; mkdir -p " + Shell.q(parent) + "; set -C; : > " + Shell.q(temp) + ") && printf YXI_READY").trim() == "YXI_READY") {
                "无法创建受保护的临时配置"
            }
            val bytes = text.toByteArray()
            require(bytes.size <= 2 * 1024 * 1024) { "配置过大，未覆盖" }
            val sftp = ssh.openSftp()
            try { sftp.write(temp, bytes) } finally { sftp.close() }
            val command = "python3 -c " + Shell.q(SCRIPT) + " " +
                listOf(path, temp, expected, RemoteAtomicJson.hash(bytes), BACKUP_DIR, if (jsonMode) "json" else "env")
                    .joinToString(" ") { Shell.q(it) }
            val raw = ssh.exec(command)
            val response = runCatching { JSONObject(raw.trim()) }.getOrElse { return "未收到配置提交确认，请刷新核对；不会自动重试" }
            return when (response.optString("status")) {
                "saved" -> null
                "conflict" -> "配置已被其他人修改，本次未覆盖，请刷新后重试"
                else -> "配置未提交：" + response.optString("error", "未知错误")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "配置未提交：${e.message}"
        } finally {
            withContext(NonCancellable) { runCatching { ssh.exec("rm -f -- " + Shell.q(temp)) } }
        }
    }

    /**
     * 提交脚本（远端 python3）。参数：path temp expected wanted backups mode。
     * 与 [RemoteAtomicJson] 同一协议，两处不同：备份目录走参数（本地测试可用临时目录）、
     * `mode=env` 不做 JSON 校验（`.env` 不是 JSON）。备份前缀 `gemini-route-`。
     */
    internal val SCRIPT = """
import os,sys,json,hashlib,fcntl,tempfile
p,t,expected,wanted,backups,mode=sys.argv[1:]
def revision():
    if os.path.islink(p): raise ValueError('Configuration symlinks are not writable here')
    if not os.path.exists(p): return 'missing',None
    with open(p,'rb') as f: data=f.read(2097153)
    if len(data)>2097152: raise ValueError('Configuration too large')
    if mode=='json': json.loads(data)
    return hashlib.sha256(data).hexdigest(),data
try:
    fd=os.open(p+'.yxi-lock',os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        current,old=revision()
        if current!=expected:
            print(json.dumps({'status':'conflict'})); sys.exit(0)
        with open(t,'rb') as f: new=f.read(2097153)
        if len(new)>2097152 or hashlib.sha256(new).hexdigest()!=wanted: raise ValueError('Upload verification failed')
        if mode=='json': json.loads(new)
        os.chmod(t,0o600)
        if old is not None:
            d=os.path.expanduser(backups)
            os.makedirs(d,mode=0o700,exist_ok=True); os.chmod(d,0o700)
            bfd,bname=tempfile.mkstemp(prefix='gemini-route-',dir=d)
            with os.fdopen(bfd,'wb') as f: f.write(old); f.flush(); os.fsync(f.fileno())
            os.chmod(bname,0o600)
        with open(t,'rb') as f: os.fsync(f.fileno())
        if revision()[0]!=expected:
            print(json.dumps({'status':'conflict'})); sys.exit(0)
        os.replace(t,p)
        print(json.dumps({'status':'saved'}))
except Exception as e: print(json.dumps({'status':'error','error':str(e)}))
""".trimIndent()
}
