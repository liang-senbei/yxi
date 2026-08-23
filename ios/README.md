# Yxi · iOS

安卓版的对照实现。**这个目录里的东西没有一行在 iPhone 上跑过** ——
写它的机器是一台 Linux 服务器，没有 macOS、没有 Xcode、没有 iOS SDK。
所以这份 README 的两件正事是：**怎么在 Mac 上把它打开**，
以及**哪些东西已经真的验证过、哪些只是写出来了**。

- 装到 iPhone 上的路子（签名 / AltStore / $99）→ [`docs/分发.md`](docs/分发.md)
- 跟安卓版逐条对照（做不到的 / 换做法的）→ [`docs/与安卓版的差异.md`](docs/与安卓版的差异.md)
- 「手机主动响」在 iOS 上怎么办 → [`docs/背景通知.md`](docs/背景通知.md)

---

## 目录

```
ios/
├── Package.swift          两个 target 分得开的地方，改之前先读文件头
├── Package.resolved       ⚠️ 必须入库（钉的是 commit 哈希，不是 tag）
├── App/                   Xcode app target 的唯一源文件 + Info.plist 清单
├── Sources/
│   ├── YxiKit/            纯逻辑 + SSH。**不许 import SwiftUI/UIKit**
│   │   ├── HostInput      地址归一与拆分（全角、零宽、user@host:port）
│   │   ├── HostConfig     连接那一刻真正用到的东西
│   │   ├── HostStore      主机列表持久化
│   │   ├── KnownHosts     指纹判定表 + 接在 Citadel 上的那道闸
│   │   ├── KeyManager     ed25519 密钥（KeyIdentity 是纯逻辑那半）
│   │   ├── Vault          Keychain 存取
│   │   ├── SSHSession     连接 / exec / 带 PTY 的 shell / 事件流 / SFTP
│   │   ├── SFTP           文件模式 + Paths（纯字符串运算）
│   │   ├── Errors         Explain：把异常翻译成「照着做点什么」
│   │   └── Agent/         转录解析、屏幕解析（另一个人写的）
│   └── Yxi/               全是 SwiftUI/UIKit，**只有 iOS 编得动**
└── Tests/YxiKitTests/     XCTest。**在 Linux 上就能跑**（见下）
```

---

## 在 Linux 上跑测试（已实测，不是理论）

`YxiKit` 里没有一行 UIKit/SwiftUI，Citadel 和 NIOSSH 也都编得过 Linux。
这台服务器上是这么跑起来的：

```bash
# 一次性：装 Swift（Ubuntu 24.04）
curl -O https://download.swift.org/swift-6.0.3-release/ubuntu2404/swift-6.0.3-RELEASE/swift-6.0.3-RELEASE-ubuntu24.04.tar.gz
tar xzf swift-6.0.3-RELEASE-ubuntu24.04.tar.gz
export PATH="$PWD/swift-6.0.3-RELEASE-ubuntu24.04/usr/bin:$PATH"

cd ios
swift build --target YxiKit
swift test
```

**这条路是这些代码唯一被验证过的地方，别让它断。**
它靠的是 `Package.swift` 里那个 `#if os(macOS)` —— 界面那个 target
和它的两个依赖（SwiftTerm / MarkdownUI）在 Linux 上**根本不被声明**。
往 `Sources/YxiKit/` 里 `import SwiftUI` 会当场把这条路弄断。

---

## 在 Mac 上打开和编译

包里**没有 `.xcodeproj`** —— 那玩意儿是二进制味的 XML，进 git 就是冲突制造机。
Xcode 工程在 Mac 上现建，一次性两分钟：

1. **命令行先确认 package 是好的**（不进 Xcode 更容易看清楚问题）：

   ```bash
   cd ios
   swift build --target YxiKit
   swift test
   ```

   ⚠️ **必须带 `--target YxiKit`。** 裸跑 `swift build` 会拿 macOS 当目标去编
   `Yxi` 那个 target，而它满是 `import UIKit`，必挂。那个 target 是给 Xcode
   按 iOS 目标编的。

2. **Xcode → File → New → Project → iOS → App**
   - Product Name `Yxi`，Interface **SwiftUI**，Language **Swift**
   - 存到 `ios/` 下（跟 `Package.swift` 同级），**不要**勾 Core Data / Tests

3. **把生成的 `ContentView.swift` 和 `YxiApp.swift` 删掉**，
   改把 `App/YxiApp.swift` 拖进 app target（勾 *Copy items* 取消，用引用）。

4. **File → Add Package Dependencies → Add Local…** 选 `ios/` 这个目录，
   给 app target 勾上 **`Yxi`** 这个 library（它会带上 `YxiKit`）。

