# 会话权限模式验收

## 已验证

- `80aa6b2` 审批文案：识别弯引号 don’t，区分单次允许、持久允许、自动模式与拒绝；未知扩展 Yes 保留原文。`run.ZS8dnM` 的 ApprovalLabelsTest：2 tests，0 failures/errors/skipped。
- `7f1d35b` 输入框左侧权限菜单，原生 footer 解析及 runtime/屏幕一致性保护。`run.I1XOGq` 的 PermissionModeTest：1 test，0 failures/errors/skipped；core 和 desktop 编译通过。
- `460c006`：`run.18ff4C` 真实 CLI + 隔离 SSH/tmux + 假接口整链通过，包含统一 ConversationRewind 首轮入口，以及 Manual → Plan → Manual；未开放 bypass 的会话请求 Bypass 会失败并回到 Manual，切换过程没有新增模型请求。不是 Windows 完整 UI 验收。

## 尚未完成

- 启动时选择 bypass、恢复原会话时保留权限模式，旧会话切换到尚未开放模式的恢复流程。
- 权限选择持久化、终端外部切换后的持续状态同步、切换过程中断与菜单确认处理。
- Windows 窄屏菜单渲染与真实点击验证；Codex 运行器对应权限适配。
- 首轮回退失败路径审查指出的恢复锁问题尚需核实修复；不得将正常链通过解释为全功能完成。

构建前清理了 1.4.10 发布暂存中的重复 Setup.exe：与 `/var/www/yxi/desktop-rollback-1.4.10-before-1.4.11/Yxi-win-Setup.exe` SHA-256 和大小一致，回滚包保留。暂存目录的 `duplicate-installer-cleanup.json` 记录来源、保留路径及摘要。定向 BuildKit 清理未释放足够空间，不计作回收成功。
