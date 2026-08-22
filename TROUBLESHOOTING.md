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
