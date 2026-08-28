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
    /// 读不到时的**真实原因**。空 = 读成功了（那时候空列表才真的是「没装」）
    @State private var note: String?

    private var runner: ShellRunner? { app.live?.link.service as? ShellRunner }

    var body: some View {
        Group {
            if loading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let note {
                // 读失败 —— 说真实原因，别拿「没装」去解释一个不知道的原因
                EmptyNote(text: note)
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
        // ⚠️ 跟实验室同一个坑：键挂在连接上，否则**连上之前**跑的那一次拿到 nil，
        // 界面就停在「没连上」再也不恢复。
        .task(id: app.live?.link.id) { await reload() }
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

    /// ⚠️ **「连不上」和「这台机器上没装」是两回事，不能说成同一句。**
    /// 原来失败就什么都不做，界面于是断言「这台机器上没找到配置」——
    /// 而真实原因可能是连接断了、脚本超时、或服务器上没有 python3。
    /// 拿一句确定的话去解释一个不知道的原因，比不解释更糟。
    private func reload() async {
        guard let runner else {
            loading = false
            note = "没连上，配置读不了。"
            return
        }
        loading = true
        defer { loading = false }
        do {
            tools = AgentConfig.parse(try await runner.run(AgentConfig.gatherScript))
            note = nil                      // ⚠️ 成功要清掉旧提示，否则重连后还挂着「没连上」
        } catch {
            note = "配置读不到：" + String(error.localizedDescription.prefix(50))
        }
    }
}

/// 单个配置项的内容。**可以改**，但有三道闸（顺序不能变）：
/// ① 没成功读到原文就不给改（否则会拿界面占位文字覆盖真配置）；
/// ② json 先校验，坏了根本不写；
/// ③ 写之前备份，**备份没成功就不写** —— 怕改坏了退不回去。
private struct ConfigDetail: View {
    let item: AgentConfig.Item
    let runner: ShellRunner?
    /// 没有它就只能看不能改 —— 写走 SFTP（二进制安全），不拼 shell
    let files: FileService?

    /// ⚠️⚠️ **必须把「还没读到」跟「读到了但是空的」分开。**
    /// 原来 text 直接从 "读取中…" 开始，读失败变 "读不到" ——
    /// 而这两个都是**界面占位文字**。点编辑时 `draft = text` 就把它们抄进草稿，
    /// 一保存，服务器上那份 CLAUDE.md 就变成三个汉字。
    /// 而且 .md/.toml 没有 json 那道校验兜底，直接覆盖。
    ///
    /// 更阴的是它不需要用户做错事：`cat` 读不到时返回空串**不算错误**
    /// （`2>/dev/null` + exec 不因非零退出码抛异常），界面会说「（空文件）」——
    /// 用户完全有理由在上面接着写。
    private enum Load: Equatable {
        case loading
        case ok(String)          // 真读到了，可以改
        case failed(String)      // 读不到 —— 只给看，不给改
    }
    @State private var load: Load = .loading
    @State private var editing = false
    @State private var draft = ""
    @State private var saving = false
    @State private var note: String?
    @Environment(\.dismiss) private var dismiss

    /// 内联展示的（被打码的那几段）没有真实路径，改不了；
    /// **没成功读到原文的也一律不给改** —— 否则等于拿占位文字覆盖真配置
    private var canEdit: Bool {
        guard case .ok = load else { return false }
        return !item.path.isEmpty && files != nil
    }

    private var shown: String {
        switch load {
        case .loading: return "读取中…"
        case .ok(let t): return t.isEmpty ? "（空文件）" : t
        case .failed(let why): return why
        }
    }

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
                        Text(shown)
                            .font(.mono(12))
                            .foregroundStyle({ if case .failed = load { Yx.error } else { Yx.onSurface } }())
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
                        Button("编辑") {
                            if case .ok(let t) = load { draft = t }
                            editing = true; note = nil
                        }
                    }
                }
            }
        }
        .task { await loadFile() }
    }

    /// ⚠️ **`cat` 读不到跟读到空文件长得一模一样**（都是空串，都不抛异常）。
    /// 所以在命令末尾加一句回执：没有它 = 没读到，有它 = 真读到了（哪怕内容是空）。
    private func loadFile() async {
        if !item.inline.isEmpty { load = .ok(item.inline); return }
        guard !item.path.isEmpty else { load = .failed("（没有内容）"); return }
        guard let runner else { load = .failed("没连上，读不到"); return }
        let marker = "__YXI_CAT_OK__"
        let cmd = AgentConfig.readCommand(item.path) + "; [ -f " + shq(item.path) + " ] && printf '%s' '\(marker)'"
        guard let out = try? await runner.run(cmd), out.hasSuffix(marker) else {
            load = .failed("读不到这个文件（可能已经被删了，或者没有权限）")
            return
        }
        load = .ok(String(out.dropLast(marker.count)))
    }

    private func shq(_ p: String) -> String {
        "'" + p.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }

    /// **改坏一份配置的代价在服务器上，用户在手机上救不回来。** 所以两道保险：
    /// ① json 先校验，坏了根本不写；② 写之前 `cp` 一份并**确认备份真的生成了**。
    private func save() {
        note = nil
        if let why = AgentConfig.validationError(path: item.path, text: draft) {
            note = why
            return
        }
        saving = true
        Task {
            defer { saving = false }
            guard let files, let runner else { note = "没连上，没保存"; return }
            let stamp = Int(Date().timeIntervalSince1970)
            // ⚠️ 备份**没成功就不写**。原来这里只看「命令跑通了没有」，
            // 而那条命令恒定成功 —— 等于告诉用户有回滚点，其实没有。
            let out = (try? await runner.run(AgentConfig.backupCommand(item.path, stamp: stamp))) ?? ""
            guard out.contains(AgentConfig.backupOK) else {
                note = "备份没成功，所以没有保存（怕改坏了退不回去）"
                return
            }
            do {
                try await files.writeFile(item.path, bytes: Data(draft.utf8))
                load = .ok(draft)
                editing = false
                note = "已保存 · 旧版留在 .yxi-bak-\(stamp)"
            } catch {
                note = "写回失败：" + String(error.localizedDescription.prefix(60))
            }
        }
    }
}
