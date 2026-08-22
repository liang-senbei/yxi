# handover · Yxi

## 基础信息
> 🔑 **一句话讲清这个项目**：什么都不装，SSH 本来就能「你去看」——看会话、进去问 Claude、
> 渲染成对话界面、翻文件、传附件，**整个 App 几乎都能用**。
> **`yxi-hook` 只买「主动」两个字**：让手机在 Claude 需要你时**主动响**。
> 不装是**监视器**（你去看它），装了才是**遥控器**（它来找你）。
> `yxi`（agent）纯属省往返，可以完全不装。详见 PRD 附录 H.0。

- **是什么**：手机指挥台 —— 复刻 Moshi（手机开终端、管 tmux、给 Claude Code 下指令和远程批权限），**去掉它的整个云端层**。
- **面向全球用户，但不跑任何后端**：每个用户连自己的服务器（PRD §2.6）。分发走 **GitHub Releases**，不上应用商店。**第一期只做 Android**（iOS 装不了 Release 的 APK，PRD §2.5）。
- **技术栈**：客户端 = **Android 原生 APK**（Kotlin ~900 行，SSH 用纯 Java 的 `mwiede/jsch`，终端用 WebView + xterm.js）；服务器 = `yxi-agent`（**不监听端口**，由 SSH exec channel 拉起）+ `yxi-inbox`（只听 unix socket）+ `yxi-hook`。**传输走 SSH，不开任何新端口、不要证书。**
- **部署在哪**：`yxi-agent` 装在任何想要完整功能的机器上（本机 / `station` / `inst2` / …）；没装的机器 App 也能当普通 SSH 终端连。**不占用任何网络端口。**
- **开发回路**：本机 `/dev/kvm` 可用、嵌套虚拟化已开 → **AVD 模拟器硬件加速**，`adb install` 迭代（MuMuPlayer 无 Linux 版）。
- **鉴权**：复用 SSH 公钥认证，私钥存 Android Keystore。**不需要 CA 证书 / mTLS / token / Tailscale / 改 ufw**——见 PRD §2.3。手机丢了 = 删一行 `authorized_keys`。
- **怎么跑**：尚未开工，见 [PLAN.md](./PLAN.md) Phase 0。

## 进度
- ✅ **已完成**：**Moshi Android 3.10.0 APK 逆向**（`/root/inbox/base.apk`，解包 `/root/inbox/apk/`；Expo/RN + Hermes，字符串表可读 → 挖出会话枚举命令、云端+本地网关接口清单、Inbox SQLite 表结构，见 PRD §1 与附录 A）；[PRD.md](./PRD.md)；[PLAN.md](./PLAN.md)；全部技术前置在本机验证（PLAN §4）
- ✅ **方案已定稿（客户端形态换过一次）**：PWA → **Android 原生 APK**。原因：浏览器强制 CA 证书，走 SSH 就没有这个限制 → 整个证书 / Tailscale / 公网暴露的问题链消失（PRD §2.1）
- ✅ **App 内必须实现 SSH**（`mwiede/jsch`，纯 Java 不用 NDK）。**这是刚需，不是可选**：要能连**任意服务器，包括以后新增的**，在 App 里现加（PRD §2.4）
  > ⚠️ 早期版本一度写成"App 内不实现 SSH，只连自己的服务器"——**那是错的，已纠正**。别再退回那个结论
- ✅ **三个决策全部落定**：①先 Android，iOS 继续用原版 Moshi（装不上自签 App）②不用 Tailscale / CA 证书 ③安卓侧不并行用 Moshi
- ⬜ **无阻塞项**，可直接开工 Phase 0
- ⬜ **待办**：Phase 0（Android 工具链 + 空壳 APK + AVD，**最大未知，先做**）→ 1（SSH 层+多主机+终端，2 天，**到这已可替代现有 SSH App**）→ 2（终端打磨：中文/方向键/重连）→ 3（yxi-agent + 会话看板）→ 4（事件+通知）→ 5（远程审批）→ 6（P1）。**P0 约 6.5 天**

