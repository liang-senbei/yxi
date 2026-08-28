import SwiftUI
import YxiKit

/// **实验室** —— agent 产出的东西按类型分栏；点栏目展开看细节。
///
/// 内容全从**服务器**读（`~/.yxi/lab/`），跟安卓版**读同一个目录、同一份 manifest**：
/// 别的 agent 用 `yxi-lab add` 推进来，这边刷新就见，**不用更新 App**。
///
/// ⚠️ 每条都标了**由谁生成**和**北京时间**（安卓侧用户明确要的）。
struct LabScreen: View {

    @ObservedObject var app: AppState
    @State private var items: [Lab.Item] = []
    @State private var approved: Set<String> = []
    @State private var expanded: Set<String> = []
    /// 置顶的栏目（按类型 key）。**本地存** —— 是每台设备自己的偏好，跟别人无关。
    @AppStorage("lab_cat_pins") private var pinsRaw = ""
    @State private var loading = true
    @State private var note: String?

    private var runner: ShellRunner? { app.live?.link.service as? ShellRunner }

    private var pins: Set<String> {
        Set(pinsRaw.split(separator: "\n").map(String.init))
    }

    /// 按类型分栏，只有有内容的类才出现。栏内按时间新的在前。
    /// **置顶的排最前**，其余按最新条目的时间。
    private var cats: [(key: String, items: [Lab.Item])] {
        let grouped = Dictionary(grouping: items, by: \.catKey)
        let pinned = pins
        return grouped
            .map { (key: $0.key, items: $0.value.sorted { $0.at > $1.at }) }
            .sorted { a, b in
                let pa = pinned.contains(a.key), pb = pinned.contains(b.key)
                if pa != pb { return pa }
                return (a.items.first?.at ?? 0) > (b.items.first?.at ?? 0)
            }
    }

    var body: some View {
        Group {
            if loading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if items.isEmpty {
                EmptyNote(text: note ?? "实验室还是空的。\n别的 agent 用 `yxi-lab add <文件>` 推进来，这里刷新就见。")
            } else {
                List {
                    ForEach(cats, id: \.key) { cat in
                        Section {
                            if expanded.contains(cat.key) {
                                ForEach(cat.items) { item in row(item) }
                            }
                        } header: {
                            header(cat)
                                // iOS 自带的滑动操作 —— 不用像安卓那边手写拖拽手势
                                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                    Button(role: .destructive) {
                                        Task { await deleteCat(cat) }
                                    } label: { Label("删除", systemImage: "trash") }
                                    Button {
                                        togglePin(cat.key)
                                    } label: {
                                        Label(pins.contains(cat.key) ? "取消置顶" : "置顶",
                                              systemImage: "pin")
                                    }
                                    .tint(Yx.teal)
                                }
                    }
                }
                .listStyle(.plain)
            }
        }
        .background(Yx.surface)
        .task { await reload() }
        .refreshable { await reload() }
    }

    private func header(_ cat: (key: String, items: [Lab.Item])) -> some View {
        let newest = cat.items.first
        return Button {
            if expanded.contains(cat.key) { expanded.remove(cat.key) } else { expanded.insert(cat.key) }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(Lab.categoryName(cat.key))
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Yx.onSurface)
                    if let n = newest {
                        Text(meta(n)).font(.system(size: 11)).foregroundStyle(Yx.dim)
                    }
                }
                Spacer()
                Text("\(cat.items.count)").font(.mono(12)).foregroundStyle(Yx.dim)
                Image(systemName: expanded.contains(cat.key) ? "chevron.down" : "chevron.right")
                    .font(.system(size: 12)).foregroundStyle(Yx.dim)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func row(_ item: Lab.Item) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(item.title).font(.system(size: 15)).foregroundStyle(Yx.onSurface)
            LabPreview(item: item, runner: runner)
                .frame(maxHeight: 340)
            if !item.desc.isEmpty {
                Text(item.desc).font(.system(size: 12)).foregroundStyle(Yx.muted).lineLimit(3)
            }
            Text(meta(item)).font(.mono(11)).foregroundStyle(Yx.dim)
            LabSaveButton(item: item, runner: runner,
                          files: app.live?.link.service as? FileService)
            Button {
                Task { await toggleApprove(item) }
            } label: {
                Text(approved.contains(item.id) ? "✓ 已审核" : "勾选 = 审核通过这个")
                    .font(.system(size: 12, weight: .medium))
                    .padding(.horizontal, 14).padding(.vertical, 6)
                    .background(approved.contains(item.id) ? Yx.amber.opacity(0.22) : Yx.high,
                                in: Capsule())
                    .foregroundStyle(approved.contains(item.id) ? Yx.amber : Yx.muted)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 4)
    }

    /// 「由谁生成 · 北京时间」——两样安卓侧都显示，这边照做。
    private func meta(_ item: Lab.Item) -> String {
        var bits: [String] = []
        if !item.by.isEmpty { bits.append("由 \(item.by) 生成") }
        if item.at > 0 {
            let f = DateFormatter()
            f.dateFormat = "MM-dd HH:mm"
            f.timeZone = TimeZone(identifier: "Asia/Shanghai")
            bits.append("\(f.string(from: Date(timeIntervalSince1970: item.at)))（北京）")
        }
        return bits.joined(separator: " · ")
    }

    private func reload() async {
        guard let runner else { loading = false; note = "没连上，实验室读不了。"; return }
        loading = true
        defer { loading = false }
        if let raw = try? await runner.run(Lab.manifestCommand) {
            items = Lab.parse(manifest: raw)
        }
        if let raw = try? await runner.run(Lab.approvalsCommand) {
            approved = Set(raw.components(separatedBy: "\n")
                .map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })
        }
    }

    private func togglePin(_ key: String) {
        var cur = pins
        if cur.contains(key) { cur.remove(key) } else { cur.insert(key) }
        pinsRaw = cur.sorted().joined(separator: "\n")
    }

    /// 删掉整栏。⚠️ **服务器上真删**，所以先从界面拿掉再发命令 ——
    /// 命令失败的话下一次 reload 会把它们带回来，比「删了却还在」诚实。
    private func deleteCat(_ cat: (key: String, items: [Lab.Item])) async {
        guard let runner, let cmd = Lab.removeCommand(ids: cat.items.map(\.id)) else { return }
        let gone = Set(cat.items.map(\.id))
        items.removeAll { gone.contains($0.id) }
        _ = try? await runner.run(cmd)
    }

    private func toggleApprove(_ item: Lab.Item) async {
        guard let runner else { return }
        let on = !approved.contains(item.id)
        if on { approved.insert(item.id) } else { approved.remove(item.id) }
        _ = try? await runner.run(Lab.approveCommand(id: item.id, on: on))
    }
}
