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
- 📦 **APK 分发**：手机上「检查更新」走的是**公网 HTTP**，不是这台开发机。
  - **公网下载点在 hk13（服务集群机 `64.90.25.56`）**，nginx **:8899**，
    包在 `/var/www/yxi/<token>/Yxi.apk`。token 见 `/root/.yxi/dl-token`（600，**仓库外**）。
    `dl.keuury.com` 的 vhost 已经备好，只差 A 记录（CF token 有 IP 白名单，本机加不了）。
  - ⚠️ **hk13 上还跑着别人的东西**（human_register / api.omggrow.com / inbox.omggrow.com …），
    动它的 nginx 前先读 `sites-enabled`，**绝不能加 `default_server`**，见 TROUBLESHOOTING #90。
  - **`server/install.sh --publish` 会自动推过去并比对 sha256**，推不动会吼。
    别再手动 scp —— 手动那次就出过「本地新、公网旧」（#69）。
  - 另一条路是 **App 内自更新走 SFTP** 读开发机的 `~/.yxi/Yxi.apk` + `latest.json`，
    不过公网、不用 token，防火墙后面照样能用。两条路的包由 `--publish` 保证是同一个。
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

- ✅ **0.5.4**：对话加「↓ 一键到底部」（只在没在底部时出现）；进对话**精确停在最后一条**；
  会话页连不上时给一个手动「重连」按钮。滚到底这件事踩了四个坑，
  真正的元凶是「定位发生在历史还在灌、布局还在变的时候」——
  把 `settled` 也当成 key、灌完再定位一次才收敛。见 TROUBLESHOOTING #80。

- ✅ **0.5.5 —— 连接自愈**：用户报「连上了，返回来又连不上，只能重启 App」。三个洞叠在一起：
  心跳 `2s×2=4s` 判死对手机太狠（终端要这么灵敏，常驻那条不该）；
  `rememberHostSession` 连上就 `return`、之后死了没人管；
  看板刷新失败只写「刷新失败」而 `ssh` 仍非 null，连重连入口都不出现。
  现在：心跳做成构造参数（终端 2s / 常驻 15s×2），连上之后 `while (isAlive) delay(3s)` 守着，
  掉了自动重连。**实测从服务器 `kill -9` 掉那条 sshd，37 秒后自己回来，界面全程没报错。**
  下拉刷新兼作手动重连（没连上=重连，连上了=立刻刷）。见 TROUBLESHOOTING #81。

- ✅ **0.5.6 —— 修「计划批准框认不出来」**：Claude Code 2.1.241 的计划批准框脚注是
  `ctrl+g to edit in VS Code · ~/.claude/plans/xxx.md`，`to cancel` / `to navigate`
  **两个已知锚全部落空** → `Prompt.parse` 返回 null → **整个框在手机上不存在，用户批不了计划**。
  改用**光标行 `❯ N.`** 做退路锚（那个形态在全部五份真实抓屏里都在）。
  是 iOS 那边的 ios-parsers 实测发现的，我在真机上复核并修的安卓。见 TROUBLESHOOTING #82。

- 🚧 **iOS 版第一版（2026-08-23，`ios/`，跟 `android/` 完全分开）**：五个子代理并行做的。
  **能验证的部分验了**：`swift build --target YxiKit` → 430/430；`swift test` → **98 条全绿**
  （这台 Linux 上装了 Swift 6.0.3，`/opt/swift/usr/bin`）。测试样例全部从真机抠。
  **编不了的部分**（SwiftUI / 真机 SSH / 分发）共 **20 处标了「未验证」**。
  ⚠️ **卡在没有 Mac** —— 完整 App 一行都没编过。见下面「## iOS 版」。

- ✅ **0.5.7 —— 终端「历史」模式**：点键盘条上的「历史」，之后在终端上**上下滑动翻页**。
  同一个需求先后走错两条路：让控件自己滚（`ScrollController` 是 Kotlin `internal`，
  **字节码里却是 public，看字节码会得出相反结论**）、驱动 tmux copy-mode
  （进得去但 `[0/0]` —— Claude Code 占**备用屏**，输出根本不进 tmux 历史）。
  正解是送 PageUp/PageDown 给那个全屏程序自己。见 TROUBLESHOOTING #83。

- ✅ **0.6.0 —— 对话三件（2026-08-23）**：用户一次点了三件，都做完了。
  ① **注入内容单独渲染**：队友消息 / 任务通知 / 系统提醒 / 命令输出在转录里**也是 `user` 类型**，
  此前一视同仁做成用户气泡 —— 屏幕上一坨 `<agent-message from="…">` 顶着「你说的话」的样子
  （真实会话里 33 处）。现在是折叠卡片。**注意队友消息走的是 `queue-operation` 不是 `parseUser`**。
  ② **增量解析**：老做法每 300ms 把整个缓冲重解，实测 `tail -n 800` = 4.17 MB，
  等于每秒重嚼三次 4 MB。改成 `Transcript.Incremental` 只喂新行；
  ⚠️ 回填工具结果**必须换新实例**（`var` 就地改 Compose 看不见，卡片永远停在「进行中」）。
  ③ **进会话不再新建连接**：此前打开一个会话要新建 **2 次** SSH 认证，实测降到 **0 次**。
  做法是 MainActivity **预热第二条**连接，而**不是共用看板那条** ——
  共用的话终端通道出事会把看板一起拖死（#16）。代价是每台主机多一条闲连接。
  见 TROUBLESHOOTING #86 / #87。
- ✅ **0.6.4 —— 输入这一块的三件（2026-08-24）**：
  ① **终端用回手机原生输入法**。根因不是「中文支持没做」，是 termlib 把 `inputType` 报成
  `VISIBLE_PASSWORD | NO_SUGGESTIONS`，输入法当密码框处理**直接不给候选词**。
  那个值写死在库里没有参数（`javap` 翻遍了），**唯一的拨杆是 compose mode，而它默认是关的**。
  现在拿到 `ComposeController` 就开。代价：回车整行提交（`Key.Enter → commit()`），
  vim/less/y-n 这种逐键交互要点掉工具条上的 `整行`。见 TROUBLESHOOTING #93。
  ② **对话里的斜杠命令提示**（`agent/Slash.kt`）。打 `/` 弹候选，点一下填进草稿。
  **不拦任何输入** —— 送出去的还是 `tmux send-keys`，自己写的斜杠命令照打照样能用。
  已实测 `send-keys -l '/context'` + Enter 能真的在 TUI 里跑起来。
  打全了就收起提示条（否则点完候选它还挂着挡输入框），这条**依赖「没有命令名是另一个的前缀」**，
  有测试盯着。
  ③ **html 文件能看渲染后的样子**，跟 md 共用同一个「阅读 / 源码」开关。
  用系统 WebView，**JS 关死、baseUrl 传 null**（远端任意文件，不能让它的脚本在 app 里跑）。
  代价是外链 CSS/图片不加载 —— 这条路上只有一条 SSH 连接，没有网络。内联 `<style>` 正常。
- ✅ **0.6.5 —— 对话里的复制与撤回（2026-08-24）**：
  ① **长按自己的消息 → 复制整段**；**AI 的输出改成原生文本选择**（`SelectionContainer`）——
  想要的多半是里面一个 URL 或一段命令，整段复制反而要回头删。
  两者互斥：长按被文本选择消费掉了，所以 AI 那一支不能再挂 `combinedClickable`。
  ② **排队中的消息长按 → 收回改一改**。协议是实测的：`Up` 弹回输入框、`C-u` 清空
  （见 [SessionProbe.popQueue]）。⚠️ **`Up` 全有全无，收不了单独一条** ——
  文案因此写「收回改一改」，收回来的拼成多行进手机的输入框。
  顺带补上转录解析漏掉的 **`popAll`**（第四种 queue operation），
  漏了它撤回后的气泡永远不消失，见 TROUBLESHOOTING #95。
  ③ **附件图片点一下能放大看**。胶囊拆成两个热区：点名字预览、点 ✕ 删掉
  （原来整块都是删除，想确认传对没有，一点就没了）。
  预览读**手机本地**那份（`Staged.localUri`），不从服务器拉回来 —— 白跑一趟还慢。
- ✅ **0.6.6 —— 连接真的持久了（2026-08-24）**：切 tab、进出会话都不再重连。
  病根是 `SessionsScreen` 在 `onDispose` 里断了一条**参数传进来的**、
  由 `MainActivity` 持有的共用连接（而同一个参数的 KDoc 正写着「连接不能跟着断」）。
  判据是**谁建的谁收** —— `disconnect()` 的六个调用点里只有这一处错。
  顺带把会话列表也挂到 `MainActivity`：活得比界面久的数据不能存在界面里，
  否则切回来是空列表、要等一次往返才有内容（就是之前说的「骨架屏闪光」）。
  验的方式是盯 TCP 源端口，不是看界面 —— 见 TROUBLESHOOTING #96。
- ✅ **0.6.7 —— 只通知置顶的会话（2026-08-24）**：设置页新增开关，**默认开**。
  ⚠️ **一条都没置顶时故意不生效**（照常全部通知）—— 否则新装的人什么都收不到
  而设置页绿勾还亮着，就是 #91 那个坑；设置页把这句明写出来。
  判断抽成纯函数 `Pinned.shouldNotify`，因为它错了的表现是「全静音且无报错」，
  差点栽在 `cc-` 前缀上（置顶存全名、`EventService` 里另有个短名变量），见 TROUBLESHOOTING #97。
- ✅ **0.6.8 —— AI 回复里的文件路径能点了（2026-08-24）**：点一下直接跳进文件模式，
  目录就进目录、文件就直接打开（`agent/Linkify.kt` + `FilesScreen(jumpTo=)`）。
  做法是**渲染前改写 markdown 源码**成链接、再用自己的 `LocalUriHandler` 接住点击 ——
  库的 `MarkdownAnnotator` 走不通（节点粒度太碎，单个节点看不出是路径）。
  认路径**故意保守**：至少两段、认汉字但不认中文标点、行内代码连反引号一起包。
  三个坑都是真句子逼出来的，见 TROUBLESHOOTING #98。
  顺带修掉 #99：工作区开着时点通知跳会话纹丝不动（`remember(host.id)` 少了会话这个 key）。
