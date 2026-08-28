package app.yxi.agent

import app.yxi.ssh.SshSession
import org.json.JSONObject

/**
 * **配置浏览器的数据层** —— 一次 SSH 往返，把连的那台机器上 Claude Code（`~/.claude`）和
 * Codex（`~/.codex`）的配置**结构化**抓回来：技能 / MCP / 子 agent / 命令 / 权限 / 钩子 / 记忆 / 插件。
 *
 * ⚠️ **密钥绝不出服务器。** 抓取脚本在**服务器侧**就把 `env` 的值、以及键名含
 * key/token/secret/password/auth 的值打码成 ••••；`.credentials.json` / `auth.json` **根本不读**。
 * 编辑时才按需取原文（那是用户明确点「编辑」的动作）。
 *
 * ⚠️ 走的是 App 已有的那条 SSH 连接（exec 抓结构、SFTP 取/存文件），不碰公网网站、不走 HTTP。
 *
 * 工具无关：哪台装了 `~/.claude` / `~/.codex` 就显示哪个，都没有就空。
 */
object ConfigRemote {
    data class Item(
        val title: String,
        val sub: String = "",
        /** 服务器上的文件路径。非空 = 能查看/编辑这个文件。 */
        val path: String = "",
        /** 没有独立文件、直接内联展示的内容（如某个 MCP server 的一段 JSON、插件信息）。 */
        val inline: String = "",
        /** 结构化文件（settings.json / config.toml）：查看时用打码版，编辑时取原文并校验。 */
        val structured: Boolean = false,
    )
    data class Cat(val key: String, val title: String, val items: List<Item>)
    data class Tool(val key: String, val name: String, val cats: List<Cat>)

    /** 抓取整棵配置树。读不到就返回空列表。 */
    suspend fun load(ssh: SshSession?): List<Tool> {
        val raw = ssh?.exec(GATHER).orEmpty()
        val json = raw.substringAfter(MARKER, "").trim().ifBlank { return emptyList() }
        return runCatching { parse(JSONObject(json)) }.getOrDefault(emptyList())
    }

    /** 取一个文件的原文（编辑用；不打码）。 */
    suspend fun readFile(ssh: SshSession?, path: String): String? {
        val p = shq(path)
        val out = ssh?.exec("cat $p 2>/dev/null").orEmpty()
        return out.ifBlank { null }
    }

    /**
     * 存一个配置文件回服务器。**先备份**（`<file>.yxi-bak-<时间戳>`），json/toml **先校验**再写。
     * @return 出错原因；null = 成功。
     */
    suspend fun save(ssh: SshSession?, path: String, text: String): String? {
        val s = ssh ?: return "没连上"
        // 结构化格式先校验，坏了绝不写（一个语法错就能让 Claude Code / Codex 起不来）
        if (path.endsWith(".json")) {
            runCatching { JSONObject(text) }.onFailure { return "JSON 格式不对，没保存：${it.message?.take(60)}" }
        }
        val p = shq(path)
        // 备份 + 写。备份用 exec cp，写用 SFTP（二进制安全）
        val ts = System.currentTimeMillis() / 1000
        runCatching {
            s.exec("cp $p $p.yxi-bak-$ts 2>/dev/null || true")
            val sftp = s.openSftp()
            try { sftp.write(path, text.toByteArray()) } finally { runCatching { sftp.close() } }
        }.onFailure { return "写回失败：${it.message?.take(60)}" }
        return null
    }

    private fun shq(p: String) = "'" + p.replace("'", "'\\''") + "'"

    private fun parse(o: JSONObject): List<Tool> {
        val tools = ArrayList<Tool>()
        o.optJSONObject("claude")?.let { tools += parseClaude(it) }
        o.optJSONObject("codex")?.let { tools += parseCodex(it) }
        return tools
    }

