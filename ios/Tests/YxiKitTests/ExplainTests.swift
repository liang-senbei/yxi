import NIOCore
import NIOPosix
import XCTest
@testable import YxiKit

/// 报错文案。
///
/// ⚠️ 这个文件存在的直接原因是 `Explain` 里用了 `if case 某枚举 = error`
/// 去匹配一个 `Error` 存在类型 —— **那种写法编得过，但完全可能一次都匹配不上**，
/// 于是所有精心写的文案静默退化成 `连 xxx 失败：<一坨异常>`，而且没人会发现。
final class ExplainTests: XCTestCase {

    func test_指纹变了要说清楚下一步干什么() {
        let msg = Explain.connection(
            KnownHosts.Rejection.fingerprintChanged(
                target: "root@1.2.3.4", expected: "SHA256:AAA", got: "SHA256:BBB"
            ),
            target: "root@1.2.3.4",
            hostname: "1.2.3.4"
        )
        XCTAssertTrue(msg.contains("指纹变了"), "枚举没匹配上，文案退化了：\(msg)")
        XCTAssertTrue(msg.contains("SHA256:AAA"))
        XCTAssertTrue(msg.contains("SHA256:BBB"))
        XCTAssertTrue(msg.contains("删掉这条再重加"), "只说「拒绝了」没用，得说怎么办")
    }

    func test_没确认信任() {
        let msg = Explain.connection(
            KnownHosts.Rejection.notTrusted(target: "root@1.2.3.4"),
            target: "root@1.2.3.4", hostname: "1.2.3.4"
        )
        XCTAssertTrue(msg.contains("没有确认信任"), "枚举没匹配上：\(msg)")
    }

    /// #27：用户填的是 `~/.ssh/config` 里的别名，而手机上没有那个文件。
    /// 报错要直接把这件事说出来，别让他去查 DNS。
    func test_解析不了地址要指出别名在手机上不管用() {
        // ⚠️ 用 NIO **真正会抛的那个类型**。第一版这里造了个假异常靠字符串匹配，
        // 编得过、跑起来一次都匹配不上 —— 见 Explain 里那段注释。
        let real = SocketAddressError.unknown(host: "天亮", port: 22)
        let msg = Explain.connection(real, target: "root@天亮", hostname: "天亮")
        XCTAssertTrue(msg.contains("IP 或真实域名"), "没走到 DNS 那条分支：\(msg)")
        // #58：还要指出**具体是哪个字**，只说「解析不了」等于没说
        XCTAssertTrue(msg.contains("「天」(U+5929)"), "没指出可疑字符：\(msg)")
    }

    /// #66：密码试错几次之后 fail2ban 封整个 IP，症状从「认证失败」变成「连不上」。
    func test_超时要提到运营商挡端口和fail2ban() {
        let msg = Explain.connection(
            ChannelError.connectTimeout(.seconds(15)), target: "root@1.2.3.4:22", hostname: "1.2.3.4"
        )
        XCTAssertTrue(msg.contains("fail2ban"), "没走到超时那条分支：\(msg)")
        XCTAssertTrue(msg.contains("22 端口出站"))
    }

    /// ⚠️ errno 的数值每个平台都不一样，只许跟符号常量比。
    func test_连接被拒和不可达分得开() {
        XCTAssertEqual(Explain.classify(IOError(errnoCode: ECONNREFUSED, reason: "x")), .refused)
        XCTAssertEqual(Explain.classify(IOError(errnoCode: ETIMEDOUT, reason: "x")), .timeout)
        XCTAssertEqual(Explain.classify(IOError(errnoCode: EPERM, reason: "x")), .other)
    }

    /// `NIOConnectionError` 是 Happy Eyeballs 的**汇总**错误 —— 只看它自己什么都看不出来。
    func test_HappyEyeballs的汇总错误要拆开看() {
        // 这个类型的 init 是 fileprivate，构造不出来；退而求其次，
        // 至少钉住「拆不开时不能装作知道」——`.other` 是诚实的答案。
        struct Opaque: Error {}
        XCTAssertEqual(Explain.classify(Opaque()), .other)
    }

    /// ⚠️ #71：文案里出现的必须是真正连的目标，**别名一个字都不能进来**。
    func test_文案里只出现真地址不出现别名() {
        struct Boom: Error {}
        let msg = Explain.connection(Boom(), target: "root@天亮:2222", hostname: "天亮")
        XCTAssertTrue(msg.contains("root@天亮:2222"))
        XCTAssertFalse(msg.contains("216.36.108.147"), "别名混进诊断文案了")
    }
}
