# handover · Yxi

## 基础信息
> 🔑 **一句话讲清这个项目**：什么都不装，SSH 本来就能「你去看」——看会话、进去问 Claude、
> 渲染成对话界面、翻文件、传附件，**整个 App 几乎都能用**。
> **`yxi-hook` 只买「主动」两个字**：让手机在 Claude 需要你时**主动响**。
> 不装是**监视器**（你去看它），装了才是**遥控器**（它来找你）。
> `yxi`（agent）纯属省往返，可以完全不装。详见 PRD 附录 H.0。

- **是什么**：手机指挥台 —— 复刻 Moshi（手机开终端、管 tmux、给 Claude Code 下指令和远程批权限），**去掉它的整个云端层**。
- **面向全球用户，但不跑任何后端**：每个用户连自己的服务器（PRD §2.6）。分发走 **GitHub Releases**，不上应用商店。**第一期只做 Android**（iOS 装不了 Release 的 APK，PRD §2.5）。
- **技术栈**：客户端 = **Android 原生 APK**（Kotlin + Compose + Material 3；SSH 用纯 Java 的 `mwiede/jsch`，
  终端用 **`org.connectbot:termlib`**（Compose 原生终端控件，不是 WebView），
  markdown 用 `multiplatform-markdown-renderer-m3`，ed25519 靠 BouncyCastle）。
  服务器端 = **只有一个 `yxi-hook`**（往 `~/.yxi/events.jsonl` 追加写，App `tail -f`）。
  **传输走 SSH，不开任何新端口、不要证书。**
- **部署在哪**：**默认哪台都不用装。** 会话看板、对话渲染、发指令、文件模式全部用现成的
  `tmux` / `~/.claude/projects` / sshd 自带的 SFTP。只有「手机主动响」需要在那台机器上装 `yxi-hook`。
- **开发回路**：本机 `/dev/kvm` 可用、嵌套虚拟化已开 → **AVD 模拟器硬件加速**，`adb install` 迭代（MuMuPlayer 无 Linux 版）。
- **鉴权**：复用 SSH 公钥认证，私钥存 Android Keystore。**不需要 CA 证书 / mTLS / token / Tailscale / 改 ufw**——见 PRD §2.3。手机丢了 = 删一行 `authorized_keys`。
- **怎么跑**：`dev/run.sh`（构建→模拟器→装→起→截图）；测试 `cd android && ./gradlew connectedDebugAndroidTest`（15 条）。
  APK 产物 `Yxi-0.1.0-debug.apk`，手机直接下的地址见下面「APK 分发」。

### SSH 接入（App 连这台机器用）
| 端口 | 用途 |
|---|---|
| **22** | 常规 |
| **8443** | **备用** —— 手机在移动网络下 22 出站常被运营商屏蔽，症状是 App 报「连不上」(TCP 超时) 而服务器侧一切正常。两个端口是同一个 sshd、同一把主机密钥，App 里只改端口号即可 |

⚠️ 端口由 `/etc/systemd/system/ssh.socket.d/yxi-altport.conf` 决定（**socket 激活**），
往 `sshd_config` 写 `Port` 无效且会跟 socket 抢端口把 22 一起搞挂。见 TROUBLESHOOTING #67。

## 进度
- ✅ **已完成**：**Moshi Android 3.10.0 APK 逆向**（`/root/inbox/base.apk`，解包 `/root/inbox/apk/`；Expo/RN + Hermes，字符串表可读 → 挖出会话枚举命令、云端+本地网关接口清单、Inbox SQLite 表结构，见 PRD §1 与附录 A）；[PRD.md](./PRD.md)；[PLAN.md](./PLAN.md)；全部技术前置在本机验证（PLAN §4）
- ✅ **方案已定稿（客户端形态换过一次）**：PWA → **Android 原生 APK**。原因：浏览器强制 CA 证书，走 SSH 就没有这个限制 → 整个证书 / Tailscale / 公网暴露的问题链消失（PRD §2.1）
- ✅ **App 内必须实现 SSH**（`mwiede/jsch`，纯 Java 不用 NDK）。**这是刚需，不是可选**：要能连**任意服务器，包括以后新增的**，在 App 里现加（PRD §2.4）
  > ⚠️ 早期版本一度写成"App 内不实现 SSH，只连自己的服务器"——**那是错的，已纠正**。别再退回那个结论