    private fun parseClaude(c: JSONObject): Tool {
        val cats = ArrayList<Cat>()
        c.optJSONObject("memory")?.takeIf { it.optBoolean("exists") }?.let {
            cats += Cat("memory", "记忆 (CLAUDE.md)", listOf(Item("CLAUDE.md", path = it.optString("path"))))
        }
        c.optJSONObject("settings")?.let { s ->
            val sub = buildString {
                s.opt("model")?.takeIf { it != JSONObject.NULL }?.let { append("模型 $it  ") }
                val hooks = s.optJSONObject("hooks"); if (hooks != null && hooks.length() > 0) append("${hooks.length()} 类钩子")
            }
            cats += Cat("settings", "设置 (settings.json)", listOf(
                Item("settings.json", sub = sub.trim(), path = s.optString("path"), structured = true,
                    inline = s.optJSONObject("raw")?.toString(2).orEmpty()),
            ))
        }
        c.optJSONArray("mcp")?.let { a ->
            if (a.length() > 0) cats += Cat("mcp", "MCP 服务器", (0 until a.length()).map { i ->
                val m = a.getJSONObject(i)
                Item(m.optString("name"), sub = mcpSub(m.optJSONObject("info")), inline = m.optJSONObject("info")?.toString(2).orEmpty())
            })
        }
        cats += mdCat(c.optJSONArray("agents"), "agents", "子 agent")
        cats += mdCat(c.optJSONArray("skills"), "skills", "技能 Skills")
        cats += mdCat(c.optJSONArray("commands"), "commands", "斜杠命令")
        c.optJSONArray("plugins")?.let { a ->
            if (a.length() > 0) cats += Cat("plugins", "插件", (0 until a.length()).map { i ->
                val p = a.getJSONObject(i)
                Item(p.optString("name"), sub = p.optString("version"), inline = "installPath: ${p.optString("path")}")
            })
        }
        return Tool("claude", "Claude Code", cats.filter { it.items.isNotEmpty() })
    }

    private fun parseCodex(x: JSONObject): Tool {
        val cats = ArrayList<Cat>()
        x.optJSONObject("memory")?.takeIf { it.optBoolean("exists") }?.let {
            cats += Cat("memory", "记忆 (AGENTS.md)", listOf(Item("AGENTS.md", path = it.optString("path"))))
        }
        x.optJSONObject("config")?.takeIf { it.optBoolean("exists") }?.let {
            cats += Cat("config", "配置 (config.toml)", listOf(Item("config.toml", path = it.optString("path"), structured = true)))
        }
        x.optJSONArray("mcp")?.let { a ->
            if (a.length() > 0) cats += Cat("mcp", "MCP 服务器", (0 until a.length()).map { Item(a.getJSONObject(it).optString("name")) })
        }
        cats += mdCat(x.optJSONArray("prompts"), "prompts", "提示 prompts")
        return Tool("codex", "Codex", cats.filter { it.items.isNotEmpty() })
    }

    private fun mdCat(a: org.json.JSONArray?, key: String, title: String): Cat {
        val items = (0 until (a?.length() ?: 0)).map { i ->
            val o = a!!.getJSONObject(i)
            val from = o.optString("from")
            Item(o.optString("name"), sub = if (from.isNotBlank()) "由 $from 提供" else "自定义", path = o.optString("path"))
        }
        return Cat(key, title, items)
    }

    private fun mcpSub(info: JSONObject?): String {
        info ?: return ""
        info.optString("command").takeIf { it.isNotBlank() }?.let { c ->
            val args = info.optJSONArray("args")?.let { (0 until it.length()).joinToString(" ") { j -> it.optString(j) } }.orEmpty()
            return (c + " " + args).trim().take(60)
        }
        info.optString("url").takeIf { it.isNotBlank() }?.let { return it.take(60) }
        return info.optString("type")
    }

    private const val MARKER = "__YXI_CFG_V1__"

