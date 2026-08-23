# TROUBLESHOOTING · Yxi

> 每条 = 症状 → 根因 → 修法。**只增不删**。

## 1. `accounts.google.com` 直连超时，但 `dl.google.com` / `play.google.com` 通
- **症状**：服务器上 `curl https://accounts.google.com/` 卡住返回 000；同时 `dl.google.com` 正常 302。
- **根因**：出口网络对 Google 账号域名单独有阻断，非全站不可达。
- **修法**：走本机 sing-box。它的 inbound 是 **`mixed`** 类型（`/etc/sing-box/config.json`，`127.0.0.1:1080`），
  **HTTP 和 SOCKS5 都收** —— 所以 `curl -x http://127.0.0.1:1080` 和 `--socks5-hostname` 都能用。
  模拟器里用 `-http-proxy http://10.0.2.2:1080`（`10.0.2.2` = 模拟器视角的宿主机）。

## 2. `apkanalyzer` 报 `Cannot locate latest build tools`
- **症状**：`apkanalyzer apk summary x.apk` 抛 `IllegalStateException: Cannot locate latest build tools`。
- **根因**：它依赖 `$ANDROID_HOME/build-tools/<ver>/aapt2`，而 cmdline-tools 单独解压时 build-tools 还没装。
- **修法**：先 `sdkmanager "build-tools;34.0.0"`。**光有 cmdline-tools 不够。**

## 3. Moshi 的 `base.apk` 单独装不起来
- **症状**：微信收到的 `base.apk` 装上后一启动就崩。
- **根因**：它是 **split APK 的 base 部分**，`unzip -l` 数出 **0 个 `.so`**。而 Moshi 重度依赖原生
  （libghostty 终端引擎 / Nitro 传输层 / Parakeet ASR，见 PRD §2.2），缺 `split_config.<abi>.apk` 必崩。
