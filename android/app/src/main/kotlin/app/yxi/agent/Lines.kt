package app.yxi.agent

import app.yxi.ssh.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * **线路** —— 那台机器上的 Claude Code 走哪个端点、用哪把钥匙。
 * 官方订阅 / 官方 API / 第三方中转，都是一条「线路」。
 *
 * 机制同 CC Switch：写 `~/.claude/settings.json` 的 `env`
 * （`ANTHROPIC_BASE_URL` / `ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_API_KEY`）。
 *
 * ⚠️⚠️ **换线路不用重启 Claude Code** —— 实测过（2026-09-04，隔离探针会话）：
 * 改了 env，运行中的会话**下一次请求**就打到新端点。但有一条反直觉的：
 *
 *   **把 key 从文件里删掉是不生效的**（旧值留在进程环境里），**必须显式写成空字符串**。
 *
 * 所以 [apply] **每次把三个 key 全写一遍，不设的写 `""`，一个都不省**。
 * 省略任一个 = 上一条线路的值继续生效，而界面显示已经切过去了 —— 静默说谎，
 * 是这个项目最贵的那类错（见 TROUBLESHOOTING #254）。
 *
 * ⚠️ **「当前用哪条」不另存一份**，就地从 `settings.json` 反读（[current] + [matches]）。
 * 另存一个 activeId 就有了第二个真相源，手动改过文件、或者电脑上的 CC Switch 也切过之后，
 * 界面会理直气壮地显示错的那条。
 *
 * ⚠️ **Codex 不吃这一套** —— 它读的是 `~/.codex/auth.json`，不是 env，
 * 换了要重开（CC Switch 文档明确写的）。这里只管 Claude Code。
 */
object Lines {

    /** 一条线路。[token] / [apiKey] 是原文，只在编辑和写入时经手，界面上一律 [mask]。 */
    data class Line(
        val id: String,
        val name: String,
        val baseUrl: String = "",
        val token: String = "",
        val apiKey: String = "",
    )

    /** `settings.json` 里此刻真正写着的那三个值。 */
    data class Env(val baseUrl: String, val token: String, val apiKey: String) {
        /** 三个都空 = 没被任何线路接管，走 Claude Code 自己的登录（订阅） */
        val isDefault get() = baseUrl.isBlank() && token.isBlank() && apiKey.isBlank()
    }

    private const val BASE = "ANTHROPIC_BASE_URL"
    private const val TOKEN = "ANTHROPIC_AUTH_TOKEN"
    private const val KEY = "ANTHROPIC_API_KEY"

    /**
     * 家目录的绝对路径。
     * ⚠️ **必须解出来**：写入走 SFTP，而 **SFTP 不认 `~`** —— 会在服务器上建出一个
     * 名字真叫 `~` 的目录，写进去谁也找不到。
     */
    private suspend fun home(ssh: SshSession?): String? {
        val h = ssh?.exec("printf %s \"\$HOME\"")?.trim().orEmpty()
        return h.ifBlank { null }
    }

    private fun settingsPath(home: String) = "$home/.claude/settings.json"
    private fun listPath(home: String) = "$home/.yxi/lines.json"

    /**
     * 改哪个文件。[cwd] = null 是**整机**（`~/.claude/settings.json`，那台机器上所有 agent 一起换）；
     * 传目录就是**只管那个项目**（`<目录>/.claude/settings.local.json`）。
     *
     * 项目级优先于用户级，所以「整机默认走 A、某个项目走 B」是天然成立的。
     *
     * ⚠️ 用 `settings.local.json` 而不是 `settings.json`：后者是**项目里大家共用**的那份，
     * 会进 git。钥匙绝不能写进会被提交的文件。`.local.json` 按约定是本机私有的。
     *
     * ⚠️ 项目级的 env 要那个目录**被信任过**才生效。用户自己天天在那个目录跑 agent，
     * 早就信任了；但如果换了个从没跑过的目录，可能不吃 —— 所以换完那次探测很重要。
     */
    private suspend fun targetPath(ssh: SshSession?, cwd: String?): String? =
        if (cwd == null) home(ssh)?.let { settingsPath(it) }
        else cwd.trimEnd('/').ifBlank { null }?.let { "$it/.claude/settings.local.json" }

    // ── 线路清单 ────────────────────────────────────────────────────────────