- ✅ **三个决策全部落定**：①先 Android，iOS 继续用原版 Moshi（装不上自签 App）②不用 Tailscale / CA 证书 ③安卓侧不并行用 Moshi
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
- ✅ **G8 完成**（验收通过）：**三模式切换 + D-Pad**。
  - 验收标准原文「用 D-Pad 在 Claude Code 的权限提示里上下选 + Enter 确认」—— **过了**：
    手机上按 ↓ 再按中央 ⏎，服务器转录里落 `"D-Pad 的两个角默认放哪组键？"="ctrl-c + tab"`。
  - **切换不断连**：连接、SFTP 通道、终端仿真器全部提到 `Workspace` 这一层，
    三个模式只是换画面。实测切到对话再切回来，`tmux attach=1` 全程没断。
  - D-Pad 是**一个手势不是五个按钮**：按下按方位判方向、压住连发、手指推向别处就跟着换。
    两个上角可配置（长按换）。
  - 「对话模式不可用」不是灰着不说话，**点了会告诉你为什么**。
  - ⚠️ 这一程挖出的最阴的一个是 #35：**节流没有尾随刷新**，
    表现是「忙的会话正常、闲的会话永远空白」。
- 🔄 **G9 大部分完成**（三条验收里两条过、一条只能真机验）：
  - ✅ **键盘工具条**：`Ctrl`(粘滞) esc tab ⇧tab ^C ^D ^Z ^L ^R **^B(tmux 前缀)** 方向键
    home/end/pgup/pgdn 和 `| / ~`（这几个符号在手机输入法里要翻两页）。
    **实测 ^C 中断了 `sleep 300`**。粘滞 Ctrl 不碰 termlib 内部 ——
    `onKeyboardInput` 回调本来就在我们手里，下一个字节 `and 0x1f` 即可，**任何输入法都适用**。
  - ✅ **中文显示**：宽字符对齐、日文韩文 emoji 都正常。
  - ✅ **断线重连**：心跳 2s×2 判死 + 看门狗 600ms 轮询 + 退避重连。
    **实测 2.9–3.4 秒恢复**（目标写的是 2 秒，没做到，分解见 TROUBLESHOOTING #41）。
    **「光标位置不丢」是满分** —— tmux 重新 attach 把整屏连回滚一起带回来。
  - ⬜ **中文输入（IME 通路）真机才验得了**：模拟器上 `adb shell input text` 打不了中文，
    而且实测它连 ASCII 都没能进远端 —— 这条只能你在荣耀 Magic7 上试。
  - ⬜ 横竖屏尺寸同步没单独验（模拟器锁竖屏）。
- ✅ **G10 完成**（实测通过，含公网远程机）：**手机主动响**。
  - **服务器侧只有一个文件**：`server/yxi-hook`（~90 行 python）+ `server/install.sh`
    （幂等 / 自动备份 / 写完校验 JSON，坏了自动回滚 / `--uninstall` 一键摘）。
    不占端口、不起守护进程、不用 systemd。
  - hook 只写**值得让手机响的**两种事件（`Stop` / `Notification`）——
    `PreToolUse` 每秒好几次，写进去等于把手机变成骚扰源。
  - App 侧前台服务常驻 SSH 通道 `tail -n 300 -f`。**不走 FCM**（D8）。
  - **补收漏掉的事件**：按「上次看到的时间戳」过滤，比记文件偏移稳
    （hook 超 5 MB 会砍前半段，偏移就废了）。实测：App 没开时写的事件，开了之后收到了。
  - 实测：**本机 + station（公网另一台）两条通道同时盯**；在 station 上写一行事件，
    手机弹出「atf翻译 需要你」；**点通知直达那个会话的对话界面**。
  - 系统低内存杀掉后 `START_STICKY` 15 秒自己回来（实测）。
  - ⚠️ **扛不住 force-stop** —— 而荣耀 MagicOS 的后台管控就是 force-stop，
    且**掐掉之后不会有任何提示**。所以第一次打开铃铛时会弹一个对话框让你去放行后台。
    这不是「优化建议」，是这个功能能不能用的前提。
  - ⬜ **锁屏通知**只能真机验（模拟器没配锁屏）。
