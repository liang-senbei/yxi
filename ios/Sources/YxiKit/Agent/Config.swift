import Foundation

/// **配置浏览器** —— 分服务器、分工具（Claude Code / Codex）看那台机器上的 agent 配置：
/// 技能 / MCP / 子 agent / 命令 / 权限 / 钩子 / 记忆 / 插件。跟安卓版同一套抓取脚本。
///
/// ⚠️ **密钥绝不出服务器。** 抓取脚本在**服务器侧**就把 `env` 的值、以及键名含
/// key/token/secret/password/auth 的值打码成 ••••；`.credentials.json` / `auth.json` **根本不读**。
/// ⚠️ 工具无关：哪台装了 `~/.claude` / `~/.codex` 就显示哪个，都没有就空。
public enum AgentConfig {
    public static let marker = "__YXI_CFG_V1__"

    public struct Item: Identifiable, Equatable, Sendable {
        public let title: String
        public let sub: String
        /// 服务器上的文件路径。非空 = 能查看/编辑
        public let path: String
        /// 没有独立文件、直接内联展示的内容
        public let inline: String
        public var id: String { "\(title)|\(path)" }
        public init(title: String, sub: String = "", path: String = "", inline: String = "") {
            self.title = title; self.sub = sub; self.path = path; self.inline = inline
        }
    }
    public struct Cat: Identifiable, Equatable, Sendable {
        public let key: String
        public let title: String
        public let items: [Item]
        public var id: String { key }
    }
    public struct Tool: Identifiable, Equatable, Sendable {
        public let key: String
        public let name: String
        public let cats: [Cat]
        public var id: String { key }
    }

