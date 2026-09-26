# Gemini 0.34.0 官方 Code Assist 配置方案

日期：2026-09-24。只读固定tag源码与项目测试；没有执行CLI/SSH、读取凭据或更改生产代码。源码副本在 `.artifacts/gemini-profile-source/`。

## 核实结果

Gemini比Hermes有更明确的内容生成器分支，但默认ACP仍不满足“不改全局配置+同会话官方端点回执”。可实现路径是复用官方库的进程级ACP启动适配，固定版本后保留原HOME与OAuth存储，通过内存settings覆盖而非调用会写全局的authenticate。不能仅调用现有ACP authenticate并宣称隔离。

1. [acpClient.ts:174-235](https://github.com/google-gemini/gemini-cli/blob/v0.34.0/packages/cli/src/acp/acpClient.ts#L174-L235)：authenticate解析AuthType；切换类型会调用clearCachedCredentialFile；成功后settings.setValue(User, security.auth.selectedType, method)。因此原生authenticate不是无副作用的进程选择，可能影响其他客户端登录/配置。
2. [同文件:405-437](https://github.com/google-gemini/gemini-cli/blob/v0.34.0/packages/cli/src/acp/acpClient.ts#L405-L437)：每个新session先根据merged.selectedType调用config.refreshAuth，再初始化MCP；失败返回authRequired，未见这里自动改为API分支。必须验证newSessionConfig重新loadSettings时覆盖仍有效，不能只改首个settings实例。
3. [contentGenerator.ts:118-139,182-227](https://github.com/google-gemini/gemini-cli/blob/v0.34.0/packages/core/src/core/contentGenerator.ts#L118-L139)：LOGIN_WITH_GOOGLE/COMPUTE_ADC与API/Vertex/Gateway走不同生成器。即便GEMINI_API_KEY存在，显式Google登录分支不会取其作为推理key。环境自动识别不是这项证明的替代。
4. [codeAssist.ts:17-40](https://github.com/google-gemini/gemini-cli/blob/v0.34.0/packages/core/src/code_assist/codeAssist.ts#L17-L40)：通过getOauthClient+setupUser构造CodeAssistServer，包含projectId/userTier/paidTier；[L45-61](https://github.com/google-gemini/gemini-cli/blob/v0.34.0/packages/core/src/code_assist/codeAssist.ts#L45-L61) getCodeAssistServer可识别实际生成器实例。这是可用于同进程回执的明确原生入口，而不是模型名猜测。
5. [server.ts:73-74,508-513](https://github.com/google-gemini/gemini-cli/blob/v0.34.0/packages/core/src/code_assist/server.ts#L508-L513)：默认https://cloudcode-pa.googleapis.com/v1internal；CODE_ASSIST_ENDPOINT/API_VERSION可覆盖。仅验证authType仍可能将OAuth发到覆盖端点，必须同时钉住实际endpoint。
6. [settings.ts:550-614](https://github.com/google-gemini/gemini-cli/blob/v0.34.0/packages/cli/src/config/settings.ts#L550-L614)：项目/用户.env可加载变量，但不会覆盖进程中已存在的键。因此对子进程设置明确空API键和官方CODE_ASSIST_ENDPOINT可挡此加载路径，而仅remove键会重新被.env填入。
7. [settings.ts:98及246-269](https://github.com/google-gemini/gemini-cli/blob/v0.34.0/packages/cli/src/config/settings.ts#L246-L269)：支持GEMINI_CLI_SYSTEM_SETTINGS_PATH且system最后覆盖；不能用私有system文件替换管理员策略来实现隔离。没有核实到保留真实managed策略的单独官方profile CLI参数。

当前ACP initialize/session response未见返回上述生成器类型、authType、端点、paidTier的受支持字段。已有内部函数不代表Yxi目前可直接RPC调用。

## 最短可开发链路

- 保留原GEMINI_CLI_HOME，不复制oauth_creds.json；认证仍由原生OAuth处理。官方选项暂存待验证身份，不把api-key模型成功当企业已登录。
- 为0.34.0建立明确版本约束的适配启动器（复用原生库，不改用户文件）：在加载真实user/workspace/system策略后，仅内存覆盖目标selectedType=oauth-personal；若真实管理策略要求别的authType则明确拒绝，不覆盖强制策略。每次session load亦应用相同覆盖。显式登录流程允许原生维护自身token，但不调用会清除另一认证缓存的切换操作。
- 子进程固定官方CODE_ASSIST_ENDPOINT与API_VERSION，GEMINI_API_KEY/GOOGLE_API_KEY/第三方URL/自定义认证headers明确清空；原始HOME保持，工具权限保持。实际需完整枚举OAuth与CodeAssist模块所有endpoint代理覆盖项，当前源码研究只确认上述几个，不能宣称清单已穷尽。
- 同进程扩展回执使用config.getContentGeneratorConfig的authType（具体公开接口再核实）、getCodeAssistServer(config)的实际实例、固定路由、setupUser返回的原生tier。只输出非秘密字段，不输出OAuth对象或tokens；在session创建和重建后校验。tier不属于企业支持范围时提示权益不匹配，而不是自动API回退。
- 记录official:gemini仅在实际会话验证成功后；运行中更换认证/provider需要新的明确流程，不能继续沿用官方标签。

## 容器验证（尚待执行）

复用 `GeminiAcpConversationNativeTest.kt`、Dockerfile.gemini的0.34.0安装及loopback HTTP fixture，增加独立模拟OAuth/CodeAssist服务；只合成凭据，测试白名单与生产常量分开。

A. config.selectedType=gemini-api-key + .env残留key/第三方URL，官方启动后必须CodeAssist生成器；第三方端点零请求；原settings/.env字节不变。
B. 缺少官方凭据但有效API残留：authRequired/明确未登录，API端点零请求，不发送提示词。
C. .env设置CODE_ASSIST_ENDPOINT恶意覆盖：子进程官方固定值仍生效；仅remove变量的反例应证明会被重新加载。
D. 管理策略强制API/Vertex：官方创建拒绝，不替换system settings绕过。
E. 企业tier成功、个人tier/权益拒绝、失效凭据分别验证；同名模型不能影响结论。
F. 两并行会话分别官方/API不串路由；原生文件权限、MCP限制仍保留；会话rebuild重新校验。

注意：上游 `integration-tests/acp-env-auth.test.ts` 整个describe.skip，不应引用为已通过证据；本项目此前真实0.34.0 API回环测试通过，但不是企业OAuth证明。当前官方产品支持要求依design/official-provider-defaults.md另行核验，不能从历史tag支持oauth-personal推断今天个人订阅仍可用。
