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
    private static func shq(_ p: String) -> String {
        "'" + p.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }
}