- ✅ **0.7.0 —— 简体中文 / English 切换（2026-08-24）**：设置页最下面切，**立刻生效不用重启**，选择落盘。
  界面、通知、SSH 报错、工具卡片全覆盖（**271 句**）。
  做法是 `t("中文原文")` + `ui/En.kt` 一张表，**中文原文就是 key**，查不到原样显示中文。
  为什么不用 `strings.xml`（插值 / 非 composable / 起 id 三笔账）写在 `ui/I18n.kt` 的类注释里。
  代价是没有编译期检查 → **`dev/i18n-check.sh`** 扫源码比对，加上 `I18nTest`
  盯占位符个数、空译文、译文里残留中文。
  ⚠️ 翻译不能放在 enum 常量参数 / object `val` 里（一次性求值，语言冻住），见 TROUBLESHOOTING #100。
  开发者模式那一页**故意不翻**（排查用，中文更准）。
- ✅ **0.7.1 —— 常驻通知变成「实时活动」，给灵动岛/灵动胶囊铺路（2026-08-24）**：
  常驻那条不再是死的「盯着 N 台机器」——有会话等你时变铜色、写出是哪几个，
  批完自动消掉。同时带上 `EXTRA_REQUEST_PROMOTED_ONGOING` 请系统提升。
  ⚠️ **提升是请求不是命令，系统不给是静默的**，所以开发者模式的诊断里加了「胶囊」一行，
  发完回头查 `FLAG_PROMOTED_ONGOING` 有没有被盖上 —— **让手机自己回答这个问题**。
  见 TROUBLESHOOTING #101。
- ✅ **0.7.2 —— 对话右上角显示上下文和今日花费（2026-08-24）**：
  `上下文 656K   今日 188.6M · $125.30`。
  ⚠️ **上下文不问服务器**：最后一条 assistant 消息的 `usage` 三项相加，
  顺着已有的转录流解出来，零额外往返（只看 `input_tokens` 会得到 1）。
  **不给百分比** —— 窗口大小转录里没有，实测同一模型名下窗口不同，算错比不算危险。
  今日花费走那台机器的 `ccusage daily`，日期在服务器上算（时区）。
  顺带修掉「ccusage 探测路径太窄，node 装在 /opt/node*/bin 时用量整块默默不显示」。
  另外 `agent/Quota.kt` 已经写好并有测试（`/usage` 面板的解析 + 跑一次的协议，
  实测那条命令不花钱、不写转录），**但还没接到界面上**。见 TROUBLESHOOTING #102 / #103。
- ✅ **0.7.3 —— 草稿不丢 · API 报错单独渲染（2026-08-24）**：
  ① 打了一半的字**落盘**（`ui/Drafts.kt`，按主机+会话分键），
  切模式 / 退出去 / App 被杀都还在 —— 实测 force-stop 重启后仍在。
  存三处：打字防抖、`onDispose`、发出去后立刻清。少一处都会在某种走法下丢字。
  ② `API Error: …` 不再当正文渲染，改成红框 + `!` + 等宽的故障卡片。
  判据用转录自己的 `isApiErrorMessage` 标志，**不匹配文本**
  （否则用户问「529 是什么意思」，回答会被渲染成故障）。
  见 TROUBLESHOOTING #104 / #105。
- ✅ **0.7.4 —— 真订阅额度（5 小时 + 本周）（2026-08-24）**：
  看板顶上的卡片点一下 → 借一个**闲着且输入框是空的**会话跑一次 `/usage` → 两行真额度。
  ⚠️ `/usage` 不花钱（$0.0000）、不写转录、Esc 就关，所以「点一下跑一次」是干净的。
  ⚠️ **安全闸实测拦住了**：点的时候有两个会话输入框里有用户没发完的字，都没被碰，
  所有转录里没有一条 `/usage` 消息。
  顺带把那条误导的标签改了：「5 小时窗口」画的其实是**时间**过了多少，不是额度 →「窗口已过」。
  见 TROUBLESHOOTING #106。
- ✅ **0.7.5 —— 手机上换模型（2026-08-24）**：对话右上角显示当前模型，点一下弹选单。
  ⚠️ **点一下 = 只换这个会话**（方向键挪过去再送 `s`）；
  「同时设为默认」要单独勾 —— 因为**直接送数字等于改账号默认**，
  连 `~/.claude/settings.json` 的 `model` 都会被改写。见 TROUBLESHOOTING #108。
  实测：选 Fable → 会话里显示 `for this session only`，账号默认仍是 `opus[1m]`。
  窄窗口下会折行 + 截断（`… +2 models`，行首是 `↓`），已按方向键滚动收集并滚回原位。
- ✅ **0.8.0 —— 界面风格可切换，先来一套 浅色（参考款）（2026-08-24）**：
  设置页「界面风格」里切，立刻生效、落盘。
  实现的关键不是配色本身，是**把 `Color.kt` 里的顶层常量改成读 `LocalPalette` 的取值器** ——
  全 app 250+ 处 `Copper` / `Dim` / `SurfaceContainerLow` **一个字都不用改**。
  编译器会替你找出 5 处非 composable 的用法（`Highlight.of` 等），标注一下就行。
  ⚠️ **终端永远深底**（ANSI 彩色在浅底上读不了），浅色下切终端会亮暗跳一下，设置页写明了。
  顺带发现并修掉：命令输出里的 ANSI 转义被纯文本卡片原样画出来。
  见 TROUBLESHOOTING #109 / #110。
- ✅ **0.8.1 —— 主机页两档额度 + 修掉排队气泡不消失（2026-08-24）**：
  主机行现在画 5 小时 / 本周两档，各带**已用 + 剩余** + 重置时间 + 「几分钟前查的」。
  查到的额度落盘（`QuotaCache`），否则主机页永远看不到会话页查的结果。
  ⚠️ 同时修掉一个老 bug：**`dequeue` 不带 content**（实测 1094 条一条都没有），
  而斜杠命令被本地消化、永远不会作为 user 消息出现 →「排队中」气泡永久挂着。
  改成真 FIFO（enqueue 进队尾 / dequeue 弹队头），容器从 Set 换 List
  （Set 会把同一句话排两次合成一条）。见 TROUBLESHOOTING #111 / #112。
- ✅ **0.8.2 —— 四件（2026-08-24）**：
  ① 对话状态条在**窄屏**上不再消失（判据从「脚注有 esc to interrupt」改成「状态行本身的形态」，
  窄屏脚注会被截断，见 #113）。
  ② 终端不再花屏：桌面同时 attach 把窗口撑宽 → `attach -d` 让手机独占 + 关掉那条 tmux 状态栏（#114）。
  ③ 附件上传不再「要好几次」：每次开新 SFTP 通道 + 试两次 + 失败报真原因（原来复用坏通道且静默失败，#115）。
  ④ 设置页照参考款 加了引导图标、粗标题、大留白（改 Card 一处，七节全变，#116）。
- ✅ **0.8.3 —— 设置收起成行 + 主机页长按看额度（2026-08-24）**：
  ① 设置默认一条条收起，点标题才展开（`Card` 加 `startExpanded`/`subtitle`，收起显示一句副标题）。
  ② 版本那节标题从「这个 App」改成「版本」。
  ③ **主机长按 → 展开 5h/7d 两档额度**（已用+剩余+重置时间）+「改主机」。
  ④ **额度查询重写成 `claude -p "/usage"`** —— 不借会话、不打扰任何东西、不花钱，
  彻底解决「会话全忙时借不到、查不了」。`borrowable` 留给了 `/model`。见 TROUBLESHOOTING #117/#118。
- ✅ **0.8.4 —— 历史滑动平滑 + 会话页撤额度（2026-08-24）**：
  ① 终端历史模式的「档位感」修了：从 PageUp（一下 8 行）改成**鼠标滚轮**（一下 1 行），
  映射到手指位移就平滑了。Claude Code 认滚轮（它为点选项开着鼠标追踪）。见 #119。
  ② **会话页顶上不再显示额度**（用户要求），只留主机页长按那台机器时查。
  删掉 UsageCard/UsageStrip/UsageCache + 会话页 Usage.probe 轮询，见 #120。
- ✅ **0.8.5 —— 会话卡：撤按钮，长按改拖排序（2026-08-24）**：
  删掉卡片上的 `开终端`/`回它一句` 按钮 + 长按回复。改成 **轻点=进对话（在里面回它），长按=拖动排序**。
  Pinned 从 Set 改成**有序 List**（次序即数据）。拖动只在置顶组内，`rememberUpdatedState` 修了
  「拖再远只动一格」（协程闭包捕获旧 tops）。实测单格/多格拖都对、重装后次序还在。见 #121。
- ✅ **0.8.6 —— 额度显示订阅档位（Max 20x/5x/Pro）（2026-08-24）**：
  额度卡顶上加「档位 · Claude Code」头（学 Moshi）。档位在 `~/.claude/.credentials.json` 的
  `rateLimitTier`，不在 /usage —— fetch 时**只 grep 那两个字段**（绝不 cat，里面有 token）跟 /usage 拼一起解析。
  ChatGPT/Codex：只能读本机装了的，本机没 codex，不做。见 TROUBLESHOOTING #122。
  ⚠️ **DNS 还是加不了**：用户给的 CF 令牌是 R2 的，能读 zone 但没 DNS:Edit（#123），
  `dl.keuury.com` 仍差一条 A 记录（要 Zone:DNS:Edit 令牌，或面板手加 → 64.90.25.56，DNS only）。
- ✅ **0.8.7 —— 修长按松手误开对话（2026-08-24）**：
  #121 上线后长按不拖就松手会误开对话（`clickable` 不认长按，抬手照样点一下）。
  修法：拖动一起来就 `openEnabled=false` 掐掉 `clickable`（`dragIndex>=0` → 整组轻点关掉）。
  实测三态齐全：轻点进对话、长按+拖排序、长按松手不开对话。见 TROUBLESHOOTING #124。
  **已发 code 41 到公网**（hk13:64.90.25.56，sha256 校验一致）。
- ✅ **0.8.8 —— 图钉下面加回「回它一句」快捷按钮（2026-08-24）**：
  0.8.5 把卡片上的回复按钮撤了（改成「点卡片进对话去回」）；用户要一个**不进对话、
  直接甩一句就走**的快捷入口。加回来，位置在**图钉正下方**一个气泡按钮（`Glyph.Chat`）。
  区分：**轻点卡片 = 进对话细聊；气泡 = 弹底部输入框送一句（`SessionProbe.send` → tmux send-keys）**。
  卡片布局从「Column + 顶栏 Row」重构成「Row(左内容 weight1 / 右竖排 图钉+气泡)」，文字不再和按钮抢位。
  ⚠️ 气泡/图钉各自 `clickable` 会**消费**点击，不冒泡到卡片的 onOpen —— 点它们不会顺带开对话。
  实测六态齐全（scratch `cat` 会话验的送达）：气泡弹框、送达 pane、框自关、点正文进对话、长按拖排序都对。
  **已发 code 42 到公网**（sha256 校验一致）。SendSheet 复活，见源码尾部。
