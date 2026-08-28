import XCTest
@testable import YxiKit

// MARK: - 文件大小

final class HumanSizeTests: XCTestCase {
    func testBoundaries() {
        XCTAssertEqual(humanSize(0), "0 B")
        XCTAssertEqual(humanSize(1023), "1023 B")
        XCTAssertEqual(humanSize(1024), "1 K")
        XCTAssertEqual(humanSize(1024 * 1024 - 1), "1024 K")
        XCTAssertEqual(humanSize(1024 * 1024), "1.0 M")
        XCTAssertEqual(humanSize(1024 * 1024 * 1024), "1.0 G")
    }
    /// 安卓那份对 5.5M 显示 "5.5 M" —— 两端必须一致，否则同一个文件两个数
    func testMatchesAndroid() {
        XCTAssertEqual(humanSize(5_767_168), "5.5 M")
        XCTAssertEqual(humanSize(2048), "2 K")
    }
}
