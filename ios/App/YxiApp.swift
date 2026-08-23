import SwiftUI
import Yxi

/// App 的入口。**整个文件只该有这么多。**
///
/// 这里不属于任何一个 SwiftPM target —— 它是 Xcode 那个 app target 里唯一的源文件，
/// 别的东西全在 package 里（`Yxi` 管界面、`YxiKit` 管 SSH 和纯逻辑）。
/// 这么分的好处是：**逻辑能在 Linux 上编译和跑测试**，
/// 而这个仓库既没有 macOS 也没有 Xcode（见 README）。
///
/// ⚠️ **未验证**：这台机器上没有 iOS SDK，本文件从未编译过。
/// `RootView` 由 `Sources/Yxi/` 那半边提供（约定：`public struct RootView: View`）。
@main
struct YxiApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
