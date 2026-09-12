# Yxi Desktop（Windows 桌面版）

## 工作台开发分支（2026-09-13）

网页预览接入JCEF Maven 146.0.10，按目标平台打入对应native依赖，首次使用从包内解压到本地版本目录，不从任意镜像下载可执行文件。内核版本及打包坐标见 [JCEF发行记录](https://github.com/jcefmaven/jcefmaven/releases/tag/146.0.10)。浏览器实例使用独立CefRequestContext；远端回环端口通过SSH转发为本机自动分配的端口，地址栏显示逻辑远端地址。

Linux root默认不启动浏览器。仅受控测试可使用 `-Dyxi.browser.localFixture=true`，该模式限制页面及资源为本地来源，并在root测试时使用无沙箱启动参数；不得用于浏览不可信网页。Windows浏览器尚待完整实机验收。本轮Linux验证包含真实WebSocket更新、SSH重连、Cookie上下文隔离、密码字段不进入选择、反馈草稿、收起恢复和退出。

JCEF退出有明确顺序：先dispose各client，借助窄适配器读取库内同步的浏览器列表，确认cleanup完成后才dispose CefApp；不能同时在两个方向持有CefApp和client列表锁。线程转储曾确认相反锁序导致Java级死锁，修复后退出测试必须成功，不能靠强杀当作通过。

桌面构建、打包与独立 jar 运行统一使用 **JDK 21**；Markdown 0.44.0 的 JVM 模块字节码为 Java 21，Java 17 虽可完成部分编译检查，打开文档会报 UnsupportedClassVersionError。Android/core 的目标保持原状。Windows Setup 会自带匹配运行时，用户不需自行安装 Java。

文件预览支持 Markdown 表格、源码/分栏、文本保存与远端更新检测。文本限 1 MB，图片限 8 MB；远端编辑保存需要 Python 3，使用内容版本校验与原子替换。变更源文件有冲突时保留本地编辑并让用户选择合并，未承诺与任意第三方编辑器之间的文件系统级原子 CAS。

验证：`JAVA_HOME=<JDK21> ./gradlew :desktop:test :desktop:packageUberJarForCurrentOS`；`python3 dev/test-document-save.py` 验证真实保存脚本；`JAVA_HOME=<JDK21> YXI_TEST_SSH_KEY=<本机已授权私钥> bash dev/desktop-document-e2e.sh` 在独立 home/display、临时文档和惰性 tmux 测试会话上验证 SSH/SFTP 预览，不触碰运行中的 Agent。

本节为当前工作台分支要求；下文保留的早期发版记录不代表最新运行时与功能状态。

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
./gradlew :desktop:test                         # UpdaterTest：挑更新 / 版本比较的纯逻辑
./gradlew :desktop:packageUberJarForCurrentOS   # 一个 jar，java -jar 能跑 → desktop/build/compose/jars/Yxi-linux-x64-1.0.1.jar
./gradlew :desktop:packageUberJarForCurrentOS -Pyxi.os=mac   # 在 Linux 上给 Mac 打（Skia 原生库换成 macos）→ Yxi-macos-arm64-1.0.1.jar
./gradlew :desktop:packageUberJarForCurrentOS -Pyxi.os=win   # 同上给 Windows → Yxi-windows-x64-1.0.1.jar
./gradlew :desktop:createDistributable          # jpackage app-image（自带 runtime，解压约 176 MB）→ desktop/build/compose/binaries/main/app/Yxi/ —— Velopack 的输入
./gradlew :desktop:packageMsi                   # 旧路线的 MSI（向导式、无自动更新），只能在 Windows 上跑；留着应急
```

- **版本号只改一处**：`build.gradle.kts` 的 `packageVersion`。jpackage 把它写进 app-image 的 `app/Yxi.cfg`（`java-options=-Djpackage.app-version=…`），
  运行时 `Updater.version` 读的就是这个系统属性，CI 也从 cfg 里读出来给 `vpk --packVersion` —— 三处同源，不会对不上。
- `-Pyxi.os` 只换 `compose.desktop.<平台>` 这一个依赖（差别就是 Skia 的 .so/.dylib/.dll），jar 名跟着目标平台走。
- ⚠️ uber jar 里要剔掉 `META-INF/*.SF|RSA`：BouncyCastle 是签过名的 jar，摊平后 `java -jar` 报 `Invalid signature file digest`（build.gradle.kts 里已做）。

## 冒烟

`java -jar <jar> --smoke`：开窗口 3 秒自动退出、打印 `smoke ok`、退出码 0 —— 证明 Compose + Skia 在那个平台起得来。

- 服务器：跑不了。装的是 headless 版 OpenJDK（没有 `libawt_xawt.so`），`xvfb-run` 起来也是 `HeadlessException: no headful library support`；要在服务器冒烟得另装带 AWT 的 JDK。
- Mac mini：`scp desktop/build/compose/jars/Yxi-macos-arm64-1.0.1.jar mac:/tmp/`，然后
  `ssh mac '~/yxi-build/jdk/jdk-17.0.20.1+1/Contents/Home/bin/java -jar /tmp/Yxi-macos-arm64-1.0.1.jar --smoke'`
  （非交互 shell 的 PATH 里没有 java，要写绝对路径）。
  ⚠️ **直接在 SSH 里跑会 `HeadlessException: not running in a desktop session`**（SSH 会话是 `launchctl managername` = Background，不在控制台用户的 Aqua 会话里；`launchctl asuser` 要 root）。办法：写个一次性 LaunchAgent plist（ProgramArguments 跑上面那条，StandardOutPath 落 /tmp），`launchctl bootstrap gui/$(id -u) /tmp/x.plist`，job 就在 GUI 会话里跑，日志里看 `smoke ok`；完了 `launchctl bootout gui/$(id -u)/<Label>`。2026-09-08 这样实测过：`smoke ok`、退出码 0。`screencapture -x` 在这两种上下文里都被 TCC 拦（`could not create image from display`），要截图得有人在 Mac 上给终端开屏幕录制权限。
- Windows：CI 里跑（下面）。

## 打包 / 安装 / 更新：Velopack（= Claude Desktop 的路线）

> 老板 2026-09-08：「我们现在这种 exe 的构建方式就是 codex 和 Claude desktop 同款的吗，我希望现代点的构建方式」。

拆包结论（`design/desktop-reference.md` §1.1 / §2.1）：**Claude Desktop = Electron + Squirrel.Windows**（一键 `Setup.exe`，per-user 装到 `%LOCALAPPDATA%`，没有向导、不弹 UAC，后台从 `RELEASES` 源静默差量更新）；**Codex = Electron + 微软商店 MSIX**。
我们技术栈不换（Compose 共用 `:core` 是根本），换的是打包器：**[Velopack](https://velopack.io)** —— Squirrel.Windows 作者做的继任项目（同一套 Setup.exe / nupkg / `RELEASES` 思路，Rust 重写，还给 Squirrel 老用户留了 `RELEASES` 迁移文件）。它跟语言无关：输入就是「一个目录 + 主 exe」，jpackage 的 app-image 直接喂进去。

| | 旧：jpackage MSI | 新：Velopack |
|---|---|---|
| 安装 | 向导 3 步、107 MB，可能弹 UAC | 双击 `Yxi-win-Setup.exe`，装完直接拉起；per-user，不弹 UAC，不问问题 |
| 装在哪 | `Program Files`（可选） | `%LOCALAPPDATA%\Yxi\current\`（`Update.exe` 在上一级） |
| 更新 | 手动下新 MSI 再装一遍 | 应用内每 6 小时查 `releases.win.json`，静默下好，横幅「立即重启」 |
| 卸载 | 设置 → 应用 | 同左（Velopack 会注册 Uninstall 项） |

流水线：`createDistributable`（jpackage app-image）→ `vpk pack`（CI，见下）→ `Releases/` → `publish.sh` → `https://yxi.keuury.com/desktop/`。

### 查过的结论（docs.velopack.io，2026-09-08，vpk 1.2.0）

- **Velopack 没有 Java SDK**（官网：C# / Rust / JS / C++ / Python 就绪，Java = planned）。但协议极简，自己实现就是 `Update.kt` 那几十行：
  - 查：`GET <feed>/releases.win.json` → `{"Assets":[{"PackageId","Version","Type":"Full"|"Delta","FileName","SHA1","SHA256","Size"}]}`，挑 `Type=Full` 里最高版本，比 `jpackage.app-version` 新才动。
  - 下：`GET <feed>/<FileName>` 存到 `%LOCALAPPDATA%\Yxi\packages\`（Velopack 自己的目录，它会清），校验 SHA256；上次下好没重启的直接复用。
  - 装：`Update.exe --silent apply --package <nupkg> --waitPid <我们的 pid>`，然后自己 `exitProcess(0)`。Update.exe 等我们退干净、整个换掉 `current\`、默认重启新版（环境变量 `VELOPACK_RESTART=true`）。
- **`Update.exe` 只有 4 个子命令**（[reference/cli/content/update-windows](https://docs.velopack.io/reference/cli/content/update-windows)）：
  `apply`（`--package <file>` / `--wait` / `--waitPid <pid>` / `--norestart` / `-- <app 参数>`；不给 `--package` 就用 `packages\` 里最新的 full 包）、`start`、`patch`（`--old --delta --output`，本地合成差量）、`uninstall`；全局 `--silent --verbose --log --rootDir --packageDir`。
  **没有 `check` / `download`** —— 那是各语言 SDK 里做的，所以我们自己走 HTTP（JDK 自带 `HttpClient`，零依赖）。
- **安装布局**（[integrating/overview](https://docs.velopack.io/integrating/overview)、[packaging/operating-systems/windows](https://docs.velopack.io/packaging/operating-systems/windows)）：
  `%LOCALAPPDATA%\Yxi\Update.exe`、`Yxi.exe`（stub，转拉 current 里的那个，快捷方式指它所以换版本不断）、`current\Yxi.exe` + `current\app\`（jar + `Yxi.cfg`）+ `current\runtime\` + `current\sq.version`、`packages\`。
  更新只换 `current\`；用户数据在 `%APPDATA%\Yxi`（`Store.dir`：hosts.json / known_hosts / prefs.json），不在 `current\` 里，不受影响。
- **钩子**（[integrating/hooks](https://docs.velopack.io/integrating/hooks)）：装 / 升 / 卸时 Velopack 会用 `--veloapp-install|updated|obsolete|uninstall <v>` 拉起主 exe，要求 15–30 秒内退出，否则被杀
  → `Updater.boot(args)` 看到 `--veloapp-*` 直接 `exitProcess(0)`，不开窗。首次运行 / 更新后重启是环境变量 `VELOPACK_FIRSTRUN` / `VELOPACK_RESTART`，正常跑。
- **jpackage 启动器 + Velopack 的坑**：
  1. `vpk pack` 默认静态扫主 exe 找 .NET 的 `VelopackApp.Run()`（不运行 exe；非 .NET 的 exe 会静默跳过）—— 我们照样加 `--skipVeloAppCheck`，明确。
  2. `Yxi.exe` 按自身位置找 `app\Yxi.cfg` 和 `runtime\`，整个目录搬进 `current\` 没问题（可重定位）。
  3. 应用里找 `Update.exe`：从 `java.home`（= `current\runtime`）往上翻；`java -jar` / `gradle run` / 旧 MSI 装的翻不到就不查更新（`Updater.version` 显示 `dev`）。
  4. Setup.exe 装完会直接拉起 `Yxi.exe`（`Setup.exe --silent` 才不拉），第一次启动就带 `VELOPACK_FIRSTRUN`。
  5. 没签名 → 见下面 SmartScreen。
- **差量包**：`vpk pack` 只在输出目录里已有上一版 full 包时才生成 delta（CI 里要先 `vpk download http --url https://yxi.keuury.com/desktop/ --outputDir Releases`），客户端还得自己 `Update.exe patch` 合成。
  `Update.kt` 现在只下 full 包（≈ 100 MB），够用，先不做；要做时 workflow 里那条注释就是入口。
- 没验证的：**Setup.exe 没在真 Windows 上装过**（没有可以随便装东西的 Windows 机；老板机上装要先问）。CI 只证明包能出、jar 能开窗。

## CI（Windows Setup.exe）

`.github/workflows/desktop.yml`：手动触发（`gh workflow run desktop.yml --ref <分支>`，工作流文件要在那个分支上）或推 tag `desktop-v*`，windows-latest + JDK 17：
`createDistributable` + uber jar → `dotnet tool install -g vpk --version 1.2.0` →
`vpk pack --packId Yxi --packVersion <cfg 里读的> --packDir <app-image> --mainExe Yxi.exe --packTitle Yxi --icon icon.ico --noPortable --skipVeloAppCheck --outputDir Releases`
→ `--smoke` → artifact **`Yxi-windows`**：`Releases/`（`Yxi-win-Setup.exe`、`Yxi-<v>-full.nupkg`、`releases.win.json`、`RELEASES`、`assets.win.json`）+ `build/compose/jars/*.jar`。

首跑 run [34249975893](https://github.com/liang-senbei/yxi/actions/runs/34249975893)（2026-09-08，3.5 分钟）：`Yxi-win-Setup.exe` **114 MB**（109 MiB；nupkg 109.6 MB，解压 175 MB，其中 runtime 73 MB）、jar 87 MB、`smoke ok`。
体积跟 MSI 一个量级 —— 大头是 JDK runtime + Compose/Skia，Velopack 只是换了壳；要瘦身得 jlink 裁模块（另一件事）。
vpk 会警告 `71 file(s) will not be signed`（没签名，见下）。

**Android SDK 的问题**：settings 里带着 `:app`，Gradle 默认会把它也配置一遍。实测（AGP 9.3.1，无 `local.properties`、`ANDROID_HOME` 未设）
只跑 `:desktop` 的任务**配置阶段不要 SDK** —— AGP 把 SDK 检查推迟到了 `:app` 自己的任务执行时。所以 workflow 里没装 setup-android
（windows-latest 本身也预装了 SDK）。哪天 AGP 升级后又要了，两条路都实测过能过：`--configure-on-demand`（只配置 :desktop + :core），或 `android-actions/setup-android`。

## 发布（给老板 / 用户下载）

```bash
gh run list -R liang-senbei/yxi -w desktop.yml -L 3          # 找 run id
android/desktop/publish.sh <run-id>                            # gh run download → rsync Releases/ 到 hk13:/var/www/yxi/desktop/（--chmod=F644，#318）→ curl -sI 验 200
```

- 下载页给 **`https://yxi.keuury.com/desktop/Yxi-win-Setup.exe`**；装了的客户端读同目录的 `releases.win.json` 自更（feed 地址写死在 `Update.kt` 的 `FEED`）。
- 旧的 MSI `https://yxi.keuury.com/desktop/Yxi-1.0.0.msi` 留着（老板还在用那个地址），脚本只增不删。**截至 2026-09-08 还没真发过 Velopack 版**（脚本 `bash -n` 过，没跑）。
- 404 = nginx 白名单（hk13 `/etc/nginx/snippets/yxi-dl.conf` 的 `location /desktop/`）没放行；403 = 文件权限（TROUBLESHOOTING #318）。
- 发新版 = 改 `packageVersion` → 跑 CI → `publish.sh`。feed 里只列这次 CI 出的版本，服务器上旧 nupkg 留着不碍事。

## SmartScreen / 代码签名（还没买，先记账）

现状：`Setup.exe` / `Update.exe` / `Yxi.exe` 都没签名 → 用户第一次运行会被 SmartScreen 拦（「Windows 已保护你的电脑」→「更多信息 → 仍要运行」），
Velopack 文档也说不签可能被当病毒。信誉是按二进制攒的，**每发一个新版本都从头攒**。三条路（价格 2026-09 查的，大概数）：

1. **Azure Artifact Signing**（原 Trusted Signing）：Basic **$9.99 / 月**（5000 次签名），证书由微软发、短期轮换，SmartScreen 直接认。
   条件：要过身份验证 —— 企业要有可核验的经营记录（微软要求 3 年以上），个人开发者目前只对美国 / 加拿大开放；国内主体基本走不通。
   接法：`vpk pack … --azureTrustedSignFile metadata.json`（`{"Endpoint","CodeSigningAccountName","CertificateProfileName"}`，runner 上要 .NET 8 runtime + Azure 登录）。
2. **OV 代码签名证书**（DigiCert / Sectigo / SSL.com…）：约 **$200–400 / 年**。2023-06 起私钥必须在硬件 token 或云 HSM 里，CI 上得走云签（SSL.com eSigner、DigiCert KeyLocker）；
   OV 的 SmartScreen 信誉照样要攒，EV（$300–700 / 年）才是即时信誉。接法：`vpk pack … --signParams "/tr http://timestamp.digicert.com /td sha256 /fd sha256 /a"`（signtool 参数原样）
   或 `--signTemplate "<签名工具> sign … {{file}}"`；vpk 会签 Setup.exe、Update.exe 和 packDir 里所有 exe/dll（`--signExclude` 排除、`--signParallel` 并发）。
3. **微软商店 MSIX**（Codex 的路）：商店代签，没有 SmartScreen；注册费**一次性 $19**（个人）/ $99（公司）。
   但 jpackage 不出 MSIX（要 MSIX Packaging Tool / makeappx 另做），更新也走商店 —— 走这条就是放弃 Velopack 的更新机制，两套不共存。

结论：先无签名发（SmartScreen 点两下能过，老板自己用），要给外人下再买 2；主体在国内，1 走不通。

## 计划（MVP → 完整）

1. **主机**：列表 / 新增（地址、端口、用户名、私钥文件或密码）/ 指纹确认 / 存 `%APPDATA%\Yxi\hosts.json`（私钥用系统凭据？先明文文件 + 0600，后接 DPAPI）
2. **会话**：连上主机后列 `cc-*` tmux 会话（跟手机端 SessionsScreen 同一套命令），新建 / 接回 / 临时会话
3. **对话**：转录尾随（`tail -F` + `Transcript` 解析）、工具卡（同名连续合并）、审批 / 选择器（`Prompt` 解析 + tmux send-keys）、发话（`Sender` 逻辑）、附件（SFTP 上传）
4. **终端**：`tmux capture-pane -e` 轮询渲染（先做只读 + 输入行；完整终端仿真后做）
5. **账号**：登录（Logto）、会员、额度 —— 手机端 `Account.kt` 的 HTTP 部分抽进 core
6. **打包**：✅ Velopack 一键 Setup.exe + 应用内更新（本文档）；待办：`Updater.boot(args)` 接进 Main.kt、`UpdateBanner()` 放进侧栏、App 内「桌面版」入口、真 Windows 装一遍
7. 之后：通知（托盘）、多窗口、快捷键、深色主题、音游（Compose Canvas 代码可直接复用）
