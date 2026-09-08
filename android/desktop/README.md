# Yxi Desktop（Windows 桌面版）

> 老板 2026-09-08：「构建一个 Windows 版本，像 Claude Desktop / ChatGPT 的 Windows 版那样。」

## 是什么

跟手机 App 同一套后端思路：**Claude Code 跑在用户自己的服务器上**，客户端只是 SSH 上去看转录、发话、审批、开终端。
桌面版用 **Compose Multiplatform（JVM）** 写，和手机端共用 `:core`（`android/core/`，纯 Kotlin：SSH 会话 / SFTP / 转录解析 / 提示解析 / 模型与用量等）。
不共用的是界面层（手机端 `app/` 的 Compose 是 Android 专属的：Context、Activity、MediaPlayer…）和本地存储（手机用 SharedPreferences / filesDir，桌面用 `%APPDATA%\Yxi`）。

## 模块

| 模块 | 内容 | 平台 |
|---|---|---|
| `:core` | `agent/`（Transcript、Live、Prompt、Model、Usage、Quota、Slave、ConfigRemote、TailscaleStatus、Plat、Tr）、`ssh/`（SshSession、Sftp、Crypto、HostConfig、HostKeys、Shell）、`ui/En`（英文表） | JVM（Android + 桌面） |
| `:app` | 手机 App | Android |
| `:desktop` | 桌面 App，`app.yxi.desktop.MainKt` | JVM 桌面（Windows 为主，macOS / Linux 也能跑） |

三个缝（core 里不许 `import android.*`）：
- `Plat.log`（Android 接 `android.util.Log`，桌面接 println / 文件）、`Plat.debug`
- `Tr.fn`（翻译：Android 接 `ui/I18n.t`，桌面接自己的语言开关 + `En.map`）
- `HostKeys`（主机指纹校验：Android 用 `KnownHosts`（存 HostStore），桌面用 `%APPDATA%\Yxi\known_hosts`）

## 构建

```bash
cd android
./gradlew :desktop:run                          # 本机有显示器时直接跑（服务器没有；去 Mac mini 跑：~/yxi-build/jdk/… 装了 JDK 17）
./gradlew :desktop:compileKotlin                # 只编桌面（别跑 :app 的任务，机器是共用的）
./gradlew :desktop:packageUberJarForCurrentOS   # 一个 jar，java -jar 能跑 → desktop/build/compose/jars/Yxi-linux-x64-1.0.0.jar
./gradlew :desktop:packageUberJarForCurrentOS -Pyxi.os=mac   # 在 Linux 上给 Mac 打（Skia 原生库换成 macos）→ Yxi-macos-arm64-1.0.0.jar
./gradlew :desktop:packageUberJarForCurrentOS -Pyxi.os=win   # 同上给 Windows → Yxi-windows-x64-1.0.0.jar
./gradlew :desktop:packageMsi                   # ⚠️ 只能在 Windows 上跑（jpackage 不能跨平台）—— 用 GitHub Actions 的 windows 跑（.github/workflows/desktop.yml）
```

- `-Pyxi.os` 只换 `compose.desktop.<平台>` 这一个依赖（差别就是 Skia 的 .so/.dylib/.dll），jar 名跟着目标平台走。
- ⚠️ uber jar 里要剔掉 `META-INF/*.SF|RSA`：BouncyCastle 是签过名的 jar，摊平后 `java -jar` 报 `Invalid signature file digest`（build.gradle.kts 里已做）。

## 冒烟

`java -jar <jar> --smoke`：开窗口 3 秒自动退出、打印 `smoke ok`、退出码 0 —— 证明 Compose + Skia 在那个平台起得来。

- 服务器：跑不了。装的是 headless 版 OpenJDK（没有 `libawt_xawt.so`），`xvfb-run` 起来也是 `HeadlessException: no headful library support`；要在服务器冒烟得另装带 AWT 的 JDK。
- Mac mini：`scp desktop/build/compose/jars/Yxi-macos-arm64-1.0.0.jar mac:/tmp/`，然后
  `ssh mac '~/yxi-build/jdk/jdk-17.0.20.1+1/Contents/Home/bin/java -jar /tmp/Yxi-macos-arm64-1.0.0.jar --smoke'`
  （非交互 shell 的 PATH 里没有 java，要写绝对路径）。不带 `--smoke` 后台跑几秒 + `screencapture -x ~/yxi-build/shots/desktop.png` 可以看窗口长什么样。
- Windows：CI 里跑（下面）。

## CI（Windows MSI）

`.github/workflows/desktop.yml`：手动触发或推 tag `desktop-v*`，windows-latest + JDK 17 跑 `packageMsi` + uber jar + `--smoke`，
产物 artifact `Yxi-windows`（`Yxi-1.0.0.msi` + jar）。

**Android SDK 的问题**：settings 里带着 `:app`，Gradle 默认会把它也配置一遍。实测（AGP 9.3.1，无 `local.properties`、`ANDROID_HOME` 未设）
只跑 `:desktop` 的任务**配置阶段不要 SDK** —— AGP 把 SDK 检查推迟到了 `:app` 自己的任务执行时。所以 workflow 里没装 setup-android
（windows-latest 本身也预装了 SDK）。哪天 AGP 升级后又要了，两条路都实测过能过：`--configure-on-demand`（只配置 :desktop + :core），或 `android-actions/setup-android`。

## 计划（MVP → 完整）

1. **主机**：列表 / 新增（地址、端口、用户名、私钥文件或密码）/ 指纹确认 / 存 `%APPDATA%\Yxi\hosts.json`（私钥用系统凭据？先明文文件 + 0600，后接 DPAPI）
2. **会话**：连上主机后列 `cc-*` tmux 会话（跟手机端 SessionsScreen 同一套命令），新建 / 接回 / 临时会话
3. **对话**：转录尾随（`tail -F` + `Transcript` 解析）、工具卡（同名连续合并）、审批 / 选择器（`Prompt` 解析 + tmux send-keys）、发话（`Sender` 逻辑）、附件（SFTP 上传）
4. **终端**：`tmux capture-pane -e` 轮询渲染（先做只读 + 输入行；完整终端仿真后做）
5. **账号**：登录（Logto）、会员、额度 —— 手机端 `Account.kt` 的 HTTP 部分抽进 core
6. **打包**：GitHub Actions windows-latest 跑 `packageMsi`；托管在 `yxi.keuury.com/desktop/`；App 内「桌面版」入口
7. 之后：通知（托盘）、多窗口、快捷键、深色主题、音游（Compose Canvas 代码可直接复用）
