# 桌面版参照：Claude Desktop / Codex(ChatGPT) Desktop 拆包结论

> 2026-09-08 拆包。目的：给 Yxi Windows 桌面版（Compose Multiplatform，`android/desktop/`）定"桌面产品形态"，不抄代码。
> 值都来自安装包里的静态文件（主进程 JS、渲染层 CSS、i18n 字符串、清单）；没有跑起来截图。标了「推断」的是从代码常量倒推的。
> 安装包和解出来的东西在服务器 scratchpad `…/scratchpad/ref/`，不进仓库；它们的 CSS / JS / 字体 / 图标一律不复制进仓库，这里只引用值。

## 0. 拿到了什么

| 包 | 版本 | 大小 | 来源 | 说明 |
|---|---|---|---|---|
| `AnthropicClaude-1.49585.0-full.nupkg` | 1.49585.0（2026-09-08 发布） | 245.6 MB | `https://downloads.claude.ai/releases/win32/x64/RELEASES` → 同目录下 nupkg | Squirrel.Windows 正式更新源；nupkg = zip，`lib/net45/` 里是整个 Electron 应用 |
| `Claude-x64.msix` | 1.46388.4（2026-09-05） | 266.5 MB | `https://claude.ai/api/desktop/win32/x64/msix/latest/redirect` → `downloads.claude.ai/releases/win32/x64/<ver>/Claude-<sha>.msix` | 企业部署用的 MSIX，同一套 app.asar |
| `ChatGPT-x64.msix` | 26.901.6511.0（2026-09-06） | 796.6 MB | `https://persistent.oaistatic.com/codex-app-prod/ChatGPT-x64.msix` | **Codex 桌面版在 Windows 上就是"ChatGPT"桌面版**（包身份 `OpenAI.Codex`，显示名 ChatGPT，协议 `codex://`）。所以任务里"Codex Windows"和"ChatGPT Windows"是同一个包 |
| `Codex.dmg` | 26.901.51231 | 643.0 MB | `https://persistent.oaistatic.com/codex-app-prod/Codex.dmg` | 和 `ChatGPT.dmg` 同一文件（大小、时间戳一致），macOS 版，用来对照 |

没拿到：
- `Claude-Setup-x64.exe`（官网"Download for Windows"按钮指向 `https://claude.ai/api/desktop/win32/x64/setup/latest/redirect`）：服务器 curl 带浏览器 UA / Referer / Sec-Fetch 头都被 Cloudflare 403；`msix` 那条同样的 redirect 接口能过（307）。Setup.exe 只是 Squirrel 引导器（内嵌同一个 nupkg + Update.exe），拿到 nupkg 已经等价。旧桶 `storage.googleapis.com/osprey-downloads-…/nest-win-x64/Claude-Setup-x64.exe`（0.14.10，2025-10）仍可下，太旧没用。
- Codex 单独的 Windows exe / msi：官方文档明说"Doesn't provide a standalone MSI or non-Store EXE"，只有 Store（`winget install --id 9PLM9XGG6VKS -s msstore`）和上面的 Store 签名 MSIX 直链。

解包方法：MSIX / nupkg 直接 `unzip`；dmg 用 `7z x`；`app.asar` 用 60 行 python（8 字节 pickle 头 + JSON 目录 + 文件拼接）解，`app.asar.unpacked` 是原生模块。

---

## 1. Claude Desktop（Windows）

### 1.1 打包与分发

- **技术**：Electron（`claude.exe` 246 MB，Chromium 版本随 Electron），主进程 Vite 打包（`.vite/build/index.pre.js` + 170 个 chunk），渲染层只有**标题栏 + 错误页**是本地 HTML，**聊天 / 设置 / 会话列表全部从 claude.ai 远程加载**（`main_window/index.html` 首行注释原话："this is the html for app title bar and error UI. everything else gets loaded from claude.ai"）。所以它本质是"claude.ai 网页 + 一层原生壳（托盘、快捷键、SSH、VM、通知、深链）"。
- **两种安装器**：
  1. Squirrel.Windows（个人用户，官网按钮）：`Setup.exe` → 装到 `%LOCALAPPDATA%\AnthropicClaude\app-<版本>\`（代码里用 `(.*\\AnthropicClaude)\\app-[^\\]+\\` 识别安装根），**per-user，不要管理员**。更新源 = `https://downloads.claude.ai/releases/win32/x64/RELEASES`（一行 `SHA1 文件名 大小`，带 delta 包）。
  2. MSIX（企业 Intune / SCCM / `Add-AppxPackage`，per-user 注册）：`claude.ai/api/desktop/win32/{x64,arm64}/msix/latest/redirect`。清单里把 `%LOCALAPPDATA%\Claude-Data`、`Claude\logs`、`Claude-3p` 和各浏览器的 NativeMessagingHosts 注册表键排除出虚拟化。MSIX 版多带 `cowork-svc.exe` + `smol-bin.x64.vhdx`（Cowork 的 VM 磁盘）。
