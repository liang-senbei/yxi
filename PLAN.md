# 计划 · Yxi 实施路线（Android APK · SSH 传输）

> 配套：[PRD.md](./PRD.md)
> 客户端 = **Android 原生 APK**（PRD §2.1）· 传输 = **SSH**（PRD §2.3）· 功能取舍 = **PRD §5.5 的 20 条对照表**
> 原则：**每个阶段都独立可用**。Phase 1 结束你就有个能替代现有 SSH App 的东西了。

---

## 0. 决策状态（全部落定，无阻塞项）

| # | 问题 | 结论 |
|---|---|---|
| 1 | 客户端形态 | ✅ **Android 原生 APK**。**第一期不做 iOS**——苹果不允许从 GitHub Release 安装（PRD §2.5） |
| 1b | **分发** | ✅ **GitHub Releases**，不上应用商店。⚠️ 签名 keystore **一次定终身**，离线备份、绝不入 git |
| 1c | **推送** | ✅ **前台服务默认 + ntfy 可选，明确不用 FCM**（FCM 会强制我们长期跑中转服务器，且国内无 GMS 即废。PRD §2.7） |
| 1d | **面向全球用户** | ✅ 架构原样支持——**每个用户连自己的服务器，我们不跑任何后端**（PRD §2.6）。需补：界面多语言（中/英） |
| 2 | 传输 | ✅ **SSH**。不要 CA 证书、不要 Tailscale、**不开任何新端口** |
| 3 | **SSH 客户端** | ✅ **不砍**。`mwiede/jsch`（纯 Java，不用 NDK）。多主机从 P1 提到 **P0** |
| 4 | Mosh | ✅ **不做**。韧性由 tmux 提供（PRD §5.5 第 19 条） |
| 5 | 原版 Moshi 并行 | ✅ 安卓侧不用了。⚠️ iOS 侧仍在用——若 iOS 的 Moshi 往**同一台机**装了 `moshi-hook`，两套 hook 会抢 `PermissionRequest`。**当前本机没装（已查）** |

---

## 1. 阶段划分

> ⚠️ **顺序已按 D10/D11/D12 重排**：Chat View 是主界面，所以它提前；
> 终端打磨（键盘条 / CJK / 手势）后移——**它不该挡在你看到成品之前**。
> 但 Phase 1 的 SSH 层不能跳，它是一切的传输层。

### Phase 0 · Android 工具链 + 空壳 APK（~半天，**大半已完成**）

| # | 做什么 | 状态 |
|---|---|---|
| 0.1 | `openjdk-17-jdk-headless` | ✅ |
| 0.2 | Android cmdline-tools + `platform-tools` / `platforms;android-34` / `build-tools;34.0.0` / `emulator` / `system-images;android-34;google_apis_playstore;x86_64` | ✅ |
| 0.6 | **AVD 模拟器**（720x1280 + swiftshader + KVM，40 秒开机） | ✅ `dev/avd.sh` |
| 0.3 | Gradle wrapper 最小工程，**`minSdk 26` + `compileSdk 36`** | ⬜ |
| 0.4 | `./gradlew assembleDebug` | ⬜ |
| 0.5 | `adb install` 进模拟器 → 再侧载到真机（荣耀 Magic7） | ⬜ |

**验收**：模拟器和真机上都装上了、点开有界面。

---

### Phase 1 · SSH 层 + 多主机 + 能出终端（~2 天）

**做完这阶段它已经是个能用的 SSH 客户端了。**

| # | 做什么 | 关键点 |
|---|---|---|
| 1.0 | **先读两个参照**：`GlassHaven/Haven`（Kotlin 现代 SSH 客户端，活跃）· `connectbot/connectbot`（Apache-2.0，可直接抄） | 别从零想（PRD 附录 B.1） |
| 1.1 | gradle 加 `com.github.mwiede:jsch`（纯 Java，无 NDK） | 备选 `sshj` / `connectbot/sshlib` |
| 1.2 | App 内生成 ed25519，私钥存 **Android Keystore** | 硬件级，导不出来 |
| 1.3 | **加/编辑主机的完整流程**：任意 IP / **任意端口** / 用户名 / **密码或密钥** | ⚠️ **不是预置列表**，要能连"以后才有的"服务器（PRD §2.4） |
| 1.4 | **一键装公钥**：密码连一次 → append 到 `authorized_keys` → 之后免密 | 相当于 `ssh-copy-id` |
| 1.5 | **`known_hosts` 校验**：首次显式确认指纹，之后变了就拒 | ⚠️ **不能 accept-any** |
| 1.6 | 终端控件先试 **`termux/terminal-view`**（原生，独立 gradle 模块） | 退路 WebView + xterm.js（PRD §2.2） |
| 1.7 | 连上跑 `tmux list-sessions -F '#{session_name}\|#{session_windows}\|#{session_activity}\|#{session_attached}'` 选会话 | 抄 Moshi 的格式串（PRD §1.2） |
| 1.8 | attach 时照抄 Moshi 那行 tmux 设置 + **注入 `YXI_CLIENT=1`** | PRD 附录 C.2 |

