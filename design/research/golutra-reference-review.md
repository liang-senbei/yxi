# Golutra 参考价值与 Yxi 采用边界

核对日期：2026-09-23。用户要求“看看有没有参考价值，但大体按我们的来”。已浅克隆只读参考代码，没有安装依赖、启动项目或将其源码并入Yxi。

参考提交：[8b68a14183afa6ec26f9905f2a81cbd91ed1b35b](https://github.com/golutra/golutra/tree/8b68a14183afa6ec26f9905f2a81cbd91ed1b35b)。本地参考目录位于主工作区旁的`golutra-reference`。

结论：适合参考多Agent编排和终端管理的分层、状态表达与交互流程；不替代Yxi既定的本地/服务器工作台、原生会话接入和供应商配置方案。以下为源码静态核对，未将README宣传、路线图或未运行的测试当作功能验收。

## 值得借鉴

| 参考点 | 已读证据 | 对Yxi的用途 |
| --- | --- | --- |
| 运行器适配配置集中管理 | `src-tauri/src/terminal_engine/default_members/registry.rs`、`codex.rs`、`claude.rs`、`opencode.rs` | 每个运行器分别声明启动、权限、恢复和就绪能力；减少散落的if/else，明确支持/未知/不支持 |
| 投递持久化与领取机制 | `src-tauri/src/message_service/chat_db/outbox.rs`、`orchestration/chat_outbox.rs` | 核对我们的待发送/投递中/结果不明/失败恢复状态；借鉴租约和批次边界，但未知投递仍禁止自动重发 |
| 消息派发与终端实现分层 | `ports/terminal_dispatch_gate.rs`、`orchestration/chat_dispatch_batcher.rs`、`message_service/pipeline/` | 将协作消息路由与具体运行器输入方式分开；不能把写入终端等同模型已接收或任务完成 |
| 成员、会话身份映射 | `message_service/chat_db/terminal_session_map.rs`、`ports/terminal_session.rs` | Agent身份不与终端标签混用；Yxi仍增加主机、系统用户、原生数据根与运行器命名空间 |
| 群聊中的成员提及和终端入口 | `src/features/chat/components/ChatInput.vue`、`ChatInterface.vue` | 可参考协作目标选择、上下文展示；不替代我们已要求的图片/文件@引用、缩略图与预览 |

## 不照搬的部分

1. **Codex原生历史接入**：所读`default_members/codex.rs`使用CLI、`resume {session_id}`和`/status`，等待文本特征后提取`session:`。在所查`src`/`src-tauri/src`未找到`app-server`、`thread/read`路径。Yxi继续采用原生协议读取历史、核对模型与审批，终端方案仅用于需要它的运行器。
2. **状态回落**：`session/polling/rules/status_fallback.rs`会在满足静默时长和防抖门槛后从Working回落Online。这可参考为界面防抖，不能成为Yxi“已完成”的权威证据；我们保留原生完成回执及结果不明状态。
3. **自动重试**：Golutra outbox有重试、租约和退避；这不证明CLI输入能端到端去重。Yxi必须按实际投递阶段区分可重试与未知，不整体复制重发策略。
4. **插件市场**：当前`src/features/PluginMarketplace.vue:150-151`明确是待接数据源的空占位列表。可以参考排版，不能把它当成能直接接入的插件目录、安装器或服务器隔离实现。
5. **多机能力与长期自治宣传**：README将跨设备/环境迁移及更高层自治列在后续计划。本次未找到与Yxi对应的SSH/Tailscale多主机管理实现；不能据此更换我们的主机架构或承诺长期无人值守能力。
6. **技术栈与外观**：其Vue/Tauri/Rust与Yxi的Compose/JVM不同。保留Yxi已有清爽布局、官方图标和导航，不改成其赛博风格或以群聊为主的产品。

## 许可边界

[LICENSE](https://github.com/golutra/golutra/blob/8b68a14183afa6ec26f9905f2a81cbd91ed1b35b/LICENSE)为Business Source License 1.1，额外使用授权为None，列明非生产使用及后续转GPL的条件。它不能按MIT项目直接移植到生产版本。当前只做参考评估、提炼交互需求与独立实现；若以后确需源码移植，应先确认适用授权。

## 维持原PRD及执行顺序

仍以`local-workspace-prd.md`、`provider-editor-prd.md`及完整Windows工作台PRD为准：先完成本地新建/续聊、官方订阅默认与每Agent第三方配置、模型映射及真实验收；保留本地/服务器位置选择、图片文件输入、探索中的连接/定时任务、按本地或当前服务器划分的插件。

本次增加的是参考依据和验收提醒，不新建另一套成员/终端/会话系统，不迁移技术栈，不因Golutra路线图扩大当前交付承诺。可优先吸收的概念是运行器能力注册、可核对的状态转换、投递恢复与协作目标表达；现有Yxi实现按自身原生接口和测试结果落地。