- **自动更新**：Electron `autoUpdater` + `setFeedURL`（Squirrel 协议）；状态机 checking → available → downloading → downloaded → `quitAndInstall`。文案：`Check for Updates…` / `Checking for Updates…` / `Downloading Update…` / `Update Available` / `Restart to Update to {version}` / `Install Update` / `Skip This Update` / `No Update Available` / `Last Update Attempt Failed`；更新服务器不通时提示"a firewall or proxy may be blocking {hosts} — ask your IT team"。托管策略可关（`autoUpdate.disabled`）。
- **开机自启**：`app.setLoginItemSettings({openAtLogin, enabled})`；卸载钩子 `--squirrel-uninstall` 时顺手注销协议和自启。
- **托盘**：有。左键显示主窗口；右键菜单 = 用量行（`Usage: {pct}`、`5-hour limit`、`Weekly · all models`、`{pct} · resets {when}`、`{tier} plan`）+ `Show App` + 退出。**关窗口 = 缩到托盘不退出**，第一次弹气泡："Claude runs in the system tray / Claude runs in the background even when you close the window. Click the Claude icon in the system tray to reopen the app, or right-click to quit." 托盘图标分深浅两套 ico。
- **单实例**：`requestSingleInstanceLock`。
- **深链**：`claude://`（另有 `claude-dev://`、`claude-nest://` 给内测通道），`setAsDefaultProtocolClient`，可被托管策略 `authentication.disableDeepLinks` 关掉。用途：
  - 登录回调：OAuth（`redirectUri: https://claude.ai/desktop/callback`，scope `user:inference`，clientId 固定）— 走系统浏览器，回到 https 页再跳 `claude://`。
  - MCP OAuth 回调：`claude://claude.ai/mcp-auth-callback/sdk`。
  - Windows 跳转列表（任务栏右键）：`New Chat` → `claude://claude.ai/new?surface=chat&source=desktop_action`；`New Code Session` → `claude://code/new?…`；`Continue Last Claude Code Session` → `claude://code/continue?session=last`；`claude://code/needs-input`；`claude://cowork`；`claude://resume`。
- **本地窗口清单**（都是 Vite 单页）：`main_window`、`quick_window`（快速输入）、`about_window`、`find_in_page`、`buddy_window`（硬件 Buddy 设备）、`local_exec_consent`、`custom3p-setup / device-code`（企业第三方推理：Bedrock / Vertex / Entra 设备码登录）。
- **平台判断**：Windows 上还区分是不是装在 `WindowsApps\`（MSIX）或 `Program Files`（机器级）来决定能不能自更新。

### 1.2 窗口与布局

| 项 | 值 | 备注 |
|---|---|---|
| 主窗口默认 | **1200 × 800** | `cnn={width:1200,height:800}`，记住上次位置尺寸（electron-window-state 那类） |
| 主窗口最小 | **600 × 400** | |
| 未登录时 | 600 × 600 | 登录窗口，同一个 BrowserWindow 换尺寸 |
| 标题栏 | `titleBarStyle:"hidden"` + `titleBarOverlay:true` | **frameless，自绘标题栏**，Windows 保留系统三键（WCO）；拖拽区 class `.nc-drag` / `.nc-no-drag` |
| 顶栏高度 | 主窗口 58 px、弹出窗 40 px（推断，`{mainWindow:58,popout:40}` 是顶部 inset） | |
| 侧栏 | **288 px**，窗口内容宽 **< 700 px 自动收起**（`sidebarWidth:288, narrowViewportMaxWidth:700`） | 启动占位骨架也按这个宽画 |
| Code 独立窗口 | 850 × 700 | Claude Code 会话弹出成独立窗 |
| Design 窗口 | 1200 × 800 | |
| 页内查找条 | 320 × 54，右上角内缩 12 px | |
| 快速输入 | 独立透明窗 `quick-entry-window`，全局快捷键默认 **`Ctrl+Alt+Space`**（Windows） | 另有听写热键 |

主区结构（侧栏内容来自 claude.ai，从字符串反推）：顶部标签 **Chats / Cowork(Tasks) / Code(Sessions) / Design** 四个"surface"；`Ctrl+N` 在哪个标签下就新建哪种（New Chat / New Task / New Session）；`Ctrl+W` 同理（Close Chat / Close Task / Close Session）。Code 标签下有**分栏**：View → Split View 子菜单：`New Session Below` / `New Session on the Right` / `Close Pane`(Ctrl+\\) / `Focus Next Split View`(Ctrl+]) / `Focus Previous Split View`(Ctrl+[) / `Close Split View`(Ctrl+Alt+W)。`Ctrl+Tab` / `Ctrl+Shift+Tab` 切标签，`Alt+←/→` 前进后退。没有右侧 diff 面板（对话流里自己渲染）。

### 1.3 视觉规范（CDS = Claude Design System，取自 `MainWindowPage-*.css`，908 个自定义属性）

- **字体**：`AnthropicSans`（variable，roman + italic，woff2 随包）、`AnthropicSerif`（同上）；栈 `var(--font-anthropic-sans), ui-sans-serif, system-ui, sans-serif`；mono `"SF Mono", ui-monospace, Menlo, Consolas, monospace`。字重 regular 400 / medium 500 / **semibold 580** / bold 600。`data-font=system` 可切系统字体。
- **字号阶梯**（compact / comfortable 两档密度，桌面默认 `data-density="comfortable"`）：caption 11/12，footnote 12/13，body **13/14**（行高 19/20），code 12/13，heading 14/15，title 20/22，prose .875rem/1rem（行高 1.25/1.5rem）。用户缩放按 `data-step 1…5` 走 12/13/14/14/15。
- **圆角**：基础 6/8 px（compact/comfortable），sm 5/7，lg 7/10，**composer 12/14**，checkbox 4/5，卡片 = 基础 + 4。
- **旧版 claude.ai 变量（HSL 三元组）**：

| token | 浅色 | 深色 |
|---|---|---|
| `--bg-000` | `0 0% 100%` (#fff) | `60 2.1% 18.4%` (#30302e) |
| `--bg-100` | `48 33.3% 97.1%` (#faf9f5) | `60 2.7% 14.5%` (#262624) |
| `--bg-200` | `53 28.6% 94.5%` (#f5f4ed) | `30 3.3% 11.8%` (#1f1e1d) |
| `--bg-300` | `48 25% 92.2%` (#efede4) | `60 2.6% 7.6%` (#141413) |
| `--bg-400` | `50 20.7% 88.6%` (#e8e6dc) | `0 0% 0%` |
| `--text-000/100` | `60 2.6% 7.6%` (#141413) | `48 33.3% 97.1%` (#faf9f5) |
| `--text-200/300` | `60 2.5% 23.3%` (#3d3d3a) | `50 9% 73.7%` (#c2c0b6) |
| `--text-400` | `51 3.1% 43.7%` (#73726c) | `48 4.8% 59.2%` (#9c9a90) |
| `--accent-brand` | `15 63.1% 59.6%` (**#d97757**，Claude 橙/陶土色） | 同 |
| `--accent-pro-100` | `251 40% 45.1%` | `251 40.2% 54.1%` |
| `--danger-000/100` | `0 58.6% 34.1%` / `0 56.2% 45.4%` | `0 98.4% 75.1%` / `0 67% 59.6%` |
| `--success-000/100` | `125 100% 18%` / `103 72.3% 26.9%` | `97 59.1% 46.1%` / `97 75% 32.9%` |

- **新版 CDS 语义色**（暖灰 ramp：gray-0 #fff · 10 #fcfcfb · 20 #f9f9f7 · 50 #f0efec · 100 #e1e0d9 · 200 #c3c2b7 · 300 #a5a49a · 400 #898781 · 500 #6d6b67 · 600 #52514e · 700 #383835 · 800 #20201f · 830 #1a1a19 · 850 #151515 · 900 #0b0b0b；`neutral-N` 浅色 = gray-N，深色整条反转）：

| token | 浅色 | 深色 |
|---|---|---|
| `--cds-surface-0`（页面底） | gray-20 #f9f9f7 | gray-900 #0b0b0b |
| `--cds-surface-1`（侧栏/次级） | gray-10 #fcfcfb | gray-850 #151515 |
| `--cds-surface-2`（面板） | #fff | gray-830 #1a1a19 |
| `--cds-surface-3`（弹层） | #fff | gray-800 #20201f |
| `--cds-text-primary` | #0b0b0b | #f0efec |
| `--cds-text-secondary` | #52514e | #c3c2b7 |
| `--cds-text-muted` | #898781 | #898781 |
| `--cds-text-accent` / `--cds-fill-accent` | blue-600 #184f95 / blue-450 #2a78d6 | blue-300 #6da7ec |
| `--cds-fill-brand` | "clay"（品牌橙） | 同 |
| `--cds-text-danger` | red-600 #8e2626 | red-300 #ec7e7e |
| `--cds-text-success` | green-600 #006300 | green-400 #0ca30c |
| `--cds-bg-accent/danger/success/warning` | 各色 100 档（#cde2fb / #fad6d6 / #caeac7 / yellow-100） | 各色 800 档 |
| git 状态色（added/removed/modified/merged/conflicting） | #1e9e3c / #cd2054 / #98801f / #8e6bd9 / #c5621b | #32d74b / #ff2c56 / #ffd014 / #b796ff / #fa832e |
| `--cds-fill-ghost-hover` / `-selected` | neutral-900 @5% / @10% | @7.5% / @15% |
| `--cds-bg-user-message` | neutral-900 @5%（用户气泡就是一层 5% 黑） | 同 |
| `--cds-fill-field` | #ffffff80 | neutral @5% |
| `--cds-shadow-popover` | `0 8px 24px #0000001f, 0 2px 6px #00000014` | `0 8px 24px #00000052, 0 2px 6px #0003` |
| 焦点环 | `inset 0 0 0 1px page-bg, 0 0 0 1px fill-accent, …` | 同 |

