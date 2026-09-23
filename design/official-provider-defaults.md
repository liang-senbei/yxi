# 各运行器的内置官方配置

2026-09-23。用户要求Claude Code、Codex、Gemini和Grok Build默认自带官方订阅配置；OpenCode/Hermes若有官方服务也加入。

| 运行器 | 内置首选 | 补充及依据 |
| --- | --- | --- |
| Claude Code | Claude官方订阅 | 委托原生Claude登录，订阅与API Key分别核对。[官方认证](https://code.claude.com/docs/en/authentication) |
| Codex | ChatGPT官方订阅 | 使用同一原生数据根和官方账号；既有进程级路由/账号校验继续适用。[官方认证](https://learn.chatgpt.com/docs/auth) |
| Gemini | Google官方账号/订阅 | 官方入口包含免费账号、Google AI Pro/Ultra等权益；AI Studio/Vertex API计费另列。[官方方案](https://geminicli.com/plans/) |
| Grok Build | Grok官方订阅 | 官方CLI支持浏览器登录及设备码，API Key另一路；权益以账号为准。[Build](https://docs.x.ai/build/overview)、[认证](https://docs.x.ai/build/enterprise)、[方案说明](https://docs.x.ai/grok/faq) |
| OpenCode | OpenCode Go官方订阅 | Go是订阅；同时提供Zen官方按量服务，不能标成同一种计费方式。它们是官方服务，不宣称OpenCode自研模型。[Go](https://opencode.ai/docs/go/)、[Zen](https://opencode.ai/docs/zen/) |
| Hermes | Nous Portal官方订阅 | 官方指南明确支持模型推理和工具订阅；登录状态与实际inference provider分开检查。[Nous Portal指南](https://hermes-agent.nousresearch.com/docs/guides/run-hermes-with-nous-portal) |

规则：内置项固定提供，无需手工添加，不持有复制来的令牌；新建时优先官方，既有会话保持原配置。未登录仍保留默认选项并引导原生登录，不自动回退第三方或付费API。额度不可用时显示未知，不将存在官方账号当成有剩余额度。

“官方配置”“沿用外部配置”“已保存第三方配置”必须分成独立身份。恢复默认线路不是官方订阅验证，不复用原来的空profileId含义来冒充官方。切换只作用于所选Agent；用户明确要求全局同步时才修改共享配置。

当前实现：`OfficialProviderProfiles`定义七个内置项和六种首选，服务器配置页固定显示相应卡片及官方说明，Grok启动命令按官方资料更新为`grok`（不因此开放尚未完成的创建适配）。卡片明确显示账号/线路尚未核对，不宣称已启用。Codex本地官方进程校验已实现；其余登录状态读取、登录UI和真实线路应用仍待逐运行器完成。本文件不把静态卡片当作默认官方发送已验收。
