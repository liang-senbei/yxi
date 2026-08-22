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

### Phase 0 · Android 工具链 + 一个能装上的空壳（~半天）

**全程最大的未知，所以放第一。先证明"这台机能编出你手机装得上的 APK"，再谈功能。**

| # | 做什么 |
|---|---|
| 0.1 | `apt install -y openjdk-17-jdk-headless`（本机**没有 java**，已查） |
| 0.2 | 下 Android **cmdline-tools**，`sdkmanager` 装 `platform-tools` + `platforms;android-34` + `build-tools;34.0.0`（约 3 GB，磁盘 451 G 空闲） |
| 0.3 | Gradle wrapper 建最小工程：一个 Activity 显示 "Yxi"。**`minSdk 26` + `compileSdk 36`**（compileSdk 36 是 Live Updates 的编译前提，minSdk 低保覆盖面） |
| 0.4 | `./gradlew assembleDebug` |
| 0.5 | `lapput` 或微信传手机 → 侧载安装 |
| 0.6 | **建 Android 模拟器（AVD）** —— 见下方「开发回路」 |

**验收**：手机上装上了、点开有界面。
> 到这一步**一行业务代码都没写**——纯粹验证路通不通。通不了就地掉头，不浪费后面六个阶段。

#### 0.6 开发回路：本机跑 Android 模拟器（**这台机跑得动**）

**已核实**：`/dev/kvm` 存在、CPU 有 `svm`、`systemd-detect-virt` = `kvm` → **嵌套虚拟化已开，模拟器能硬件加速**。
另有图形环境（lightdm + VNC `:5901`）。

> ❌ **MuMuPlayer 用不了**——它只有 Windows / macOS 版，Linux 上没有。
> ✅ **但不需要它**：Android SDK 自带 **AVD 模拟器**，Phase 0.2 装 SDK 时顺手 `sdkmanager 'system-images;android-34;google_apis;x86_64'` 即可，对开发比 MuMu 更合适（有 `adb`、有 logcat）。

**为什么值得花这半小时**：开发回路从
`编译 → lapput/微信传手机 → 手动点安装 → 试` 变成 `编译 → adb install → 试`，
**每一轮省几分钟**，几十轮下来省一整天。真机只在阶段验收时用（有些坑只有真机有：国产 ROM 杀后台、真实输入法、4G 切换）。

#### 想在模拟器里跑**原版 Moshi** 做参照？要先补齐 split

⚠️ 你给的 `base.apk` 是 **split APK 的 base 部分**，`unzip -l` 数出 **0 个 `.so`**。
而 Moshi 恰恰**重度依赖原生**（libghostty 终端引擎 / Nitro 传输层 / Parakeet 语音模型，见 PRD §2.2）
→ **单独装 base.apk 一启动就崩。**

要跑起来得拿到完整 split 集（`base` + `split_config.arm64_v8a` + `split_config.<dpi>` + `split_config.<语言>`）：

| 办法 | 怎么做 | 评价 |
|---|---|---|
| **A（最省事）** | 模拟器用**带 Play 商店的镜像**（`system-images;android-34;**google_apis_playstore**;x86_64`），登 Google 账号，**在 Play 里直接装 Moshi** | ✅ **推荐**。Play 自动处理 split 和 ABI，不用手工拼。<br>官网确认安卓版就是走 Play：`play.google.com/store/apps/details?id=app.getmoshi.android`，**没有直接 APK 下载** |
| B | 手机上装 APK 提取工具导出完整 `.apks`，微信发过来 → `adb install-multiple` | 手工，但不依赖 Google 账号 |
| C | 手机开无线调试 → `adb shell pm path app.getmoshi.android` → `adb pull` 每个 split（不用 root） | 同上 |

> ⚠️ 走 A 的两个前提：模拟器要能**连得上 Google**（本机有 sing-box 代理可用）；
> Moshi 若只发 arm64，x86_64 模拟器可能显示"不兼容"——那就退 B/C 加 ARM 转译镜像。
> **这条不是必需路径**——参照价值主要是抓它的网关请求体（唯一没挖到的东西）。
> **优先级低于把我们自己的东西跑起来。**

---

### Phase 1 · SSH 层 + 多主机 + 能开终端（~2 天）

