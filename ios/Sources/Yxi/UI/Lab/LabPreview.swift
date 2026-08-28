import SwiftUI
import YxiKit
#if canImport(WebKit)
import WebKit
#endif
#if canImport(UIKit)
import UIKit
import ImageIO
#endif

/// 实验室条目的预览。按 `type` 分流，跟安卓 `LabItemPreview` 一一对应：
/// html→WebView（JS 开着）、gif→会动的、image/svg→静态图、其它→文字。
///
/// ⚠️ **这里的 JS 是开着的，跟文件模式的查看器相反**，因为两者的来源不同：
/// 文件模式看的是服务器上**任意**文件；实验室里的是我们自己那条链路推上来的产物，
/// 而那些 html 本来就是靠 JS 动起来的（图表、动画）。
struct LabPreview: View {

    let item: Lab.Item
    let runner: ShellRunner?

    @State private var data: Data?
    @State private var text: String?
    @State private var failed = false

    var body: some View {
        Group {
            switch item.type {
            case "html":
                if let text {
                    LabWeb(html: text).frame(height: 320).clipShape(RoundedRectangle(cornerRadius: 12))
                } else { placeholder }
            case "gif":
                if let data {
                    AnimatedImage(data: data).clipShape(RoundedRectangle(cornerRadius: 12))
                } else { placeholder }
            case "image", "svg":
                if let data { StillImage(data: data) } else { placeholder }
            default:
                Text(item.desc.isEmpty ? item.title : item.desc)
                    .font(.system(size: 15)).foregroundStyle(Yx.onSurface)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .task(id: item.id) { await load() }
    }

    @ViewBuilder private var placeholder: some View {
        HStack {
            Spacer()
            if failed {
                Text("这个预览拉不下来").font(.system(size: 13)).foregroundStyle(Yx.dim)
            } else {
                ProgressView().tint(Yx.copper)
            }
            Spacer()
        }
        .frame(height: 120)
    }

    private func load() async {
        guard let runner else { failed = true; return }
        switch item.type {
        case "html":
            let t = try? await runner.run(Lab.textCommand(item.file))
            // ⚠️ 命令带 `2>/dev/null` 且 exec 不因非零退出码抛异常 ——
            // 文件不存在/没权限拿回来的是**空串，不是错误**。
            // 不把空串当失败的话，用户看到一个白框，什么也不说。
            if let t, !t.isEmpty { text = t } else { failed = true }
        case "gif", "image", "svg":
            // ⚠️ 预览走 base64（一条 shell 命令就够）；**保存原文件走 SFTP**，
            // 见 LabSaveButton —— base64 把大文件塞进一条命令的输出里，
            // 视频那种尺寸会崩，而预览本来就不需要原尺寸。
            let b64 = (try? await runner.run(Lab.bytesCommand(item.file))) ?? ""
            let clean = b64.trimmingCharacters(in: .whitespacesAndNewlines)
            // ⚠️ **`Data(base64Encoded: "")` 返回的是 0 字节，不是 nil。**
            // 所以「拿不到文件」在这里长得跟「成功拿到空图」一模一样，
            // 判 `data == nil` 永远为假 —— 写好的「这个预览拉不下来」一辈子不显示，
            // 用户看到的是永远空白的一块。要判**有没有字节**。
            let bytes = Data(base64Encoded: clean, options: .ignoreUnknownCharacters)
            if let bytes, !bytes.isEmpty { data = bytes } else { failed = true }
        default:
            break
        }
    }
}

// MARK: - 会动的 GIF

#if canImport(UIKit)
/// SwiftUI 的 `Image` 只画 GIF 的第一帧 —— 静止的 GIF 等于没有 GIF。
/// 用 Core Graphics 自带的 `CGAnimateImageDataWithBlock`（iOS 13+）：
/// 系统按帧延时自己回调，不用引任何第三方库，也不用自己写定时器。
struct AnimatedImage: UIViewRepresentable {
    let data: Data

    func makeUIView(context: Context) -> UIImageView {
        let v = UIImageView()
        v.contentMode = .scaleAspectFit
        v.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        CGAnimateImageDataWithBlock(data as CFData, nil) { _, cgImage, _ in
            v.image = UIImage(cgImage: cgImage)
        }
        return v
    }

    func updateUIView(_ v: UIImageView, context: Context) {}
}

struct StillImage: View {
    let data: Data
    var body: some View {
        if let img = UIImage(data: data) {
            Image(uiImage: img)
                .resizable().scaledToFit()
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 12))
        } else {
            Text("这张图解不开").font(.system(size: 13)).foregroundStyle(Yx.dim)
        }
    }
}
#else
struct AnimatedImage: View { let data: Data; var body: some View { EmptyView() } }
struct StillImage: View { let data: Data; var body: some View { EmptyView() } }
#endif

