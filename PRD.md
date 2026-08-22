# PRD · Yxi —— 自建手机指挥台（Moshi 复刻）

> 状态：草案 v1，待你拍板
> 日期：2026-08-22
> 一句话：把 Moshi 干的事——**手机上开终端、管一堆 tmux 会话、给 Claude Code 下指令和远程批权限**——在你自己的服务器上重做一遍，**去掉它的云端和原生 App**。

---

## 1. Moshi 是怎么做的（**基于 APK 逆向 + 官方文档**）

> 证据来源：微信收到的 `base.apk`（89 MB，已取回 `/root/inbox/base.apk`）。
> 包名 **`app.getmoshi.android`**，版本 **3.10.0**。
> 关键发现：**它是 Expo / React Native 应用**（`assets/index.android.bundle` 是 Hermes 字节码，
> Expo SDK 57，OTA 走 `api.getmoshi.app/api/ota/manifest`）。字符串表可读 → 下面的命令、接口、
> 表结构**全是从二进制里抠出来的原文**，不是猜的。

### 1.1 三层结构

| 层 | 是什么 | 干什么 |
|---|---|---|
| **手机端 App** | **Expo/RN + Hermes**，iOS(`app.getmoshi.ios`) / Android(`app.getmoshi.android`) | SSH/Mosh 客户端 + 终端 + 多路复用器面板 + Inbox + Chat View |
| **主机守护 `moshi-hook`** | 单二进制（macOS/Linux x86_64+arm64，作者 rjyo） | ①写 agent 配置 ②unix socket 收 hook 事件 ③连云 WS ④**本地网关 `127.0.0.1:24543`** |
| **Moshi 云端** | `api.getmoshi.app` | 配对、APNs/Expo 推送、事件中继、订阅/License |

**他不托管算力**——代码、密钥、agent 进程、tmux 全在你自己机器上，云端只当信使。

### 1.2 会话发现：一次 SSH 往返，标记分段（**这招值得偷**）

APK 里内嵌了一整段 shell，**连上时非交互跑一次**，输出用随机 marker 分段，客户端解析：

```sh
section() { printf '%s\t%s\t%s\n' "$marker" "$1" "$2"; }
if command -v tmux >/dev/null 2>&1; then
  section tmux_installed 1; section tmux begin
  tmux list-sessions -F '#{session_name}|#{session_windows}|#{session_activity}|#{session_attached}' 2>/dev/null || true
  section tmux end
else
  section tmux_installed 0
fi
if command -v zellij >/dev/null 2>&1; then …fi
if command -v herdr >/dev/null 2>&1; then
  herdr_json=$(herdr session list --json 2>/dev/null || true)
  …
  # JSON stays authoritative in the app. The table only lets this shell decide
  # whether to include sole-session workspaces without requiring jq or python.
fi
```

三个设计要点，直接抄：
1. **一次往返拿全部信息**，不是探一次连一次。手机网络下往返成本高，这个很关键。
2. **主机上不假设有 jq / python**，只用 `printf` / `awk`。（他自己在注释里写了原因）
3. **每段都 `|| true`**，任何一段失败不影响其余。

其余探测：`command -v moshi / moshi-hook / bash / curl / nc / pgrep`，剪贴板探 `xclip`(X11) / `wl-copy`(Wayland) / `osascript`(macOS)。
> 坑：全走**非交互 shell 的 PATH**，binary 不在那个 PATH 里就探不到。

attach 时它还会顺手调 tmux：
```
tmux set -g set-titles on \; set -g mouse on \; set -g status-right '' \
  \; unbind -q -T root WheelUpStatus \; unbind -q -T root WheelDownStatus
```
（开鼠标、清掉右侧状态栏、解绑滚轮在状态栏上的绑定——都是为手机屏幕让路）

### 1.3 两套 API：云端 vs 本地网关（**文档没写，从 APK 挖的**）

**云端 `api.getmoshi.app`**（过公网，只走摘要和元数据）
`/api/v1/register` · `/api/v1/hosts` · `/api/v1/agent-events` · `/api/v1/inbox/events` ·
`/api/v2/push/updateDeviceToken` · `/api/v1/rotate-token` · `/api/v1/transcribe`（语音转写）·
`/api/v1/dictation/usage` · `/api/v1/images/upload` · `/api/v1/accounts/usage-screen` ·
`/api/v1/licenses/*` · `/api/v1/subscriptions/*` · `/api/ota/manifest`

**本地网关 `127.0.0.1:24543`**（SSH 端口转发，**不过云**）
`/v1/diff/start` · `/v1/transcripts/blob` · `/v1/paste-image` · `/v1/list-dirs` ·
`/v1/workspaces/focus` · `/v1/webhooks/stream` · **`/v1/questions/answer`** · **`/v1/plans/answer`**

> ⚠️ **重要修正**：最后两个说明 **审批/提问的回答走的是本地网关，不是云**。云只负责"把你叫醒"（推送），
> 你一旦打开 App 建立了 SSH 转发，**决定是直接送回主机的**。这比我从文档推的版本更干净，
> 也说明他把"通知通道"和"控制通道"彻底分开了。

健康检查是三级的（错误文案原文可证）：
- 二进制不在 → 引导装
- `moshi-hook is installed but not running. Start it on the server…`（`pgrep` 探）
- `moshi-hook is running but the gateway isn't reachable on 127.0.0.1:24543.`（`nc`/`curl` 探）

### 1.4 Inbox 的数据模型（APK 里的 SQLite 建表原文）

```sql
CREATE TABLE IF NOT EXISTS inbox_events (
  id TEXT PRIMARY KEY NOT NULL,
  event_id TEXT NOT NULL,
  session_id TEXT,
  timestamp INTEGER NOT NULL,
  project TEXT NOT NULL,
  host_id TEXT NOT NULL,
  host_name TEXT NOT NULL,
  agent TEXT NOT NULL,
  pending_action_id TEXT,      -- 待批的动作 id
  resolved_action TEXT,        -- 批了什么
  archived_at INTEGER,
  category TEXT,               -- approval_required / task_complete / session_started / tool_running / tool_finished
  is_mock INTEGER NOT NULL DEFAULT 0,
  payload TEXT NOT NULL        -- 原始事件 JSON
);
CREATE TABLE IF NOT EXISTS inbox_tombstones (   -- 软删除，多设备同步用
  id TEXT PRIMARY KEY NOT NULL, event_ts INTEGER NOT NULL, deleted_at INTEGER NOT NULL
);
```

**手机端是本地 SQLite 镜像 + 墓碑软删除**，不是每次拉列表。`pending_action_id` / `resolved_action`
这两列就是审批状态机。看板三列（Needs you / Working / Done）由 `category` + `resolved_action` 推出来。
归档规则：待批一直留；完成的留 10 分钟；6 小时以上自动归档。
限流：免费 10 事件/60 秒，Pro 60 事件/60 秒。

### 1.5 事件链路与审批回程

```
Claude Code hooks → unix socket → moshi-hook
   → 云 POST /api/v1/agent-events → APNs/Expo → 手机（叫醒你）
   ← 你的决定 → 本地网关 /v1/questions/answer → moshi-hook → 还阻塞着的 hook 进程 → Claude 继续
```

> **这条链路能成立的唯一原因：Claude Code 的 hook 默认超时 600 秒。** hook 进程可以站那儿等 10 分钟。
> 整个"远程审批"就架在这一个数字上。

### 1.6 Chat View：终端是唯一真相

daemon 读本机 transcript → 走 SSH 转发的 `/v1/transcripts/blob` 流给手机 → 归一成"消息 / 工具卡片 /
计划 / 提问 / 图片 / 回合小结"。