- **组件词汇**（`data-cds=`）：`AssistantMessage`、`MessageActions`（悬停浮现的一排操作，`[data-reveal]`）、`MessageAttachments{Image,Video,File,Quote}`、`Card` / `CardLink`（可点整卡）、`ChatComposerDock`（输入框停靠区）、`ChatComposerQueuedMessages`（排队消息）、`ChatComposerNotices`、`Skeleton/SkeletonGroup`、`Table/DataTable`、`PreviewCard`、`Banner`、`Aside`、`Accordion`。工具调用卡片 / 代码块的样式在 claude.ai 远程 CSS 里，包里没有。

### 1.4 功能清单（字符串反推，1183 条 `defaultMessage`）

- **菜单栏（Windows 原生 Electron 菜单）**：File / Edit / View / Window / Help / Developer。
  - File：New Chat | New Session | New Task（Ctrl+N，随当前标签）、Open Folder…（Ctrl+Shift+O）、Close Chat/Session/Task（Ctrl+W）、Settings（Ctrl+,，Windows 不带省略号）、Close Window。
  - Edit：Undo / Redo(Ctrl+Y) / Cut / Copy / Paste / Paste and Match Style(Ctrl+Shift+V) / Select All / Find…(Ctrl+F) / Find Next(F3, Ctrl+G) / Find Previous(Shift+F3, Ctrl+Shift+G)。
  - View：Sidebar(Ctrl+B) / Split View ▸ / Actual Size(Ctrl+0) / Zoom In(Ctrl+= 、Ctrl+Plus) / Zoom Out(Ctrl+-) / Full Screen(F11) / Reload(F5) / Command Palette…(Ctrl+K)。
  - Help：Help(F1) / Keyboard Shortcuts(Ctrl+/) / Check for Updates… / Troubleshooting / Clear Cache and Restart / Import Claude Code CLI Sessions…。Developer：Show Dev Tools(Ctrl+Alt+I)、Developer mode 开关。
