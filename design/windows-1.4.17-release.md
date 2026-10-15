# Windows 1.4.17 候选发布记录

状态：已发布（2026-10-15 13:33:16 UTC），正式下载源为 1.4.17，上一版 1.4.16。发布记录见文末。用户 10-15 在 Windows 电脑上测过托盘右键菜单：中文正常、点别处收起、「退出」可用。用户 10-15 同意推 GitHub、版本定为 1.4.17、默认仍为经典风格、Code 风格保留「预览」，由 CI 出 Windows 包；托盘右键菜单由用户在 Windows 电脑上实测，测过之后再决定是否上线（上线要写 hk13 的下载目录，届时另行确认）。

在 claude/code-theme 上建立独立分支 claude/windows-release-1.4.17。版本和内置更新说明改为 1.4.17。

范围：1.4.16（340207e9）之后 claude/code-theme 上的全部提交：Code 风格（预览）的对话流（M3）、右侧面板 / 快捷键 / 任务切换 / 逐行评论 / 定时任务页 / 按文件夹记住权限 / 打开方式 / 用量卡 / 斜杠菜单 / 分支改动条 / 自定义页与技能 / Code 设置弹窗（M4a–M4q），本机对齐 P4–P6，Windows 托盘右键菜单改用 Swing 画（中文乱码），以及 1.4.17 发版前检查修的三处 Windows 问题（升级系统后本机会话按系统家族认领、本机 git 卡住时能超时并连子进程一起结束、.cmd 编辑器不接带 cmd 特殊字符的目录）。macOS 相关代码按 os.name 分支，在 Windows 上不生效。不在范围：远程主机上外部 Claude Code 会话的列表（M4s，开发中）、macOS 发版（desktop.yml 的 macos 任务会顺带出一个未签名的 dmg，不发布）。

本机验证（Mac mini，10-15）：见下方「本机验证」。36c2d92a 之前的提交在 Windows 电脑上验过（经典 12 张 0 差异、Code 130 张）；之后的提交只在 macOS 上验过，Windows 上第一次运行就是这次 CI。

候选必须通过 Windows CI 的 Workbench core regression 和真装真启动检查，并经用户在 Windows 上实测托盘右键菜单（100% 和 125–150% 缩放、Yxi 窗口隐藏且别的程序在前台：能弹出、点别处收起、「退出」可用、中文不乱码），才可上传。发布沿用 1.4.16 流程（yxi-交接 §6）：备份现有下载源，nupkg 先上并核对公网 hash，再依次替换 Setup、RELEASES、release-notes.json，最后切 releases.win.json；已发布的旧包不得覆盖。发布脚本的参数要按 1.4.17 改（版本、run id、artifact、release-notes 的 sha256）。

已知限制（写进了内置更新说明）：Code 风格仍是预览、部分面板沿用经典样式；Ctrl+Shift+F 与微软拼音简繁切换撞键；对话字号 / 宽度的非默认档位待与参考界面核对。

## 本机验证

Mac mini（macOS，隔离 profile，真实 ~/.config/yxi 前后都不存在、钥匙串监视无调用），候选工作树 = 26f816cd + 版本号与更新说明：全套 suite 43 个测试类无失败；门禁（同 Windows CI 的 Workbench core regression 19 类）115 例无失败；经典 12 张与基线 0 差异；风格来回切换 22 步全 0 差异；Code 风格 252 张全部正常，与上一基准（code-m4r，240 张）相比 238 张逐像素相同，变的 2 张是设置页（换成 Code 风格的设置弹窗），新增 12 张（设置弹窗外观 / 常规 / 通知下拉 / Esc、对话加宽、大字号）。这些都在 macOS 上；Windows 上的第一次运行是这次 CI。

desktop.yml 的 Windows 任务这次多一步「Code style unit tests」：在 Windows 上跑 1.4.16 之后新加的 19 个测试类，先不挡打包，看日志。

## CI

