# 本地运行器、Codex 历史与订阅接入调研

日期：2026-09-23。状态：只读调研；不代表相关能力已在 Yxi 交付。

关联：[本地工作台 PRD](../local-workspace-prd.md) · [配置与模型映射 PRD](../provider-editor-prd.md)。

本轮没有安装运行器，没有修改第三方配置、登录凭据、原生会话或长期记忆，没有发送模型请求。研究用 app-server 进程可能产生运行器自己的普通运行日志；“只读”指仅调用读取接口，不声明文件系统零写入。已有 1.4.14 托盘补丁发布范围与本调研分开。

## 一、结论及证据强度

| 用户问题 | 结论 | 证据边界 |
| --- | --- | --- |
| 能否识别已安装 Codex、Claude Code 等？ | 可以；应识别原生程序、npm 启动器、桌面内置程序、WSL 和指定路径 | 本机已发现 npm Codex/Claude；当前 Yxi 只查 `.exe` 会漏检 |
| 截图里的 Codex 会话能否读取？ | 可以，已实际查到三个同名会话并读取一个的历史结构 | 仅验证本机该数据根；不代表云端、远端或全部归档都已经覆盖 |
| 能否用现有官方订阅？ | Codex 本机原生接口已返回 ChatGPT / Pro；可设计原生订阅默认项 | 登录状态不是额度保证；未调用模型验证实际计费、余额或所有模型可用性 |
| App 与 CLI 是否共享历史？ | 本机两份运行器读到了相同会话；共享取决于宿主、系统用户和数据根 | 没有从另一进程接管活跃会话，也没有验证桌面与 Yxi 同时发送 |
| 是否共享长期记忆？ | 本地 Codex 有独立记忆目录；使用同一数据根且启用记忆时可复用 | 是否在特定一轮被注入要结合该运行器版本及会话设置；不等于每条历史都成为记忆 |
| CC Switch 只影响 PowerShell 中的 Codex？ | 不是按 PowerShell/App 区分，而是按配置文件及有效覆盖设置区分 | 同一配置根的本地运行器可能一起受影响；不能承诺所有桌面/云端模式都跟随 |
| OpenCode、Hermes 是否有 CLI？ | 两者都有；并且分别提供服务接口/ACP 等更适合 GUI 的接入方式 | 当前本机 Windows PATH 未发现，未安装、未做原生执行验证 |

## 二、本机实查

系统为 Windows，未设置 `CODEX_HOME`，默认用户根为 `C:\Users\dfhzw`。

| 对象 | 实查结果 |
| --- | --- |
| npm Codex | `D:\qianduantool\nodejs\codex.cmd` / `.ps1`；`codex-cli 0.149.0` |
| 桌面环境内置 Codex | `C:\Users\dfhzw\.codex\plugins\.plugin-appserver\codex.exe`；`0.155.0-alpha.16` |
| 其他 Codex 候选 | PATH 还有 `%LOCALAPPDATA%\OpenAI\Codex\bin\<版本目录>\codex.exe`；不能仅凭同名判定等价 |
| Claude Code | npm `.cmd/.ps1`，实际 `--version` 返回 `2.1.228` |
| Gemini | 检测到 npm 启动器，本轮未验证版本和认证 |
| OpenCode、Hermes | Windows PATH 及本轮检查的默认用户目录未发现；不是“整台电脑未安装”的结论 |
| WSL | 枚举到 Ubuntu-22.04、Ubuntu-24.04；本轮未进入发行版扫描，因此内部安装状态未知 |
| 数据目录 | `.codex/sessions`、`.codex/archived_sessions`、`.codex/memories`、`.claude/projects` 存在 |

当前实现证据：`LocalAgents.binary()` 主要查 `~/.local/bin`、Codex 内置路径和 PATH 下的 `.exe`，不处理 npm `.cmd/.ps1`；没有启动时完整检测、认证状态列表或外部历史导入。

### 原生 Codex RPC 实验

分别启动上述 npm 包内的原生二进制和桌面环境内置二进制；使用当前用户默认数据根。只调用 `initialize`、`account/read(refreshToken=false)`、`config/read`、`thread/list`、`thread/read`，不调用 `thread/resume`、`thread/start`、`turn/start`、登录或任何写配置接口。

两份二进制均返回：