**做完这一阶段，它已经是个能用的 SSH 客户端了——可以替代你手机上现在那个。**

| # | 做什么 | 关键点 |
|---|---|---|
| 1.1 | gradle 加 `com.github.mwiede:jsch`（纯 Java，无 NDK） | 备选 `sshj`（拖 BouncyCastle，Android 上有冲突风险）或 `connectbot/sshlib`（Apache-2.0） |
| 1.0 | **先读两个参照**：`GlassHaven/Haven`（Kotlin 现代 SSH 客户端，AGPL，今天还在更新）和 `connectbot/connectbot`（Apache-2.0，可直接抄） | 见 PRD 附录 B.1。**别从零想**，这道题有人做过 |
| 1.2 | App 内生成 ed25519 密钥，私钥存 **Android Keystore**（硬件级，导不出来） | 比 Moshi 的二维码配对流程简单 |
| 1.3 | 显示公钥（文本 + 二维码）→ 你贴进各机 `~/.ssh/authorized_keys` | 一次性 |
| 1.4 | `HostListActivity`：**加/编辑主机的完整流程**——任意 IP / **任意端口** / 用户名 / **密码或密钥** | ⚠️ **不是预置列表**。要能连"以后才有的"服务器（PRD §2.4）。具体机器清单见 `/root/src/CLAUDE.md`（不入库） |
| 1.4b | **一键装公钥**：密码连上一次 → append 到 `~/.ssh/authorized_keys` → 之后免密 | 相当于 `ssh-copy-id`，比 Moshi 的二维码配对还省事 |
| 1.5 | **`known_hosts` 校验**：首次连接指纹显式确认，之后变了就拒 | ⚠️ **不能图省事 accept-any**，那等于关掉 SSH 的中间人防护 |
| 1.6 | `TerminalActivity` 接 SSH **shell channel**。终端控件**先试 `termux/terminal-view`**（原生，GPL-3.0，已核实是独立 gradle 模块） | 退路：WebView + xterm.js。见 PRD §2.2 / 附录 B.2 |
| 1.7 | 连上后跑 `tmux list-sessions -F '#{session_name}\|#{session_windows}\|#{session_activity}\|#{session_attached}'` 让你选会话 | **抄 Moshi 的格式串**（PRD §1.2） |
| 1.8 | attach 时照抄 Moshi 那行 tmux 设置 | `tmux set -g set-titles on \; set -g mouse on \; set -g status-right '' \; unbind -q -T root WheelUpStatus \; unbind -q -T root WheelDownStatus` |

**验收（三条）**：
- ✅ 能连上**本机**，选会话，attach 进 `cc-root`，跑 `ls` 看到彩色输出
- ✅ 能连上 **`station` 和 `inst2`**（这两台什么都没装）当普通 SSH 终端用
- ✅ 故意改一台的 host key → **App 拒绝连接**，不是静默接受

**自检**：`test_yxi.py::test_tmux_format` —— 断言那条 `-F` 命令的输出能被解析成会话列表。

---

### Phase 2 · 终端打磨（~1 天）

**PRD §5.5 里的 P0 项，都在这一阶段。终端不好用，后面全是白搭。**

| 对照表# | 做什么 |
|---|---|
| #2 | **键盘工具条**：Esc / Tab / Ctrl / ↑↓←→ / Ctrl-C（手机软键盘没这些键） |
| #6 | **快捷面板简版**：tmux 前缀 `Ctrl-B` + 窗口 1~9。**不做自定义按钮编辑器** |
| #3 | **CJK 输入**：内嵌一份等宽 CJK 字体（Moshi 专门下 NotoMonoCJK，说明这是真坑） |
| #7 | **滚动**：xterm.js 自带，接上就行 |
| — | **断线重连**：指数退避 → 重新 attach。**tmux 保住内容，不用自己存 scrollback** |
| — | 尺寸同步：横竖屏切换 → 发 SSH window-change |

**验收**：**打中文进终端** → WiFi 切 4G，2 秒内自动恢复且**光标位置没丢** → 用工具条按出 `Ctrl-C` 能中断命令。
> ⚠️ 最可能翻车的是 **WebView 里的输入法**：中文候选、光标定位、选区。**早试，别拖到最后。**

---

### Phase 3 · yxi-agent + 会话看板 + 发指令（~1 天）