- **Code 标签 = Claude Code for Desktop**：会话列表、`Sessions Waiting for You`、`Continue Last Claude Code Session`、`Trust {cwd} and start a code session?`、导入本机 CLI 会话（"All importable Claude Code sessions on this computer, whether started in the Code tab or the terminal…"）、bypass 模式确认（`Run coding task in bypass permissions mode?` + 长警告）、`Claude is working in {n} sessions. Quitting now will interrupt that work.`、通知 `Claude needs your input to continue` / `Return to Claude to keep your task moving`。
- **Remote Control（手机 → 这台电脑）**：把文件夹加入 Remote Control 后，手机 App / claude.ai 可以在这台电脑上起 Claude Code 会话（"Start Claude Code sessions here from your phone"、"Session started from another device"、"Sessions run in the folders you trust for Claude Code, whenever Claude is running, even in the background"、文件夹数量上限、登录过期提醒 "Sign in again to keep Remote Control available"）。
- **SSH 远程会话（Code 标签里连远程机跑 Claude Code）**——和 Yxi 同一件事，细节：
  - 用**系统 OpenSSH ≥ 7.6**（PATH 里的 `ssh`，或托管指定 `sshClientPath`），读用户自己的 `~/.ssh/config`（`{host} is the SSH host alias`），支持跳板机。
  - 指纹流程：首次 → 通知 `Confirm it's {host} to connect` / `This is Claude's first connection to this machine. Review its key in Claude to continue`；经跳板 → `Confirm a machine on the way to {host}`；**key 变了直接拒绝**："The host's SSH key has changed, so the connection is being refused. If the host was not rebuilt or re-keyed, do not trust the new key. Once known_hosts has the right key, the sessions you left running there reconnect."
  - 认证交互全部搬进 App：`{host} needs you to sign in` + 密码 / 一次性验证码 / 私钥口令 / 安全密钥 PIN 四种提示（"Enter your password or verification code in Claude to continue."）。
  - 远程侧下载两样东西：`https://downloads.claude.ai/claude-ssh-releases`（SSH 助手）和 `claude-code-releases`（Claude Code 本体），走 SFTP 拷（有 "refused the SFTP subsystem" 错误分支）；推理凭据由桌面端转发给远程（"the app forwards the session's inference credential"）。
  - 断线语义：**远程会话继续跑，本地只是失联**，统一措辞 "The sessions you left running there are kept; … they reconnect"；不可恢复原因单独列（远端磁盘满 / 家目录只读 / 平台不支持 / 本地磁盘满 / 组织策略封锁 / ssh 太旧 / ssh config 报错）。
  - 托管：`sshHostAllowlist`（`*` 放开）、`sshClientPath`。
- **审批**：`Allow Claude to use {toolName}?`，`Allow` / `Always allow`（可托管关闭 "persistent tool approvals"）、`Auto mode`（"Claude decides which actions need approval"）、deny 规则如 `Read(**/.env)`、`Disable bypass permissions mode`。
- **通知**：Electron `Notification`（Windows toast）+ 托盘气泡；场景：需要输入、远程起了会话、SSH 要你确认/登录、断线、更新。
- **其它**：快速输入窗、听写（热键）、Command Palette、键盘快捷键表、多窗口、Cowork（本地 VM 沙箱：Windows 用 `smol-bin.x64.vhdx`，Linux 要 KVM，mac 14+）、Design surface、桌面扩展（DXT）/ MCP / 插件 / 技能、Chrome 扩展桥（`chrome-native-host.exe`）、硬件 Buddy（BLE）。设置页在 claude.ai 远程；托管设置分组可见：appearance / chatSurface / codeSurface / cowork / autoUpdate / extensions / plugins / dictation / mcp.managedServers / sandbox / authentication / inference(3P)。

### 1.5 文本措辞（英文原文，可参考语气）

- 动作短语用动词开头、Title Case、不带句号：`New Session` / `Open in Claude Code` / `Show App` / `Check for Updates…` / `Clear Cache and Restart` / `Restart to Update to {version}` / `Skip This Update`。
- 问句式确认：`Trust {cwd} and start a code session?` / `Allow Claude to use {device}?` / `Turn off Remote Control and stop {n} remote sessions?` / `Remove "{folderName}" and stop {n} remote sessions?`。
- 错误 = 现象 + 原因 + 你能做什么，一句话一件事：`Reconnecting to {host} stopped: the ssh now on this computer is older than OpenSSH 7.6, which Claude needs. The sessions you left running there are kept; update ssh and they reconnect.`
- 状态行：`Updated {minutes}m ago` / `{pct} · resets {when}` / `Usage: {pct}`。

---

## 2. Codex / ChatGPT Desktop（Windows）

### 2.1 打包与分发

- **技术**：Electron 定制版，代号 **"owl"**（`owl-app.ini`：`UserDataDirectoryName=Codex`；framework 改名 `Codex Framework`，Chromium **152.0.7977.83**，`chrome.dll` 322 MB）。主进程 Vite（`main-*.js` 3 MB + `worker.js`），渲染层 `webview/` 291 MB（React + Tailwind v4，60+ 语言包各 2 MB，docx/xlsx wasm）。同一份 webview 同时给 VS Code 扩展和 Electron 用——CSS 里全是 `--vscode-*` 变量，Electron 下由 `.electron-light` / `.electron-dark` 两个 class 赋值。
- **原生模块**：`node-pty`（真终端）、`better-sqlite3`（本地库）、`@parcel/watcher`、`ssh-config`、`git-worker`、TS/Python/Java LSP + 按需下载 rust-analyzer、`rg`（ripgrep）、**codex CLI 0.153.4**（Rust，`codex.exe`）、`codex-windows-sandbox-setup.exe`、`codex-command-runner.exe`、`codex-code-mode-host.exe`、`cua_node`（电脑操控）、`windows-account.node`（COM 服务）。
- **安装**：**只有 MSIX**（Store 签名）。`winget install --id 9PLM9XGG6VKS -s msstore`；企业用直链 `persistent.oaistatic.com/codex-app-prod/ChatGPT-{x64,arm64}.msix` + `ChatGPT-License.xml` 离线许可。装进 `WindowsApps`（系统管理，per-user 注册，不要管理员）。用户数据 `%LOCALAPPDATA%\OpenAI`（清单里排除虚拟化）。清单：`runFullTrust` + `internetClient`，最低 Win10 19041。
- **自动更新**：Windows 走 **Store**：App 轮询 `https://persistent.oaistatic.com/codex-app-prod/windows-store-update.json`（内容仅 `{schemaVersion, buildVersion, storeProductId, packageIdentity}`），比版本号后触发 Store 更新；侧栏横幅 `有新的 Codex 更新可用：{title}` → `正在下载更新，{pct}` → `立即重启`；确认框 `现在更新 {appName}？ {appName} 将退出以安装更新，这会中断此设备上当前活动的本地会话 [取消] [更新]`。四个 flavor：Nightly / InternalAlpha（裸 msix）、PublicBeta（Store 9N8CJ4W95TBZ）、Prod。macOS 用 Sparkle（`sparkle.node`，`SUPublicEDKey`，feed 在 `oaisidekickupdates.blob.core.windows.net/owl`）。
- **开机自启**：主进程里没找到 `setLoginItemSettings`（MSIX 走 Store 的 StartupTask，代码里看不到）。
- **托盘**：有，Windows 图标分深浅（跟 `nativeTheme` 切换）；菜单：打开主窗口、新聊天、最近聊天、反馈、Chronicle 开关。**Windows 上 `window-all-closed` 不退出**（代码：`process.platform!=='win32' && … && app.quit()`），即关完窗口留在托盘。退出确认：`退出 {appName}？ 本机正在进行的本地聊天将被中断 / 已安排的任务不会运行`。
- **单实例**：`requestSingleInstanceLock`，第二个实例直接 `exit(0)`。
- **深链** `codex://`：`codex://threads/new`、`codex://threads/<id>`、`codex://review?pr=…&path=…&line=…&side=right`、`codex://settings/connections`、`codex://space`、`codex://shared-thread`。文件关联 `.csv .docx .pptx .tsv .xls .xlsm .xlsx` 和 `.skill`；资源管理器文件夹右键 `OpenProjectInCodex`（COM）。
- **登录**：OAuth 到 `https://auth.openai.com`（clientId `app_EMoamEEZ73f0CkXaXp7hrann`），**本地回调 `http://localhost:1455/auth/callback`**（备用 1457，10 分钟超时），清单里为 1455/1457 开了入站防火墙规则；沙箱代理端口段 3128–3159、8081–8112。远程机上的 codex CLI 登录靠 **SSH 端口转发**把 1455 拉回本机（见 2.4）。
- **通知**：Electron `Notification` + 自带音效 `codex-notification.wav` + `notification_helper.exe`。

