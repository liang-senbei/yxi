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
- ✅ **G1 完成**（2026-08-22）：Gradle 工程建好，debug APK 编出并在模拟器跑通，M3 深色主题生效。
  `android/`（AGP 9.3.1 · Kotlin 2.4.10 · Gradle 9.7.1 · compose-bom 2026.08.00 · **compileSdk/targetSdk 37** · minSdk 26）。
  配色写在 `android/app/src/main/kotlin/app/yxi/ui/theme/Color.kt` —— **改配色只改这一个文件**。
  产物 `Yxi-0.1.0-debug.apk`（11 MB）。踩的 3 个坑见 TROUBLESHOOTING #9–#11。
- ✅ **G2 基本完成**：**真终端跑起来了** —— `org.connectbot:termlib`（Compose 原生终端控件）
  接上 SSH shell channel，tmux attach 成功、**彩色输出正常**、连接稳定、URL 自动检测。
  ⬜ 剩：**IME 通路**（软键盘打字）——归到 G9 终端打磨一起做；真机上手指点才是真验证。
  ⚠️ 这一段挖出 5 个坑，其中 **TROUBLESHOOTING #16（jsch 写包路径非线程安全）是全项目最阴的一个**。
- ✅ **G3 完成**（实测通过）：App 内生成 ed25519 密钥（Keystore 加密保存）· 主机列表与加主机 UI
  （任意 IP / **任意端口** / 用户名 / 密码或密钥）· 一键装公钥 · **`known_hosts` 指纹校验**。
  验收全过：① 指纹与服务器 `ssh-keygen -lf` 一致 ② 二次连接不再询问
  ③ **篡改指纹后直接拒绝，不给"仍然连接"的口子** ④ **连上真实远程机 `station`（公网、只装了公钥）**。
  ⚠️ 挖出**两个安全漏洞**：TROUBLESHOOTING #21（双重编码导致 CHANGED 永远检测不到）
  和 #22（jsch 在 CHANGED 时也会问，点一下就能绕过）。**两个都是「专门测反向用例」才发现的。**
- ✅ **G4 完成**（实测通过）：会话看板三段分组（等你/干活中/已完成/空闲）+ 不进终端给**任意**会话发消息。
  一次 SSH 往返拿全部（带版本号的 marker 分段，抄 Moshi）。**服务器上不用装任何东西** ——
  `tmux list-sessions` 和 `~/.cloud-status` 都是现成的。
  实测：本机 16 个会话、station 3 个会话（远程、公网）都正确分组；
  长按会话发 `touch /tmp/yxi-g4-sent`，服务器上文件出现、tmux 有回显。
- ✅ **G5 完成**（实测通过）：**对话渲染模式**——读 `~/.claude/projects` 下的转录 JSONL（`tail -n N -f`），
  **不刮屏**。消息气泡 / 思考默认折叠 / markdown 渲染（`com.mikepenz:multiplatform-markdown-renderer-m3`，
  Compose 原生，不用 AndroidView 包 Markwon）/ 工具卡片（Bash 铜色 · Edit/Write 青色 · 带完成状态）。
  输入框走 `tmux send-keys` 打进活着的会话 —— **不重新实现 agent 协议**，
  所以 Claude Code 的配置、权限、MCP、skills 原样生效。
  解析分层抄 Lucarne 的 `agent-sessions`：原始层与语义层分开，`Unknown` 是兜底不是终点。
- ✅ **四个安全/正确性问题全部收口**（G1–G5 一程挖出来的）：
  - jsch 的 Session 写包路径**不是线程安全**的 → `Shell.write/resize` 上 `Mutex`（#16）
  - `HostKey.getKey()` 已经是 base64，重复编码让指纹校验失效（#21）
  - `StrictHostKeyChecking=ask` 在 **CHANGED 时也弹窗**，用户点一下就连上 → 短路直接拒（#22）
  - 共用连接件漏掉「装公钥」这个调用点 → 认证覆盖做成 `connect(auth)` 参数，
    **`ui/` 和 `term/` 下再没有裸 `SshSession(`**（#24 / #25）
  - `KnownHostsTest`（5 条仪器测试）钉住这几条分支，且**已用变异测试确认断言会红**（#26）
  - 错误文案统一走 `Connector.explain()`：`UnknownHostException` 会直接告诉用户
    「这栏要填 IP 或域名，SSH 别名在手机上不解析」（#27）
