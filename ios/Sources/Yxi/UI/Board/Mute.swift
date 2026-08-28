import Foundation

/// 静音的会话 —— **按主机分开、存全名**（带 `cc-` 前缀）。
///
/// 置顶的反面：置顶是「只有这些才提醒」，静音是「这些永远别提醒」。
/// 有些会话（一直在跑的部署、盯日志的）你根本不想被它 ping。
///
/// ⚠️ 只存本地，是这台手机的偏好，**不动服务器** —— 换个人连同一台机器
/// 不该继承你的静音设置。
enum Mute {
    private static func key(_ hostID: String) -> String { "muted/" + hostID }

    static func all(_ hostID: String) -> Set<String> {
        Set(UserDefaults.standard.stringArray(forKey: key(hostID)) ?? [])
    }

    /// ⚠️ 存的和查的都用**全名**（`cc-Yxi`）：通知那条路拿到的就是全名，
    /// 一边存短名一边用全名查，静音会**看起来生效了其实没有**。
    static func isMuted(_ hostID: String, _ fullName: String) -> Bool {
        all(hostID).contains(fullName)
    }

    @discardableResult
    static func toggle(_ hostID: String, _ fullName: String) -> Bool {
        var cur = all(hostID)
        let on = !cur.contains(fullName)
        if on { cur.insert(fullName) } else { cur.remove(fullName) }
        UserDefaults.standard.set(Array(cur).sorted(), forKey: key(hostID))
        return on
    }
}
