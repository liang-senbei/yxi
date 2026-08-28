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
    @State private var saved: URL?

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

    /// 存到手机。
    ///
    /// ⚠️ **跟安卓不一样，而且只能不一样**：安卓写 MediaStore（图进相册、别的进「下载」目录）；
    /// iOS 没有那种共享下载目录，App 只能写自己的沙盒。所以落在 App 的 Documents 里
    /// （`UIFileSharingEnabled` 让它在「文件」App 里可见），再用系统分享面板
    /// 让你自己送去相册 / iCloud / 微信。这是 iOS 上的原生做法，不是将就。
    @ViewBuilder private var saveButton: some View {
        if let saved {
            ShareLink(item: saved) {
                Text("分享")
                    .font(.system(size: 13)).foregroundStyle(Yx.copper)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(Yx.container, in: Capsule())
            }
        } else {
            Button {
                saving = true
                Task { await save() }
            } label: {
                Text(saving ? "保存中…" : "保存")
                    .font(.system(size: 13)).foregroundStyle(Yx.copper)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(Yx.container, in: Capsule())
            }
            .buttonStyle(.plain)
            .disabled(saving)
        }
    }

    @ViewBuilder private func body(of b: Data) -> some View {
        if IMAGES.contains(ext) {
            ImageBody(data: b)
        } else if ext == "md" && !source {
            ScrollView {
                Markdown(String(decoding: b, as: UTF8.self))
                    .markdownTheme(.yxi)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 18).padding(.top, 4).padding(.bottom, 28)
            }
        } else if (ext == "html" || ext == "htm") && !source {
            HtmlBody(html: String(decoding: b, as: UTF8.self))
        } else if TEXTISH.contains(ext) || looksTextual(b) {
            CodeBody(text: prettyIfJSON(b))
        } else {
            Text("二进制文件，不显示")
                .font(.system(size: 15)).foregroundStyle(Yx.dim)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    // MARK: 取数

    private func loadFile() async {
        bytes = nil; error = nil; saved = nil
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
        guard
            let data = try? await files.readFile(path, max: 64 << 20),
            let dir = try? FileManager.default.url(
                for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        else { error = "保存失败"; return }
        let url = dir.appendingPathComponent(name)
        do {
            try data.write(to: url, options: .atomic)
            saved = url
        } catch {
            self.error = "保存失败：\(error.localizedDescription)"
        }
    }
}

/// 没有扩展名的文件（Makefile、Dockerfile、脚本）也该能看 —— 抽样看有没有 NUL 字节。
func looksTextual(_ b: Data) -> Bool {
    !b.prefix(4000).contains(0)
}

/// json 排一下版再看。排不了（截断了 / 不是合法 json）就原样显示，别报错。
private func prettyIfJSON(_ b: Data) -> String {
    let raw = String(decoding: b, as: UTF8.self)
    guard Paths.extOf("x.json") == "json",
          let obj = try? JSONSerialization.jsonObject(with: b),
          let pretty = try? JSONSerialization.data(
            withJSONObject: obj, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
    else { return raw }
    return String(decoding: pretty, as: UTF8.self)
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

private struct CodeBody: View {
    let text: String
    var body: some View {
        ScrollView([.horizontal, .vertical]) {
            Text(text)
                .font(.mono(12))
                .foregroundStyle(Yx.onSurfaceVar)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
        }
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