### 2.2 窗口与布局

| 项 | 值 |
|---|---|
| 主窗口（primary） | Windows/Linux：`titleBarStyle:"hidden"` + `titleBarOverlay`（frameless + 系统三键），`autoHideMenuBar:true`（**菜单栏自绘在网页顶栏里**：文件 / 编辑 / 视图 / 帮助，高 36 px = `--height-toolbar-sm`，整条是拖拽区，按钮 `no-drag`）；mac：`hiddenInset` + `vibrancy:"menu"` |
| 主窗口最小 | **480 × 600**（`getPrimaryMinimumSize`）；默认尺寸不在代码里写死，存 `electron-main-window-bounds` 并夹到当前显示器工作区（推断：首启按显示器给） |
| 其它窗口类型 | `quickChat`（透明、可缩放、有阴影的浮窗）、`detached`（标签页拖出成独立窗）、`secondary`（如 Debug 920×840）、`hud`（置顶、不可最小化，宠物覆盖层）、1×1 透明置顶辅助窗、420×176 固定对话框 |
| 侧栏宽 | `clamp(240px, 275px 首选, min(520px, 100vw − 320px))`，可拖 |
| 工具栏 | 46 px（`--height-toolbar`），面板工具栏 40 px，导航行 30–36 px，设置行 4rem，模式切换 32 px |
| 内嵌浏览器视图 | 最小 240×160，最大 4096 |

区域：**左侧栏**（按项目分组的聊天/线程列表，置顶、未读、状态行）→ **主区标签页**（可拆窗、可 `split` 工作区布局）里是对话流 + 底部 composer → **右侧面板**（审阅/diff、文件树、PR 面板、内嵌浏览器侧栏，`Ctrl+Alt+B`）→ **底部面板**（终端 `Ctrl+J`，PowerShell / CMD / Git Bash / WSL 可选）。composer 内：模型选择（`Ctrl+Shift+M`）、项目选择、**权限下拉**、本地 / worktree / 云端 下拉、推理强度、计划模式、听写、附件（`Ctrl+U`）、后台子智能体折叠条。**快捷浮窗（hotkey window）**：Windows 默认 `Ctrl+Enter`（系统全局）、mac `Alt+Space`；首页占位 `在 {project} 中从本地询问 ChatGPT 任何问题`，有 `/new` `/resume` 命令和"聊天设置"菜单（项目 / 分支 / 环境 / 权限 / 启动模式）。三个模式 `Alt+1/2/3`（mac `Ctrl+1/2/3`）。

### 2.3 视觉规范（`app-initial-*.css`，Tailwind v4）

- **字体**：`"OpenAI Sans"`，回退 `-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`；mono `ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas`；字重 300/400/500/600/700。**聊天正文 13 px**（`--codex-chat-font-size`），diff 12 px。
- **字号阶梯**：xs 11 · sm 12 · base **14**（行高 1.5）· lg 16 · heading-sm 18 · md 20 · lg 24 · xl 28。间距单位 `--spacing: .25rem`（4 px）。
- **圆角**：xs 4 · sm 6 · md 8 · lg 10 · xl 12 · 2xl 16 · 3xl 20 · 4xl 24，Codex 模式整体 **× 1.25**（`--codex-corner-radius-scale`）；列表行 pill（9999）。
- **中性灰（无色相）**：gray-0 #fff · 50 #f9f9f9 · 75 #f3f3f3 · 100 #ededed · 150 #dfdfdf · 200 #cdcdcd · 300 #afafaf · 400 #8f8f8f · 500 #5d5d5d · 600 #414141 · 700 #303030 · 750 #282828 · 800 #212121 · 900 #181818 · 950 #131313 · 1000 #0d0d0d。彩色：blue-400 #0285ff / 500 #0169cc；red-500 #e02e2a；green-500 #00a240 / 600 #008635；orange-500 #e25507；yellow-400 #ffc300；purple-500 #8046d9；pink-500 #e04c91。
- **Electron 主题（浅 / 深）**：

