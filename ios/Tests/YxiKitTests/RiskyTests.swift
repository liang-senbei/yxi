import XCTest
@testable import YxiKit

/// 哪些操作**不给**一键批。
///
/// ⚠️ 这里错了是**单向的**：漏判一条就意味着某个 `rm -rf` 在锁屏上被顺手点掉了，
/// 而那一下没有撤销。所以下面全是「必须 false」的钉子。
final class RiskyTests: XCTestCase {

    /// 一键批 = 没看内容就批。这几类批错了**连补救都进不去**。
    func test_删和毁的一律不给一键批() {
        for (tool, arg) in [
            ("Bash", "rm -rf /tmp/build"),
            ("Bash", "rm -r node_modules"),
            ("Bash", "sudo rm /etc/hosts"),
            ("Bash", "git reset --hard origin/main"),
            ("Bash", "git push --force"),
            ("Bash", "git push --force-with-lease"),
            ("Bash", "mkfs.ext4 /dev/sdb1"),
            ("Bash", "cat /dev/zero > /dev/sda"),
            ("Bash", "DROP TABLE users"),
            ("Bash", "truncate table logs"),
            ("Bash", "kubectl delete pod api-0"),
            ("Bash", "docker system prune -af"),
            ("Bash", "chmod -R 777 /"),
            ("Bash", "chown -R nobody /srv"),
            ("Bash", "claude --dangerously-skip-permissions"),
            ("Bash", ": > /var/log/app.log"),
            ("Bash", "shutdown -h now"),
            ("Bash", "reboot"),
        ] {
            XCTAssertFalse(Risky.oneTapOk(tool: tool, arg: arg), "「\(arg)」不该能一键批")
        }
    }

    /// ⚠️ 真踩过：一个脚本把用户手机的公钥从 `authorized_keys` 里删掉了（安卓 #65）。
    /// **动到「以后还能不能连上这台机器」的东西**，比命令危险更该拦。
    func test_动到还能不能连上的东西一律不给一键批() {
        for (tool, arg) in [
            ("Write", "/root/.ssh/authorized_keys"),
            ("Edit", "~/.ssh/config"),
            ("Write", "/root/.ssh/known_hosts"),
            ("Edit", "/etc/ssh/sshd_config"),
            ("Edit", "/etc/sudoers"),
            ("Read", "/etc/shadow"),
            ("Edit", "/etc/passwd"),
            ("Edit", "/root/.claude/settings.json"),
            ("Read", "/root/.claude/.credentials.json"),
            ("Edit", "/etc/nginx/nginx.conf"),
            ("Write", "/etc/systemd/system/yxi.service"),
        ] {
            XCTAssertFalse(Risky.oneTapOk(tool: tool, arg: arg), "「\(arg)」不该能一键批")
        }
    }

    /// 大小写不能当逃生口。
    func test_大小写不影响判定() {
        XCTAssertFalse(Risky.oneTapOk(tool: "Bash", arg: "RM -RF /tmp/x"))
        XCTAssertFalse(Risky.oneTapOk(tool: "Write", arg: "/root/.ssh/AUTHORIZED_KEYS"))
    }

    /// ⚠️ 反过来也要钉：日常操作**必须**能一键批。
    /// 全拦等于没拦 —— 用户会去关掉这个功能，那就一条都防不住了。
    func test_日常操作照常一键批() {
        for (tool, arg) in [
            ("Bash", "ls -la /root/src"),
            ("Bash", "git status"),
            ("Bash", "git push"),               // 故意放行：太常见，会天天挡
            ("Bash", "npm test"),
            ("Read", "/root/src/main.swift"),
            ("Edit", "/root/src/workspace/Yxi/README.md"),
        ] {
            XCTAssertTrue(Risky.oneTapOk(tool: tool, arg: arg), "「\(arg)」该能一键批")
        }
    }

    func test_空白不算危险() {
        XCTAssertFalse(Risky.matches(""))
        XCTAssertFalse(Risky.matches("   \n  "))
    }

    /// `tool` 和 `arg` 是**拼起来一起看**的 —— 危险词落在哪半边都得抓到。
    func test_两个字段拼起来一起判() {
        XCTAssertFalse(Risky.oneTapOk(tool: "rm -rf", arg: "/tmp"))
        XCTAssertFalse(Risky.oneTapOk(tool: "Bash", arg: "rm -rf /tmp"))
    }
}
