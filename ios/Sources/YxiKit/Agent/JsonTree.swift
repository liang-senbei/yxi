import Foundation

/// JSON 折叠树。**默认只展开第一层** —— 手机屏幕小，一个几千行的配置全铺开等于没法看。
///
/// 解析不了就返回 nil，界面退回纯文本（很多 `.json` 其实是 JSONL、带注释的 JSON5，
/// 或者干脆是被 1 MB 上限**截断**了的半截文件）。
///
/// ⚠️ **住在 YxiKit**：展开/折叠这套「树 → 行」的逻辑是最容易写错又最难在界面上看出错的，
/// 放这儿才测得到（`JsonTreeTests`）。视图只剩「画一行」。
public enum JsonTree {

    /// 铺平后的一行。[path] 既是身份也是折叠状态的键（`/a/0/b` 这样）。
    public struct Row: Equatable, Sendable, Identifiable {
        public let path: String
        public let depth: Int
        /// 数组元素的 key 是下标；根是空
        public let key: String
        public let value: String
        /// 能点开（非空的对象/数组）
        public let foldable: Bool

        public var id: String { path }
    }

    /// - Parameter expanded: 已展开的 [Row.path] 集合。根的 path 是 `""`。
    /// - Returns: 解析不了返回 nil —— 这时别画树，直接给原文。
    public static func rows(_ text: String, expanded: Set<String>) -> [Row]? {
        guard let data = text.data(using: .utf8),
              // ⚠️ 不给 `.fragmentsAllowed`：光秃秃一个 `"abc"` 或 `3` 也是合法 JSON，
              // 但给它画一棵一行的树没有意义，当纯文本看更好
              let any = try? JSONSerialization.jsonObject(with: data)
        else { return nil }
        let root = JSON(any)
        switch root {
        case .object, .array:
            return flatten(root, path: "", key: "", depth: 0, expanded: expanded)
        default:
            return nil
        }
    }

    /// 树 → 行列表。**只有展开的分支才往下递归**，所以巨大的 JSON 也不会一次全建出来。
    private static func flatten(
        _ node: JSON, path: String, key: String, depth: Int, expanded: Set<String>
    ) -> [Row] {
        switch node {
        case let .object(o):
            var out = [Row(path: path, depth: depth, key: key, value: "{\(o.count)}",
                           foldable: !o.isEmpty)]
            if expanded.contains(path) {
                // ⚠️ **必须排序。** `JSONSerialization` 给回来的是 `[String: Any]`，
                // 没有顺序可言；Swift 的字典遍历顺序每个进程还不一样 ——
                // 不排的话同一个文件今天这个次序、明天那个次序。
                // 代价是丢掉文件里的原始顺序（`YxiKit.JSON.keys` 也是这么取舍的）。
                for k in o.keys.sorted() {
                    out += flatten(o[k] ?? .null, path: "\(path)/\(k)", key: k,
                                   depth: depth + 1, expanded: expanded)
                }
            }
            return out
        case let .array(a):
            var out = [Row(path: path, depth: depth, key: key, value: "[\(a.count)]",
                           foldable: !a.isEmpty)]
            if expanded.contains(path) {
                for (i, item) in a.enumerated() {
                    out += flatten(item, path: "\(path)/\(i)", key: "\(i)",
                                   depth: depth + 1, expanded: expanded)
                }
            }
            return out
        default:
            return [Row(path: path, depth: depth, key: key, value: leaf(node), foldable: false)]
        }
    }

    private static func leaf(_ node: JSON) -> String {
        switch node {
        case let .string(s): return "\"\(s)\""
        case let .bool(b): return b ? "true" : "false"
        case let .number(n): return number(n)
        default: return "null"
        }
    }

    /// ⚠️ `JSON` 里数字一律是 `Double`，直接印会把 `"port": 22` 显示成 `22.0` ——
    /// 端口号、行号、计数全是整数，多出来的 `.0` 每一行都在骗人。
    private static func number(_ n: Double) -> String {
        // 2^53 以外 Double 已经存不下准确整数，那时印 Double 的原样反而诚实
        if n == n.rounded(), abs(n) < 9_007_199_254_740_992 { return String(Int64(n)) }
        return String(n)
    }
}
