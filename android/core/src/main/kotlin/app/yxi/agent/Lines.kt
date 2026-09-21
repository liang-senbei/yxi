package app.yxi.agent

import app.yxi.ssh.Shell
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
        /**
         * **CC Switch 那一整段 settings 片段里，除三个核心 env 之外的部分**（v2，2026-09-05）：
         * `env` 里的模型映射（ANTHROPIC_MODEL / ANTHROPIC_DEFAULT_*_MODEL / ENABLE_TOOL_SEARCH …）
         * 和顶层键（model / effortLevel / includeCoAuthoredBy / autoCompactWindow …）。
         * 三个核心键**不放这里**（放了就有两个真相源）。Codex 线路放 model / model_reasoning_effort 等。
         */
        val extra: JSONObject = JSONObject(),
        val note: String = "",
        val website: String = "",
    ) {
        val isCodex get() = agent == CODEX
        /** extra 里 env 部分（不含核心三键） */
        fun extraEnv(): JSONObject = extra.optJSONObject("env") ?: JSONObject()
        /** extra 里顶层键（不含 env） */
        fun extraTop(): Map<String, Any> = extra.keys().asSequence().filter { it != "env" }.associateWith { extra.get(it) }
        /** 整段 settings 片段（核心三键 + extra），给「高级 JSON」编辑和导出用 */
        fun settingsJson(): JSONObject {
            val env = JSONObject(extraEnv().toString())
            env.put("ANTHROPIC_BASE_URL", baseUrl).put("ANTHROPIC_AUTH_TOKEN", token).put("ANTHROPIC_API_KEY", apiKey)
            val o = JSONObject(); extraTop().forEach { (k, v) -> o.put(k, v) }; o.put("env", env); return o
        }
        companion object {
            /** 从一整段 settings 片段拆回 Line（编辑「高级 JSON」保存时用）。核心三键抽出来，其余进 extra。 */
            fun fromSettings(base: Line, settings: JSONObject): Line {
                val env = settings.optJSONObject("env") ?: JSONObject()
                val ex = JSONObject(); val exEnv = JSONObject()
                // ⚠️ 进模型这一道门就过白名单：不许的键连 lines.json 都进不去（见 TOP_ALLOW / envAllowed）
                env.keys().forEach { k -> if (k !in CORE && envAllowed(k)) exEnv.put(k, env.get(k)) }
                settings.keys().forEach { k -> if (k != "env" && topAllowed(k)) ex.put(k, settings.get(k)) }
                if (exEnv.length() > 0) ex.put("env", exEnv)
                return base.copy(
                    baseUrl = env.optString("ANTHROPIC_BASE_URL"), token = env.optString("ANTHROPIC_AUTH_TOKEN"),
                    apiKey = env.optString("ANTHROPIC_API_KEY"), extra = ex,
                )
            }
        }
    }

    const val CLAUDE = "claude"
    const val CODEX = "codex"

    /** 三个核心 env 键。**永远全写、不设写空串**（#254）。 */
    val CORE = listOf("ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY")
    /** 官方文档明写「启动时读一次」的顶层键 —— 碰了这些切换后要提示重开（或 /model、/effort）。 */
    val RESTART_ONLY_TOP = setOf("model", "effortLevel", "modelSettings", "outputStyle")

    /**
     * ⚠️⚠️ **线路能写进 settings.json 的顶层键 —— 白名单，默认拒绝。**
     * 安全审查（2026-09-05）：`~/.yxi/lines.json` 服务器上的 agent 能写；若允许任意顶层键，
     * 被注入的 agent 往某条线路塞 `hooks` / `apiKeyHelper` / `permissions` / `enabledPlugins`，
     * 用户在手机上点一下「换线」，settings.json 里就多了一条**会执行命令的钩子**（Claude Code 热重载 hooks）——
     * 「人在手机上把关」被整个绕开。所以只放 CC Switch 那一排**纯偏好**的键；不在名单里的**不写、不删**，界面上明说。
     */
    val TOP_ALLOW = setOf(
        "model", "effortLevel", "modelSettings", "outputStyle", "includeCoAuthoredBy",
        "autoCompactWindow", "autoUpdatesChannel", "skipDangerousModePermissionPrompt", "skipWebFetchPreflight",
    )

    /**
     * ⚠️⚠️ **env 同理，白名单按前缀。** `NODE_OPTIONS=--require=/x.js` / `LD_PRELOAD` / `PATH` / `HOME`
     * 会进 Claude Code 的进程环境，等于任意代码执行（审查员核过：它是动态链接的 Node SEA）。
     * 放行：`ANTHROPIC_*`（端点/钥匙/模型映射）、`ENABLE_*` / `DISABLE_*`（功能开关），
     * 外加几个点名的 Claude Code 开关。其余一律不进数据模型、不写文件。
     */
    private val ENV_ALLOW_PREFIX = listOf("ANTHROPIC_", "ENABLE_", "DISABLE_")
    private val ENV_ALLOW_EXACT = setOf(
        "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS", "CLAUDE_CODE_MAX_OUTPUT_TOKENS", "MAX_THINKING_TOKENS", "API_TIMEOUT_MS",
    )
    fun envAllowed(k: String) = k in ENV_ALLOW_EXACT || ENV_ALLOW_PREFIX.any { k.startsWith(it) }
    fun topAllowed(k: String) = k in TOP_ALLOW

    /**
     * 把一段 settings 片段里**不许写**的键挑出来（顶层 + env），返回它们的名字。
     * 数据进模型时（[Line.fromSettings]）和落盘时（[apply]）**都**过一遍 —— 两道门。
     */
    fun rejectedKeys(extra: JSONObject): List<String> {
        val out = ArrayList<String>()
        extra.keys().forEach { k -> if (k != "env" && !topAllowed(k)) out += k }
        extra.optJSONObject("env")?.keys()?.forEach { k -> if (k !in CORE && !envAllowed(k)) out += "env.$k" }
        return out
    }

    /** `settings.json` 里此刻真正写着的那三个值。 */
    data class Env(val baseUrl: String, val token: String, val apiKey: String, val all: Map<String, String> = emptyMap()) {
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
     * 我们**曾经**写过的 env 键 / 顶层键，**只增不减**。
     * 为什么要单独存：`apply()` 靠「线路 extra 的并集」决定把哪些键写成空串 —— 线路一删，它独有的键就从并集里消失，
     * settings.json 里那个值再没人清，**永久残留**（正确性审查复现）。
     * 为什么不放进 lines.json：那是个数组，1.1.8 的 App 用 `JSONArray(raw)` 读，改成对象它们就「取不到」。
     */
    private fun ownedPath(home: String) = "$home/.yxi/lines-owned.json"
    private class Owned(val env: MutableSet<String>, val top: MutableSet<String>)
    private suspend fun readOwned(ssh: SshSession, home: String): Owned {
        val o = runCatching { JSONObject(ConfigRemote.readFile(ssh, ownedPath(home)) ?: "{}") }.getOrElse { JSONObject() }
        fun set(k: String) = (o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()).toMutableSet()
        return Owned(set("env").filter(::envAllowed).toMutableSet(), set("top").filter(::topAllowed).toMutableSet())
    }
    /** 把这批线路的键并进历史集合并落盘（只增）。写不成不算错 —— 最坏是残留一次，下次再清。 */
    private suspend fun growOwned(ssh: SshSession, home: String, lines: List<Line>): Owned {
        val o = readOwned(ssh, home)
        lines.filter { !it.isCodex }.forEach { l ->
            l.extraEnv().keys().forEach { if (envAllowed(it)) o.env += it }
            l.extraTop().keys.forEach { if (topAllowed(it)) o.top += it }
        }
        val j = JSONObject().put("env", JSONArray(o.env.sorted())).put("top", JSONArray(o.top.sorted())).toString(1)
        runCatching { val f = ssh.openSftp(); try { f.write(ownedPath(home), j.toByteArray()) } finally { runCatching { f.close() } } }
        ssh.exec("chmod 600 " + Shell.q(ownedPath(home)) + " 2>/dev/null")
        return o
    }

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
        else safeCwd(cwd)?.let { "$it/.claude/settings.local.json" }

    /**
     * cwd 来自 `tmux` 的 `pane_current_path` —— **服务器上的 agent 自己就能 `cd`**，是信任边界。
     * 只收绝对路径、不含 `..` 段、不含控制字符；不合格返回 null，调用方**拒绝写**（fail-closed，
     * 同 SECURITY.md 对会话名的处理）。
     */
    private fun safeCwd(cwd: String): String? {
        val c = cwd.trimEnd('/')
        if (!c.startsWith("/") || c.isBlank()) return null
        if (c.split('/').any { it == ".." }) return null
        if (c.any { it < ' ' || it == '\u007f' }) return null
        return c
    }

    // ── 线路清单 ────────────────────────────────────────────────────────────

    /** @return null = 拿不到（没连上）。空表 = 真的一条都没建过 —— 两件事，界面要分开说。 */
    suspend fun list(ssh: SshSession?): List<Line>? = withContext(Dispatchers.IO) {
        val h = home(ssh) ?: return@withContext null
        val raw = ConfigRemote.readFile(ssh, listPath(h)) ?: return@withContext emptyList()
        // ⚠️ 文件在但解析不了 = 「拿不到」，不是「一条都没有」。当成空表的话人会去重建、
        //    原来那份（可能是手改坏的）就被覆盖了。返回 null 让界面说「取不到」。
        parseLines(raw)
    }

    private fun parseLines(raw: String): List<Line>? =
        if (raw.isBlank()) emptyList() else runCatching {
            val a = JSONArray(raw)
            val ids = mutableSetOf<String>()
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let {
                    require(it.optString("id").isNotBlank() && ids.add(it.getString("id"))) { "线路 ID 缺失或重复" }
                    val base = Line(
                        id = it.optString("id"), name = it.optString("name"),
                        baseUrl = it.optString("baseUrl"), token = it.optString("token"),
                        apiKey = it.optString("apiKey"),
                        // 老清单没有这个字段，默认当 Claude —— 加字段不能让已有的线路变身
                        agent = it.optString("agent").ifBlank { CLAUDE },
                        note = it.optString("note"),
                        website = it.optString("website"),
                    )
                    // v2：带 settings 片段就从片段拆；v1 条目没有 settings，原样（1.1.8 用户无感升级）
                    it.optJSONObject("settings")?.let { st -> if (base.isCodex) base.copy(extra = st) else Line.fromSettings(base, st) } ?: base
                }
            }
        }.getOrNull()

    private fun encodeLines(lines: List<Line>): JSONArray {
        val a = JSONArray()
        lines.forEach {
            a.put(
                JSONObject().put("id", it.id).put("name", it.name)
                    .put("baseUrl", it.baseUrl).put("token", it.token).put("apiKey", it.apiKey)
                    .put("agent", it.agent).put("note", it.note).put("website", it.website)
                    // v2：整段片段也存一份 —— 读的时候以它为准；核心三键仍单独存是给 1.1.8 之前的 App 读的
                    .put("settings", if (it.isCodex) it.extra else it.settingsJson()),
            )
        }
        return a
    }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }

    /** A supplied expected catalog protects edits made since the UI loaded it; legacy callers still get atomic writes. */
    suspend fun saveList(ssh: SshSession?, lines: List<Line>, expected: List<Line>? = null): String? = withContext(Dispatchers.IO) {
        val s = ssh ?: return@withContext "没连上"
        val h = home(s) ?: return@withContext "取不到家目录"
        val snapshot = try { RemoteAtomicJson.read(s, listPath(h)) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { return@withContext "原配置无法读取，未覆盖：${e.message}" }
        val current = parseLines(snapshot.text ?: "[]") ?: return@withContext "原配置已损坏，未覆盖"
        if (expected != null && canonical(encodeLines(expected)) != canonical(encodeLines(current))) return@withContext "线路清单已变化，本次未覆盖，请刷新"
        if (s.exec("mkdir -p \"\$HOME/.yxi\" && chmod 700 \"\$HOME/.yxi\" && printf YXI_READY").trim() != "YXI_READY") return@withContext "无法准备线路目录"
        RemoteAtomicJson.write(s, listPath(h), encodeLines(lines).toString(2), snapshot.revision)?.let { return@withContext it }
        growOwned(s, h, lines)          // 删线路之前它的键已经在历史集合里了，删了照样能清
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
            Env(e.optString(BASE), e.optString(TOKEN), e.optString(KEY),
                e.keys().asSequence().associateWith { k -> e.optString(k) })
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
    fun matches(line: Line, env: Env): Boolean {
        if (line.baseUrl != env.baseUrl || line.token != env.token || line.apiKey != env.apiKey) return false
        // v2：这条线路带的模型映射等 env 键也要对上（两条线端点钥匙一样、模型不一样是常见的）
        val ex = line.extraEnv()
        return ex.keys().asSequence().all { k -> env.all[k] == ex.optString(k) }
    }

    // ── 换线 ────────────────────────────────────────────────────────────────

    /**
     * 换到这条线路。[line] 传 null = 回默认（三个 key 全写空串，**不是删掉**）。
     *
     * ⚠️ 写法上只碰 `env` 里那三个 key，`settings.json` 的其余部分原样保留；
     * 落盘走 [ConfigRemote.save]，它**先备份、json 先校验**，坏了绝不写。
     *
     * @return [Applied]：`err` 出错原因（null = 成功）；`restart` 这次**真改了值**的启动时读一次的顶层键
     *   （model/effortLevel/modelSettings/outputStyle），界面要照实说「这几个要重开会话」。
     *   ⚠️ 只算真变了的：以前按「所有线路碰过的键」算，effortLevel 没变也喊要重开，E2E 抓到是错的。
     */
    class Applied(val err: String?, val restart: List<String> = emptyList())

    suspend fun apply(ssh: SshSession?, line: Line?, cwd: String? = null, all: List<Line> = emptyList()): Applied = withContext(Dispatchers.IO) {
        val s = ssh ?: return@withContext Applied("没连上")
        val path = targetPath(s, cwd) ?: return@withContext Applied("取不到要改的文件路径")
        // 项目级那个 .claude 目录可能还不存在，SFTP 不会替你建
        if (cwd != null) s.exec("mkdir -p " + Shell.q(cwd.trimEnd('/') + "/.claude"))
        val raw = ConfigRemote.readFile(s, path) ?: "{}"
        val root = runCatching { JSONObject(raw) }.getOrElse {
            return@withContext Applied("settings.json 现在就是坏的（${it.message?.take(40)}），没敢动")
        }
        val env = root.optJSONObject("env") ?: JSONObject()
        // ⚠️⚠️ 三个全写，不设的写空串。**别用 remove()** —— 删掉不生效，旧值还在进程里。
        env.put(BASE, line?.baseUrl.orEmpty())
        env.put(TOKEN, line?.token.orEmpty())
        env.put(KEY, line?.apiKey.orEmpty())
        // v2：我们「拥有」的 env 键 = 所有线路里出现过的 env 键的并集。**同样全写、不设写空串** ——
        //    上一条线路设过 ANTHROPIC_MODEL、这一条没设，不写空串它就一直是上一条的模型（#254 的同款）。
        // ⚠️ 落盘前**再**过一遍白名单：lines.json 是服务器上读回来的，agent 能改，进模型那道门挡不住它。
        // 并集 = **历史上写过的**（含已删线路的）∪ 当前列表 ∪ 这条。历史只增不减，所以删线路不会留残留。
        val hist = growOwned(s, home(s) ?: return@withContext Applied("取不到家目录"), all + listOfNotNull(line))
        val owned = hist.env
        owned.forEach { k -> env.put(k, line?.extraEnv()?.optString(k).orEmpty()) }
        root.put("env", env)
        // 顶层键：并集里这条没给的**删掉**（顶层键没有 env 那种「删不生效」的事，删了就是回默认）；
        // 给了的照值写。model / effortLevel 这些是启动时读一次的，**真变了**才让界面提示要重开。
        // 顶层键只碰白名单里的：不在名单里的**既不写也不删**（用户自己的 hooks / permissions 一根手指都不碰）
        val restart = mutableListOf<String>()
        hist.top.forEach { k ->
            val v = line?.extraTop()?.get(k)
            if (k in RESTART_ONLY_TOP && root.opt(k)?.toString() != v?.toString()) restart += k
            if (v == null) root.remove(k) else root.put(k, v)
        }
        val err = if (cwd == null) ConfigRemote.save(s, path, root.toString(2)) else writeNoBackup(s, path, root.toString(2))
        Applied(err, restart.sorted())
    }

    /**
     * 项目级文件**不能**走 [ConfigRemote.save]：它会在**项目目录**里留一份
     * `settings.local.json.yxi-bak-<时间戳>`，里面有钥匙，而 `.gitignore` 里匹配的是
     * `settings.local.json`，**备份文件名对不上** —— 用户一句 `git add -A` 就把钥匙提交进历史
     * （安全审查 2026-09-05 用 `git check-ignore` 实测）。
     * 这里 JSON 已经在内存里校验过（就是我们自己 `toString` 出来的），直接 SFTP 写，不留备份。
     */
    private suspend fun writeNoBackup(s: SshSession, path: String, text: String): String? {
        runCatching { JSONObject(text) }.onFailure { return "内部错误：要写的不是合法 JSON" }
        return runCatching {
            val sftp = s.openSftp()
            try { sftp.write(path, text.toByteArray()) } finally { runCatching { sftp.close() } }
            null
        }.getOrElse { "写失败：${it.message?.take(60)}" }
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
        val hist = readOwned(s, home(s) ?: return@withContext "取不到家目录")
        root.optJSONObject("env")?.let { e ->
            e.remove(BASE); e.remove(TOKEN); e.remove(KEY)
            // 「跟随整机」= 项目级不再有我们写过的**任何**键：只删三键的话模型/强度会留在项目级继续覆盖整机（正确性审查复现）
            hist.env.forEach { k -> e.remove(k) }
            if (e.length() == 0) root.remove("env") else root.put("env", e)
        }
        hist.top.forEach { k -> root.remove(k) }
        writeNoBackup(s, path, root.toString(2))
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
        val u = Shell.q(baseUrl.trimEnd('/'))
        // ⚠️ **别写 `|| echo 000`。** curl 连不上时**自己就会**把 `%{http_code}` 输出成 `000`
        //    并且退出码非零，`||` 再补一个就拼成 `000000`，于是「== 000」判不出来，
        //    界面把一个死端点报成「通」。E2E 实测抓到的（2026-09-05），见 TROUBLESHOOTING #255。
        val code = s.exec("curl -sS -o /dev/null -m 8 -w '%{http_code}' $u 2>/dev/null").trim()
        when {
            // 000 是 curl 表示「压根没连上」的约定值；用 startsWith 兜住重复输出的情况
            code.isBlank() || code.startsWith("000") -> "连不上这个地址"
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
    private const val ORIGINAL_ROOT = "# yxi original root: "

    /** Preserve displaced root assignments as comments so removing our blocks restores them. */
    internal fun shadowCodexRoot(body: String, keys: Set<String>): String {
        var root = true
        return body.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trimStart()
            if (root && !trimmed.startsWith("#")) {
                require(!line.contains("\"\"\"") && !line.contains("'''")) { "顶层包含多行 TOML 字符串，请先将配置改为单行字符串再切换线路" }
                if (trimmed.startsWith("[")) root = false
            }
            val name = if (root) Regex("""^\s*(?:([A-Za-z0-9_-]+)|"([A-Za-z0-9_-]+)"|'([A-Za-z0-9_-]+)')\s*=""").find(line)
                ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } else null
            if (name in keys) ORIGINAL_ROOT + line else line
        }
    }

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
    internal fun stripBlocks(toml: String): String? {
        // ⚠️ **按整行认标记**，不是 indexOf：值里若出现同样的字（name 写成 `x # <<< yxi provider <<<`），
        //    indexOf 会在值中间截断，把 auth 段泄进「用户内容」区（安全审查实测）。
        //    配合 [tomlEscape]（值里不可能有换行），只有真正独占一行的才算标记。
        val lines = toml.split('\n')
        val out = ArrayList<String>(lines.size)
        var inside: String? = null            // 当前在哪一段里，null = 用户内容
        for (ln in lines) {
            val t = ln.trim()
            when {
                inside == null && (t == HEAD_ON || t == BODY_ON) -> inside = if (t == HEAD_ON) HEAD_OFF else BODY_OFF
                inside != null && t == inside -> inside = null
                inside != null && (t == HEAD_ON || t == BODY_ON || t == HEAD_OFF || t == BODY_OFF) -> return null
                inside == null && (t == HEAD_OFF || t == BODY_OFF) -> return null
                inside == null -> out.add(if (ln.startsWith(ORIGINAL_ROOT)) ln.removePrefix(ORIGINAL_ROOT) else ln)
            }
        }
        // ⚠️ 开了没关 = 标记不成对。**宁可拒绝，不能猜**：原来遇到这种情况是把后面整段截掉，
        //    用户的 MCP 配置会跟着一起消失（正确性审查指出）。
        if (inside != null) return null
        return out.joinToString("\n").trim('\n', ' ', '\t')
    }

    /**
     * TOML 基本字符串转义。**跟 [Shell.q] 是两套规则**，不能混用：
     * `"` `\` 要转义，换行/控制字符要写成 `\n` / `\uXXXX`。
     * 不转义的话 baseUrl 里一个 `"` 就能关掉字符串、往 `[model_providers.yxi]` 表里注入任意键
     * （安全审查用 tomllib 和 codex --strict-config 双双复现）。
     */
    internal fun tomlEscape(v: String): String = buildString(v.length + 8) {
        for (ch in v) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch < ' ' || ch == '\u007f' -> append("\\u%04X".format(ch.code))
            else -> append(ch)
        }
    }

    /** Codex 此刻被我们接管到的端点 + 钥匙。**两个都要比**：同一个中转常挂几家，端点一样钥匙不一样。 */
    data class CodexNow(val baseUrl: String, val apiKey: String, val model: String = "", val effort: String = "")

    fun matchesCodex(line: Line, now: CodexNow): Boolean = line.baseUrl == now.baseUrl && line.apiKey == now.apiKey &&
        line.extra.optString("model") == now.model && line.extra.optString("model_reasoning_effort") == now.effort

    /** Codex 现在走的是不是我们设的线；null = 没被我们接管（走它自己的登录）。 */
    suspend fun currentCodex(ssh: SshSession?): CodexNow? = withContext(Dispatchers.IO) {
        val h = home(ssh) ?: return@withContext null
        val raw = ConfigRemote.readFile(ssh, "$h/.codex/config.toml") ?: return@withContext null
        val ls = raw.split('\n').map { it.trim() }
        val i = ls.indexOf(BODY_ON); if (i < 0) return@withContext null
        val j = ls.indexOf(BODY_OFF); if (j < i) return@withContext null
        val url = ls.subList(i, j).firstOrNull { it.startsWith("base_url") }
            ?.let { Regex("""base_url\s*=\s*"(.*)"\s*$""").find(it)?.groupValues?.get(1) }
            ?.let { tomlUnescape(it) } ?: return@withContext null
        // 钥匙只读进内存做比对，不显示、不落日志
        val key = ConfigRemote.readFile(ssh, keyPath(h))?.trimEnd('\n').orEmpty()
        val headStart = ls.indexOf(HEAD_ON); val headEnd = ls.indexOf(HEAD_OFF)
        val head = if (headStart >= 0 && headEnd > headStart) ls.subList(headStart + 1, headEnd) else emptyList()
        fun ownedValue(name: String) = head.firstNotNullOfOrNull { row ->
            Regex("""^$name\s*=\s*"(.*)"\s*$""").find(row)?.groupValues?.get(1)?.let(::tomlUnescape)
        }.orEmpty()
        CodexNow(url, key, ownedValue("model"), ownedValue("model_reasoning_effort"))
    }

    /**
     * 给 Codex 换线。[line] 传 null = 撤掉我们那两段，回它自己原来的登录。
     * @return 出错原因；null = 文件已写好（**但要重开那个会话才生效**，调用方必须说这句）。
     */
    suspend fun applyCodex(ssh: SshSession?, line: Line?): String? = withContext(Dispatchers.IO) {
        val s = ssh ?: return@withContext "没连上"
        val h = home(s) ?: return@withContext "取不到家目录"
        val path = "$h/.codex/config.toml"
        // Codex 的 auth.command 不按 shell 解析，路径里有空白/引号就没法表达 —— 与其写一条必失败的命令，不如拒绝并说清
        if (line != null && keyPath(h).any { it.isWhitespace() || it == '"' || it == '\'' })
            return@withContext "家目录路径含空格或引号（$h），Codex 的 auth.command 不支持这种路径，这条线路写不了"
        val body = stripBlocks(ConfigRemote.readFile(s, path).orEmpty())
            ?: return@withContext "config.toml 里 Yxi 的标记不成对（被手改过？），没敢动 —— 手动把 `# >>> yxi` / `# <<< yxi` 那几行清掉再试"
        // ⚠️ 用户自己手写过一个同名的表（没带我们的标记）→ 再写一份就是重复表头，TOML 非法、codex 直接拒启。
        //    宁可拒绝并说清楚，不能猜（第二轮复核指出）。
        // ponytail: 只认 `[model_providers.yxi]` 这种朴素写法；TOML 还允许 `[ model_providers.yxi ]`
        //    和 `[model_providers."yxi"]`，这里漏检（第三轮复核指出）。漏检的后果是 codex 拒启、当场可见，
        //    不是静默；真有人这么写再上 TOML 解析。
        if (line != null && body.lineSequence().any {
                val t = it.trim(); t == "[model_providers.$PROVIDER_ID]" || t.startsWith("[model_providers.$PROVIDER_ID.")
            }
        ) return@withContext "config.toml 里已经有一个你自己写的 [model_providers.$PROVIDER_ID]，跟 Yxi 要写的撞名了 —— 先把它改个名再试"
        val model = line?.extra?.optString("model").orEmpty()
        val effort = line?.extra?.optString("model_reasoning_effort").orEmpty()
        val ownedKeys = setOf("model_provider") + (if (model.isBlank()) emptySet() else setOf("model")) +
            (if (effort.isBlank()) emptySet() else setOf("model_reasoning_effort"))
        val preserved = if (line == null) body else try { shadowCodexRoot(body, ownedKeys) }
            catch (e: IllegalArgumentException) { return@withContext e.message }
        val next = if (line == null) body else buildString {
            append(HEAD_ON).append('\n')
            append("# 这两段是 Yxi「线路」自动写的，手改会被覆盖。\n")
            append("model_provider = \"").append(PROVIDER_ID).append("\"\n")
            if (model.isNotBlank()) append("model = \"").append(tomlEscape(model)).append("\"\n")
            if (effort.isNotBlank()) append("model_reasoning_effort = \"").append(tomlEscape(effort)).append("\"\n")
            append(HEAD_OFF).append("\n\n")
            if (preserved.isNotBlank()) append(preserved).append("\n\n")
            append(BODY_ON).append('\n')
            append("[model_providers.").append(PROVIDER_ID).append("]\n")
            append("name = \"").append(tomlEscape(line.name)).append("\"\n")
            append("base_url = \"").append(tomlEscape(line.baseUrl)).append("\"\n")
            append("wire_api = \"responses\"\n")
            // ⚠️ 用 auth.command 而不是 env_key：env_key 要求进程环境里真有那个变量，
            //    而我们没法往用户已经开着的 tmux 里注环境变量。command 实测可行。
            append("\n[model_providers.").append(PROVIDER_ID).append(".auth]\n")
            // ⚠️ Codex 的 auth.command **不走 shell**，自己按空格切：加引号会被当成文件名的一部分
            //    （实测 `cat '/x/with space/key'` → No such file or directory）。所以路径**不能**加引号；
            //    含空格/引号的家目录在上面已经拒绝了，这里拿到的路径一定是干净的。
            append("command = \"").append(tomlEscape("cat " + keyPath(h))).append("\"\n")
            append(BODY_OFF).append('\n')
        }
        s.exec("mkdir -p \"\$HOME/.codex\" \"\$HOME/.yxi\" && chmod 700 \"\$HOME/.yxi\"")
        if (line != null) {
            // 钥匙单独一个 600 的文件，不进 config.toml —— config.toml 用户自己也会看、也会贴给人看
            runCatching {
                val sftp = s.openSftp()
                try { sftp.write(keyPath(h), (line.apiKey + "\n").toByteArray()) } finally { runCatching { sftp.close() } }
            }.onFailure { return@withContext "写钥匙失败：${it.message?.take(60)}" }
            s.exec("chmod 600 " + Shell.q(keyPath(h)))
        }
        // ⚠️ ConfigRemote.save 只校验 .json，**toml 它不校验**。所以这里绝不做「合并」，
        //    只做「挖掉自己那两段再拼回去」——用户的部分是原样搬运的，语法坏不了。
        val saved = ConfigRemote.save(s, path, next.trimEnd() + "\n")
        if (line == null && saved == null) {
            // 回默认线且配置已写好，把钥匙文件一并删掉（正确性/安全自查 F-A）：config.toml 已不再引用它，
            // 留一把明文钥匙在盘上没有理由。配置没写成（saved 非空）就不删，保持原样可回退；
            // 之后切回任意 Codex 线路时 applyCodex 会用线路里的 apiKey 重写这个文件，删了不影响。
            s.exec("rm -f " + Shell.q(keyPath(h)))
        }
        saved
    }

    private fun keyPath(home: String) = "$home/.yxi/codex-key"

    /**
     * [tomlEscape] 的逆，只用来把 config.toml 里我们自己写的 base_url 读回来比对。
     * ⚠️ **单遍扫描，不能用连串 replace**：先换 `\n` 再换 `\\` 的话，`\\n`（转义的反斜杠 + 字母 n）
     * 会被先当成换行吃掉（第二轮复核用 Python 转写复现）。
     */
    internal fun tomlUnescape(v: String): String =
        Regex("""\\(u([0-9A-Fa-f]{4})|[nrt"\\])""").replace(v) { m ->
            val g = m.groupValues[1]
            when {
                g.startsWith("u") -> m.groupValues[2].toInt(16).toChar().toString()
                g == "n" -> "\n"
                g == "r" -> "\r"
                g == "t" -> "\t"
                g == "\"" -> "\""
                else -> "\\"
            }
        }


    /**
     * 每个会话**现在用的是哪个模型**（老板 2026-09-07：「官方登录那行还要显示具体模型」）。
     * 读的是这个 cwd 最新那份转录里最后一条 `"model":"…"`。
     *
     * ⚠️ **必须按会话读，不能拿 settings.json 的账号默认糊上去**：实测同一台机器上各会话不一样
     *    （Yxi 在 fable-5-1、别的在 opus-5），糊上去就是显示一个假的 —— 比不显示更糟。
     * ⚠️ **不并进 [labels] 那条 5 秒轮询**：每个 cwd 要 ls + tail + grep 三个进程，三十个会话
     *    就是每 5 秒近百个进程，压在一台跑着一堆 agent 的机器上不合适。模型极少变，
     *    界面上单独挂一条慢轮询（60 秒）就够。
     * ⚠️ 目录名编码跟 Claude Code 一致：**非字母数字一律换成 `-`**（同 [Dirs] 里 resume 那段）。
     * @return 会话名 → 短模型名（去掉 `claude-` 前缀，跟对话页顶栏一个写法）
     */
    suspend fun models(ssh: SshSession?, sessions: List<Session>): Map<String, String> = withContext(Dispatchers.IO) {
        val s = ssh ?: return@withContext emptyMap()
        val cwds = sessions.asSequence().filter { !it.isCodex }.mapNotNull { safeCwd(it.cwd) }.toSet()
        if (cwds.isEmpty()) return@withContext emptyMap()
        val script = buildString {
            cwds.forEach { c ->
                append("enc=${'$'}(printf %s ").append(Shell.q(c)).append(" | sed 's/[^A-Za-z0-9]/-/g'); ")
                append("f=${'$'}(ls -t \"${'$'}HOME/.claude/projects/${'$'}enc\"/*.jsonl 2>/dev/null | head -1); ")
                append("[ -n \"${'$'}f\" ] && tail -c 32768 \"${'$'}f\" | grep -o '\"model\":\"[^\"]*\"' | tail -1; ")
                // ⚠️ cwd 走 printf 的**参数**，不嵌进格式串 —— 路径里一个 ' 会毁掉整段脚本（同 [labels]）
                append("printf '\\n@@M %s\\n' ").append(Shell.q(c)).append("; ")
            }
        }
        val out = runCatching { s.exec(script) }.getOrNull() ?: return@withContext emptyMap()
        val byCwd = HashMap<String, String>()
        var buf = StringBuilder()
        out.lineSequence().forEach { ln ->
            if (ln.startsWith("@@M ")) {
                Regex("\"model\":\"([^\"]+)\"").find(buf)?.groupValues?.get(1)
                    ?.removePrefix("claude-")?.takeIf { it.isNotBlank() }
                    ?.let { byCwd[ln.removePrefix("@@M ")] = it }
                buf = StringBuilder()
            } else buf.append(ln).append('\n')
        }
        val res = HashMap<String, String>()
        sessions.forEach { sess -> safeCwd(sess.cwd)?.let { c -> byCwd[c]?.let { res[sess.name] = it } } }
        res
    }

    // ── 看板：每个会话走的是什么 ────────────────────────────────────────────

    /** 给看板显示的一行小字。[custom] = 设了但不在清单里（电脑上用 CC Switch 切的、或手改的）。 */
    data class Label(val text: String, val lineName: String?, val project: Boolean, val custom: Boolean)

    /**
     * 所有会话各走哪条线路，**一次 SSH 往返**：一个脚本把整机 settings、每个 cwd 的项目级 settings、
     * codex 那段一起打回来，匹配在手机上做。读不到的会话**不给标签**（不猜）。
     *
     * ⚠️ cwd 过 [safeCwd]，不合格的不进脚本 —— 它来自 tmux，服务器上的 agent 能改。
     */
    suspend fun labels(ssh: SshSession?, sessions: List<Session>): Map<String, Label> = withContext(Dispatchers.IO) {
        val s = ssh ?: return@withContext emptyMap()
        val h = home(s) ?: return@withContext emptyMap()
        val cwds = sessions.asSequence().filter { !it.isCodex }.mapNotNull { safeCwd(it.cwd) }.toSet()
        val script = buildString {
            // 线路清单也并进同一次往返 —— 看板每 5 秒调一次，别为它多开一条 exec
            append("cat ").append(Shell.q(listPath(h))).append(" 2>/dev/null | tr -d '\\n'; printf '\\n@@LINES\\n'; ")
            append("cat ").append(Shell.q(settingsPath(h))).append(" 2>/dev/null | tr -d '\\n'; printf '\\n@@MACHINE\\n'; ")
            cwds.forEach { c ->
                // ⚠️ cwd 作为 printf 的**参数**传（%s），不嵌进格式串：嵌进去的话 Shell.q 的 '\'' 会跟外层单引号打架，
                //    路径里一个 ' 就让整段脚本解析失败、所有会话丢标签（安全审查用 bash -c 复现）
                append("cat ").append(Shell.q("$c/.claude/settings.local.json")).append(" 2>/dev/null | tr -d '\\n'; printf '\\n@@P %s\\n' ").append(Shell.q(c)).append("; ")
            }
            append("sed -n '/").append(BODY_ON).append("/,/").append(BODY_OFF).append("/p' ").append(Shell.q("$h/.codex/config.toml")).append(" 2>/dev/null; printf '\\n@@CODEX\\n'")
        }
        val out = runCatching { s.exec(script) }.getOrNull() ?: return@withContext emptyMap()
        fun envOf(raw: String): Env? = runCatching {
            val e = JSONObject(raw).optJSONObject("env") ?: return@runCatching null
            if (!e.has(BASE) && !e.has(TOKEN) && !e.has(KEY)) null
            else Env(e.optString(BASE), e.optString(TOKEN), e.optString(KEY), e.keys().asSequence().associateWith { k -> e.optString(k) })
        }.getOrNull()
        // 按标记切段：每段 = 内容 + 一行 @@… 标签
        val machineEnv: Env?; val project = HashMap<String, Env?>(); var codexUrl: String? = null
        var buf = StringBuilder(); var me: Env? = null; var machineSeen = false
        var lines: List<Line> = emptyList()
        out.lineSequence().forEach { ln ->
            when {
                ln == "@@LINES" -> { lines = parseLines(buf.toString()) ?: return@withContext emptyMap(); buf = StringBuilder() }   // 清单坏了宁可不标
                ln == "@@MACHINE" -> { me = envOf(buf.toString()); machineSeen = true; buf = StringBuilder() }
                // printf %s 已把 Shell.q 的引号吃掉，拿到的就是原路径 —— **别 trim 引号**，路径尾真有 ' 会被切掉
                ln.startsWith("@@P ") -> { project[ln.removePrefix("@@P ")] = envOf(buf.toString()); buf = StringBuilder() }
                ln == "@@CODEX" -> {
                    codexUrl = buf.lineSequence().firstOrNull { it.trim().startsWith("base_url") }
                        ?.let { Regex("""base_url\s*=\s*"(.*)"\s*$""").find(it.trim())?.groupValues?.get(1) }?.let { tomlUnescape(it) }
                    buf = StringBuilder()
                }
                else -> buf.append(ln).append('\n')
            }
        }
        if (!machineSeen) return@withContext emptyMap()      // 脚本没跑完整，宁可不标
        machineEnv = me
        val claudeLines = lines.filter { !it.isCodex }
        fun labelFor(env: Env?, fromProject: Boolean): Label {
            if (env == null || env.isDefault) return Label("claude · 官方登录", null, fromProject, false)
            val hit = claudeLines.firstOrNull { matches(it, env) }
            return if (hit != null) Label("Yxi_switch · ${hit.name}", hit.name, fromProject, false)
            else Label("自定义 · " + hostOf(env.baseUrl), null, fromProject, true)
        }
        val res = HashMap<String, Label>()
        sessions.forEach { sess ->
            if (sess.isCodex) {
                val url = codexUrl
                res[sess.name] = if (url == null) Label("codex · 官方登录", null, false, false)
                else lines.firstOrNull { it.isCodex && it.baseUrl == url }?.let { Label("Yxi_switch · ${it.name}", it.name, false, false) }
                    ?: Label("自定义 · " + hostOf(url), null, false, true)
            } else {
                val c = safeCwd(sess.cwd)
                val p = c?.let { project[it] }
                res[sess.name] = if (p != null) labelFor(p, true) else labelFor(machineEnv, false)
            }
        }
        res
    }

    private fun hostOf(url: String): String =
        url.removePrefix("https://").removePrefix("http://").substringBefore('/').ifBlank { url.ifBlank { "?" } }

    // ── 杂 ──────────────────────────────────────────────────────────────────

    /** 界面上显示钥匙一律走这里。全空串给空串，别显示成一串星号让人以为设过。 */
    fun mask(secret: String): String = when {
        secret.isBlank() -> ""
        secret.length <= 12 -> "•".repeat(secret.length)
        else -> secret.take(6) + "…" + secret.takeLast(4)
    }

    fun newId(): String = "line-" + java.util.UUID.randomUUID().toString().take(8)

}