- ✅ **0.8.9 —— 修「有时闪退」（2026-08-24）**：dropbox 里挖出崩溃都在 SSH 层 ——
  连接半路断了（锁屏/切网/服务器掐空闲），下一个 SSH 操作抛 `session is down`/`Broken pipe`，
  **从协程逸出没人接 = 闪退**。看门狗最长 30 秒才判死，这窗口里任何 exec 撞上就崩。
  修法（源头兜，别指望 30+ 调用点各自加 try）：`SshSession.exec` 整段 try —— 取消照抛、
  连接类失败吞掉返回空（跟 `Shell.write` 一个路子）；`ChatScreen` 那条裸 `stream().collect`
  用 `catching` 包住（看聊天时连接抖一下就崩的那条）。见 TROUBLESHOOTING #125。
  **实测**：杀连接/冻结连接/看聊天时杀连接三种走法都不崩，日志里能看到 `exec 挂了…session is down`
  被兜住、随后自动重连。**已发 code 43 到公网**（sha256 一致）。
- ✅ **0.9.0 —— 会话新增「实验室」页（2026-08-24）**：会话导航栏从 3 个变 4 个
  （终端/对话/文件/**实验室**）。实验室 = UI 实验的展示台，一张张 demo 卡，点开满屏跑：
  加载 UI 合集 / 弹簧动效 / 网页效果（WebView JS 开着）/ 新页面原型。**加新实验 = 往
  `LabScreen.experiments()` 加一个 `Experiment`**。纯本地不碰 SSH。`Mode` 枚举加 `Lab`、
  `when(mode)` 加一路分派、`ModeSwitcher` 自动多一个 tab。实测四个 demo 都跑通。
  ⚠️ WebView demo 别用 canvas / 动画背景层（不合成），只用纯色+transform，见 TROUBLESHOOTING #126。
  **已发 code 44 到公网**（sha256 一致）。
- ✅ **0.9.1 —— 实验室支持 GIF（2026-08-24）**：加了「动图 GIF」实验，`assets/lab_sample.gif`
  用 `ImageDecoder`→`AnimatedImageDrawable` **原生播放**（API 28+；26/27 退回 BitmapFactory 首帧），
  **没引任何图片库**。证明实验室能吃 GIF 这类现成媒体格式，不止手写 Compose。已发 code 45。
- ✅ **0.9.2 —— 实验室：Yxxxxxi 加载动画候选 + 审核勾选（2026-08-24）**：抄 Dribbble「字顶上滚东西」
  的 loading，改成「Yxxxxxi」——`YxiLoader.kt` 里 **5 版候选**（顶部传送带/扫光填充/小球跳过/
  字母波浪/底部点阵），`HorizontalPager` **左右滑着挑**（像刷小红书），标序号 + 圆点。每版底下
  **审核勾选**：勾了写进**连的那台服务器** `~/.yxi/lab-approvals.txt`（一行一个 id，见 `agent/LabApprovals.kt`）——
  **这样开发者 `cat` 一下就知道用户审核过哪几个**（勾选落服务器不落本地，就为了我读得到）。
  实测：5 版都跑通、滑动/序号/圆点对、勾选后文件里出现 `loader-1-topconveyor` 等。已发 code 46。
- ✅ **0.9.3 —— 实验室加图标候选 + 参考动效收藏（2026-08-25）**：
  ① **Yxi 图标 4 候选**（`LogoConcepts.kt`）：❯提示符/对话气泡/Y字标/遥控，**Compose Canvas 画的**
  （`LogoMark`，`PathParser` 解 SVG 路径 + 珊瑚渐变底），实测跟网页提案 `mark.html` 一样质感。
  也放进网页版给对比：hk13 上 `http://64.90.25.56:8899/mark.html`（临时预览，选定后可撤）。
  ② **参考动效收藏**：把用户发的两个 Dribbble GIF（`assets/ref_submit.gif` 提交→进度→成功/失败、
  `ref_blob.gif` 液态球→打勾）摆进实验室能播；勾选 = 「想复刻这个」。
  两个都走 `LabApprovals`（logo-N-* / ref-N-*），`cat ~/.yxi/lab-approvals.txt` 读用户勾了啥。已发 code 47。
- ✅ **0.9.4 —— 实验室「在线实验」：内容从服务器读，推新设计不用更新 App（2026-08-25）**：
  用户问「以后要更新 App 才能在实验室看到吗」→ 不用了。`agent/LabRemote.kt` 读连的那台机器
  `~/.yxi/lab/manifest.json` + 素材（html/gif/image/note），`ui/RemoteLab.kt` 渲染（html 走 WebView JS 开、
  gif/图 base64 经 exec 传回来）。**我往 `~/.yxi/lab/` 丢东西、用户点刷新就见，不发版不更新。**
  勾选审核照走 `LabApprovals`。已 seed 两个**参考动效的真复刻**（不是播 GIF）：
  `submit.html`（GIF A：按钮→进度→成功/失败，状态驱动）、`blob.html`（GIF B：液态球 loading，
  **打勾只在加载真完成时画一次**——用户指出直接用 GIF 会循环闪勾，这版把勾挂到完成事件）。
  实测：改服务器 html + 点刷新即更新（不重装）；blob 的勾像素级验过只在 done 帧出现。已发 code 48。
- ✅ **0.9.5 —— 表格横滑看全 + 变形提交按钮（2026-08-25）**：
  ① **聊天里 markdown 表格**原来每格单行截成省略号，读不到。换掉库默认表格（`markdownComponents(table=…)`）：
  自己画的 `MarkdownScrollTable`（`ui/MarkdownTable.kt`）—— 定宽列 + 单元格换行 + 整表横向滚动，
  从 AST 取原始表格文本 `getTextInNode` 自己按 `|` 切（`parseMdTable`）。实测窄表看全、宽表左右滑。
  ② **变形提交按钮** `ui/SubmitButton.kt`（复刻用户选中的 Dribbble GIF A）：点→morph 进度条→✓成功/✗失败重试，
  接**真状态**不是播动画（work 返回 Result，可喂真实 progress）。接进用户选定的三处：**回它一句的发送**
  （成功✓自动收起 sheet；exec 静默失败，靠 isConnected 判成败）、**下载更新**（真实百分比进度）、**装公钥**。
  实测回它一句：发送→✓已送达→自动关，消息真的到了。已发 code 49。
- ✅ **0.9.6 —— 对话加载体验修好（2026-08-25）**：用户报「加载慢 / 先加载旧对话 / 跳底部要点好几次」。
  ① **先加载旧的**根因：head（tail -60 最新）先显示没错，但随后 `tail -n N -f` 流从**最老**开始吐，
  每批 `items=inc.snapshot()` 把最新顶成「只含最老几行」——用户看着从旧滚到新。改成 inc 后台默默攒，
  等它**追上 head 最新那条**（uuid key 对上）才交出完整列表；在那之前界面稳停 head=最新。
  ② **跳底部要点好几次**：加了 `stick`（粘底）状态 —— `snapshotFlow{isScrollInProgress}` 每次滚停按落点
  更新 stick=atBottom；点 ↓ 直接 stick=true。粘着时每条新内容自动跟到底，一次点到位不用再点。
  ③ backlog 800→400，传输/解析减半。实测：一进来就在最新（无 ↓ 按钮）、点一次 ↓ 到底、表格也正常。已发 code 50。
- ✅ **0.9.7 —— 对话跳底部真·一次到位（2026-08-25）**：0.9.6 修了大半但用户报「活跃会话还是点好几次」。两处根因：
  ① **scrollToEnd 提前退出**：懒加载下面几项没组合时 `canScrollForward` 会提前报 false，传进来的下标又过时，
  于是只滚一点就 return。改成：**自己读 `layoutInfo.totalItemsCount` 不信外面的下标**，边滚边发现新项就重跳，
  要求**连续两帧**都到底才算真到底，最多 60 帧。（改成无参 `scrollToEnd()`）
  ② **stick 被程序滚动误关**：`snapshotFlow{isScrollInProgress}` 每次滚停都 `stick=atBottom`，活跃会话里程序滚
  常在「刚到底又被新内容顶起」间落定→被判不在底→stick 关→跟随停。改成**只有用户拖动（DragInteraction）才改 stick**。
  ③ 一批多条一次涌入时，最后一条高度在首次滚动后才定，补一个 `delay(120)` 再滚一次贴死底。
  实测（scrolltest 30→40 条 + 突发追加）：一进来在最新、一次点到底、流入自动贴底最后一条完整。已发 code 51。
- ✅ **0.9.8 —— 实验室大改：从「写死的 demo 画廊」变「按类型分栏的产物库」（2026-08-25）**：
  **撤掉所有内嵌 demo**（删了 YxiLoader/LogoConcepts/RemoteLab.kt + 三个 bundled gif）。实验室现在全从服务器读。
  顶层 = **栏目**（按 type 分：图像/矢量/动图/视频/网页/文字，只有有内容的类才出现），
  **左滑露出置顶/删除**（`SwipeActions` 自绘，Animatable+detectHorizontalDrag；删调服务器 `yxi-lab rm`，置顶存本地 `LabPins`）；
  点栏目 → 详情列表，每条：预览（图/网页/动图/文字）+ **由谁生成**（manifest 的 `by`）+ **北京时间**（`at` unix→Asia/Shanghai）+ 勾选审核。
  `yxi-lab add` 第 5 参数 = 由谁生成；已更新 nanobanana 的 CLAUDE.md 让它出图带 "参考款 · nanobanana"。
  实测：4 类分栏、左滑置顶(📌浮顶)/删除(服务器同步没了)、点开图像栏看到真图+由谁+北京时间。已发 code 52。
- ✅ **0.9.9 —— 更新下载改到后台，切页面不断（2026-08-25）**：用户报「点更新后切进会话再退出，下载就停了」。
  根因：下载挂在更新横幅的 `rememberCoroutineScope` + 界面持有的 SFTP 通道上，一进会话看板销毁 → 协程取消、通道关闭 → 静悄悄断。
  修：下载搬进单例 `object UpdateDownloader`（app 级 `SupervisorJob` scope，永不取消），状态 `mutableStateOf` 放单例、横幅只读它画进度；
  通道自己从常驻 `shared.session`（MainActivity 导航之上持有）现开一条 SFTP。回看板时 `LaunchedEffect(ssh)` 重查、横幅摆回、`mine` 命中续显实时进度。
  顺手把 `SubmitButton` 拆出纯视觉 `MorphButton`（状态外传）给横幅复用。已发 **code 53 / 0.9.9**（本地 `~/.yxi/` + hk13，sha256 一致 9cfded23778c）。见 TROUBLESHOOTING #127。
- ✅ **0.9.10 —— 实验室素材「保存原画到本地」（2026-08-25）**：用户要图/GIF/视频等能把原画直接下到手机。
  每个素材卡加一个「保存原图/保存 GIF/保存视频/下载到本地」按钮（复用 `SubmitButton`：点→进度条→✓已存到相册）。
  **存的是原文件字节**（不是预览缩图）：`LabRemote.download` 走 **SFTP 流式**下（`.yxi/lab/<file>` 相对路径 jsch 自解析到 home）——
  **不用 base64 经 exec**，那个会把大文件（视频）截断。`MediaSaver`（新）用 MediaStore 分区存：图→相册 Pictures/Yxi、
  视频→相册 Movies/Yxi、其它→下载 Download/Yxi，**Android 10+ 不要任何权限**。
  实测（模拟器连 10.0.2.2=本机）：点保存原图 → `/sdcard/Pictures/Yxi/…jpg` **943575 字节，跟服务器原文件一模一样**（原画），
  MediaStore 登记 mime=image/jpeg、按钮转「✓已存到相册」。已发 **code 54 / 0.9.10**（本地 + hk13，sha256 一致 660fb4f57b3f）。
  ⚠️ ponytail 天花板：只做了 API 29+（用户机是 15）；26–28 会明确报「需要 Android 10+」，要支持再加动态存储权限。
- ✅ **0.9.11 —— 一批小巧思（19 项，调研后用户拍板全做，2026-08-25）**：四路 subagent 调研（代码盘点/同类App/CC能力面/手机端）汇总后落地。清单见 `POLISH-CHECKLIST.md`。
  **通知**（`watch/EventService.kt`+`AnswerReceiver`）：拆 CH_NEEDS(急促两下)/CH_DONE(轻一下)两频道给不同触感；加 RemoteInput「回一句」到 needs+done（顺带白送语音）；「静音」动作+`ui/Mute.kt`；等待计时+跨 2/5/10/20/40 分升级重提醒(escalate)；30s statusLoop 数「在跑」喂常驻通知。
  **聊天**（`ChatScreen`/`ToolCards`）：TodoWrite 渲染成 ☐▶☑ 清单+顶栏「正在做…」；忙时 LiveStatus 加「■停」(发 Escape)；上下文数 ≥15万染琥珀、点发 /compact；`Snippets.kt` 常用语 chip（草稿框+回复 sheet，可编辑）；危险审批(`Risky`正则)先验指纹(`Biometric` 框架 API28+)；PendingCard「看改动」→`GitDiff` DiffSheet(+绿-红@@青)。
  **看板**（`SessionsScreen`）：相对活跃时间`ago()`；悬浮卡实时状态词/耗时(`Live.doneFor`)；每会话静音(卡片🔕+回复sheet开关)；每主机稳定配色`hostColor()`；「＋」从手机拉起会话(最近目录→tmux new+claude)。
  **新组件**：`ShareActivity`(分享文字/图片/文件到某会话)、`widget/WaitingTile`(QS磁贴)、`widget/WaitingWidget`(桌面小组件，RemoteViews无新依赖)——后两个读 EventService 写进 prefs 的已知态。
  **没做**（用户说大赌注先不做）：PreToolUse 阻塞式远程审批、连接健康点+自动重连；Wear OS（荣耀表非 Wear OS）。
  实测（模拟器）：ShareActivity 全流程（选会话+预览+相对时间）、看板配色点/＋、聊天常用语 chip 均正常，三个新组件已注册、无崩溃。已发 **code 55 / 0.9.11**（sha256 一致 a9133f7788d1）。
  ⚠️ 现有已很完整：终端快捷键条(`KeyBar.kt` esc/tab/^B/方向/^C…)、语音(RecognizerIntent)、灵动胶囊(promote)本就有，本次没重做。
- ✅ **0.9.12 —— 对话里下载文件 + 「配置」tab（2026-08-25/26）**：
  **① 对话里下载文件**：文件查看器（`ui/FileViewer.kt`）顶栏加「下载」按钮 —— 从对话点文件路径就能到这，把**整个原文件**下到手机：图/视频进相册、csv/xlsx/pdf/zip 等进「下载」目录(Download/Yxi)。复用现成 `Sftp.download`(流式) + `MediaSaver`；`MediaSaver.mimeOf` 加了 xlsx/csv/pdf 等办公/数据格式表(各机型 MimeTypeMap 不一致)。实测：csv 下到 `/sdcard/Download/Yxi/`，字节一字不差。**全程走 SSH/SFTP,不碰公网 HTTP**(那只给网页装包)。
  **② 「配置」tab**（底部导航 会话/主机/**配置**/设置）：分服务器、分工具(Claude Code `~/.claude` / Codex `~/.codex`)浏览 + 编辑 agent 配置：技能/MCP/子 agent/命令/权限/钩子/记忆/插件。工具无关——哪台装了哪个才显示(现在三台都只有 Claude Code,Codex 没装)。**插件自带的技能/命令也枚举**(installPath 下 glob)。
  - `agent/ConfigRemote.kt`：一次 SSH 抓取(python3 heredoc)→ 结构化 JSON。**密钥服务器侧就打码**(env 值 + 键名含 key/token/secret/password/auth → ••••)，`.credentials.json`/`auth.json` **根本不读**。`save()` = json 先 `JSONObject` 校验 → `cp` 备份成 `<file>.yxi-bak-<ts>` → SFTP 写回。
  - `ui/ConfigScreen.kt`：主机头+下拉 → 工具段 → 可展开类目 → 项 → 详情(md 渲染/mono 文本；编辑取原文明文，结构化文件横幅提醒)。
  实测(模拟器连本机)：配置 tab 显示 Claude Code 的 记忆/设置/技能6(全是 ponytail 插件的)/插件1；开 skill 看 SKILL.md(md 渲染)；settings.json **env 值已打码 ••••**；造个测试 skill 改一行保存 → 磁盘内容变了 + 生成 `.yxi-bak-` 备份(内容是原文)。已发 **code 56 / 0.9.12**(sha256 一致 d42590007f9b)。
  ⚠️ 用户拍板范围=**查看+编辑**、工具通用框架；大赌注(PreToolUse 阻塞审批/连接健康)仍不做。编辑 settings.json/config.toml 这类结构化文件风险高——已上 json 校验+备份，但 toml 只备份没校验(Codex 没装,没实测)。