**从这里开始才是 Moshi 独有的部分——之前两阶段任何 SSH App 都有。**

| 层 | 做什么 |
|---|---|
| 服务端 | `yxi-agent`（~200 行 Python）：**不监听任何端口**，被 SSH exec channel 拉起，stdin/stdout 走 JSON 行协议（协议表见 PRD §6） |
| 服务端 | `list` = 会话+状态（读 `~/.cloud-status/*.json`，`cc-state` 已经在写，白捡）<br>`peek` = `tmux capture-pane -p`<br>`send` = `tmux send-keys` |
| App | `SessionsActivity` 三列看板（**等你 / 干活中 / 已完成**），点会话展开预览 + 输入框 |

> **这里我们比原版强**：Moshi 的输入框只能发给**当前 attach 着的会话**（PRD §1.6，APK 里搜不到 `send-keys`）。
> 我们用 `send-keys`，**不用先 attach 就能给任意会话下指令**。

**验收**：手机上看到全部 14 个 `cc-*` 会话和各自状态；给 `cc-Yxi` 发一句话，服务器终端里能看到它收到了。
**自检**：`test_yxi.py::test_agent_list` —— 断言 `yxi-agent` 的 `list` 输出会话集合 == `tmux ls` 的集合。

---

### Phase 4 · 事件 + 通知（~1 天）

> **推送方式已定**（PRD §2.7）：**前台服务**为默认，**ntfy** 为可选，**不用 FCM**。

| 层 | 做什么 |
|---|---|
| hook | `yxi-hook`（~60 行）挂 `~/.claude/settings.json` 的 `Notification` / `Stop` / `SessionStart` / `SessionEnd`，投 `/run/yxi.sock` |
| 服务端 | **`yxi-inbox`**（~80 行，systemd，**只监听 unix socket**）：收 hook 事件、落盘。<br>**必要性**：手机没连着的时候 Claude 也在干活，得有人接着；agent 连上时把积压一次性吐出去 |
| 服务端 | 限流：按 Moshi 的做法把 `tool_running`/`tool_finished` 折叠，别把手机炸了 |
| App | `EventService` 前台服务：常驻一条 exec channel，收事件 → `NotificationManager` 发**本地通知** |

**没有 FCM、没有 Firebase、没有任何云、没有账号。**

**验收（两条）**：
- ✅ 手机**锁屏状态**收到「cc-mail 干完了」的通知，点开直达该会话
- ✅ **手机没连着的时候产生的事件，连上后能补收到**（`yxi-inbox` 积压吐出）

> ⚠️ **国产 ROM 杀后台**是这阶段最大风险。对策：电池优化白名单 + `START_STICKY` + 开机广播。
> **`yxi-inbox` 保证事件不丢**，最坏情况只是延迟收到。再兜底可加 ntfy 作第二通道。

---

### Phase 5 · 远程审批（~1 天）

**整个项目的核心，也是唯一有真实风险的一段。**

```
Claude 要跑危险命令
  → PermissionRequest hook 触发
  → yxi-hook 投事件到 /run/yxi.sock，然后【阻塞等回复，最多 570 秒】
  → yxi-inbox → yxi-agent → SSH → 前台服务发通知（工具名 + 完整参数 + 三个按钮）
  → 你在【通知上】直接点 批准 / 拒绝 / 转终端（不用打开 App）
  → 原路回到还等着的 hook 进程
  → hook 打印 permissionDecision JSON 退出 → Claude 继续
```

**必须做对的三件事**：
1. **fail-closed**：超时 570s / inbox 挂 / socket 连不上 / 解析失败 → 一律输出 `"ask"`，退回终端手动批。**任何分支都不能默认 allow。**
2. **留余量**：570 < 600（hook 硬超时）。超了 Claude 那边当没决定，不如自己先退成 `ask`。
3. **「转终端」按钮**：不想在手机上决定时一键立刻返回 `ask`，别让 Claude 干等 9 分钟。

**验收（两条都要过）**：
- ✅ 手机**通知上直接**批一次危险命令，Claude 继续跑
- ✅ **`systemctl stop yxi-inbox` 后再触发一次 → Claude 退回终端问你**（不是自动放行）