- 认证类型 `chatgpt`，方案 `pro`，要求 OpenAI 认证。
- 本次过滤范围内 23 个未归档会话，来源为 20 个 `vscode` 和 3 个 `cli`；查询的 `modelProviders=[]` 表示跨供应商列出，不能因为当前线路变更把旧会话隐藏。
- 截图同名匹配：“微调PPT第七页内容”“这个方向怎么样”“acquire和order”。来源元数据不应被用来推断当前由哪个桌面窗口拥有会话。
- 对“微调PPT第七页内容”调用历史读取：29 个 turn、110 个 item，包含用户/助手消息、命令、图片、工具记录等结构。未在报告中导出对话正文或秘密。
- 内置版本暴露 `features.memories=true`、`memories.use_memories=true`；较旧 CLI 未在本次响应中暴露相同字段，不能把缺字段解释为 false。

本地证据：工作区外层 `.artifacts/local-agent-research-2026-09-23/` 中的 `codex-readonly-evidence.json`、`codex-cli-readonly-evidence.json`，以及只打印白名单字段的探针脚本。生成的版本对应 JSON Schema 同目录保存，仅作接口研究。

**已经证明“能列出和读取”；尚未证明“任何原生 App 会话都可无缝交给 Yxi 控制”。** 原会话可能依赖桌面动态工具、附件、worktree、MCP、权限和特殊后台服务；只保留 session ID 不够。

## 三、配置、会话、记忆不是同一个东西

| 层 | 含义 | 建议处理 |
| --- | --- | --- |
| 原生会话 | 某个 thread 的历史、工具活动、附件及恢复标识 | 通过原生接口分页列出/读取/恢复；先浏览，再按运行状态接续 |
| 长期记忆 | 从历史提炼出的偏好、工作习惯、项目知识 | 由原生运行器在对应数据根读取，不自动合并或改写各家记忆 |
| 项目规则 | AGENTS.md、CLAUDE.md、项目指令等 | 按所选执行位置与运行器规则加载，记录来源 |
| UI 元数据 | 项目收藏、排序、固定分组、侧栏布局 | 优先公共接口；没有接口时在 Yxi 保存关联视图，不写别人的私有数据库 |
| 登录及路由 | 账号认证、provider、模型、端点 | 单独展示有效值和来源；不能由历史目录是否存在猜测 |