- **修法**：① 模拟器用 `google_apis_playstore` 镜像从 Play 装（Play 自动处理 split，**官网确认安卓版只走 Play，无直接 APK 下载**）；
  ② 或手机装 [SAI](https://github.com/Aefyr/SAI) 导出完整 `.apks` → `adb install-multiple`。

## 4. Hermes 字节码里 `grep` 搜不到明明存在的字符串
- **症状**：`grep -oE 'https?://...' index.android.bundle` 返回空，但 `strings | grep` 有结果。
- **根因**：`.bundle` 是二进制，GNU grep 判定为 binary 后 `-o` 不输出。
- **修法**：**必须加 `-a`**（`grep -aoE`）。另外 Hermes 字符串表是**拼接存储**的（无分隔符），
  `strings` 会把相邻字符串串成一行 —— 要读上下文得按 offset 取字节，见 `dev/dig.py`。

## 5. 模拟器跑着跑着崩：`ERROR | Failed to find ColorBuffer: NN`
- **症状**：无 GUI 模拟器开机正常，一打开图片多的界面（应用商店的图标墙）就整个 qemu 进程死掉，
  `adb` 立刻变 `device offline` → `no devices/emulators found`。
- **根因**：软件渲染（gfxstream / swiftshader / lavapipe）在 **1080x2400** 这种大分辨率下渲染压力过大。
  **不是内存问题**（崩的时候还剩 11 G）。
- **修法**：把 AVD 降到 **720x1280 / density 320**（`hw.lcd.*`）并加 `-skin 720x1280`。降完就稳了。

## 6. `pkill -f 'qemu-system'` 把执行它的脚本自己杀了
- **症状**：脚本跑到 `pkill` 那行就整个退出，退出码 144，**一行输出都没有**。
- **根因**：`pkill -f` 匹配**完整命令行**，而当前 shell 的命令行里就含 `qemu-system` 这个字符串 → **自杀**。
  `pgrep -f` 同理，会把自己算进匹配结果，导致 `until ! pgrep -f X` 永远不退出。
- **修法**：模式里插方括号打断字面匹配：`pkill -f 'qemu-sys[t]em'`。

## 7. `set -euo pipefail` 误杀模拟器启动脚本
- **症状**：脚本直接退出，`/tmp/emulator.log` 内容还是上一次的（说明 nohup 那行压根没执行到）。
- **根因**：`until` 轮询、`adb wait-for-device` 等语句的中间退出码非 0，被 `set -e` 当成失败。
- **修法**：这类等待脚本**不要用 `set -e`**。

## 8. Aurora Store 匿名会话装不了某些应用：`App not supported`
- **症状**：Aurora 匿名登录成功、能打开应用页面，但点 Install 报 `App not supported`；
  页面上版本号显示 **`v (0)`**。
- **根因**：`v (0)` 是关键线索 —— **匿名会话拿不到该应用的完整元数据**（较新/受限的应用常见），
  于是 Aurora 自己的兼容性检查判定不支持。**不是 ABI 问题**：
  `ro.product.cpu.abilist` = `x86_64,arm64-v8a`，镜像自带 ARM 转译。
- **修法**：改用 Aurora 的 **Google 账号登录**；或在真机上用 [SAI](https://github.com/Aefyr/SAI) 导出完整 `.apks`。
  → 对本项目**价值不高**：原生库是 libghostty 和 Mosh 传输，两块我们都不抄（PRD §2.2）。

## 9. AGP 9 内置 Kotlin —— 再加 `kotlin.android` 插件会直接报错
- **症状**：`Failed to apply plugin 'org.jetbrains.kotlin.android'` →
  「The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0」
- **根因**：AGP 9.0 起 Kotlin 支持内置，旧教程里那句 `alias(libs.plugins.kotlin.android)` 现在是冲突。
- **修法**：删掉 `kotlin.android` 插件（根和 app 两处）。
  **`kotlin.plugin.compose` 要留着**（Compose 编译器仍是独立 Kotlin 插件）。
  同时 `kotlin { compilerOptions { jvmTarget … } }` 这个块也去掉，AGP 自己管。

## 10. `sourceSets["main"].kotlin.srcDirs(...)` 已废弃
- **症状**：先是 deprecation 警告，改成 `directories.add(file("..."))` 后报
  `Argument type mismatch: actual type is 'File', but 'String' was expected`。
- **修法**：`sourceSets["main"].kotlin.directories.add("src/main/kotlin")`——**收字符串，不是 `File`**。

## 11. AndroidX 2026.08 起要求 compileSdk 37
- **症状**：`checkDebugAarMetadata` 失败，一长串
  「Dependency 'androidx.compose.…:1.12.0' requires libraries and applications that
  depend on it to compile against version 37 or later」。
- **根因**：compose-bom 2026.08.00 / core-ktx 1.19.0 这批的 AAR 元数据要求 compileSdk ≥ 37。
- **修法**：`sdkmanager "platforms;android-37.0" "build-tools;37.0.0"`（⚠️ 包名是
  **`android-37.0`** 带小数点，不是 `android-37`），然后 compileSdk/targetSdk 都调 37。
  minSdk 保持 26 不受影响。

## 12. ⭐ Android 上 jsch 的 ed25519 认证必然失败 —— 除非注册 BouncyCastle
- **症状**：`JSchException: Auth fail for methods 'publickey,password'`。异常消息本身**什么也说明不了**。
- **诊断**：接上 jsch 自己的 logger（`JSch.setLogger`）才看到真话：
  ```
  Signature algorithms unavailable for non-agent identities = [ssh-ed25519, ssh-ed448]
  ssh-ed25519 not available for identity <名字>
  ```
- **根因**：**Android 的 JCA 不提供 `Ed25519` 签名算法**。jsch 内建的 ed25519 依赖
  JDK 15+ 的 JCA，安卓（API 34 实测）没有。
- **走过的弯路**：
  - 加 `net.i2p.crypto:eddsa` —— **没用**。jsch 查的算法名是 `Ed25519`，i2p 那个库注册的是 `EdDSA`/`NONEwithEdDSA`，对不上
  - 退回 ECDSA nistp256 —— 能用，但 ed25519 才是现在的默认，没必要退
- **修法**：注册 **BouncyCastle**（`org.bouncycastle:bcprov-jdk18on`），它正好用 `Ed25519` 这个名字：
  ```kotlin
  java.security.Security.removeProvider("BC")   // 先摘安卓自带的阉割版，否则算法查找命中旧的
  java.security.Security.insertProviderAt(BouncyCastleProvider(), 1)
  ```
  验证：日志变成 `ssh-ed25519 auth success` / `Authentication succeeded (publickey)`。
  代价：APK 从 12 MB 涨到 15 MB。

## 13. `NetworkOnMainThreadException` —— jsch 的写入必须自己切线程
- **症状**：认证成功、PTY 也开了，一往终端写字节就 `IOException: android.os.NetworkOnMainThreadException`。
- **根因**：Compose 的 `LaunchedEffect` 跑在主线程。jsch 的 `OutputStream.write` 是网络操作。
  读那边我用了 `withContext(Dispatchers.IO)` 所以没事，**写那边漏了**。
- **修法**：`Shell.write` / `resize` 一律 `suspend` + `withContext(Dispatchers.IO)`，
  别指望调用方记得切。

## 14. 模拟器在 gradle 构建时被挤死
- **症状**：构建跑着跑着 `adb: no devices/emulators found`，模拟器进程没了。不是 GPU 崩溃（#5 那种）。
- **根因**：**内存**。gradle 的 JVM 默认吃到 5 GB，加上模拟器 4 GB，机器 16 GB 还跑着
  VNC / Inspector / 多个 cc 会话 —— 可用只剩 4.9 GB 时模拟器被挤掉。
- **修法**：① `org.gradle.jvmargs=-Xmx2048m` + `org.gradle.parallel=false`（单模块并行无收益）
  ② 模拟器降到 `-memory 2048` ③ **先构建、构建完 `./gradlew --stop` 放掉守护进程内存，再拉模拟器**
  —— 已固化进 `dev/run.sh`。

## 15. 模拟器的 `-http-proxy` 会打断 SSH
- **症状**：SSH 握手和认证全部成功，数据流几百字节后
  `java.net.SocketException: Connection reset`。
- **根因**：当初为了登 Google Play 给模拟器加了 `-http-proxy http://10.0.2.2:1080`。
  **模拟器会把所有 TCP 都塞进那个 HTTP 代理**，而 sing-box 处理不了长连的 SSH 隧道。
- **修法**：`dev/avd.sh` 里 `PROXY` 默认留空，只有要登 Google 时才临时 `PROXY=... ./dev/avd.sh start`。

## 16. ⭐⭐ jsch 的 Session 写包路径**不是线程安全的**
- **症状**（两种表现，都极难定位）：
  - 服务器 `journalctl -u ssh` 里 `ssh_dispatch_run_fatal: … message authentication code incorrect`，随即杀连接
  - 或者通道**无声无息**关闭：客户端读到 EOF、`ch.connected=false`、没有任何异常、
    服务器日志也干净
- **诊断过程**（走了很多弯路，记下来省得重来）：
  1. 以为是 tmux 问题 → `ssh -tt 'tmux attach'` 从服务器手工跑，完全正常
  2. 以为是 `ChannelExec`+PTY 的问题 → 换 `ChannelShell`，还是断
  3. 以为是写得太早被 tty 冲掉 → 加就绪检测，还是断
  4. **把终端控件从数据通路上摘掉**（不调 `emulator.writeInput`）→ **通道稳如磐石**
  5. 恢复控件 + 打全量回调日志 → `控件 onResize -> 55x42` 之后紧跟着 EOF
- **根因**：终端控件的 `onResize` 从它自己的线程调 `setPtySize`，与读循环/`write`
  **并发写同一条 SSH 连接**，把包流写坏。MAC 错误和静默关闭是同一个根因的两种表现。
- **修法**：`Shell` 内部加 `Mutex`，`write` 和 `resize` 全部 `ioLock.withLock { … }` 串行化。
- **教训**：**「摘掉一个组件看还坏不坏」比继续猜有效得多**。前四步都在猜，第五步一刀切开。

## 17. jsch `ChannelExec` + `setPty(true)` 的输入流会提前 EOF
- **症状**：跑 `sleep 25` 这种长时间无输出的命令，只读到 13 字节就 `read() == -1`，
  而 `channel.isConnected` **仍然是 true**。
- **修法**：终端通道用 **`ChannelShell`**，别用 `ChannelExec`+PTY。
  `ChannelExec` 留给「跑一条命令拿输出就退」（`exec()`）。

## 18. 往刚开的 shell 里写命令会被冲掉
- **症状**：命令在终端里**回显了但没执行**，随后出现全新的提示符。
- **根因**：登录 shell（starship 那种）初始化要时间。在它开始读 stdin 之前写进去的字节
  被 tty 回显，然后被 shell 启动时的输入冲刷丢弃。
- **修法**：等第一批输出到达（表示 shell 在跑了）+ 一个静默间隔再写。
  更好的做法是根本别往终端里打配置命令——**开一条独立的 exec channel 去做**，
  终端的输入通道留给用户。

## 19. `run.sh` 里构建失败被管道吃掉
- **症状**：改了代码但行为没变，反复查不出原因——其实是**构建早就失败了**，
  脚本却拿着旧 APK 继续装。
- **根因**：`./gradlew … | grep … | tail` 之后 `$?` 是 `tail` 的退出码，**永远 0**。
- **修法**：`exit "${PIPESTATUS[0]}"`。

## 20. ed25519 私钥必须用 `writeOpenSSHv1PrivateKey`，不是 `writePrivateKey`
- **症状**：`KeyPair.genKeyPair(jsch, KeyPair.ED25519)` 之后调 `writePrivateKey()`
  抛 `UnsupportedOperationException`，**而且 message 是 null**——异常本身毫无信息量。
- **根因**：`writePrivateKey` 走的是传统 PEM 格式，**ed25519 只能用 OpenSSH v1 格式**。
- **修法**：`kp.writeOpenSSHv1PrivateKey(outputStream, null)`。读回用 `KeyPair.load` 即可（它认这个格式）。
- **另外**：`KeyPair.genKeyPair(…, ED25519)` **也需要先注册 BouncyCastle**（#12 只提到认证，
  其实生成同样需要）。所以把注册抽成了 `Crypto.ensureProviders()`，`KeyManager` 和
  `SshSession` 两边都调。

## 21. ⭐ `HostKey.getKey()` 返回的已经是 base64——再编码一次会让指纹校验彻底失效
- **症状**：每次连同一台主机都弹「第一次连这台主机」，**指纹变了却检测不出来**。
- **根因**：`HostKeyRepository.check()` 拿到的是**原始字节**，而 `add()` 里的
  `HostKey.getKey()` 返回的**已经是 base64 字符串**。我在 `add()` 里又 base64 了一次
  → 存的是双重编码，跟 `check()` 的单次编码永远对不上 → 永远 `NOT_INCLUDED`。
- **后果**：`CHANGED` 这条分支**永远走不到**。这不是显示瑕疵，**是中间人防护完全失效**。
- **修法**：`add()` 里直接存 `hostkey.key`。
- **怎么发现的**：故意篡改存下来的 hostKey 去试「应该被拒」，结果弹的是「第一次连」——
  **如果不专门测这个反向用例，这个洞会一直躺在那**。

## 22. ⭐ jsch 的 `StrictHostKeyChecking=ask` 在指纹**变了**时也会弹窗询问
- **症状**：篡改 hostKey 后连接，弹的是普通的「要不要信任」对话框；
  用户点「连」**就真连上了**。
- **根因**：我以为 `ask` 模式下 `CHANGED` 会被 jsch 直接拒绝——**错的**。
  它同样走 `UserInfo.promptYesNo()`。
- **后果**：社工一句「服务器刚重装过」就能骗过这道防线。
- **修法**：在 `promptYesNo()` 开头判断自己记的 `changedDetected` 标志，
  **为真就直接 return false，连问都不问**。真是重装了，让用户去主机列表显式删掉再重加——
  那是一个有意识的动作，不是随手点一下「确定」。

## 23. Kotlin 的块注释**可以嵌套**——注释里写 `/` 紧跟 `*` 会吃掉整个文件
- **症状**：`Syntax error: Unclosed comment`，报的行号是**文件最后一行 +1**，
  而那里什么都没有，完全看不出问题在哪。
- **根因**：文档注释里写了路径 `~/.cloud-status/` + `*.json`——中间的 `/` `*` 两个字符
  开了一个**嵌套块注释**。Kotlin 与 Java 不同，**块注释是可嵌套的**，
  于是后面的 `*/` 只闭合了内层，外层一路开到文件末尾。
- **怎么定位**：数一下 `/*` 和 `*/` 的出现次数，不相等就是它。
- **修法**：注释里别写这种路径，或者把通配符拆开写。

## 24. 抽出来的共用件忘了传参，失败长得跟真攻击一模一样
- **症状**：会话看板一连就报 `reject HostKey`，而终端界面连同一台主机完全正常。
- **根因**：我在会话看板里图省事写了 `KnownHosts(store, host.id, null)`——
  **prompt 传了 null**，于是「没见过这台主机」时无从询问，只能保守拒绝。
- **为什么值得记**：这个失败的表现**跟真的中间人攻击一模一样**。
  如果不是我自己刚写的代码，很可能会往"服务器被劫持了"的方向查。
- **修法**：把「连接 + 首次信任确认」抽成共用的 `rememberSshConnector`，
  终端和看板都用它。**凡是每个界面都要做一遍的安全动作，就该只有一份实现。**

## 25. ⭐ 抽了共用件却漏掉一个调用点——「给新主机装公钥」永远失败
- **症状**：主机列表里加一台新机（只填了密码），点「装公钥」→ 报 `reject HostKey`。
  **这个失败长得跟真的中间人攻击一模一样。**
- **根因**：`InstallKeySheet` 自己 `KnownHosts(store, host.id, null)` + `SshSession(...)`，
  prompt 传 `null`。新主机 `check()` 返回 `NOT_INCLUDED` → jsch 问 `promptYesNo` →
  没有 UI 可问 → 保守拒绝。#24 修的是会话看板那处，**这一处漏了**。
- **为什么会漏**：装公钥要用**密码**认证（公钥还没装上去），而 `rememberSshConnector`
  只会用主机存着的认证方式 —— 于是「它满足不了我的需求」成了绕开它的理由。
- **修法**：把认证覆盖做成共用入口的**参数**（`connect(Auth.Password(pw))`），
  让 UI 层**没有任何理由**自己 `new SshSession`。改完 `grep -rn "SshSession("` 在 ui/ 和 term/ 下
  只剩 connector 自己那一处 —— 这条 grep 就是防线。
- **教训**：抽共用件的时候要问「有没有哪个调用点因为需求不同而绕开它」，
  **绕开的那个就是下一个洞**。抽完立刻 grep 一遍原始构造函数。

## 26. ⭐ 绿的测试在没见它红过之前不算数
- **背景**：#21 #22 两个安全洞**正向用例全绿时都静静躺着**，只有专门测「应该失败」才露出来。
  补了 `KnownHostsTest`（5 条，仪器测试，因为依赖 `android.util.Base64`）之后，
  第一次跑就全绿 —— 但这**什么也没证明**。
- **做法**：把两个洞**按原样 sed 回去**再跑：
  `记住之后同一把钥匙必须OK` 报 `expected:<0> but was:<2>`、`指纹变了连问都不问直接拒` 失败。
  确认断言真的在扛事，再 `cp` 回来复绿。
- **顺带更正 #21 的一句话**：双重编码后 `check()` 实际返回的是 **`CHANGED`(2)** 而不是
  `NOT_INCLUDED`(1)。当时看到的「每次都弹第一次连这台主机」是 **#22 叠加造成的**——
  信任弹窗的文案写死了「第一次连」，CHANGED 走到同一个弹窗，从界面上根本分不出来。
  两个洞叠在一起互相掩护，这也是为什么单看现象定位不到。
- **命令**：`./gradlew connectedDebugAndroidTest`（要有设备/模拟器）。

## 27. 表单标签「主机名 / IP」在邀请用户填 SSH 别名
- **症状**：真机上用密码连，报 `JSchException: java.net.UnknownHostException: 天亮`。
  「天亮」是用户给这台机起的**别名**。
- **根因**：代码没错（`jsch.getSession(cfg.username, cfg.hostname, cfg.port)` 用的是 hostname），
  是**标签在误导**：「主机名」这个词让人以为可以填 `~/.ssh/config` 里的别名。
  手机上没有 `~/.ssh/config`，别名不解析。
- **修法**：标签改成「IP 或域名，如 38.244.50.31」；别名那栏改成「随便起，只给你自己看」。
  另外把异常翻译集中到 `Connector.explain()`：`UnknownHostException` 直接告诉用户
  「这一栏要填 IP 或真实域名，SSH 别名在这儿用不了」。
- **教训**：**能被误填的字段，标签就得写成没法误填的样子**；报错要说「怎么办」不是说「是什么」。

## 28. ⭐⭐ 待答的 `tool_use` **不落转录** —— 「此刻在等你」只能抓屏幕
- **背景**：G6 原计划从转录 JSONL 里读 `AskUserQuestion` 渲染成可点选项。
- **实测**：起一个会话让它问问题，**问题挂在屏幕上等着**，同时去看转录文件 ——
  用户那条消息在，**assistant 那条（含 `tool_use`）不在**。回答之后才一起写进去。
  也就是说「有个问题正等着你」这段时间，转录里什么都没有。
- **根因**：Claude Code 把 assistant 消息**缓冲到工具跑完**才落盘。
- **结论**：**转录是权威的历史，屏幕是唯一的「此刻」。** 两条路分工，不是二选一：
  历史读 `~/.claude/projects/**.jsonl`，待答抓 `tmux capture-pane`。
- **顺带的好处**（比正确性更重要的一点）：屏幕上写着几号就送几号。
  要是改成「从 JSON 读选项、按下标送键」，一旦两边顺序对不上就会**点 A 选中 B 且不报错**。
  **屏幕上的数字就是契约。**

## 29. Claude Code TUI 选择器的按键协议（实测，不是猜的）
起一个 tmux 会话跑 `claude`，用 `tmux send-keys` 逐个试出来的：

| 场景 | 按键 |
|---|---|
| 单选（AskUserQuestion / 信任目录 / 计划批准） | **送数字 → 直接选中并确认**，不用再送 Enter |
| 多选 | 数字 = **切换勾选**；`Right` 跳到 Submit 页；再送 `1` 才算提交 |
| 多个问题 | `Left` / `Right` 在问题标签之间切换 |
| 通用导航 | `Up` / `Down` / `j` / `k`；`Esc` 取消 |
| 多选时的 Enter | **是切换勾选，不是提交** —— 我一开始按常识以为是提交，错的 |

选项最多 4 个 + `Type something` + `Chat about this` = 6，**数字永远是一位**。
`Type something` 也能点：送数字后 TUI 开文本框，接着走正常的 `send-keys -l 文本` + `Enter` 就能填。

## 30. 屏幕上的编号列表会被误认成选项 —— 靠「编号连续到 1」而不是「离脚注多近」
- **症状**：ExitPlanMode 的批准框解析出来，标题是空的、选项数不对。
- **根因**：计划正文本身就是 `1. 烧水 / 2. 下面 / 3. 出锅`，**就贴在选择器上面**。
  一开始的规则是「取脚注上面 30 行里所有编号行」，把计划正文一起收进去了。
- **后果**：用户点第 3 项，送出去的 `3` 落到真选项的第 3 个上 —— **点 A 选中 B，而且不报错**。
- **修法**：从脚注**往上**收，编号必须 **连续递减到 1**，断了就停。
  真选项永远是紧贴脚注的那一组 1..N。
- **教训**：距离是启发式，结构才是规则。**能悄悄选错的地方，宁可规则严一点。**

## 31. 模拟器上 `adb install -r` 之后 App 数据没了（主机列表、密钥全丢）
- **症状**：每次重装完打开都是「还没有主机」，而且 App 的公钥变了（服务器上刚加的
  `authorized_keys` 立刻失效），报「认证被拒」。
- **影响**：每轮验证都要重新走一遍加主机的 UI，用 `adb shell input text` 一格格填，很慢。
- **绕法**：**别用 UI 填，直接塞文件**（debug 包可以 `run-as`）：
  ```
  adb push hosts.json /data/local/tmp/
  adb shell run-as app.yxi cp /data/local/tmp/hosts.json files/hosts.json
  ```
  `hostKey` 字段先用 `ssh-keyscan -t ed25519 <ip> | awk '{print $3}'` 填好，
  连的时候就不弹指纹确认了（顺带又验证了一次 known_hosts 的编码是对的）。
  公钥则每次重装后重新取一遍：`adb logcat -d -s YxiKey | grep -o 'pub=[A-Za-z0-9+/=]*'`。
- ⚠️ `adb shell run-as app.yxi sh -c '...'` 里的重定向会以 `/` 为工作目录，
  要用 `run-as app.yxi cp` 这种直接形式。

## 32. 「漏传 modifier」第三次出现了 —— 这次是文件查看器被状态栏压住
- **症状**：从文件列表点进一个文件，标题栏和状态栏叠在一起。
- **根因**：`FilesScreen` 把 `modifier`（里面有 Scaffold 的系统栏边距）用在了自己的 Column 上，
  但 `open?.let { FileViewer(...) }` 这条早退分支**没往下传**。
- **同一个形状已经出现三次**：#24（会话看板漏传 trust prompt）、#25（装公钥漏传 prompt）、这次。
  **凡是「每个调用点都要传一遍」的东西，就会有人漏传。**
- **怎么早点发现**：写完早退分支（`?.let { ...; return }`）回头看一眼参数表，
  外层用到的东西是不是都传下去了。这类分支最容易漏，因为它绕过了下面的主体。

## 33. `/tmp` 有 433 项 —— 靠滚是找不到东西的
- **症状**：文件模式做完，去 station 的 `/tmp` 找测试文件，滚了半天找不到。
- **根因**：不是 bug，是**功能缺了一块**。目标里本来就写着「直接输入路径」，我先只做了树形浏览。
- **修法**：面包屑末尾加一个 ⌖，弹框里可以直接敲路径（支持 `~`，走 SFTP 的 `realpath` 解析），
  另外记住最近去过的 8 个目录。**收藏没做** —— 那要落盘，等有真需求再说。
- **教训**：**目录浏览器在真实机器上的第一个障碍不是功能而是数量。** 演示目录都很小，
  真机上随便一个 `/tmp` 就几百项。

## 34. 语法高亮把 markdown 的 `#` 当成了注释符
- **症状**：md 源码模式下，`# 标题` 整行变灰。凑巧好看，但 `见 https://x/#anchor` 也会从 `#` 起灰掉。
- **根因**：注释符按扩展名分派时，默认返回了 `listOf("//", "#")`（"两种都认，认错顶多少上点色"），
  md 落进了默认分支。
- **修法**：`md` / `txt` / `csv` / `log` 明确返回**空**注释符列表。
- **教训**：「宽松一点没坏处」的默认值，**在它不该生效的类型上就是坏处**。

## 35. ⭐⭐ 节流没有「尾随刷新」—— 忙的会话正常，闲的会话永远空白
- **症状**：对话模式在有些会话里正常，有些会话**永远是 0 条**。服务器上 `tail -n 800 -f` 明明在跑，
  转录文件里也确实有内容。
- **根因**：解析做了节流「距上次超过 250ms 才重新解析」。而 `tail` 是**一次性把历史吐完**的：
  33 行全落在同一个 250ms 窗口里，**只有第一行触发了解析**（那行还是 `system` 类型，解出 0 条），
  剩下 32 行被吞掉，然后 tail 阻塞等新内容 —— 界面就永远停在第一行的解析结果上。
- **为什么难查**：**活跃的会话完全正常**（我一直在往里写，新行不断触发重解），
  只有闲着的会话才空白。这种「看起来偶发」的现象最容易被归到网络或时序上去。
- **修法**：节流一律要有尾随刷新。收行的只管 `buf += line; dirty = true`，
  另起一个协程每 300ms 把脏的刷出来（拷一份再丢到 `Dispatchers.Default` 解析，别在主线程解 800 行）。
- **教训**：**凡是「攒一批再处理」，都要问一句「最后那批谁来收」。** 丢的永远是最后一批，
  而最后一批往往就是全部。

## 36. `projectDirOf` 只把斜杠换成横杠不够 —— 非字母数字**全部**变横杠
- **症状**：中文路径的会话（`/opt/workspace/日常对话`）永远「没找到转录」。
- **根因**：Claude Code 的项目目录名规则是**凡不是 ASCII 字母数字的字符一律换成 `-`**，
  不只是斜杠。实测本机存在 `-opt-workspace-----`（`/opt/workspace/日常对话`，四个汉字四个横杠），
  没有任何带中文的目录名。点号、空格、连字符同理。
- **修法**：`cwd.map { if (它是 ASCII 字母或数字) 它 else '-' }`。
  ⚠️ 别用 Kotlin 的 `isLetterOrDigit()` —— **汉字在它眼里是字母**，会原样留下。
- 有测试盯着（`TranscriptTest.项目目录名`），断言里写的是实测到的真实目录名。

## 37. D-Pad 长按能用、快点没反应 —— 「按下发一次」不能放进 LaunchedEffect
- **症状**：压住方向键能连续走，**快速点一下完全没反应**。
- **根因**：把「按下立刻发一次」写在了 `LaunchedEffect(held)` 里。按下和抬起如果落在同一帧，
  `held` 已经变回 null，effect 还没轮到跑第一次 `send`。
- **修法**：第一下**在手势回调里直接发**，`LaunchedEffect` 只负责之后的连发；
  手指推到另一个方向时同样在回调里立刻补一发、并重启连发计时。
- **教训**：**「立即」的事情不要交给 effect。** effect 的执行时机由重组决定，
  而手势的生命周期比一帧还短。用起来的感觉是「有时候不灵」，最难查。

## 38. `pointerInput(Unit)` 抓的是**第一次组合时**的那个 lambda
- 手势里要调用外面传进来的回调，得先 `rememberUpdatedState(callback)` 再用，
  否则一直用的是初次组合那份闭包。这次没炸只是因为闭包读的是 `MutableState`，
  但只要哪天回调换了实现就会静默失效。

## 39. 「记住每会话偏好」把「开终端」按钮压没了
- **症状**：点「开终端」进去却是对话模式。
- **根因**：工作区一律用记住的偏好覆盖传进来的初始模式。
- **修法**：**显式动作压过记忆** —— 点「开终端」「文件」传具体模式，
  点会话卡片传 null（那才是「打开这个会话」而不是「我要某个模式」）。
- **教训**：记忆类功能要想清楚**它该压过谁**。压过显式点击就等于把按钮做废了。

## 40. 测试纪律：别往不是自己的 tmux 会话里打字
- **经过**：会话看板每 5 秒刷一次并重排，我截图定位好坐标、`adb shell input tap` 落下时
  列表已经变了 —— 点进了别人的会话，还往里 `input text` 了一行测试命令。
- **后果**：这次侥幸没造成影响（那行字没进输入框，也没发回车），但**这是运气不是设计**。
- **纪律**：① 测试只用**自己起的** `cc-probe` 之类的会话；② 往终端里打字前先确认标题栏；
  ③ 定位靠内容不靠坐标 —— 坐标会过期。
- 顺带：这也是个**真实的可用性问题** —— 看板在用户手指落下前重排，点错的不只是我。

## 41. 重连要多久：分解出来才知道该不该优化
- **目标写的是「切网后两秒内恢复」，实测 2.9–3.4 秒**（模拟器，掐断 → tmux 重新 attach）。
- **分解**：掐断 → 新 TCP 建立 **0.69 秒**（含看门狗 600ms 轮询）；剩下 ~2.2 秒是
  SSH 握手 + ed25519 认证 + 开 shell + 登录 shell 出提示符。
  服务器侧基准：`bash -lic true` 只要 0.25 秒，**所以瓶颈不在登录 shell**，
  在模拟器的软件加密（x86 翻译执行）。真机大概率更快，但**「应该会快」不能当验收**。
- **两个不该做的优化**：
  - 把心跳调到 1 秒以下 → 弱网 RTT 抖一下就**误杀一条还活着的连接**，正在跑的命令白跑。
  - 用 `ChannelExec + PTY` 直接跑 `tmux attach` 绕开登录 shell → 撞 #17（空闲时提前 EOF）。
- **做了的**：重连时跳过 tmux 的建会话/设选项那次往返（那些早就设过了）。
- **真正重要的那半是「光标位置不丢」，这个是满分**：重连后 `tmux attach` 把整屏原样带回来，
  连之前的回滚都在。**这是 tmux 白送的，不是我们实现的** —— 也正因如此，
  不针对 tmux 会话的裸终端重连后内容就是会丢，那是 SSH 的性质。

## 42. 固定的「等它就绪」延时，两头都不讨好 —— 改成等它安静下来
- **背景**：连上 shell 之后不能立刻写 `tmux attach`（#18：登录 shell 初始化期间写进去的字节
  会被 tty 回显后冲掉，现象是命令回显了却没执行）。原来的写法是死等 900ms。
- **问题**：快的机器白等 900ms（重连慢一截），慢的机器 900ms 还不够（踩回 #18）。
- **修法**：**等它安静** —— 记录最后一次收到输出的时刻，输出停了 250ms 就认为就绪
  （上限 3 秒兜底）。快的机器 ~0.4 秒走完，慢的机器自动多等。
- **教训**：**固定延时是在赌对面的速度。** 能观察到「完成」的信号就别赌 ——
  这里的信号就是「输出停了」。

## 43. `cd android` 之后又用相对路径装包 —— 静默装了个旧包，白查半小时
- **症状**：加了日志、重新构建、重新安装，**logcat 里一条新日志都没有**。
  于是开始怀疑服务没起、`Log` 被过滤、tag 太长…… 全是错的方向。
- **根因**：命令是 `cd android && ./gradlew … ; adb install -r android/app/build/…`。
  `cd` 之后那个相对路径不存在了，而我把 install 的输出 `>/dev/null 2>&1` 吞掉了，
  **失败完全看不见**，跑的还是上一版 APK。
- **修法**：装包一律用绝对路径（`"$PWD/android/app/build/..."`），
  而且**别把 install 的输出吞掉** —— 它就一行，留着。
- **教训**：`>/dev/null 2>&1` 吞掉的不只是噪音，还有「这一步根本没成功」。
  这和 TROUBLESHOOTING #19（`$?` 拿的是管道最后一个命令的退出码）是同一类错误：
  **把失败藏起来，然后去别处找原因。**

## 44. 模拟器重装清数据，把重建现场做成脚本（`dev/seed.sh`）
- 每次 `adb install -r` 之后：主机列表没了、App 的密钥重新生成（服务器上的
  `authorized_keys` 立刻失效）。手工重来一遍要点十几下 UI，我重复了十几次才去写脚本。
- `dev/seed.sh` 一条命令做完：取公钥 → 装进本机和 station 的 authorized_keys →
  用 `ssh-keyscan` 拿主机指纹预置进 hosts.json（连指纹确认框都省了）→ 重启 App。
- **教训**：**同一段手工操作做到第三遍就该写脚本了**，我做到了第十几遍。

## 45. 后台盯梢连一台**没装 hook** 的机器会陷入无限重连
- **症状**：某台机器的盯梢一直连不上，日志里反复「连上了 → 断了」。
- **根因**：`tail -n 300 -f ~/.yxi/events.jsonl` 在**没装 yxi-hook 的机器上没有那个目录**，
  `touch` 失败 → tail 起不来 → 通道立刻 EOF → 退避重连 → 每次都要完整握手 + 认证一遍。
- **修法**：命令前面加 `mkdir -p $HOME/.yxi`。建好之后它就只是**一条永远没有内容的流** ——
  正是「没装就静悄悄」该有的样子。
- **教训**：**「优雅降级」不会自己发生**。我在 `Host.watch` 的注释里写着「没装就一直静悄悄，
  不会报错」，但代码里并没有实现那句话 —— 注释写的是意图，不是事实。

## 46. ⭐⭐ 权限提示的脚注**没有 `to navigate`** —— 最该认出来的一种，一开始完全认不出来
- **症状**：G6 的「等你选」卡片对 `AskUserQuestion` 有效，对**权限提示**完全没反应。
- **根因**：`Prompt.isFooter` 拿 `to navigate` 当锚。真机上抓到两种脚注：
  - `Enter to select · ↑/↓ to navigate · Esc to cancel`（AskUserQuestion / 计划批准）
  - `Esc to cancel · Tab to amend · ctrl+e to explain`（**权限提示，没有 navigate**）
- **修法**：改用共同点 `to cancel` 当锚。测试里放了**真机原样抄下来的**权限提示样本。
- **教训**：拿样本推规则时，样本的**覆盖面**比样本的精确度更要紧。
  我用两种样本推出了一条只对那两种成立的规则，而漏掉的那种恰好是最重要的。

## 47. ⭐ 「拒绝」是 **3** 不是 2 —— 硬编码一下就是永久放行
权限提示的三个选项是：
```
1. Yes
2. Yes, and always allow access to /tmp from this project   ← 这是「以后都别问」
3. No
```
通知按钮上要是把「拒绝」写死成 2，用户点一下 = **永久放行这一类操作**，
而且他以为自己拒绝了。所以按钮的号码和文案**一律从屏幕上读**，读不出来就不给按钮。
`PromptTest.权限提示` 钉住了这条。

## 48. ⭐ 通知按钮用 `PendingIntent.getService` 会被**静默挡掉**
- **症状**：通知上的按钮点了**完全没反应**，`logcat` 里**一条日志都没有**（连异常都没有）。
  按钮位置、坐标、PendingIntent 的 requestCode 全查过一遍，都没问题。
- **根因**：targetSdk 34+ 之后「从后台启动服务」被限制，通知动作里的
  `PendingIntent.getService` 会被系统直接吞掉。没有异常、没有日志。
- **修法**：改成 `PendingIntent.getBroadcast` + 一个 `BroadcastReceiver`
  （这本来就是通知动作的标准做法），接收器再去够那个活着的服务实例。
- **教训**：**「什么都没发生」也是一种症状，而且是最难查的一种。**
  查了好几轮才想到问题不在我的代码里，而在于我的代码压根没被调用。

## 49. fail-closed 该靠结构而不是靠代码写对
PRD 原本的设计是：hook 在 `PreToolUse` 阻塞最多 570 秒等手机回答，超时就输出 `"ask"`。
那条路上「自动放行」是**一个 if 写错就会发生**的事。

改成：**hook 只发通知，权限决定完全不经过它** —— 手机上点的按钮是往 TUI 送一个按键，
跟人在键盘上按是同一条路。于是：
- 手机连不上 → 没人按 → Claude Code 停在它自己的提示上等着
- `~/.yxi` 不可写 → 事件发不出去 → 同上
- hook 出任何错 → 它本来就不输出决定 → 同上

**「自动放行」这条路在结构上不存在**，而不是被小心地避开了。
`server/test_yxi.py::test_approval_fail_closed` 端到端验了这条：
`~/.yxi` 设成不可写 + 没人回答，40 秒后命令没执行、提示还挂着。

## 50. LazyColumn 的 content 里读 state，加进去的 item 不出现
- **症状**：用量卡怎么都不显示。日志证明数据拿到了、解析对了、state 也写了 ——
  但 `usage?.let { item(key="usage") { UsageCard(it) } }` 这一项就是不出现。
- **绕法**：把它挪到 `LazyColumn` **外面**（列表上方）。一挪就好了。
- **顺带更好**：用量是「今天还能干多少」的背景信息，本来就不该跟着会话列表滚走。
  所以这个绕法同时是更对的 UI。
- ⚠️ 我没有深究 Compose 内部为什么如此（可能跟 content lambda 的订阅时机有关），
  **所以这条记的是现象和绕法，不是根因** —— 别把它当结论引用。

## 51. 用量宁可不显示，也不能显示假的
`ccusage` 探测不到就**整块藏起来**：不显示 0，不显示「未知」，不画空进度条。
理由很实际：额度这种数字**你会照着它安排今天开不开大活**。
一个假的 0 会让你以为额度还很多，一个假的满格会让你不敢干活 —— 两种都比看不见糟。

主机列表上的用量是**缓存**（会话看板连上时探一次存下来），超过 30 分钟会标出
「几小时前」。显示旧数字不标时间，跟显示假数字是一个性质。

## 52. 附件不走对话内容，走**路径映射**
上传到 `/root/src/tmp/<会话名>/`，发送时在正文前面加一行 `[图片1] <绝对路径>`。
Claude 自己去 Read 那个文件 —— 我们不把图片内容塞进对话，也就不用管编码、大小、多模态格式。
实测：手机上传一张截图 → 那边的 Claude 直接把截图内容描述出来了。

清理那条 `find` 是刻意写死的：路径是常量（不接受外部输入）、`-xdev`、
`-type f`（**不跟符号链接**，`-L` 绝不加）、`-mindepth 2`（不动 tmp 根本身）、
删之前先把清单追加进 `.swept.log`。**会删文件的命令，每个参数都要说得出理由。**

## 53. ⭐ 只比「几号 + 选项文案」挡不住过期 —— 两个权限提示长得一模一样
- **怎么发现的**：在**设计测试**的时候发现的，不是跑出来的。
  我想构造「屏幕上换成另一个提示」的场景，写着写着意识到：
  两个权限提示的选项**完全相同** —— 都是 `1. Yes / 2. Yes, and always… / 3. No`，
  连标题都同样是 `Do you want to proceed?`。原来的检查照样放行。
- **后果**：你以为在批 A，实际批的是屏幕上换上来的 B。**而且没有任何提示。**
- **修法**：给整块提示算个指纹（1 号选项**往上 8 行**到脚注，去空白后哈希）——
  往上 8 行才能把命令正文圈进来，那才是区分两个提示的东西。
- **两条测试**：不同命令的两个提示指纹必须不同；同一个提示反复解析指纹必须稳定
  （不稳定的话每次抓屏都判「变了」，按钮永远按不动）。
- **教训**：**把测试写出来这件事本身就在做设计审查。** 这个洞不是测出来的，
  是「想不出怎么测」的时候暴露的。

## 54. `set -euo pipefail` 下 `grep` 没匹配会直接终止脚本
- **症状**：`dev/seed.sh` 只打印一行 `Success` 就结束了，**没有任何错误信息**，
  后面的步骤全没跑，App 数据也没写。
- **根因**：`PUB=$(… | grep -o … | cut …)`，grep 没匹配返回 1，`pipefail` 让整个
  命令替换返回 1，`set -e` 当场终止 —— **在我下一行那句「拿不到公钥就报错」之前**。
  我写了检查，但它永远轮不到执行。
- **修法**：`… || true`，让检查有机会跑。
- 跟 #19（`$?` 拿的是管道最后一个命令的退出码）、#43（装包失败被 `>/dev/null` 吞掉）
  是同一个家族：**把失败藏起来，然后去别处找原因。**

## 55. 输入框里那些「我没打过的话」是 Claude Code 的建议下一句
测试期间反复看到 tmux 会话的输入框里出现我从没输入过的中文
（「切到 default 模式再跑一次」「查这 6 个会话为啥都在等」…）。
一度怀疑是自己 `adb shell input text` 打错了会话，还为此紧张过一次。
**其实是 Claude Code 自己渲染的「建议下一句」（ghost text）。**
—— 也就是说那次并没有往别人的会话里打字。记下来免得下次又白紧张。

## 56. 自更新：模拟器的图形安装器装不上，但包是好的
- **现象**：App 里点「下载并安装」→ 系统弹「App not installed.」。
- **查证**：把 App 下载到 `cache/update/Yxi.apk` 的那个文件拉回服务器比对 ——
  **sha256 和服务器上的完全一致**；而且同一个文件用 `adb pm install -r` **装得上**。
  第一次失败时日志里还给了原因：`INSTALL_FAILED_VERIFICATION_FAILURE`。
- **结论**：检测 / 下载 / 完整性校验 / 权限处理 / 拉起安装器**这几段都验过了**，
  卡在模拟器自带的图形安装器上。真机上这是所有自更新 APK 的标准路径。
  ⚠️ **但我没有在真机上验过最后那一下** —— 别把它当已验证的功能。
- 顺带：`REQUEST_INSTALL_PACKAGES` 没授权时不能直接 `startActivity`（会被静默拒），
  要先判 `canRequestPackageInstalls()` 再把用户送去设置页。这条已经实测过。

## 57. 更新走 SFTP 不走 HTTP —— 为什么
- 仓库是私有的，GitHub Release 的 API 要 token，**把 token 塞进 APK 等于公开它**。
- 这个 App 到目前为止**除了那一条 SSH 连接之外没有任何网络面**。
  为了「查个版本号」引入 HTTP 客户端、证书校验、代理处理，不划算。
- 服务器是用户自己的，公司内网 / 防火墙后面照样能用。
- 代价：得自己 `./server/install.sh --publish <apk> <code> <name> [说明]` 把包摆上去。
  仓库将来转公开的话，再加一条 GitHub Release 的路子也不冲突。

## 58. ⭐ 「地址解析不了」的真正原因多半是**全角字符**
- **现象**：用户在真机上填 `216.36.108.147`，App 报「地址解析不了」。
  他看着那个地址觉得**完全正确** —— 因为全角句点 `．` 和半角句点 `.` 长得几乎一样。
- **同类**：中文输入法的全角数字 `２１６`、中文句号 `。`、零宽字符（**肉眼完全看不见**）、
  以及顺手带上的 `root@` / `:22` / `ssh://` / 前后空格。
- **修法**：
  - 输入时做安全归一（全角→半角、清空白和零宽）
  - 还有非 ASCII 就**当场指出是哪个字**：`地址里有个连不上的字符：「。」(U+3002)`。
    只说「解析不了」等于没说。
  - 报错文案也把它想解析的那个字符串**原样引出来**，并指出可疑字符。
- **教训**：**「用户输错了」不是结论，是问题的开始** —— 得回答「他为什么会输错、
  以及怎么让他看见」。这个坑在电脑上几乎不存在，在手机上很常见。

## 59. ⭐ 别在用户手指底下改他正在打的东西
- **经过**：为了让 `root@ip:2222` 自动拆进三个栏位，我做成了**边输边拆**。
  加了个「一次进来一大段就当粘贴」的启发式想避开逐字符的问题。
- **结果**：启发式不成立。输入法整词上屏、`adb input text` 成块送，都会触发 ——
  打到 `216.36.108.147:22` 那一刻 `22` 被切走当端口，剩下的 `22` 落回地址栏，
  变成 `216.36.108.14722` / 端口 `22`。**人手打一样中招。**
- **修法**：打字时只做**幂等且不改结构**的归一（全角→半角）；
  拆分放到**保存**时做，那时整串才完整；中间用一行预览告诉用户
  「保存时会拆成：地址 … · 用户名 … · 端口 …」——**告知，但不动他的输入**。
- 这跟 #40（会话看板在手指落下前重排）是**同一类错误**：
  界面在用户操作的过程中改变了操作对象。

## 60. ⭐ `detectVerticalDragGestures` 会把**横向拖拽也吃掉**
- **症状**：悬浮排列的卡片横滑完全不动，pager 像死了一样。不报错、没日志。
- **根因**：卡片上挂了 `pointerInput { detectVerticalDragGestures { … } }`（做「上滑归档」），
  而卡片铺满整页 —— 于是所有拖拽都先被它拿走，上层 pager 一个事件都收不到。
- **怎么定位的**：**把那个 pointerInput 摘掉**，横滑立刻恢复。
  跟 TROUBLESHOOTING #16 一样：**摘掉一个组件看还坏不坏，比继续猜有效得多。**
- **修法**：改用 `Modifier.draggable(orientation = Vertical, …)` ——
  它带方向锁，竖向归自己、横向让给上层滚动容器。
  **需要跟父级滚动共存的手势，就别用裸 `pointerInput`。**

## 61. ⭐ 定位用的 `LaunchedEffect` 键里带了会变的列表 → 每次刷新都把用户拽回去
- **症状**：卡片能滑一点点，然后自己弹回原来那张。看起来像「手势没生效」。
- **根因**：`LaunchedEffect(list, current) { scrollToPage(当前会话) }` ——
  而 `list` 每 5 秒随会话快照刷新一次，于是**每 5 秒把 pager 拽回去一次**。
- **修法**：加一个 `located` 标志，只定位一次。
- **教训**：**「打开时定位到某处」这种一次性行为，别写成对状态变化的响应。**
  我先去查手势了，因为现象长得像手势失效 —— 两个不同的 bug 叠在一起，
  症状完全一样（滑不动），这是查得慢的真正原因。

## 62. `enableEdgeToEdge` 下软键盘会盖住底部的工具条
- **症状**：终端模式弹出软键盘，**键盘工具条（esc / tab / ^C）整条不见了** ——
  而那恰恰是打字时最需要的几个键。
- **根因**：`enableEdgeToEdge` 之后窗口是铺满的，IME 弹出不会自动压缩内容，
  底部那一条就被键盘盖在下面了。`android:windowSoftInputMode="adjustResize"` 也救不了它。
- **修法**：工作区根部加 `Modifier.imePadding()`。
- **教训**：**edge-to-edge 是把「处理系统栏」这件事从系统手里接过来了**，
  状态栏、导航栏、IME 三处都得自己吃边距 —— 漏一个就有一处被盖住，
  而且只在特定状态下才看得见（这条只在键盘弹起时才暴露）。

## 63. 换了导航结构，测试脚本跟着废了
- `dev/seed.sh` 靠「点右上角公钥按钮」逼 App 生成密钥、再从 logcat 里捞公钥。
  公钥入口搬进设置页之后这条路断了，脚本报「没拿到公钥」，
  但**真正的原因跟公钥一点关系都没有**。
- 修法不是追坐标（那还会再断），而是**让被测对象自己说话**：
  `KeyManager` 每个进程报一行「本次使用 pub=…」，生成新密钥时再单独警告一行。
  公钥本来就要贴到服务器上去，不是秘密；而它一变就是所有机器同时失效的大事，
  这行日志本身就有排查价值。
- **教训**：**测试脚本别依赖界面坐标。** 界面一定会改，而脚本坏掉时的报错
  往往指向完全无关的地方。

## 64. ⭐ 模拟器一直走 `10.0.2.2`，「连公网 IP」这条路一次都没测过

**症状**：模拟器上一切正常，用户装上真机说「连不上服务器」。

**根因**：`dev/seed.sh` 里预置的主机是 `10.0.2.2` —— 模拟器的宿主机别名，
指向本机回环。所以 sshd 日志里 App 的连接**永远来自 `127.0.0.1`**。
用户走的是公网 IP → 完全另一条路径（真 TCP、真握手、可能有防火墙 /
fail2ban / 运营商），而这条路径从来没跑过。回环连通**不构成**公网连通的证据。

**修法**：`seed.sh` 里加一台走本机真实公网 IP 的主机（从 `ip -o addr` 取，不写死）。
排查「用户连不上」时，第一件事是去 sshd 日志看**源 IP**：全是 127.0.0.1
就说明你压根没测过用户走的那条路。

**怎么避开**：测试环境和用户环境不同的每一处，都要问一句「这条差异会不会
正好盖住 bug」。宿主机别名、回环、内网直连，都属于「会盖住」的那一类。

## 65. ⭐⭐ 开发脚本把**用户真手机的公钥**从服务器上删掉了

**症状**：用户「连不上服务器」，同时「检查更新也失败」。服务器这头查什么都正常：
端口开着、从外部机器 SSH 得通、sshd 配置没问题、fail2ban 没封他。
日志里**找不到他手机的任何痕迹** —— 不是「连上被拒」，是根本没到认证。

**根因**：`KeyManager` 给**每一台** Android 设备生成的公钥注释都是 `yxi@android`
—— 模拟器是这个，用户的真手机也是这个。而 `dev/seed.sh` 装模拟器公钥时写的是：

```bash
grep -v yxi@android ~/.ssh/authorized_keys > /tmp/.ak   # 删掉所有 yxi@android
echo "ssh-ed25519 $PUB yxi@android" >> /tmp/.ak          # 只把模拟器的加回去
```

于是**每跑一次开发脚本，就把用户真手机踢出服务器一次**，station 上也一样。
用户那把钥匙的指纹在整个 sshd 日志里一次都没出现过。

**为什么难查**：改的是开发脚本，坏的是生产授权，两者在脑子里根本不挨着；
而且服务器侧所有常规检查（端口、配置、防火墙、日志）全是绿的 ——
唯一的线索是「日志里连他的失败记录都没有」，这个「没有」很容易被当成「他没试」。

**修法**：模拟器自己的钥匙用**专属标签** `yxi@emulator`，过滤也只过滤这个标签。
`server/test_yxi.py::test_seed_never_evicts_a_real_phone` 守住：seed.sh 的**代码**里
出现 `yxi@android` 就红。

**怎么避开**：任何**自动改共享状态**的脚本（authorized_keys、known_hosts、
数据库、配置文件），删除条件必须能唯一认出「我自己写的那条」，
绝不能用一个别人也会用的标签。这类脚本改的是**别人也在用的东西**，
`grep -v <一个不够独特的串>` 就是一把没有刀鞘的刀。

## 66. fail2ban 会让「密码打错几次」变成「连接超时」

**症状**：先是认证失败，重试几次之后变成连不上 / 超时，看起来像网络断了。

**根因**：本机 fail2ban `maxretry=5 / findtime=600 / bantime=600` ——
10 分钟内失败 5 次，这个 IP 被封 10 分钟。封的是**整个 IP**，
表现从「认证失败」变成「TCP 连不上」，症状完全换了一副面孔。

**怎么避开**：手机在移动网络下 IP 还会变，封了换个基站可能就好了 ——
这种「时好时坏」最容易误判成 App 的 bug。用户报「一开始报错、后来干脆连不上」，
先去 `fail2ban-client status sshd` 看 banned 列表，别急着改代码。