## 读写信息在哪
| 路径 | 性质 |
|---|---|
| `~/.cloud-status/<会话>.json` | 【只读源】`cc-state` 写的会话状态，Phase 1 的数据源 |
| `~/.claude/projects/<项目>/<uuid>.jsonl` | 【只读源】Claude transcript，Phase 5 Chat View 的数据源 |
| `~/.claude/settings.json` | 【要改】Phase 3/4 往里注册 `yxi-hook` |
| `/root/.local/bin/{cc-state,hub,cc-quota}` | 【只读源】复用的现成件 |
| `~/.yxi/events.jsonl` | 【本项目自有】事件流，hook 追加写、App `tail -f` 读。超 5 MB 自截断 |
| `~/.yxi/answers/<id>` | 【本项目自有】审批回答，App 写、hook 读完即删 |
| `/root/src/tmp/<项目>/` | 【本项目自有·**会被自动删**】手机上传的附件暂存区，**保留 3 天**。`/root/src` 不是 git 仓库，安全 |
| Android Keystore 里的 SSH 私钥 | 【App 内·硬件保护】导不出来；撤销 = 服务器删 `authorized_keys` 一行 |
| `/root/inbox/base.apk`、`/root/inbox/apk/` | 【参考】原版 Moshi Android 3.10.0 及其解包，逆向证据来源 |

## 界面视觉稿