Desktop 工作流 run 38725108568（workflow_dispatch，分支 claude/windows-release-1.4.17，head 2163aaab）。Windows 任务成功：托盘中文字体检查、SSH 密钥更新契约、Workbench core regression（19 类 115 例，无失败、错误和跳过）、jpackage + uber jar、Velopack 从 Yxi.cfg 读到 1.4.17（Yxi-1.4.17-full.nupkg 330688656 字节）、冒烟 smoke ok、真装真启动 smoke ok。新加的「Code style unit tests」（不挡打包）在 Windows 上 87 例：1 例失败、1 例跳过——失败的是 OpenInTest 的测试假设（Windows 没有可执行位，canExecute 对任何文件都为真；产品在 Windows 上只找 .cmd / .exe，不受影响），测试已在 claude/code-theme 上改；跳过的是 LocalGitTest 里要 python3 的那一段（按设计 Windows 上跳过）。产物：Yxi-windows，artifact 11967318688，931264050 字节，digest sha256:c8813304eb4a568fa4fc59274f86f4fba5ea7cba257b5bc8637c1f0ee0888295。

同一次运行的 macOS 任务（未签名 dmg，不在本次发布范围）在冒烟里失败：内嵌浏览器（JCEF）在 ad-hoc 签名下报签名校验错误，随后「Native browser shutdown did not finish」。留给 Mac 发版处理。

## 发布记录

用户在 Windows 上测完托盘后同意上线。流程同 1.4.16（yxi-交接 §6），在 Mac mini 上准备、在 hk13 上执行，脚本只改了参数（版本、run、source、artifact、候选包大小和 sha256、release-notes 的 sha256、上一版 1.4.16）：

- backup：线上原是 1.4.16，四个文件存进 pre-publish/，hash 与 1.4.16 发布记录一致（Setup e35f19eb…、releases.win.json d9058d54…、RELEASES 629eaf75…、release-notes.json b261286b…）。
- candidate.zip：artifact 11967318688 经约 1 分钟有效的签名地址直接下到 hk13（令牌和签名地址都不进命令行），931264050 字节，sha256 c8813304eb4a568fa4fc59274f86f4fba5ea7cba257b5bc8637c1f0ee0888295，与 GitHub digest 一致。
- verify：包大小 / sha256、ZIP 成员路径和 CRC、nupkg 里 Yxi.cfg 的版本 1.4.17、RELEASES 只指向 1.4.17 的 nupkg（sha1、大小一致）、validate_release.py（取自 2163aaab，摘要未变）比对线上 1.4.16 清单、release-notes.json 取自 2163aaab 的 git blob（LF，16254 字节，sha256 6fcfa45f…），全过。
- promote：拿 desktop-publish.lock，再核 run / jobs（completed、success、head 2163aaab、Workbench core regression 与真装真启动成功）；nupkg 硬链接进下载目录，再依次换 Setup.exe、RELEASES、release-notes.json，最后 releases.win.json，每个都从公网完整取回核 sha256（.exe 带唯一 query 回源）。输出 PUBLISHED 1.4.17。1.4.15、1.4.16 的 nupkg 保留，assets.win.json 沿用。

| 文件 | 大小 | sha256 |
| --- | ---: | --- |
| Yxi-1.4.17-full.nupkg | 330688656 | cdb27051195552ecc1cf311ee0541e8ed690cc99dd1476795a7e78aa80ee41da |
| Yxi-win-Setup.exe | 335210640 | 70bc8a6011fd41bf9570a02655f8892fc11b80802e6da1b484a9d22bb68aaa64 |
| RELEASES | 75 | e173c26332f15c60084cac58fab513a9bdbcec79179a1cf942052b9bb01cc611 |
| release-notes.json | 16254 | 6fcfa45f1c67292b784a0c8e7d336b040a68077713a3ca00803b38c895a097c8 |
| releases.win.json | 243 | 7251f0bba087cdff54e118e213055b3eafe8b1baa9df47be42e393f01654158e |

发布后从 Mac 直接取公网：releases.win.json 是 1.4.17、release-notes.json 首条是 1.4.17。已装的客户端启动 10 秒后、之后每 6 小时检查一次，会自动升到 1.4.17。Cloudflare 按 .exe 缓存，最多 4 小时内个别节点可能还给 1.4.16 的 Setup，装上后会自动升级。

证据目录：hk13 `/root/src/workspace/yunxi/windows-release/1.4.17-run38725108568/`，含 run.json、jobs.json、artifact.json、validation-markers.txt、validate_release.py、notes/、candidate.zip（0600）、artifact/、pre-publish/、backup-result.json、verify.log、verified-files.json、promote.log、deployment-result.json 和三个脚本。

回退：先用 pre-publish/releases.win.json 让旧 feed 停止推荐 1.4.17，再恢复 release-notes.json、RELEASES 和 Setup.exe；保留全部 nupkg。feed 回退不会让已升级的客户端降级。
