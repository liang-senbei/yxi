# Yxi iOS

SwiftPM 包，两个 target：

| target | 内容 | 哪里编得动 |
|---|---|---|
| **`YxiKit`** | 纯逻辑：SSH（Citadel）、主机存储、known_hosts、路径解析 | **Linux + Mac 都行** |
| **`Yxi`** | 全是 SwiftUI / UIKit：看板、对话、终端 | **只有 Mac**（`Package.swift` 里用 `#if os(macOS)` 摘掉了） |

分这一刀的原因、依赖为什么钉 `exact`、`Wellz26/swift-nio-ssh` 那条 fork 链是怎么回事 ——
**全写在 `Package.swift` 的注释里**，动依赖之前先读那个文件。

---

## 在这台 Linux 上验逻辑层

```sh
/opt/swift/usr/bin/swift test          # Swift 6.0.3，⚠️ 不在 PATH 里
```

只编 `YxiKit` + 测试，跑得起来。**这个仓库没有 Mac、没有 Xcode ——
能不能在 Linux 上跑测试，决定了这些代码有没有被验证过。**

白送的守卫：`YxiKit` 里一旦有人 `import SwiftUI`，Linux 上立刻编不过。

## 在 Mac 上打开

`App/` 还是空的（**Xcode 工程还没建**）。现在的开法：

```
Xcode → File → Open… → 选 ios/ 这个目录（有 Package.swift 的那层）
```

命令行编 **必须带 `--target YxiKit`**：

```sh
swift build --target YxiKit
```

裸跑 `swift build` 会拿 macOS 当目标去编 `Yxi`，撞上 `import UIKit` 必挂 —— 它是给 Xcode 按 iOS 目标编的。

> 要出 `.ipa` 就得在 `App/` 下建 Xcode 工程（App target + 签名配置）。**还没人做。**

## 底线要求

- **`Package.resolved` 必须入库** —— 它钉的是 commit 哈希，不是 tag。理由见 `Package.swift` 里 nio-ssh 那段。
- 平台地板：**iOS 17 / macOS 15**（Citadel 的 `withPTY` 标了 `@available(macOS 15.0, *)`）。

## 文档

`docs/` 下三份，iOS 版能不能做、要花多少钱都在里面：

| | 说什么 |
|---|---|
| [`docs/背景通知.md`](docs/背景通知.md) | 「Claude 需要你时手机会响」在 iOS 上怎么做。**结论：能，秒级** —— 但必须过 APNs |
| [`docs/分发.md`](docs/分发.md) | 怎么装到手机上。**没有「从服务器直接装」这回事**；要么 7 天一刷，要么 ¥688/年 |
| [`docs/与安卓版的差异.md`](docs/与安卓版的差异.md) | 做不到 / 要换做法 / iOS 反而更好的，一张表 |
