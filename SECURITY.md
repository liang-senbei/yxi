# Yxi 安全审计与红队 · 2026-09-04

一次针对手机 App 侧的完整审计，配一轮**在模拟器上真打**的红队验证。
登录中心（`logto_yxi`）那半边由对方出报告，本文只管 App 与下载站。

**结论**：一条**严重**（命令注入）、一条**中危**（实验室页面能开 WebSocket 出网），
都已修复并**实测封死**；其余中低危一并处理。剩一条已知缺口写在最后，是**有意暂缓**不是漏掉。

---

## 0. 威胁模型（先说清楚在防谁）

这个 App 的全部意义是「**人在手机上给 agent 把关**」。所以最要命的不是外人打进来，而是：

> **服务器上的 agent 被提示词注入**，反过来驱使手机在服务器上执行**未经审批**的命令。

那等于把这个产品存在的理由整个绕开。下面的第 1 条正是这个。

其次是：实验室页面（**agent 写的 HTML**）在用户手机上跑；下载站被打穿后推恶意包；手机丢了之后本地数据。

---

## 1. 【严重】会话名一路流进 `tmux -t '…'` —— 已修，已实测

**问题**：会话名有两个来源，**两个都不可信**：

| 来源 | 谁能写 |
|---|---|
| `tmux list-sessions` | 服务器上任何能开 tmux 的人：`tmux new -s "x'; rm -rf ~; :'"` |
| `~/.yxi/events.jsonl` | **服务器上的 agent 自己**（钩子往里追加事件） |

这个名字会被拼进 `tmux capture-pane -t '…'`、`send-keys -t '…'` 等一堆命令。
更糟的是**事件那条路不需要用户点任何东西**——常驻的盯梢服务收到事件就自动去探屏。

**根因**：同一个转义函数在四个文件里各抄了一份（`ConfigRemote.shq` / `Dirs.q` / `Connect.shq` /
`Tickets.shellSingleQuote`）。抄了四份的结果是「谁想起来谁用」——**26 个插值点漏网**。

**修法**（两道，都要）：

1. 新建 `ssh/Shell.kt`，**全 App 唯一**的转义 `q()`；26 个插值点全部改成走它。
2. 两处信任边界（`SessionProbe` 解析会话列表、`EventService` 解析事件流）用 `safeName()`
   **整条丢掉**不合规的名字（fail-closed）。第一道防「我漏了一个点」，第二道防「以后新加一个点又忘了」。

**红队实证**（不是推理，是在模拟器上对着真服务器打的）：

先证明载荷是真的 —— 裸插值下 `tmux capture-pane -p -t 'evil';touch /tmp/yxi_pwn_a;''`
确实创建了 `/tmp/yxi_pwn_a`。然后：

| 打法 | 对照组（合法名字） | 恶意载荷 | 载荷有没有执行 |
|---|---|---|---|
| **会话列表**：在服务器上 `tmux new -s "evil';touch …;'"` | 服务器 31 个会话 → 看板 30 个 | **少的正好是它** | ❌ 没有标记文件 |
| **事件流**：往 `events.jsonl` 追加投毒事件 ×7 | `cc-e2e-ok` → 弹出「e2e-ok 需要你」 | 同名 + 载荷 → **不弹、不进等待列表** | ❌ 没有标记文件 |

⚠️ 两组的关键在于**有对照**：先证明这条路当时是通的（合法名字进得来），恶意的没进来才说明是被挡的，
不是链路本来就断。中间确实撞见过一次「两条都没反应」，查下来是通道断了 —— 那次结果作废重打。

---

## 2. 【中危】实验室页面能开 WebSocket 出公网 —— 已修，已实测

实验室里的 HTML 是**服务器上 agent 产出的**，在 App 内的 WebView 里跑。原来的两道防线：

- `settings.blockNetworkLoads = true`
- `WebViewClient.shouldInterceptRequest` 把非 `data:` 的请求全部喂空应答

推一张自检页上去实测（判据是**服务器侧监听有没有收到请求**，不看页面里的回调）：

| 通道 | 结果 |
|---|---|
| 外链 `<img>` | ✅ 挡住 |
| `fetch` / XHR / `sendBeacon` / `<script src>` | ✅ 挡住（拦截器喂空应答，请求根本没发出去） |
| **`new WebSocket('wss://…')`** | ❌ **`onopen` 触发了** —— 对方回了 HTTP 101，真连上了公网 |

**根因**：`shouldInterceptRequest` **不为 WebSocket 回调**，`blockNetworkLoads` 也不管它。

