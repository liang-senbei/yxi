# ACP 官方身份接入方案：Hermes 优先（2026-09-24）

## 结论

Hermes 的 provider 解析器可以作为证据来源，但当前 ACP 返回不足以证明官方推理端点。不能把 authMethods 中出现 nous、模型 ID 前缀 nous: 或 Portal 已登录当作“本会话已使用官方订阅”。可立即做官方选项/待核验状态；要真正闭环，应加入版本约束的 Hermes 适配扩展，在同一 ACP 进程对实际 agent provider、base_url、api_mode 做脱敏回执及发送前约束。当前原生 ACP 没有已核实的配置覆盖+有效端点查询组合，不建议仅清理环境变量后宣称完成。

## 已确认（当前应用）

- `LocalAcpTransport.kt:AcpLaunch.environment` 仅保留同一 HERMES_HOME；继承其他环境。启动参数仅 hermes acp。
- `LocalAcpTasks.kt:prepare/create` 初始化、创建会话并保存 provider=native；没有官方身份或端点断言。
- `AcpClient.kt` 接线 initialize/authenticate/session new/model/config option；没有有效 provider/base_url 查询。
- `HermesAcpConversationNativeTest.kt:52-55` 已有合成 HERMES_HOME、.env、config.yaml、回环 HTTP fixture，可复用来测试第三方残留与失败回退；此前成功回环不等于官方订阅验证。

## 已确认（本轮获取的固定上游源码）

版本基准来自 `dev/isolated-tests/Dockerfile.hermes:8-14`：5a3e03ef37462000b5b13d03915eb3e7b1633b6f。

1. [acp_adapter/auth.py](https://github.com/NousResearch/hermes-agent/blob/5a3e03ef37462000b5b13d03915eb3e7b1633b6f/acp_adapter/auth.py#L11-L45)：detect_provider 调用 resolve_runtime_provider，要求非空凭据后返回 provider；握手只投影 provider 名，丢弃实际 base_url/source。因此它比账户标签强，但不能证明官方路由。
2. [hermes_cli/runtime_provider.py](https://github.com/NousResearch/hermes-agent/blob/5a3e03ef37462000b5b13d03915eb3e7b1633b6f/hermes_cli/runtime_provider.py#L464-L473)：显式 requested > config.model.provider > HERMES_INFERENCE_PROVIDER > auto。环境设置 nous 不能覆盖现有第三方 config。
3. 同文件 [L526-L527](https://github.com/NousResearch/hermes-agent/blob/5a3e03ef37462000b5b13d03915eb3e7b1633b6f/hermes_cli/runtime_provider.py#L526-L527)：Nous credential-pool 的 base_url 可被 `_nous_inference_env_override()` 覆盖；provider=nous 本身仍不足。
4. 同文件 [L1023-L1067](https://github.com/NousResearch/hermes-agent/blob/5a3e03ef37462000b5b13d03915eb3e7b1633b6f/hermes_cli/runtime_provider.py#L1023-L1067)：auto 会吞 OAuth AuthError 再落到其他来源；明确 requested=nous 则抛出。官方模式必须显式请求，不能使用 auto。
5. [acp_adapter/entry.py](https://github.com/NousResearch/hermes-agent/blob/5a3e03ef37462000b5b13d03915eb3e7b1633b6f/acp_adapter/entry.py#L74-L101) 加载 HERMES_HOME/.env；ACP parser 未提供 --provider/--config 覆盖参数。

本轮源码副本在 `.artifacts/hermes-profile-source/`。获取 server.py/portal_cli.py 被 GitHub 429 拒绝，未重试；以下缓存线索不能冒充本轮下载校验结果。

## 缓存源码线索（需固定版本复核）

- `.artifacts/hermes-native/source/session.py:457-537` `_make_agent`：load_config读取model.provider，再 resolve_runtime_provider；异常被暂存后仍构造 AIAgent 默认解析，只在 AIAgent也失败时重新抛原错误。这是需要实测的回退窗口。
- 缓存 `server.py:298-315` 模型状态取 agent.provider，但 base_url仅交给内部model catalog；`_switch_model` 支持显式 provider:model且注释不持久化。没有看到端点回执。
- 缓存 `config.py:499-501` 主配置固定 get_hermes_home()/config.yaml；未发现可用独立配置文件覆盖。源码中的 HERMES_CONFIG_PATH 名称在受限变量表出现，并不能证明支持。

## 实施方案与边界

1. 增加官方/沿用原配置两种 profile identity，官方首选仍展示“待核验”；原生登录使用用户现有 HERMES_HOME。不要调用 setup --portal 来偷偷全局改配置，不复制 auth.json，也不换空 HOME。
2. 为已支持的固定 Hermes 版本增加小型同进程 ACP 扩展（可向上游提交；若项目维护自己的兼容启动适配则明确版本支持范围）。创建 agent 时显式 requested_provider=nous，不采用配置默认；保留原有工具、权限、记忆配置。禁用主模型跨provider故障回退并在失败时返回明确认证/路由错误。不要以全局忽略用户配置来绕过权限策略。
3. 扩展返回允许字段 provider、base_url、api_mode、credential-source 分类、sessionId；从实际 agent 实例取值，不输出 api_key/token、完整配置或auth store。初始化和每次model rebuild/发送前验证同一规则：provider=nous、官方HTTPS端点白名单、无userinfo/query、批准的协议。端点列表须进一步核实 Nous 官方实现，不能只允许名字包含nous的任意URL。
4. Kotlin官方配置禁用其他provider模型项；选择Nous模型后检查回执。第三方配置保持独立路径。只有经过回执的记录可写 official:hermes；不匹配关闭此连接，保留草稿与未发送队列。
5. 若决定不维护原生扩展，当前可交付边界仅是显式Nous选择+身份未核验。现有协议无法可靠证明端点；不要用预检查独立进程替代实际会话回执。

## 隔离验证矩阵（尚未执行）

仅Docker合成HOME/凭据，两个回环HTTP计数器模拟“批准测试端点”和“残留第三方端点”；测试模式注入测试白名单，生产白名单不可由用户endpoint扩展。

- 第三方config、.env同时残留：官方创建后实际请求只到批准端点；原config/.env/auth文件字节不被Yxi改写。
- Nous无凭据/过期、第三方key有效：官方明确失败、两个模型端点均无请求，队列仍Local；不自动选择第三方。
- provider=nous但override/credential-pool端点第三方：拒绝发送；不将Nous令牌发送到残留端点。
- 中途model切到其他provider或重建失败：阻止下一轮、不保留错误的官方标签；原session身份仍明确。
- 在AIAgent构造回退路径注入resolver异常：断言不会进入默认provider请求，这是现有ACP桥最值得先验证的反例。
- 两个并行会话（official与native/thirdparty）：实际请求端点不串、用户全局配置不变；退出一个不影响另一个。

## Gemini 顺位

尚未完成其固定源码审计，不建议为了快而假设 `selectedAuthType` 等于有效企业端点。当前官方defaults文档要求Code Assist企业与API分开；后续需核实ACP实例是否暴露认证类型+实际内容生成路由及托管策略优先级，才能与上述方法比较。

官方指南仅作产品流程依据：[Nous Portal指南](https://hermes-agent.nousresearch.com/docs/guides/run-hermes-with-nous-portal)。它区分登录和推理provider；setup会改全局provider。本文源码分析独立于该指南，不把指南终端输出当作本机实测。