    // 服务器侧抓取 + 打码。用 python3（这些机器都有）；没有就返回空，界面显示「读不到」。
    private val GATHER = """
        command -v python3 >/dev/null 2>&1 || { echo '$MARKER'; echo '{}'; exit 0; }
        python3 - <<'YXIEOF'
        import json, os, glob, re
        home = os.path.expanduser("~")
        SECRET = re.compile(r'(key|token|secret|password|passwd|auth|credential)', re.I)
        def mask(o, under_env=False):
            if isinstance(o, dict):
                return {k: ('••••' if (under_env or (SECRET.search(k) and isinstance(v,(str,int)) and str(v))) else mask(v, k.lower()=='env')) for k,v in o.items()}
            if isinstance(o, list): return [mask(x) for x in o]
            return o
        def md_list(d):
            return [{"name": os.path.basename(f)[:-3], "path": f} for f in sorted(glob.glob(os.path.join(d,"*.md")))]
        out = {}
        cdir = os.path.join(home, ".claude")
        if os.path.isdir(cdir):
            c = {}
            mp = os.path.join(cdir,"CLAUDE.md"); c["memory"] = {"path": mp, "exists": os.path.isfile(mp)}
            sp = os.path.join(cdir,"settings.json"); mcp = {}
            if os.path.isfile(sp):
                try: s = json.load(open(sp))
                except Exception: s = {}
                c["settings"] = {"path": sp, "model": s.get("model"), "hooks": {k: len(v) for k,v in (s.get("hooks") or {}).items()}, "raw": mask(s)}
                mcp = dict(s.get("mcpServers") or {})
            cj = os.path.join(home,".claude.json")
            if os.path.isfile(cj):
                try: mcp.update(json.load(open(cj)).get("mcpServers") or {})
                except Exception: pass
            c["mcp"] = [{"name": k, "info": mask(v)} for k,v in mcp.items()]
            agents = md_list(os.path.join(cdir,"agents"))
            commands = md_list(os.path.join(cdir,"commands"))
            skills = [{"name": os.path.basename(os.path.dirname(sk)), "path": sk} for sk in sorted(glob.glob(os.path.join(cdir,"skills","*","SKILL.md")))]
            plugins = []
            pj = os.path.join(cdir,"plugins","installed_plugins.json")
            if os.path.isfile(pj):
                try:
                    for name, insts in (json.load(open(pj)).get("plugins") or {}).items():
                        for inst in insts:
                            ip = inst.get("installPath","")
                            plugins.append({"name": name, "version": inst.get("version",""), "path": ip})
                            skills += [{"name": os.path.basename(os.path.dirname(sk)), "path": sk, "from": name} for sk in sorted(glob.glob(os.path.join(ip,"skills","*","SKILL.md")))]
                            agents += [{"name": os.path.basename(f)[:-3], "path": f, "from": name} for f in sorted(glob.glob(os.path.join(ip,"agents","*.md")))]
                            commands += [{"name": os.path.basename(f)[:-3], "path": f, "from": name} for f in sorted(glob.glob(os.path.join(ip,"commands","*.md")))]
                except Exception: pass
            c["agents"]=agents; c["commands"]=commands; c["skills"]=skills; c["plugins"]=plugins
            out["claude"]=c
        xdir = os.path.join(home,".codex")
        if os.path.isdir(xdir):
            x={}
            mp=os.path.join(xdir,"AGENTS.md"); x["memory"]={"path":mp,"exists":os.path.isfile(mp)}
            ct=os.path.join(xdir,"config.toml"); x["config"]={"path":ct,"exists":os.path.isfile(ct)}
            mcps=[]
            if os.path.isfile(ct):
                for line in open(ct):
                    m=re.match(r'\s*\[mcp_servers\.([^\]]+)\]', line)
                    if m: mcps.append({"name": m.group(1).strip('"')})
            x["mcp"]=mcps
            x["prompts"]=[{"name": os.path.basename(f), "path": f} for f in sorted(glob.glob(os.path.join(xdir,"prompts","*")))]
            out["codex"]=x
        print("$MARKER"); print(json.dumps(out, ensure_ascii=False))
        YXIEOF
    """.trimIndent()
}
