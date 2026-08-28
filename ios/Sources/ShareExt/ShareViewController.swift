import Social
import UIKit
import UniformTypeIdentifiers

/// **分享到 Yxi** —— 从别的 App 把一段报错、一张截图、一个文件甩进来。
/// 对标安卓的 `ShareActivity`。
///
/// ⚠️⚠️ **跟安卓最大的差别：这里不发送，只暂存。** 理由写在 [Outbox] 上（读不到钥匙串里的
/// 私钥，而且扩展进程随时会被系统掐掉）。所以界面上**必须说清楚要等你开一次 Yxi** ——
/// 让人以为已经送到 Claude 那儿了，是这条路最容易犯、也最伤人的错。
///
/// ⚠️ 用 `SLComposeServiceViewController` 而不是自己搭一套 SwiftUI：它自带
/// 「一个文本框 + 取消/发布 + 系统观感」，正好是我们要的全部。
/// 安卓版那个「选哪个会话」的列表这里**做不出来** —— 列会话得先连上服务器。
class ShareViewController: SLComposeServiceViewController {

    /// nil = 这个安装包没有 App Group entitlement（CI 里的包就是这样，见 [AppGroup.container]）。
    private let outbox = Outbox.shared

    override func viewDidLoad() {
        super.viewDidLoad()
        placeholder = outbox == nil
            ? "这个安装包没开 App Group，分享用不了"
            : "说点什么（下次打开 Yxi 时送进会话）"
    }

    /// 没有容器就别让人白按一下「发布」。只有附件没正文也算数 —— 图和文件本身就是内容。
    override func isContentValid() -> Bool { outbox != nil }

    override func didSelectPost() {
        guard let outbox else { return complete() }
        let providers = (extensionContext?.inputItems as? [NSExtensionItem])?
            .flatMap { $0.attachments ?? [] } ?? []

        // ⚠️ `[self]`：Task 的闭包是逃逸的，类里用隐式 self 直接编不过
        // （requires explicit use of 'self'）。这类错只有 macOS runner 才会告诉你。
        Task { [self] in
            // ⚠️ `note` 在 Task **里面**声明。放外面就成了「被并发闭包捕获的可变变量」，
            // 开严格并发检查那天会从警告变成错误。
            var note = contentText ?? ""
            do {
                let dir = try outbox.begin()
                for p in providers {
                    // 纯文字：`contentText` 通常已经预填过了，只在它确实没带上时才补。
                    if p.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                        if let s = await loadText(p, .plainText), !note.contains(s) {
                            note = note.isEmpty ? s : note + "\n" + s
                        }
                        continue
                    }
                    // 网页链接。⚠️ 要先排掉 fileURL —— 它也 conform 到 public.url，
                    // 当成链接就把用户分享的文件丢了。
                    if p.hasItemConformingToTypeIdentifier(UTType.url.identifier),
                       !p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                        if let s = await loadText(p, .url) { note = note.isEmpty ? s : note + "\n" + s }
                        continue
                    }
                    await stage(p, into: dir)
                }
                try outbox.commit(dir, note: note)
                complete()
            } catch {
                fail(error)
            }
        }
    }

    // MARK: - 取内容

    /// 文字和链接都走这条：`loadItem` 回调给的可能是 String / URL / Data，三种都见过。
    private nonisolated func loadText(_ p: NSItemProvider, _ type: UTType) async -> String? {
        await withCheckedContinuation { (k: CheckedContinuation<String?, Never>) in
            p.loadItem(forTypeIdentifier: type.identifier, options: nil) { item, _ in
                switch item {
                case let s as String: k.resume(returning: s)
                case let u as URL:    k.resume(returning: u.absoluteString)
                case let d as Data:   k.resume(returning: String(data: d, encoding: .utf8))
                default:              k.resume(returning: nil)
                }
            }
        }
    }

    /// ⚠️⚠️ `loadFileRepresentation` 给的那个临时文件**在回调返回后就被删掉**，
    /// 必须在回调里当场拷走。这个坑很安静：调试时偶尔还读得到，上手机就变成空文件。
    ///
    /// ⚠️ 用 `public.item` 而不是逐个列 png/jpeg/pdf/…：任何附件都 conform 到它，
    /// 「用户分享了个我们没列进来的类型」于是不会变成一次静悄悄的丢弃。
    private nonisolated func stage(_ p: NSItemProvider, into dir: URL) async {
        await withCheckedContinuation { (k: CheckedContinuation<Void, Never>) in
            p.loadFileRepresentation(forTypeIdentifier: UTType.item.identifier) { url, _ in
                defer { k.resume() }
                guard let url else { return }
                var dst = dir.appendingPathComponent(AppGroup.safeName(url.lastPathComponent))
                // 一次分享多张图，名字常常一模一样（IMG_0001.jpg）——撞了就加个前缀，别覆盖。
                if FileManager.default.fileExists(atPath: dst.path) {
                    dst = dir.appendingPathComponent("\(UUID().uuidString.prefix(4))-\(dst.lastPathComponent)")
                }
                try? FileManager.default.copyItem(at: url, to: dst)
            }
        }
    }

    // MARK: - 收尾

    private func complete() {
        extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
    }

    /// ⚠️ 存不下就得说。默默 `completeRequest` 一走，用户看到的是分享面板正常收起，
    /// 也就是「成功了」—— 而东西根本不在。
    private func fail(_ error: Error) {
        let a = UIAlertController(title: "没存下来",
                                  message: "\(error.localizedDescription)\n什么都没送出去。",
                                  preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "好", style: .default) { [weak self] _ in self?.complete() })
        present(a, animated: true)
    }
}