**验收**：① 连上本机 attach `cc-root` 跑 `ls` 有彩色输出 ② 连上 `station`/`inst2`（什么都没装）当普通 SSH 用
③ 故意改 host key → **拒绝连接**

---

### Phase 2 · 会话看板 + 发指令（~1 天）

> ⚠️ **服务器侧已按 PRD 附录 H 简化**：这一阶段**不需要在服务器上装任何东西**——全是 shell 命令。

| 层 | 做什么 |
|---|---|
| App | `list` → `tmux list-sessions -F '…'` + 读 `~/.cloud-status/*.json`（`cc-state` 已在写） |
| App | `peek` → `tmux capture-pane -p -t <会话>` · `send` → `tmux send-keys` |
| App | **探测每个会话里跑的是什么**（Claude Code？普通 shell？）→ 决定要不要给对话模式 |
| App | 会话看板三列（**等你 / 干活中 / 已完成**） |
| 服务端（可选） | `yxi` 脚本（~60 行）：把上面几条打包成一次 SSH 调用、**带版本号的 marker 分段**返回（抄 Moshi，PRD §1.2 / 附录 C.1）。<br>**只为省往返，不是能不能用的前提** |

**验收**：手机看到全部 14 个 `cc-*` 会话和状态；给 `cc-Yxi` 发一句话，服务器上能看到收到。

---

### Phase 3 · ⭐ Chat View 渲染（~2 天）—— **这是主界面**

| 层 | 做什么 |
|---|---|
| App | **`tail -f` 转录 jsonl** 直接经 SSH exec channel 流回来，App 侧解析。<br>⚠️ **不需要服务器上有任何东西**——`tail` 是系统自带的（PRD 附录 H.4） |
| App | 渲染 `text` / `thinking`（默认折叠）/ `tool_use`+`tool_result` 工具卡片 |
| App | **按工具定制卡片**：`Bash`（占 3177 次，先做它）· `Edit`/`Write` 渲染成 **diff** · `Read` · `Agent` |
| App | ⭐ **`AskUserQuestion` → 可点选项按钮**（`questions[].options[]` 直接变按钮） |
| App | ⭐ **`ExitPlanMode` → 计划卡片**（markdown）+「批准 / 继续讨论」 |
| App | markdown 渲染 + 代码块语法高亮 + 一键复制 |
| App | 输入框 → `tmux send-keys` 打回同一个活着的会话 |
| App+服务端 | ⭐ **附件/图片上传**（PRD 附录 F）：SFTP channel 传到 `/root/src/tmp/<项目>/`，<br>chip 显示「图片1/附件1」，发送时头部带路径映射 |
| 服务端 | **3 天清理** systemd timer。⚠️ 路径写死、`-xdev`、不跟符号链接、删前记日志 |

**验收**：① 打开 `cc-Yxi` 的对话模式，**能像原生 Claude App 那样读完整段对话**；打字回它，服务器上收到
② 从手机相册选 2 张图 + 1 个文件发出去，`/root/src/tmp/Yxi/` 里出现清洗过名字的文件，**Claude 能读到**
**自检**：`test_yxi.py::test_tmp_cleanup` —— 造 3 个文件（1 天前/4 天前/带空格中文名），
断言只有 4 天前的被删、名字清洗正确、符号链接不被跟随。
**⚠️ 要实测的**：点 `AskUserQuestion` 的选项时，Claude Code 的 TUI 选择器**接受什么按键**（数字键？↑↓+Enter？）。
备选路径：查 `Elicitation` / `ElicitationResult` hook 能否程序化应答。
> ✅ **风险已有兜底**：不通的话选项卡片做成只读，**你用 D-Pad 上下选 + 中央 Enter 确认**（PRD 附录 I.3）。
> TUI 菜单本来就是 ↑↓+Enter，所以**无论如何都能用**，风险从「可能做不了」降成「可能不够优雅」。

---

### Phase 4 · ⭐ 三模式切换 + 文件浏览 + 语音输入（~1.5 天）