| token | 浅色 | 深色 |
|---|---|---|
| 主背景 `--color-background-surface` | #fff | gray-900 #181818 |
| 侧栏底 `--color-background-surface-under` | gray-50 #f9f9f9 | #000 |
| 自绘菜单栏 `--color-background-application-menu` | gray-50 | gray-800 #212121 |
| 弹层 `--color-background-elevated-primary-opaque` | #fff | gray-750 #282828 |
| 正文 `--color-text-foreground` | **#1a1c1f** | gray-150 #dfdfdf |
| 主按钮 | 底 = 正文色 #1a1c1f，字白 | 反转（浅底深字） |
| 强调 `--color-text-accent` / 底 | blue-300 #339cff / blue-50 | blue-100 #99ceff / blue-900 |
| 错误 / 成功 / 警告 | red-500 / green-500 / orange-500 | red-300 / green-300 / orange-300 |
| 焦点边 | blue-300 | 同 |
| tip 徽标 | 底 #e8f3fe 字 #3a83f7 | blue-100 |
| composer 投影 `--elevation-composer` | `0 0 0 1px #0000000a, 0 2px 8px #0000000a, 0 4px 80px 8px …` | 同 |
| 侧栏 `--elevation-sidebar` | 0.5 px 描边 + `0 3px 7.5px #00000008, 0 0 16px …` | |

- 外观设置项（说明它们把主题做成了可配置）：深/浅两套"chrome theme"各自可调 **强调色**（默认 / 蓝 / 绿 / 橙 / 粉 / 紫 / 黄 / 黑 / 白 / 自定义）、背景色、前景（"墨迹"）、对比度、半透明侧栏、UI 字体 / 内容字体 / 代码字体（族 + 样式 + 字号）、代码主题、diff 标记样式（颜色 vs +/−）、减少动效、Dock 图标（ChatGPT / Codex）。
- 消息：用户消息 `--color-text-user-message` 正文色；工具执行输出块 `--color-background-execution-output` = 次级面 + 标签色 tertiary；traceback 黑底白字；终端 ansi 16 色按主题定义。

### 2.4 功能清单（zh-CN 语言包 14 580 条，键名可检索 `codex-strings.json`）

- **线程模型**：项目（文件夹）→ 聊天（线程）；置顶 / 未读 / 归档 / 重命名 / 分叉 / 侧边聊天 / 在新窗口打开；行状态 `等待批准` / `需要用户输入` / `任务遇到系统错误` / PR 状态（打开 / 草稿 / 已合并 / 已关闭 / 合并冲突）；hover 卡片写运行位置 `在云端运行` / `在你的电脑上运行`。
- **本地 / worktree / 云端**：`新建本地工作树` / `新建远程工作树 · {repo}` / `现有工作树 · {worktree}` / 云端任务；`切换云端/本地`、`切换本地/工作树` 命令；已安排任务（automations）。
- **审阅面板**：按来源看 diff（最近一轮 / 此分支 / 提交 / 云端更改），提交（信息留空自动生成）、提交并推送、创建 PR / 草稿 PR / 合并 PR、blame、转到定义、文件树。
- **审批**（composer 权限下拉，标题 `应如何批准 Codex 操作？`）：
  - `请求批准`（默认："编辑外部文件和使用互联网时始终询问"）
  - `帮我批准`（guardian："仅对检测到的风险操作请求批准"，自动审核代理）
  - `完全访问`（"可不受限制地访问互联网和你电脑上的任何文件"，大确认框列三条：文件和文件夹 / 终端命令 / 互联网和已连接的应用）
  - `自定义 (config.toml)` / `由组织管理`
  - 审批卡片：`允许一次` / `允许此对话` / `始终允许` / `拒绝`，带 `原因`；**Enter = 批准，Esc = 拒绝**；减少提示的 nudge：`想减少审批提示吗？ [帮我批准] [保留手动审批]`。
- **远程连接**（设置 → 连接 / 远程计算机）——和 Yxi 最像的一块：
  - 分组 `来自此电脑的 SSH 连接`（空态 `通过 SSH 连接到远程设备`）、`WSL 连接`（发行版）、本机（`使此电脑保持唤醒`、`允许发现并控制此设备`）。
  - 连接详情字段：**别名 / 主机 / 端口 / 身份文件 / 版本**；`为 {connectionName} 添加远程项目`；每个连接可设**颜色**（`连接颜色…`，在所有出现处着色）。
  - 线程头状态徽标：`已连接` / `正在连接` / `已断开连接` / `错误` / `需要登录` / `未安装 Codex CLI` → 按钮 `安装 Codex CLI` / `更新 Codex CLI` / `登录 Codex` / `重新连接` / `立即重启`（"重启将停止正在运行的 Codex CLI 进程以及此远程主机上所有正在进行的任务"）。
  - composer 顶横幅：`正在连接到 {connectionName}…` / `与 {connectionName} 的连接已断开。正在重新连接…` / `无法重新连接到 {connectionName} [重新连接]`。
  - 远程登录：`验证此远程机器上的 Codex CLI 以继续`，实现是 `ssh <hostConfig.terminal_command> -N -L 1455:127.0.0.1:1455 -o ExitOnForwardFailure=yes -o BatchMode=yes -o ControlMaster=no -o ControlPath=none -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4`，让远程 CLI 的 OAuth 回调落到本机浏览器。
  - 手机控制：`通过手机或其他设备控制` / `允许设备控制此连接吗？` / `请在您的设备上批准，以便远程使用此连接`（配对）。
- **终端**：node-pty 真 PTY，可选 shell；`打开终端` / `切换底部面板`。**内嵌浏览器**：新标签 `Ctrl+T`、地址栏 `Ctrl+L`。文件预览、插件、技能、MCP、钩子、语音 / 听写、电脑操控、Chronicle（电脑历史）、宠物覆盖层、Appshots。
- **通知设置**：轮次完成 `始终 / 仅在未聚焦时 / 从不`；`启用权限通知`；`启用问题通知`；`全部标为已读`（Shift+Esc）。
- **设置导航**（zh-CN 原文）：常规 · 外观 · 通知 · 键盘快捷键 · 账户 · 安全 · 使用情况和计费 · 连接 · 远程计算机 · 环境 / 云端环境 · Git · 钩子 · MCP 服务器 · 插件 · 技能 · Worktrees · 代码审查 · 浏览器 · 电脑操控 · 语音 · 个性化 · 存储 · 已归档的聊天 · 调试；分组标题 个人 / 编码 / 集成 / 已归档。

