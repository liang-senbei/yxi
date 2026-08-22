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
