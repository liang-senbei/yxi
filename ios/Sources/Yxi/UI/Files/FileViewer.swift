import SwiftUI
import MarkdownUI
import YxiKit
#if canImport(WebKit)
import WebKit
#endif

private let IMAGES: Set<String> = ["png", "jpg", "jpeg", "gif", "webp", "bmp", "heic"]
/// 有两副面孔的：排好版的「阅读」和原文「源码」。
private let RENDERABLE: Set<String> = ["md", "html", "htm"]
private let TEXTISH: Set<String> = [
    "md", "txt", "json", "kt", "java", "py", "js", "ts", "tsx", "jsx", "sh", "bash", "zsh",
    "yml", "yaml", "toml", "ini", "conf", "cfg", "xml", "html", "htm", "css", "sql", "go", "rs",
    "c", "h", "cpp", "hpp", "rb", "php", "gradle", "kts", "properties", "env", "log", "csv",
    "swift", "m", "mm", "plist", "podspec",
]

/// 看一个远端文件。**只读。**
///
/// markdown 和 html 默认走**阅读模式**，可以切到源码 —— 手机上看 README 就该是排好版的，
/// 但改动之前你总想看一眼原文（PRD 附录 G）。html 的「阅读」= 真的按 CSS 渲染出来。
struct FileViewer: View {

    let files: FileService
    let path: String
    let onBack: () -> Void

    @State private var bytes: Data?
    @State private var error: String?
    @State private var source = false        // RENDERABLE 的「源码」开关
    @State private var truncated = false
    @State private var saving = false
    /// 落在「文件」里的那份，用来分享。存进相册的没有可分享的 URL —— 看 [savedNote]
    @State private var saved: URL?
    /// 存到哪儿去了（相册 / 「文件」）。⚠️ 必须报出去：iOS 上这两条路的结果差很远，
    /// 用户去哪儿找那个文件全看这一句
    @State private var savedNote: String?

    private var ext: String { Paths.extOf(path) }

    var body: some View {
        VStack(spacing: 0) {
            header
            if let b = bytes {
                body(of: b).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                Spacer()
            }
        }
        .task(id: path) { await loadFile() }
    }

    private var header: some View {
        HStack(spacing: 10) {
            Button(action: onBack) {
                Text("←").font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Yx.onSurface)
                    .padding(.horizontal, 15).padding(.vertical, 8)
                    .background(Yx.container, in: Capsule())
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 2) {
                Text(Paths.nameOf(path))
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Yx.onSurface).lineLimit(1)
                Text(subtitle)
                    .font(.mono(12))
                    .foregroundStyle(error != nil ? Yx.error : Yx.dim)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)

