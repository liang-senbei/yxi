import Foundation

/// 没发出去的草稿，**按会话分开存，落盘**。
///
/// ⚠️ 只放在内存里的话，切个会话、切个标签页、被系统回收，打了一半的话就没了 ——
/// 而手机上打字本来就是最费劲的一步（安卓 #104）。
///
/// ⚠️ 存 `UserDefaults` 不存钥匙串：这是草稿不是密码，而且要能快、能在
/// 界面每次改动时随手写。
enum Drafts {
    private static func key(_ session: String) -> String { "draft/" + session }

    static func load(_ session: String) -> String {
        UserDefaults.standard.string(forKey: key(session)) ?? ""
    }

    static func save(_ session: String, _ text: String) {
        // 空的就删掉，别让 UserDefaults 攒一堆空字符串
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            UserDefaults.standard.removeObject(forKey: key(session))
        } else {
            UserDefaults.standard.set(text, forKey: key(session))
        }
    }

    static func clear(_ session: String) {
        UserDefaults.standard.removeObject(forKey: key(session))
    }
}
