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

    /** 就地从 `settings.json` 反读。@return null = 拿不到（没连上 / 读不到文件）。 */
    suspend fun current(ssh: SshSession?): Env? = withContext(Dispatchers.IO) {
        val h = home(ssh) ?: return@withContext null
        // 文件不存在是**正常**的（没设过任何 env），当成默认线路，不是「拿不到」
        val raw = ConfigRemote.readFile(ssh, settingsPath(h)) ?: return@withContext Env("", "", "")
        runCatching {
            val env = JSONObject(raw).optJSONObject("env")
            Env(env?.optString(BASE).orEmpty(), env?.optString(TOKEN).orEmpty(), env?.optString(KEY).orEmpty())
        }.getOrNull()
    }

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
    suspend fun apply(ssh: SshSession?, line: Line?): String? = withContext(Dispatchers.IO) {
        val s = ssh ?: return@withContext "没连上"
        val h = home(s) ?: return@withContext "取不到家目录"
        val path = settingsPath(h)
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
}