- ✅ **0.9.13 —— 通知显示「要你决定什么」（2026-08-26）**：用户报「通知说需要决策，但没说决策什么，回复不了」。**根因是 0.9.11 我的 Phase-4 回归**：重构成 `postNeeds`/`postDone` 时把事件的 `detail`（Notification message）漏掉了，只剩「等你决定」。
  修：① `EventService` 把 `detail`（为什么找你）+ 新的 `preview`（Claude 最后说的一句）穿进 `postNeeds`/`postDone`，折叠行就显示「要你决定什么」，展开显示 preview+屏幕提示+位置+等待时长；`WaitCtx` 存 detail/preview 给升级重提醒。② **服务器 `yxi-hook` 加 `preview` 字段** —— 从 `transcript_path` **tail 末尾 64KB**取最后一条 assistant 文本，**纯读文件、零 token/零 API**。
  实测（模拟器）：造 needs 事件带 preview → 通知 `android.text` 直接是那句「…rm 掉可以吗？」，不再是「等你决定」。已发 **code 57 / 0.9.13**（sha a1515c141bd9）。
  ⚠️ **配套**：`yxi-hook` 已更到**本机**（手机盯的就是这台；station/inst2 没装 hook）。手机连别的装了 hook 的机器要一起更 `~/.local/bin/yxi-hook`。旧 hook 也不会崩，只是没 preview。
- ✅ **0.9.14 —— 聊天顶栏「⚡模式」快切（2026-08-26）**：用户要便捷切模型/模式(1M、最大思考、ultracode)且能叠加。
  `ui/Modes.kt`：底部弹出 `ModeSheet`，大 chip 一点就把对应**斜杠命令**发进会话(`SessionProbe.send`)，**点了不关面板**——好连点**叠加**(各模式是独立斜杠命令,`/model`+`/effort`+`/ponytail` 各走各的)。命令**可编辑**(「名字|命令」一行一个,存 prefs)。默认:`1M 上下文|/model opus[1m]`、`最大思考|/effort max`、`高强度|/effort high`、`ultracode|/ponytail ultra`、`普通|/ponytail`。入口=聊天顶栏「⚡模式」(在模型名旁)。
  ⚠️ **默认命令是最可能的猜测**——不同 Claude Code 版本/习惯,`/effort`、`/model <arg>`、ultracode 具体命令可能不一样,所以做成**可编辑**,用户进去改成真能用的那句。实测(模拟器):⚡模式 chip 在、sheet 五个 chip 都对(标签+命令)、编辑弹窗能开;**没在真会话上点发**(会真切模型/模式),send 本身是proven。已发 **code 58 / 0.9.14**(sha 0586a8d034de)。
- ✅ **0.9.15 —— 切完模型立刻显示 + ⚡模式默认命令修正（2026-08-26）**：用户报「切到 Opus 5 了，模式旁边还显示 opus-4-8」。
  **先查清:那次不是 bug** —— 模型是**按会话**的：他在 Yxi 会话切的，而 App 当时显示的是 **claude_desktop**（另一个已在跑的会话，实测其转录最后仍是 `claude-opus-4-8`）；`/model` 回执写的也是「saved as your default for **new sessions**」，不动已跑的会话。
  **但顺带暴露两个真问题，都修了**：
  ① **顶栏模型名会滞后**：它取自「最后一条 assistant 消息」的 model，切换不改写旧消息 → 切完没回话前还显示旧名，用户会以为没切成。修：`Transcript` 认 `/model` 回执（命令输出里的 `Set model to …`）并覆盖 `Ctx.model`（`parseInto` 里加 `lastCtx`）。
    ⚠️ 两个坑（都写进测试了）：**不能先整体 clean ANSI** —— 别名 `claude-opus-5[1m]` 里的 `[1m` 跟加粗序列一样，会被吃成 `claude-opus-5]`；改成**在原文匹配、只摘首尾加粗标记**。正则还要**以 `<` 收尾**，否则把 `</local-command-stdout>` 吃进模型名（**测试抓出来的**）。`Kept model as …`＝没切，不能误判。
  ② **⚡模式默认命令是错的**：`/model opus[1m]` 实测回「Kept model as Opus 4.8」=没切；转录里核到能用的是全名形式 **`/model claude-opus-5[1m]`**。`/effort max|high|mid` 转录里确认真在用（13/5/6 次），另加了「中等」。
  测试：`TranscriptTest.切完模型还没回话也显示新模型`（4 个断言）；**全套 111 个测试通过**。已发 **code 59 / 0.9.15**（sha 5c71833edd80）。
