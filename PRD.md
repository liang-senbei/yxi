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

#### 灵动岛怎么办

**灵动岛是 iPhone 的叫法**——Moshi 的 `NSSupportsLiveActivities` 写在 `app.config` 的 **ios 段**里，
**它的安卓版没有这功能**。但**安卓厂商各自做了对应物**（用户的荣耀 Magic7 就有「灵动胶囊」）。
三层对应物：

| 层 | 是什么 | 我们 |
|---|---|---|
| ① **前台服务的常驻通知** | 所有安卓都有 | ✅ **本来就要做**，这是基础 |
| ② **Android 16 Live Updates** | 官方对应物：`Notification.ProgressStyle` + `setShortCriticalText` → 状态栏胶囊 + 锁屏抬升 | ✅ **做，当渐进增强**（P2） |
| ③ 厂商自家的（荣耀灵动胶囊 / 小米焦点通知 / OPPO 灵动胶囊 / vivo 原子岛） | 各家私有 | ⚠️ **先不做**——**确实开放第三方**（荣耀侧小鹏汽车 App 已接入，能显示充电/OTA 进度），但走**开发者平台合作接入**，已接入的都是抖音/支付宝/高德/B站这类大厂。<br>对 GitHub 分发的开源小工具大概率走不通，但**不是技术上做不到**——主功能跑通后可再评估 |

**② 的限制要说清楚**：需 **API 36（Android 16）**；且**完整体验（状态栏胶囊 + 锁屏抬升）先在 Pixel 上随季度更新放出**，
其它厂商机器不一定有。所以**只能当渐进增强，不能当核心功能**。

**最佳用法：审批倒计时。** `setRequestPromotedOngoing` 是把**已有的常驻通知**提升成胶囊——
我们前台服务本来就有那条通知，加个属性即可。而 hook 阻塞有个**真实的 570 秒截止时间**（§6），
正好是 `ProgressStyle` 设计出来的场景（外卖/导航倒计时）：

```
🔴 cc-mail 等待授权 · 还剩 8:32
   [批准]  [拒绝]
```

> 技术要求：`compileSdk 36`。但 `minSdk` 仍可定低（26），运行时判版本——
> **新手机吃到好体验，老手机照常能用。**

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
| P0-8b | **⭐ 双模式切换** | 顶部分段控件一键切换「终端 / 对话」。不断连、记住每会话偏好、没 agent 时置灰说明。**附录 D.5** |
| P0-8 | **⭐ Chat View（主界面）** | 把 Claude Code 的输出渲染成原生 App 那样的对话界面，不是裸终端。数据源是转录 jsonl，不刮屏。**完整规格见附录 D** |
| P0-9 | **⭐ 语音输入** | 系统 `SpeechRecognizer` 为主、输入法语音键兜底。⚠️ **命令行模式下必须先确认再发送**（识别错 = 在服务器上跑了没说过的命令）。**附录 E** |
| P0-10 | **⭐ 附件与图片** | 上传到 `/root/src/tmp/<项目>/`，按项目分类、每条消息内编号（图片1/附件1）、**3 天自动清理**。**附录 F** |
| P0-11 | **⭐ 文件浏览与阅读** | 第三个模式。**走 SFTP，不需要 `yxi-agent`**，任何 SSH 主机可用。md 支持「渲染 ⇄ 源码」切换、图片、JSON 折叠树、代码高亮。**只读**。**附录 G** |
| P0-12 | **⭐ D-Pad 方向键盘** | 圆形四向 + 中央 Enter，两个上角可配置，长按连发、按住拖动持续导航。**顺手兜底 AskUserQuestion 的选项风险**。**附录 I** |
| P0-13 | **一键装公钥** | 用密码连上一次 → 一键把 App 公钥 append 进 `~/.ssh/authorized_keys` → 之后免密。相当于 `ssh-copy-id`（§2.4） |

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
| -1 | **文件浏览与阅读** | — | ✅ **做，第三个模式** | **P0** | ⚠️ **Moshi 没有这个功能** —— 用户要求（D14）。走 SFTP 不需要 agent，适用面比对话模式更广。**附录 G** |
| 0 | **连接管理**（加/编辑主机） | 主页 → 连接 | ✅ 做 | **P0** | **必须能现加新服务器**：任意 IP / 端口 / 用户名 / 密码或密钥 + 一键装公钥（§2.4） |
| 1 | **tmux 会话** | 连接 → 选会话 | ✅ 做 | **P0** | 核心。会话枚举命令已从 APK 挖到（§1.2） |
| 2 | **方向键** | 键盘工具栏 | ✅ 做 | **P0** | 手机软键盘没方向键/Esc/Tab/Ctrl，**没它终端等于废** |
| 3 | **CJK 输入** | 设置 → 终端字体 | ✅ 做 | **P0** | 中文是刚需。需内嵌等宽 CJK 字体（Moshi 专门下载 NotoMonoCJK，§1.8） |
| 3.5 | **⭐ Chat View / 聊天式界面** | 会话内 | ✅ **做，且作为主界面** | **P0** | 用户要求（D10）：像原生 Claude App，不是裸终端。**附录 D** |
| 4 | **收件箱** | 主页 | ✅ 做 | **P0** | **这是复刻的核心动机**。表结构已从 APK 挖到（§1.4） |
| 5 | **（审批）** | 收件箱 | ✅ 做 | **P0** | 同上。整个项目最有价值的一块 |
| 6 | **快捷面板** | 设置 → 快捷 | ⚠️ 简版 | **P0** | 做固定的一排（tmux 前缀 + 窗口 1~9），**不做自定义按钮编辑器** |
| 7 | **在终端中滚动** | 任何终端会话 | ✅ 做 | **P0** | xterm.js 自带，白送 |
| 8 | **远程剪贴板** | 任何终端会话 | ✅ 做 | P1 | OSC 52，xterm.js 有现成 addon，几乎白送 |
| 9 | **语音 → 终端** | 设置 → 听写 | ✅ **做** | **P0** | Android `SpeechRecognizer` 系统自带。**Moshi 是自己塞了 NVIDIA Parakeet 端上模型**（§2.2）——我们用系统的就够，省一整个模型 |
| 10 | **最近的目录** | 连接 | ⚠️ 简版 | P1 | 直接读 tmux 会话的 cwd，**不做 Moshi 那套 `moshi-hook cwd-list` 历史库** |
| 11 | **跳转到…** | 终端工具栏 | ⚠️ 待定 | P1 | 先用一阵看用不用得上，别提前造 |
| 12 | **粘贴并标注图像** | 聊天输入框 | ⚠️ **粘贴做，标注不做** | **P0**（粘贴） | 传图 → 存服务器 → 路径 send-keys 进去。**标注（画笔）不做**：成本高、收益低 |
| 13 | **用量** | 主页 | ✅ 做 | P2 | `cc-quota` 已有现成的，包一层就行 |
| 14 | **Diff 查看器** | 终端标题栏 | ⚠️ 待定 | P2 | `git diff` → WebView 渲染。不难，但不急 |
| 15 | **硬件键盘** | 设置 → 键盘 | ⚠️ 白送 | P2 | Android 蓝牙键盘基本自动工作。**不做自定义键位映射** |
| 16 | **终端手势** | 设置 → 手势 | ⚠️ 简版 | P2 | 只做双指滑动切窗口。**不做手势编辑器** |
| 17 | **浏览器预览** | 终端标题栏 | ❌ **不做** | — | 手机上预览服务器的 web 服务。SSH `-L` 端口转发能做，但**用得着的场景太少** |
| 18 | **Live Activity / 灵动岛** | 设置 | ⚠️ **安卓版做**（渐进增强） | P2 | **iPhone 独有**，Moshi 安卓版也没有。<br>安卓对应物 = **Android 16 Live Updates**（`Notification.ProgressStyle`，API 36）。<br>最佳用法：**审批 570 秒倒计时**做成状态栏胶囊，见 §2.7。<br>厂商私有的仿灵动岛（小米/OPPO/vivo）要逐家申请接入，走不通 |
| 19 | **Mosh 连接** | 任何终端会话 | ❌ **不做** | — | 要 UDP + 原生协议实现。**韧性由 tmux 提供**：断线重连 attach 回去，内容一点不丢（Moshi 自己文案也承认："Mosh keeps the live terminal connected, but it does not carry scrollback… tmux keeps the scrollback"） |
| 20 | **主题、字体与图标** | 设置 | ❌ **不做** | — | 一个人用，一套配色够了。**这是最典型的"做产品才需要"的功能**——他要卖给一万个审美不同的人，我们不用 |