### 2.5 快捷键表（Windows；`CmdOrCtrl` 记作 Ctrl）

| 功能 | 键 |
|---|---|
| 新聊天（项目内） | Ctrl+Shift+O；独立聊天 Ctrl+Alt+O；临时聊天 Ctrl+Shift+N；快速聊天 Ctrl+Alt+N |
| 打开文件夹 | Ctrl+O |
| 搜索 / 切换聊天 | Ctrl+K |
| 转到聊天 1–9 / 最近 1–6 | Ctrl+1…9 / Ctrl+Alt+1…6 |
| 上/下一个聊天、标签 | Ctrl+Shift+[ / ]，Ctrl+Shift+Tab / Ctrl+Tab，Ctrl+PgUp / PgDn |
| 下一个需要处理的聊天 | **Ctrl+Alt+A** |
| 侧栏 / 底部面板 / 侧面板 / 审阅 | Ctrl+Shift+S / Ctrl+J / Ctrl+Alt+B / Ctrl+Shift+G；布局循环 Ctrl+Shift+B |
| 归档 / 置顶 / 标未读 / 侧边聊天 | Ctrl+Shift+A / Ctrl+Alt+P / Ctrl+Shift+U / Ctrl+Alt+S |
| 批准 / 拒绝 | **Enter / Esc**；全部标已读 Shift+Esc |
| 后台发送 | Ctrl+Enter；附件 Ctrl+U；模型 Ctrl+Shift+M；项目 Ctrl+Alt+Shift+O；听写 Ctrl+Shift+D；语音 Ctrl+Shift+V |
| 查找 / 转到行 / 地址栏 | Ctrl+F / Ctrl+L / Ctrl+L |
| 前进后退 | Ctrl+[ / Ctrl+]（+ 鼠标侧键）；浏览器 Alt+← / → |
| 关闭其它标签 / 重开标签 | Ctrl+Alt+W / Ctrl+Shift+T |
| 设置 / 快捷键表 | Ctrl+, / Ctrl+/ |
| 模式 1/2/3 | Alt+1 / 2 / 3 |
| 全局：快捷浮窗 / 宠物 | Ctrl+Enter（os-global）/ Ctrl+Space |

### 2.6 文本措辞（zh-CN 原文，直接可借）

- 菜单：文件 · 编辑 · 视图 · 帮助 / 新聊天 · 新建独立聊天 · 新建临时聊天 · 新建窗口 · 打开文件夹… · 归档聊天 · 重命名聊天 · 上一个聊天 · 下一个聊天 · 转到聊天 N · 切换侧边栏 · 切换底部面板 · 切换审阅面板 · 打开终端 · 在新窗口中打开 · 复制会话 ID · 复制工作目录 · 复制深层链接 · 设置… · 键盘快捷键 · 检查更新… · 系统状态 · 故障排除 · 任务管理器 · 重新加载窗口 · 实际大小 · 切换全屏 · 注销 / 退出登录 · 退出 {appName}。
- 状态：已连接 / 正在连接 / 已断开连接 / 正在重新连接… / 连接失败 / 需要登录 / 需要更新 / 需要重新启动 / 等待批准 / 需要用户输入 / 正在运行 / 已完成 / 未读。
- 确认框都是"标题问句 + 一句后果 + [取消] [动作]"：`停止并归档此聊天？ 归档会停止所有正在进行的工作。你可以稍后在设置中恢复该聊天。`
- 空态一句话：`通过 SSH 连接到远程设备` / `尚未添加设备` / `暂无近期对话`。

---

## 3. 两家共同点（= 桌面产品的"及格线"）

1. frameless + 自绘标题栏，Windows 保留系统三键（`titleBarOverlay`）。
2. 关窗口不退出，留托盘；托盘左键唤回、右键退出；第一次缩托盘弹一次气泡说明。
3. 单实例；深链协议；OAuth 走系统浏览器再回跳。
4. per-user 安装、不要管理员；自动更新 + 侧栏/菜单"检查更新"；更新前确认"会中断本地会话"。
5. 左栏 = 列表（项目/主机 → 会话），行上带状态徽标；`Ctrl+K` 搜索切换；`Ctrl+N` 新建；`Ctrl+,` 设置；`Ctrl+/` 快捷键表；`Ctrl+B`/`Ctrl+Shift+S` 收侧栏；`Ctrl+Tab` 切会话；`Ctrl+1…9` 直达。
6. 审批：卡片 + 一次 / 会话 / 永久 / 拒绝，Enter 批准 Esc 拒绝；桌面通知"需要你输入/批准"。
7. 深浅主题跟系统，token 化（surface 0–3、text primary/secondary/muted、accent、danger/success/warning、ghost hover 5%/10%）。
8. 远程 SSH：用系统 ssh + 用户 ssh config；指纹首次确认、变更即拒；断线时远端会话保留、本地自动重连并把原因说清；连接状态徽标常驻会话头部。

---

## 4. 对 Yxi 桌面版的落地建议

模块对应：HostsPane（主机）/ SessionsPane（cc-* 会话）/ ChatPane（转录 + 审批 + 发话）/ TermPane（tmux 抓屏）/ 打包（jpackage MSI + GitHub Actions）。

### 必须有