5. **Info**：把 `App/Info.plist` 里那几个键搬进工程的 Info 设置
   （语音 / 麦克风 / 相册的用途说明）。**少一个就是运行时崩溃，不是编译错误。**

6. **Signing & Capabilities**：选你的 Apple ID。免费账号也能装到自己手机上，
   代价是 7 天要重签一次 —— 详见 [`docs/分发.md`](docs/分发.md)。

7. 选真机（**模拟器连不了 SSH 之外的坑不少，但 SSH 本身是通的**），⌘R。

### 界面那半边的约定

`App/YxiApp.swift` 里只有一句 `RootView()`。
`Sources/Yxi/` 需要导出 `public struct RootView: View` 作为根 ——
入口保持在六行，别把装配逻辑塞进 app target，那是唯一一块 Linux 上编不到的地方。

---

## SSH 库：为什么是 Citadel

能同时满足「纯 Swift · iOS 能用 · **Linux 上能跑测试**」的只有两个：

| | `apple/swift-nio-ssh` | **`orlandos-nl/Citadel`** ← 选它 |
|---|---|---|
| SFTP | **没有**（它是协议实现，不是客户端） | ✅ 自带 SFTP v3 客户端 |
| 交互式 PTY / shell | 要自己拼 child channel + 事件 | ✅ `withPTY` / `withTTY` / `withExec` |
| 主机指纹校验 | 有 delegate，够用 | ✅ 同一个，包了一层 |
| Linux | ✅ | ✅ **本机实测编过** |

决定性的一条是 **SFTP**：文件模式（PRD 附录 G / D14）是 P0，
而且它是「任何 SSH 主机不装东西就能用」的那部分。
按 RFC 自己写一个 SFTP v3 客户端是几百行协议代码 —— 那不该是这个项目的成本。
libssh2 系（NMSSH / Shout）被排除：ObjC 且多年不维护，或者要给 iOS 交叉编译一个 C 库，
**而且都不能在 Linux 上跑测试**。

### 已知缺口（都核对过源码，不是推测）

1. **它依赖的不是 `apple/swift-nio-ssh`。** 是一条 fork 链：
   `apple` → `Joannis`（Citadel 作者，为了上游没有的算法）→ **`Wellz26`**
   —— 最后那一跳是 2026-04-02 一位外部贡献者提交改的，那个仓 0 star、推完当天就没动过。
   已把 `Wellz26/0.3.4` 和 `Joannis/0.3.4` **逐文件比过：完全一致**，当前 tag 上没夹带。
   风险在**控制权**不在代码 → `Package.resolved` 必须入库；真不放心就 fork 一份 Citadel
   改回去，我们自己的代码一行不用动。
2. **没有任何 keepalive。** 安卓靠 jsch 的 `serverAliveInterval` 发现切网后的「假活」
   （TROUBLESHOOTING #81）—— 这条路 iOS 上不存在。
   对策：`SSHSession.follow()` 让远端每 20 秒吐一个空行，`Liveness` 拿它当死活判据。
3. **`executeCommand` 退出码非 0 时抛异常，并把已收到的输出全丢掉**（它自己文档写的）。
   而我们大量命令本来就会非 0。所以 `SSHSession.exec()` 自己收流，**退出码当数据返回**。
4. **`SFTPFile.read(from:length:)` 一次只发一个 READ 请求**，拿回来的是一小块。
   直接用会**静默截断**大文件。`SFTP.read(_:max:)` 自己循环并带上限。
5. **没有「exec + PTY」的公开入口。** 安卓是 exec+PTY 直接跑 `tmux attach`，
   绕开了登录 shell 的横幅和时序竞态（#18）。iOS 上只能开 shell 再把命令打进去，
   **#18 那个坑因此回来了** —— 见 `SSHSession.openShell` 里的「等它先说话、再等它安静下来」。
6. **只有 AES-GCM，也没有 strict-KEX。** 安卓那条「必须避开 AES-GCM」（#16）
   是 **jsch 自己的 bug**，不适用这里；而且这里想避也没得选。
7. **底下是 NIOPosix（BSD socket），不是 Network.framework。**
   Citadel 的 `connect(host:port:…)` 里写死了 `MultiThreadedEventLoopGroup.singleton`。
   iOS 上苹果推荐 NIOTransportServices —— 它才懂 WiFi↔蜂窝切换和后台挂起。
   升级路子现成：Citadel 有 `connect(on channel:settings:)`，喂一个 NIOTS channel 进去即可。

---

## 安卓踩过的坑，在这边是什么样

