// swift-tools-version:5.9
import PackageDescription

// ⚠️ **`Package.swift` 是在「跑 SwiftPM 的那台机器」上求值的，不是在目标平台上。**
// 于是 `#if os(macOS)` 正好用来把 SwiftUI 那个 target 从 Linux 上摘掉：
// 在 Mac 上（哪怕是给 iPhone 编）它在；在这台 Linux 服务器上它根本不被声明，
// 于是 `swift test` 能跑纯逻辑那部分 —— 这个仓库没有 macOS 也没有 Xcode，
// **能不能在 Linux 上跑测试决定了这些代码有没有被验证过。**

var targets: [Target] = [
    .target(
        name: "YxiKit",
        dependencies: [
            .product(name: "Citadel", package: "Citadel"),
            // ⚠️ **显式声明，不靠隐式传递。**
            // SwiftPM 现在允许 import 一个只是「传递依赖」里的模块，但那是宽容不是承诺。
            // 更重要的是：写在这里，下一个人翻开 Package.swift 就会看见
            // NIOSSH 来自哪个仓 —— 见下面 dependencies 里那段说明。
            .product(name: "NIOSSH", package: "swift-nio-ssh"),
        ]
    ),
    .testTarget(
        name: "YxiKitTests",
        dependencies: [
            "YxiKit",
            .product(name: "NIOSSH", package: "swift-nio-ssh"),
        ]
    ),
]

// 界面那一半的依赖。跟上面同理：只在 Mac 上声明，Linux 上连解析都不用解析
// （SwiftTerm 里有 UIKit/AppKit，MarkdownUI 拖 cmark-gfm 的 C 目标，都不必让
// 这台服务器去下载和编译）。
var uiDependencies: [Package.Dependency] = []
#if os(macOS)
uiDependencies = [
    // 终端控件。安卓那边用的是 `org.connectbot:termlib`（Compose 原生控件，不是 WebView）；
    // iOS 上的对应物是 SwiftTerm —— 同样是原生控件，作者是 Miguel de Icaza。
    .package(url: "https://github.com/migueldeicaza/SwiftTerm.git", from: "1.20.0"),
    // markdown 渲染。对应安卓的 `multiplatform-markdown-renderer-m3`。
    // ⚠️ 安卓踩过 #76：库的 M3 默认把 `#`/`##` 映射到 displayLarge/Medium（57sp / 45sp），
    // 聊天气泡里一个 `##` 就占半屏。**换到这边同样要自己压一套字号**，别用默认主题。
    .package(url: "https://github.com/gonzalezreal/swift-markdown-ui.git", from: "2.4.1"),
]
#endif

var products: [Product] = [
    .library(name: "YxiKit", targets: ["YxiKit"]),
]

// ⚠️ `Yxi` 这个 target 里全是 SwiftUI / UIKit，**只有 iOS 编得动**。
// 它只在 Mac 上被声明，于是两件事同时成立：
//   · 在这台 Linux 上它根本不存在 → `swift test` 只编 YxiKit，跑得起来；
//   · YxiKit 那边一旦不小心 `import SwiftUI`，Linux 上立刻编不过 —— **白送的守卫**。
//
// ⚠️ **在 Mac 上用命令行时要带 `--target YxiKit`。**
// 裸跑 `swift build` 会拿 macOS 当目标去编这个 target，撞上 `import UIKit` 必挂。
// 它是给 Xcode 按 iOS 目标编的（见 README「怎么在 Mac 上打开」）。
#if os(macOS)
targets.append(
    .target(
        name: "Yxi",
        dependencies: [
            "YxiKit",
            .product(name: "MarkdownUI", package: "swift-markdown-ui"),
            .product(name: "SwiftTerm", package: "SwiftTerm"),
        ],
        path: "Sources/Yxi"
    )
)
products.append(.library(name: "Yxi", targets: ["Yxi"]))
#endif

let package = Package(
    name: "Yxi",
    platforms: [
        // Citadel 的地板是 iOS 17 / macOS 14；
        // 但 `withPTY` / `withTTY` / `withExec` 标了 `@available(macOS 15.0, *)`，
        // 所以 macOS 这边只能是 15。iOS 那边没有额外标注，17 就够。
        .iOS(.v17),
        .macOS("15.0"),   // 字符串写法：`.v15` 要 swift-tools-version 6.0，而我们停在 5.9（见文件头）
    ],
    products: products,
    dependencies: [
        // ⚠️⚠️ **exact 而不是 from。** 这是 SSH 客户端，私钥要经过它的手，
        // 版本要人看过才升，不能靠 SemVer 自动往上飘（Citadel 还在 0.x，
        // 0.x 的 minor 升级在 SwiftPM 眼里就是 breaking，实际也确实会 breaking）。
        .package(url: "https://github.com/orlandos-nl/Citadel.git", exact: "0.12.1"),

        // ⚠️⚠️ **这不是 `apple/swift-nio-ssh`。** Citadel 0.12.1 依赖的是一条 fork 链：
        //   apple/swift-nio-ssh
        //     → Joannis/swift-nio-ssh        （Citadel 作者的 fork，为了上游没有的 RSA 等算法）
        //       → Wellz26/swift-nio-ssh      （0.12.1 里指着的这个）
        // 最后那一跳是 **2026-04-02 一位外部贡献者**在 Citadel 上提的一次提交
        // （"use Wellz26 nio-ssh fork for Mac Catalyst compatibility"），那个仓 0 star、
        // 建库当天推完就没动过。
        //
        // 我们把 `Wellz26/0.3.4` 和它的上游 `Joannis/0.3.4` **逐文件比过：
        // Sources 与 Package.swift 完全一致**，当前 tag 上没有夹带任何东西。
        // 所以风险不在代码，在**控制权** —— 那个 tag 随时可以被挪到别的 commit 上。
        //
        // 因此两条纪律：
        //   1. **`Package.resolved` 必须入库**（它钉的是 commit 哈希，不是 tag）。
        //   2. 哪天不放心了：fork 一份 Citadel、把它 Package.swift 里那一行改回
        //      `Joannis/swift-nio-ssh`，然后把上面那条依赖指向自己的 fork。
        //      **我们自己的代码一行都不用动。**
        .package(url: "https://github.com/Wellz26/swift-nio-ssh.git", "0.3.4" ..< "0.4.0"),
    ] + uiDependencies,
    targets: targets
)
