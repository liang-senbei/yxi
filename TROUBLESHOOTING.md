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