| 安卓 | iOS |
|---|---|
| #12 ed25519 要注册 BouncyCastle | ✅ **不存在**。swift-crypto 自带 Curve25519 |
| #20 私钥必须导成 OpenSSH v1 文本 | ✅ **不存在**。私钥全程是个类型，不变成字符串 |
| #16 jsch 写包路径非线程安全，要加锁 | ✅ **不存在**。NIO 的写会自己 hop 到 event loop |
| #16 同类：SFTP 通道要串行化 | ✅ **不存在**。Citadel 按 request id 配对响应 |
| #17 exec+PTY 的流会提前 EOF | ✅ 绕过了（`.command` 模式本来就不带 PTY）。**别为了彩色给它加 PTY** |
| #21 指纹存的和读的编码不一样 → CHANGED 永远走不到 | ⚠️ 同类风险还在，靠 `KnownHostsTests` 的反向用例钉住 |
| #22 指纹变了也弹窗，点一下就绕过 | ⚠️ 换了张脸：`SSHHostKeyValidator.acceptAnything()` 就是 `StrictHostKeyChecking=no`。**全 App 不许出现它** |
| #58 全角字符导致「地址解析不了」 | ⚠️ **一模一样**，`HostInput` 照搬并加强 |
| #59 边输边拆，在用户手底下改他打的字 | ⚠️ **iOS 更躲不掉**（中文候选整词上屏）。拆分只在保存时做 |
| #65 所有设备共用注释 `yxi@android` → 脚本删了用户的钥匙 | ✅ 注释由公钥自己派生，结构上不可能误伤 |
| #71 报错报的是别名不是真地址 | ⚠️ `HostConfig.target` 强制要求传真地址，`Explain` 不给别名留位置 |
| #78/#79 `runCatching` 吞掉取消，界面留一句永久假错误 | ⚠️ Swift 里叫 `CancellationError`，坑一模一样 → 用 `Errors.swift` 里的 `catching` |
| #81 心跳阈值照抄导致常驻连接被误杀 | ⚠️ 见上面「没有 keepalive」。`Liveness.terminal` / `.background` 差一个量级 |

---

## 验证到什么程度

**已经真的跑过**（Linux / Swift 6.0.3）：

- `swift build --target YxiKit` 通过
- `swift test` 全绿
- **指纹跟系统 `ssh-keygen -lf` 的输出逐字节对过**（黄金值来自 ssh-keygen，不是自己算的）
- `Explain` 的错误分类 —— **测试第一次跑就把它照红了**：原来是按
  `String(describing: error)` 里的字匹配的，而 NIO 真正抛的是
  `SocketAddressError.unknown(host:port:)`，那串字里**根本没有 "unknownHost"**。
  这种失效是**静默**的：文案退化成「连 xxx 失败：<一坨异常>」，没人会发现。
  已全部改成按类型判（`SocketAddressError` / `NIOConnectionError` /
  `ChannelError.connectTimeout` / `IOError.errnoCode`，errno 只跟符号常量比，
  因为数值 Linux 和 Darwin 不一样）。
- 按 TROUBLESHOOTING #26 的规矩做了**变异测试**：把三处安全逻辑故意改坏
  （① 存的读不出来时降级成「第一次见」= #21 的失效方式 ② 指纹变了也去问用户 = #22
  ③ 注释写死成所有设备共用的词 = #65），确认对应断言**真的会红**，再改回来复绿。
  绿的测试在没见它红过之前不算数。

**标了 `⚠️ 未验证` 的**（这台机器上验不了，第一次在 Mac 上跑起来时优先验这几条）：

| 在哪 | 什么 |
|---|---|
| `SSHSession.openShell` | 「等它先说话、再等它安静下来」那段时序（对应 #18） |
| `SSHSession.open` | 把 Citadel 的作用域 API 适配成句柄的那套 continuation |
| `App/YxiApp.swift` | 整个文件（没有 iOS SDK，从未编译过） |
| `Vault` / `KeyManager` 的 Keychain 部分 | 在 `#if canImport(Security)` 里，Linux 上编不到 |
| `SFTP.list` 从 `permissions` 判目录 | SFTP v3 的属性位是可选的，兜底走 `ls -l` 那串文字，没见过真服务器 |

---

## 一件必须单独拍板的事

**「手机主动响」在 iOS 上没有等价物。**
安卓靠前台服务常驻一条 SSH 通道 `tail -f` 事件流；iOS 进后台约 30 秒就收走 socket，
`BGProcessingTask` 由系统决定什么时候跑、不保证有网络。
而 PRD §2.7 / D8 已经否掉了推送那条路（会逼我们长期跑一台中转服务器）。

代码里 `Host.watch` 因此只能是「**App 在前台时盯着**」。
详细选项（含 Bark 那一层）在 [`docs/背景通知.md`](docs/背景通知.md)。
