# 本地 Codex 官方订阅接入：认证边界复核

2026-09-23。此记录支持 L2 实施，不代表官方订阅默认发送已完成。

## 已验证的基础

`CodexAppServer` 已抽出 `CodexTransport`，原 SSH 构造调用保留，新增由 Yxi 持有生命周期的本机进程通道。显式 argv/environment 启动，不用 shell 拼用户输入；退出仅停止该通道所属进程。本机目录使用本机绝对路径及存在性校验。已通过真实 Codex 本机初始化/历史读取、原会话日志与配置不变的测试，以及原 SSH 接口测试。

这使本地会话能继续接入既有 `CodexTaskController`，复用审批、取消、队列和投递不明状态；尚未把只读历史页接成发送页，也未宣称本机发送或原会话接管已完成。

## 官方配置不能只改供应商名

已打开核对 [配置参考](https://learn.chatgpt.com/docs/config-file/config-reference) 和 [认证文档](https://learn.chatgpt.com/docs/auth)。另按测试所用 Codex 0.153.4 对应 `rust-v0.153.4` 源码逐项检查；原文副本位于工作区 `.artifacts/codex-0.153.4-reference/`。

- `config-mod.rs:3713`：该版本会过滤空的 `openai_base_url`，再创建内置提供方；需用隔离配置验证 CLI 覆盖确实移除了旧端点，不能仅依赖名称是 `openai`。
- `provider.rs:294`：默认端点由认证类型决定；ChatGPT 认证使用 ChatGPT Codex 后端，API Key 使用 API 端点。硬写普通 API URL 不能等同订阅模式。
- `auth-manager.rs:1287-1341`、`:1399`：强制登录方式不匹配时调用注销并清理认证存储。不能为“默认官方”盲目新增 `forced_login_method=chatgpt`，否则可能清掉原来的 API 登录。
- 不读取或复制真实令牌；使用原生账号接口检查类型，账号不符合时保留既有登录、显示原因并提供原生登录流程。

## 下一步必须验证

1. 进程级官方配置覆盖与继承环境清理：旧 Base URL/API Key/组织策略不能使“官方订阅”静默改走第三方；不改共享 config 或 auth。
2. 以原生账号类型和有效配置为发送前条件，不拿 Pro 标签当作端点已核验；原生模型目录决定可选模型，不能沿用第三方模型 ID。
3. 每 Agent 第三方配置独立于官方登录；两个隔离测试端点观察请求归属、模型和 Key，确认共享配置不变。
4. 原历史继续仍需控制权检查；占用未知提供明确标记的原生 fork，不直接 resume 后假报已取得跨应用互斥。Windows附件路径和工具兼容性亦需覆盖。

## 官方进程校验实现进度

新增`LocalCodexProfiles`：保留原`CODEX_HOME`，仅在新进程清除继承的API Key/外部API地址及联合身份环境，覆盖`model_provider`、清空`openai_base_url`并使用官方登录地址。未强制改登录方式，不写共享认证或配置。连接后读取有效配置和原生账号，必须同时确认官方路由与ChatGPT账号类型；缺字段或未知状态不会启用发送。

发送前重复校验，并针对旧会话单独检查实际`modelProvider`。原生resume/fork要求显式选择官方提供方，turn/start和steer要求原会话提供方已经确认为openai；只核对进程配置不能替代这一检查。

首轮真实原生隔离测试证明第三方端点可以被进程级覆盖、API Key原生登录被拒但不被删除，全局config和auth字节保持不变。此处的测试账号和端点均为隔离fixture；后续正向离线ChatGPT身份测试不等同于真实订阅模型请求验收。发送UI、模型目录选择、登录流程和每Agent线路仍未接入。