**自检**：`test_yxi.py::test_approval_fail_closed` —— socket 路径指到不存在的文件，断言 `yxi-hook` 输出的 `permissionDecision == "ask"`。
> **这条测试比其它所有测试加起来都重要。** 它守的是"手机连不上时会不会自动放行"。

---

### Phase 6 · P1 功能（~1 天，按需）

对照 PRD §5.5：

| # | 功能 | 成本 |
|---|---|---|
| #8 | **远程剪贴板**（OSC 52） | 小 —— xterm.js 有现成 addon，几乎白送 |
| #9 | **语音 → 终端** | 小 —— Android `SpeechRecognizer` 系统自带，**不用云** |
| #10 | **最近的目录**（简版） | 小 —— 直接读 tmux 会话的 cwd |
| #12 | **粘贴图像**（不做标注） | 中 —— 传服务器 → 路径 send-keys 进去 |
| #11 | 跳转到… | 待定 —— 先用一阵看用不用得上 |

### Phase 7 · 发布准备（P0 跑通后再做）

| # | 做什么 | 注意 |
|---|---|---|
| 7.1 | 生成 **release keystore**，离线备份两份 | ⚠️ **一次定终身**，丢了 = 所有用户装不了更新 |
| 7.2 | `assembleRelease` + 签名 + GitHub Release 挂 APK | 附 SHA256 供校验 |
| 7.3 | 写面向用户的 README（安装 / 加主机 / 装 `yxi-agent` 三步） | 中英双语 |
| 7.4 | **拆仓**：公开仓（代码 + 用户文档）/ 私有仓（PRD、PLAN、handover、逆向笔记） | ⚠️ 见 §5 |

### Phase 8 · P2（不排期）

`#13 用量`（`cc-quota` 已有）· `#14 Diff 查看器` · `#15 硬件键盘`（基本白送）· `#16 手势简版` · **Chat View**（读 jsonl，对应 Moshi 的 `/v1/transcripts/blob`）

**`#18 Live Updates`（安卓版「灵动岛」）** —— `Notification.ProgressStyle`（API 36）把前台服务那条通知提升成**状态栏胶囊**，显示**审批 570 秒倒计时**。需 `compileSdk 36`（`minSdk` 仍可 26，运行时判版本）。⚠️ 完整体验先在 Pixel 放出，其它厂商不一定有 → **渐进增强，不当核心功能**。详见 PRD §2.7

**明确不做**：`#17 浏览器预览` · `#18 Live Activity`（iOS 独有） · `#19 Mosh` · `#20 主题字体图标` —— 理由见 PRD §5.5

---

## 2. 文件清单

```
/root/src/workspace/Yxi/
├── PRD.md / PLAN.md / handover.md      ✅ 已完成
├── TROUBLESHOOTING.md                  Phase 0 起边做边记
├── server/
│   ├── yxi-agent          ~200 行 Python · 不监听端口 · SSH 拉起
│   ├── yxi-inbox          ~80 行 · systemd · 只听 unix socket
│   ├── yxi-hook           ~60 行 · 挂 settings.json
│   ├── yxi-inbox.service
│   └── install.sh         注册 hook + 起服务（各台机跑一次）
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

服务器侧 ~340 行，App 侧 ~1250 行（含前端）。**总量约 1600 行。**

> 注意：**没有 `certs/` 了**。改走 SSH 后不需要自建 CA（PRD §2.3）。

---

## 3. 排期

| 阶段 | 工作量 | 累计后你能干什么 |
|---|---|---|
| Phase 0 | 半天 | 手机上装上了空壳 ← **先证明路通** |
| **Phase 1** | **2 天** | **能连所有服务器、开终端、选 tmux 会话** ← 已可替代现有 SSH App |
| Phase 2 | 1 天 | 终端真正好用（中文、方向键、重连） |
| Phase 3 | 1 天 | **会话看板 + 给任意会话发指令** ← Moshi 独有部分从这开始 |
| Phase 4 | 1 天 | 手机会主动响 |
| Phase 5 | 1 天 | **通知上直接批权限** ← P0 完成 |
| Phase 6 | 1 天 | 剪贴板 / 语音 / 图片 / 最近目录 |

**P0 合计约 6.5 天，含 P1 约 7.5 天。**

**三个自然停止点**：Phase 0（路不通就掉头）· **Phase 2**（当个好用的 SSH App 用着）· Phase 5（P0 完成）。

---

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