> ⚠️ **第二个修正**：APK 里**搜不到 `send-keys`**。它的输入框是**直接往已连上的那条终端通道里打字**
> （反正 SSH/PTY 就在那儿）。所以它只能对**当前 attach 着的会话**发消息。
> ——这一点我们可以做得比他好，见 §2。

### 1.7 数据分流（他最聪明的设计）

| 走他家云 | 只走 SSH 直连、不过云 |
|---|---|
| 通知摘要、prompt 前 **200** 字、response 前 **80** 字 | **完整 transcript** |
| 审批的命令行前 **256** 字 | diff 内容、源码 |
| 元数据：项目名、session id、agent、model、tool 名 | **审批的回答本身** |

**推送必须过公网（APNs 就在那儿），但正文和控制指令可以不过。** 这条线切得很干净。

### 1.8 其余（解释了它为什么好用）
用量环（daemon 轮询各 agent rate-limit API）、灵动岛 Live Activity、语音听写（本地或云 `/api/v1/transcribe`）、
图片粘贴、OSC 52 剪贴板、diff 查看器、内嵌浏览器预览（往 WebView 注 `window.__moshiAutoplayPolicy` 解自动播放）、
手势、终端工具条（Ctrl/Esc/Tab/方向键 + tmux 前缀 + 窗口 1~20 快捷行）、YubiKey NFC 认证 SSH、
Face ID 保护私钥、Bonjour 发现局域网 `_ssh._tcp` 主机。

**CJK 字体是按需下载的**：`fonts.getmoshi.app/noto-mono-cjk/2.004/NotoMonoCJK{sc,tc,jp,kr}-2.004.zip`
（还有 Iosevka / DejaVu / Ioskeley）。说明**中日韩等宽字体在移动终端是个真问题**，他专门处理了。

---

## 2. 复刻的取舍（这份 PRD 的核心决定）

**你不是要做一个卖给所有人的产品，你是要一个自己用的。** 所以他为"做产品"付的那些成本，我们一分都不用付。

> 💡 **APK 带来的最大结论：他自己的客户端就是 JS。** Moshi 是 Expo / React Native（Hermes 字节码）+ OTA 热更新，**不是** Swift/Kotlin 原生。所以我们用 PWA **不是降级**——只是把 React Native 换成浏览器，逻辑层是同一个量级的东西。他之所以要打成 App，是因为要上架收订阅、要 APNs、要 Face ID/NFC/蓝牙这些原生能力，**这些我们一个都不需要。**


| Moshi 的做法 | 我们的做法 | 为什么 |
|---|---|---|
| iOS + Android 原生 App | **Android 原生 APK（侧载）**，iOS 暂缓 | 见 §2.1、§2.5。走 SSH → **整个 CA 证书问题消失** |
| App 内 SSH（原生桥 + 交叉编译 `.so`） | **纯 Java 库 `mwiede/jsch`**，gradle 一行依赖 | 见 §2.2——原生 Kotlin 不用 NDK。**SSH 是刚需，不砍** |
| App 内 **Mosh**（UDP 协议实现） | **不做** | 韧性来源本来就是 tmux（下一行） |
| 终端渲染自研 | **WebView + xterm.js** | 现成的、成熟的，省一大块 |
| 自建云端做配对 + 推送中继 | **删掉整层** | 单用户不需要多租户 |
| APNs + Expo Push（要 Firebase / 云） | **前台服务常驻一条 SSH exec channel**，自己发本地通知 | 零第三方、零云、零账号 |
| 本地网关 `127.0.0.1:24543` + SSH 端口转发 + HTTP | **SSH exec channel + stdin/stdout JSON 行协议** | 少绕一层 HTTP，**服务端不监听任何端口**（§2.3） |
| Mosh（UDP 抗断网） | **WSS 自动重连 + tmux 持久化** | 韧性的来源本来就是 **tmux**，mosh 只保客户端那条链路。断了重连 attach 回去，体感一样 |
| Face ID / NFC / YubiKey 保护私钥 | **Android Keystore**（硬件级，系统自带） | 标准 API，不用自己做 |
| 自建云配对 + `moshi-hook host setup` 二维码流程 | **App 生成密钥 → 显示公钥 → 你贴进 `authorized_keys`** | 一次性，一条命令的事 |
| 支持 6 家 agent（Codex/Cursor/Kimi…） | **先只做 Claude Code** | YAGNI。你机器上跑的就是 cc |
| 从零写会话发现、状态跟踪 | **复用你已有的** `hub` / `cc-state` / `cloud-sesslist` / `cc-agents` / `cc-quota` | 这些已经在跑了，见 §5.2 |
| 一次 SSH 往返 + marker 分段拿全部会话 | **照抄**（见 §1.2） | 这招是对的，手机网络下往返贵 |
| 探测标记**带版本号**（`__MOSHI_MULTIPLEXER_SNAPSHOT_V1__`） | **照抄** | 附录 C.1。agent 和 App 版本不同步时能优雅降级 |
| 往远端 shell 注入 `MOSHI_CLIENT=1` | **照抄**（`YXI_CLIENT=1`） | 附录 C.2。一行 export 换来主机侧脚本自适应 |
| 通知的 approve/deny action（`MOSHI_APPROVE_ACTION`） | **照抄** | 附录 C.6。这就是「通知上直接批」的实现方式 |
| 输入框只能发给**当前 attach 着**的会话（§1.6） | **`tmux send-keys` 发给任意会话** | 我们比他强的一点：不用先 attach 就能下指令 |
| 手机端 SQLite 镜像 + 墓碑软删除同步（§1.4） | **服务端一个 JSON 文件** | 单设备不需要多端同步，墓碑表是为多设备付的税 |

### 2.1 为什么从 PWA 改成 APK

原方案选 PWA，被一个问题卡住：**浏览器只信 CA 签发的证书**——Service Worker 和 Web Push 都强制
secure context，于是被迫去搞 Let's Encrypt / Tailscale funnel / 域名，还得把服务挂公网。

**那是浏览器的限制，不是这个问题本身的限制。** Moshi 从来没有证书问题，因为它是 SSH 客户端——
**SSH 自带加密和双向认证，不需要任何 CA。**

→ 改原生 App 后，**整个证书 / Tailscale / 公网暴露的问题链全部消失。**

### 2.2 SSH 不砍——而且它比我一开始说的容易得多

> ⚠️ **修正一个判断错误。** 我先前写过"App 内实现 SSH 是最难的部分，砍掉"。
> **对 React Native 确实难**（要写原生桥 + 交叉编译 `.so`）；**对原生 Kotlin 不难**——
> Java 生态有成熟的**纯 Java** SSH 实现，gradle 加一行依赖就行，**不需要 NDK、不需要 `.so`**。

| 库 | 状态 | 取舍 |
|---|---|---|
| **`mwiede/jsch`** ← **选它** | 活跃，2.28.3（2026-06） | 纯 Java，Android 上最省事，依赖最少 |
| `sshj` | 活跃，Apache 2.0（2025-05） | API 更现代，但拖 BouncyCastle，Android 上有冲突风险 |

**Moshi 那些缺失的 `lib/*.so` 到底是什么？** 从 dex 里挖出了它自己的原生包名（附录 A）：

