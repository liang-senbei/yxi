import AppIntents
import SwiftUI
import WidgetKit

/// 控制中心的那个按钮，对标安卓的快捷设置磁贴 `WaitingTile`：一眼看见「几个等你」，点一下进 App。
///
/// ⚠️ **iOS 18 才有 Control Widget**，而我们的地板是 iOS 17，所以整个文件都带 `@available`，
/// 挂进 bundle 的方式见 `Bundle.swift`。
///
/// ⚠️ **这个文件是整批改动里最可能编不过的一处** —— 本机是 Linux，iOS 18 的
/// `ControlWidget` / `AppIntents` 这几个 API 我一行都没编译过。
/// 真挂了就：删掉本文件 + 把 `Bundle.swift` 里的 `main()` 固定成 `BasicBundle.main()`，
/// 别的部分不受影响。
@available(iOS 18.0, *)
struct OpenYxi: AppIntent {
    static var title: LocalizedStringResource = "打开 Yxi"
    /// 这个 intent 什么都不做，就是把 App 拉起来（磁贴 `onClick` 的等价物）。
    static var openAppWhenRun = true
    func perform() async throws -> some IntentResult { .result() }
}

@available(iOS 18.0, *)
struct WaitingControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "app.yxi.control.waiting") {
            ControlWidgetButton(action: OpenYxi()) {
                // ⚠️ 在这儿现读文件：控制中心每次拉开都会重求一次值。
                // 读不到就只写 "Yxi" —— 跟小组件同一条规矩，不显示 0。
                Label(WaitingSnapshot.current()?.title ?? "Yxi", systemImage: "terminal")
            }
        }
        .displayName("Claude")
    }
}