**文件模式**（PRD 附录 G）—— 走 **SFTP**（`jsch` 自带），**不需要 `yxi-agent`**，任何 SSH 主机可用：
- 起点 = 会话的 cwd，可上下导航
- `.md` **渲染 ⇄ 源码** 一键切换（用 **Markwon**，与对话模式共用一套渲染器）
- 图片显示可缩放 · `.json`/`.jsonl` 折叠树 · 代码高亮 + 横向滚动 · `.log` 不换行
- ⚠️ **md 里相对路径的图片要 SFTP 取回来显示**——不做的话带图文档就是一堆破图标
- **只读**。改文件用终端模式

**三模式切换**（PRD 附录 D.5）：顶部分段控件 `[终端│对话│文件]`
1. **切换不断连** —— 同一个会话的两种渲染，光标和滚动位置都不能丢
2. **记住每个会话上次用的模式**
3. **没装 `yxi-agent` 的主机：对话模式置灰 + 说清原因 + 给安装引导**
4. **不是 Claude Code 的会话：不显示对话模式**
5. 默认：探测到 Claude Code → 对话模式；其余 → 终端模式

**语音输入**（PRD 附录 E）：
- 主力 `SpeechRecognizer`，**运行时探测可用性**，不可用退到"请按输入法上的麦克风"
- ⚠️ **命令行模式下永远先显示识别结果、确认才发送**——识别错 + 自动回车 = 在服务器上跑了没说过的命令
- 对话模式默认也先确认；自动发送做成可选开关

**验收**：① 三个模式来回切，会话不断、位置不丢
② 在**没装 agent 的 `station`** 上也能翻文件、读 md（证明 SFTP 这条路独立）
③ 打开一个带图的 `README.md`，**图片能显示出来**（相对路径解析对了）
④ 语音说一句中文能正确填进输入框

---

### Phase 5 · 终端打磨（~1 天）

| 对照表# | 做什么 |
|---|---|
| #2 | **键盘工具条**：Esc / Tab / Ctrl / ↑↓←→ / Ctrl-C |
| ⭐ | **D-Pad**（PRD 附录 I）：圆形四向 + 中央 Enter，工具条按钮唤出浮层；<br>两个上角可配置（Backspace / Ctrl+C / 自定义 / 隐藏）；**长按连发**（500ms 后每 80ms）；<br>**按住拖到方向 = 持续导航**；位置可拖、记左右手偏好；**对话模式也能唤出** |
| #6 | **快捷面板简版**：tmux 前缀 `Ctrl-B` + 窗口 1~9（不做自定义编辑器） |
| #3 | **CJK 输入**：内嵌等宽 CJK 字体 |
| #7 | 滚动 · 断线重连（指数退避 → 重新 attach，**tmux 保住内容**）· 横竖屏尺寸同步 |

**验收**：① **打中文进终端** ② WiFi 切 4G 两秒内恢复且**光标位置没丢** ③ 工具条按出 `Ctrl-C` 能中断命令
④ **用 D-Pad 在 Claude Code 的权限提示里上下选 + Enter 确认**（证明兜底路径成立）
> ⚠️ 最可能翻车的是**输入法**。虽然排在后面，**Phase 1 出终端时就顺手试一次中文**，别等到这里才发现。

---

### Phase 6 · 事件 + 通知（~1 天）

> 推送方式已定（PRD §2.7）：**前台服务默认 + ntfy 可选，不用 FCM**。

> ⚠️ **已按 PRD 附录 H 简化：没有守护进程。** `yxi-hook` 是唯一要装的东西。

| 层 | 做什么 |
|---|---|
| hook | **`yxi-hook`**（~80 行）挂 `PermissionRequest` / `Notification` / `Stop` / `SessionStart` / `SessionEnd`，<br>**追加写 `~/.yxi/events.jsonl`**（顺手把 stdin 里的 `transcript_path` 写进去 → App 零映射逻辑） |
| hook | 自己限流：`tool_running`/`tool_finished` 折叠；文件超 5 MB 自截断保留尾部 1000 行 |
| App | `EventService` 前台服务：常驻一条 exec channel 跑 `tail -f ~/.yxi/events.jsonl`，收事件 → 本地通知 |

**验收**：① 荣耀 Magic7 **锁屏**收到「cc-mail 干完了」，点开直达 ② **手机没连着时产生的事件，连上后能补收到**
> ⚠️ **就在荣耀 Magic7 上验**（MagicOS 后台管控严 = 最恶劣环境）。要做：电池白名单引导 + `START_STICKY` + 开机广播。

---

### Phase 7 · 远程审批（~1 天）—— **整个项目的核心**