    /** @return null = 拿不到（没连上）。空表 = 真的一条都没建过 —— 两件事，界面要分开说。 */
    suspend fun list(ssh: SshSession?): List<Line>? = withContext(Dispatchers.IO) {
        val h = home(ssh) ?: return@withContext null
        val raw = ConfigRemote.readFile(ssh, listPath(h)) ?: return@withContext emptyList()
        runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let {
                    Line(
                        id = it.optString("id"), name = it.optString("name"),
                        baseUrl = it.optString("baseUrl"), token = it.optString("token"),
                        apiKey = it.optString("apiKey"),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    /** @return 出错原因；null = 成功。 */
    suspend fun saveList(ssh: SshSession?, lines: List<Line>): String? = withContext(Dispatchers.IO) {
        val s = ssh ?: return@withContext "没连上"
        val h = home(s) ?: return@withContext "取不到家目录"
        val a = JSONArray()
        lines.forEach {
            a.put(
                JSONObject().put("id", it.id).put("name", it.name)
                    .put("baseUrl", it.baseUrl).put("token", it.token).put("apiKey", it.apiKey),
            )
        }
        // ⚠️ 里面装的是钥匙：目录 700、文件 600。**先建目录再写**，SFTP 不会替你建。
        s.exec("mkdir -p \"\$HOME/.yxi\" && chmod 700 \"\$HOME/.yxi\"")
        runCatching {
            val sftp = s.openSftp()
            try { sftp.write(listPath(h), a.toString(2).toByteArray()) } finally { runCatching { sftp.close() } }
        }.onFailure { return@withContext "写失败：${it.message?.take(60)}" }
        s.exec("chmod 600 \"\$HOME/.yxi/lines.json\"")
        null
    }

    // ── 当前走哪条 ──────────────────────────────────────────────────────────

    /** 这一刻真正生效的那套值，以及它是谁给的。 */
    data class Active(val env: Env, val fromProject: Boolean)

    /** 读某个文件里的那三个 key。文件不存在 = 没设过，返回 null（跟「读坏了」区分）。 */
    private suspend fun envAt(ssh: SshSession?, path: String): Env? {
        val raw = ConfigRemote.readFile(ssh, path) ?: return null
        return runCatching {
            val e = JSONObject(raw).optJSONObject("env") ?: return null
            if (!e.has(BASE) && !e.has(TOKEN) && !e.has(KEY)) return null
            Env(e.optString(BASE), e.optString(TOKEN), e.optString(KEY))
        }.getOrNull()
    }

    /**
     * 就地从配置文件反读**此刻真正生效**的那套。[cwd] 传目录就连项目级一起算。
     *
     * ⚠️ **不另存一份「当前用哪条」。** 存了就有第二个真相源 —— 用户在电脑上用 CC Switch
     * 也切过、或者手动改过文件之后，界面会理直气壮地显示错的那条。
     *
     * @return null = 拿不到（没连上）。
     */
    suspend fun active(ssh: SshSession?, cwd: String? = null): Active? = withContext(Dispatchers.IO) {
        val h = home(ssh) ?: return@withContext null
        // 项目级压用户级。我们写的时候三个 key 一起写，所以覆盖是整体的，不会半边。
        if (cwd != null) targetPath(ssh, cwd)?.let { p ->
            envAt(ssh, p)?.let { return@withContext Active(it, true) }
        }
        // 文件不存在或没设过 env 都是**正常**的（就是默认线路），不是「拿不到」
        Active(envAt(ssh, settingsPath(h)) ?: Env("", "", ""), false)
    }

    /** 整机那份。[LinesPanel] 用它显示「整机现在走哪条」。 */
    suspend fun current(ssh: SshSession?): Env? = active(ssh, null)?.env

    /**
     * 这条线路是不是当前在用的那条。
     * ⚠️ **三个值全比**，不是只比 baseUrl —— 同一个中转常常挂好几家，端点一样钥匙不一样。
     */
    fun matches(line: Line, env: Env): Boolean =
        line.baseUrl == env.baseUrl && line.token == env.token && line.apiKey == env.apiKey

    // ── 换线 ────────────────────────────────────────────────────────────────

    /**
     * 换到这条线路。[line] 传 null = 回默认（三个 key 全写空串，**不是删掉**）。
     *
     * ⚠️ 写法上只碰 `env` 里那三个 key，`settings.json` 的其余部分原样保留；
     * 落盘走 [ConfigRemote.save]，它**先备份、json 先校验**，坏了绝不写。
     *
     * @return 出错原因；null = 成功。
     */
    suspend fun apply(ssh: SshSession?, line: Line?, cwd: String? = null): String? = withContext(Dispatchers.IO) {
        val s = ssh ?: return@withContext "没连上"
        val path = targetPath(s, cwd) ?: return@withContext "取不到要改的文件路径"
        // 项目级那个 .claude 目录可能还不存在，SFTP 不会替你建
        if (cwd != null) s.exec("mkdir -p " + shq(cwd.trimEnd('/') + "/.claude"))
        val raw = ConfigRemote.readFile(s, path) ?: "{}"
        val root = runCatching { JSONObject(raw) }.getOrElse {
            return@withContext "settings.json 现在就是坏的（${it.message?.take(40)}），没敢动"
        }
        val env = root.optJSONObject("env") ?: JSONObject()
        // ⚠️⚠️ 三个全写，不设的写空串。**别用 remove()** —— 删掉不生效，旧值还在进程里。
        env.put(BASE, line?.baseUrl.orEmpty())
        env.put(TOKEN, line?.token.orEmpty())
        env.put(KEY, line?.apiKey.orEmpty())
        root.put("env", env)
        ConfigRemote.save(s, path, root.toString(2))
    }

    /**
     * 撤掉某个项目的单独设置，让它**回去跟随整机**。
     *
     * ⚠️⚠️ **这一个方向必须重开那个会话才生效。** 因为「跟随整机」只能靠**把 key 从文件里删掉**
     * 来表达（写空串是「强制走默认线路」，不是「跟随整机」，两者含义不同），
     * 而**删 key 是不热生效的** —— 旧值留在进程环境里（TROUBLESHOOTING #254）。
     * 调用方必须把这句告诉用户，不能假装已经切回去了。
     *
     * @return 出错原因；null = 文件已改好（但仍需重开会话）。
     */
    suspend fun clearProject(ssh: SshSession?, cwd: String): String? = withContext(Dispatchers.IO) {
        val s = ssh ?: return@withContext "没连上"
        val path = targetPath(s, cwd) ?: return@withContext "路径不对"
        val raw = ConfigRemote.readFile(s, path) ?: return@withContext null   // 本来就没有，等于已经跟随整机
        val root = runCatching { JSONObject(raw) }.getOrElse {
            return@withContext "这个项目的 settings.local.json 是坏的，没敢动"
        }
        root.optJSONObject("env")?.let { e ->
            e.remove(BASE); e.remove(TOKEN); e.remove(KEY)
            if (e.length() == 0) root.remove("env") else root.put("env", e)
        }
        ConfigRemote.save(s, path, root.toString(2))
    }

    /**
     * 换完当场从**服务器上**探一下这个端点通不通。
     *
     * ⚠️ 只验「连得上」，**不验钥匙对不对** —— 401/403 同样算通（说明端点活着、在要认证）。
     * 要验钥匙就得替用户发一次真请求，那要花钱，而且各家鉴权头还不一样。
     *
     * @return 给人看的一句话。
     */
    suspend fun probe(ssh: SshSession?, baseUrl: String): String = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext "默认线路（走 Claude Code 自己的登录）"
        val s = ssh ?: return@withContext "没连上，没法探"
        val u = "'" + baseUrl.trimEnd('/').replace("'", "'\\''") + "'"
        val code = s.exec("curl -sS -o /dev/null -m 8 -w '%{http_code}' $u 2>/dev/null || echo 000").trim()
        when {
            code == "000" || code.isBlank() -> "连不上这个地址"
            else -> "通（HTTP $code）"
        }
    }

    // ── 杂 ──────────────────────────────────────────────────────────────────

    /** 界面上显示钥匙一律走这里。全空串给空串，别显示成一串星号让人以为设过。 */
    fun mask(secret: String): String = when {
        secret.isBlank() -> ""
        secret.length <= 12 -> "•".repeat(secret.length)
        else -> secret.take(6) + "…" + secret.takeLast(4)
    }

    fun newId(): String = "line-" + java.util.UUID.randomUUID().toString().take(8)

    private fun shq(p: String) = "'" + p.replace("'", "'\\''") + "'"
}