- ✅ **0.9.16 —— 顶栏显示本会话的模型 + 模式（2026-08-26）**：用户要「模型显示要显示本对话的模型和模式」。
  数据**全在转录里，零额外开销**：`effort`（`max`/`high`/`mid`）在**转录行顶层**（跟 `type`/`uuid` 平级，**不在 message 里**——找错地方永远是空）；模式来自单独的 `{"type":"mode","mode":"plan|normal"}` 行（**没有 message 字段**，得在「非消息行静默跳过」之前接住）。
  `Transcript.Ctx` 加 `effort`/`mode` 两个字段；`parseInto` 加 `lastMode`，模式行来得比回话晚也立刻反映。顶栏在模型名后显示「最大思考/高强度/中等」+「计划模式」（Copper 色），normal 不显示（默认态不占位）。
  ⚠️ 那一行现在有**五格**（⚡模式/模型/强度·模式/上下文/今日），窄屏会挤没左边的 → 整行改成**可横滑**（`horizontalScroll`）。
  测试：`TranscriptTest.顶栏带思考强度和模式`（模式行在回话前/后两种顺序都验）；**全套 112 个测试通过**。实机(模拟器)确认顶栏渲染成 `⚡模式 opus-4-8 最大思考 上下文 693K 今日 …`。已发 **code 60 / 0.9.16**（sha f1627f946683）。
- ✅ **0.9.17 —— 顶栏再加 ponytail 强度（2026-08-26）**：接 0.9.16 那条待办。
  **比原计划更省**：本来打算 SSH 读 `~/.claude/.ponytail-active`（每次进会话多一个请求，而且那文件是**全局的**、未必等于本会话）；查下来它每次注入的 `PONYTAIL MODE ACTIVE — level: x` **就落在转录里**，于是**零额外请求、而且是本会话的**。
  `Ctx` 加 `ponytail` 字段；`parseInto` 用正则**直接扫原始行**（`PONYTAIL MODE [A-Z]+[^:]*level:\s*([A-Za-z]+)`）——不钻 hook_success 的 JSON 结构（那是插件实现细节，会变）。
  ⚠️ **不一定读得到**：它只在会话开始/换模式/提交提示时注入，实测同一会话相邻两次可隔 ~3000 行，超出 App 的 tail 窗口就读不到 → **读不到就空着不显示**（沿用「宁可不显示也不显示假的」）。空等级的注入（实测真有 29 条）不会冲掉已知值。
  验证：`TranscriptTest.认得出ponytail强度`（4 组断言）+ **正则跑真实转录：122 条真注入全中、全部解出 `full`，17 条未匹配都是我自己的 grep 命令文本被记进转录，正确忽略**；**全套 113 个测试通过**。已发 **code 61 / 0.9.17**（sha b092dba11a94）。
  ⚠️ 顶栏那行没再视觉复核（模拟器 App 数据又被重装清空）——渲染走的是跟「最大思考」同一条 buildList 分支，那条 0.9.16 已实机确认。
- ✅ **0.9.18 —— 修「按下时高亮是个方块」（2026-08-26）**：用户报长按各种可点的东西，变色的是个长方形块而不是按钮本身。
  根因：`Surface(shape = X, modifier = Modifier.clickable{})` —— Surface 只裁**内容**，`clickable` 在它**外面**，波纹画在矩形边界里。全 App **48 处**都这么写。修法：`clickable` **紧前面**加 `.clip(X)`。
  ⚠️ 第一版脚本插到链首，遇到链里有 `.padding()` 的等于没修（波纹变成 padding 后的小矩形）——必须紧挨 `clickable`，改了 24 处位置。详见 TROUBLESHOOTING #129（含验证手法）。
  验证：脚本复查「有 shape 且 clickable 却没 clip 的 Surface」= **0 处**；非 Surface（带 shape 背景的 Box/Row）也扫了 = 0 处；**113 个测试通过**；实机 `input motionevent DOWN` 按住截图 + 像素 diff：变化区域**四角未被涂到**、且与胶囊边界吻合，放大目视确认是**胶囊形高亮**。已发 **code 62 / 0.9.18**（sha d6edd7e28d19）。
- ✅ **0.9.19 —— 修「会话卡时间不对」（2026-08-26）**：用户报卡片写着 14 小时前/1 天前，但那些会话刚聊过。
  根因：`lastActivity` 只取 tmux 的 `#{session_activity}`，而它会陈旧到离谱 —— 实测 `claude_desktop` tmux 说 2 天前、cc-state 的 ts 说 **7 天前**，而转录**1 分钟前**还在写。**转录 mtime 才是权威**（Claude Code 每说一句都写它）。
  修：抓取脚本加一段列「项目目录 → 最新 .jsonl mtime」，`SessionProbe.lastActivityOf()` 取 `max(tmux, 转录)`，读不到退回 tmux。⚠️ 用 `find -printf | awk` 一次扫完（**8ms**）而不是每目录 ls+stat（**230ms**，看板每 5 秒一次受不了）；非 GNU find 就输出空 → 优雅降级。
  验证：新 `ActivityTest`（4 例，含真实的 claude_desktop 数据）；**全套 117 个测试通过**；实机确认卡片从「14 小时前/1 天前」变成 **5/7/24 分钟前**。已发 **code 63 / 0.9.19**（sha 73f6173d354a）。见 TROUBLESHOOTING #130。
  ⚠️ 遗留：`state`（等你/干活中）也来自 cc-state，同样可能陈旧，本次没动 —— 哪天「分组不对」先怀疑它。
- ✅ **0.9.20 —— 修「点发送再切走，消息丢了」（2026-08-26）**：用户原话「要在对话里面等几秒再返回才算发给 agent 了」。
  两层病根：① 发送跑在对话界面的 `rememberCoroutineScope`，切走即取消，而草稿在点击那刻已清空并落盘 → 话**既没发出去也没了**；② `SessionProbe.send()` 是「打字 + 回车」两步，中途取消 = 字进去了回车没送，卡在对方输入框里。
  修：`send()` 整段 `withContext(NonCancellable)`；新增 `ui/Sender.kt`（app 级 scope，仿 `UpdateDownloader`），**发失败把话还回草稿**并提示。
  实测：临时 tmux 靶子会话 → 输入 `YXISENDPROOF42` → 点发送后**立刻返回（零等待）** → 会话里收到**且被执行**（command not found）✓。**120 个测试通过**。已发 **code 64 / 0.9.20**。见 TROUBLESHOOTING #131。
  ⚠️ 判据推广：**任何「点一下就走」的动作**都不能挂界面 scope。已排查：装公钥/附件上传在 sheet 里（点完不会立刻销毁）、下载更新已是 app scope。
- ✅ **0.9.21 —— 设置里加「工单中心」（2026-08-26）**：用户要一个地方收集 App 的不足，方便查阅更新。
  `agent/Tickets.kt` + 设置页 `TicketsCard`：写一条 → 追加进**连着那台服务器**的 `~/.yxi/tickets.jsonl`（JSONL，只追加），
  **自动带上版本号 + 机型**（不带的话回头对不上是哪版的毛病）；卡片里同时列出已提的（北京时间）。
  **为什么存服务器而不是手机本地**：存本地只有本人看得见 = 等于没提。**为什么不开公网接口**：Yxi 无云后端，公网 POST 要防刷+隐私，与「不依赖第三方」冲突；走已有 SSH 通道零新基建。
  ⚠️ **局限**：APK 分享给别人后，他们的工单落在**他们自己的服务器**上，我们看不到。要收外部反馈得另在下载机(hk13)开收集端点 —— 那是另一件事，没做。
  查阅方式：`cat ~/.yxi/tickets.jsonl`。实测：App 里提交 → 服务器文件里出现带 version/device 的 JSON 行 → 按钮转「记下了」、列表显示「已提 1 条」✓。新增 `TicketsTest`（shell 单引号转义 + 坏行跳过），**全套 120 个测试通过**。已发 **code 65 / 0.9.21**。
- ✅ **0.9.22→0.9.25 —— 更新改走公网下载页 + 上 HTTPS 域名（2026-08-28）**：用户问「换个客户不就搞不了了」，确实——旧做法查的是**所连服务器**的 `~/.yxi/latest.json`，别的客户机器上没包。
  **现在**：`Update.checkPublic/publicVerbose` 查 **https://dl.keuury.com/latest.json**（根目录由 nginx 别名指向当前发布目录，**不用带 token**），下载走 HTTP 流式；公网不通才回落到服务器 SFTP（防火墙后仍可用）。
  **域名/证书**：CF 加 A 记录 `dl` → 64.90.25.56（灰云）→ 源站 `certbot --nginx` 拿 Let's Encrypt 证书（到期 2026-11-26，自动续期）→ http 301 跳 https。nginx 里 `server_name dl.keuury.com` 的块早就写好了，只差 DNS。
  ⚠️ 中途为明文 HTTP 加过 `network_security_config.xml`（只豁免单域名），**上了 HTTPS 后已整个删除**。
  ⚠️ 关键 bug：第一版把公网检查写在 `LaunchedEffect(ssh)` 里，**没连主机就查不到更新**——已拆成独立 effect；设置页「检查更新」也不再要求已连接。
  验证：**藏掉服务器 latest.json + 不配任何主机**，App 仍查到 0.9.24 并下完 33846290 字节（与公网一字节不差）、拉起安装器 ✓。**120 个测试通过**。已发 **code 69 / 0.9.25**。见 TROUBLESHOOTING #132。
  ⚠️ 老链接 `http://64.90.25.56:8899/<token>/` **继续保留**（已装旧版的人靠它更新）。
