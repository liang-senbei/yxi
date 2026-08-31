import Foundation

// 看板上**只属于这台手机**的三样偏好：收藏、按什么轴看、收起了哪些组。
//
// ⚠️ **跟分组表分开存，是故意的。** 分组表在服务器上（`~/.yxi/groups.json`，
// 见 `YxiKit.Groups`）—— 因为读它的不只有手机，还有组里的 agent 自己。
// 而这三样**没有第二个读者**：收藏是「我关心哪几个」，视图和收起是「我怎么看」，
// 写到服务器上只会污染别人的视图。
//
// ⚠️ 一律**按主机分开存**（跟 `Pinned` / `Mute` 一个规矩）：
// 换台机器，同名会话未必是同一件事。

/// 收藏的会话名。**存全名**（带 `cc-` 前缀），跟 tmux 里一致。
///
/// ⚠️ **跟置顶各管各的**：置顶管位置（把它拎到最上面），收藏管「还要不要它」——
/// 收藏过的会话**死了也记着**，会列进看板最后那段「未启用」，随时在原目录拉回来。
enum Fav {
    private static func key(_ hostID: String) -> String { "faved/" + hostID }
    private static func cwdKey(_ hostID: String, _ name: String) -> String {
        "favcwd/\(hostID)/\(name)"
    }

    static func all(_ hostID: String) -> Set<String> {
        Set(UserDefaults.standard.stringArray(forKey: key(hostID)) ?? [])
    }

    static func set(_ hostID: String, _ v: Set<String>) {
        UserDefaults.standard.set(Array(v).sorted(), forKey: key(hostID))
    }

    /// 记住这个会话**当时在哪个目录**。
    ///
    /// ⚠️ 必须**趁它活着**记 —— 会话一被杀，tmux 那边什么都不剩了。
    /// 只记名字的话「唤起」会把它开在 `$HOME`，而不是原来干活的地方。
    /// ⚠️ 取消收藏时**不删这条**：再收藏回来还能落回原目录，几十个键而已。
    static func remember(_ hostID: String, _ name: String, cwd: String) {
        // 没变就别写 —— 5 秒一轮的轮询会一直来叫它
        guard !cwd.isEmpty, cwd != UserDefaults.standard.string(forKey: cwdKey(hostID, name))
        else { return }
        UserDefaults.standard.set(cwd, forKey: cwdKey(hostID, name))
    }

    /// 收藏时记下的目录。老收藏（还没记过的）是 nil —— 界面上要**说清楚**会开在 `~`，
    /// 不然点下去开错地方，用户以为「唤起」坏了。
    static func cwd(_ hostID: String, _ name: String) -> String? {
        UserDefaults.standard.string(forKey: cwdKey(hostID, name))
    }
}

/// 看板的两种看法 + 收起了哪些组。
///
/// ⚠️ 分组**不是替代**按状态看，是**换个轴**：按状态看回答「谁在等我」，
/// 按分组看回答「这摊活儿都谁在干」。所以是切换，不是取代。
enum BoardPrefs {
    /// 没存过 = false = 按状态看（原来那样）
    static func grouped(_ hostID: String) -> Bool {
        UserDefaults.standard.bool(forKey: "boardgroup/" + hostID)
    }

    static func setGrouped(_ hostID: String, _ v: Bool) {
        UserDefaults.standard.set(v, forKey: "boardgroup/" + hostID)
    }

    /// ⚠️ 存的是**收起的**那几个，不是展开的 —— 新建的组默认展开。
    /// 反过来存的话，别处（安卓、agent）新建的组一出现就是收着的，看着像丢了。
    static func collapsed(_ hostID: String) -> Set<String> {
        Set(UserDefaults.standard.stringArray(forKey: "collapsed/" + hostID) ?? [])
    }

    static func setCollapsed(_ hostID: String, _ v: Set<String>) {
        UserDefaults.standard.set(Array(v).sorted(), forKey: "collapsed/" + hostID)
    }
}