- ✅ **G11 完成**（两条验收都过）：**在通知上批权限**。
  - **架构跟 PRD 不一样，是有意改的**：PRD 写的是 hook 阻塞 570 秒等回答、超时输出 `"ask"`。
    那条路上「自动放行」是**一个 if 写错就会发生**的事。
    改成 **hook 只发通知，权限决定完全不经过它** —— 手机上按的按钮是往 TUI 送一个按键，
    跟人在键盘上按是同一条路。于是「自动放行」**在结构上不存在**。
  - 通知按钮上的选项**从屏幕上读**，不预设：权限提示是
    `1. Yes` / `2. Yes, and always allow…` / `3. No` —— 把「拒绝」写死成 2
    等于**永久放行这一类操作**（#47）。读不出来就不给按钮，只能点开去看。
  - **送键前会重新抓一次屏确认**号码和文案没变 —— 从发通知到你按下可能过了几分钟，
    那个提示可能已经换成另一个了。
  - ✅ 验收①：手机通知上点「1. Yes」→ 服务器上 marker 文件出现、提示消失、Claude 继续。
  - ✅ 验收②：`server/test_yxi.py` 三条全过，含端到端的
    `test_approval_fail_closed`（`~/.yxi` 不可写 + 没人回答 → 命令没执行、提示还挂着）。
  - ⚠️ 顺带修了个会要命的：**权限提示的脚注没有 `to navigate`**，
    G6 的解析器原本完全认不出它（#46）—— 最该认出来的一种。
- ✅ **G12 完成**（三样，两样实测、一样真机才验得了）：
  - ✅ **附件**：上传到 `/root/src/tmp/<会话名>/`，编号「图片1/附件1」，
    发送时正文前面带 `[图片1] <绝对路径>` —— **不把内容塞进对话，Claude 自己去 Read**。
    实测：手机传一张截图 → 那边的 Claude 把截图内容描述出来了。
    3 天清理的 `find` 每个参数都是刻意的（路径写死 / `-xdev` / 不跟符号链接 / 删前记日志）。
  - ✅ **用量**：`ccusage blocks --active --json`（schema 抄自本机能跑的 `cc-quota`）。
    会话看板上是详情卡、主机列表上是细线（读缓存，**不为显示用量额外建连接**）。
    **探测不到就整块藏起来** —— 这台机器上没 npm 装不了 ccusage，所以真实状态就是不显示；
    用一个临时 shim 验过「有数据」那条路的渲染，**验完立刻删了**。
  - ⬜ **语音**：接了系统 `RecognizerIntent`。对话模式把识别结果**填进输入框**、
    终端模式**弹确认框**（识别错一个字在服务器上就是另一条命令）。
    模拟器没有语音引擎，**只验了点下去不崩** —— 真正的识别只能你在真机上试。
- ✅ **自更新**（后加的）：`./server/install.sh --publish <apk> <code> <name> [说明]`
  把包和清单摆进 `~/.yxi/`，手机连上就看到横幅、走 **SFTP** 下载。
  **不查 GitHub Release**：仓库私有、API 要 token，而把 token 塞进 APK 等于公开它；
  而且这个 App 除了那条 SSH 之外本来没有任何网络面。
  检测 / 下载（sha256 一致）/ 完整性 / 权限处理都实测过；
  **最后那一下装不上是模拟器图形安装器的问题**（同一个文件 `adb pm install -r` 装得上），
  真机没验过 —— 见 TROUBLESHOOTING #56。
  ⚠️ **以后每次发包 `versionCode` 必须 +1**，手机只比这个数。
- ✅ **G13 完成**（实测通过）：**悬浮排列会话切换**（D18/D17）。
  - 工作区顶部点会话名 `▾` 唤出卡片轮播；卡上是那个会话的**实时屏幕缩略**（`capture-pane`，
    只抓当前页和左右邻居，全抓的话 20 个会话每 5 秒就是 20 次往返）。
  - 视差：邻居缩到 0.86 + 压暗，内容比卡片慢一拍。**系统关了动画就一律不做位移和缩放** ——
    这不是体贴，是无障碍要求。
  - **上滑 = 归档（本地名单，服务器一根毛没动）；杀会话要长按 + 二次确认。**
  - 看板上加了「悬浮」入口，和列表并存。
  - ⭐ **连接改成按 host 记，不按 (host, session) 记**：换会话时
    **SSH 连接数实测全程不变**，终端靠 `tmux switch-client` 切过去（一次往返）。
    之前是整个 Workspace 重建 = 重新握手 + ed25519 认证 + 起登录 shell，模拟器上两三秒。
  - ⚠️ 挖出两个叠在一起、症状完全一样（「滑不动」）的 bug，见 TROUBLESHOOTING #60 #61。