// MARK: - html 预览

#if canImport(WebKit)
private struct LabWeb: UIViewRepresentable {
    let html: String

    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.defaultWebpagePreferences.allowsContentJavaScript = true
        let web = WKWebView(frame: .zero, configuration: cfg)
        web.isOpaque = false
        web.backgroundColor = .clear
        web.scrollView.backgroundColor = .clear
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {
        // baseURL 传 nil：不透明源，加载不了外链（这条路上没有网络，只有一条 SSH）
        web.loadHTMLString(html, baseURL: nil)
    }
}
#else
private struct LabWeb: View { let html: String; var body: some View { EmptyView() } }
#endif

// MARK: - 保存原文件

/// 「保存原图 / 保存 GIF / 保存视频」。
///
/// ⚠️ **走 SFTP 取原文件，不是把预览那份存下来。** 预览是 base64 走 shell 拿的，
/// 一条命令的输出塞不下一个视频；而且用户点「保存原图」要的就是原画，
/// 存下一份缩水的等于骗人。
struct LabSaveButton: View {

    let item: Lab.Item
    let runner: ShellRunner?
    let files: FileService?

    @State private var state: Step = .idle
    /// ⚠️ 别叫 State —— 会把 SwiftUI 的 `@State` 遮掉
    /// （编译器原话：enum 'State' cannot be used as an attribute）
    private enum Step: Equatable {
        case idle, working, done(String), failed(String)
    }

    var body: some View {
        Group {
            if case .done(_) = state, let url = savedURL {
                ShareLink(item: url) { label }
            } else {
                Button { save() } label: { label }
                    .buttonStyle(.plain)
                    // 存进相册那条路没有可分享的 URL，所以还是这颗按钮 ——
                    // 但已经存完了就别让它再点一次
                    .disabled(state == .working || isDone)
            }
        }
    }

    @State private var savedURL: URL?

    private var label: some View {
        Text(title)
            .font(.system(size: 13, weight: .medium))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 11)
            .background(Yx.high, in: Capsule())
            .foregroundStyle(isError ? Yx.error : Yx.copper)
    }

    private var isError: Bool { if case .failed = state { return true }; return false }
    private var isDone: Bool { if case .done = state { return true }; return false }

    private var title: String {
        switch state {
        case .working: return "保存中…"
        // 落在「文件」里的会换成 ShareLink（那时 label 由它渲染），
        // 存进相册的留在这儿，只报结果
        case .done(let place): return savedURL == nil ? place : place + " · 分享"
        case .failed(let why): return why
        case .idle:
            switch item.type {
            case "image", "svg": return "保存原图"
            case "gif": return "保存 GIF"
            case "video": return "保存视频"
            default: return "下载到本地"
            }
        }
    }

    private func save() {
        state = .working
        Task {
            guard let runner, let files else { state = .failed("没连上"); return }
            // `Lab.dir` 里是 `$HOME`，SFTP 不认 shell 变量 —— 让服务器自己展开
            guard let home = try? await runner.run("echo \(Lab.dir)") else {
                state = .failed("找不到实验室目录"); return
            }
            let dir = home.trimmingCharacters(in: .whitespacesAndNewlines)
            let name = Paths.nameOf(item.file).isEmpty ? "yxi-\(item.id)" : Paths.nameOf(item.file)
            do {
                let data = try await files.readFile("\(dir)/\(item.file)", max: 256 << 20)
                switch try await SaveToPhone.save(data, as: name) {
                case .photos:
                    state = .done("已存到相册"); savedURL = nil
                case .documents(let url):
                    state = .done("已存到「文件」"); savedURL = url
                }
            } catch {
                state = .failed("保存失败")
            }
        }
    }
}