- ✅ **GitHub 恢复同步（2026-08-28）**：远端曾停在 0.8.6，落后十几个版本。已提交并推送 0.8.7→0.9.21（推前扫过密钥/密码/token，干净）。
  ⚠️ 顺手把一个**有效的 GitHub token** 从 `mail` 仓 remote URL 的明文里摘掉，改存 `~/.git-credentials`（600）+ `credential.helper store`，以后各仓都能直接推。
  ⚠️ **该 token 早前在聊天里贴过、且仍然有效，建议轮换**。
  **纪律：以后每次发版顺手 commit + push，别再攒。**
- ✅ **0.9.26 —— 选择器大修（2026-08-28）**：用户报「只有选择没有问题」「点一个选项要等十秒」「回不到上一题」「支持多选吗」。
  **拿真机样本修的**：起了个临时会话让真 Claude Code 出一个「两问题 + 第二问多选」的 AskUserQuestion，**并把窗口缩到 46 列复现窄屏**，抓下 4 份原始屏幕固化成 `PromptRealTest`。
  ① 问题正文丢失：窄屏脚注折成两行，前半行被当成最后一项的说明（截图里就是它）→ 新增 `isFooterish` 过滤；标题改成**按段收集再拼**（长问题会折行）。
  ② 延迟：老写法送键后 `delay(500)` 只抓一次，抓到旧屏就得等被 `busy` 停住的轮询恢复（闲时 2.5s）→ 新增 `awaitChange()`（130ms 连抓、指纹一变就返回），待答挂着时轮询 2.5s→0.7s。**实测 ~10 秒降到 ~3 秒**（余下是 SSH 往返，本地抓屏 0ms）。
  ③ 回上一题：TUI 本就支持（`←  ☐ 名字  ☒ 配色  ✔ Submit  →`），已解析成 `Pending.tabs` 并给出「← 上一题 / 下一题 →」+ 标签状态（☑ 已答）。
  ④ 多选：本来就解析 `[ ]`/`[✔]`，补了「提交答案」按钮和一行说明；提交不再硬编码「Right 一次」，改成一路 → 直到 `review` 页再选 `Submit answers`。
  实测（模拟器连真会话）：标题、标签栏、← 上一题、多选勾选（☐蓝色/☑绿色/☐紫色）全部正常；**127 个测试通过**。已发 **code 70 / 0.9.26**。见 TROUBLESHOOTING #133。
- ✅ **0.9.27 —— 延迟根治：屏幕改成「服务器变了才推」（2026-08-28）**：用户追问「延迟真的没办法解决吗」。有办法，而且是治本的。
  0.9.26 只是把轮询调快（~10s→~3s）；本因是**每问一次就是一个 SSH 往返**，而抓屏本身 **0ms**。
  新增 `SessionProbe.watchScreen()`：一条长连通道，服务器侧自比对，**没变不过网**；实测静止零推送、变化 **206ms** 到达。送键后不再自己抓屏（`waitScreenChange` 只读本地状态）。轮询保留为回落。
  ⚠️ 踩坑一：flow 体默认在**收集方线程**跑，`readLine()` 阻塞主线程 → **ANR**，必须 `.flowOn(IO)`。
  ⚠️ 踩坑二：解析也不能在主线程（它忙时屏幕每 0.2s 变一次）→ 在 IO 上解析完再给界面，并 `.conflate()`。
  ⚠️ 量法教训：`uiautomator dump` 一次 **2.4–2.8 秒**，拿它量 0.3 秒的东西量出来全是噪声。分段量才对：TUI 重绘 100ms + 推送 206ms ≈ **0.3–0.4 秒**端到端。
  **127 个测试通过**；实机确认卡片正常、无 ANR。已发 **code 71 / 0.9.27**。见 TROUBLESHOOTING #134。
  📎 顺手加了 `dev/emu-restore.sh`：模拟器 `adb install` 常把 App 数据清空，这脚本一把恢复（授权公钥 + 写回 hosts.json）。
- 📎 **给 nanobanana 的模子文档**：`/root/src/workspace/nanobanana/YXI-MOLD.md`（174 行）—— 工具链/可抄文件/发布/纪律/踩坑 + **「在哪测怎么测」详版**（模拟器、UI 自动化的坑、端到端验证招式）；并在它的 `CLAUDE.md` 里加了指路。
  ⚠️ 模拟器踩坑：`install -r` 那次变成**全新安装**（uid 变了），App 数据被清空。恢复办法：读 App「公钥」界面的公钥追加进本机 `~/.ssh/authorized_keys`，再用 `adb shell run-as app.yxi` 直接写 `files/hosts.json`（UI 自动化填表会因软键盘顶起布局而串行到同一个输入框）。
- ✅ **`yxi-lab` CLI —— 让别的 agent 把产物推进实验室（2026-08-25）**：用户问「别的 tmux（如 nanobanana 出图）
  能不能把生成的东西放实验室」。能 —— 在线实验本来就读 `~/.yxi/lab/`。做了个 `/root/.local/bin/yxi-lab`：
  `yxi-lab add <文件> [标题] [说明]`（图/GIF/网页/文本，新的排最前，自动写 manifest.json）、`list`/`rm`/`clear`、
  `yxi-lab approved`（读用户勾了哪些）。别的 agent 一行就推，用户 App 刷新即见、勾选审核。
  实测：PIL 造图 → `yxi-lab add` → 手机实验室第一张就是那图、渲染正常、能勾选。
  ⚠️ 图走 base64 经 SSH 取，>3MB 会慢（脚本会提醒）。要让某 agent 常态这么干，往它项目的 CLAUDE.md 加一句即可。
  ⚠️ 发版又差点栽 #107：改完版本号没重编就 publish，APK 还是旧 code。**每次 publish 后必用 aapt2 核 APK 实际 versionCode**。
  ⚠️ WebView 在 RemoteLab 容器里会把短内容竖直居中/顶部裁切，做全屏 html 别指望精确布局，留余量。
  ⚠️ 图标定了 → 把选中那版转成真自适应图标（改 `res/drawable/ic_launcher_fg.xml` + `ic_launcher_colors.xml`）。
  ⚠️ 选定用哪版接到真加载态（连接中/重连中）时，改 `YxiLoader.YxiLoader()` 里默认调的那个变体。
- ✅ **下载站升级成正经网页（2026-08-24）**：原来公网只是个裸文件直链（`return 404` 的根）。
  现在 hk13 的 `/var/www/yxi/index.html` 是一张**深色下载页**（Yxi logo + 版本/大小/更新说明现取
  自 `latest.json` + 大按钮 + 4 步安装引导）。nginx snippet `yxi-dl.conf` 改成：`/`=首页、
  `/Yxi.apk` 和 `/latest.json`=干净公开直链、老 token 路径保留（已分享的链接不断）。`:8899` 和
  将来的 `dl.keuury.com` 共用这个 snippet。已 `nginx -t` + reload，实测首页/直链/版本都对。
  ⏳ **就差 HTTPS**：要 `dl.keuury.com` A→64.90.25.56 的 DNS 记录（R2 令牌改不了 DNS，见 #123），
  记录一通就 certbot 签证书（hk13 已有 certbot，别的站在用）。在此之前站是活的、只是 HTTP + 靠 IP:8899 访问。
  ⚠️ **App 内更新走 SFTP**（连的那台的 `~/.yxi/`），跟 hk13 这套 HTTP 分发**互不相干**，改这边不影响升级。
- ✅ **0.9.68（2026-09-02）**：开屏 logo 动效五个方案（`ui/splash/Splash*.kt`，五个子代理各写一个：
  笔触书写 / 光晕绽放 / 墨迹落定 / 粒子聚合 / Y 落下字展开），摆在实验室「开屏动效 · 待你审」卡里能播能选；
  选中的（`Splash.chosen`，prefs `splash`）冷启动时由 `SplashGate` 盖着播一遍。⏳ **等用户挑**，挑之前默认不播。
- ✅ **0.9.67（2026-09-02）**：附件头在正文中间也认（#201）；抓取不完整不再当成零个会话（#202）；
  设置「手机主动响」加「弹窗（横幅）」状态 + 「发一条试试」（#203）。只更新安卓（D24）。
- ✅ **0.9.66（2026-09-02）**：连着 ≥3 条同名、已完成的工具卡合成一张（`groupToolRuns` /
  iOS `ChatRow.group`），点开铺开，进行中和出错的不合；输入框底色跟页面光晕同一套色相流动
  （`glowBrush` / iOS `GlowPill`）；GitHub 登录 `BROWSER=true`（#199）。两端都编过。
- ✅ **0.9.65（2026-09-02）**：配置页分成「连接」和「Agent 配置」两块。「连接」（`agent/Connect.kt` +
  `ui/ConnectPanel.kt`）：GitHub 设备码登录（git + GitHub MCP 一起）、二十来个公开远程 MCP
  （Notion / Linear / Sentry / Jira / Stripe / Zapier / Vercel / Figma …）一键 `claude mcp add` + `login`，
  回调靠 SSH 端口转发接到手机（#198）；免认证的 Context7 / DeepWiki / Hugging Face 直接加；自定义地址。
  Gmail / Slack / 日历只在 claude.ai 订阅登录下有，面板里说明了。
  iOS 同步有了（`YxiKit/Agent/Connect.swift` 有测试 + `UI/Config/ConnectPanel.swift`，Mac 上编过）——
  ⚠️ iOS **没做端口转发**（Citadel 只给积木），MCP 授权完要把 localhost 地址粘回来。
- ✅ **0.9.60 ~ 0.9.64（2026-09-02）**：终止走 `cloud-forget`（#188）；通知图标换新 logo + 官网换 logo/favicon（#189）；
  附件发出去第一帧就是缩略图（#190）；**照参考款 录像逐帧抄的动效**：光晕待机在底部、忙了迁到顶部、
  干活中色相循环、回答到达退掉，新消息滑入淡入（#191）。`/model` 菜单只在会话启动时读配置（#187）。
  0.9.63：光晕铺整页，输入框上方那道色差没了（#192）；仓库里旧产品名全部换成「参考款」，只改名字（#193）。
  0.9.64：通知着色改品牌蓝；锁屏小图标显示旧的是 **ROM 缓存**，包里已核实是新 Y（#194）。
  官网首屏加了同一套流动光晕，底边 mask 淡出（#195，已部署）。
  ⏳ **iOS 还没跟上这一轮**（缩略图 / 光晕 / 滑入 / 手机端语音 / 新 logo / 默认浅色）—— 本机编不了 SwiftUI，
  要推上去跑 CI；用户说 GitHub token 要先换，所以还没推。