1. **打包**：MSI 改 per-user（Compose `nativeDistributions.windows { perUserInstall = true; upgradeUuid 固定; menuGroup }`），装到 `%LOCALAPPDATA%\Yxi`，不弹 UAC —— 两家都 per-user。
2. **窗口**：`undecorated = true` + `WindowDraggableArea` 自绘标题栏（放侧栏折叠钮 + 当前主机/会话名 + 最小化/最大化/关闭），默认 **1200×800**，最小 **720×560**，记住上次位置尺寸（`%APPDATA%\Yxi\window.json`）。
3. **托盘 + 关窗不退出**：Compose `Tray`（浅/深两套图标），`onCloseRequest` 只隐藏窗口，首次隐藏用 `TrayState.sendNotification` 说一句"Yxi 会在托盘里继续运行"；右键菜单：显示窗口 / 新建会话 / 检查更新 / 退出。单实例用 `%LOCALAPPDATA%\Yxi\lock` 文件锁 + 本地端口唤醒。
4. **HostsPane + SessionsPane 合成一根左栏**（Codex 的"项目 → 线程"）：主机是分组头（状态点、别名、连接颜色），下面挂 cc-* 会话行；行尾徽标 **等待批准 / 需要输入 / 运行中 / 未读**，`Ctrl+Alt+A` 跳到下一个需要处理的会话。侧栏 **288 px**，窗口 < 700 px 自动收起，`Ctrl+B` 手动。
5. **ChatPane 审批条对齐 Codex 卡片**：`允许一次 / 本会话允许 / 始终允许 / 拒绝` 四键 + 原因文本，**Enter 允许、Esc 拒绝**；会话头部常驻连接徽标 `已连接 / 正在重连… / 已断开 [重新连接]`，断线 3 秒内自动重连，转录 tail 断了不清屏。
6. **主机指纹流程照 Claude 文案**：首次"确认这是 {host}"弹指纹让用户核对；指纹变了**直接拒绝并说明"如果主机没重装/换 key，不要信任新 key"**，删旧记录是单独一步（FileHostKeys 已有，补文案）。
7. **快捷键表（第一批）**：Ctrl+N 新会话 · Ctrl+K 搜索/切换会话 · Ctrl+B 侧栏 · Ctrl+J 终端面板 · Ctrl+Tab / Ctrl+Shift+Tab 切会话 · Ctrl+1…9 直达 · Ctrl+F 查找 · Ctrl+= / Ctrl+- / Ctrl+0 缩放 · Ctrl+, 设置 · Ctrl+/ 快捷键表 · Enter/Esc 审批 · F5 刷新转录。
8. **桌面通知**：`TrayState.sendNotification` 发"{会话} 需要你输入 / 等待批准 / 与 {host} 断开"，设置三档 `始终 / 仅在未聚焦时 / 从不`（Codex 原样）。
9. **深浅主题 token 化**（跟系统）：建议取 Claude 暖灰 —— 浅：surface0 #f9f9f7 / 侧栏 #fcfcfb / 面板 #fff / 正文 #0b0b0b / 次级 #52514e / 弱 #898781 / 强调 #2a78d6 / 危险 #8e2626 / 成功 #006300；深：#0b0b0b / #151515 / #1a1a19 / #f0efec / #c3c2b7 / #898781 / #6da7ec / #ec7e7e / #0ca30c；悬停 = 正文色 5%，选中 10%；圆角 8、输入框 14；正文 14 px、代码 13 px、行高 20。

### 应该有

1. **自动更新**：托管 `yxi.keuury.com/desktop/latest.json`（照 Codex 极简：`{version, url, sha256, notes}`），启动 + 每 6 小时查一次；侧栏顶横幅"有新版本 {v} → 下载中 {pct} → 立即重启"；确认框写明"会断开当前连接，服务器上的会话不受影响"；MSI 静默升级（`msiexec /i … /qn`，同 upgradeUuid）。
2. **深链 `yxi://`**：`yxi://session/<host>/<cc-name>`（手机端"在桌面打开"、通知点击）、`yxi://login/callback`（Logto 走系统浏览器回跳）；注册表 `HKCU\Software\Classes\yxi`（jpackage `--win-url-protocol` 不支持，装后自己写）。Windows 跳转列表：新建会话 / 继续上次会话。
3. **分栏**：两个会话并排（Claude Split View）：`新会话在右侧` / `新会话在下方` / `关闭窗格 Ctrl+\` / `Ctrl+] Ctrl+[` 切窗格。
4. **右侧面板：文件变更**：从转录里的 Edit/Write 工具调用汇总"本轮改了哪些文件 +N −M"，点开看 diff（Codex 审阅面板的最小版）；不做提交/PR。
5. **设置页分类**（Codex 词）：常规（开机自启、关窗行为、语言）/ 外观（主题、字号）/ 通知 / 连接（主机，含颜色）/ 键盘快捷键 / 账户。开机自启写 `HKCU\…\Run`。
6. **命令面板 Ctrl+K**：会话 + 主机 + 动作（新建、连接、断开、打开终端）一个框搜完。
7. **快速输入浮窗**：全局 `Ctrl+Alt+Space` 弹 320×54 单行框，给当前会话发一句就收（Claude 快速输入 / Codex hotkey window）。
8. **TermPane 升级**：`tmux capture-pane` 轮询改成 SSH `shell` 通道 + 本地 VT 解析（Codex 用 node-pty，Claude 直接用 xterm 类），至少把键盘全映射（方向键、Ctrl+C、Tab 补全）。
9. **连接错误分类文案**（照 Claude）：认证失败 / 指纹变化 / ssh 版本或 config 错 / 远端磁盘满 / 策略封锁，各一句"发生了什么 + 服务器上的会话还在 + 你现在能做什么"。

### 以后

1. 手机 → 桌面"远程控制"配对（Claude Remote Control / Codex 通过手机控制）：手机端一键在桌面机起会话。
2. worktree / 多分支并行会话（Codex），Yxi 对应"同一项目开多个 cc-*"。
3. 内嵌浏览器面板、语音听写、插件 / 技能市场、多窗口拖出标签页、分享链接、每主机"保持唤醒"。
4. 用量/额度托盘菜单行（Claude：`用量 42% · 59 分钟后重置`）——等 core 的 Usage/Quota 接进桌面再做。