官方文档明确区分 ChatGPT 网页记忆与本地 Codex 记忆。本地记忆在 Codex home 下，使用和生成各有开关；不是实时完整同步所有对话。[Memories](https://learn.chatgpt.com/docs/customization/memories)

截图看起来是 ChatGPT/Codex 桌面端的本地项目任务。PRD 按此设计，同时明确：普通 ChatGPT 网页对话、ChatGPT Work 云端和 hk13 上的会话，不会因为本机 `.codex` 存在就自动出现在本地列表。

## 四、CC Switch 的生效范围

已存在的参考仓库 `cc-switch-reference`，本轮审阅提交 `56df6513943062e8ca9eb80d8928eef7cf08a76d`（2026-09-22），不声称等于未来最新版本。

源码 `src-tauri/src/codex_config.rs`：

- `get_codex_config_dir` 使用可覆盖目录，否则 `~/.codex`。
- `get_codex_config_path` 指向 `config.toml`。
- `write_codex_live_atomic` 涉及 `auth.json` 和 `config.toml` 写入及回滚；不同预设/切换路径会有差别。

因此：如果 CC Switch 改的是 App 和 CLI 都使用的那份用户配置，二者的后续本地运行可能一起受到影响。若有不同 `CODEX_HOME`、profile、启动覆盖、原生会话保留设置或远程执行位置，结果会不同。当前进程也不保证热加载。更改本地配置不能被推广成改变普通 ChatGPT 云端模型。

Codex 官方说明有分层配置和启动参数覆盖；本轮没有为了验证路由而切换用户全局配置或发送真实请求。因此“当前桌面版本的每个入口最终使用哪条第三方线路”仍需隔离流量验收。[Config basics](https://learn.chatgpt.com/docs/config-file/config-basic) · [Advanced configuration](https://learn.chatgpt.com/docs/config-file/config-advanced)

产品应分别提供“官方订阅”“某个已保存第三方配置”“沿用外部配置”，不要把后者命名成官方订阅；用户已指定默认官方订阅。

## 五、多运行器接入建议

| 运行器 | 原生能力与依据 | Yxi 建议适配 | 当前验证等级 |
| --- | --- | --- | --- |
| Codex | app-server 有读取历史、恢复、账号和额度接口 | 本地 stdio app-server；读取与控制分离，按版本协商 | 本机双版本只读通过；既有新建/续聊隔离测试另见实施记录 |
| Claude Code | CLI 可编程输入输出与恢复，认证由原生工具管理 | 原生 CLI/经验证的 Agent SDK 适配；探测新旧版本历史接口 | 本机安装已识别；本轮未读取订阅档位 |
| OpenCode | CLI、JSON session 操作、HTTP Server/SDK、ACP；有多供应商登录 | 优先已验证的本地 Server API + 事件流；通过 `/doc` 核对版本契约 | 官方文档确认，当前机器未安装实测 |
| Hermes | CLI 与 `hermes acp`，ACP 可传工具、消息和审批 | ACP stdio；缺依赖时给安装/修复入口 | 官方文档确认，当前机器未安装实测 |
| Gemini | 有本机启动器线索 | 先检测，适配与验收单列；不能因为图标存在就宣称可运行 | 本轮仅发现启动器 |

OpenCode 的命令、API 和数据位置按实际版本核对，文档已经存在 v1/v2 路径差异。服务默认只绑定 loopback，使用本机认证和端口管理；列出与读取已有 session 后再接续，不在扫描时启动工作。[CLI](https://opencode.ai/docs/cli/) · [Server](https://opencode.ai/docs/server/) · [Windows](https://opencode.ai/docs/windows-wsl/)

Hermes 现在已有原生 Windows 安装文档，不能沿用旧印象说“只能 WSL”。`hermes acp --check` 可检测 ACP 依赖；ACP 模式保留 Hermes 自身配置、记忆、技能和会话存储。应进行能力协商，不能假定每个已安装老版本都支持全部能力。[安装](https://hermes-agent.nousresearch.com/docs/getting-started/installation/) · [ACP](https://hermes-agent.nousresearch.com/docs/user-guide/features/acp/) · [会话](https://hermes-agent.nousresearch.com/docs/user-guide/sessions/)

## 六、订阅不能跨运行器简单复制

Codex `account/read` 能返回认证类型及方案；`account/rateLimits/read` 可提供额度窗口。这是“账号状态可显示”的依据，不意味着 Yxi 应提取令牌直接请求模型服务。[App Server](https://learn.chatgpt.com/docs/app-server) · [Authentication](https://learn.chatgpt.com/docs/auth)

Claude Code 的环境 API Key 可能影响有效认证，`--bare` 等运行模式也有差异。应委托其原生认证入口并核对有效状态；不得拿“已安装 Claude”当作“已订阅”。不同订阅对程序化使用的额度规则会变，应在发版前复查，而不是写死成永久免费。[Claude authentication](https://code.claude.com/docs/en/authentication) · [程序化使用](https://code.claude.com/docs/en/headless) · [订阅说明](https://support.claude.com/en/articles/15036540-use-the-claude-agent-sdk-with-your-claude-plan)

OpenCode 文档提供 ChatGPT 登录选项，但这是 OpenCode 自己的授权流程，不是许可 Yxi 直接拷贝 `.codex/auth.json`；Hermes 使用其 provider 登录/配置。跨厂商不承诺共用一份订阅、额度或原生会话格式。[OpenCode providers](https://opencode.ai/docs/providers/)

## 七、模型映射界面为什么偏离参考

CC Switch 在 `ClaudeFormFields.tsx:964` 使用 `120px / 1fr / 1fr / 104px` 四列；行控件 `h-9`（36px）、列间距 8px；角色本身也有同高的灰底容器；输入右端有模型下拉。默认模型独立置于表格下方。源码使用剥离后的纯模型 ID 显示，1M 独立控制。

Yxi 当前 `RoutesPane.kt`：角色是比例 `weight(0.6)`，实际模型列包含输入框加下面一行 picker，但显示名称只有输入框；默认 Material 文本框最小高度和额外文字扩大整行。`ProviderModelField.kt` 同样把选择器放在字段下方。这直接解释了截图里的垂直错位与空白，不是微调一个 padding 就能对齐。

参考许可证是 MIT，可移植布局思想或源码并保留版权声明；Yxi 使用 Compose，不能把 React 的 CSS 类名机械替换成 Material 默认组件后宣称复刻完成。细节验收见第二份 PRD。[CC Switch 源码](https://github.com/farion1231/cc-switch/tree/56df6513943062e8ca9eb80d8928eef7cf08a76d)

## 八、尚未证实、不得先承诺

1. 多个客户端同时控制同一个 Codex 原生会话的可靠互斥，及外部进程占用探测；另起 app-server 的 `thread/loaded/list` 不能代表全机状态。
2. 桌面专属动态工具、浏览器、账号插件在 Yxi 中恢复后的实际可用性。
3. 改动 CC Switch 路由后，桌面当前轮次是否立即使用新路由；须新建/续聊/重启分别验证。
4. OpenCode、Hermes 在此 Windows/WSL 安装上的认证、续聊、审批与取消实测。
5. 全部截图中的项目分组、置顶与云端/远端任务是否有公开完整接口；本轮23条不是“全账号全部历史”。
6. 官方订阅强制模式在外部路由已配置、组织策略限制、账号失效时的具体兼容矩阵。