- ✅ **公钥界面**：点整块复制到剪贴板 + 「换一把」（带不可逆后果说明的确认框）。
- 📦 **APK 分发**：`Yxi-0.1.0-debug.apk`（33 MB）。手机直接下：
  批注服务 `yxi-review` 的 token 路径下加了 `/apk`（没 token 返回 404）。
  URL 里的 token 见 `design/review/.token`（**gitignore，不写进文档**）。
  到笔电的反向隧道会断，所以装包不该依赖那条链路。
- ✅ **G6 完成**（实测通过）：**工具卡片按工具定制 + 点选项**。
  - 卡片：Bash（命令横滚不折行 / stdout·stderr 分开 / `Exit code N` 提取）、Edit（真 diff，
    走 `toolUseResult.structuredPatch`）、Write（新建 vs 覆盖）、Read（行数 / 图片尺寸）、
    Agent（同步 vs 后台）、AskUserQuestion（问题 + 你选了啥）、ExitPlanMode（计划 markdown）。
  - **点选项**：手机上点第 2 项 → 服务器转录里落 `"先做哪一块？"="三模式切换"`。**闭环实测通过。**
  - ⚠️ **关键发现**：待答的 `tool_use` **不落盘** —— Claude Code 要等工具跑完才写进 JSONL。
    所以「此刻在等你」只能抓屏幕（`tmux capture-pane`）。**转录是权威的历史，屏幕是唯一的「此刻」。**
  - 按键协议全部实测：单选送数字即确认；多选送数字是勾选、`Right`+`1` 才提交；ExitPlanMode 同一套。
- ✅ **G7 完成**（验收通过）：**文件模式**。走 SFTP，**服务器上不装任何东西**。
  - 验收标准原文是「在没装任何东西的 station 上打开一个带图的 README.md，图片能显示出来
    （相对路径解析对了）」—— **过了**：`docs/shot.png` 这个相对路径的图在手机上显示出来了。
    靠的是库自带的 `ImageTransformer` 钩子（`transform(link)` 拿到的就是 md 源码里那个原始字符串，
    库不做任何 URL 拼接），相对路径由 `Paths.resolve` 按 md 所在目录解析，有测试盯着。
  - 目录树（目录在前、符号链接解引用、大小）、面包屑跳转、**直接输入路径 + 最近 8 个目录**、
    md「阅读⇄源码」、图片、JSON 折叠树（默认只展开第一层）、极简语法高亮（注释/字符串/数字/关键字）。
  - **只读**，不做写删。**收藏没做**（要落盘，等有真需求）。
  - ⚠️ 遗留清理项：测试期间把**模拟器的**公钥装进了 station 的 `~/.ssh/authorized_keys`
    （`yxi@android` 那行）。G8 之后要删掉 —— 那不是用户手机的钥匙。
    测试样本留在 `station:/tmp/yxi-g7/`。
- ⬜ **待办**：G8（三模式切换 + D-Pad）
  → G9（终端打磨：**软键盘 IME 通路真机才验得了**）
  → G10（手机主动响）→ G11（锁屏批权限）→ G12（附件+语音+用量）。清单见 [GOALS.md](./GOALS.md)

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

**`http://<公网IP>:8899/<token>/`** —— **可交互原型**（悬浮排列真能滑、带视差；模式切换真能跳；思考条能展开；滚动入场动画）+ 手机上点任意位置钉批注，落 `design/review/pins.json`，
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
| D20 | **SSH 密钥用 ed25519，靠 BouncyCastle 支撑** | Android 的 JCA 没有 `Ed25519` 签名算法，jsch 认证必失败。<br>试过 `net.i2p.crypto:eddsa`（算法名对不上，无效）和退回 ECDSA（可行但没必要）。<br>**注册 BouncyCastle 即解决**，代价 APK +3 MB。TROUBLESHOOTING #12 |
| D19 | **用量显示，按服务器关联** | 数据源 = `ccusage`（`remote-dev-station/bin/cc-quota` 已在用）读本机 `~/.claude`，**天然按服务器分，零关联工作**。转录里每条 assistant 消息自带完整 `usage` + `model` → **本地算钱，不调 API**。<br>两层：主机列表紧凑条 / 会话看板详情卡。中转站余额记 P2（token 留服务器，不进 App）。PRD 附录 K |
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
