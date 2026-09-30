# Windows 1.4.16 候选发布记录

状态：已发布（2026-09-30 12:15:45 UTC），正式下载源为 1.4.16，上一版 1.4.15。发布记录见文末。

在 claude/code-theme 上建立独立分支 claude/windows-release-1.4.16。版本和内置更新说明改为 1.4.16。

范围：1.4.15（e744ba25）之后已提交的本地 Claude 图片输入、剪贴板贴图、跨运行器交接基础层（无界面入口），加上界面风格切换：默认经典，Code 风格标「预览」。macOS 相关代码按 os.name 分支，在 Windows 上不生效；desktop.yml 的 macOS 打包任务不在本次范围，工作树里的改动未提交。

本机验证：经典风格与改动前逐像素比对 12/12 张 0 差异；Code 风格截图 28 场景 56 张与基准一致；风格来回切换状态保留、项目树用例通过。验证都在隔离的临时 profile 里跑，真实 ~/.yxi/prefs.json 修改时间未变。工作台回归没在本机跑，由 Windows CI 的 Workbench core regression 把关。

候选必须通过 Windows CI 的 Workbench core regression 和真装真启动检查，才可上传；下载到产物不等于可发布。发布沿用 1.4.15 流程：备份现有下载源，nupkg 先上并核对公网内容 hash，再依次替换 Setup、RELEASES，最后切 releases.win.json。已发布的 1.4.14、1.4.15 包不得覆盖。

线上 release-notes.json 停在 1.4.14，1.4.15 客户端的更新日志显示「暂未提供」。本次发布一并换成新版内置说明（含 1.4.15 与 1.4.16 条目）。

不在本次范围：图片的 Windows 原生文件框与真实剪贴板整链、原生历史图片展示、草稿持久化与快照回收；跨运行器交接的审阅界面和完整产品链；Code 风格下本机对话面板的外观、停止与排队、浮层关闭后的焦点回收；macOS 发版（签名、公证、钥匙串实机验收）。

## 发布记录

CI：Desktop 工作流 run 36711166899（workflow_dispatch，分支 claude/windows-release-1.4.16，head 340207e959b0f78a4f6c7cac25e1aca05c7bb31f）。Windows Setup.exe + jar 成功；Workbench core regression 为 19 个类、111 个用例，无失败、错误和跳过；Velopack 从 Yxi.cfg 读到 1.4.16；真装真启动 Setup 与 Yxi.exe --smoke 退出码都是 0，并有 smoke ok，凭据和浏览器原生检查所在步骤通过。

产物：Yxi-windows，artifact 11094264314，927272617 字节，GitHub digest sha256:794fcf5d9e57a7c45b231e12d02217465af3c1364f64c48ae0161abad56d3909。在 hk13 上下载后核对了大小和 sha256，解压前检查了成员路径、链接和 CRC。validate_release.py 取自 340207e9，通过比对线上 1.4.15 清单；nupkg 内 Yxi.cfg 的版本为 1.4.16；RELEASES 只指向 1.4.16 的 nupkg，sha1 和大小一致。

| 文件 | 大小 | sha256 |
| --- | ---: | --- |
| Yxi-1.4.16-full.nupkg | 329358324 | eac34a590f504d8b072e8e8ce854ff680e616da23bd927574a68f2aae8009c75 |
| Yxi-win-Setup.exe | 333880308 | e35f19eb94e024aeaea94fa472d61d621f5ba79f3636c83bea379f467b76d4a6 |
| RELEASES | 75 | 629eaf75896f359d8567d3b8b4a0516a8e794ececf4b278c320343103a8bd639 |
| release-notes.json | 13362 | b261286b3d85f1145da2cdf399ac2dfcb6ebf6303ce2a0ab83269584f078b943 |
| releases.win.json | 243 | d9058d54f8e5fe30317a2fadb00d429404326633f62799cf8aff9fbef65aa495 |

上线顺序：nupkg 硬链接进下载目录，然后依次是 Setup.exe、RELEASES、release-notes.json，releases.win.json 最后换。每个文件经临时文件、fsync 原子替换，再从公网完整取回核对 sha256。1.4.14、1.4.15 的 nupkg 未改动。assets.win.json 与 1.4.15 一样沿用旧文件。

release-notes.json 不在 CI 产物里，取自 340207e9 的 git blob（LF），单独放在证据目录的 notes/。线上原来停在 1.4.14 的说明已换掉，1.4.15 客户端打开更新日志也能看到对应条目。

缓存：Cloudflare 按扩展名缓存 .exe（源站 max-age=14400）。Setup.exe 的公网核对带唯一 query 回源。发布后直连地址，香港节点 EXPIRED、达拉斯节点 MISS，都已是新包。其他节点若仍有旧副本，最多 4 小时内可能下到 1.4.15 的 Setup，装上后会自动升级到 1.4.16。清单、日志和 nupkg 都是 no-cache。

证据目录：hk13 `/root/src/workspace/yunxi/windows-release/1.4.16-run36711166899/`，含 run.json、jobs.json、artifact.json、validation-markers.txt、verified-files.json、pre-publish/（1.4.15 的四个文件）、backup-result.json、deployment-result.json、promote.log、candidate.zip（0600），以及 backup/verify/promote 三个脚本。

回退：先用 pre-publish/releases.win.json 让旧 feed 停止推荐 1.4.16，再恢复 release-notes.json、RELEASES 和 Setup.exe；保留全部 nupkg。feed 回退不会让已升级的客户端降级。
