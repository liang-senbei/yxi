# handover · Yxi

## 基础信息
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
| `/run/yxi.sock` | 【本项目自有】hook ↔ 守护的 unix socket |
| Android Keystore 里的 SSH 私钥 | 【App 内·硬件保护】导不出来；撤销 = 服务器删 `authorized_keys` 一行 |
| `/root/inbox/base.apk`、`/root/inbox/apk/` | 【参考】原版 Moshi Android 3.10.0 及其解包，逆向证据来源 |

## 开源参照（PRD 附录 B 有全表）
- **Phase 1 直接抄**：`GlassHaven/Haven`（Kotlin 现代 Android SSH 客户端，AGPL，活跃）· `connectbot/connectbot`（Apache-2.0，可直接复用代码）
- **终端控件**：`termux/terminal-view` + `terminal-emulator`（已是独立 gradle 模块）→ **可能不需要 WebView+xterm.js**
- **同类思路**：`tuchg/Lucarne`（Rust，通知/审批走微信+Telegram，不做 App，零 hook）——取舍与我们相反，见 PRD 附录 B.3/B.4

## GitHub 耦合
- 仓库：**`liang-senbei/yxi`（私有）**。⚠️ `/root/src/CLAUDE.md`（含明文密码，权限 600）**在父目录、不在本仓**，不会被提交。
- **深耦合 `remote-dev-station`**（`/root/src/workspace/remote-dev-station`）：复用它的 `bin/cc-state`、`hub/`、`bin/cloud-sesslist`、`bin/cc-quota`；它的 `phone/README.md` 是**原版 Moshi** 的配置 runbook（本项目是它的替代品，不是补充）。
- 端口需避开 `Anthropic-Inspector`（80/443/7800）。

## 已知的机器级敞口（与本项目无关，但更要紧）
本机 sshd 同时开着 root 登录和密码认证，公网 22 端口每天被僵尸网络爆破数千次
（`journalctl` / `auth.log` 里可查，前十来源合计三千余次失败）。
用户手机端已在用**公钥**认证 → **关掉密码认证不影响使用**。已告知用户，由其决定。
> 具体主机地址见 `/root/src/CLAUDE.md`（不入库）。
