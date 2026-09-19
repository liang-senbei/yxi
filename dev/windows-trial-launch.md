# Windows 试用包辅助启动说明（windows-trial-launch.ps1）

**用途**：手动打开试用包时，让应用只用交付目录内的独立 profile——不碰机器上已有的 Yxi 配置和单实例锁，不动已安装的 Yxi，不写全局/用户环境变量。

## 用法

```
pwsh -File dev\windows-trial-launch.ps1 -ExePath <交付目录>\Yxi\Yxi.exe
```

可选 `-ProfileName <名>`（默认 `trial-profile`，建在交付目录内；**删掉该目录 = 完全清理**）。

脚本只改启动那一刻的进程环境（`HOME` / `APPDATA` / `LOCALAPPDATA` / `user.home` 四项指向包内 profile），`Yxi.exe` 启动后立即还原父进程环境；不设置全局或用户级环境变量。`ExePath` 若指到 Program Files 下会直接拒绝（防误对已装版本使用）。

## 本次试用包（首包）事实清单

| 项 | 值 |
|---|---|
| 应用源码 | `codex/windows-workbench-review@3152af0` |
| 版本号 | 仍是 **1.2.0 开发快照**（packageVersion 单源），**非正式发布** |
| 首包不含 | `d43449e` / `ee677ae` 等后续改动（后续出包以随包新说明为准） |
| Windows 真机 | **未验**——本包在 Linux 侧构建、脚本仅静态自查，真机行为以首跑为准 |
| jar SHA256 | `fecaf5f0d63237e5c973318d965d22e964a0a3eb3874e530c3a83a8caedff5db` |

## 边界（脚本刻意不做的事）

- 不写全局/用户环境变量；不动已安装的 Yxi（Program Files 路径直接拒绝）。
- 不传任何参数：不跑 `--smoke` 等测试、不触发任何更新命令（应用自身例行的行为与脚本无关）。
- 不修改应用、不重建 jar；试用 profile 随时可删，不残留。