```
Claude 要跑危险命令 → PermissionRequest hook 触发
  → yxi-hook 往 ~/.yxi/events.jsonl 追加一行，然后【轮询 ~/.yxi/answers/<id>，最多 570 秒】
  → App 的 tail -f 看到 → 前台服务发通知（工具名 + 完整参数 + 三个按钮）
  → 你在【通知上】点 批准 → App 经 SSH 跑  printf 'allow' > ~/.yxi/answers/<id>
  → hook 读到 → 打印 permissionDecision → Claude 继续
```

**零守护进程**（PRD 附录 H）。失败模式只剩「文件没出现」一种 → 超时即 `"ask"`。

**必须做对的三件事**：
1. **fail-closed**：超时 570s / inbox 挂 / socket 连不上 / 解析失败 → **一律输出 `"ask"`**，退回终端。**任何分支都不能默认 allow。**
2. **留余量**：570 < 600（hook 硬超时）
3. **「转终端」按钮**：一键立刻返回 `ask`，别让 Claude 干等 9 分钟

**验收（两条都要过）**：① 手机**通知上直接**批一次，Claude 继续跑
② **把 `~/.yxi/` 改成不可写后再触发 → Claude 退回终端问你**（不是自动放行）

**自检**：`test_yxi.py::test_approval_fail_closed` —— socket 指到不存在的路径，断言输出 `permissionDecision == "ask"`。
> **这条测试比其它所有加起来都重要。**

---

### Phase 8 · P1（按需）

**界面多语言（中/英）** · **ntfy 可选推送** · #8 远程剪贴板(OSC 52) · #10 最近目录(简版) · #12 粘贴图像(不做标注) · #11 跳转到…(待定)
· **附录 E.3 服务器端 whisper 转写**（准确度明显好于系统 API，尤其中英混杂和技术词）

### Phase 9 · 发布准备

| # | 做什么 | 注意 |
|---|---|---|
| 9.1 | 生成 **release keystore**，离线备份两份 | ⚠️ **一次定终身** |
| 9.2 | `assembleRelease` + 签名 + GitHub Release 挂 APK | 附 SHA256 |
| 9.3 | 面向用户的 README（安装 / 加主机 / 装 `yxi-agent` 三步），中英双语 | |
| 9.4 | **拆仓**：公开（代码+用户文档）/ 私有（PRD、PLAN、handover、逆向笔记） | ⚠️ 见 §5 |

### Phase 10 · P2（不排期）

`#13 用量`（`cc-quota` 已有）· `#14 Diff 查看器` · `#15 硬件键盘`（基本白送）· `#16 手势简版`
· **`#18 Live Updates`（安卓版「灵动岛」）** —— `Notification.ProgressStyle`（API 36）把前台服务那条通知提升成
**状态栏胶囊**，显示**审批 570 秒倒计时**。⚠️ 完整体验先在 Pixel 放出 → **渐进增强，不当核心功能**（PRD §2.7）

**明确不做**：`#17 浏览器预览` · `#19 Mosh` · `#20 主题字体图标` · **iOS**（PRD §2.5）
**先不做但不是做不了**：`#3 厂商灵动胶囊`（荣耀等确实开放接入，走开发者平台合作，PRD §2.7）

---

## 2. 文件清单

```
/root/src/workspace/Yxi/
├── PRD.md / PLAN.md / handover.md      ✅ 已完成
├── TROUBLESHOOTING.md                  Phase 0 起边做边记
├── server/                ~140 行，无守护进程（PRD 附录 H）
│   ├── yxi-hook           ~80 行 · 挂 settings.json · **唯一必须装的**
│   ├── yxi                ~60 行 · **可选** · 打包探测省往返
│   └── install.sh         注册 hook + 建 ~/.yxi/
├── web/                   塞进 WebView 的前端
│   ├── term.html          xterm.js + 键盘工具条 · ~350 行
│   └── xterm.js / xterm.css / CJK 字体   vendor（本机没 npm，curl 拉）
├── android/               Kotlin 工程 · ~900 行
│   ├── HostListActivity.kt     多主机列表
│   ├── SessionsActivity.kt     会话看板
│   ├── TerminalActivity.kt     WebView 壳
│   ├── SshManager.kt           jsch 连接池 + Keystore + known_hosts
│   ├── EventService.kt         前台服务 + 常驻 exec channel + 通知
│   └── (gradle 工程文件)
└── test_yxi.py            自检 · ~80 行（4 个断言，见各 Phase）
```

服务器侧 **~140 行**（原 ~340，见 PRD 附录 H），App 侧 ~1250 行。**总量约 1400 行。**

