import WidgetKit

/// 小组件扩展的入口。
///
/// ⚠️ **没写成 `WidgetBundle` 的 body 里 `if #available(iOS 18)`**：
/// `@WidgetBundleBuilder` 到底吃不吃条件分支，要真编一次才知道，而这台机器编不了 iOS。
/// 两个 bundle + 手写 `main()` 把那个不确定换掉了 —— 多五行，
/// 但不会因为一个 result builder 的细节让整条 CI 红掉。
@main
enum YxiWidgets {
    static func main() {
        if #available(iOS 18.0, *) { ControlBundle.main() } else { BasicBundle.main() }
    }
}

struct BasicBundle: WidgetBundle {
    var body: some Widget { WaitingWidget() }
}

@available(iOS 18.0, *)
struct ControlBundle: WidgetBundle {
    var body: some Widget {
        WaitingWidget()
        WaitingControl()
    }
}
