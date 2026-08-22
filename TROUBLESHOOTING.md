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
