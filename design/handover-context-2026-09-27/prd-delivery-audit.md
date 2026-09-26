# PRD 交付缺口审计（只读，2026-09-24）

以当前代码为准；已读 windows-workbench-prd、local-workspace-prd、shared-plugin-prd、official-provider-defaults 及最新实施记录。六运行器入口、分类、官方图标、模型映射编辑器、探索连接与客户端定时器均已有实现，不应重做或称其不存在。以下按用户可感知收益排序。

## 1. 官方默认配置尚未成为 ACP 新建会话的有效身份

证据：`OfficialProviderProfiles.kt` 已有所有官方卡片，但 `LocalAcpTasks.kt:create` 将 provider 固定保存为 `native`；`AcpConversationPane.kt:NewAcpConversationDialog` 以原生连接/认证方式创建，未把官方与沿用原配置分成可核验选择；`LocalWorkspacePane.kt:127` 仍明确单 Agent 官方/第三方切换在接入中。

立即开发：为 ACP 新建提供显式 profile identity，并让各适配器返回账号/实际 provider 的核验状态；未能验证官方时阻止把 native 配置标成 official，不要统一改文案冒充生效。先闭环一种已具备完整原生测试的运行器，再按相同界面接其他运行器。

验收：官方选项默认可见；第三方环境残留不导致静默回退；未登录保持官方选择并引导登录；创建结果保存真正的 profile identity；同机两个不同配置会话不串线路。

## 2. 插件市场安装与共享配置仍是两套流程

证据：`PluginsPane.kt` 将“共享配置”作为第三页；`NativePluginPane.kt:105` 远端仍提示“安装到当前服务器的 Codex”，安装操作没有共享运行器绑定选择。`SharedMcpPane.kt` 的定义来源为 manual。`SharedMcpRegistry.kt` 以及 Claude/Codex/OpenCode 适配器已有实现及验证，缺的是市场条目到共享资源/绑定的产品链路。

立即开发：对原生目录中可解析的通用 MCP 条目提供“登记共享配置”预览，展示源、版本、命令/URL、环境变量引用和目标运行器；专属插件保留不支持原因。随后在同一详情显示 desired/applied/verified，而非另一个完全脱离市场的配置页。

验收：市场选一个通用 MCP，登记一次，三个运行器各调用一次；禁用某运行器不影响另外两个；安装、授权、加载状态分别显示；本地与两个服务器隔离；敏感值不写索引。

## 3. 定时任务仍走旧本地单轮执行器，未接统一多运行器工作台

证据：`ScheduledTasksPane.kt:80` 本地目标仅 Codex/Claude；`ScheduledTasks.kt:execute` 的 local 分支调用 `state.localAgents.start`，与新 LocalClaudeTasks/LocalAcpTasks/LocalOpenCodeTasks 管理器分离。已有 durable claim、未知结果暂停和重复周期算法，不需要重写调度核心。

立即开发：抽取 schedule target adapter，接统一任务管理器、返回可导航 task key 和运行器完成回执；目标能力驱动显示六运行器，未连接/未认证清楚显示原因。先覆盖本地新会话与已连接 ACP 会话。

验收：定时执行产生工作台可打开任务；真实完成才显示完成；审批暂停、进程崩溃、重启未知记录不自动重发；同名跨主机任务不误投。后台常驻/关闭客户端后执行仍是独立交付项，不能由已有定时器测试代证。

## 4. Claude 长历史被 8MiB 硬上限阻止读取和恢复

证据：`LocalClaudeHistory.kt` 一次读取全部文件，超过 8MiB 直接抛错；`LocalClaudeTasks.resume` 在连接前读取历史，因此长期对话不仅不能看也不能继续。短会话读取/恢复已有原生和 UI 测试，不应重做整条链。

立即开发：带稳定游标的窗口读取，保留 session/cwd 校验；UI“加载更早消息”；恢复只需验证身份和加载首屏，不要求一次读完全文。特别处理 tool_use/tool_result 跨页与半行尾部。

验收：大于 8MiB 合成历史能读首屏并恢复，不改原文件；翻页无重复/缺失；工具配对正确；追加写入不破坏游标；身份错配拒绝恢复。

建议顺序：2 与 3 可独立并行、1 按适配器推进、4 单独交付。模型映射及连接已有主流程，当前优先完成可用链路比重复写静态卡片更直接。Windows 真交互、发布包升级和真实账号仍需单独验收；本审计没有执行原生 CLI/SSH，也没有把缺测试当成缺实现。
