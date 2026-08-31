import XCTest
@testable import YxiKit

/// 「在这个目录开一个新会话」。
///
/// ⚠️ 类名不叫 `DirsTests`：`SFTPTests.swift` 里已经有一个（测的是候选目录那几个函数）。
///
/// ⚠️ 这块**错了不会报错，只会静静地开错地方** —— `tmux new-session -c <不存在的目录>`
/// 返回 0 然后跑去 `$HOME`。安卓侧用户真撞上过：想在 `.../logto` 开，开在了 `/root`。
/// 所以下面每一条钉的都是「少了它就会静默出问题」的东西。
final class DirsCreateTests: XCTestCase {

    private let cmd = Dirs.createCommand(dir: "/root/src/workspace/logto", session: "cc-logto")

    // MARK: - createCommand

    /// 目录不存在就建出来 —— 不然 tmux 会「成功」地开去 `$HOME`。
    func test_必须先建目录() {
        XCTAssertTrue(cmd.contains("mkdir -p"), "没有 mkdir -p，目录不存在时会开去 $HOME")
    }

    /// ⚠️ **不信 tmux 的退出码，只信它真正落在哪。**
    /// 少了这一段，「名字对、位置错」的会话会被当成开成功。
    func test_建完必须回头核对落点() {
        XCTAssertTrue(cmd.contains("pane_current_path"), "没回头查 pane_current_path，开错地方也看不出来")
        XCTAssertTrue(cmd.contains("pwd -P"), "两边都要 pwd -P，不然软链会把比较搞砸")
        XCTAssertTrue(cmd.contains("kill-session"), "落点不对必须把会话杀掉，不能留个骗人的空壳")
        XCTAssertTrue(cmd.contains("\(Dirs.tag):wrongdir"), "落点不对要报 wrongdir")
    }

    /// 已经开着的不重开、开不起来要有代号。
    func test_四个出口都在() {
        for code in ["nodir", "exists", "failed", "ok"] {
            XCTAssertTrue(cmd.contains("\(Dirs.tag):\(code)"), "少了 \(code) 这个出口")
        }
        XCTAssertTrue(cmd.contains("has-session"), "已经开着的会话不能重开")
        XCTAssertTrue(cmd.contains("send-keys"), "新开的会话里要把 claude 跑起来")
    }

    /// ⚠️ shell 单引号转义是 `'\''` **四个字符**。写成 `'''` 的话，
    /// 路径里有单引号就会把整条命令截断 —— 而这在真机上表现为「莫名其妙开不了」。
    func test_路径里的单引号必须正确转义() {
        let c = Dirs.createCommand(dir: "/root/it's here", session: "cc-it's")
        XCTAssertTrue(c.contains("'\\''"), "单引号必须转义成 '\\'' 四个字符")
        XCTAssertFalse(c.contains("'''"), "写成 ''' 是错的，会把命令截断")
        XCTAssertTrue(c.contains("d='/root/it'\\''s here'"), "转义后的整段要能原样喂给 shell")
    }

    /// 末尾斜杠去掉；去光了退回 `/`，不能产出 `d=''`。
    func test_末尾斜杠和空目录() {
        XCTAssertTrue(Dirs.createCommand(dir: "/a/b/", session: "n").contains("d='/a/b'"))
        XCTAssertTrue(Dirs.createCommand(dir: "/", session: "n").contains("d='/'"))
        XCTAssertTrue(Dirs.createCommand(dir: "   ", session: "n").contains("d='/'"), "空目录不能产出 d=''")
    }

    // MARK: - madeFrom

    /// ⚠️ **认不出来一律当失败**（fail-closed）。跳进一个没建成的会话，
    /// 用户看到的是一片空白加「连不上」，比直接说「没开成」难查得多。
    func test_认不出来一律当失败() {
        XCTAssertEqual(Dirs.madeFrom(""), .failed(code: "noresult", detail: ""), "空输出不能当成功")
        XCTAssertEqual(Dirs.madeFrom("\n\n  \n"), .failed(code: "noresult", detail: ""))
        XCTAssertEqual(Dirs.madeFrom("bash: tmux: command not found"),
                       .failed(code: "noresult", detail: ""), "一堆 shell 报错里没有标记 = 没开成")
        XCTAssertEqual(Dirs.madeFrom("\(Dirs.tag):随便什么新代号"),
                       .failed(code: "unknown", detail: "随便什么新代号"), "认不出的代号当失败，别当成功")
    }

    func test_读出每一种结果() {
        XCTAssertEqual(Dirs.madeFrom("\(Dirs.tag):ok"), .ok)
        XCTAssertEqual(Dirs.madeFrom("\(Dirs.tag):exists"), .exists)
        XCTAssertEqual(Dirs.madeFrom("\(Dirs.tag):nodir"), .failed(code: "nodir", detail: ""))
        XCTAssertEqual(Dirs.madeFrom("\(Dirs.tag):failed"), .failed(code: "failed", detail: ""))
    }

    /// wrongdir 要把**真正落在哪**带回来 —— 界面上「本该在 X，实际在 Y」全靠它。
    func test_wrongdir带回真正的落点() {
        XCTAssertEqual(Dirs.madeFrom("\(Dirs.tag):wrongdir:/root"),
                       .failed(code: "wrongdir", detail: "/root"))
    }

    /// ⚠️ 前面可能还有 shell 自己的回显 / 别的输出，**取最后一条标记行**。
    func test_取最后一条标记行且不怕噪音() {
        let noisy = """
        Warning: Permanently added 'host' to the list of known hosts.
        \(Dirs.tag):exists
        \(Dirs.tag):ok
        """
        XCTAssertEqual(Dirs.madeFrom(noisy), .ok)
        XCTAssertEqual(Dirs.madeFrom("  \(Dirs.tag):ok  "), .ok, "两边的空白要吃掉")
    }
}
