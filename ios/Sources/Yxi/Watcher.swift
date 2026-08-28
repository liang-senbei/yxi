import Foundation
import YxiKit
#if canImport(UserNotifications)
import UserNotifications
#endif

/// 前台盯梢：开了铃铛的主机，每隔一会儿看一眼有没有会话在等你，有就出通知。
///
/// ⚠️⚠️ **iOS 上这只能是「前台盯梢」，跟安卓不是一回事，别照抄。**
/// 安卓靠前台服务常驻一条 SSH 通道 `tail -f` 事件流。iOS 没有前台服务：
/// App 进后台大约 30 秒内所有 socket 就被收走，`BGProcessingTask` 由系统决定
/// 什么时候跑、几分钟到几小时不等、也不保证有网络。
/// 所以界面上那句文案（「只在 Yxi 开着的时候盯」）是**如实描述**，不是免责声明。
///
/// ⚠️ 在这之前，主机页那个铃铛**开关是通的、后面什么都没接** ——
/// 用户打开它、等着响、永远不响。一个不干活的开关比没有这个开关更糟。
@MainActor
final class Watcher: ObservableObject {

    /// 上一轮已经通知过的会话。**只对「新变成等你」的报** ——
    /// 每轮都报的话，一个等了十分钟的会话会响十次。
    private var notified: Set<String> = []
    private var task: Task<Void, Never>?

    /// 盯梢的间隔。⚠️ 别太密：每一轮是一次真的 SSH 往返，
    /// 手机网络下太密既费电又容易排队堆积。
    private let interval: Duration = .seconds(20)

    func start(_ link: HostLink?) {
        stop()
        guard let link else { return }
        task = Task { [weak self] in
            await self?.requestPermissionOnce()
            while !Task.isCancelled {
                await self?.tick(link)
                try? await Task.sleep(for: self?.interval ?? .seconds(20))
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
        notified = []
    }

    private func tick(_ link: HostLink) async {
        guard let svc = link.service else { return }
        guard let sessions = try? await svc.snapshot() else { return }   // 抓不到就下一轮再说
        let waiting = sessions.filter { $0.state == .needsYou }
        let names = Set(waiting.map(\.name))
        for s in waiting where !notified.contains(s.name) {
            await notify(session: s)
        }
        // ⚠️ 已经不等了的要从集合里去掉，否则它下次再等你时**不会再响**
        notified = names
    }

    private func notify(session: BoardSession) async {
        #if canImport(UserNotifications)
        let c = UNMutableNotificationContent()
        c.title = session.short + " 等你"
        // ⚠️ 一定要带**在等什么**。只说「需要你」而不说要决定什么，
        // 用户看到了也回不了 —— 得先打开 App 才知道，通知就白发了。
        c.body = session.detail.isEmpty ? "它停下来等你回话了" : session.detail
        c.sound = .default
        let req = UNNotificationRequest(identifier: "yxi-\(session.name)", content: c, trigger: nil)
        try? await UNUserNotificationCenter.current().add(req)
        #endif
    }

    private func requestPermissionOnce() async {
        #if canImport(UserNotifications)
        _ = try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound])
        #endif
    }
}