十一张安卓界面稿在 **[claude.ai/code/artifact/e2546d9f-3fb1-44b1-92d5-18736ade8d1e](https://claude.ai/code/artifact/e2546d9f-3fb1-44b1-92d5-18736ade8d1e)**（私有）。
源文件在 `design/*.dc.html` + `design/canvas.json`，**改动要改源文件再重新生成**，成品页 `yxi-app-design.html` 已 gitignore。

**视觉方向**（我定的，用户认可）：暖色深底 + 终端气质。`Space Grotesk`（界面）配 `JetBrains Mono`（代码/路径/时间）。
两个强调色同亮度同彩度、只变色相：铜色 `#e08b57`（主操作、用户消息）、青色 `#35b1a1`（干活中、Edit）。
**琥珀 `#d5a244` 专留给「等你」**——全 app 只有需要用户动手时才出现这个色。
**没有克隆 Claude 的品牌**，Yxi 有自己的身份。

十一张：会话看板 · 对话模式（主界面）· 交互卡片（AskUserQuestion / ExitPlanMode）· 终端模式+键盘工具条 · **文件模式** · **Markdown 阅读视图** · **D-Pad 方向键盘** · **悬浮排列会话切换** · 附件与语音三态 · 锁屏审批 · 主机管理。

## 视觉稿批注页（评审用）

**`http://<公网IP>:8899/<token>/`** —— 手机上点任意位置钉批注，落 `design/review/pins.json`，
**Claude 直接读这个文件**就知道是哪张稿、哪个坐标、什么问题，不用用户描述位置。

- `design/review/serve.py` —— stdlib http.server，只读画板 + 一个写 pins 的接口
- `design/review/build.py` —— 把 `design/*.dc.html` 的画板抽出来拼成单页，
  ⚠️ **各文件的 `<style>` 必须作用域隔离**（Terminal 和 DPad 都定义了 `.k`，不隔离会互相覆盖）
- systemd `yxi-review.service`（开机自起）· ufw 放行 8899
- ⚠️ **token 在 `design/review/.token`，已 gitignore**。页面无敏感内容、不执行任何东西、
  路径穿越和越界 POST 都返回 404

**改完稿要重新发布 Artifact，批注页会自动跟着更新**（它每次请求都重新抽取源文件）。

## 视觉方向：**Material 3 深色**（已定）

**M3 的核心是用面的明度分层代替描边**——全部 1px 边框已去掉：
页面 `#16130f` → 卡片 `#1e1b17` → 控件 `#221f1b` → 抬起 `#2d2925` → 最高 `#383430`

圆角 22~28px、控件一律药丸 100px、留白 18px、块间距 22px、正文 15px。
强调色：铜 `#ffb787` · 青 `#8fd8c6` · **琥珀 `#ffc46b`（等你，全 app 只在需要你动手时出现）**。

**接受的代价**：同屏信息量比原方案少约三分之一 → 所以**会话看板保留紧凑列表视图**，
悬浮排列是另一种视图而非替代。

改样式请改 `design/*.dc.html` 源文件（`design/apply_m3.py` 是当初批量转换的脚本，留档）。

## 决策记录（用户拍板过的，按时间倒序 —— 改动前先看这里，别推翻已定的）

| # | 决定 | 理由 / 出处 |
|---|---|---|
| D18 | **悬浮排列的会话切换** | 像手机后台：卡片轮播 + `capture-pane` 实时缩略预览。与列表视图并存（`[列表│悬浮]`）。<br>⚠️ **上滑 = 归档，不杀 tmux 会话**（不可逆操作绝不能是滑动手势；杀会话要长按+二次确认）。PRD 附录 J.3 |
| D17 | **滑动切卡的视差过渡** | 三层不同速度：焦点卡 1.0x / 邻居 0.86x+缩放+压暗 / 背景 0.3x。`ViewPager2` + 自定义 `PageTransformer`，无额外依赖。<br>⚠️ **必须尊重系统「移除动画」设置**——对前庭障碍用户视差会引发不适。PRD 附录 J.2 |
| D16b | **视觉方向定为 Material 3 深色** | 用户说想学 Gemini。Gemini 好看是因为它是 M3 Expressive 的样板实现，而 M3 是 Google 公开给第三方用的设计系统 → 学 M3 正当，**未克隆 Gemini 界面**。PRD 附录 J.1 |
| D16 | **D-Pad 方向键盘** | 圆形四向 + 中央 Enter，两个上角可配置槽位（抄 Moshi 逆向所得）。我们加：对话模式也能唤出、长按连发、按住拖动持续导航。<br>⭐ **顺手兜底 Phase 3 的 AskUserQuestion 选项风险**——TUI 菜单本来就是 ↑↓+Enter。PRD 附录 I |
| D15 | **推翻服务器侧的过度设计**（用户质疑「为什么要 agent」） | **唯一必须装的是 `yxi-hook`**（Claude Code 只调 settings.json 里的 hook，SSH 替代不了）。<br>`yxi-inbox` 守护删掉 → 换成追加写的 `~/.yxi/events.jsonl` + `tail -f`。<br>`yxi-agent` 降级成**可选脚本**，只为省往返，不是能不能用的前提。<br>服务器侧 ~340 行 + systemd + unix socket → **~140 行 + 两个文件路径**。PRD 附录 H |
| D14 | **文件浏览与阅读**，作为第三个模式 | `[终端│对话│文件]`。**走 SFTP → 不需要 `yxi-agent`**，任何 SSH 主机可用。md 支持「渲染 ⇄ 源码」切换（用户说的「人类易读模式」）、图片、JSON 折叠树、代码高亮。**P0 只读**。<br>渲染库 Markwon，**与对话模式共用一套**。PRD 附录 G |
| D13 | **附件/图片暂存区** | 传到 `/root/src/tmp/<项目>/`（项目 = tmux 会话名去 `cc-` 前缀，如 `cc-Yxi`→`tmp/Yxi`）。每条消息内编号「图片1/附件1」方便引用，**3 天自动清理**。PRD 附录 F |
| D12 | **语音输入提为 P0** | 系统 `SpeechRecognizer` 为主 + 输入法兜底。⚠️ **命令行模式下自动发送必须禁用**——识别错会直接在服务器上执行。PRD 附录 E |
| D11 | **双模式切换**：命令行模式 / 对话渲染模式，都是一等公民 | 顶部分段控件。切换不断连、记住每会话偏好、没 `yxi-agent` 时置灰并说明。PRD 附录 D.5 |
| D10 | **Chat View 提为 P0 主界面** | 用户要求「像原生 Claude App 一样，不是裸终端」。数据源 = 转录 jsonl（不刮屏）。**改变阶段顺序**：终端打磨往后放。PRD 附录 D |
| D9 | **灵动胶囊先不做**，但**不是做不了** | 荣耀确实开放第三方接入（小鹏 App 已接），但走开发者平台合作，已接入的都是大厂。主功能跑通后可再试 |
| D8 | **推送用前台服务，不用 FCM** | FCM 会强制我们长期跑中转服务器；且用户主力机 **荣耀 Magic7** 的 GMS 默认关闭、需国际网络才可用 → FCM 不可靠。PRD §2.7 |
| D7 | **只做 Android，iOS 第一期不做** | 苹果不允许从 GitHub Release 安装，与 D6 本质冲突。PRD §2.5 |
| D6 | **分发走 GitHub Releases**，不上应用商店 | 省掉审核 / 隐私政策 / 合规追赶 |
| D5 | **面向全球用户，但不跑任何后端** | 每个用户连自己的服务器。需补界面多语言（中/英）。PRD §2.6 |
| D4 | **SSH 客户端不砍**，多主机提到 P0 | 要能连 `station`/`inst2`/… **以及以后才有的新服务器**。<br>⚠️ 早期一度错写成「App 内不实现 SSH」，**已纠正，别再退回**。PRD §2.4 |
| D3 | **传输走 SSH**，不要 CA 证书 / mTLS / 新监听端口 | SSH 自带双向认证。PRD §2.3 |
| D2 | **客户端做 Android 原生 APK**，不做 PWA | 浏览器强制 CA 证书；原生走 SSH 就没这限制。PRD §2.1 |
| D1 | **不并行用原版 Moshi**（安卓侧） | 避免两套 hook 抢 `PermissionRequest` |

> **纪律**：用户在对话里拍的每个板，**当场追加到这张表**，再去改 PRD/PLAN 对应章节。
> 只改章节不记这里 → 三个月后没人知道「为什么当初这么定」。

## 用户环境（影响技术选型）

- **手机：荣耀 Magic7（MagicOS）** —— 项目的目标设备和主测试机
  - GMS 默认关闭、需手动开且要国际网络 → **FCM 不可靠**（D8 的直接依据）
  - MagicOS 后台管控严（自启动 / 关联启动 / 后台活动都要手动放行）
    → **前台服务保活的最严苛测试场**。Phase 4 就在这台机上验，别用宽松环境自欺
  - 有**灵动胶囊**（D9）

## 开源参照（PRD 附录 B 有全表）
> **已实读源码**（第一版只是书签清单）。完整结论见 PRD 附录 B，两条更正：
- ⭐ **`termux/terminal-view` + `terminal-emulator` 是 Apache-2.0，不是 GPL-3.0**（`LICENSE.md` 里有豁免，继承自 `jackpal/Android-Terminal-Emulator`）→ **许可顾虑消失**。而且带 `maven-publish`，是按可发布库模块组织的
- ⭐ **ConnectBot 不「更老」**，是 Kotlin + Compose + DI（`compose.bom` / `material3` / `navigation.compose`，`data/ di/ service/ transport/ ui/`）
- **Phase 1 照着 ConnectBot 的 `transport/` 写**：`AbsTransport.kt` / `TransportFactory.kt` / **`SSH.kt` 60 KB**（Apache-2.0 可直接抄）。它还有 `JumpHostProxyData.kt` **跳板机**支持 → 我们记为 P1
- **SSH 库仍选 `mwiede/jsch`**，理由变硬：`org.connectbot:sshlib` 搜不到 `SFTPv3Client`，而我们的文件模式刚需 SFTP（Phase 1.1 实测确认）
- ⭐ **`tuchg/Lucarne` 的 `agent-sessions` crate 比我们的 Chat View 设计更周到**（支持 claude/codex/copilot/cursor/gemini/grok/pi 七家）：原始层与语义层严格分开、`Unknown` 有升级纪律、shell 语义在解析层就抽出来。**三条都该抄**，见 PRD 附录 B.4

## GitHub 耦合
- 仓库：**`liang-senbei/yxi`（私有）**。⚠️ `/root/src/CLAUDE.md`（含明文密码，权限 600）**在父目录、不在本仓**，不会被提交。
- **深耦合 `remote-dev-station`**（`/root/src/workspace/remote-dev-station`）：复用它的 `bin/cc-state`、`hub/`、`bin/cloud-sesslist`、`bin/cc-quota`；它的 `phone/README.md` 是**原版 Moshi** 的配置 runbook（本项目是它的替代品，不是补充）。
- 端口需避开 `Anthropic-Inspector`（80/443/7800）。

## 已知的机器级敞口（与本项目无关，但更要紧）
本机 sshd 同时开着 root 登录和密码认证，公网 22 端口每天被僵尸网络爆破数千次
（`journalctl` / `auth.log` 里可查，前十来源合计三千余次失败）。
用户手机端已在用**公钥**认证 → **关掉密码认证不影响使用**。已告知用户，由其决定。
> 具体主机地址见 `/root/src/CLAUDE.md`（不入库）。