**修法**：`ui/WebFence.kt` —— 加载前往 HTML 里插一条 CSP `<meta>`，`connect-src 'none'`
（fetch / XHR / WebSocket / EventSource / sendBeacon 全归它管）。
插入位置取「`<head>` 之后」与「第一个 `<script` 之前」**靠前的那个**（恶意页面会把脚本写在 `<head>` 前面）。

**修后复测**：同一张页，两个外网 `wss://` 全部 `onerror`。

---

## 3. 其余已修项

| 问题 | 影响 | 修法 |
|---|---|---|
| 通知整条标成 `VISIBILITY_PUBLIC` | 这个标志的语义是「永远显示」，**压过用户系统里设的「仅解锁后显示敏感内容」**；配上黑屏点亮，Claude 最后那段话和项目绝对路径摆给旁边所有人看 | 改 `VISIBILITY_PRIVATE` + 只给一个「谁在等你」的 `publicVersion`；渠道 `lockscreenVisibility` 同改 |
| `allowBackup` 默认 true | 主机、密钥引用、会话数据可被 adb backup 捞走 | 显式 `false` |
| 账号 token 明文落盘 | 手机丢了即泄露 | 走 `ssh/Vault` 加密（Keystore） |
| **更新包不校验** | 下载站被打穿 = 推什么装什么 | 清单里带 `sha256`，下载时流式校验；再比 **签名者证书**（`apkContentsSigners`）跟当前 App 一致 |
| 调试面板 `confirmNewHost` 默认 true | 等于默认关掉 TOFU 指纹确认 | 改 `false` |
| 剪贴板 | 复制的内容进剪贴板历史 | 加 `EXTRA_IS_SENSITIVE` |
| OIDC | 登出后 token 仍有效 | 调撤销端点，清 `verifier`/`state`；`state` 严格校验 |
| **不混淆** | 类名、`.kt` 文件名全在包里，逆向零成本 | 开 R8 + 资源压缩：类名 136→6、`.kt` 文件名 3→0，包 52MB→41MB |
| **签名密钥是临时的** | — | 换成正式 keystore（RSA 4096 / 30 年 / 随机口令），密钥与口令在仓库外 `~/.secrets/`（600），已异地备份 |

⚠️ 换签名密钥 = **签名变了**，用户必须**卸载重装**，服务器上的公钥也要重新贴。

---

## 4. 已知缺口（有意暂缓，不是漏掉）

**装机脚本 `bootstrap.sh` 没有校验**。`agent/Setup.kt` 的 `fetchScript()` 从
`https://yxi.keuury.com/bootstrap.sh` 取脚本，只做了形状检查（`#!` 开头、含 "bootstrap"、
不含 heredoc 结束符），**没有哈希/签名**。取到之后这段脚本是在**客户的服务器上以 root 跑**的。

- **为什么现在不算紧急**：只在「首次装机」这一步用到；要利用它得先拿下 hk13 或它的 TLS，
  而那种情况下 APK 本身也在同一台机器上（不过 APK 有签名校验兜底，脚本没有 —— 所以脚本是更弱的一环）。
- **推荐的修法（比加哈希更好）**：**把 `bootstrap.sh` 打进 APK 当资源**，别从网上取。
  这样它自动被 APK 签名覆盖，不用管密钥、也不用每次改脚本发一次 App
  （脚本本来就跟 App 一起由 `install.sh --publish` 发，版本天然对齐）。
  次选才是「发一份 `bootstrap.sha256` + App 里校验」——那个仍然挡不住「站被打穿则两份一起换」。

---

## 5. 复现这些验证的办法（下次改动后照着再跑一遍）

1. **命令注入**：`tmux new -s "cc-x';touch /tmp/pwn;'"` → 看板会话数应当比服务器少 1，且 `/tmp/pwn` 不存在。
   往 `~/.yxi/events.jsonl` 追加一条 `session` 带同样载荷的事件 → 不弹通知、`/tmp/pwn` 不存在。
   **必须同时打一发合法名字的对照**，否则证明不了链路是通的。
2. **WebView 出网**：本机起个监听（`python3 -m http.server`），推一张往它发 fetch/img/WS 的实验室页，
   **看监听有没有收到请求**（别看页面里的回调，`fetch` 被喂空应答一样会 resolve）。
3. **混淆规则**：`unzip -Z1 app.apk 'lib/*'` 列出所有 `.so`，每个都要有对应 keep 规则；
   然后**真机跑一遍** SSH 连接 / 终端 / 语音 / 生成密钥这四条路（见 TROUBLESHOOTING #234）。