- ✅ **0.9.53 ~ 0.9.59（2026-09-01 ~ 09-02）**：
  - 同组 agent 互发**多行**消息卡在对方输入框（#174）：Claude Code 把连着来的一大块当**粘贴**，
    紧跟的 Enter 被吞进粘贴块。`yxi-hub` 和手机端 `send` 都在文本和回车之间 `sleep 0.4`。
  - 语音识别三层（#175 #177）：**手机上算**（sherpa-onnx + SenseVoice，APK 28→49MB，
    模型 153MB 首次下载）→ 服务器 `yxi-asr` → 系统识别。按住说话，结果只填输入框。
    ⚠️ release 只打包 arm64，**模拟器上没语音**；debug 补回 x86_64。
  - 发出去的图显示缩略图、点开放大（#179 #184）；思考时多色**流动**背景光（#180 #185 #186）。
  - 新 logo（`design/logo-yunxi.png`，#181）；默认浅色、名字不再提 参考款（#182）。
  - 「未启用」在没同步到之前不下结论（#183）；冷启动骨架 / 断线提示（#170）。
  - `/model` 菜单定成 `["default","fable-5-1[1m]","opus-4-6[1m]","sonnet[1m]","haiku"]`（#187）。
  - 服务器：swap 8G→16G（`/swapfile2`，已进 fstab）。

- ✅ **0.9.52：一次传多个 + 安装器能重拉（2026-09-01）**
  - **附件一次能选多个**（用户要的）：安卓换 `GetMultipleContents`、iOS 换
    `photosPicker(maxSelectionCount:)` + `fileImporter(allowsMultipleSelection:)`。
    传的时候**顺序传不并发**（#172）：并发时每个协程算 `idx` 都读到同一份 `staged`，
    五张全叫「图片1」；顺序传每轮读得到上一轮结果。失败**攒起来一次报**（「5 个里有 3 个没传上」），
    界面上带「传着… 3/5」。
  - ⚠️ iOS 顺手修了个还没露头的坑（#171）：相册给的每张都叫 `image.png`，
    而远端路径是「秒级时间戳-文件名」—— 同一秒选的几张会写到**同一个路径**互相覆盖。
    改成毫秒 + 文件名带批内序号。
  - **「点拉起安装器没反应」**（#173）：那个绿按钮是 `MorphButton` 的**成功态**，
    `clickable(enabled = Idle || Fail)` —— **根本不可点**，而文案写着「拉起安装器」。
    真根因更深：下载跑在 app scope 上要好几分钟，下完那一刻用户多半已经切出去了，
    而 **Android 10 起后台不许起 Activity，`startActivity` 静默失败**。
    现在 `MorphButton` 有 `okTap`，`UpdateDownloader` 留着下好的文件（`ready`）+ `installNow()`，
    回到这屏点一下就再拉一次，**不用重下 28 MB**。文案改成「已下好 · 点一下安装」。
  - ⚠️ **iOS 那两处界面改动没有编译验证过** —— `swift build` 在 Linux 上只编 `YxiKit`，
    SwiftUI 那个 target 只能在 Mac / GitHub macOS runner 上编（#160 就是这么漏的）。
    逻辑层 314 个测试全过，但界面要 push 触发 iOS CI 才算数。

- ✅ **0.9.50 / 0.9.51：四个用户报的 bug + 一次我自己造成的事故（2026-09-01）**
  - **一键收拾以前收的是它自己**（#165）：`ps -eo args=` 会把执行扫描的那条 awk 列出来，
    而它命令行里写着 `GradleDaemon` —— 于是把自己认成 Gradle 守护进程。
    用户看到的「可以收拾 1 类 · 约 7 MB」就是它自己那三个临时 shell。**这按钮从上线起是空的。**
  - **新增两类可收拾的**：「闲置会话」和「一直霸着 CPU 的进程」。
    会话走 `cloud-forget`（移出自动恢复名单 + 杀，**对话存档保留**），不按 pid 杀 ——
    否则 `cloud-watchdog` 15 秒就把它 `--resume` 拉回来。
  - ⚠️⚠️ **我用 tmux 的 `session_activity` 判闲置，杀掉了用户正在用的 `cc-hexingyang`**（#166）。
    那个时间戳没人 attach 时不更新，而这条坑 `SessionProbe.lastActivityOf` 的注释里是我自己写的。
    代码里改成 `max(tmux 活动, 转录 mtime)`、转录按 sessionId 找、busy/waiting 跳过、
    **查不到就不列（fail-closed）**。
  - **会话 cd 过就再也找不到转录**（#167）：改成按 `sessionId` 找（`~/.claude/sessions/`
    下那些 json 里有 `tmux` 和 `sessionId`，转录文件名就是 `<sessionId>.jsonl`）。
  - **`[Image: …]` 冒充用户说话**（#168）：那些消息带 `isMeta: true`，图片注解整条丢、
    其余画成「系统消息」。
  - **冷启动一块空看板**（#170）：`Recent` 落盘（带时间戳，>24h 不给），
    断线时摆上次那份并标「N 分钟前的状态」；**真的一无所有**才画 `BoardSkeleton`
    （骨架卡片跟真卡片同形同位，关了系统动画就不扫光）。
  - ⚠️ 踩了个 Kotlin 的坑（#169）：**块注释会嵌套**，注释里写 `sessions/*.json`
    那个 `/*` 开了内层注释，把整个文件吃掉了，报错却指向几十行外的 `{`。

- ✅ **官网换成真机截图 + 参考款 配色（2026-09-01）**：`yxi.keuury.com` 首屏那台手机、
  以及滚动走廊的四个场景（看板 / 审批 / 分组 / 体检），原来都是**手写 HTML 画的仿真界面**，
  现在换成**真截图** —— 安卓模拟器跑真 App、连演示账号 `demo@本机`、拍下来的
  `/var/www/yxi/shots/{board,approve,group,health}.webp`（600×1300，各 25~36 KB，共 110 KB）。
  相框改成 `aspect-ratio:1080/2340`，跟截图同比例，所以一个像素都没裁。配色是 参考款那套
  （`#346BF0` / `#4893FC` / `#BD99FE`，渐变只用在标题第二行和主按钮两处）。
  ⚠️ 加 `/shots/` 踩了两个坑，都记在 TROUBLESHOOTING：**#162** 那份 snippet 是白名单，
  不登记的路径一律 404；**#163** Cloudflare 连 404 都缓 4 小时，改文件名比清缓存省事。
  ⚠️ **截图怎么拍见 #164** —— 这台机器 steal 50%，模拟器一被点就 ANR，
  能改 prefs 就别点屏幕（比如切分组视图是写 `shared_prefs/yxi.xml` 的 `boardview:demo`）。
  ⚠️ 演示数据是**编的**：`demo` 用户的 `~/.claude/sessions/*.json`（会话状态，看板**优先读这份**）、
  `~/.cloud-status/*.json`、`~/.yxi/events.jsonl`（卡片上那句「在干什么」）、`~/.yxi/groups.json`
  和五个 tmux 会话，里面没有任何真实客户 / 项目名。**要重拍先刷新这些时间戳**，
  否则卡片上会写「18 小时前」。

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

- ✅ **iOS 版补齐到与安卓同级（2026-08-28）**：用户要「像素级复刻安卓版」。
  这一轮补上**文件浏览**（面包屑/跳转/多格式查看/保存到手机）、**实验室预览**
  （html·会动的 GIF·图片，保存原文件走 SFTP 而不是存预览那份，左滑置顶/删除）、
  **配置页可编辑**（json 先校验 + 改前备份两道保险）、**盯屏推流**（轮询兜底）、
  **TodoWrite 卡 + 顶栏「正在做」**、**本对话的模型/强度/模式**、**看 git diff**。
  逻辑层 **158 个测试全过**；界面在 CI 的 iPhone 16 模拟器上编译+截图。
  剩下没做的只有「分享到会话」（要单开 Share Extension）和小组件。
  ⚠️ 途中抓到两个隐蔽 bug，都记进 TROUBLESHOOTING：清 ANSI 的正则写成
  raw string 会被 ICU 吃掉整个模型名；`CODE_SIGNING_ALLOWED=NO` 顺带废掉钥匙串。

## iOS 版（`ios/`）—— 状态与硬约束

> ⚠️ **2026-09-02 起冻结（D24）**：只更新安卓。下面是冻结时的状态，仅供有人要重新捡起来时参考。

**目录**：`ios/`，与 `android/` 完全分开，互不引用。SwiftPM 双 target：
- `YxiKit` —— 纯逻辑 + SSH（Citadel / swift-nio-ssh）。**不依赖 UIKit/SwiftUI，Linux 上能编能测**
- `Yxi` —— SwiftUI app，**只有 Xcode 能编**

**验证边界**（务必分清，这是这份交付最要紧的一件事）：

| | 状态 |
|---|---|
| `YxiKit`（SSH / 密钥 / 主机存储 / 转录解析 / 屏幕解析 / 配置 / 实验室） | ✅ **真编译真测试**，158 条全绿（Linux 上跑） |
| `Yxi`（SwiftUI 全部界面） | ✅ **在 GitHub 的 macOS runner 上真编译、装进 iPhone 16 模拟器、逐页截图** |
| 本轮（0.9.64）补的界面：缩略图 / 光晕 / 滑入 / 按住说话 / 新图标 / 浅色默认 | ✅ **在用户的 Mac 上 `xcodebuild` 真编译过**（#196 的流程）；❌ 没进过模拟器、没截过图 |
| 真机 SSH / SFTP / tmux | ✅ **CI 里连的是真 sshd**（见下）——不是假数据 |
| 分发（装到用户手机上） | ❌ 仍然需要开发者账号或每 7 天重签，见下面三条硬约束 |
| 推送 | ❌ 未做 |

**iOS 这轮补齐了什么（2026-09-02，0.9.64 / build 108）**：附件缩略图（`AttachThumb.swift`，
`YxiKit.Attachments.parseRefs` 有测试）；对话页背景光 `ThinkingGlow.swift`（参数同安卓）；新条目滑入
（`.transition(.rise)`）；按住说话 `MicHold.swift`（系统 `SFSpeechRecognizer` 离线识别，**不扛安卓那个
150MB 模型**，只填输入框不发送；Info.plist 加了麦克风/语音两条）；App 图标（`App/Assets.xcassets`，
单张 1024）；配色改成浅/深两套动态色、**默认浅色**（设置页「外观」切，终端永远深底）。
⚠️ Linux 上的 xcodegen 现在会崩（#196），**工程在 Mac 上生成**。

