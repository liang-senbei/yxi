import SwiftUI
import WidgetKit

/// 桌面小组件：几个会话在等你。对标安卓的 `WaitingWidget`。
///
/// ⚠️ 数据来自共享容器里那份 [WaitingSnapshot]（主 App 在前台时写的），
/// **这里不连服务器**，理由见 WaitingSnapshot 的说明。
struct WaitingEntry: TimelineEntry {
    let date: Date
    /// nil = 读不到或者已经过期。⚠️ **这时整块不显示，不显示 0。**
    let snap: WaitingSnapshot?
}

struct WaitingProvider: TimelineProvider {

    /// 小组件库里那张预览图。这里给的是**假数据**，是唯一允许编数字的地方。
    func placeholder(in context: Context) -> WaitingEntry {
        WaitingEntry(date: Date(),
                     snap: WaitingSnapshot(waiting: 2, working: 1, names: ["yxi", "站长机"], at: Date()))
    }

    func getSnapshot(in context: Context, completion: @escaping (WaitingEntry) -> Void) {
        completion(WaitingEntry(date: Date(), snap: WaitingSnapshot.current()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WaitingEntry>) -> Void) {
        let now = Date()
        let snap = WaitingSnapshot.current(now: now)
        // ⚠️ 两条 entry：现在这条，和「它过期的那一刻」那条（空的）。
        // 这样即使主 App 忘了叫刷新、或者用户好几天没开过 App，
        // 桌面上也不会一直挂着一个早就不成立的「3 个等你」—— 到点自己闭嘴。
        var entries = [WaitingEntry(date: now, snap: snap)]
        if let snap {
            entries.append(WaitingEntry(date: snap.at.addingTimeInterval(WaitingSnapshot.maxAge), snap: nil))
        }
        completion(Timeline(entries: entries, policy: .after(now.addingTimeInterval(15 * 60))))
    }
}

struct WaitingView: View {
    var entry: WaitingEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let s = entry.snap {
                Text(s.title).font(.headline)
                Text(s.subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            } else {
                // ⚠️ 读不到就只留个名字。**不写「0 个等你」** ——
                // 那是在替一件我们并不知道的事打包票，而它的代价是用户放心地不去看。
                Text("Yxi").font(.headline)
                Text("打开看看").font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        // ⚠️ iOS 17 起小组件的背景**必须**走 containerBackground，
        // 不然待机显示/桌面上会缺一块底。
        .containerBackground(.fill.tertiary, for: .widget)
    }
}

struct WaitingWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "app.yxi.widget.waiting", provider: WaitingProvider()) {
            WaitingView(entry: $0)
        }
        .configurationDisplayName("Claude")
        .description("几个会话在等你")
        // ⚠️ 不加 `.widgetURL` —— 那要 App 注册 URL scheme 并在 RootView 里接住。
        // 不接就是点了没反应；默认行为（打开 App）已经够用，跟安卓那个一样。
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
