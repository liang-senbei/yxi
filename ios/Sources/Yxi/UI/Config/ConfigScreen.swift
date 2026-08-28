import SwiftUI
import YxiKit

/// **配置** —— 分服务器、分工具（Claude Code / Codex）浏览那台机器上的 agent 配置：
/// 技能 / MCP / 子 agent / 命令 / 权限 / 钩子 / 记忆 / 插件。
///
/// ⚠️ **密钥不出服务器**：抓取脚本在服务器侧就把 env 的值、以及键名含
/// key/token/secret/password/auth 的值打码成 ••••，`.credentials.json` 根本不读。
/// ⚠️ 工具无关：哪台装了哪个才显示哪个。
struct ConfigScreen: View {

    @ObservedObject var app: AppState
    @State private var tools: [AgentConfig.Tool] = []
    @State private var expanded: Set<String> = []
    @State private var loading = true
    @State private var open: AgentConfig.Item?

    private var runner: ShellRunner? { app.live?.link.service as? ShellRunner }

    var body: some View {
        Group {
            if loading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if tools.isEmpty {
                EmptyNote(text: "这台机器上没找到配置。\n装了 Claude Code（~/.claude）或 Codex（~/.codex）才有。")
            } else {
                List {
                    ForEach(tools) { tool in
                        Section(tool.name) {
                            ForEach(tool.cats) { cat in
                                catRow(tool: tool, cat: cat)
                                if expanded.contains("\(tool.key)/\(cat.key)") {
                                    ForEach(cat.items) { item in
                                        Button { open = item } label: { itemRow(item) }
                                            .buttonStyle(.plain)
                                    }
                                }
                            }
                        }
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle("配置")
        .background(Yx.surface)
        .task { await reload() }
        .refreshable { await reload() }
        .sheet(item: $open) { item in
            ConfigDetail(item: item, runner: runner,
                          files: app.live?.link.service as? FileService)
        }
    }

    private func catRow(tool: AgentConfig.Tool, cat: AgentConfig.Cat) -> some View {
        let key = "\(tool.key)/\(cat.key)"
        return Button {
            if expanded.contains(key) { expanded.remove(key) } else { expanded.insert(key) }
        } label: {
            HStack {
                Text(cat.title).font(.system(size: 15)).foregroundStyle(Yx.onSurface)
                Spacer()
                Text("\(cat.items.count)").font(.mono(12)).foregroundStyle(Yx.dim)
                Image(systemName: expanded.contains(key) ? "chevron.down" : "chevron.right")
                    .font(.system(size: 12)).foregroundStyle(Yx.dim)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func itemRow(_ item: AgentConfig.Item) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(item.title).font(.mono(13)).foregroundStyle(Yx.onSurface)
            if !item.sub.isEmpty {
                Text(item.sub).font(.system(size: 11)).foregroundStyle(Yx.dim)
            }
        }
        .padding(.leading, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

    private func reload() async {
        guard let runner else { loading = false; return }
        loading = true
        defer { loading = false }
        if let raw = try? await runner.run(AgentConfig.gatherScript) {
            tools = AgentConfig.parse(raw)
        }
    }
}

/// 单个配置项的内容。**只读** —— 编辑要先解决「改坏了工具起不来」的兜底，
/// 安卓那边是「先备份 + json 先校验」，iOS 这边等界面稳了再接（`AgentConfig.backupCommand` 已就绪）。
private struct ConfigDetail: View {
    let item: AgentConfig.Item
    let runner: ShellRunner?
    /// 没有它就只能看不能改 —— 写走 SFTP（二进制安全），不拼 shell
    let files: FileService?

    @State private var text: String = "读取中…"
    @State private var editing = false
    @State private var draft = ""
    @State private var saving = false
    @State private var note: String?
    @Environment(\.dismiss) private var dismiss

    /// 内联展示的（比如 `settings.json` 里被打码的那几段）没有真实路径，改不了
    private var canEdit: Bool { !item.path.isEmpty && files != nil }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let note {
                    Text(note)
                        .font(.system(size: 13))
                        .foregroundStyle(note.hasPrefix("已保存") ? Yx.copper : Yx.error)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(Yx.low)
                }
                if editing {
                    TextEditor(text: $draft)
                        .font(.mono(12))
                        .foregroundStyle(Yx.onSurface)
                        .scrollContentBackground(.hidden)
                        .background(Yx.surface)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .padding(.horizontal, 10)
                } else {
                    ScrollView([.vertical, .horizontal]) {
                        Text(text)
                            .font(.mono(12))
                            .foregroundStyle(Yx.onSurface)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(16)
                    }
                }
            }
            .background(Yx.surface)
            .navigationTitle(item.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(editing ? "取消" : "关闭") {
                        if editing { editing = false; note = nil } else { dismiss() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if editing {
                        Button(saving ? "保存中…" : "保存") { save() }.disabled(saving)
                    } else if canEdit {
                        Button("编辑") { draft = text; editing = true; note = nil }
                    }
                }
            }
        }
        .task { await load() }
    }

    private func load() async {
        if !item.inline.isEmpty { text = item.inline; return }
        guard !item.path.isEmpty, let runner else { text = "（没有内容）"; return }
        text = (try? await runner.run(AgentConfig.readCommand(item.path))) ?? "读不到"
        if text.isEmpty { text = "（空文件）" }
    }

    /// **改坏一份配置的代价在服务器上，用户在手机上救不回来。** 所以两道保险：
    /// ① json 先校验，坏了根本不写；② 写之前 `cp` 一份 `.yxi-bak-<时间戳>`。
    private func save() {
        note = nil
        if let why = AgentConfig.validationError(path: item.path, text: draft) {
            note = why
            return
        }
        saving = true
        Task {
            defer { saving = false }
            guard let files else { note = "没连上，没保存"; return }
            let stamp = Int(Date().timeIntervalSince1970)
            // 备份失败不拦着保存（可能只是文件本来就不存在），但要让用户知道
            let backedUp = (try? await runner?.run(AgentConfig.backupCommand(item.path, stamp: stamp))) != nil
            do {
                try await files.writeFile(item.path, bytes: Data(draft.utf8))
                text = draft
                editing = false
                note = backedUp ? "已保存 · 旧版留在 .yxi-bak-\(stamp)" : "已保存"
            } catch {
                note = "写回失败：" + String(error.localizedDescription.prefix(60))
            }
        }
    }
}