- ✅ **G14 完成**（验收通过）：**底部导航「会话 · 主机 · 设置」+ 设置页**。
  - **只在外层**，进工作区整屏让位。实测：终端里弹软键盘，底部栏不出现、
    键盘工具条正好落在键盘上方（靠 `imePadding()`，见 #62）。
  - 会话页带主机下拉 `dev ▾` —— 换主机不用退出去；不再需要「主机列表→看板」的下钻。
  - 设置页：**版本号（0.3.0 / versionCode 4，从 BuildConfig 读）**、主动检查更新、
    公钥（查看/复制/换一把）、后台放行状态、关于。
  - ⚠️ 更新检查三种结果分清楚了，都实测过：
    有新版本 / **✓ 已是最新（服务器上就是 0.3.0）** / **✗ 没查到：…（读不到）**。
    「没查到」绝不显示成「已是最新」。
- ⬜ **剩下的**：
  - **真机四件事**（模拟器都验不了）：中文输入 · 锁屏通知 · 语音识别 · **自更新最后那一步安装**
  - 横竖屏尺寸同步没单独验（模拟器锁竖屏）
  - 重连 2.9–3.4 秒，目标写的 2 秒 —— 分解见 #41，两条能更快的路都有代价，没走
  - TROUBLESHOOTING #50（LazyColumn 里读 state 加的 item 不出现）**只记了现象和绕法，没查根因**
  - 清理：station 的 `~/.ssh/authorized_keys` 里那行**模拟器的** `yxi@android` 要删
  - 发布前：GitHub token 轮换、仓库拆公开/私有

- ✅ **修好「用户真机连不上」（2026-08-23）**：根因**不在 App**，在 `dev/seed.sh` ——
  它按注释 `yxi@android` 过滤 `authorized_keys`，而 **`KeyManager` 给每台安卓设备
  写的注释都是 `yxi@android`**，模拟器和用户真手机撞了。于是每跑一次开发脚本，
  就把用户手机的公钥从本机和 station 上删一次；症状是他那头「连不上 + 检查更新失败」，
  服务器这头**查什么都正常**（端口通、外部机器 SSH 得通、fail2ban 没封他），
  唯一线索是 sshd 日志里他那把钥匙的指纹**一次都没出现过**。
  修法：模拟器专用标签 `yxi@emulator`，`server/test_yxi.py::test_seed_never_evicts_a_real_phone` 守住。
  同时暴露出 **测试盲区**：seed 里预置的主机一直是 `10.0.2.2`（模拟器→宿主机回环），
  「App 走公网 IP 连这台服务器」这条路一次都没跑过 —— 已在 seed 里补一台真公网 IP 的主机，
  并实测通过（连接 + 检查更新）。见 TROUBLESHOOTING #64 / #65 / #66。

- ✅ **开发者模式（2026-08-23）**：设置页**连点三下版本号 + 口令**（口令只存哈希，
  见 `DevMode.HASH`；明文不进 git）→ 「跑一次诊断」：解析地址 → 连 TCP → SSH 招呼 →
  认证，逐步计时报错，再挨个探同一个 IP 上的 `本机端口/22/8443/8899/443/80`，
  最后给一句结论 + 一键复制。**做它的理由是排查「用户连不上」时服务器侧是瞎的** ——
  包没飞到就等于什么都没发生，而「超时 vs 拒绝」「哪个端口通」「WiFi vs 移动网络」
  这些决定性信息全在手机上。诊断文本不含密码和私钥。见 TROUBLESHOOTING #70。

- ✅ **对话模式补上「此刻」（2026-08-23）**：三件事。
  ① **排队中的输入**（用户在 Claude 忙时打的字）此前**一条都不显示，处理完也不显示** ——
  它们在转录里的类型是 `queue-operation` / `queued_command` 而不是 `user`，
  解析器静默丢了。现在排队时显示成虚线气泡，被处理时转成正常消息。
  ② **状态词**（`✽ Scampering… (4m 48s · ↓ 10.2k tokens)`）—— 这个转录里没有，
  只有屏幕有，用 `tmux capture-pane` 抓（`Live`）。长工具调用时没有它，界面看起来就是卡死。
  ③ **进对话不再一闪一闪跳** —— 历史灌完之前瞬移不做动画。
  见 TROUBLESHOOTING #72 / #73。
- ✅ **会话可置顶**（📌，按主机分开存在手机本地）：置顶的**从原组取出**单独放最上面。
  22 个会话时留在原组只加图标等于没置顶。