    /// 服务器侧抓取 + 打码。用 python3（这些机器都有）；没有就返回空，界面显示「读不到」。
    /// ⚠️ **跟安卓版是同一份脚本** —— 两端看到的配置必须一模一样，否则「复刻」无从谈起。
    public static let gatherScript = #"""
command -v python3 >/dev/null 2>&1 || { echo '__YXI_CFG_V1__'; echo '{}'; exit 0; }
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
print("__YXI_CFG_V1__"); print(json.dumps(out, ensure_ascii=False))
YXIEOF
"""#

    public static func parse(_ out: String) -> [Tool] {
        guard let r = out.range(of: marker) else { return [] }
        let json = String(out[r.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard let o = JSON.parse(line: json) else { return [] }
        var tools: [Tool] = []
        if o["claude"].isObject { tools.append(parseClaude(o["claude"])) }
        if o["codex"].isObject { tools.append(parseCodex(o["codex"])) }
        return tools.filter { !$0.cats.isEmpty }
    }

    private static func mdCat(_ a: JSON, _ key: String, _ title: String) -> Cat {
        Cat(key: key, title: title, items: a.array.map { o in
            let from = o["from"].string
            return Item(title: o["name"].string,
                        sub: from.isEmpty ? "自定义" : "由 \(from) 提供",
                        path: o["path"].string)
        })
    }

    private static func parseClaude(_ c: JSON) -> Tool {
        var cats: [Cat] = []
        if c["memory"]["exists"].bool {
            cats.append(Cat(key: "memory", title: "记忆 (CLAUDE.md)",
                            items: [Item(title: "CLAUDE.md", path: c["memory"]["path"].string)]))
        }
        if c["settings"].isObject {
            let model = c["settings"]["model"].string
            cats.append(Cat(key: "settings", title: "设置 (settings.json)", items: [
                Item(title: "settings.json", sub: model.isEmpty ? "" : "模型 \(model)",
                     path: c["settings"]["path"].string),
            ]))
        }
        let mcp = c["mcp"].array
        if !mcp.isEmpty {
            cats.append(Cat(key: "mcp", title: "MCP 服务器",
                            items: mcp.map { Item(title: $0["name"].string) }))
        }
        for (k, t, a) in [("agents", "子 agent", c["agents"]),
                          ("skills", "技能 Skills", c["skills"]),
                          ("commands", "斜杠命令", c["commands"])] {
            let cat = mdCat(a, k, t)
            if !cat.items.isEmpty { cats.append(cat) }
        }
        let plugins = c["plugins"].array
        if !plugins.isEmpty {
            cats.append(Cat(key: "plugins", title: "插件", items: plugins.map {
                Item(title: $0["name"].string, sub: $0["version"].string)
            }))
        }
        return Tool(key: "claude", name: "Claude Code", cats: cats)
    }

    private static func parseCodex(_ x: JSON) -> Tool {
        var cats: [Cat] = []
        if x["memory"]["exists"].bool {
            cats.append(Cat(key: "memory", title: "记忆 (AGENTS.md)",
                            items: [Item(title: "AGENTS.md", path: x["memory"]["path"].string)]))
        }
        if x["config"]["exists"].bool {
            cats.append(Cat(key: "config", title: "配置 (config.toml)",
                            items: [Item(title: "config.toml", path: x["config"]["path"].string)]))
        }
        let mcp = x["mcp"].array
        if !mcp.isEmpty {
            cats.append(Cat(key: "mcp", title: "MCP 服务器",
                            items: mcp.map { Item(title: $0["name"].string) }))
        }
        let prompts = mdCat(x["prompts"], "prompts", "提示 prompts")
        if !prompts.items.isEmpty { cats.append(prompts) }
        return Tool(key: "codex", name: "Codex", cats: cats)
    }

    /// 读一个配置文件的原文（编辑用，不打码）
    public static func readCommand(_ path: String) -> String {
        "cat \(shq(path)) 2>/dev/null"
    }
    /// 存回去：**先备份**（`<file>.yxi-bak-<时间戳>`）再写。
    /// ⚠️ json 的语法校验放在界面层做 —— 一个语法错就能让 Claude Code 起不来。
    public static func backupCommand(_ path: String, stamp: Int) -> String {
        let p = shq(path)
        return "cp \(p) \(p).yxi-bak-\(stamp) 2>/dev/null || true"
    }
    /// 写回之前的把关。**结构化格式坏了绝不写** ——
    /// `~/.claude/settings.json` 里一个多余的逗号就能让 Claude Code 起不来，
    /// 而那是在服务器上，用户在手机上根本救不回来。
    ///
    /// 返回 nil = 可以写；返回字符串 = 拒绝的理由（直接给用户看）。
    /// ⚠️ 只认 json；toml/md/txt 没有便宜的校验器，放行。
    public static func validationError(path: String, text: String) -> String? {
        guard path.hasSuffix(".json") else { return nil }
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "空文件，没保存"
        }
        // ⚠️ **不能只靠 JSONSerialization**：Linux 的 swift-corelibs-foundation
        // 会**收下**尾逗号（`{"a":1,}`），而 iOS 上的 Darwin 版拒绝。
        // 于是同一份代码两边行为不一样 —— 一个安全检查不能这样。
        // 尾逗号又恰恰是手改 json 最常犯的错，所以这里自己认一遍，两边一致。
        if let bad = trailingCommaIndex(text) {
            let line = text.prefix(bad).filter { $0 == "\n" }.count + 1
            return "JSON 格式不对，没保存：第 \(line) 行有多余的逗号"
        }
        do {
            _ = try JSONSerialization.jsonObject(with: Data(text.utf8), options: [.fragmentsAllowed])
            return nil
        } catch {
            return "JSON 格式不对，没保存：" + String(error.localizedDescription.prefix(60))
        }
    }

    /// 找 `,` 后面（跳过空白）紧跟 `}` 或 `]` 的位置。**字符串字面量里的逗号不算**，
    /// 所以要顺带跟踪引号和转义。返回那个逗号的下标。
    private static func trailingCommaIndex(_ text: String) -> Int? {
        var inString = false
        var escaped = false
        var lastComma: Int?
        for (i, ch) in text.enumerated() {
            if escaped { escaped = false; continue }
            if inString {
                if ch == "\\" { escaped = true }
                else if ch == "\"" { inString = false }
                continue
            }
            switch ch {
            case "\"": inString = true; lastComma = nil
            case ",": lastComma = i
            case "}", "]": if lastComma != nil { return lastComma }
            case " ", "\t", "\n", "\r": continue          // 空白不打断「逗号后面」
            default: lastComma = nil
            }
        }
        return nil
    }

    private static func shq(_ p: String) -> String {
        "'" + p.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }
}
