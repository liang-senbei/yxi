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
        /** 这条线是给谁的：`claude` 或 `codex`。**两家的机制完全不同**，见 [applyCodex]。 */
        val agent: String = CLAUDE,
    ) {
        val isCodex get() = agent == CODEX
    }

    const val CLAUDE = "claude"
    const val CODEX = "codex"

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
                        // 老清单没有这个字段，默认当 Claude —— 加字段不能让已有的线路变身
                        agent = it.optString("agent").ifBlank { CLAUDE },
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
                    .put("baseUrl", it.baseUrl).put("token", it.token).put("apiKey", it.apiKey)
                    .put("agent", it.agent),
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


    // ── Codex ───────────────────────────────────────────────────────────────

    /**
     * Codex 的线路。**跟 Claude Code 完全不是一套东西**，下面每一条都是实测出来的
     * （2026-09-05，本机 codex-cli 0.153.0，隔离 `CODEX_HOME` 探针）：
     *
     * 1. **认 `~/.codex/config.toml` 的 `model_provider` + `[model_providers.<id>].base_url`** ——
     *    实测把 base_url 指到 `127.0.0.1:1`，codex 报 `Reconnecting... waiting for network`，
     *    说明确实按它去连。
     * 2. ⚠️ **写 `~/.codex/auth.json` 的 `OPENAI_API_KEY` 喂不了自定义供应商** ——
     *    实测报 `Missing environment variable: OPENAI_API_KEY`。
     *    `env_key` 要的是**进程环境里真有那个变量**。
     *    （CC Switch 的文档只写了「写 auth.json」，那条**只对内置的 openai 供应商成立**。）
     * 3. ✅ **`[model_providers.<id>.auth]` 的 `command` 可以绕开环境变量** ——
     *    实测不导出任何环境变量也能过认证、直接去连 base_url。
     *    所以钥匙放一个 600 的文件、让它 `cat` 出来，**不用往 tmux 里注环境变量**。
     *    ⚠️ `command` 是**字符串**不是数组（写成数组报 `invalid type: sequence, expected a string`）。
     *
     * ⚠️ **Codex 换线一定要重开会话。** 配置是进程启动时读的，这一条我**没有**实测
     * （手上没有可用的 Codex 钥匙，没法验「换了之后运行中的会话变没变」），
     * 依据是 CC Switch 的文档明写「Codex requires a terminal restart」。**界面上要照实说。**
     */
    private const val PROVIDER_ID = "yxi"
    private const val HEAD_ON = "# >>> yxi line >>>"
    private const val HEAD_OFF = "# <<< yxi line <<<"
    private const val BODY_ON = "# >>> yxi provider >>>"
    private const val BODY_OFF = "# <<< yxi provider <<<"

    /**
     * 把我们那两段从 config.toml 里挖掉，其余**一字不动**。
     *
     * ⚠️ **为什么是两段而不是一段**：TOML 里 `model_provider = "..."` 是**顶层键**，
     * 必须出现在**任何 `[表]` 之前**；而 `[model_providers.yxi]` 是个表，必须放在**最后**
     * （否则它后面用户自己的顶层键会被吃进这张表里）。一段做不到，两段才安全。
     *
     * ⚠️ **不做 TOML 解析**。config.toml 里还装着用户的 MCP 配置，
     * 用字符串硬拼一个「合并」出来的文件迟早把人家的东西写坏。只认自己那两段标记，
     * 认不出来就当没有 —— 宁可少改，不能改坏。
     */
    private fun stripBlocks(toml: String): String {
        var t = toml
        for ((a, b) in listOf(HEAD_ON to HEAD_OFF, BODY_ON to BODY_OFF)) {
            while (true) {
                val i = t.indexOf(a)
                if (i < 0) break
                val j = t.indexOf(b, i)
                if (j < 0) { t = t.substring(0, i); break }
                t = t.substring(0, i) + t.substring(j + b.length)
            }
        }
        return t.trim('\n', ' ', '\t')
    }

    /** Codex 现在走的是不是我们设的线；返回那条线的 base_url，null = 没被我们接管。 */
    suspend fun currentCodex(ssh: SshSession?): String? = withContext(Dispatchers.IO) {
        val h = home(ssh) ?: return@withContext null
        val raw = ConfigRemote.readFile(ssh, "$h/.codex/config.toml") ?: return@withContext null
        val i = raw.indexOf(BODY_ON); if (i < 0) return@withContext null
        val j = raw.indexOf(BODY_OFF, i); if (j < 0) return@withContext null
        Regex("""base_url\s*=\s*"([^"]*)"""").find(raw.substring(i, j))?.groupValues?.get(1)
    }

    /**
     * 给 Codex 换线。[line] 传 null = 撤掉我们那两段，回它自己原来的登录。
     * @return 出错原因；null = 文件已写好（**但要重开那个会话才生效**，调用方必须说这句）。
     */
    suspend fun applyCodex(ssh: SshSession?, line: Line?): String? = withContext(Dispatchers.IO) {
        val s = ssh ?: return@withContext "没连上"
        val h = home(s) ?: return@withContext "取不到家目录"
        val path = "$h/.codex/config.toml"
        val body = stripBlocks(ConfigRemote.readFile(s, path).orEmpty())
        val next = if (line == null) body else buildString {
            append(HEAD_ON).append('\n')
            append("# 这两段是 Yxi「线路」自动写的，手改会被覆盖。\n")
            append("model_provider = \"").append(PROVIDER_ID).append("\"\n")
            append(HEAD_OFF).append("\n\n")
            if (body.isNotBlank()) append(body).append("\n\n")
            append(BODY_ON).append('\n')
            append("[model_providers.").append(PROVIDER_ID).append("]\n")
            append("name = \"").append(line.name.replace("\"", "'")).append("\"\n")
            append("base_url = \"").append(line.baseUrl).append("\"\n")
            append("wire_api = \"responses\"\n")
            // ⚠️ 用 auth.command 而不是 env_key：env_key 要求进程环境里真有那个变量，
            //    而我们没法往用户已经开着的 tmux 里注环境变量。command 实测可行。
            append("\n[model_providers.").append(PROVIDER_ID).append(".auth]\n")
            append("command = \"cat ").append(keyPath(h)).append("\"\n")
            append(BODY_OFF).append('\n')
        }
        s.exec("mkdir -p \"\$HOME/.codex\" \"\$HOME/.yxi\" && chmod 700 \"\$HOME/.yxi\"")
        if (line != null) {
            // 钥匙单独一个 600 的文件，不进 config.toml —— config.toml 用户自己也会看、也会贴给人看
            runCatching {
                val sftp = s.openSftp()
                try { sftp.write(keyPath(h), (line.apiKey + "\n").toByteArray()) } finally { runCatching { sftp.close() } }
            }.onFailure { return@withContext "写钥匙失败：${it.message?.take(60)}" }
            s.exec("chmod 600 " + shq(keyPath(h)))
        }
        // ⚠️ ConfigRemote.save 只校验 .json，**toml 它不校验**。所以这里绝不做「合并」，
        //    只做「挖掉自己那两段再拼回去」——用户的部分是原样搬运的，语法坏不了。
        ConfigRemote.save(s, path, next.trimEnd() + "\n")
    }

    private fun keyPath(home: String) = "$home/.yxi/codex-key"

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
