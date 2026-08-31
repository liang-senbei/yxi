import Foundation

/// 会话分组 —— 把几个会话编成一组，组里的 agent 能互相说话。
///
/// ⚠️ **分组表存在服务器上（`~/.yxi/groups.json`），不存在手机里。**
/// 因为读它的不只有手机，还有**组里的 agent 自己**（`yxi-hub who` 要回答
/// 「我同组有谁」）。存进手机本地的话，agent 永远看不到，
/// 「打通」就只是个视觉分类。换手机、多台手机也都还在。
///
/// ⚠️ **一个会话可以同时在好几个组里**（用户明确要的）。所以成员关系是
/// 多对多，`groupsOf` 返回的是「它所在的所有组」，不是一个。
///
/// 跟安卓端 `app.yxi.agent.Groups` 是**同一份契约**：同一个 JSON、同一个
/// `~/.yxi/groups.json`、同一个 `yxi-hub` 在读。两边任何一边改格式都会
/// 让分组静悄悄失效（UI 一切正常，agent 却说「你不在任何组里」），
/// 所以两边的测试里用的是**同一串样例 JSON**。
public enum Groups {

    /// 分组表。组名 → 成员会话名（带 `cc-` 前缀，跟 tmux 里一致）。
    public struct Table: Equatable, Sendable {
        public var groups: [String: [String]]

        public init(_ groups: [String: [String]] = [:]) { self.groups = groups }

        /// 这个会话在哪几个组里。
        public func groupsOf(_ session: String) -> [String] {
            groups.filter { $0.value.contains(session) }.keys.sorted()
        }

        /// 同组的其他人（跨它所在的全部组，去重）。
        public func mates(of session: String) -> [String] {
            var seen = Set<String>()
            var out: [String] = []
            for (_, members) in groups.sorted(by: { $0.key < $1.key }) where members.contains(session) {
                for m in members where m != session && !seen.contains(m) {
                    seen.insert(m); out.append(m)
                }
            }
            return out
        }

        public func withMember(_ group: String, _ session: String) -> Table {
            var g = groups
            var members = g[group] ?? []
            if !members.contains(session) { members.append(session) }
            g[group] = members
            return Table(g)
        }

        public func withoutMember(_ group: String, _ session: String) -> Table {
            var g = groups
            /// ⚠️ **成员空了也把组留着** —— 组是用户建的，不该因为人走光了就没了。
            g[group] = (g[group] ?? []).filter { $0 != session }
            return Table(g)
        }

        public func withoutGroup(_ group: String) -> Table {
            var g = groups; g.removeValue(forKey: group); return Table(g)
        }
    }

    private static let version = 1

    /// 读 `~/.yxi/groups.json`。
    ///
    /// ⚠️ **读不懂就当没有分组，绝不抛异常。** 这个文件是手机写的，但用户可能
    /// 手改过、或者被别的版本写过。为了一个坏掉的分组表让整个看板打不开，
    /// 是拿主功能给附加功能陪葬。
    public static func parse(_ raw: String) -> Table {
        let txt = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !txt.isEmpty, let data = txt.data(using: .utf8) else { return Table() }
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let g = obj["groups"] as? [String: Any] else { return Table() }
        var out: [String: [String]] = [:]
        for (k, v) in g {
            out[k] = (v as? [Any])?.compactMap { $0 as? String }.filter { !$0.isEmpty } ?? []
        }
        return Table(out)
    }

    public static func encode(_ t: Table) -> String {
        let obj: [String: Any] = ["v": version, "groups": t.groups]
        guard let d = try? JSONSerialization.data(withJSONObject: obj),
              let s = String(data: d, encoding: .utf8) else { return "{\"v\":1,\"groups\":{}}" }
        return s
    }

    /// 写回服务器的命令。
    ///
    /// ⚠️ 走 `cat > 临时文件 && mv`，不直接覆盖：mv 在同一个文件系统上是原子的，
    /// **半截文件永远不会被 agent 读到**。直接覆盖的话，写到一半被打断，
    /// `parse` 会当成「没有分组」—— 用户辛苦编的组静悄悄没了。
    public static func saveCommand(_ t: Table) -> String {
        let json = encode(t).replacingOccurrences(of: "'", with: "'\\''")
        return "mkdir -p \"$HOME/.yxi\" && printf '%s' '\(json)' > \"$HOME/.yxi/groups.json.tmp\" "
            + "&& mv \"$HOME/.yxi/groups.json.tmp\" \"$HOME/.yxi/groups.json\""
    }

    /// 「你被编进这个组了、同组有谁」—— 这是**「打通」发生的那一刻**。
    ///
    /// 不发这一句的话，分组对 agent 来说是不存在的：它不会主动去读 groups.json，
    /// 也就不知道自己有队友、更不知道能 `yxi-hub say` 找他们。
    ///
    /// - Returns: 每个成员一条 (会话名, 要发的话)；组里不足两人时返回空。
    public static func announcements(_ t: Table, group: String) -> [(String, String)] {
        let members = t.groups[group] ?? []
        guard members.count >= 2 else { return [] }
        return members.map { me in
            let mates = members.filter { $0 != me }.joined(separator: "、")
            return (me, "[Yxi 分组] 你被编进了「\(group)」组，同组还有：\(mates)。"
                + "给他们发消息用 `yxi-hub say <名字> \"内容\"`，"
                + "`yxi-hub who` 看当前组员。只能发给同组的。")
        }
    }
}