> 注意：**没有 `certs/` 了**。改走 SSH 后不需要自建 CA（PRD §2.3）。

---

## 3. 排期

| 阶段 | 工作量 | 累计后你能干什么 |
|---|---|---|
| Phase 0 | 半天（**大半已完成**） | 手机上装上了空壳 |
| Phase 1 | 2 天 | **能连所有服务器、开终端、选 tmux 会话** ← 已可替代现有 SSH App |
| Phase 2 | 1 天 | 会话看板 + 给任意会话发指令 |
| **Phase 3** | **2 天** | ⭐ **像原生 Claude App 一样读对话、回消息** ← 你要的主界面 |
| Phase 4 | 1.5 天 | ⭐ 三模式切换（+**文件浏览**）+ 语音输入 |
| Phase 5 | 1 天 | 终端真正好用（中文、方向键、重连） |
| Phase 6 | 1 天 | 手机会主动响 |
| Phase 7 | 1 天 | **通知上直接批权限** ← P0 完成 |

**P0 合计约 10 天**（最初 6.5 天）。多出来的 3 天全在 **Chat View + 双模式 + 语音**——
这三样是 D10/D11/D12 三条新要求，**也正是"像原生 App"和"裸终端"的分界线**。

**三个自然停止点**：Phase 1（当好用的 SSH App 用着）· **Phase 4**（主界面成型，最想看到的东西都有了）· Phase 7（P0 完成）

## 4. 已确认的前置条件

**服务器侧（全绿）**
- ✅ `python3` 3.12.3 + stdlib → **服务器侧零新依赖**（走 SSH 后连 `websockets`/`ssl` 都不需要了）
- ✅ `tmux` 在跑，14 个 `cc-*` 会话；`cc-state` 已挂 6 个 hook，`~/.cloud-status/` 有数据
- ✅ Claude Code hook 超时 600s（官方文档确认）→ 远程审批可行
- ✅ transcript JSONL 格式已实际读取确认
- ✅ **Moshi APK 逆向完成**（`/root/inbox/base.apk`）→ 会话枚举命令、网关接口、Inbox 表结构都是实证
- ✅ **SSH 免密已配好**：`station` / `inst2` / `inst3` / `inst4`（见 `/root/src/CLAUDE.md`）
- ✅ **不需要改 ufw、不需要动 sshd、不开任何新端口**

**Android 侧（要装）**
- ❌ **没有 java、没有 Android SDK、没有 gradle** → Phase 0.1–0.2 装，约 3 GB
- ✅ 磁盘 451 G 空闲、16 核、15 G 内存 → 无 GUI 可 `gradlew assembleDebug`
- ✅ **`/dev/kvm` 在 + CPU `svm` + 嵌套虚拟化已开** → **AVD 模拟器可硬件加速**（开发回路见 Phase 0.6）
- ❌ **MuMuPlayer 无 Linux 版** → 用 SDK 自带 AVD 替代，对开发更合适
- ✅ **侧载通路现成**：微信 / `lapput`（原版 Moshi 的 APK 就是这么到你手机的）

**已作废的前置**
- ~~Tailscale HTTPS Certificates~~ · ~~ACL funnel~~ · ~~自建 CA / mTLS~~ · ~~ufw 开 8443~~ → 改 SSH 后全不需要

---

## 5. 下一步

**先做 Phase 0**（半天）。全程最大的未知——**先证明这台机能编出你手机装得上的 APK**，
通不了就地掉头，不浪费后面七个阶段。

然后 Phase 1（2 天）你就有个能连所有服务器的 SSH 客户端了，这本身就有用。
Moshi 独有的那部分（看板 / 通知 / 审批）从 Phase 3 才开始。

---

## 5. ⚠️ 发布前必须处理：仓库要拆

GitHub Releases 要让人下载，**仓库必须是公开的**（私有仓的 Release 也是私有的）。

但本仓现在含**你的基础设施信息**：服务器别名、`/root/src/CLAUDE.md` 的路径、
以及 PRD §8 里那条 **「本机 sshd 同时开着 root 登录和密码认证」**——这条尤其不能公开。

**发布时拆两个仓：**

| 仓 | 内容 |
|---|---|
| **公开** | `android/` · `server/` · `web/` · 面向用户的 README · LICENSE |
| **私有**（就是现在这个） | PRD · PLAN · handover · TROUBLESHOOTING · 逆向笔记 |

**现在先不动**，但**从写第一行代码起就注意**：别把你的主机名 / IP / 路径 / 凭据写进
`android/` `server/` `web/` 这三个目录里的任何文件——它们将来是要公开的。
