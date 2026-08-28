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
    @State private var loading = true
    @State private var note: String?

    private var runner: ShellRunner? { app.live?.link.service as? ShellRunner }

    /// 按类型分栏，只有有内容的类才出现。栏内按时间新的在前。
    private var cats: [(key: String, items: [Lab.Item])] {
        let grouped = Dictionary(grouping: items, by: \.catKey)
        return grouped
            .map { (key: $0.key, items: $0.value.sorted { $0.at > $1.at }) }
            .sorted { $0.items.first?.at ?? 0 > $1.items.first?.at ?? 0 }
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
            if !item.desc.isEmpty {
                Text(item.desc).font(.system(size: 12)).foregroundStyle(Yx.muted).lineLimit(3)
            }
            Text(meta(item)).font(.mono(11)).foregroundStyle(Yx.dim)
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

    private func toggleApprove(_ item: Lab.Item) async {
        guard let runner else { return }
        let on = !approved.contains(item.id)
        if on { approved.insert(item.id) } else { approved.remove(item.id) }
        _ = try? await runner.run(Lab.approveCommand(id: item.id, on: on))
    }
}
