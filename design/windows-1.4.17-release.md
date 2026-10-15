# Windows 1.4.17 候选发布记录

状态：候选（未发布）。线上下载源仍是 1.4.16。用户 10-15 同意推 GitHub、版本定为 1.4.17、默认仍为经典风格、Code 风格保留「预览」，由 CI 出 Windows 包；托盘右键菜单由用户在 Windows 电脑上实测，测过之后再决定是否上线（上线要写 hk13 的下载目录，届时另行确认）。

在 claude/code-theme 上建立独立分支 claude/windows-release-1.4.17。版本和内置更新说明改为 1.4.17。

范围：1.4.16（340207e9）之后 claude/code-theme 上的全部提交：Code 风格（预览）的对话流（M3）、右侧面板 / 快捷键 / 任务切换 / 逐行评论 / 定时任务页 / 按文件夹记住权限 / 打开方式 / 用量卡 / 斜杠菜单 / 分支改动条 / 自定义页与技能 / Code 设置弹窗（M4a–M4q），本机对齐 P4–P6，Windows 托盘右键菜单改用 Swing 画（中文乱码），以及 1.4.17 发版前检查修的三处 Windows 问题（升级系统后本机会话按系统家族认领、本机 git 卡住时能超时并连子进程一起结束、.cmd 编辑器不接带 cmd 特殊字符的目录）。macOS 相关代码按 os.name 分支，在 Windows 上不生效。不在范围：远程主机上外部 Claude Code 会话的列表（M4s，开发中）、macOS 发版（desktop.yml 的 macos 任务会顺带出一个未签名的 dmg，不发布）。

本机验证（Mac mini，10-15）：见下方「本机验证」。36c2d92a 之前的提交在 Windows 电脑上验过（经典 12 张 0 差异、Code 130 张）；之后的提交只在 macOS 上验过，Windows 上第一次运行就是这次 CI。

候选必须通过 Windows CI 的 Workbench core regression 和真装真启动检查，并经用户在 Windows 上实测托盘右键菜单（100% 和 125–150% 缩放、Yxi 窗口隐藏且别的程序在前台：能弹出、点别处收起、「退出」可用、中文不乱码），才可上传。发布沿用 1.4.16 流程（yxi-交接 §6）：备份现有下载源，nupkg 先上并核对公网 hash，再依次替换 Setup、RELEASES、release-notes.json，最后切 releases.win.json；已发布的旧包不得覆盖。发布脚本的参数要按 1.4.17 改（版本、run id、artifact、release-notes 的 sha256）。

已知限制（写进了内置更新说明）：Code 风格仍是预览、部分面板沿用经典样式；Ctrl+Shift+F 与微软拼音简繁切换撞键；对话字号 / 宽度的非默认档位待与参考界面核对。

## 本机验证

Mac mini（macOS，隔离 profile，真实 ~/.config/yxi 前后都不存在、钥匙串监视无调用），候选工作树 = 26f816cd + 版本号与更新说明：全套 suite 43 个测试类无失败；门禁（同 Windows CI 的 Workbench core regression 19 类）115 例无失败；经典 12 张与基线 0 差异；风格来回切换 22 步全 0 差异；Code 风格 252 张全部正常，与上一基准（code-m4r，240 张）相比 238 张逐像素相同，变的 2 张是设置页（换成 Code 风格的设置弹窗），新增 12 张（设置弹窗外观 / 常规 / 通知下拉 / Esc、对话加宽、大字号）。这些都在 macOS 上；Windows 上的第一次运行是这次 CI。

desktop.yml 的 Windows 任务这次多一步「Code style unit tests」：在 Windows 上跑 1.4.16 之后新加的 19 个测试类，先不挡打包，看日志。

## CI

（跑完补）