            saveButton
            if RENDERABLE.contains(ext), bytes != nil {
                Button { source.toggle() } label: {
                    Text(source ? "源码" : "阅读")
                        .font(.system(size: 13))
                        .foregroundStyle(source ? Yx.copper : Yx.muted)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .background(source ? Yx.high : Yx.container, in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 14).padding(.top, 12).padding(.bottom, 8)
    }

    private var subtitle: String {
        if let error { return error }
        guard let b = bytes else { return "读取中…" }
        return humanSize(Int64(b.count)) + (truncated ? " · 已截断" : "")
    }

    /// 存到手机。**走 [SaveToPhone]，跟实验室那条路是同一套** ——
    /// 以前这里自己往 Documents 写，于是「文件模式里存一张图不会进相册、实验室里会」，
    /// 同一个 App 里两种行为，用户只会觉得坏了。
    ///
    /// ⚠️ **跟安卓不一样，而且只能不一样**：安卓写 MediaStore（图进相册、别的进「下载」目录）；
    /// iOS 没有那种共享下载目录，App 只能写自己的沙盒。图片 / 视频进相册，
    /// 其余落在 App 的 Documents 里（`UIFileSharingEnabled` 让它在「文件」App 里可见），
    /// 再用系统分享面板让你自己送去 iCloud / 微信。这是 iOS 上的原生做法，不是将就。
    @ViewBuilder private var saveButton: some View {
        if let saved {
            ShareLink(item: saved) { savePill((savedNote ?? "已保存") + " · 分享") }
        } else if let savedNote {
            // 进了相册，没有可分享的 URL —— 只报结果
            savePill(savedNote)
        } else {
            Button {
                saving = true
                Task { await save() }
            } label: {
                savePill(saving ? "保存中…" : "保存")
            }
            .buttonStyle(.plain)
            .disabled(saving)
        }
    }

    private func savePill(_ title: String) -> some View {
        Text(title)
            .font(.system(size: 13)).foregroundStyle(Yx.copper)
            .padding(.horizontal, 14).padding(.vertical, 8)
            .background(Yx.container, in: Capsule())
    }

    @ViewBuilder private func body(of b: Data) -> some View {
        if IMAGES.contains(ext) {
            ImageBody(data: b)
        } else if ext == "md" && !source {
            ScrollView {
                Markdown(String(decoding: b, as: UTF8.self))
                    .markdownTheme(.yxiFile)
                    // 图片路径是相对**这份 md 所在的目录**的，得走 SFTP 去取
                    .markdownImageProvider(SftpImages(files: files, baseDir: Paths.dirOf(path)))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 18).padding(.top, 4).padding(.bottom, 28)
            }
        } else if (ext == "html" || ext == "htm") && !source {
            HtmlBody(html: String(decoding: b, as: UTF8.self))
        } else if ext == "json" {
            JsonBody(text: String(decoding: b, as: UTF8.self))
        } else if TEXTISH.contains(ext) || looksTextual(b) {
            CodeBody(text: String(decoding: b, as: UTF8.self), ext: ext)
        } else {
            Text("二进制文件，不显示")
                .font(.system(size: 15)).foregroundStyle(Yx.dim)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    // MARK: 取数

    private func loadFile() async {
        bytes = nil; error = nil; saved = nil; savedNote = nil
        let limit = IMAGES.contains(ext) ? 8 << 20 : 1 << 20
        do {
            let data = try await files.readFile(path, max: limit)
            bytes = data
            truncated = data.count >= limit
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// ⚠️ 存的是**预览那份**（有上限）还是整个文件？—— 重新完整读一次，
    /// 否则大文件会存下一个被截断的残件，而用户根本看不出来。
    private func save() async {
        defer { saving = false }
        let name = Paths.nameOf(path)
        do {
            let data = try await files.readFile(path, max: 64 << 20)
            switch try await SaveToPhone.save(data, as: name) {
            case .photos:
                savedNote = "已存到相册"; saved = nil
            case .documents(let url):
                savedNote = "已存到「文件」"; saved = url
            }
        } catch {
            self.error = "保存失败：\(error.localizedDescription)"
        }
    }
}

/// 没有扩展名的文件（Makefile、Dockerfile、脚本）也该能看 —— 抽样看有没有 NUL 字节。
func looksTextual(_ b: Data) -> Bool {
    !b.prefix(4000).contains(0)
}

/// JSON 折叠树。**默认只展开第一层** —— 手机屏幕小，一个几千行的配置全铺开等于没法看。
///
/// 树怎么铺、折叠状态怎么算全在 [JsonTree]（YxiKit，有测试）；这儿只负责画一行。
/// 以前这里是 pretty-print，一个 1000 行的 `settings.json` 排完版还是 1000 行。
private struct JsonBody: View {
    let text: String
    /// ⚠️ 根的 path 是空串，所以初值 `[""]` = 只展开第一层
    @State private var expanded: Set<String> = [""]

    var body: some View {
        if let rows = JsonTree.rows(text, expanded: expanded) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(rows) { row($0) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14).padding(.top, 4).padding(.bottom, 28)
            }
        } else {
            // 解析不了就退回纯文本，别硬画一棵歪树 ——
            // 真实的 `.json` 常常是 JSONL、带注释的 JSON5，或者被 1 MB 上限截断了半截
            // （截断这件事顶栏那行小字已经说了）
            CodeBody(text: text, ext: "json")
        }
    }

    private func row(_ r: JsonTree.Row) -> some View {
        Button {
            if expanded.contains(r.path) { expanded.remove(r.path) } else { expanded.insert(r.path) }
        } label: {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                // 不可折的也占一个字位，否则同一层的键会左右错开
                Text(r.foldable ? (expanded.contains(r.path) ? "▾" : "▸") : " ")
                    .font(.mono(12)).foregroundStyle(Yx.dim)
                if !r.key.isEmpty {
                    Text(r.key).font(.mono(12)).foregroundStyle(Yx.copper)
                    Text(":").font(.mono(12)).foregroundStyle(Yx.dim)
                }
                Text(r.value)
                    .font(.mono(12))
                    .foregroundStyle(r.foldable ? Yx.muted : valueColor(r.value))
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
            .padding(.leading, CGFloat(r.depth) * 14)
            .padding(.vertical, 3)
        }
        .buttonStyle(.plain)
        .disabled(!r.foldable)
    }

    private func valueColor(_ v: String) -> Color {
        if v.hasPrefix("\"") { return Yx.addFg }
        if v == "true" || v == "false" || v == "null" { return Yx.teal }
        if let c = v.first, c.isNumber || c == "-" { return Yx.teal }
        return Yx.onSurfaceVar
    }
}

/// ⚠️ 聊天里的附件预览也用这一份，别再抄一遍。
struct ImageBody: View {
    let data: Data
    var body: some View {
        #if canImport(UIKit)
        if let img = UIImage(data: data) {
            ScrollView([.horizontal, .vertical]) {
                Image(uiImage: img)
                    .resizable().scaledToFit()
                    .frame(maxWidth: .infinity)
                    .padding(12)
            }
        } else {
            Text("这张图解不开")
                .font(.system(size: 15)).foregroundStyle(Yx.dim)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        #else
        Text("这张图解不开")
            .font(.system(size: 15)).foregroundStyle(Yx.dim)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        #endif
    }
}

/// 代码。**横着也要能滚**：折行读代码很痛苦，长管道和长路径本来就是一行。
///
/// 高亮怎么分类在 [Highlight]（YxiKit，有测试），这儿只把类别换成颜色。
private struct CodeBody: View {
    let text: String
    let ext: String

    /// ⚠️ 超过这个大小就**不上色，直接给原文**。上色要把整份文件切成几千段再拼成
    /// `AttributedString`，几百 KB 的文件上光是拼就够卡一下 —— 而**没颜色的代码
    /// 总好过界面卡住**。这个上限只影响颜色，一个字都不会少。
    /// ponytail: 真要给大文件上色，就按屏幕可见范围切片再高亮。
    private static let colorLimit = 200 << 10

    var body: some View {
        ScrollView([.horizontal, .vertical]) {
            Text(colored)
                .font(.mono(12))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
        }
    }

    private var colored: AttributedString {
        guard text.utf8.count <= Self.colorLimit else {
            var plain = AttributedString(text)
            plain.foregroundColor = Yx.onSurfaceVar
            return plain
        }
        var out = AttributedString()
        for seg in Highlight.segments(text, ext: ext) {
            var piece = AttributedString(String(seg.text))
            piece.foregroundColor = color(seg.kind)
            out.append(piece)
        }
        return out
    }

    /// 取值跟安卓的 `Highlight.kt` 一一对齐（关键字铜色 / 字符串绿 / 数字青 / 注释暗）
    private func color(_ kind: Highlight.Kind?) -> Color {
        switch kind {
        case .keyword: return Yx.copper
        case .string: return Yx.addFg
        case .number: return Yx.teal
        case .comment: return Yx.dim
        case nil: return Yx.onSurfaceVar
        }
    }
}

/// 让 markdown 里的图片走 SFTP 读回来。
///
/// ⚠️ **相对路径是相对「这份 md 所在的目录」的**，解析歪了界面上只表现为「图裂了」，
/// 看不出是路径错还是文件真没有。[Paths.resolve] 有测试盯着（`PathsTests`）。
///
/// ⚠️ **http(s) 直接放弃**：这条路上根本没有网络，只有一条 SSH 连接
/// （跟 [HtmlBody] 里 baseURL 传 nil 是同一个道理）。段落中间的行内图（badge 那种）
/// 走的是库的另一条路 `InlineImageProvider`，它只会去网络取 —— 同样取不到，
/// 那条我们没接管：README 顶上的 badge 本来就是 http 的。
private struct SftpImages: ImageProvider {
    let files: FileService
    let baseDir: String

    @ViewBuilder func makeImage(url: URL?) -> some View {
        // scheme 非空 = http(s)/data，取不到；`relativePath` 是**解过百分号编码**的，
        // 文件名里有空格或中文时不能用 absoluteString
        if let url, url.scheme == nil, !url.relativePath.isEmpty {
            SftpImage(files: files, path: Paths.resolve(base: baseDir, ref: url.relativePath))
        }
    }
}

private struct SftpImage: View {
    let files: FileService
    let path: String

    @State private var data: Data?
    @State private var failed: String?

    var body: some View {
        content
            .task(id: path) {
                data = nil; failed = nil
                do { data = try await files.readFile(path, max: 8 << 20) }
                catch { failed = error.localizedDescription }
            }
    }

    @ViewBuilder private var content: some View {
        #if canImport(UIKit)
        if let data, let img = UIImage(data: data) {
            Image(uiImage: img)
                .resizable().scaledToFit()
                .frame(maxWidth: .infinity)
        } else if data != nil {
            note("这张图解不开：\(Paths.nameOf(path))")
        } else if let failed {
            // 取不到就**说清楚为什么**，不要留一个空白等用户猜
            note("图读不到 \(path)：\(failed)")
        } else {
            note("读取中…")
        }
        #else
        note("这张图解不开")
        #endif
    }

    private func note(_ s: String) -> some View {
        Text(s).font(.mono(11)).foregroundStyle(Yx.dim)
    }
}

extension Theme {
    /// 文件模式里的 markdown = 聊天那套 `.yxi`，外加**宽表能横滑**。
    ///
    /// ⚠️ 病根跟安卓一样（`MarkdownTable.kt`）：库默认把表格塞进屏宽，
    /// 列一多每格就剩几个字，用户原话「表格里全是省略号」。
    static let yxiFile = Theme.yxi
        .table { c in
            ScrollView(.horizontal, showsIndicators: false) { c.label }
                // ⚠️ 不加这句，横滑容器在竖滚里会去抢一整屏高度（嵌套 ScrollView 的老毛病）
                .fixedSize(horizontal: false, vertical: true)
        }
        .tableCell { c in
            c.label
                // ⚠️ **必须是定宽 `width`，不能写 `maxWidth`。** 横滑给下来的宽度提案是「不限」，
                // `maxWidth` 只裁不换行 —— 长内容会被截掉，正是我们要修的那个毛病。
                // 定宽才会换行：窄表原样看全，宽表左右滑，一个字都不截。
                .frame(width: 160, alignment: .leading)
                .padding(.horizontal, 10).padding(.vertical, 8)
                .markdownTextStyle { FontWeight(c.row == 0 ? .semibold : .regular) }
                // 表头单独一层底色。横滑里画横线会算歪，用面分隔（M3 那套）
                .background(c.row == 0 ? Yx.high : Color.clear)
        }
}

/// 按 CSS 渲染的 html。用系统自带的 WebView —— 手机上本来就有一个浏览器引擎，
/// 没必要自己实现排版。
///
/// ⚠️ **JS 关着，而且不能开。** 这是从服务器上拉回来的任意文件，
/// 在 WebView 里跑它的脚本 = 让远端文件在 app 的进程里执行。
///
/// ⚠️ **baseURL 传 nil**：页面落在一个不透明源上，既加载不了外链，也没有同源可言。
/// 代价是外部的 `<link rel=stylesheet>` / `<img src>` 不会加载 ——
/// 这条路上根本没有网络，只有一条 SSH 连接。内联的 `<style>` 完全正常，
/// Claude 生成的那种单文件 html 就是内联的。
#if canImport(WebKit)
private struct HtmlBody: UIViewRepresentable {
    let html: String

    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        // 默认就是 false，这里显式写一次是怕以后有人「顺手」打开
        cfg.defaultWebpagePreferences.allowsContentJavaScript = false
        let web = WKWebView(frame: .zero, configuration: cfg)
        web.isOpaque = false
        web.backgroundColor = .clear
        web.scrollView.backgroundColor = .clear
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {
        web.loadHTMLString(html, baseURL: nil)
    }
}
#else
private struct HtmlBody: View {
    let html: String
    var body: some View { CodeBody(text: html) }
}
#endif
