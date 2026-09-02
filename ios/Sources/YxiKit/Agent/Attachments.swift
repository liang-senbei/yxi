import Foundation

/// 用户消息最前面那几行附件引用：`[图片1] /root/src/tmp/<项目>/xxx.jpg`。
///
/// 发送时由 `attachmentHeader` 贴上去（`RemoteHost`），Claude 照着路径自己去读；
/// 回头渲染那条消息时再把它们摘出来画成缩略图 —— 一条又长又没用的路径占四行，
/// 用户想看的是**那张图**。跟安卓 `Attachments.parseRefs` 一个规则、一套测试。
public enum Attachments {

    public struct Ref: Equatable, Sendable {
        /// 界面上那个名字（`图片1` / `附件2`）
        public let label: String
        /// 远端绝对路径
        public let path: String
        public let isImage: Bool
        /// 文件名 —— 拉不到图时显示它，比整条路径有用
        public var name: String { (path as NSString).lastPathComponent }
        public init(label: String, path: String, isImage: Bool) {
            self.label = label; self.path = path; self.isImage = isImage
        }
    }

    /// ⚠️ 图片还是附件**按扩展名判**，不按标签判：标签是本地化的，
    /// 换个语言看历史消息就全认不出来了。
    static let imageExt: Set<String> = ["jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif"]

    private static let line = try! NSRegularExpression(pattern: "^\\[([^\\]]+)\\]\\s+(/\\S+)\\s*$")

    /// 把附件头从正文里摘出来。
    ///
    /// ⚠️ **只认开头连续的那几行。** 附件头永远在最前面，而正文里完全可能出现
    /// 同样长相的一行（比如用户在讲「[图片1] 是哪张」）—— 一路扫到底会把正文也吃掉。
    public static func parseRefs(_ text: String) -> (refs: [Ref], body: String) {
        let lines = text.components(separatedBy: "\n")
        var refs: [Ref] = []
        var i = 0
        while i < lines.count {
            let l = lines[i]
            guard let m = line.firstMatch(in: l, range: NSRange(l.startIndex..., in: l)),
                  let lr = Range(m.range(at: 1), in: l), let pr = Range(m.range(at: 2), in: l)
            else { break }
            let path = String(l[pr])
            let ext = (path as NSString).pathExtension.lowercased()
            refs.append(Ref(label: String(l[lr]), path: path, isImage: imageExt.contains(ext)))
            i += 1
        }
        if refs.isEmpty { return ([], text) }
        return (refs, lines[i...].joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines))
    }
}