在 Linux 上验逻辑层（秒级，日常就用它）：
```bash
export PATH=/opt/swift/usr/bin:$PATH
cd ios && swift build --target YxiKit && swift test
```

**界面怎么验（没有 Mac 也能验）**——`.github/workflows/ios.yml`：
macOS runner 编译 → 起 iPhone 16 模拟器 → 装上去 → **在 runner 上现起一台
真 sshd + tmux + 样本文件**，App 用**自己的密钥**连 `127.0.0.1`
（模拟器与 Mac 共用网络栈，等同安卓模拟器的 `10.0.2.2`）→ 逐页截图传回 artifact。

⚠️ 两个绊过的坑，改 CI 前先看：
- `CODE_SIGNING_ALLOWED=NO` 会把 entitlement 一起关掉 → 钥匙串全线 `-34018`
  → `Vault`/`KeyManager` 失效 → 认证层整个废掉。模拟器要 **ad-hoc 签名**
  （`CODE_SIGN_IDENTITY=-`）。TROUBLESHOOTING #135
- SwiftUI 那半**在 Linux 上编不了**，所以它的编译错误只有推上去才知道，
  一轮约 6 分钟。多攒几处改动再推，比一处一推划算。

**三条硬约束（`ios/docs/` 里有完整论证和出处）**：

1. **必须借一台 Mac** 才能编出 App。这是唯一的硬门槛，绕不过去。
2. **分发**：免费 Apple ID 签名 **7 天失效**（要连 Mac 重刷）；¥688/年 的账号一年刷一次。
   安卓那套「服务器放 APK 点一下就装」**没有等价物**；App 也**不能自己装更新**。
3. **手机主动响能做，而且零成本** —— 自建 `bark-server`（MIT）→ Apple APNs → App Store 的
   Bark App。**已复核**：`apns/apns_certs.go` 内嵌 Bark 自己的 APNs 私钥，所以不需要开发者账号。
   两个代价：① 锁屏上是 **Bark 的图标和名字**，跟你别的告警混在一栏；
   ② 那把私钥**全世界自建用户共用一把**，Apple 一旦吊销则所有自建 server 同时哑掉。
   免费路线下**通知上不能直接批准**（Bark 没有自定义按钮），要「点通知 → 开 Yxi → 按」。

⚠️ **服务器侧零改动**：`server/yxi-hook` / `install.sh` 一个字没动，安卓版不受影响。
推送是一个**可选的独立脚本**，装不装都行。

## ⚠️ 定位前提已经变了（2026-08-29 调研）

**官方自己做了「手机上管 Claude Code」。** Claude Code 2.1.251 内置
`claude remote-control`，帮助第一行原话：

> Remote Control - Control local sessions from **claude.ai/code or the Claude mobile app**

它在几块上**比 Yxi 强**，而且追不上（三周内 20+ 条相关更新）：
原生对话/工具卡片（不用解析 TUI）、远程批权限**无限期挂起 + 断线补发**
（我们架在 hook 的 600 秒上，是硬上限）、跨机器统一会话列表。

**但它硬编码排除了一批人**，这是 Yxi 结构性的市场（二进制原话，已亲自核实）：

- 「Remote Control is only available with **claude.ai subscriptions**.」
- 「connected through an **enterprise cloud gateway** … does not support Remote Control」
- 「(`_CLAUDE_CODE_ASSUME_FIRST_PARTY_BASE_URL` does not apply to Remote Control.)」
  ← **连绕过的后门都专门堵了**，说明是主动排除不是遗漏

即：**用第三方中转 key / Bedrock / Vertex / 企业网关的人，官方那套永远用不了。**
（v2.1.196 起的策略。本项目的用户自己就在这个人群里：站长机走第三方中转、
手机是 GMS 默认关闭的荣耀。）

**官方还明确不做的**：真终端 / 任意 tmux 全景（它只给「一个 Claude Code 会话的窗口」，
没有 shell）、多 agent、不经过 Anthropic 服务器（RC 期间 transcript 全量存它那儿）。
另：官方文档写死 **one remote session per interactive process** —— 「一屏看清所有机器
所有会话谁在等你」它结构上给不了。

### 由此得出的方向（尚待用户拍板）

1. **定位从「Claude Code 手机客户端」改成「SSH 终端 + 多 agent 指挥台」** ——
   终端那块（G2/G8/G9）已经做完，且是官方明确不做的，是唯一不会被抹掉的沉没投入。
2. **目标用户 = 官方明文排除的那批**：中转 key / Bedrock / 内网 / 受限地区 / 非 GMS。
   对他们 Yxi 不是「更好」，是**唯一选项**。
3. **别再往正面重合处投工时**：对话渲染精细度、工具卡片完整度、权限转发可靠性 ——
   保持够用即可。尤其那条 600 秒的远程审批，应从「卖点」降级成「够用的兜底」。

⚠️ **未验证、且只有用户能验的**：他的荣耀手机（非 GMS + 大陆 IP）到底能不能用官方那套。
这一次实测能一次性定下方向 —— 过不去，则第 2 条从「一个细分市场」升级成「Yxi 存在的
全部理由」。

## 决策记录（用户拍板过的，按时间倒序 —— 改动前先看这里，别推翻已定的）

| # | 决定 | 理由 / 出处 |
|---|---|---|
| D24 | **iOS 冻结：以后只更新安卓，不再给 iOS 补功能；iOS 没改动就不动它的版本号** | 用户 2026-09-02 拍板（「以后只更新安卓的吧，别更新苹果的了，苹果的版本号也别变了如果没更新的话」）。iOS 停在 0.9.66 / build 110，代码留着、CI 留着，**新功能不再做 iOS 版**，`ios/project.yml` 的版本号也不跟着安卓走。 |
| D23 | **设置页要显示版本号并能主动查更新** | 用户补充。现在的更新检查是**进会话看板时被动跑一次**，没有主动入口，也看不到自己装的是哪一版。<br>⚠️ 三种结果必须分清：有新版本 / 已是最新 / **连不上没查到** —— 把「没查到」显示成「已是最新」是在骗用户。<br>版本号从 `BuildConfig` 读，不写死。 |
| D22 | **底部导航只在「外层」出现：会话 · 主机 · 设置** | 用户拍板（方案 A / 三格）。<br>**进工作区就整屏让位** —— 终端最缺竖向空间，而软键盘弹起时底部栏会和键盘工具条、系统手势条挤成四层。<br>模式切换 `[终端│对话│文件]` **留在顶部不动**：它是「看哪一面」，跟底部栏的「在 app 的哪儿」是两条轴，放一起会打架。<br>「会话」页带主机下拉 → 换主机不用退出去。 |
| D21 | **补上 D18/D17：悬浮排列会话切换**（先做这个，再做底部栏） | 用户拍板。**换会话是最高频也最费劲的动作**（返回→看板→在 20 个里滚→点），比加导航栏收益大。<br>而且它是早就批过的稿子，不用重新决策。<br>⚠️ 连一并要改：`Workspace` 目前按 (host, session) 记连接，改成**按 host 记** —— 同一台机器上换会话就不用重连。 |
| D20 | **SSH 密钥用 ed25519，靠 BouncyCastle 支撑** | Android 的 JCA 没有 `Ed25519` 签名算法，jsch 认证必失败。<br>试过 `net.i2p.crypto:eddsa`（算法名对不上，无效）和退回 ECDSA（可行但没必要）。<br>**注册 BouncyCastle 即解决**，代价 APK +3 MB。TROUBLESHOOTING #12 |
| D19 | **用量显示，按服务器关联** | 数据源 = `ccusage`（`remote-dev-station/bin/cc-quota` 已在用）读本机 `~/.claude`，**天然按服务器分，零关联工作**。转录里每条 assistant 消息自带完整 `usage` + `model` → **本地算钱，不调 API**。<br>两层：主机列表紧凑条 / 会话看板详情卡。中转站余额记 P2（token 留服务器，不进 App）。PRD 附录 K |
| D18 | **悬浮排列的会话切换** | 像手机后台：卡片轮播 + `capture-pane` 实时缩略预览。与列表视图并存（`[列表│悬浮]`）。<br>⚠️ **上滑 = 归档，不杀 tmux 会话**（不可逆操作绝不能是滑动手势；杀会话要长按+二次确认）。PRD 附录 J.3 |
| D17 | **滑动切卡的视差过渡** | 三层不同速度：焦点卡 1.0x / 邻居 0.86x+缩放+压暗 / 背景 0.3x。`ViewPager2` + 自定义 `PageTransformer`，无额外依赖。<br>⚠️ **必须尊重系统「移除动画」设置**——对前庭障碍用户视差会引发不适。PRD 附录 J.2 |
| D16b | **视觉方向定为 Material 3 深色** | 用户说想学 参考款。参考款 好看是因为它是 M3 Expressive 的样板实现，而 M3 是 Google 公开给第三方用的设计系统 → 学 M3 正当，**未克隆 参考款 界面**。PRD 附录 J.1 |
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
- ⭐ **`tuchg/Lucarne` 的 `agent-sessions` crate 比我们的 Chat View 设计更周到**（支持 claude/codex/copilot/cursor/参考款/grok/pi 七家）：原始层与语义层严格分开、`Unknown` 有升级纪律、shell 语义在解析层就抽出来。**三条都该抄**，见 PRD 附录 B.4

## GitHub 耦合
- 仓库：**`liang-senbei/yxi`（私有）**。⚠️ `/root/src/CLAUDE.md`（含明文密码，权限 600）**在父目录、不在本仓**，不会被提交。
- **深耦合 `remote-dev-station`**（`/root/src/workspace/remote-dev-station`）：复用它的 `bin/cc-state`、`hub/`、`bin/cloud-sesslist`、`bin/cc-quota`；它的 `phone/README.md` 是**原版 Moshi** 的配置 runbook（本项目是它的替代品，不是补充）。
- 端口需避开 `Anthropic-Inspector`（80/443/7800）。

## 已知的机器级敞口（与本项目无关，但更要紧）
本机 sshd 同时开着 root 登录和密码认证，公网 22 端口每天被僵尸网络爆破数千次
（`journalctl` / `auth.log` 里可查，前十来源合计三千余次失败）。
用户手机端已在用**公钥**认证 → **关掉密码认证不影响使用**。已告知用户，由其决定。
> 具体主机地址见 `/root/src/CLAUDE.md`（不入库）。
