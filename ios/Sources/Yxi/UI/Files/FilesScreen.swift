import SwiftUI
import YxiKit

/// 文件模式：在任意 SSH 主机上翻文件。
///
/// ⚠️ **走 SFTP，服务器上不用装任何东西**（PRD 附录 H）。起点是会话的 cwd ——
/// 你正在看的那个会话在哪儿干活，文件模式就从哪儿开始，不用自己找路。
///
/// **只读。** 不做写和删：手机上误触的代价太高，而这个功能的价值是「看一眼」。
/// 跟安卓版 `FilesScreen.kt` 一一对应。
struct FilesScreen: View {

    let files: FileService
    let startDir: String

    @State private var dir = ""
    @State private var entries: [SFTP.Entry] = []
    @State private var status: String? = "连接中…"
    @State private var open: String?          // 正在看的文件
    @State private var jumping = false
    /// 最近去过的目录。⚠️ 只在内存里 —— 关掉就没了。要跨会话记住得落盘，那是另一件事
    @State private var recent: [String] = []

    var body: some View {
        Group {
            if let file = open {
                FileViewer(files: files, path: file) { open = nil }
            } else {
                browser
            }
        }
        .task { await resolveStart() }
    }

    // MARK: 目录浏览

    private var browser: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Text(status ?? "\(entries.count) 项")
                    .font(.mono(13))
                    .foregroundStyle(Yx.dim)
            }
            .padding(.horizontal, 18).padding(.top, 8).padding(.bottom, 4)

            crumbs
            list
        }
        .sheet(isPresented: $jumping) {
            JumpSheet(current: dir, recent: recent) { target in
                jumping = false
                Task { await jump(to: target) }
            }
        }
    }

    /// 面包屑：点哪一级跳哪一级
    private var crumbs: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                ForEach(Array(Paths.crumbs(dir).enumerated()), id: \.offset) { i, crumb in
                    if i > 0 {
                        Text("/").font(.system(size: 12)).foregroundStyle(Yx.dim)
                    }
                    Button {
                        Task { await load(crumb.path) }
                    } label: {
                        Text(crumb.name)
                            .font(.mono(13))
                            .foregroundStyle(crumb.path == dir ? Yx.onSurface : Yx.muted)
                            .padding(.horizontal, 11).padding(.vertical, 6)
                            .background(crumb.path == dir ? Yx.high : Yx.container, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
                // ⚠️ 必须有：/tmp 这种目录随便就是几百项，靠滚是找不到东西的
                Button { jumping = true } label: {
                    Text("⌖")
                        .font(.system(size: 13))
                        .foregroundStyle(Yx.copper)
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(Yx.container, in: Capsule())
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 14).padding(.bottom, 8)
        }
    }

    private var list: some View {
        List {
            if dir != "/" {
                row(icon: "↰", iconColor: Yx.copper, name: "上一级", nameColor: Yx.muted, size: nil) {
                    Task { await load(Paths.dirOf(dir)) }
                }
            }
            ForEach(entries, id: \.name) { e in
                row(
                    icon: e.isDir ? "▸" : "·",
                    iconColor: e.isDir ? Yx.copper : Yx.dim,
                    name: e.name + (e.isLink ? " ⇢" : ""),
                    nameColor: e.isDir ? Yx.onSurface : Yx.onSurfaceVar,
                    size: e.isDir ? nil : humanSize(e.size)
                ) {
                    let target = Paths.resolve(base: dir, ref: e.name)
                    if e.isDir { Task { await load(target) } } else { open = target }
                }
            }
            if entries.isEmpty && status == nil {
                Text("空目录")
                    .font(.system(size: 15)).foregroundStyle(Yx.dim)
                    .frame(maxWidth: .infinity).padding(.vertical, 40)
                    .listRowBackground(Color.clear).listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    private func row(
        icon: String, iconColor: Color, name: String, nameColor: Color,
        size: String?, tap: @escaping () -> Void
    ) -> some View {
        Button(action: tap) {
            HStack(spacing: 14) {
                Text(icon).font(.system(size: 17)).foregroundStyle(iconColor).frame(width: 14)
                Text(name).font(.system(size: 16)).foregroundStyle(nameColor).lineLimit(1)
                Spacer(minLength: 8)
                if let size {
                    Text(size).font(.mono(12)).foregroundStyle(Yx.dim)
                }
            }
            .contentShape(Rectangle())
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
        .listRowBackground(Color.clear)
        .listRowSeparatorTint(Yx.container)
    }

    // MARK: 动作

    /// 起点可能是 `~` 或不存在的路径 —— 解析失败就退到家目录，别把界面卡死
    private func resolveStart() async {
        guard dir.isEmpty else { return }
        let start = (try? await files.resolve(startDir)) ?? (try? await files.resolve(".")) ?? "/"
        await load(start)
    }

    private func load(_ path: String) async {
        dir = path
        status = nil
        do {
            entries = try await files.listDir(path)
            recent.removeAll { $0 == path }
            recent.insert(path, at: 0)
            if recent.count > 8 { recent.removeLast(recent.count - 8) }
        } catch {
            entries = []
            status = error.localizedDescription
        }
    }

    /// 输错了要说清楚，不能默默不动
    private func jump(to target: String) async {
        // ⚠️ `~` 手机这边展不开，交给服务器的 realpath
        guard let abs = try? await files.resolve(target) else {
            status = "去不了 \(target)"
            return
        }
        // 目录就进目录，文件就直接打开 —— 用「列得出来吗」判断，省一次 stat 往返
        if let listed = try? await files.listDir(abs) {
            dir = abs; entries = listed; status = nil
            recent.removeAll { $0 == abs }
            recent.insert(abs, at: 0)
        } else {
            dir = Paths.dirOf(abs)
            open = abs
        }
    }
}

/// 直接输入路径 + 最近去过的。**收藏没做** —— 那要落盘，等有真需求再说。
private struct JumpSheet: View {
    let current: String
    let recent: [String]
    let onGo: (String) -> Void

    @State private var text = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("/opt/workspace 或 ~/src", text: $text)
                        .font(.mono(14))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onSubmit { onGo(text.trimmingCharacters(in: .whitespaces)) }
                }
                if recent.count > 1 {
                    Section("最近") {
                        ForEach(recent.dropFirst().prefix(5), id: \.self) { r in
                            Button(r) { onGo(r) }
                                .font(.mono(13))
                                .foregroundStyle(Yx.muted)
                                .lineLimit(1)
                        }
                    }
                }
            }
            .navigationTitle("去哪儿")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("去") { onGo(text.trimmingCharacters(in: .whitespaces)) }
                }
            }
        }
        .onAppear { text = current }
        .presentationDetents([.medium])
    }
}