- ✅ **对话可读性 + 连接寿命（2026-08-23，0.5.1）**：
  ① **工具卡默认折叠成一行**（`Bash  cat > /tmp/… 完成`），点开才展开。
  一个回合十几条 Bash/Read 会把正文挤没 —— 出错的和「要你拿主意」的两类**不折叠**。
  ② **连接提到 tab 切换之上**（`rememberHostSession`）：切「设置↔会话」不再重连，
  实测来回 8 次新增认证 **0 次**。
  ③ 「后台不受限制」入口修好 —— 原来跳的是「已放行应用列表」，我们还没放行所以找不到自己。
  ④ 状态词压字重不压字号（量过是 12sp，比正文小；显得大是视觉重量）。
  见 TROUBLESHOOTING #74 / #75。

- ✅ **0.5.2（2026-08-23）四个修复**：
  ① **markdown 标题不再是 45sp** —— 库的 M3 默认把 `#`/`##` 映射到 displayLarge/Medium
  （57sp / 45sp，正文才 16sp）。那套字号是给落地页大标题用的，聊天气泡里一个 `##`
  就占半屏。见 `ui/MarkdownStyle.kt`。
  ② **「排队中」不再永久挂着** —— 出队判据从 `remove` 改成「这句话有没有真的作为
  用户消息出现过」。实测真实会话 35 enqueue / 29 remove，剩下 13 条早就处理完了。
  ③ **「终端起不来」的假错误** —— `runCatching` 把 `CancellationException` 也吞了，
  一次正常的取消被写成用户可见的错误且永久留在界面上。同一写法全仓有四处。
  ④ **终端按真实尺寸开** —— 控件量尺寸发生在 shell 建好之前，回调被丢且不再触发，
  tmux 永远停在 80x24 而控件只有 55 列，画面整个是花的。
  见 TROUBLESHOOTING #76 / #77 / #78。

- ✅ **0.5.3（2026-08-23）**：连接失败**自动重试**（指数退避 1s→15s，指纹变了才停）。
  此前失败一次就把「连不上」钉在界面上、再也不会自己清 —— 手机上网络时断时续，
  等于把一次抖动变成一次永久故障。同时修掉「吞掉 CancellationException」的**第五处**
  （前四处写的是 `status =`，这处写 `error =`，按写法 grep 漏了）。
  新增 `app.yxi.ssh.catching {}`：不吞取消的 `runCatching`，
  **凡是「失败要显示给用户」的地方一律用它**。
  诊断报告加一行「界面 已连上 / ✗ 此刻显示：…」—— 报告要报告**被抱怨的那个东西**的状态，
  不是它自己另测一遍的结果。见 TROUBLESHOOTING #79。

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
| `~/.ssh/authorized_keys`（本机 / station / inst2） | 【要改·**共享状态**】`yxi@android` = 用户真手机，`yxi@emulator` = 模拟器。**`dev/seed.sh` 只许动 `yxi@emulator`**，动了另一个就等于把用户踢下线，见 TROUBLESHOOTING #65 |
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
| D23 | **设置页要显示版本号并能主动查更新** | 用户补充。现在的更新检查是**进会话看板时被动跑一次**，没有主动入口，也看不到自己装的是哪一版。<br>⚠️ 三种结果必须分清：有新版本 / 已是最新 / **连不上没查到** —— 把「没查到」显示成「已是最新」是在骗用户。<br>版本号从 `BuildConfig` 读，不写死。 |
| D22 | **底部导航只在「外层」出现：会话 · 主机 · 设置** | 用户拍板（方案 A / 三格）。<br>**进工作区就整屏让位** —— 终端最缺竖向空间，而软键盘弹起时底部栏会和键盘工具条、系统手势条挤成四层。<br>模式切换 `[终端│对话│文件]` **留在顶部不动**：它是「看哪一面」，跟底部栏的「在 app 的哪儿」是两条轴，放一起会打架。<br>「会话」页带主机下拉 → 换主机不用退出去。 |
| D21 | **补上 D18/D17：悬浮排列会话切换**（先做这个，再做底部栏） | 用户拍板。**换会话是最高频也最费劲的动作**（返回→看板→在 20 个里滚→点），比加导航栏收益大。<br>而且它是早就批过的稿子，不用重新决策。<br>⚠️ 连一并要改：`Workspace` 目前按 (host, session) 记连接，改成**按 host 记** —— 同一台机器上换会话就不用重连。 |
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