| 原生包 | 是什么 | 我们的对应 |
|---|---|---|
| `app.getmoshi.**ghostty**`<br>`GhosttyTerminalManagerModule` / `ViewManager` | **嵌了 [Ghostty](https://ghostty.org) 的终端引擎**（Zig 写的 libghostty，C API） | **`termux/terminal-view`**（见下方取舍） |
| `app.getmoshi.**nitro.transport**` | Nitro Modules 写的原生传输层（SSH / Mosh） | **`mwiede/jsch`**（纯 Java，够用） |
| `app.getmoshi.**parakeet**` | NVIDIA Parakeet **端上语音识别模型** | Android `SpeechRecognizer`（系统自带） |
| `liveactivity` / `pasteimage` / `audiofocus` | iOS 灵动岛、图片粘贴、音频焦点 | 前两个不做 / 简版 |
| dex 里的 `crypto_kem_*` `crypto_sign_*` `ssh-ed25519` | libsodium 类 + 后量子 KEM（大概是 `sntrup761x25519`）+ SSH 主机密钥算法 | jsch 自带 |

**终端引擎怎么办**（Moshi 用的是原生 libghostty）——查过开源生态后，**我们不必退到 WebView**：

| 方案 | 许可 | 取舍 |
|---|---|---|
| **`termux/terminal-view` + `terminal-emulator`** ← **首选** | GPL-3.0 | Termux（59k★）里**已经拆成独立 gradle 模块**的原生终端控件，被几百万台设备验证过。纯 Java/Kotlin，**不用 NDK**。自用不触发 GPL 分发义务 |
| `connectbot` 的 `de.mud.terminal` vt320 | Apache-2.0 | 许可最宽松，ConnectBot（3.4k★）在用，成熟但更老 |
| `jackpal/Android-Terminal-Emulator` | Apache-2.0 | 3.2k★ 的 VT-100 实现，Termux 的祖先 |
| WebView + xterm.js | MIT | **退路**。VS Code 在用，成熟；但多一层 WebView，IME 和性能都吃亏 |

→ **先试 Termux 的 `terminal-view`**（原生控件，中文输入这类坑它早趟过了），
不行再退 WebView + xterm.js。**这样就没有"明确不如原版"这一条了。**

**Mosh（UDP 协议）我们不做**——韧性由 tmux 提供（§5.5 第 19 条）。

**而且 SSH 是刚需**：你要连 `station` / `inst2` / `inst3` / `inst4` 和 Windows 那几台，
不是只连本机。这一条把多主机从 P1 提到了 P0（§2.4）。

### 2.3 于是：一切走 SSH，服务端不开任何端口

既然 App 里已经有 SSH 了，那增值功能（看板 / 事件 / 审批）就没必要另起一条通道：

- **终端** = SSH **shell channel** + `tmux attach`（跟 Moshi 一样）
- **数据** = 同一条 SSH 连接上再开一个 **exec channel**，跑 `yxi-agent`，
  stdin/stdout 走 **JSON 行协议**

**`yxi-agent` 不监听任何端口**——它是被 SSH 调起来的普通进程，stdin/stdout 就是通道。于是：

| 上一版（WSS 方案） | 现在（SSH 方案） |
|---|---|
| 自建 CA + 签服务端/客户端证书 | ❌ 不需要 |
| 双向 mTLS + App 内 pin 指纹 | ❌ 不需要（**SSH 公钥认证本来就是双向的**） |
| token + 同 IP 失败锁定 | ❌ 不需要 |
| `ufw allow 8443/tcp` | ❌ **不开任何新端口** |
| 公网多一个服务入口 | ✅ **零新增攻击面** |
| `yxid` 常驻监听进程 | ✅ 按需被 SSH 拉起，用完就退 |

**比上一版又简单又安全。** 这也解释了 Moshi 为什么把本地网关放在 `127.0.0.1:24543`
再靠 SSH 端口转发访问——同一个道理，只是它多绕了一层 HTTP，我们直接用 stdin/stdout。

### 2.4 通用 SSH 客户端是 P0——**必须能连"以后才有的"服务器**

> ⚠️ 这一条曾经被我写错过（"我们只连自己的服务器，所以不用做 SSH"）。**错的。**
> 需求是**连任意服务器，包括现在还不存在的**——新开一台云主机、临时接一个客户的机器，
> 都要能在**手机上当场把它加进来**。这决定了 App 必须是个**完整的 SSH 客户端**，
> 不是"连几台预置机器的专用工具"。

**已知的机器**只是起点，不是全集：`station` · `inst2` · `inst3` · `inst4` ·
`laptop`（Windows 反向隧道）· `han`（客户 Windows，**非 22 端口**）。
> 具体 IP / 端口 / 凭据见 **`/root/src/CLAUDE.md`（权限 600，不入库）**——本仓只写别名。

#### 「加主机」必须支持的（P0）

| 项 | 要求 | 为什么 |
|---|---|---|
| 主机名 / IP | 任意填 | 新机器随时加 |
| **端口** | **任意，不能写死 22** | `han` 就是 2222 |
| 用户名 | 任意 | 不一定是 root |
| **密码认证** | **必须支持** | 新开的云主机**一开始只有密码**（你的备用密码清单就是这么用的） |
| 公钥认证 | 必须支持 | 日常主力 |
| **一键装公钥** | 密码登录一次 → App 把自己的公钥 append 进 `~/.ssh/authorized_keys` → 之后免密 | 相当于 `ssh-copy-id`。**比 Moshi 的二维码配对流程还省事**——它得先在服务器上跑 `moshi-hook host setup` 生成二维码再扫 |
| `known_hosts` | 首次显式确认指纹，之后变了就拒 | 不能 accept-any |

#### 两类主机，都要能用

| 主机类型 | 能干什么 |
|---|---|
| **装了 `yxi-agent`** | 完整功能：会话看板 + 事件通知 + 远程审批 + 终端 |
| **没装的（任意 SSH 主机）** | **普通 SSH 终端**，照样能连、能开 tmux。装不装 agent 是可选叠加 |

→ 这个 App 从第一天起就是个**通用 SSH 客户端**，Moshi 独有的增值功能是叠加在上面的。
**Phase 1 结束（2 天）你就有个能替代现有 SSH App 的东西。**

### 2.5 分发与平台（已定稿）

**分发：GitHub Releases。不上应用商店。**

一下砍掉一大堆产品化负担：不用审核、不用隐私政策、不用数据安全声明、不用追 targetSdk 合规。
就是开源项目的标准做法：Release 页挂 APK，用户下载侧载。

> ⚠️ **签名密钥一次定终身**：第一个 Release 用哪个 keystore 签，以后**所有版本必须用同一个**。
> 换了签名安卓会拒绝更新，用户只能卸载重装。**keystore 要离线备份、绝不入 git**（已在 `.gitignore`）。

**平台：第一期只做 Android。iOS 不做。**

理由不是技术，是苹果的分发限制——**iOS 装不了 GitHub Release 上的东西**：

| 路子 | 代价 |
|---|---|
| App Store | 99 美元/年 + 审核 |
| TestFlight | 也要 99 美元账号，外部测试上限 100 人 |
| AltStore / SideStore | 用户得有电脑，**免费账号每 7 天重签一次** |

**「只发 GitHub Release」和「支持 iOS」本质冲突**，所以第一期明确不做。

> 好消息：**`yxi-agent` 与客户端形态完全无关**（就是个读 stdin 写 stdout 的进程）。
> 哪天决定掏 99 美元做 iOS，服务器侧一行不用改。

### 2.6 面向全球用户 —— 但**不需要任何后端**

这套架构原样就能给全球用户用，因为**每个用户连的是他自己的服务器**：
装我们的 APK → 加他自己的主机 → 在他自己的服务器上装 `yxi-agent`。

**我们不跑任何服务器、没有账号体系、没有云。** 比 Moshi 更彻底——它还有个云做推送中转和授权，我们连这个都不要。

**唯一会把后端拽回来的是推送，所以推送必须选对**（§2.7）。

其余全球化工作：**界面多语言**（首期中文 + English，Moshi 做了约 15 种，可后续加）。

### 2.7 推送：默认前台服务，**明确不用 FCM**

| 方案 | 要谷歌吗 | **要我们跑服务器吗** | 国内手机 | 采用 |
|---|---|---|---|---|
| **前台服务** | ❌ | ❌ | ✅ | ✅ **默认，开箱即用** |
| **ntfy**（用户自己填地址） | ❌ | ❌（是用户自己的） | ✅ | ✅ **可选，想省电就配** |
| FCM | ✅ | ✅ **要**（见下） | ❌ 无 GMS 即废 | ❌ **不用** |

**FCM 为什么不能用**：它要求 App 内嵌**我们的** Firebase 配置、发送方持**我们的**服务器密钥。
用户自己的服务器拿不到那个密钥，链路必须变成

```
用户的服务器 → 【我们的中转服务器】 → 谷歌 FCM → 用户手机
```

中间那一环一出现，我们就得**长期跑一台服务器**：花钱、运维、扩容，而且**用户的通知内容全部经过我们**。
对一个免费开源工具，这个包袱不能背。

**前台服务的真实代价**（不粉饰）：
- 通知栏常驻一条划不掉的通知 → 设成最低优先级、可折叠
- 耗电：一条闲置 SSH 连接只是几十秒一次心跳，跟 Syncthing / Termux 同量级，日常掉电约几个百分点
- **国产 ROM 会杀它**（即使是前台服务）→ 引导用户加电池白名单 + `START_STICKY` + 开机广播 +
  `yxi-inbox` 保证事件不丢（最坏只是延迟收到）

---

**结果：服务器侧 P0 不需要任何新依赖。** 已在本机核实：`pty`（stdlib）、`websockets`、`cryptography` 都在；`tailscale serve` 能签 Let's Encrypt 真证书。唯一要下载的是 xterm.js 的两个静态文件。

---

## 3. 目标与非目标

**目标**
1. 人在外面、只有手机时，能看到服务器上**所有 cc 会话在干嘛**，一眼看出哪个卡着等你。
2. 能**远程批权限**，不用打开终端。
3. 能**给任意会话发指令**（一句话就行，不用开终端）。
4. 需要时能开**真终端**，手机上能打字、能跑命令。
5. Claude 干完活 / 要授权 → **手机主动响**，不用自己去刷。

**非目标（明确不做）**
- ✗ 多用户、多租户、别人也能用的 SaaS
- ✗ **上架应用商店**（只侧载）
- ✗ **iOS 版**（第一期不做，见 §2.5——苹果不允许从 GitHub Release 安装）
- ✗ **应用商店**（走 GitHub Releases）
- ✗ **FCM / 任何需要我们跑服务器的推送**（见 §2.7）
- ✗ **账号体系 / 云端**（每个用户连自己的服务器，见 §2.6）
- ✗ **App 内 Mosh（UDP 协议）**——韧性由 tmux 提供
- ✗ **新的网络监听端口**（走 SSH，见 §2.3）
- ✗ 除 Claude Code 外的 agent
- ✗ 替代桌面开发（这是**遥控器**，不是工作站）

---

## 4. 场景（验收就照这几条走）

1. **地铁上**：手机弹通知「cc-mail 要跑 `rm -rf build/`」→ 点开看到完整命令 → 点"批准" → Claude 继续。全程 10 秒，没开终端。
2. **躺床上**：打开 Yxi → 看板：`cc-nanobanana` 在 Working、`cc-项目` 在 Needs you、其余 Done → 点 `cc-项目` → 看到它在问什么 → 直接打字回它。
3. **等电梯**：想起来该让 `cc-mail` 跑个测试 → 会话列表长按 → 输入 "跑一下测试" → 发送 → 收工。
4. **出事了**：某个服务挂了 → 开 `cc-root` 的真终端 → `systemctl restart xxx` → 看输出。
5. **切网**：从 WiFi 切 4G → 终端卡 2 秒 → 自动重连，**还在原来那个会话、原来那个位置**。

---

## 5. 功能需求

### 5.1 P0（不做这些就不叫复刻）

| # | 功能 | 说明 |
|---|---|---|
| P0-1 | **会话总览** | 列出所有 `cc-*` tmux 会话 + 状态（work/input/done/idle）+ 最近在干嘛 + 项目路径。三列看板：等你 / 干活中 / 已完成 |
| P0-2 | **真终端** | 点会话 → xterm.js 接上 `tmux attach`。能打字、能看颜色、能滚。断线自动重连 |
| P0-3 | **推送通知** | Claude 要授权 / 干完一轮 → 手机响。点通知直达对应会话 |
| P0-4 | **远程审批** | 通知里/看板里显示 工具名 + 完整参数 → 批准 / 拒绝 / 转终端。**超时 fail-closed** |
| P0-5 | **快捷发指令** | 不开终端，选会话 → 打一句话 → `tmux send-keys` 送进去 |
| P0-6 | **移动键盘工具条** | Esc / Tab / Ctrl / ↑↓←→ / Ctrl-C / tmux 前缀。手机软键盘没这些键，没它终端等于废 |
| P0-7 | **通用 SSH 客户端** | **能在 App 里现加任意新服务器**（IP / 任意端口 / 用户名 / 密码或密钥），私钥存 Android Keystore。**没装 `yxi-agent` 的机器也能当普通 SSH 终端用**。详见 §2.4 |
| P0-8 | **一键装公钥** | 用密码连上一次 → 一键把 App 公钥 append 进 `~/.ssh/authorized_keys` → 之后免密。相当于 `ssh-copy-id`（§2.4） |

### 5.2 P0 能白捡的现成件（这就是为什么 P0 不大）

| 已有的 | 在哪 | P0 里当什么用 |
|---|---|---|
| `cc-state` | `/root/.local/bin/cc-state`，已挂在 `~/.claude/settings.json` 的 6 个 hook 上 | **状态源**。它已经把每个会话的 work/input/done 写进 `~/.cloud-status/<会话>.json` 了。P0-1 直接读 |
| `hub say` | `/root/.local/bin/hub` | **P0-5 的发送内核**，已验证能把消息打进 cc 会话 |
| `cloud-sesslist` / `cloud-sesspreview` | `remote-dev-station/bin/` | 会话枚举 + 屏幕预览的现成逻辑 |
| `cc-quota` | 同上 | P2 用量环的数据源 |
| tmux | 已装，14 个 `cc-*` 会话在跑 | 会话持久化基座 |
| Moshi 的 `-F` 格式串 | 从 APK 挖的（§1.2） | P0-1 的会话枚举命令，直接抄 |
| 你的侧载通路 | 微信 / `lapput`（Moshi 的 APK 就是这么到你手机的） | APK 分发，不用另想办法 |

> ~~Tailscale~~ **已不再需要**——改 APK 后不用 CA 证书了（§2.1）。

### 5.3 P1（明显更好用，但不阻塞）
- **Chat View**：读 `~/.claude/projects/<项目>/<uuid>.jsonl` 渲染成对话流（已验证格式：每行一个 JSON，含 `type`/`message`/`timestamp`/`cwd`/`sessionId`）
- **语音输入**：浏览器 Web Speech API，一行的事
- **图片上传**：手机拍照/截图 → 存到服务器 → 把路径发给 cc
- **diff 查看器**：`git diff` 渲染

### 5.4 P2（有余力再说）
- 用量/配额环、Live Activity 类的常驻通知、多主机（station / inst2）、`hub` 跨会话消息在手机上可见

---

### 5.5 与原版 Moshi 的**逐条功能对照**（依据 App 内功能清单）

> 来源：Moshi App 自带的功能列表（用户提供）。这是**权威清单**，比从文档推的准。
> 一条一条决定做不做——**"不做"的每一条都写清楚为什么**。

| # | Moshi 功能 | 在 App 哪 | 我们 | 优先级 | 说明 |
|---|---|---|---|---|---|
| 0 | **连接管理**（加/编辑主机） | 主页 → 连接 | ✅ 做 | **P0** | **必须能现加新服务器**：任意 IP / 端口 / 用户名 / 密码或密钥 + 一键装公钥（§2.4） |
| 1 | **tmux 会话** | 连接 → 选会话 | ✅ 做 | **P0** | 核心。会话枚举命令已从 APK 挖到（§1.2） |
| 2 | **方向键** | 键盘工具栏 | ✅ 做 | **P0** | 手机软键盘没方向键/Esc/Tab/Ctrl，**没它终端等于废** |
| 3 | **CJK 输入** | 设置 → 终端字体 | ✅ 做 | **P0** | 中文是刚需。需内嵌等宽 CJK 字体（Moshi 专门下载 NotoMonoCJK，§1.8） |
| 4 | **收件箱** | 主页 | ✅ 做 | **P0** | **这是复刻的核心动机**。表结构已从 APK 挖到（§1.4） |
| 5 | **（审批）** | 收件箱 | ✅ 做 | **P0** | 同上。整个项目最有价值的一块 |
| 6 | **快捷面板** | 设置 → 快捷 | ⚠️ 简版 | **P0** | 做固定的一排（tmux 前缀 + 窗口 1~9），**不做自定义按钮编辑器** |
| 7 | **在终端中滚动** | 任何终端会话 | ✅ 做 | **P0** | xterm.js 自带，白送 |
| 8 | **远程剪贴板** | 任何终端会话 | ✅ 做 | P1 | OSC 52，xterm.js 有现成 addon，几乎白送 |
| 9 | **语音 → 终端** | 设置 → 听写 | ✅ 做 | P1 | Android `SpeechRecognizer` 系统自带。**Moshi 是自己塞了 NVIDIA Parakeet 端上模型**（§2.2）——我们用系统的就够，省一整个模型 |
| 10 | **最近的目录** | 连接 | ⚠️ 简版 | P1 | 直接读 tmux 会话的 cwd，**不做 Moshi 那套 `moshi-hook cwd-list` 历史库** |
| 11 | **跳转到…** | 终端工具栏 | ⚠️ 待定 | P1 | 先用一阵看用不用得上，别提前造 |
| 12 | **粘贴并标注图像** | 聊天输入框 | ⚠️ **只做粘贴** | P1 | 传图 → 存服务器 → 路径 send-keys 进去。**标注（画笔）不做**：成本高、收益低 |
| 13 | **用量** | 主页 | ✅ 做 | P2 | `cc-quota` 已有现成的，包一层就行 |
| 14 | **Diff 查看器** | 终端标题栏 | ⚠️ 待定 | P2 | `git diff` → WebView 渲染。不难，但不急 |
| 15 | **硬件键盘** | 设置 → 键盘 | ⚠️ 白送 | P2 | Android 蓝牙键盘基本自动工作。**不做自定义键位映射** |
| 16 | **终端手势** | 设置 → 手势 | ⚠️ 简版 | P2 | 只做双指滑动切窗口。**不做手势编辑器** |
| 17 | **浏览器预览** | 终端标题栏 | ❌ **不做** | — | 手机上预览服务器的 web 服务。SSH `-L` 端口转发能做，但**用得着的场景太少** |
| 18 | **Live Activity** | 设置 | ❌ **做不了** | — | **iOS 独有的锁屏常驻卡片**，Android 没有对等物。<br>Android 的近似物是前台服务的常驻通知——**我们本来就有一个**（§6） |
| 19 | **Mosh 连接** | 任何终端会话 | ❌ **不做** | — | 要 UDP + 原生协议实现。**韧性由 tmux 提供**：断线重连 attach 回去，内容一点不丢（Moshi 自己文案也承认："Mosh keeps the live terminal connected, but it does not carry scrollback… tmux keeps the scrollback"） |
| 20 | **主题、字体与图标** | 设置 | ❌ **不做** | — | 一个人用，一套配色够了。**这是最典型的"做产品才需要"的功能**——他要卖给一万个审美不同的人，我们不用 |

**统计**：21 条里 **P0 做 8 条、P1 做 5 条、P2 做 4 条、明确不做 4 条。**

**四条"不做"的共性**：Live Activity 是平台不支持；Mosh 是有更省的替代；
浏览器预览和主题系统是**做产品才需要，自用不需要**。
——这正是复刻能比原版省一大截的地方：**Moshi 的成本里有相当一部分是"卖给别人"的成本。**

---

## 6. 架构

```
┌───────────────────────────────────────────────────┐
│  Yxi.apk  ——  Android 原生（Kotlin）               │
├───────────────────────────────────────────────────┤
│  HostListActivity   多主机列表（station/inst2/inst3/…）│
│  SessionsActivity   某台机的会话看板（等你/干活中/完成） │
│  TerminalActivity   WebView + xterm.js + 键盘工具条    │
│  SshManager         mwiede/jsch，管连接池 + known_hosts│
│                     私钥存 Android Keystore（硬件级）  │
│  EventService       前台服务：对装了 agent 的主机常驻    │
│                     一条 exec channel，收事件→本地通知  │
│                     通知上直接带「批准 / 拒绝 / 转终端」 │
└───────────────────────────────────────────────────┘
        │
        │   SSH（公钥认证 · 22 端口 · 本来就开着 · 零新增端口）
        │   ├─ shell channel  →  tmux attach            【终端】
        │   └─ exec channel   →  yxi-agent              【数据 / 事件 / 审批】
        ▼
┌───────────────────────────────────────────────────┐
│  任意目标服务器                                     │
│                                                   │
│  ┌─ 装了 yxi-agent ─────────────────────────────┐  │
│  │  yxi-agent   ~200 行 Python，**不监听端口**    │  │
│  │    stdin  ←  list / peek / send / approve     │  │
│  │    stdout →  事件流（JSON 行）                 │  │
│  │              ↕ /run/yxi.sock                  │  │
│  │  yxi-hook    挂 ~/.claude/settings.json        │  │
│  │              PermissionRequest / Notification │  │
│  │              / Stop / SessionStart / SessionEnd│  │
│  └───────────────────────────────────────────────┘  │
│                                                   │
│  没装的主机 → 就是个普通 SSH 终端，照样能连         │
└───────────────────────────────────────────────────┘
```

**跟 Moshi 比，少了一整层**：他家云端我们没有。
**通知不需要 FCM / APNs**：前台服务自己持着那条 exec channel，事件下来直接
`NotificationManager` 弹本地通知。Android 前台服务是官方支持的常驻方式（带一个持久通知），
**零第三方、零推送服务、零账号**。

**`yxi-agent` 的协议**（stdin/stdout，JSON 行）：

| 方向 | 消息 | 说明 |
|---|---|---|
| App → agent | `{"op":"list"}` | 返回会话 + 状态（抄 Moshi 的 `-F` 格式串，§1.2）+ 读 `~/.cloud-status/*.json` |
| App → agent | `{"op":"peek","s":"cc-mail","n":40}` | `tmux capture-pane -p` |
| App → agent | `{"op":"send","s":"cc-mail","text":"跑一下测试"}` | `tmux send-keys` |
| App → agent | `{"op":"approve","id":"...","decision":"allow"}` | 唤醒阻塞的 hook |
| agent → App | `{"ev":"approval_required","id":"...","tool":"Bash","input":{...}}` | 由 hook 经 unix socket 投进来 |
| agent → App | `{"ev":"task_complete"\|"session_started"\|...}` | 限流：`tool_running`/`tool_finished` 折叠 |

**`yxi-hook` 的行为**（核心，与客户端形态无关）：
- `PermissionRequest` → 投事件到 `/run/yxi.sock` → **阻塞等回复，最多 570 秒**
  （留 30 秒余量，hook 硬超时 600 秒）→ 打印
  `{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow"|"deny","permissionDecisionReason":"手机批准"}}`
- **超时 / agent 没连 / socket 连不上 → 输出 `"ask"`**，退回终端手动批。**绝不默认 allow。**
- 其余事件 → 投完就退，不阻塞。

> ⚠️ 一个设计约束：`yxi-agent` 由 SSH 拉起、随连接生命周期存在，但 `yxi-hook` 要往
> `/run/yxi.sock` 投事件**随时可能发生**（手机没连着的时候 Claude 也在干活）。
> 所以 socket 那头需要一个**极小的常驻收件箱**（`yxi-inbox`，systemd，~80 行）：
> hook 投给它，它落盘；agent 连上时把积压的一次性吐给 App。
> **它只监听 unix socket，不监听网络端口**，攻击面为零。

---

## 7. 关键技术决策

| 决策 | 选择 | 状态 |
|---|---|---|
| 客户端形态 | **Android 原生 APK（侧载）** | 见 §2.1、§2.5 |
| **传输** | **SSH**（shell channel + exec channel） | 见 §2.3——**不开任何新端口** |
| **CA 证书 / TLS / token** | **全部不需要** | SSH 公钥认证已经是双向的 |
| App 内 SSH 库 | **`mwiede/jsch`**（纯 Java，2.28.3 / 2026-06） | 备选 `sshj`（拖 BouncyCastle，Android 上有冲突风险） |
| App 内 Mosh | **不做** | 韧性由 tmux 提供；断线重连 attach 回去体感一样 |
| 私钥存哪 | **Android Keystore**（硬件级） | 系统标准 API |
| 密钥分发 | App 生成 ed25519 → 显示公钥 → 贴进 `authorized_keys` | 一次性；比 Moshi 的二维码流程更简单 |
| 终端渲染 | **`termux/terminal-view`**（原生控件，GPL-3.0）；退路 WebView + xterm.js | 见 §2.2。原生控件中文输入更稳 |
| 事件常驻 | **前台服务 + exec channel**（默认）；**ntfy** 可选 | 见 §2.7。**明确不用 FCM**——它会强制我们长期跑一台中转服务器 |
| 服务端形态 | `yxi-agent`（**不监听端口**）+ `yxi-inbox`（**只听 unix socket**） | 零新增网络攻击面 |
| 终端桥 | Python stdlib `pty` | ✅ 在（其实走 SSH 后连它都可能不需要——直接 shell channel 跑 `tmux attach`） |
| 远程审批可行性 | Claude Code hook 默认超时 **600s** | ✅ 官方文档确认 |
| 状态源 | `~/.cloud-status/*.json` | ✅ `cc-state` 已在写 |
| 会话枚举 | 抄 Moshi 的 `-F` 格式串（§1.2） | ✅ 已验证 |
| transcript 格式 | JSONL，每行一个事件 | ✅ 已实际读取确认 |
| **构建工具链** | JDK 17 + Android cmdline-tools + Gradle | ⚠️ **本机都没装**，约 3 GB；无 GUI 可 `gradlew assembleDebug` |
| 分发 | **GitHub Releases**（开发期用 `adb install` / 微信侧载） | 见 §2.5。⚠️ 签名 keystore 一次定终身，离线备份 |

> ~~端口 8443 / ufw / mTLS / 自建 CA~~ —— 改走 SSH 后**全部作废**（§2.3）。

---

## 8. 安全要求

**改走 SSH 之后，这一节大幅缩水——因为我们没有新增任何网络入口。**

1. **认证复用 SSH**：公钥认证，私钥存 **Android Keystore**（硬件级，导不出来）。
   **手机丢了 → 服务器上删那一行 `authorized_keys` 即可**，不用改任何服务配置。
2. **`known_hosts` 必须校验**：首次连接指纹要**显式确认**，之后变了就拒。
   **不能图省事直接 accept-any**——那等于把 SSH 的中间人防护关掉了。
3. **审批 fail-closed**：超时 570s / agent 没连 / socket 连不上 / 解析失败 →
   **一律返回 `ask`**，退回终端。**任何分支都不能自动 allow。**
4. **`yxi-inbox` 只监听 unix socket**，文件权限 `0600`，不绑任何网络地址。
5. **审批决定单独记日志**（谁、何时、批了什么），沿用 `~/.hub/send.log` 风格。
6. **不新开端口、不改 ufw、不动 sshd 配置。**

> ⚠️ **与本项目无关但更要紧的**：本机 sshd 同时开着 root 登录和**密码认证**，
> 公网 22 端口每天被僵尸网络爆破数千次（`auth.log` 可查，前十来源合计三千余次失败）。
> 手机端**已在用公钥** → 关掉密码认证不影响使用。
> 这跟 Yxi 无关，但它是这台机上最大的敞口，**别让 Yxi 的安全工作掩盖了它**。

---

## 9. 验收标准

P0 算做完，当且仅当 §4 的 5 个场景**在真手机上**全部走通，外加：
- [ ] APK 能侧载安装并正常启动
- [ ] **能连上 `station` / `inst2` 这些没装 agent 的机器，当普通 SSH 终端用**
- [ ] `known_hosts` 指纹变更时**会拒绝**（不是静默接受）
- [ ] 手机**锁屏**能收到审批通知，通知上直接点「批准」即可放行（不用打开 App）
- [ ] **`systemctl stop yxi-inbox` 后再触发一次 → Claude 退回终端手动批**（不是自动放行）
- [ ] 手机没连着的时候 Claude 产生的事件，连上后能补收到（`yxi-inbox` 积压吐出）
- [ ] 4G ↔ WiFi 切换后终端自动恢复，会话内容没丢
- [ ] **中文能打进终端**（CJK/IME 是移动终端的经典坑，Moshi 专门下载 NotoMonoCJK 字体处理过，见 §1.8）
- [ ] 前台服务被系统杀掉后能自恢复（或至少重开 App 立刻重连）

---

## 10. 风险

| 风险 | 影响 | 对策 |
|---|---|---|
| **Android 工具链从零搭**（无 Java、无 SDK） | 中 —— Phase 0 全部时间可能都花在这 | 纯 CLI 装，路径成熟；先出 hello-world APK 侧载成功再往下走 |
| **前台服务被厂商 ROM 杀**（国产 ROM 尤其狠） | 高 —— 杀了就收不到通知 | 电池优化白名单 + `START_STICKY` + 开机广播；**`yxi-inbox` 保证不丢事件，最坏情况是延迟收到**；再兜底可加 ntfy 作第二通道 |
| jsch 在 Android 上的算法兼容（新 KEX / ed25519） | 中 | 2.28.3 已支持；Phase 1 先拿 `station`/`inst2` 实测，不行换 sshj |
| WebView + xterm.js 的 IME / 中文输入坑 | 中 | 早验证，进 §9 验收清单 |
| hook 阻塞 570 秒期间 Claude 完全卡住 | 中 | 设计使然（Moshi 同理）。通知上给「转终端」按钮立刻放行回终端 |
| **iOS 装不上** | 中 | 已接受（§2.5）。iOS 继续用原版 Moshi；`yxi-agent` 共用，将来不返工 |
| 手机丢失 | 中 | 私钥在 Keystore 导不出；服务器删 `authorized_keys` 一行即可撤销 |

---

## 附：待确认事项

1. ✅ **已定：先做 Android APK**，iOS 继续用原版 Moshi（§2.3）。
2. ✅ **已定：不需要 Tailscale、不需要 CA 证书、不开新端口**（§2.3）。**认证复用 SSH 公钥。**
3. ✅ **已定：SSH 不砍**——你要连 `station` / `inst2` / `inst3` / `inst4` 和 Windows 那几台。
   多主机因此从 P1 提到 **P0**（§2.4）。没装 `yxi-agent` 的机器 = 普通 SSH 终端，照样能用。
4. ✅ **已定：原版 Moshi 不并行用**（安卓侧）。
   → `~/.claude/settings.json` 里**不会有两套 hook 抢 `PermissionRequest`**，配置省一截。
   ⚠️ 但注意：**iOS 那边你还在用 Moshi** → 如果 iOS 的 Moshi 也配了 `moshi-hook` 到**同一台服务器**，
   那还是会打架。开工前确认这台机上 `moshi-hook` 有没有装/在跑（当前：**没装**，已查）。

---

## 附录 A · 从 APK 提取的实物清单

| 项 | 值 |
|---|---|
| 文件 | `/root/inbox/base.apk`（89,254,837 字节），解包在 `/root/inbox/apk/` |
| 来源 | 微信 → `C:\Users\dfhzw\xwechat_files\...\2026-08\base.apk.1`（经 `laptop` 反向隧道取回） |
| 包名 / 版本 | `app.getmoshi.android` / **3.10.0**（iOS 对应 `app.getmoshi.ios`，Team `668Y6UQ668`） |
| 框架 | Expo SDK **57** + React Native + **Hermes 字节码 v98**（`assets/index.android.bundle`，8.7 MB） |
| OTA | `https://api.getmoshi.app/api/ota/manifest`，`runtimeVersion` 3.10.0 |
| URL scheme | `moshi://` |
| 内购 | `app.getmoshi.pro`、`app.getmoshi.2026.yearly` |
| 原生能力 | Face ID(私钥)、NFC/YubiKey(SSH 认证)、Bonjour `_ssh._tcp`(局域网发现)、麦克风(听写)、相机(扫配对码)、Live Activities |
| 注意 | 这是 **split APK 的 base 部分**，`unzip -l` 数出 **0 个 `.so`** → 原生库全在 `split_config.<abi>.apk` 里，**没拿到** |
| 后果 | **单独装 base.apk 跑不起来**（一启动就找不到 libghostty / transport）。想在模拟器里跑真 Moshi，得拿到完整 split 集——见 PLAN §1 Phase 0.6 |

**怎么复现这次逆向**（Hermes 字符串表可读，但字符串是拼接存储的，`strings` 会串行）：
```bash
unzip -o base.apk assets/index.android.bundle
grep -aoE '<正则>' assets/index.android.bundle | sort -u     # 注意必须带 -a
# 要读上下文，用 python 找 offset 再打印前后字节（脚本见 scratchpad/dig.py）
```

**从 dex 里挖到的原生实现**（`classes*.dex`，`strings` 即可）：
```
Lapp/getmoshi/ghostty          →  GhosttyTerminalManagerModule / GhosttyTerminalViewManager
Lapp/getmoshi/nitro/transport  →  Nitro Modules 原生传输层（SSH / Mosh）
Lapp/getmoshi/parakeet         →  NVIDIA Parakeet 端上 ASR
Lapp/getmoshi/liveactivity | pasteimage | audiofocus
crypto_kem_dec/enc/keypair · crypto_sign* · ssh-ed25519/rsa/dss   →  libsodium 类 + 后量子 KEM
```
→ **这就是它必须有 `lib/*.so` 的原因**（见 §2.2 的对照）。

**没能拿到的**（如果需要，得另想办法）：
- `moshi-hook` 二进制本身（服务器侧，`cdn.getmoshi.app/hook/latest/`）——**审批阻塞的具体实现在它里面**
- 本地网关 `24543` 的**请求体格式**（只拿到路径，没拿到 schema）
- 原生 SSH/Mosh 层（在缺失的 split APK 里）

> 这三样都不阻塞复刻——我们的实现本来就不同（§6）。列在这只是说明**证据边界在哪**：
> §1 里凡是引了原文命令/SQL/路径的都是实证，其余（如云端 200/80/256 字截断）来自官方文档，未在 APK 中二次核实。

---

## 附录 B · 开源参考项目（GitHub 实查，2026-08）

### B.1 Android SSH 客户端 —— Phase 1 抄这些

| 项目 | ★ | 许可 | 为什么看它 |
|---|---|---|---|
| **`GlassHaven/Haven`** | 1091 | AGPL-3.0 | **Kotlin 写的现代 Android SSH/VNC/RDP/SFTP 客户端，今天还在更新**。跟我们 Phase 1 要做的几乎一样，**最直接的参照** |
| **`connectbot/connectbot`** | 3391 | **Apache-2.0** | Android 上第一个 SSH 客户端，仍在维护。许可最宽松→**可以直接抄代码**。自带 `sshlib`（Trilead SSH2 分支）和 vt320 终端 |
| `electerm/electerm` | 14905 | MIT | 桌面端（Electron），但 UI/交互设计值得参考 |

### B.2 Android 终端控件 —— 替代 WebView+xterm.js（见 §2.2）

| 项目 | ★ | 许可 | 说明 |
|---|---|---|---|
| **`termux/termux-app`** | 59558 | GPL-3.0 | **`terminal-view` / `terminal-emulator` 已是独立 gradle 模块**（已核实目录存在），可直接依赖 |
| `jackpal/Android-Terminal-Emulator` | 3188 | Apache-2.0 | Termux 的祖先，VT-100，许可宽松 |

### B.3 Claude Code 远程控制 —— 别人怎么解这道题

| 项目 | ★ | 许可 | 做法 |
|---|---|---|---|
| **`tuchg/Lucarne`** | 332 | MIT | **最值得看的**。Rust 守护 `lucarned`，**通知和审批走微信 / Telegram**，**不做 App**。<br>⚠️ 它明确"**no hooks, no skills, no MCP**"（零侵入）→ 靠**监视 CLI 进程/终端输出**判断状态，而不是 Claude 的 hook。<br>**取舍相反**：它牺牲准确性换零配置；我们用 hook 换准确性和**能真正阻塞住授权** |
| `voglster/lumbergh` | 32 | MIT | 自托管 web 看板，监督多个 Claude Code |
| `1203Arya/Claude-control` | 0 | ? | "Control Claude Code from your phone. Every file write, bash command…" |
| `devswha/chatmux` | 18 | AGPL-3.0 | tmux 聊天式 web 终端，agent 无关 |
| `chrismccord/webtmux` | 136 | MIT | Phoenix 作者写的 tmux 专用 web 终端 |
| `linwk20/tmux-kanban` | 11 | MIT | tmux 会话的 web 看板 |

### B.4 从 Lucarne 学到的一个备选思路：**微信当审批通道**

Lucarne 不做 App，把通知和审批塞进**微信 / Telegram**——"引用一条通知回复，它自动恢复对应的 agent 会话"。

对你特别有意思，因为**你本来就重度用微信**（Moshi 的 APK 就是微信传过来的）。

| | 我们的方案（APK） | 微信通道 |
|---|---|---|
| 手机装什么 | 侧载我们的 APK | **什么都不用装** |
| 终端 | ✅ 有 | ❌ 没有 |
| 审批 | ✅ 通知上点按钮 | ✅ 引用回复 |
| 内容经过 | **只经过你自己的机器** | 命令内容**过腾讯服务器** |
| 实现成本 | 中 | 低（但要接微信机器人，个人号有封号风险） |

**不改变主方案**（你要的是 APK，且终端是刚需）。
但 **Phase 4 的通知兜底**（国产 ROM 杀前台服务时）除了 ntfy，**微信也是一条候选**——记在这里备用。

---

## 附录 C · 深度反编译成果（第二轮，2026-08-22）

> 第一轮只用 `strings` 硬抠（结果粘连）。第二轮上了真工具：
> **`hermes-dec`** 解 Hermes 字节码 → **56,188 条干净分离的字符串**；**`jadx`** 反编译 7 个 dex。
> 下面全是原文。

### C.1 探测协议是**带版本号**的

```
__MOSHI_MULTIPLEXER_SNAPSHOT_V1__     ← 就是预检脚本里那个 $marker（§1.2）
__MOSHI_HOOK_PROBE_V2__               ← 探 moshi-hook 是否就绪，已经迭代到 V2
__MOSHI_RECENT_CWDS_V1__              ← 探最近工作目录
```

**值得抄**：探测输出用**带版本号的标记**分段，客户端按版本解析。
主机侧 agent 升级了、客户端还是旧的，能优雅降级而不是解析崩掉。我们的 `yxi-agent` 照做。

### C.2 `MOSHI_CLIENT=1` —— 让主机知道"这是手机在开"

它会（可开关，设置项 `MOSHI_CLIENT_SSH_EXPORT`）在远端 shell 里注入：
```
export MOSHI_CLIENT=1
```
（也见 ` -l MOSHI_CLIENT=1` 形式）

**这招很聪明，直接抄**。主机上的脚本 / `.bashrc` / agent 可以据此改变行为——
比如手机连进来时自动用更窄的输出、跳过花哨的 TUI、把提示语写短。
一行 export 换来整个主机侧的自适应能力。

### C.3 OSC 777：把结构化消息塞进**终端字节流**

```
\x1b]777;moshi-snapshot=1\x07
```

这是 **OSC（Operating System Command）转义序列**——主机往 stdout 写一段特殊序列，
**终端模拟器截获并解析**，而不显示出来。于是**不需要另开通道**，控制消息和终端输出走同一条流。

> 我们的方案（PRD §2.3）是另开一个 SSH exec channel 跑 `yxi-agent`，**更干净**（结构化、双向、不污染终端流）。
> 但 OSC 这招留作备用：如果哪天想给**没装 `yxi-agent` 的主机**也加一点点信号，
> 让 shell 提示符里 `printf '\033]777;...\007'` 就行，零安装。

### C.4 没有 hook 的 agent 怎么审批：**直接往 TTY 里打字节**

```
CODEX_APPROVE_BYTES
CODEX_DENY_BYTES
```

Codex CLI 没有 Claude Code 那样的 hook API，所以 Moshi 的做法是：
**把"批准/拒绝"对应的原始按键字节直接写进终端**，等于替你在键盘上按了那个键。

**每个 agent 一套事件通道**（原文）：
```
claude-event-approval · claude-event-running · claude-event-done
claude-hook-session-approval · claude-hook-session-running · claude-hook-session-done
codex-event-working · codex-event-done · codex-hook-session-working · codex-hook-session-done
```
注意 Claude 有 `-approval` 通道而 Codex 没有——**因为只有 Claude Code 能真正阻塞住授权**（hook），
Codex 那边只能"看到它在等"然后替你按键。这印证了 PRD §1.5 的判断：
**远程审批的能力上限，取决于 agent 有没有可阻塞的 hook。**

### C.5 Ghostty 终端模块暴露给 JS 的完整 API（jadx 从 Kotlin metadata 还原）

```kotlin
write(reactTag, data)              clear/reset/focus/blur/dispose(reactTag)
requestPaste(reactTag)             fit(reactTag)
scrollToBottom(reactTag)           scrollLines(reactTag, lines)
paste(reactTag, data)              writeAndGetCursorPosition(reactTag, data, promise)
getBuffer(reactTag, scrollback, promise)     getCursorPosition(reactTag, promise)
readScreenRow(reactTag, row, promise)
registerFontFile(filePath, promise)          resolveLoadedFontName(query, promise)
```

**这就是一个移动终端控件该有的接口清单**——我们用 `termux/terminal-view`（PRD §2.2）时，
照着这张表核对功能覆盖即可。注意 `registerFontFile` / `resolveLoadedFontName`：
**动态注册字体**是为 CJK 服务的（§1.8 它按需下载 NotoMonoCJK）。

### C.6 其它零碎

| 发现 | 说明 |
|---|---|
| `~/.moshi/uploads/`（`ABSPATH=$(cd ~/.moshi/uploads && pwd)/`） | 图片上传落在主机这个目录 |
| `--moshi-diff-*` CSS 变量、`window.__moshiDiffFreeUseLimit` | **diff 查看器是个 WebView 网页**，不是原生。免费版有次数限制 |
| `approval_delegation` / `approval_delegation_off` | 有"审批委派"功能 |
| `MOSHI_APPROVAL_CATEGORY` / `MOSHI_APPROVE_ACTION` / `MOSHI_DENY_ACTION` | Android 通知的 category 和两个 action id ——**通知上直接批准就是靠这个** |
| `app.getmoshi.liveactivity.MoshiFcmService` | **安卓侧推送走 FCM**，不是前台服务保活（见下） |
| `MOSHI-XXXX-XXXX-XXXX` | License key 格式 |

### C.7 一个要慎重对待的分歧：**它用 FCM，我们用前台服务**

Moshi 安卓版靠 **FCM**（Firebase）收推送。我们（PRD §6）选前台服务常驻。对比：

| | FCM（Moshi） | 前台服务（我们） |
|---|---|---|
| 被系统杀 | ✅ 不怕，系统级唤醒 | ⚠️ 国产 ROM 会杀 |
| 依赖 | ❌ Google 服务 + Firebase 项目 | ✅ 零依赖 |
| **国内手机没 GMS** | ❌ **直接不可用** | ✅ 照常工作 |
| 内容过谁 | Google + Moshi 云 | **只过你自己的机器** |

**结论：坚持前台服务**。国产 ROM 杀后台的风险，用「电池白名单 + `START_STICKY` + 开机广播 +
`yxi-inbox` 保证事件不丢」来对冲（PLAN Phase 4）——**总比在没有 GMS 的手机上直接失灵强。**

### C.8 复现方法

```bash
pip install --break-system-packages hermes-dec
python3 -c "
from hermes_dec.parsers.hbc_file_parser import HBCReader
r=HBCReader(); r.read_whole_file(open('assets/index.android.bundle','rb'))
open('strings_clean.txt','w').write('\n'.join(str(s).replace(chr(10),'\\n') for s in r.strings))"
# ⚠️ 本仓环境里 `grep` 被包成 ugrep 且带 --ignore-files，会跳过这个文件 —— 用 python 过滤
jadx -d out --no-res --deobf -j 8 base.apk     # Kotlin metadata 里有完整方法签名
```
