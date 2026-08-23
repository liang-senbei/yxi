import NIOCore
import NIOPosix
import XCTest
@testable import YxiKit

/// 测试用的小零件。故意都很笨 —— 测试里最不该出现的就是「聪明」。

final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var n = 0
    var value: Int { lock.withLock { n } }
    func bump() { lock.withLock { n += 1 } }
}

final class Box<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: T
    init(_ initial: T) { stored = initial }
    var value: T {
        get { lock.withLock { stored } }
        set { lock.withLock { stored = newValue } }
    }
}

struct CountingPrompt: TrustPrompt {
    let answer: Bool
    let counter: Counter
    func confirmNewHost(target: String, fingerprint: String) async -> Bool {
        counter.bump()
        return answer
    }
}

/// 模拟「用户把 App 切走了再也没回来」。
struct NeverAnsweringPrompt: TrustPrompt {
    func confirmNewHost(target: String, fingerprint: String) async -> Bool {
        try? await Task.sleep(for: .seconds(3600))
        return true
    }
}

/// `HostKeyGate` 要一个 `EventLoopPromise`，给它一个真的事件循环就行。
struct TestLoop {
    let loop: EventLoop = MultiThreadedEventLoopGroup.singleton.next()
    func promise() -> EventLoopPromise<Void> { loop.makePromise(of: Void.self) }
}

func XCTAssertThrowsErrorAsync(
    _ expression: @autoclosure () async throws -> some Any,
    _ message: String = "本该抛异常，结果成功了",
    file: StaticString = #filePath,
    line: UInt = #line
) async {
    do {
        _ = try await expression()
        XCTFail(message, file: file, line: line)
    } catch {
        // 期望如此
    }
}