**统计**：22 条里（含 1 条 Moshi 没有的）**P0 做 9 条、P1 做 5 条、P2 做 4 条、明确不做 4 条。**

**四条"不做"的共性**：Live Activity 是平台不支持；Mosh 是有更省的替代；
浏览器预览和主题系统是**做产品才需要，自用不需要**。
——这正是复刻能比原版省一大截的地方：**Moshi 的成本里有相当一部分是"卖给别人"的成本。**

---

## 6. 架构

> ⚠️ **本节的服务器侧设计已被 [附录 H](#附录-h--服务器侧到底需要什么推翻前面的过度设计) 修正**：
> `yxi-inbox` 守护进程删掉了（换成一个追加写的文件），`yxi-agent` 降级成可选脚本。
> **唯一必须装的是 `yxi-hook`。** 下面的图保留作为「原本设想」的记录，实施以附录 H 为准。


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

## 附录 B · 开源参考项目（**已实读源码**，2026-08）

> ⚠️ 用户问「那些之前挖掘的 GitHub 项目你参考了吗」——**第一版只是书签清单（名字/许可/star），没读过代码。**
> 下面是真读过之后的结论，**其中两条推翻了第一版的记录**。

### B.1 ⭐ 两条更正

**① `termux/terminal-view` 和 `terminal-emulator` 是 Apache-2.0，不是 GPL-3.0。**

我第一版记成 GPL-3.0，那是**整个 `termux-app` 仓库**的许可。但它的 `LICENSE.md` 里有明确豁免：

> "Terminal Emulator for Android code is used which is released under Apache 2.0 license.
> Check **`terminal-view`** and **`terminal-emulator`**"

→ 这两个模块继承自 `jackpal/Android-Terminal-Emulator`（Apache-2.0）。
**许可顾虑消失，可以放心用。**
而且 `terminal-view/build.gradle` 里有 **`apply plugin: 'maven-publish'`**、`namespace "com.termux.view"`、
`api project(":terminal-emulator")` ——**是按可发布的库模块组织的**，不是耦在 App 里。

规模：`TerminalView.java` **68 KB** · `TerminalRenderer.java` 13 KB ·
`GestureAndScaleRecognizer.java` · `TerminalViewClient.java` · `textselection/`——**成熟且完整**。

**② ConnectBot 不「更老」，它是 Kotlin + Compose + DI。**

我第一版写「成熟但更老」。实际读了它的 `app/build.gradle.kts`：

```
androidx.compose.bom · compose.material3 · navigation.compose
lifecycle.viewmodel.compose · material.icons.extended · hilt 风格的 di/ 目录
```

源码结构：`data/ di/ logging/ service/ transport/ ui/ util/` + `ConnectBotApplication.kt`。
**这是一个现代 Kotlin/Compose 应用**，参考价值比我以为的高得多。

### B.2 ⭐ ConnectBot 的 transport 层——**Phase 1 直接照着这个结构写**

```
transport/
├── AbsTransport.kt          6.8 KB   抽象基类
├── Transport.kt             4.0 KB   接口
├── TransportFactory.kt      4.1 KB   工厂
├── SSH.kt                  60.3 KB   ⭐ SSH 传输全部实现
├── Local.kt                 5.2 KB   本地 shell
├── Telnet.kt               10.3 KB
├── StreamSocket.kt          4.0 KB
└── JumpHostProxyData.kt     2.3 KB   ⭐ 跳板机
```

**两个直接可用的收获：**
1. **`SSH.kt` 60 KB 是我们要写的那部分的参考实现**（Apache-2.0，可直接抄）
2. **`JumpHostProxyData.kt`——它支持跳板机（ProxyJump）**。我们没想到这个，
   但你的 `laptop`（反向隧道）和 `han`（非标端口）说明这类需求是真实的 → **记为 P1**

### B.3 ⭐ SSH 库的选择：**`mwiede/jsch` 保持不变，理由变硬了**

ConnectBot 用的是 **`org.connectbot:sshlib:2.2.48`**（Maven Central，Apache-2.0，Trilead SSH2 分支）
+ **`org.connectbot:termlib:0.1.0`**。

一度考虑改用 sshlib（许可更宽松、Android 专用）。但：

> **代码搜索 `SFTPv3Client` 在 `connectbot/sshlib` 里 0 命中。**
> ConnectBot 本身不做文件传输，Trilead 的 SFTP 客户端很可能在它的分支里被裁掉了。

**而我们的文件模式（附录 G）刚需 SFTP** → **`mwiede/jsch` 胜出**（自带 SFTP）。
> ⚠️ 代码搜索依赖索引，不是铁证。**Phase 1.1 拉下来实际验证一次**再定死。

### B.4 ⭐ Lucarne 的 `agent-sessions`——**比我附录 D.2 想得更周到**

`tuchg/Lucarne`（MIT，Rust）里有个独立 crate **`agent-sessions`**，
专门解决**「读各家 agent 的会话转录」**——**跟我们 Chat View 要解决的是同一个问题**。
它支持 **7 家**：`claude` `codex` `copilot` `cursor` `gemini` `grok` `pi`。

它的 `AGENTS.md` 写了架构纪律，**三条我们该照抄**：

| # | 它的规则（原文要点） | 对我们的意义 |
|---|---|---|
| 1 | **原始层与语义层严格分开**：`providers::<agent>::…` 是 agent 专属的强类型 schema；`agent_session::{Session, Event, Body}` 是共享语义层。**「不要让原始类型为了少写点代码去依赖共享类型」** | 我附录 D.2 把两层压成了一张映射表。**分开更好**：将来加 Codex 不用动渲染层 |
| 2 | **`Unknown` 是兼容兜底，不是正常终点**。「在发明新的公开变体名之前，先扫真实的本地会话库，用观测到的键集和子类型计数确认这个形状真的稳定」；「真实样本里出现 `Unknown` 就在同一次改动里把它提升成强类型变体」 | **Claude Code 会不断加新的 content block 类型**（`thinking` 就是后加的）。渲染器必须优雅降级，**而且要有升级纪律**，否则 Unknown 会烂在那 |
| 3 | **「如果一个稳定的原始字段本来就带 shell 语义，就在原始层把这部分解析掉；不要让下游重新打开 agent 专属的 JSON blob 去捞 `command`、`duration`」**（它有专门的 `bash.rs`） | 正对我们的 **Bash 卡片**（全项目 3177 次，最高频）。`command` / 退出码 / 耗时 应该在**解析层**就抽出来，不是让 UI 层再去翻 `tool_input` |

它还有 `watch/`（文件监视）、`reader.rs`、`parse_selection.rs`——**结构值得整体参考**。

> 💡 一个诚实的对比：**Lucarne 在「读转录」这件事上做得比我的设计细。**
> 它不用 hook（零侵入），所以**必须**把转录解析做到极致；我们有 hook，
> 所以状态判断更准，但**转录解析这块该虚心抄它的分层**。

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

---

## 附录 D · Chat View 规格（**主界面**，不是附加功能）

> 用户要求（D10）：**别只给一个裸终端。要像原生 Claude App 一样**——
> 把 Claude Code 的输出和你的输入重新包装成好看的对话界面。
> 这正是 Moshi 的 Chat View（§1.6），原本排在 P1，现在**提到 P0 并作为默认主界面**。

### D.1 数据从哪来：**读转录，不刮屏**

两条路，只有一条对：

| | 做法 | 判断 |
|---|---|---|
| ❌ | 解析终端输出（刮 TUI 的屏） | ANSI 重绘、spinner 帧、折行——**必然脆**。不做 |
| ✅ | **读 `~/.claude/projects/<项目>/<会话uuid>.jsonl`** | 结构化、权威、格式已实测确认。**Moshi 也是这么做的** |

`yxi-agent` **`tail -f` 那个 jsonl**，解析成结构化事件，经 SSH exec channel 流给 App。
**终端仍是唯一真相，Chat View 只是渲染层**——跟 Moshi 一个思路。

### D.2 渲染映射（基于本机转录实测统计）

实测一个会话：`assistant` 411 条 · `user` 200 条 · content block 里
`tool_use` 176 · `tool_result` 175 · `thinking` 128 · `text` 107。
全项目工具使用 top：`Bash` 3177 · `Edit` 460 · `Read` 419 · `Write` 205 · `Agent` 144 ·
`WebFetch` 109 · `AskUserQuestion` 37 · `ExitPlanMode` 8。

| 转录里的东西 | 渲染成 |
|---|---|
| `text` block（assistant） | **消息气泡**，markdown 渲染（代码块要语法高亮 + 一键复制） |
| `text` block（user） | 右侧消息气泡 |
| `thinking` block | **可折叠的「思考」**，默认收起（128 条/会话，全展开会淹没内容） |
| `tool_use` + 对应 `tool_result` | **工具卡片**，按工具名定制（见 D.3），结果默认折叠 |
| `AskUserQuestion` | ⭐ **可点的选项卡片**——`questions[].options[]` 直接变按钮 |
| `ExitPlanMode` | ⭐ **计划卡片**——`plan` 是 markdown，渲染出来 + 「批准 / 继续讨论」按钮 |
| `TodoWrite` / `TaskCreate` / `TaskUpdate` | **任务清单**，带勾选状态 |
| `attachment` / 图片 | 缩略图，点开大图 |
| `file-history-snapshot` | 不显示（内部用） |
| `ai-title` / `custom-title` | 会话标题 |

### D.3 工具卡片按工具定制（Bash 占了 3177 次，优先做它）

| 工具 | 卡片长什么样 |
|---|---|
| **`Bash`** | 命令用等宽字体 + 语法着色；输出折叠，超长只显示头尾；退出码非 0 标红 |
| **`Edit` / `Write`** | **diff 视图**（`old_string` → `new_string`），红绿高亮；文件名可点 |
| **`Read`** | 文件名 + 行数范围，内容折叠 |
| **`Agent` / `Task`** | 子任务卡片，显示 agent 类型和描述，可展开看子会话 |
| **`WebFetch` / `WebSearch`** | URL / 查询词 + 结果摘要 |
| 其它 | 通用卡片：工具名 + 参数 JSON 折叠 |

### D.4 输入侧：三种，都打回同一个活着的会话

| 交互 | 怎么送回去 |
|---|---|
| 打字发消息 | `tmux send-keys -t <会话> <文本> Enter`（`hub say` 已验证可行） |
| **点 `AskUserQuestion` 的选项** | ⚠️ **需实测**：Claude Code 的 TUI 选择器接受什么按键（数字键？↑↓+Enter？）。<br>这正是 Moshi 对 Codex 用的招（`CODEX_APPROVE_BYTES`，附录 C.4）。<br>备选：查 `Elicitation` / `ElicitationResult` hook 能否程序化应答 |
| 批准权限请求 | 走 hook 阻塞回程（§6），**不经终端** |

### D.5 ⭐ 双模式切换（用户要求 D11）

**两个模式都是一等公民**，顶部一个分段控件一键切换：

```
┌─────────────────────────────┐
│  cc-mail        [终端│对话] │  ← 一键切换
└─────────────────────────────┘
```

| | **命令行模式** | **对话渲染模式** |
|---|---|---|
| 看到什么 | 真终端（`tmux attach`） | 消息气泡 / 工具卡片 / 计划 / 提问 |
| 数据源 | SSH shell channel 的字节流 | `yxi-agent` tail 的转录 jsonl |
| 输入 | 直接打进 pty | 输入框 → `tmux send-keys` |
| 适合 | 跑命令、看实时输出、干真正的终端活 | 看 Claude 干了什么、回它、批计划 |

**切换必须做对的四件事：**

1. **不断连**。两个模式是**同一个活着的会话**的两种渲染，切换只是换 UI，
   SSH 连接和 tmux 会话原样不动。**切过去再切回来，光标位置、滚动位置都不能丢。**
2. **记住每个会话上次用的模式**。你在 `cc-mail` 用对话模式、在 `cc-root` 用终端模式，
   下次进去各自还是那样（Moshi 对 tmux/herdr 也记这个）。
3. **没装 `yxi-agent` 的主机：对话模式置灰**，并说清原因（"这台机没装 yxi-agent"）+ 给安装引导。
   不能只是消失得不明不白。
4. **不是 Claude Code 的会话：不显示对话模式**。会话里跑的是个普通 shell 或 vim，
   渲染成对话毫无意义。靠 `yxi-agent` 探测会话里跑的是什么（Moshi 也做 agent 探测）。

> **默认哪个？** 探测到是 Claude Code 会话 → **默认对话模式**（这是用户想要的主界面）；
> 其余 → 终端模式。用户切过一次就按第 2 条记住。

### D.6 这改变了阶段顺序

**通往「好看的聊天界面」的最短路径上，没有终端打磨这一步：**

```
Phase 1 (SSH + 能出终端)  →  Phase 3 (yxi-agent)  →  【Chat View】  →  终端打磨往后放
```

理由：如果主界面是 Chat View，**终端就退化成「高级/逃生出口」**——
用来连没装 agent 的机器、或者干真正的终端活。它的打磨（键盘工具条、CJK、手势）
仍然要做，但**不必挡在你看到成品之前**。

> ⚠️ 但 Phase 1 的 SSH 层**不能跳**——它是所有东西的传输层，且没装 agent 的主机只能靠它。

### D.7 明确的非目标

- ✗ **不重新实现 agent 协议**。我们不调 Anthropic API，只读转录 + 往终端打字。
  好处：Claude Code 的配置、权限、MCP、skills 原样生效，升级了我们也不用跟。
- ✗ **不做离线编辑 / 不改转录**。只读。

---

## 附录 E · 语音输入规格（P0，用户要求 D12）

### E.1 三条路，按顺序降级

| 优先级 | 方案 | 要谷歌吗 | 要我们跑服务器吗 | 说明 |
|---|---|---|---|---|
| **① 主力** | Android **`SpeechRecognizer`**（系统 API） | ❌ | ❌ | 国产 ROM 有自家实现（讯飞/百度内核），**接口一样**。荣耀 MagicOS 自带语音输入 |
| **② 兜底** | **输入法自带的语音键**（搜狗/百度/讯飞） | ❌ | ❌ | **零工作量**，任何文本框都能用。①不可用时引导用户按输入法上那个麦克风 |
| **③ 可选进阶** | **音频经 SSH 传回用户自己的服务器，跑 `whisper.cpp`** | ❌ | ❌（是用户自己的机器） | 见 E.3 |

> ⚠️ **运行时必须探测 ①**：`SpeechRecognizer.isRecognitionAvailable()`。
> 不可用就自动退到 ②，**别静默失败**。中文识别也要实测——不能假设默认语言是中文。

### E.2 ⚠️ 自动发送必须默认关闭（这是安全问题，不是偏好）

从 Moshi 逆向里挖到 `setAutoSendDictation` / `toasts.autoSend.enabled`——**它有这个开关**。

**为什么重要**：语音识别会出错。在**对话模式**里识别错了，最多是 Claude 答非所问；
但在**命令行模式**里，识别错的文本 + 自动回车 = **直接在你服务器上跑了一条你没说过的命令**。

**规则**：
- **命令行模式：永远先显示识别结果，用户确认才发送。** 不提供自动发送选项。
- **对话模式**：默认也是先显示；自动发送做成可选开关，用户自己开。

### E.3 可选：让用户自己的服务器做转写

架构上很顺——**音频走已经建好的那条 SSH 连接**回到用户自己的机器，
`whisper.cpp` 转写完把文字送回来。

| | 系统 `SpeechRecognizer` | 服务器端 whisper |
|---|---|---|
| 延迟 | 低 | 取决于机器（本机 16 核，small 模型够快） |
| 准确度 | 一般，专业术语/代码差 | **好得多**，尤其中英混杂和技术词 |
| 隐私 | 可能过厂商云 | **只过用户自己的机器** |
| 成本 | 0 | 用户自己装 whisper.cpp |

**对我们这个场景（说的都是命令、路径、技术词）②的准确度优势很实在。**
但**不进 P0**——先用系统 API 跑通，用户嫌不准了再加。

> 对比 Moshi：它塞了 **NVIDIA Parakeet 端上模型**（附录 C），APK 体积和复杂度都上去了。
> 我们不塞模型——**要么用系统的，要么用你自己服务器上的**。

### E.4 两个模式里的入口

- **对话模式**：输入框旁边一个麦克风按钮 → 识别 → 填进输入框 → 你确认后发送
- **命令行模式**：键盘工具条上一个麦克风 → 识别 → **显示待确认条** → 确认才打进终端

---

## 附录 F · 附件与图片暂存区（P0，用户要求 D13）

### F.1 路径规则

```
/root/src/tmp/<项目>/
```

**`<项目>` = tmux 会话名去掉 `cc-` 前缀**。例：`cc-Yxi` → `/root/src/tmp/Yxi/`

已核实：`/root/src` **不是 git 仓库**（不会误提交），磁盘剩 444 G。目录由 `yxi-agent` 首次上传时自动创建。

> 对比 Moshi：它传到 `~/.moshi/uploads/`（逆向所得，附录 C.6）——**平铺、不分项目、不自动清理**。
> 我们这套按项目分 + 定期清理，更好。

### F.2 编号：**每条消息内独立编号**，图片和附件分开数

你在一条提示词里加了 3 张图 2 个文件，App 显示成可点的 chip：

```
[图片1] [图片2] [图片3] [附件1] [附件2]
┌──────────────────────────────────┐
│ 看下 图片1 的报错，对照 附件1 的日志 │
└──────────────────────────────────┘
```

**编号每条消息重置**（你明确要的）。不会歧义，因为**每条消息发出去时都带完整路径映射**（F.3）。

### F.3 发给 Claude 的格式

`tmux send-keys` 实际送出去的是：

```
图片1: /root/src/tmp/Yxi/20260822-143005-img1-screenshot.png
图片2: /root/src/tmp/Yxi/20260822-143005-img2-error.png
附件1: /root/src/tmp/Yxi/20260822-143005-file1-server.log

看下 图片1 的报错，对照 附件1 的日志
```

**头部给路径映射，正文保留你写的自然引用。** 这样 Claude 既知道文件在哪，
也能对上你说的"图片1"。**不用改 Claude Code 任何配置**——它本来就能 `Read` 图片和文件。

> App 里那条消息气泡也要把 chip 显示出来，滚回去还知道「图片1」当时是什么。

### F.4 文件命名：必须清洗

```
<YYYYMMDD-HHMMSS>-<类型><序号>-<清洗后的原名>.<扩展名>
例：20260822-143005-img1-screenshot.png
```

⚠️ **手机上的原文件名带空格、中文、`(1)`、emoji 都很常见**，直接用会在 shell 里出事。
规则：非 `[A-Za-z0-9._-]` 的字符换成 `-`，截断到 40 字符，**扩展名按实际类型判定**（不信任原扩展名）。
原始文件名存进 `.index.json` 供 UI 显示。

### F.5 三天清理

systemd timer，每天跑一次：

```bash
# 只删文件，不删目录；不跟随符号链接；不跨文件系统
find /root/src/tmp -xdev -type f -mtime +3 -print -delete >> /var/log/yxi-tmp-clean.log
find /root/src/tmp -xdev -mindepth 1 -type d -empty -delete
```

**三条安全边界**（自动删除是不可逆操作，不能马虎）：
1. **路径写死 `/root/src/tmp`**，不接受参数、不从配置读——避免任何形式的路径注入
2. **`-xdev` + 不跟随符号链接**——防止有人在暂存区放个软链指向 `/`
3. **删之前 `-print` 记日志**——出事能查

> ⚠️ **后果说清楚**：3 天后引用就失效了。四天前的对话里提到的「图片1」，文件已经没了。
> 这是暂存区的既定契约，**别往这里放需要长期保留的东西**。

### F.6 容量限制

| 限制 | 值 | 为什么 |
|---|---|---|
| 单文件 | **50 MB** | 手机照片约 5 MB；超过多半是视频，而 **Claude 读不了视频** |
| 单项目目录 | **500 MB** | 超了先删最旧的，并提示 |
| 上传前提示 | 大于 10 MB 时确认 | 手机流量 |

### F.7 传输方式

复用**已经建好的那条 SSH 连接**开一个 **SFTP channel**（`jsch` 自带）。
不新开端口、不新建连接、不过任何第三方。

### F.8 P1 增强（先不做）

- 引用**之前消息**的附件（现在只能引用本条消息里的）
- 图片上传前压缩（手机原图常常几 MB，Claude 不需要那么大）
- 从相机直接拍

---

## 附录 G · 文件浏览与阅读（P0，用户要求 D14）

### G.1 它是**第三个模式**

```
┌──────────────────────────────────────┐
│  cc-Yxi        [终端│对话│文件]       │
└──────────────────────────────────────┘
```

**关键性质：文件模式走 SFTP，不需要 `yxi-agent`。** 所以：

| 模式 | 需要 `yxi-agent` 吗 |
|---|---|
| **终端** | ❌ 任何 SSH 主机 |
| **文件** | ❌ **任何 SSH 主机**（SFTP 是 SSH 自带的） |
| 对话 | ✅ 需要 |

→ 连 `station` / `inst2` 这些什么都没装的机器，**也能直接翻文件、读 md、看图**。
这比对话模式的适用面更广，实现也更简单（`jsch` 自带 SFTP，不用写协议）。

**起点 = 该会话的 cwd**（`cc-Yxi` → `/root/src/workspace/Yxi/`），可上下导航、可跳到任意路径。

### G.2 按类型渲染

| 类型 | 怎么显示 |
|---|---|
| **`.md` / `.markdown`** | ⭐ **渲染视图 ⇄ 源码** 一键切换（用户说的「人类易读模式」）。默认渲染视图 |
| **图片** `.png .jpg .webp .gif .svg` | 直接显示，双指缩放、双击放大 |
| **`.json`** | 格式化缩进 + **可折叠树**，长数组折叠显示条数 |
| **`.jsonl`** | 按行折叠，每行单独展开成树（Claude 的转录就是这格式） |
| 代码 / 文本 | 语法高亮 + 行号 + 可切等宽/换行 |
| **`.log`** | 等宽、不换行、可横向滚动；错误行标红 |
| 二进制 / 超大 | 不渲染。只显示元信息（大小、类型、mtime）+ 「用终端打开」按钮 |

### G.3 Markdown 渲染的三个细节（容易漏）

1. ⭐ **相对路径的图片要能解析**。`README.md` 里写 `![](docs/arch.png)`，
   要相对**该文件所在目录**去 SFTP 取那张图并显示。不做这个，带图的文档就是一堆破图标。
2. **代码块要语法高亮**，且**可横向滚动**——手机窄，代码块换行会毁掉可读性。
3. **表格要能横向滚动**，别挤成一团。

**渲染库：[Markwon](https://github.com/noties/Markwon)**（Android 原生、**不用 WebView**、
遵循 commonmark 规范、自带 Prism4j 语法高亮插件）。
> 💡 **同一套渲染器也服务对话模式**（PRD 附录 D.2 里 assistant 的 `text` block 是 markdown）——
> **一个依赖服务两个功能**，别引两套。
> 若 App 走 Compose-first，用 `AndroidView` 包一层，或换 `mikepenz/multiplatform-markdown-renderer`。

### G.4 与附件功能联动

在文件模式看到一个文件 → 「**发给 Claude**」→ 它变成下一条消息里的一个引用
（复用附录 F 的编号机制，但**不用上传**——文件本来就在服务器上，直接引用路径即可）。

反过来：附件暂存区 `/root/src/tmp/<项目>/` 也可以在文件模式里直接翻，看看传上去的到底是什么。

### G.5 边界

| 限制 | 值 | 为什么 |
|---|---|---|
| 文本渲染上限 | **2 MB** | 超了只显示头尾 + 「用终端打开」 |
| 图片显示上限 | **20 MB** | 超了先在服务器端缩，别把手机内存打爆 |
| 目录条目 | 一次 500，分页 | `node_modules` 这种目录能有几万个条目 |

**P0 只读。不做写、改、删、重命名。**
理由：手机上误触的代价太高，而**你要改文件本来就有终端模式**。
真需要了再说（P2），而且要加二次确认。

### G.6 明确不做

- ✗ **在手机上编辑文件**（P0；见上）
- ✗ 文件搜索 / grep（终端模式里 `rg` 更快更好）
- ✗ 版本控制操作（同上）

---

## 附录 H · 服务器侧到底需要什么（**推翻前面的过度设计**）

### H.0 一句话：**hook 只买「主动」两个字**

> 用户第二次追问：「我不就是点进去对应的 tmux 然后问 Claude Code 吗，那为什么要 yxi-hook 或者 agent？」
> ——**这是全项目最该说清楚的一句。**

**什么都不装，你已经能做几乎整个 App：**

| 能力 | 需要装东西吗 |
|---|---|
| 看到所有 tmux 会话和状态 | ❌ |
| 进任意会话，看 Claude 在干嘛 | ❌ |
| 打字问它 | ❌ |
| **对话渲染模式**（好看的界面） | ❌（`tail -f` 转录文件） |
| 翻文件、读 md、看图 | ❌（SFTP） |
| 传附件、语音输入 | ❌ |

因为这些全是「**我去看**」——SSH 本来就能做。

**`yxi-hook` 加的只有一件事：让手机主动响。**

- **不装** → **你去看它**（要打开 App 才知道发生了什么）——它是个**监视器**
- **装了** → **它来找你**（有事手机就响）——它才是**遥控器**

具体场景：

> 你给 `cc-mail` 下了指令，锁屏，去吃饭。
> **没装** —— Claude 跑 30 秒撞上授权请求，然后**站在那儿等**。你一小时后打开 App，发现它等了你一小时。
> **装了** —— 它撞上的那一刻你手机就响。锁屏点一下「批准」，它继续跑。**你饭还没吃完它就干完了。**

> 💡 Moshi 自己的宣传语是「**婴儿监视器之于熟睡的孩子，Moshi 之于你的 AI agent**」（§1.8 附近）。
> 婴儿监视器的全部价值就在**它会叫你**——不然它只是一扇窗。

**两个必须说清的补充：**
1. **授权不是非 hook 不可。** 没装 hook 也能批——attach 进终端按个键就行。
   问题是：① **你不知道它在等** ② 你得在手机上开终端、在 TUI 里操作，而不是锁屏点个按钮。
2. **`yxi`（agent）可以完全不装**，一点功能都不少，只是把多次 SSH 往返打包成一次、**快一点**。

**→ 所以 `yxi-hook` 是按机器可选的**：主力机装（让它叫你），客户的机器不装（当纯 SSH 客户端）。**两种都完整可用。**



> 用户问：「为什么要 yxi-agent，加一个 agent 的作用是什么」——**问对了。**
> 前面 §6 的设计里 `yxi-agent` + `yxi-inbox` 大部分是多余的。这里是修正后的版本，
> **§6 的架构图以本附录为准**。

### H.1 唯一**必须**装的：`yxi-hook`

**因为 Claude Code 只调用注册在 `settings.json` 里的 hook。**
SSH 做不到让 Claude 停下来问你手机——这是全项目唯一没有替代方案的一环。

> ✅ **机制已被本机现成代码验证**：`/root/.claude/hooks/hub-gate.py` 是一个跑了很久的
> `PreToolUse` 闸门，它的注释明确写着：
> 「`--dangerously-skip-permissions`（bypass）模式下 `"ask"` 仍会强制弹窗——
> bypass 只跳过提示，显式 ask 强制的除外。」
> → **我们 fail-closed 到 `"ask"` 在 bypass 模式下依然有效。**

### H.2 `yxi-inbox`（常驻守护）——**删掉**

它的活是「手机不在时接住事件」。**一个追加写的文件就够了**：

```
~/.yxi/events.jsonl      hook 追加写；App 连上就 tail -f
~/.yxi/answers/<id>      App 写；hook 轮询读
```

**没有守护进程、没有 systemd 单元、没有 unix socket、没有监听端口**——
**也就不存在「守护进程挂了」这一整类失败模式**。文件天然持久，`tail -f` 天然流式。

### H.3 `yxi-agent`——**降级成一个脚本，不是守护进程，而且不是必需品**

它原本被安排的活，全是 shell 命令：

| 原本说要 agent 做 | 其实就是 |
|---|---|
| `list` 会话 + 状态 | `tmux list-sessions -F '#{session_name}\|…'` + 读 `~/.cloud-status/*.json` |
| `peek` 预览 | `tmux capture-pane -p -t <会话>` |
| `send` 发指令 | `tmux send-keys` |
| 文件浏览 | **SFTP**（SSH 自带，附录 G） |
| 转录流（Chat View） | `tail -f <transcript_path>` |
| 审批回答 | `printf 'allow' > ~/.yxi/answers/<id>` |

**连我以为需要逻辑的那块也不需要**：原本担心「怎么知道哪个会话对应哪个转录文件」，
但 **hook 的 stdin 里本来就带 `transcript_path`**（官方文档确认，§7）。
hook 把它写进事件行，App 直接读——**零映射逻辑**。

**那还留它做什么？** 只有一个理由：**省往返**——把多条探测打包成一次 SSH 调用、
用带版本号的 marker 分段返回（Moshi 那招，§1.2）。手机网络下往返贵，这个优化实在。

> ⚠️ **但它不是必需品。** 没有它，App 多跑几条命令一样能工作。
> 这个区别很重要：「**装了 agent 才有的功能**」这句话现在只对「省往返」成立，
> **不对「能不能用」成立**。

### H.4 修正后的模式可用性

| 模式 | 需要什么 |
|---|---|
| **终端** | 什么都不要（SSH shell channel） |
| **文件** | 什么都不要（SFTP） |
| **对话（Chat View）** | 只要能读到转录文件即可——`tail -f` 就行。**不需要装任何东西** |
| **事件通知 + 远程审批** | **只需要 `yxi-hook`**（唯一必须装的） |

> 也就是说：**没在某台机器上装任何东西，三个模式全都能用**——只是不会收到主动通知、
> 也不能远程批权限（因为那两个功能的前提是 Claude 主动告诉你，而这只能靠 hook）。

### H.5 修正后的服务器侧文件清单

```
server/
├── yxi-hook          ~80 行 · 挂 settings.json · 唯一必须装的
├── yxi               ~60 行 · 可选 · 打包探测省往返（marker 分段）
└── install.sh        注册 hook、建 ~/.yxi/ 目录

运行期产生：
~/.yxi/events.jsonl   事件流（追加写，App tail -f）
~/.yxi/answers/<id>   审批回答（App 写，hook 读完即删）
```

**从 ~340 行 + 一个 systemd 服务 + 一个 unix socket，变成 ~140 行 + 两个文件路径。**

### H.6 顺带简化的地方

- ~~`/run/yxi.sock`~~ → 不需要了
- ~~`yxi-inbox.service`~~ → 不需要了
- ~~事件限流要在 daemon 里做~~ → hook 自己按类型决定写不写就行
- **审批的 fail-closed 更硬了**：没有守护进程可挂，失败模式只剩「文件没出现」一种，
  超时即 `"ask"`
- **`~/.yxi/events.jsonl` 需要轮转**（别无限长）：hook 每次写前检查，超过 5 MB 就
  截断保留尾部 1000 行。一行代码的事，比守护进程可靠

---

## 附录 I · 方向键盘 D-Pad（P0，用户要求 D16）

> 用户：「设计一个按键，就是方便我上下左右选择的」。
> Moshi 有这个，下面是从 APK 字符串里挖出的它的完整设计（原文），加上我们的改动。

### I.1 Moshi 的 D-Pad（逆向所得，原文）

> **"Open the circular D-Pad with four arrow keys and a central Enter button"**
> 「打开圆形 D-Pad，四个方向键 + 中央 Enter 按钮」

**结构**：圆形 · 四向 · **中央 Enter**。由工具条上一个按钮唤出浮层
（`ToolbarDPadTrigger` → `ToolbarDPadOverlay`，`onDPadTriggerPress`），不是常驻。

**只有两个可配置槽位**——**左上角和右上角**（`dpadTopLeftSlot` / `dpadTopRightSlot`，
"Upper-left/Upper-right D-Pad button"）。不是四个角。

**每个槽位可选的动作**（`TERMINAL_DPAD_SLOT_ACTIONS`）：

| 动作 | 原文 |
|---|---|
| Backspace | "Send Backspace from this D-Pad corner." |
| Ctrl+C（中断） | "Send Ctrl+C from this D-Pad corner." |
| 自定义快捷 | "Send a custom shortcut from this D-Pad corner." |
| 隐藏 | "Hide this D-Pad corner button." |

**可选图标**（`terminalDpad.icons.*`）：`delete` `enter` `history` `interrupt` `keyboard` `paste` `shortcuts`
**位置可调**：`terminalDpadPosition` / `setTerminalDpadPosition`
**长按体系**：`keyboardButtonLongPressAction` · `ctrlButtonLongPressAction` · `tabLongPress` · `onShortcutLongPress`
**另一招**：`spaceBarArrowKeys`——**在空格键上横向滑动移动光标**（iOS 那招）

### I.2 我们的设计

**照抄的**：圆形 · 四向 · **中央 Enter** · 两个上角可配置槽位（Backspace / Ctrl+C / 自定义 / 隐藏） · 位置可拖。

**我们加的三条：**

| # | 加什么 | 为什么 |
|---|---|---|
| 1 | ⭐ **对话模式也能唤出** | Moshi 的是终端专属（`terminal-dpad`）。我们的对话模式里也可能要在 TUI 菜单里选——见 I.3 |
| 2 | ⭐ **按住不放 = 连发** | 500 ms 后每 80 ms 一次。翻历史命令要按 20 次 ↓，一次次点是折磨 |
| 3 | ⭐ **按住拖到方向 = 持续导航** | 不用抬手重按。手指压在中心，往哪边推就往哪边走，回中心即停 |

**半透明**，不挡终端内容；拖动改位置后**记住左右手偏好**。

### I.3 ⭐ 它顺手解掉了 Phase 3 的那个风险

PLAN Phase 3 标了一个风险：**「点 `AskUserQuestion` 的选项时，要往 TUI 送什么按键？没实测，通不了就只能做成只读」**。

**Claude Code 的 TUI 菜单（权限提示、计划批准、AskUserQuestion）本来就是 ↑↓ 选、Enter 确认。**
所以 D-Pad 是这个风险的**兜底**：

- **理想情况**：能把「点第 2 个选项」映射成确定的按键序列 → 卡片按钮直接可点
- **兜底**：映射不可靠 → 卡片显示成只读，**你用 D-Pad 上下选 + 中央 Enter 确认**

→ **无论哪种情况都能用**，风险从「可能做不了」降成「可能不够优雅」。

### I.4 边界

- ✗ **不做四个角**（Moshi 也只做两个上角）——下面两个角在拇指自然握持位之外，够不着
- ✗ **不做自定义手势编辑器**（PRD §5.5 第 16 条已定简版）
- ⚠️ `spaceBarArrowKeys`（空格键滑动移光标）**记为 P2**——很妙，但它依赖软键盘本身，
  国内输入法行为不一，先不碰
