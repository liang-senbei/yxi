## 2026-09-24 Windows 独立进程会话锁验收

- ClaudeSessionLeaseTest新增独立Java进程持锁场景，父进程使用产品acquire验证排他；分别正常退出和强制结束持锁进程，再验证重取锁，保持锁文件不删除。
- 首次立即重取测试失败，进一步测得Windows强制结束后有短暂锁释放延迟。测试仅在确认子进程已退出后最多3秒等待释放，本次正常退出0次重试、强制退出1次20ms重试。
- Windows最终2项测试通过，0失败/错误/跳过，17秒构建；测试报告明确输出两种退出方式和重试次数。产品仍采用tryLock，取不到时拒绝第二个写入连接，不自动重启CLI。
- 此证据为独立进程文件锁；不验证外部Claude使用同一锁，也不替代原生连接占用整链及恢复UI验收。未发布。

## 2026-09-24 Claude 原生连接占用锁

- ClaudeSessionLease用OS文件锁协调相同规范runtimeHome+sessionId，锁文件在Yxi数据目录，只存哈希文件名；不删除锁文件以避免inode分裂。启动CLI前取得锁，失败启动关闭锁，进程退出后释放；close请求本身不提前释放仍在退出中的进程所有权。
- Windows编译及ClaudeSessionLeaseTest 1项、LocalClaudeTasksTest 5项通过，0失败/错误/跳过，22秒。验证同身份拒绝重复占用、不同home/session独立、释放后重取及重复close。
- 当前基础测试在同一JVM，跨独立进程竞争/退出释放及原生连接集成仍需验证；此锁只协调Yxi，不证明外部Claude客户端空闲。恢复UI与外部占用检测尚未完成，未发布。

## 2026-09-24 Claude 任务管理器显式恢复

- LocalClaudeTasks.resume接入已核对的恢复服务，串行化创建/恢复；复用已有ready控制器，拒绝原连接仍在处理或队列存在Delivering/Unknown/InProgress。成功后保存有效模型并建立控制器，不发送本地队列项。
- 创建与恢复共用attach，确保模型列表/通知/模型持久化接线一致；关闭管理器取消恢复准备并释放所属控制器。
- Windows编译及LocalClaudeTasksTest 5项通过，0失败/错误/跳过（30秒）。新增验证Unknown时零启动、重复恢复只建立一个连接、本地指令不重放、模型更新及关闭释放。
- 该互斥限同一管理器；跨进程占用、原生历史正文及恢复UI仍待接入和验收。未发布。

## 2026-09-24 本地 Claude 恢复前身份检查

- LocalClaudeSubscription.resume验证本地official:claude记录、用户/平台、运行器配置目录、原工作目录和规范UUID，再创建恢复连接；回传requestedSessionId必须与记录匹配。仍走同一连接的登录身份及有效官方端点检查，无prompt或队列重放。
- Windows构建29秒通过，LocalClaudeTasksTest 4项、LocalClaudeSubscriptionTest 2项，均0失败/错误/跳过。覆盖六类启动前不匹配拒绝、匹配连接核对与关闭、错误会话身份立即关闭且不发送控制请求。
- 此项是恢复服务接线前置；管理器并发所有权、历史正文和恢复按钮仍待实现。未发布。

## 2026-09-24 Claude 关闭后原生恢复验证

- de214938f14c300a9ab5f50e58ae4ae3df03b4ed为LocalClaudeControlTransport增加显式resumeSessionId；只接受完整规范UUID，使用--resume而非创建新的--session-id。默认创建行为保持原有逻辑。
- 构建run.yrWGOL；ClaudeControlClientNativeTest run.JJ9n8p 1项通过，0失败/错误/跳过，4.068秒。关闭旧CLI并核对PID退出后，启动新进程恢复原ID；初始化/settings没有模型请求，显式下一轮保持session_id且包含首轮及停止后继续的历史。
- 证据 `.artifacts/claude-control/claude-resumed-session.json`。使用CLI 2.1.280、容器合成凭据、回环模型；恢复UI、控制器所有权及历史正文接线仍待完成，不代表恢复功能整体交付。未发布。

## 2026-09-24 模型菜单交互与选中样式

- 893aa4a277a81275c7e5d884eff99fbcef3054ab为Claude模型菜单加入8dp圆角灰色加深选中背景，展示resolvedModel而非角色别名。
- 构建run.XO4mC6，ClaudeConversationUiTest run.PL6i7d 1项通过，0失败/错误/跳过，7.971秒。点击打开菜单、键盘选择第二项后，控制器和任务记录均更新实际模型；set_model仅一次，没有新增user请求，既有三轮审批/停止流程继续通过。
- 已查看 `.artifacts/claude-conversation-ui/claude-conversation-model-menu.png` 和 model-selected.png，选中背景及新模型显示正常。该项为Linux窗口+协议替身；真实CLI模型请求另有证据，Windows交互和真实账号验收仍未完成。未发布。

## 2026-09-24 Claude 真实 CLI 模型切换请求验证

- 源码220f43a44a8a13a93cdd653dfbc1c7484d52c5b9，隔离构建run.VsQGgY，ClaudeControlClientNativeTest run.iKrarU：1项通过，0失败/错误/跳过，3.397秒。
- CLI 2.1.280调用set_model后，get_settings.applied.model与下一轮回环HTTP请求model均为claude-haiku-4-5-20251001；原生session_id一致，请求仍含切换前历史。后续批准/拒绝、interrupt及控制器持久化回归继续通过。
- 证据 `.artifacts/claude-control/claude-model-switch.json`；测试使用独立容器、合成凭据及回环服务，不证明真实订阅额度。实际菜单点击/选中样式和Windows交互仍待验收，未发布。

## 2026-09-24 本地 Claude 模型菜单与索引接线

- LocalClaudeTasks从原生初始化models的resolvedModel取真实ID并去重；缺少解析结果时不猜测别名对应模型、不展示不可确认选项。创建控制器携带当前模型，确认后同步持久任务索引。
- ClaudeConversationPane加入模型菜单并显示当前实际模型。ClaudeTaskController在空闲、无审批时才允许切换，校验选择在运行器列表内，重新确认官方端点及有效模型；保存失败停用连接，不把选择伪装为成功。模型切换计入退出操作，发送与指令队列按钮在切换期间禁用。
- Windows编译及ClaudeTaskControllerTest 6项、LocalClaudeTasksTest 3项通过，0失败/错误/跳过。新增覆盖未列出模型拒绝、确认后更新、索引保存回调失败不更新显示且禁止继续发送。
- 尚需真实CLI切换与模型请求核对、实际菜单点击与选中样式验收。未发布，不能据此宣称模型切换整体完成。

## 2026-09-24 Claude 模型切换控制接口

- 已核对官方Python SDK的_internal/query.py：set_model控制请求携带model字段。既有CLI 2.1.280初始化证据包含models.value及resolvedModel；后续菜单应呈现实际模型，不能把别名当成实际第三方模型。
- ClaudeControlClient增加setModel，等待控制响应后再次get_settings返回实际applied.model；切换期间与prompt互斥，禁止并发第二次切换。未确认设置时关闭连接，避免继续在未知模型状态发送。
- Windows编译及ClaudeControlClientTest 8项、ClaudeTaskControllerTest 5项通过，0失败/错误/跳过（31秒）。新增测试核对model载荷、ACK不等于有效设置确认、切换期间不发送prompt。
- 此项仅为协议层；真实CLI切换、会话控制器/菜单/索引同步尚待接入，不算完整模型切换验收。未发布。
- 接口来源：https://github.com/anthropics/claude-agent-sdk-python/blob/main/src/claude_agent_sdk/_internal/query.py

## 2026-09-24 本地 Claude 任务通知接线

- ClaudeTaskController增加通知回调；新有效审批、持久化完成/失败/中断和投递未确认触发通知，重复审批ID不重复提示，无效审批不再误显示等待审批。关闭后不通知，通知异常不影响队列处理。
- LocalClaudeTasks把记录身份传至AppState统一Notify入口，使用taskKey定位并沿用通知过滤。通知正文只带任务显示名，不带命令或模型回复。
- Windows编译及ClaudeTaskControllerTest 5项通过，0失败/错误/跳过（构建33秒）。成功/失败通知和回调抛错不破坏结果记录已验证；系统通知弹出/点击及审批通知重复场景仍需界面验收。未发布。

## 2026-09-24 停止按钮实际点击与继续对话

- `b1332dc007d6906ceecc9995671630b6acb4af98` 扩展ClaudeConversationUiTest协议替身为逐轮唯一回执/审批ID，interrupt返回原生格式中断结果。
- 隔离构建run.m8kOyF；测试run.xo9GIL，1项通过，0失败/错误/跳过，7.215秒。实际点击停止按钮只发一次interrupt、不额外答应工具；审批清空、localOperations归零，队列最终Completed/Interrupted/Completed。第三轮通过Enter继续并在UI拒绝工具，正确回传deny。
- 已查看 `.artifacts/claude-conversation-ui/claude-conversation-stopped.png` 和 continued.png：停止提示及继续回复可见，审批卡片清除。本项为Linux窗口+协议替身；原生CLI停止链另有此前证据，Windows交互和真实账号仍未完成。未发布。

## 2026-09-24 长输入审批卡片

- `6c0459e4e94ad350d338e46aac1ca62757a1c760` 提取 ClaudePermissionCard；完整可选输入在最高220dp区域内滚动，批准和拒绝按钮放在区域外，响应/停止期间禁用。服务器Agent因429额度限制停止且无草稿交付，此项由主任务完成。
- Windows compileKotlin通过（21秒）；隔离构建run.SIzfDY，ClaudeConversationUiTest run.fn82f4 1项通过，0失败/错误/跳过。60行命令覆盖新建、Enter发送、允许本次以及updatedInput完整性。
- 已查看 `.artifacts/claude-conversation-ui/claude-conversation-approval-long.png`，长内容有滚动条且审批按钮可见。测试为Linux窗口+协议替身，不是Windows实际账号验收；未发布。停止按钮点击回归仍待补齐。

## 2026-09-24 停止功能真实控制器回归

- `25e5ce94c0351c4c27301d3bfd42b46ba1fa3865` 为控制器增加独立延迟响应场景，真实调用cancelTurn后核对持久Interrupted、停止状态清理，再继续发送新轮次并核对回复文本。回环fixture按合并消息的最后一个显式标记回复，避免重放旧停止标记造成伪延迟。
- 构建run.bM7lz2；ClaudeControlClientNativeTest run.p6Y51o 1项通过，0失败/错误/跳过，3.398秒。重新读取队列得到Completed/Completed/Interrupted/Completed，均Accepted，原生会话继续可用。
- 最新回执在 `.artifacts/claude-control/claude-controller-queue.json`。这是实际控制器+原生CLI+回环模型，不是Windows停止按钮点击或真实账号验收；未发布。
- 已向确认属于cc-yxi的%8派发审批卡片长命令排版独立草稿任务，要求不改主仓库、不运行宿主机AI/集成测试；等待复核交付。
## 2026-09-24 停止按钮与发布状态核对

- 本轮实时读取 https://yxi.keuury.com/desktop/releases.win.json，最新Full仍为1.4.14（SHA256 9D0C66602C664A9344C7A7B5FD81D39A82D61949563F78A256CDE5248795394C），近期开发改动未发布到更新源。
- 停止按钮接入controller.cancelTurn，停止期间禁发/禁审批；仅本地发起停止且原生terminal_reason=aborted_streaming时持久化Interrupted，否则按原生成功/失败处理。发送前停止保留Local；等待interrupt应答期间继续计入退出检查。
- Windows构建与ClaudeTaskControllerTest 5项/ClaudeControlClientTest 7项全部通过，0失败/错误/跳过，45秒。覆盖停止ACK不结束轮次、原生中断回执、非用户中断不误标取消、前置检查中停止不发prompt。界面停止点击与真实控制器停止整链仍待验收。
- 未发布新版本，当前Windows开发包仍沿用1.4.14元数据，不代表线上更新已完成。
## 2026-09-24 Claude 原生 interrupt 回执

- `24f2818a1f6af1334b33ecddfd21a16f80fff16e` 加入显式interrupt控制请求，重复停止去重，停止期间禁止继续答应工具审批；仅收到interrupt应答不会完成prompt，仍等待原生result。
- Windows ClaudeControlClientTest 7项通过，无失败/错误/跳过。构建run.1aQ0tK；ClaudeControlClientNativeTest run.hLDQKD 1项通过，0失败/错误/跳过。
- 真实CLI在回环服务延迟响应期间被中断，result为subtype=error_during_execution、is_error=true、terminal_reason=aborted_streaming，随后同一session_id继续下一轮成功。证据 `.artifacts/claude-control/claude-control-interrupted.json`。
- 该结果用于后续控制器和UI停止状态接线，不能把interrupt ACK当作停止完成；当前停止按钮尚未接入，未发布。
## 2026-09-24 本地 Claude 新建到审批界面验收

- `f26da74e7335217fc77edd322cab14e0f164e4bd` 为AppState提供本地Claude管理器工厂注入（正式默认不变），新建弹窗可接受布局modifier；新增完整组件流程测试。
- 构建run.VjKdBU；ClaudeConversationUiTest run.aZWo6c 1项通过，0失败/错误/跳过：实际点击新建、原生协议fixture核对后创建索引但无user消息；Enter仅发送一次并清草稿；待审批localOperations=1；键盘选择允许本次正确回传allow；轮次Completed后计数归零。
- `.artifacts/claude-conversation-ui/claude-conversation-approval.png`和completed.png已目视：审批只有允许本次/拒绝，完成回复Markdown正常。此项为Linux窗口+协议替身，真实CLI另有回归；不是Windows真实账号端到端验收。
- 停止、模型切换、历史恢复及其余PRD项仍待继续，未发布。
## 2026-09-24 本地 Claude 新建与对话预览接线

- `e5014ef9` 将LocalClaudeTasks接入AppState惰性管理、运行任务计数、退出清理和本地任务定位；本地Claude安装项新增官方对话预览入口，新建弹窗核对后登记但不自动发送。
- 新增ClaudeConversationPane：共享草稿、Enter发送/Shift+Enter换行、持久指令条、Markdown文本、原生工具输入及明确的允许本次/拒绝按钮；页面切换不取消控制器所属发送任务，断开记录不自动恢复或重发。
- Windows createDistributable及LocalClaudeTasksTest 3项/ClaudeTaskControllerTest 3项通过，0失败/错误/跳过。`4b516e00`随后补新消息跟随、上滚停止跟随和回到最新，Windows应用目录重建通过（28秒）。
- 新页面尚未做完整点击/滚动/审批UI验收；暂停原生轮次、模型选择、恢复历史、附件等仍需继续接入。真实官方订阅额度未验证，当前为未发布开发预览。
## 2026-09-24 本地 Claude 会话登记与明确身份

- `bfd02a3383736473dc66ad423dea7fea5a53e328` 原生传输分配UUID并传--session-id；控制客户端可绑定requestedSessionId，发送和原生回执均核对身份。LocalClaudeSubscription默认保留该绑定。
- 新增LocalClaudeTasks：准备连接核对通过后保存claude/official:claude索引与实际模型，交给持久控制器；关闭时取消正在准备的子作用域并关闭所属连接，重读索引不启动或重放。现有通用任务索引允许claude引擎。
- Windows LocalClaudeTasksTest 3项及ClaudeControlClientTest 6项通过，无失败/错误/跳过；覆盖同名不同ID、索引重读无启动、认证失败无任务、关闭期间取消。
- 构建run.5MoWtK；ClaudeControlClientNativeTest run.NJDsdh 1项通过，0失败/错误/跳过，指定UUID与真实首轮/续轮回执一致，已有上下文/审批/队列回归通过。
- 管理器尚未接入AppState和正式新建/对话界面，真实官方订阅权益未验证；未发布。
## 2026-09-24 Claude 持久指令与消息控制器

- 新增ClaudeTaskController：发送前配置核对、先持久化Delivering再写原生输入、消费文本/工具/审批事件、等待结果消息已处理后保存Accepted及Completed/Failed。发送前失败保留Local；发送后未确认记Unknown并关闭连接，不重发。审批由显式动作回传。
- Windows ClaudeTaskControllerTest 3项及ClaudeControlClientTest 6项通过，无失败/错误/跳过，覆盖落盘先于IO、消息先于完成记录、成功/失败回执、前置检查失败、断连后Unknown与防重放。
- `22b89a6720e26af422e059caf5e2b6e688e00517` 构建run.kSXadZ；ClaudeControlClientNativeTest run.NKsGVw 1项通过，0失败/错误/跳过。真实CLI通过控制器完成两轮（含拒绝Bash），重新加载队列得到两条Accepted/Completed且原生turn UUID不同，消息已消费、审批清空、拒绝文件未生成，PID回收。
- 证据 `.artifacts/claude-control/claude-controller-queue.json`及Windows XML。原生测试使用回环端点校验替身，不代表真实官方订阅权益；会话索引和正式对话UI尚未接线，未发布。
## 2026-09-24 Claude 原生工具审批回传

- 控制客户端接入can_use_tool和control_cancel_request：只在活跃轮次登记请求，原始input私有快照，显式允许本次或拒绝，不发送updatedPermissions；重复/已取消请求不能再次审批，结果到达后清理残余请求。原生传输启用permission-prompt-tool stdio。
- Windows ClaudeControlClientTest 6项通过，无失败/错误/跳过，覆盖无自动答复、输入快照不可被UI副本改写、一次性回传和取消失效。
- `65c8c168cbe642b1edef0847b741e86bd397ec97` 构建run.1FIPT7；ClaudeControlClientNativeTest run.gUQK7T 1项通过，0失败/错误/跳过。真实Claude在测试Bash写文件前等待原生审批；允许一次后文件为allowed，拒绝后另一文件不存在，两轮均有结束回执，既有上下文/配置/进程回收断言通过。
- `.artifacts/claude-control/claude-control-permission-*.json`保留回执。模型为回环fixture，审批UI与持久任务控制器尚未接线；未发布。
## 2026-09-24 Claude 控制连接原生文本轮次

- 新增显式prompt调用：仅初始化后的连接可发送，单轮CAS锁、1MiB文本限制、原生result完成回执、结果UUID去重及session_id一致性；超时/取消/断连关闭连接，不自动重发。审批仍不自动允许。
- Windows ClaudeControlClientTest 5项通过，无失败/错误/跳过，新增并发发送拒绝、两轮身份保留及超时后禁止重放。
- `df0475c1e122109df92ae129a39d997624497649` 构建run.yulFTG；ClaudeControlClientNativeTest run.JVQXd3 1项通过，0失败/错误/跳过。先读取配置且无模型messages，再新建原生控制会话显式发送两轮，session_id相同、第二轮HTTP消息包含第一轮上下文、原生result success，关闭PID消失。
- `.artifacts/claude-control/claude-control-second-turn.json`保存回执。模型端点为隔离回环fixture；任务持久化、审批及正式界面发送仍未接通，真实订阅权益未验证，未发布。
## 2026-09-24 同连接核对本地 Claude 身份与有效端点

- `9d0d07e7d87f9062672de317d405fad1234109f4` 新增LocalClaudeSubscription.prepare：在同一原生控制连接上initialize读取account来源，再get_settings核对effective端点/认证覆盖；失败关闭，成功返回仍存活的Prepared连接供后续任务使用。无用户prompt写入。
- 本地检查弹窗默认接入该准备流程，并在结束时关闭连接；成功文案明确认证与有效端点已核对但订阅额度未验证。服务器检查仍保留其身份检查范围。
- Windows LocalClaudeSubscriptionTest 2项 + ClaudeSubscriptionSettingsTest 5项通过，无失败/错误/跳过；覆盖成功连接保留、身份/端点冲突关闭及请求序列。
- 构建run.8kOC87；ClaudeControlClientNativeTest run.do0YV4 1项通过，0失败/错误/跳过。真实Claude在OAuth身份正确但effective为回环端点时被准备流程按官方端点错误拒绝，进程回收、无模型messages。
- 正式任务发送/权限交互与真实官方订阅权益尚未完成；未发布。
## 2026-09-24 原生 Claude 控制客户端

- `817bec61d1c4868a00a380bde6505015e531bc8c` 新增ClaudeControlClient与LocalClaudeControlTransport，使用Claude stream-json控制协议（非ACP），独立argv/环境/进程所有权，UUID响应关联、2MiB单行限制、30秒请求限制、断连失败及清理。当前只开放initialize/get_settings，不发送用户prompt；未知交互请求关闭连接，不自动批准。
- Windows ClaudeControlClientTest 3项通过，0失败/错误/跳过：乱序回复正确关联、原生错误不泄露payload、断连或未支持交互让待处理检查失败且无批准回传。
- 构建run.uR1Xll；ClaudeControlClientNativeTest run.Qdk0Ql 1项通过，0失败/错误/跳过。正式客户端连接原生Claude2.1.280，读取effective实际回环端点，该端点被官方线路校验正确拒绝；没有模型messages，关闭后原生PID消失。
- 证据 `.artifacts/claude-control/claude-client-effective-settings.json`及Windows XML。尚未接入完整任务发送/审批/历史流程，未发布。
## 2026-09-24 Claude 有效官方线路校验

- 新增requireOfficialRoute，消费原生get_settings的effective数据：只接受HTTPS api.anthropic.com根端点（默认或443端口），拒绝userinfo/query/fragment/其他路径与伪装域名；检查API/Bearer/custom headers/profile/federation/云开关、helper、网关和登录方式冲突。错误不回显地址中的凭据值。
- `dd972d2e7cbb8e927dbf4a4d7e349e7eaa70539a` 进一步将空白字符凭据视为未解决覆盖，不能当作已清空。Windows ClaudeSubscriptionSettingsTest 5项通过，0失败/错误/跳过，21秒构建；XML保存在 `.artifacts/windows-claude-subscription-guard/`。
- 此校验函数尚未和原生控制连接/实际任务发送连成完整流程，不能据单元测试宣称官方订阅应用已完成；真实权益也未验证。未发布。
## 2026-09-24 Claude 有效配置控制接口实证

- 固定官方2.1.280二进制静态检查发现get_settings控制请求及连接类型限制，不能仅凭字符串认为可用。`7bf9fa3a`首跑run.Y4X9Dw初始化超时，未得到接口结论。
- `0555d2c844b587d201e8d3204a45c54908a04dcd` 将原生启动端点指向隔离回环服务、保留诊断控制帧；构建run.YFuYjD，ClaudeSettingsControlNativeTest run.qy3aKc 1项通过，0失败/错误/跳过。
- 手动核对原生响应：initialize成功，get_settings success返回effective（含真实合并后的ANTHROPIC_BASE_URL及认证env）、sources（本例flagSettings）和applied模型/effort。测试只发initialize/get_settings，回环服务未收到模型messages。
- 证据 `.artifacts/claude-control/`。这证明当前版本可在发送前读取有效配置；尚未集成到生产会话和验证托管端点冲突。原生账号权益/正式订阅应用仍未完成，未发布。
## 2026-09-24 服务器认证检查取消与错误隔离

- `f749a8a8` 新增真实SSH下取消、超大输出、无效JSON、非零退出测试。首跑run.HFjmLB中原生身份检查通过，取消场景失败：JSch读取被中断时抛InterruptedIOException，未转换为协程取消。
- `96a95d7bb7ee0e9a3460fb67bf8d880f57f80308` 在有界读取作用域内先检查协程状态，仅已取消时按取消传播；非取消IO错误仍失败，finally继续关闭所属通道。
- 构建run.WJaR89；RemoteClaudeSubscriptionProbeTest run.je49Qp 2项通过，0失败/错误/跳过。覆盖四种异常及原生Claude身份检查；远端PID消失、原SSH仍可执行、无关sleep存活，合成私密响应不进入错误提示。
- Windows真实桌面交互和正式订阅任务应用仍未完成，未发布。
## 2026-09-24 服务器 Claude 认证检查界面接线

- 服务器OfficialProviderCards接入RemoteClaudeSubscriptionCheck，显示主机身份、服务器绝对工作目录，连接不可用时禁用入口；本地与远端复用有取消/防重复提交的弹窗，分别验证本机路径和服务器路径。
- Windows createDistributable及ClaudeSubscriptionSettingsTest通过（42秒，3项校验0失败/错误/跳过）；仍非Windows真实鼠标验收。
- `662e2787c068b8d2f2d94cb233aaccf4ec643a74` 新增真实窗口+隔离SSH+Claude2.1.280点击测试；构建run.U9NAud，RemoteClaudeSubscriptionUiTest run.CSKTFn 1项通过，0失败/错误/跳过，单次点击到服务器probe，原SSH保持、没有.credentials.json新增。
- `.artifacts/claude-subscription-ui/remote-claude-subscription.png` 已目视确认服务器身份和结果说明。仍只核对认证来源，不代表订阅额度/有效端点/任务配置应用已完成；未发布。
## 2026-09-24 服务器 Claude 认证探测

- `35de0b4571f43207fb54f7f49d4f1a44a1a2f843` 新增verifyRemote：按服务器目录解析原生Claude，通过独立SSH exec和Python监督进程执行临时订阅配置下的auth status；仅过滤远端子进程环境、不传本机凭据，15秒服务端/17秒客户端限制、64KiB上限、退出回执及通道清理。
- 构建run.3Tw4my；RemoteClaudeSubscriptionProbeTest run.YDkJrN 1项通过，0失败/错误/跳过。真实隔离SSH+Claude2.1.280中，无OAuth明确拒绝、有合成OAuth通过；原SSH环境API变量仍完整、连接可执行，未生成.credentials.json。
- 服务器UI入口尚未接入，远端取消/异常输出和真实账号权限仍待进一步验收。只核对身份，不证明端点/订阅权益，未发布。
## 2026-09-24 Windows 认证弹窗点击验收未通过

- 新增YXI_SUBSCRIPTION_UI_FIXTURE显式测试入口，Test进程的user.home/HOME/USERPROFILE/APPDATA/LOCALAPPDATA均指向专用profile，界面检查函数为替身，不启动原生AI、不读取真实登录。修正Gradle java扩展遮蔽java.io.File的编译问题后，Windows测试可编译执行。
- 两次窗口回归均在等待打开弹窗处超时，检查函数尚未调用；加入窗口toFront/requestFocus后仍失败。截图 `.artifacts/windows-subscription-ui/results/claude-subscription-success-failure.png` 全黑，XML `.artifacts/windows-subscription-ui/failed-test.xml` 为1失败，不能宣称Windows点击验收通过。
- 当前交互桌面是否可用尚未确认，已异步询问用户是否锁屏/远程桌面断开；不推断全黑必定由锁屏导致。保留已通过的Linux窗口验收与Windows构建/存储/Chromium表面渲染证据，各自范围不扩张。未发布。
## 2026-09-24 认证检查重复点击与回归约束

- `71da9f89` 将busy置位和目标目录捕获移到点击同步路径，避免协程调度前重复启动检查；窗口测试连续点击两次，检查回调延迟期间必须只执行一次。进程异常测试明确排除TimeoutCancellationException，不能把测试超时算作有效拒绝。
- 构建run.RghUQA；probe run.fIWMbd 1项通过，0失败/错误/跳过。首轮UI run.F1VJIN在首次等待弹窗出现时超时，尚未开始检查。
- `cba52e02fbcd9397da7c6004437429bcb9fa196c` 用有界窗口显示/打开等待替代固定700ms首次点击；构建run.pDtFJQ，UI run.IfKR9w 1项通过，0失败/错误/跳过，覆盖成功/错误/取消及各模式双击只执行一次。
- 仍为Linux隔离窗口验证，Windows真实账号流程及订阅任务应用未完成，未发布。
## 2026-09-24 官方认证检查弹窗交互验收

- `1a55df34dab4660ccdba901660057de0c3fe8323` 为检查弹窗提供可注入检查函数与布局modifier；正式入口默认仍调用同一原生probe，测试不启动真实账号。
- 构建run.bJLeeq；ClaudeSubscriptionCheckUiTest run.novlvX 1项通过，0失败/错误/跳过，覆盖真实点击打开/开始、成功、错误、Escape取消挂起协程，每种模式仅调用一次检查，传入正确运行器与目录。
- `.artifacts/claude-subscription-ui/` 三张截图已目视，文案、错误、禁用输入/按钮和取消入口正常。这是Linux隔离窗口+检查替身，不代表Windows高DPI或真实账号端到端验收；任务订阅应用仍待接线，未发布。
## 2026-09-24 Claude 认证检查异常与取消清理

- `d3cfda5ba0bb3cfab0231075dcc0998016b11327` 在读取超过64KiB时即时停止探测，不再等15秒总时限；新增隔离Python进程fixture覆盖正确argv、API环境剥离、正常JSON、无效JSON、超大输出、非零退出与取消。
- 构建run.H4N4cv；ClaudeSubscriptionProbeTest run.1ifRdf 1项通过，0失败/错误/跳过，耗时0.523秒。各case原进程退出、无关sleep仍存活；错误提示未包含合成私密响应内容。
- 该测试证明探测器进程生命周期和失败语义，未实际点击Windows弹窗，亦未使用真实账号。正式任务配置应用与完整端点核对仍待完成，未发布。
## 2026-09-24 本地 Claude 官方认证检查入口

- `f49ad53f` 在本地工作台/配置的Claude安装项加入检查官方认证弹窗：明确目标运行器和工作目录、忙碌禁用/取消、成功与冲突提示；使用已验证临时配置和原生probe，不发送任务、不保存线路选择。指定发现的CLAUDE_CONFIG_DIR，支持原生exe或已发现的node+CLI argv组合。
- Windows createDistributable与ClaudeSubscriptionSettingsTest通过（36秒；3项测试0失败/错误/跳过）。该版本只完成界面编译与身份校验回归，未执行Windows真实账号检查或弹窗点击验收。
- 随后`fe98dfc8`仅将说明改为“不保存新的线路配置”，避免承诺原生CLI不会写入自身元数据；该文案未重新截图/打包。
- 正式任务的官方订阅选择/应用仍未接通，检查通过不代表额度或端点验证完成；服务器入口亦待接入。未发布。
## 2026-09-24 Claude 原生发送前认证探测

- `24b58e77` 新增ClaudeSubscriptionProbe：目标cwd/临时settings/过滤后env下执行auth status，不发送prompt；15秒限时、64KiB输出限制、取消检查、所属进程清理，原生状态不直接输出到用户日志。
- 首跑run.WxJfQj发现全局--settings放在auth子命令后无JSON；`5dda94c3`调整顺序后run.SaiVvx发现磁盘登录认证方法不是oauth_token。核对固定官方二进制后，`c32061db22c90a05af626eee455f203181cff149`识别claude.ai，仍要求无API来源和firstParty。
- 最终构建run.KO4NSl；ClaudeAuthenticationRequestTest run.hhlLrm 1项通过，0失败/错误/跳过。所有普通覆盖场景先通过原生认证检查且无模型请求，托管API场景被检查器拒绝；之后原生请求回归12组合通过。
- 第二次构建前空间不足，按归属标签、源码tag和无容器引用核验删除35个旧测试镜像，保留最新5个及两份依赖基线；审计removed-images-auth-probe.json，空闲约11GiB，未动生产资源。
- 此探测只核对凭据身份，不证明端点政策/订阅权益，且尚未接入正式任务入口。未发布。
## 2026-09-24 Claude 原生认证身份冲突校验

- `06c5ea9ebf065c5308cb777b5458c44fb10126a1` 增加 requireOAuthIdentity：严格要求原生布尔loggedIn、firstParty、无apiKeySource和已知oauth_token认证方式；对已实测的混合OAuth/API状态明确报错，不误判为订阅启用。错误文案不输出原生凭据内容。
- Windows 本机 ClaudeSubscriptionSettingsTest 3项通过，0失败/错误/跳过，覆盖混合凭据、未知/缺失/错误类型字段、非官方提供方，以及覆盖层不改原映射、不改权限顶层键。XML保存在 `.artifacts/windows-claude-subscription-guard/`。
- 此校验尚未连到实际启动入口，亦不证明端点、权益或全部托管来源。官方managed-settings文档本轮核对显示还需考虑注册表/MDM/远端策略来源，不能只查一个文件。https://code.claude.com/docs/en/managed-settings 。未发布。
## 2026-09-24 Claude 托管认证策略优先级

- `f989306c4efac3be42639999300c49d42e7271b0` 在隔离Docker内临时创建/etc/claude-code/managed-settings.json（先断言不存在，finally只删除本测试创建的文件），注入假API凭据与回环端点，和订阅覆盖层一起启动原生Claude2.1.280。
- 构建run.JoOklL；ClaudeAuthenticationRequestTest run.qhYX9V 1项通过，0失败/错误/跳过，覆盖12组合。托管场景实际api=true/oauth=false，证实托管认证优先于临时覆盖；其余普通配置场景OAuth与权限回归通过。
- 证据 `.artifacts/claude-auth-requests/claude-request-managed-overlay.jsonl`。该结果要求产品接线时检测并报告有效凭据冲突，不能因已选择订阅就显示启用；尚未实现完整托管/网关/profile检测，未修改真实策略、未发布。
## 2026-09-24 Claude 订阅启动云提供方残留处理

- `f31616068db74d88d24d2af8645efcd32c873791` 在进程环境移除CLAUDE_CODE_USE_BEDROCK/VERTEX/FOUNDRY，并在临时settings env中置空，处理普通用户/项目配置中的残留，不写原文件。
- 构建run.95uYqd；ClaudeAuthenticationRequestTest run.F0ZVh5 1项通过，0失败/错误/跳过，覆盖11组合。三云开关分别同时存在于进程环境、用户配置和两份项目配置，实际请求均回到回环端点，api=false/oauth=true，原设置字节保全。
- 证据 `.artifacts/claude-auth-requests/claude-request-cloud-*.jsonl`。前有的凭据/禁读/磁盘登录回归同轮通过；企业托管策略、网关和profile/federation仍需单独检测。未接入正式订阅入口、未发布。
## 2026-09-24 Claude 磁盘凭据回落验证

- `af1bb654d67d04cfa4ba2b67e3fab238087bfd63` 增加隔离CLAUDE_CONFIG_DIR下合成.credentials.json，移除OAuth环境变量后运行临时订阅覆盖；不使用真实账号。
- 构建run.K5hC38；ClaudeAuthenticationRequestTest run.g9l91D 1项通过，0失败/错误/跳过，覆盖8组合。新增场景实际请求api=false/oauth=true，磁盘凭据、用户设置字节保全，旧API/helper仍被覆盖。证据 `.artifacts/claude-auth-requests/claude-request-stored-overlay.jsonl`。
- cc-yxi审查报告已收回 `.artifacts/claude-auth-requests/overlay-review.md`，基线3f238dc1，不含本次新增磁盘场景；云提供方开关、托管设置优先级等剩余项继续有效。合成凭据回落不证明真实订阅有效。
- 下一步需处理云提供方残留并验证托管策略明确失败/不越权行为，之后才能接入选择入口。未发布。
## 2026-09-24 临时订阅覆盖层保留项目禁读规则

- `b9e1bb2e` 增加正常读取与项目禁止Read的对照；首跑run.LLhCCw失败在permission_denials数组断言。实际原生CLI将Read工具直接禁用，返回明确的disabled工具错误，而非填充permission_denials数组。
- `3f238dc13bbf77ceb7d5ed96becb1fec9478ce68` 改为验证原生tool_result.is_error及Read is disabled for this session，仍要求测试内容未进入后续请求；无禁读规则的对照必须读到文件内容。构建run.1eMLuD，run.360w3I 1项通过，0失败/错误/跳过，覆盖7个请求/权限组合。
- 项目禁读与临时订阅覆盖同时生效，原配置字节不变；证据 `.artifacts/claude-auth-requests/claude-request-permission-*.jsonl`。只验证固定版本的这一禁读规则，企业托管策略和磁盘登录仍待验证；未接入正式订阅选择入口，未发布。
## 2026-09-24 Claude 项目级线路冲突验证

- `3af6934f6af4129213cae77f4d4a9c5dd2efb54d` 在独立工作目录增加项目settings.json及settings.local.json，含冲突API/Bearer/无效端点和permissions字段，与用户级旧配置并存；运行临时订阅覆盖层。
- 构建run.iJoSu6，ClaudeAuthenticationRequestTest run.4UbJww 1项通过，0失败/错误/跳过，覆盖5组合。项目冲突场景实际请求api=false/oauth=true，用户和两个项目文件字节不变，旧密钥助手不执行。
- 证据 `.artifacts/claude-auth-requests/claude-request-project-overlay.jsonl`。仅证明此版本回环场景的请求与文件保留；permissions字段实际执行、企业托管策略、云提供方和磁盘订阅登录仍需验证。cc-yxi 正在独立复核遗漏，未发布。
## 2026-09-24 Claude 临时订阅凭据覆盖层

- `31a0f49c766760d0bbe9566c3e3ee4695ddaa25b` 新增 ClaudeSubscriptionSettings：进程环境移除API Key/Bearer/旧Base URL，临时 --settings 清空API凭据和apiKeyHelper并指定官方端点；不写入用户配置文件。该层尚未接入产品入口，不能独立证明订阅或企业策略兼容性。
- 原生Claude2.1.280测试新增旧settings.json内API Key、无效旧端点和密钥助手冲突场景，测试仅将覆盖层端点替换为回环协议服务。构建run.3ZrUKh；run.tSVVxf 1项通过，0失败/错误/跳过，覆盖4种请求组合。
- 覆盖场景实际请求api=false/oauth=true，用户settings字节未改变，助手标记未出现。证据 `.artifacts/claude-auth-requests/claude-request-overlay.jsonl`。先前混合凭据场景的API优先断言也通过。
- 构建最初因不足4GiB被拒；删除61份已完成、带org.yxi.test-isolation标签且无容器引用的context副本后空闲5.6GiB，报告/镜像/生产资源均保留。审计 `/root/.cache/yxi-isolated-tests/removed-contexts-subscription-overlay.json`。
- 磁盘订阅登录、项目/企业配置和真实官方端点仍待验证；未修改真实账号、未发布。
## 2026-09-24 Claude 原生请求凭据实证

- `d2c61bad` 新增真实Claude2.1.280到隔离容器回环Anthropic协议服务的三组合请求验证；首跑run.5ha2DM在测试脚本类加载器处NPE，未发请求。`52098d2f217ccb7b50ff9733101e6bc119c8ade2` 修正类加载器并保留测试输出。
- 构建run.gGCNOQ；ClaudeAuthenticationRequestTest run.6IWZL6 1项通过，0失败/错误/跳过；三组合都完成原生文本轮次。服务端仅保存测试凭据匹配布尔值，不存请求头值。
- 逐份JSONL核对：API单独存在为api=true/oauth=false；OAuth单独存在为api=false/oauth=true；两者并存为api=true/oauth=false。结合先前混合auth status的oauth_token，可确认该状态字段不能代表实际请求凭据优先级。
- 证据 `.artifacts/claude-auth-requests/`；仅针对该固定版本、进程环境、回环自定义端点。磁盘登录/项目及企业设置、真实订阅有效性尚未验证；真实官方订阅启动适配未完成，未发布。
## 2026-09-24 Claude 混合凭据状态与 Grok 审计

- `cc46f378` 扩展原生Claude2.1.280测试到空配置/API/OAuth/两者并存，首跑 run.5B77q2 失败，推翻了将auth status等同请求优先级的假设：两者并存时authMethod仍为oauth_token，同时apiKeySource=ANTHROPIC_API_KEY。
- `cf82a1925994d842889efba7a8ecb01f15457dc7` 明确测试仅校验原生状态报告，不推断真实请求用哪种凭据；构建run.qpKyNr，run.WuMJL1 1项通过，0失败/错误/跳过，覆盖4种隔离环境组合。合成凭据无回显，结果在 `.artifacts/claude-auth-native/`。
- cc-yxi Grok审计已收回 `.artifacts/grok-native/official-profile-audit.md`：官方文档、二进制字符串与既有未认证握手证据分开标注；广告模型/认证方法不是已登录证明，未有订阅完成会话的正向验证。未经执行验证的偏好字段不能直接作为产品配置实现。
- 官方订阅真实启动适配仍未完成；下一步需要以隔离回环请求验证凭据和路由，并覆盖磁盘/项目配置优先级。未修改真实账号，未发布。
## 2026-09-24 官方订阅适配核对与 Claude 原生认证探测

- 核对当前 OfficialProviderCards/Profiles：除Codex本地专门进程校验外，内置卡片仍主要为说明，不能作为全部运行器已默认应用官方订阅的证明。已委派cc-yxi进行Grok官方认证来源/线路验证能力审计，尚在执行。
- `4fa64bd3c7b66365d8655dfff7621042424ce64a` 增加 ClaudeAuthenticationNativeTest，固定Claude2.1.280、隔离Docker网络和HOME，执行auth status --json。构建run.kabH2t；run.y2ioVk 1项通过，0失败/错误/跳过。
- 空配置返回 loggedIn=false/authMethod=none；合成API Key返回 loggedIn=true/authMethod=api_key/apiKeySource=ANTHROPIC_API_KEY，两者apiProvider均为firstParty。没有密钥回显。因此loggedIn或firstParty不能判定订阅有效，也不证明真实账号可请求。证据 `.artifacts/claude-auth-native/`。
- 官方认证优先级资料：https://code.claude.com/docs/en/authentication#authentication-precedence 。后续适配必须分别核对原生认证来源与请求路由，不能仅清一个环境变量。当前未应用真实账号配置，未发布。
## 2026-09-24 本地 ACP 通知接线

- `dfb5cc6c18a0f617cc82dbf23fbd3b0d0606c603` 本地ACP管理器将控制器通知连到Notify现有偏好过滤，使用完整taskKey和用户显示名称。Main新增本地登记优先的通知定位，支持本地Codex/OpenCode/ACP记录，否则查找远端，不新建或恢复原生会话。
- Windows本机执行 LocalAcpTasksTest 4项及 AcpTaskControllerTest 4项，共8项通过，0失败/错误/跳过。三种ACP引擎的内存协议fixture均确认创建不通知、原生结束回执后通知携带正确taskKey；既有取消/未知/重连语义回归通过。
- XML证据 `.artifacts/windows-local-acp-notifications/`。未启动原生AI或真实登录；本地通知点击路由本轮仅编译核对，实际Windows气泡与完整焦点/静音组合仍待验收。未发布。
## 2026-09-24 原生会话静音入口与 Windows 偏好验证

- `551b5f69` 在共享远端原生会话行补齐静音/恢复任务通知入口，适用于OpenCode与ACP；使用现有完整taskKey存储，不按显示名称静音。
- Windows 本机执行 `:desktop:test --tests app.yxi.desktop.WorkspaceNavigationTest`，7项通过，0失败/错误/跳过。新增原生会话跨服务器身份、静音重读、仅置顶通知及恢复通知持久化断言；原有归档待处理/损坏数据保护等回归一并通过。
- 证据 `.artifacts/windows-notification-551b5f69/TEST-app.yxi.desktop.WorkspaceNavigationTest.xml`。测试仅使用临时文件，不启动AI或SSH，不代表菜单点击和Windows真实气泡已验收。未发布。
## 2026-09-24 远端 ACP 通知触发

- `ee6742799c19d7a260aa04f7275318f596c6e583` 增加控制器通知回调，远端任务接入现有Notify偏好/静音过滤与完整taskKey定位。审批只在新增待处理项时提醒；原生轮次结束按完成/取消/需核对区分，超时及投递未知明确提醒，不冒充成功；关闭控制器不再发通知，通知异常不破坏队列结论。
- 构建 `run.kF5UUT`；AcpTaskControllerTest `run.6Mra9T` 4项通过，0失败/错误/跳过。覆盖审批到达、轮次完成、取消后排队审批不提醒、超时Unknown及通知回调抛异常仍保持投递锁定。
- 测试验证回调与队列语义，未发送真实桌面通知；Windows气泡/焦点/多任务通知竞争、本地ACP和OpenCode通知触发仍待验收或接入。未发布。
## 2026-09-24 远端任务通知定位

- `ac1c813ef98f340be166b07ed567551a8a42ef9c` 提取 AppState.openRemoteTask，Main 通知点击按完整持久任务key定位终端/Codex/OpenCode/ACP，保留服务器和现有控制器；归档任务切到归档视图，未知key不改变当前页面，不猜同名任务或创建替代会话。
- 构建 `run.1RSESK`；RemoteAcpCreationUiTest `run.RfF7qg` 1项通过，0失败/错误/跳过。新增未知key保持页面、ACP归档任务定位、同一Conn/控制器、没有额外session/new的断言。
- 这是隔离SSH fixture下的回调定位验证，未点击Windows真实通知气泡；ACP通知触发/去重/完整本地通知仍待接入和验收。未发布。
## 2026-09-24 Windows 内嵌浏览器原生验收

- 使用源码 `33fbc32073c6cdd50a41f35540c8e41019e2e576` 本机构建的 Yxi.exe，在全新隔离 profile 执行 --browser-smoke；测试入口位于账号、Updater 和服务器连接初始化之前，仅访问一次性127.0.0.1页面。
- 退出码0；browser native pixels ok、browser native render and live style ok、browser native shutdown ok 三个标记齐全。CJK标题与 LIVE_STYLE=40px 的 Chromium 表面截图已目视，非空白，样式修改生效。
- 证据 `.artifacts/windows-browser-33fbc32/result.json`、browser.png、stdout.txt、stderr.txt。这证明Windows原生Chromium提取/启动/渲染/脚本修改/关闭基础路径；不代表真实远端端口转发、右侧面板布局与页面选择回传完整验收。未发布。
## 2026-09-24 Windows 主机与凭据跨进程持久化

- 首次本机 --host-smoke 在旧测试断言 HostStoreNativeSmoke.kt:38 失败：实际存储已迁移到用户目录 `.yxi`，测试仍要求 Local/Yxi。保留失败日志 `.artifacts/windows-storage-a04d50c/`；没有改动真实用户配置。
- `33fbc32` 将原生检查对齐当前 `.yxi` 约定，同时核对旧主机明文清理、新加密文件存在及内容未泄漏。Windows createDistributable 重建通过（26秒，3任务执行）。
- 新隔离 profile 下四个独立进程全部退出0且匹配预期标记：host-smoke、host-reopen、credential-smoke、credential-reopen。覆盖旧目录迁移、主机改名/偏好重读、DPAPI加密、篡改拒绝、凭据轮换及注销后重登录的本地持久化。结果 `.artifacts/windows-storage-33fbc32/results.json`。
- 使用虚构主机和合成凭据，未连接服务器或真实登录；只证明Windows本地存储行为，不代表服务端授权、模型请求或完整升级验收。未发布。
## 2026-09-24 当前开发源码 Windows 本机构建与启动

- 源码 `a04d50c` 在本机 Windows 完成 `:desktop:createDistributable`，13项任务执行，BUILD SUCCESSFUL（3m46s）。最初 PowerShell 未引用带点的 -P 参数导致任务名被拆分；加引号后发现 core 要求 JDK17、本机仅有JDK21。
- 从 Adoptium 官方API下载 Temurin17 ZIP，按API返回SHA256验证，解压到 `.artifacts/windows-toolchain/jdk17`，通过命令行 `-Porg.gradle.java.installations.paths=...` 传入；未更改系统Java和用户环境变量。元数据见 `.artifacts/windows-toolchain/temurin17.json`。
- 产物 `android/desktop/build/compose/binaries/main/app/Yxi/Yxi.exe` 在新建 `.artifacts/windows-native-a04d50c/profile` 下运行 --smoke，退出码0且日志有 smoke ok。HOME/USERPROFILE/APPDATA/LOCALAPPDATA/user.home 均隔离，父进程环境在 finally 恢复；未安装或覆盖现有应用。
- 证据 `.artifacts/windows-native-a04d50c/result.json`、stdout.txt、stderr.txt。仅验证 Windows 打包启动器/Compose/Skia 基础启动，不能扩张为模型、真实账号、插件安装或完整UI验收。开发包仍使用当前源码版本号，未发布。
## 2026-09-24 六款新增官方插件图标

- `9222c501e017c9a39dbb1a1ae7e8aabf0b33f6d4` 接入 Figma、Stripe、Vercel、Supabase、Google Calendar、Outlook 官方资源；cc-yxi 收集，主线程核对 manifest 哈希及图形，来源记录于 plugin-icons/SOURCES.md，名称与官网域名同时匹配。内置覆盖从8款扩到14款。
- 首跑 `run.M8O8A5` 7项中1失败：旧测试要求PNG超过500字节，对简单图形压缩不适用。`84eda53bdf484b6b572ab82b0bbeb4276a0c428d` 改为实际可见像素及非单色图案验证。
- 最终构建 `run.dSx7Yz`，PluginIconsTest `run.MKzQON` 7项全部通过，0失败/错误/跳过；包括离线渲染与同名非官方域名拒绝套图。`.artifacts/plugin-brand-wave2/plugin-*.png` 六款64px渲染已目视，无裁切。
- Outlook官方源仅48px，Windows高DPI仍待确认；尚未覆盖全市场，也未发布。
## 2026-09-24 插件图标失败退避

- `8a2510b8c9c638a219835fc9c38f8458878335c5` 对目录及官网图标全部失败的请求增加60秒内存退避（最多4096项），到期可重试；取消异常直接传播，不再继续抓取官网备用地址。
- 构建 `run.thkIIY`，PluginIconsTest `run.14ZUVX` 6 项通过，0 失败/错误/跳过，包括失败重复加载不重复请求、到期恢复、取消不继续请求和已有官方图标渲染。
- 已委派 cc-yxi 收集 Figma/Stripe/Vercel/Supabase/Google Calendar/Outlook 官方资产，当前尚在收集，未接入。网络测试使用注入 fetch；本轮不代表所有市场图标已覆盖，未发布。
## 2026-09-24 原生会话目录与待处理筛选

- `17f7fff9e259ddc5065cb4345ec883ff4136f25b` 将 Codex/OpenCode/ACP 目录并入项目分组的默认新建目录和复制菜单，避免只有原生登记会话的分组丢失工作路径。
- 构建 `run.Ly5Bh4`；RemoteAcpCreationUiTest `run.AQaEYe` 1 项通过，0 失败/错误/跳过。新增队列投递中 Working、未知 NeedsYou、归档后仍进待处理、人工核对后 Idle 并退出待处理的断言，无 session/prompt。
- 目录菜单仅编译核对，本轮未增加其实际点击验收；状态测试为隔离 Linux SSH/协议 fixture，Windows 实机与整体发布仍未完成。
## 2026-09-24 分组加载期间保留原生会话

- `c142867fd9c19996cfa324626314e7580b371a4f` 修正已登记 ACP 会话仍显示服务器无会话的错误提示；分组尚未加载时，Codex/OpenCode/ACP 登记会话保持可见，不再等待分组读取完成。
- 构建 `run.BGFtmZ`；RemoteAcpCreationUiTest `run.8uAZf0` 1 项通过，0 失败/错误/跳过。新增分组未加载状态下项目树搜索 Gemini 并点击的真实窗口回归，返回相同控制器且没有额外 session/new。
- `.artifacts/server-acp-ui/project-loading.png` 已目视：加载提示和可点击的原会话同时显示。测试为隔离 Linux SSH/协议 fixture，完整分组菜单与 Windows 实机仍待验收，未发布。
## 2026-09-24 远端 ACP 会话侧栏入口

- `e4374d71e40472b344f8255730d2858e9349bb1b` 将 Gemini/Grok/Hermes 已登记远端会话接入项目树、主机搜索、置顶与收藏；复用原生会话行，支持显示名称、归档、移组与灰色8dp选中态。按控制器审批/运行状态及队列未知状态显示待处理，断开的会话不冒充空闲。
- 构建 `run.ADp3K5`；RemoteAcpCreationUiTest `run.hAKJ87` 1 项通过，0 失败/错误/跳过。真实隔离 SSH + 协议 fixture 中，创建后点击会话行返回同一 Conn/控制器，session/new 仍仅一次，无 session/prompt，异主机查询为空，置顶/收藏登记成功。
- `.artifacts/server-acp-ui/sidebar.png` 已目视；这是单行组件截图，不能作为完整项目树/分组/收藏菜单布局验收。完整侧栏交互、Windows/macOS 实机及重启后原生会话恢复仍待完成，未发布。
## 2026-09-24 原生 Hermes 远端认证入口

- `18181682c11915284c9738c4fe600d61f9ea3be2` 新增 HermesRemoteAuthenticationTest：固定官方 Hermes 0.21.4 源码，在隔离 HOME 下经真实 SSH 完成 ACP 握手，使用原生 hermes-setup 描述启动 SSH PTY，实际出现 Select provider 菜单。关闭认证通道后无成功回执，原 SSH 仍可执行命令。
- 专用 Hermes 镜像与显式 SSH PTY 模式下 `run.jE4lWd`：1 项通过，0 失败、0 错误、0 跳过。原生终端输出保存在 `.artifacts/hermes-native/remote-terminal-setup.txt`，启动内部标记未泄漏。
- 本次仅证明真实 CLI 的远端配置入口和取消语义，未选择供应商、未完成真实账号认证、未验收 Windows/macOS，未发布。
- cc-yxi 已交付验收清单刷新草案；主线程发现将三家握手概括为三家完整文本轮次及旧截图状态的表述不准确，已退回修订，不将草案当作完成证明。
## 2026-09-24 远端认证窗口取消回归

- 源码 `00f0de01095554edb82f863a2a6edcc05d187de8` 增加实际关闭认证窗口的回归：认证进程退出、取消结果无成功退出码、不生成输入结果文件，原 SSH 连接仍可执行命令。
- 构建 `run.LiPAyO`，显式 SSH PTY 模式测试 `run.fHCMPM`：RemoteAuthenticationDialogTest 2 项通过，0 失败、0 错误、0 跳过，包含键盘成功与窗口取消。
- 仍为隔离 Linux SSH 与测试认证程序；Windows/macOS、真实账号认证尚未完成，未发布。
## 2026-09-24 远端认证窗口实际键盘验收

- `5ddb2581174342b213d183a8c417bcba9b9daf06` 为缺失 TERM 的远端环境提供 xterm-256color 缺省值；新增真实 SSH PTY + JediTerm 窗口测试，核对原始 argv、环境值、TTY 状态，实际点击输入 ok/Enter，返回退出码0且原 SSH 连接仍可执行。
- 构建 `run.Dx1WN3`，显式 SSH PTY 测试模式下 RemoteAuthenticationDialogTest `run.7liBWJ` 1 项通过，无失败/跳过。`.artifacts/acp-auth/remote-terminal.png` 已目视，中文提示正常、无启动标记和配置值回显。
- 使用隔离 SSH 与测试认证程序，不代表远端 Hermes 真实配置或 Windows/macOS 已完成。未发布。

## 2026-09-24 远端认证配置接收确认

- `458fd7786f81ce620540bf082583e0b3580099bd` 增加服务器配置已接收的第二阶段标记；客户端在该确认前不暴露终端输入，避免 stdin 缓冲读取启动 JSON 时吞入过早按键。两类内部标记均在桥接层消费，不进入终端界面。
- 构建 `run.eoNODi`，受控 SSH PTY 下 RemoteAcpTransportTest `run.djXq8m` 1 项通过，无失败/跳过，包含6000字节环境传输、不回显、无内部标记显示、退出回执、重连创建及连接隔离。远端窗口实操与真实 Hermes 仍待验收，未发布。

## 2026-09-24 远端交互认证终端与重连

- `9f9d1c3` 增加 RemoteAuthenticationTerminal/Plan：SSH PTY 引导先关闭回显与规范行缓存，参数/env 通过有界 JSON stdin 传入，不放命令行；同一运行器 argv/env 执行、退出回执、计划所属通道取消。RemoteAcpTasks 退出码0后重新握手，远端表单接入统一认证窗口，并显示服务器身份。
- openPtyCommand 补异常/协程取消时关闭已打开通道。首次远端测试 run.lvTIxB 通过，本地 GUI run.whR4x6 暴露共享窗口 start 工厂与控件方法重名导致重复启动；`f1082f06e514a8f0f573c3f2ac3a8637efb96c1c` 改为 openTerminal 和显式 this.start。
- 最终构建 `run.50GiZl`，启用受控 SSH PTY 测试能力的 RemoteAcpTransportTest `run.JYmWRg` 1 项、本地认证窗口 `run.5n92PR` 2 项全部通过，无失败/跳过。6000字节环境值完整到达且不回显，终端成功后重连并创建任务，原有输入/取消回收回归通过。
- 远端认证窗口本身的 GUI/真实 Hermes 配置、长延迟输入与 Windows/macOS 仍待验收；引导配置发送后的启动确认还需加强，未发布。

## 2026-09-24 SSH PTY 退出回执

- `96073c9` 增加 Shell.exitCode/awaitExitCode：无原生退出回执为 null，不推断成功。测试先后在 run.Ckglrz/run.mUh40j/run.eqzQ0G 暴露 PTY 命令前退出255；DEBUG3 和 OpenSSH 9.6 audit-linux.c 定位到 root 登录审计权限要求，而非运行器问题。
- runner `2b8792f` 为显式 YXI_TEST_SSH_PTY=1 加入 AUDIT_WRITE + 专属标签，其他边界不变。代码/测试镜像 `23c9af59fa3d15dc20acb9646f19b98a1d099354` 在 `run.07sK0c` 1 项通过，无失败/跳过：真实提示+输入后退出码7，主动关闭无回执为null，并通过既有ACP隔离/清理验证。
- `b23ad90` 的纯 Python 隔离校验测试8项通过，审计能力默认拒绝，缺标签拒绝，显式模式下 SYS_ADMIN 仍拒绝。运行要求写入 isolated-tests README。尚未接远端认证界面，未发布。

## 2026-09-24 服务器表单连接、认证、创建点击验收

- `defb3aaee810d917c138266352ce3680c7cbbc8e` 扩展 RemoteAcpCreationUiTest，使用真实隔离 SSH 与协议 fixture，实际点击连接、认证、创建，断言只调用一次 session/new，进入对应远端会话，草稿保留、发送队列为空、没有 session/prompt。
- 构建 `run.T4Mayr`，窗口测试 `run.ejt8a9` 1 项通过，无失败/跳过。`.artifacts/server-acp-ui/connected.png` 已目视，服务器/运行器/目录及认证入口显示正常；此前文案调整也已随本轮编译渲染。
- 这是 Linux 窗口与认证 fixture 验证，不代表真实账号登录或 Windows 实机验收。远端交互终端认证仍待接入，未发布。

## 2026-09-24 服务器新建预览路由点击验收

- `0524ca1` 新增真实 SSH+Compose 点击测试；首次 `run.Fhbbv1` 因测试把 Compose 内绘弹窗当作 AWT Dialog 而失败。`4eee8b8386250161bc6351c516f32b6d565ac7ed` 调整定位后，构建 `run.ytIQVy`，RemoteAcpCreationUiTest `run.1pU0Ir` 1 项通过，无失败/跳过。
- 实际点击 Gemini 预览确认后 Page.Acp、Conn、engine、远端目录和提示词均正确；任务索引仍空、探测用运行器标记未生成，证明跳转不启动 Agent 或发送草稿。`.artifacts/server-acp-ui/create.png` 与 preview.png 已目视核对。
- 随后仅将产品入口/说明里的 ACP 改为对话预览文字；此文案更新未重新截图。服务器表单连接/认证/创建全程与 Windows 实机仍待验收，未发布。

## 2026-09-24 服务器 ACP 界面与新建入口接入

- `cd2645f23b0322d8343ce0c8d791077ddad85228` 新增 Page.Acp/RemoteAcpPane，接入侧栏和项目树 NewSessionDialog 的 ACP 预览回调；任务使用远端控制器与返回目标，提示词仅存草稿，已有目录约束明确。协作组/worktree 尚不支持；远端 terminal 认证显示原因且不误发 RPC。
- AppState 接入主机运行状态判断，侧栏断开与应用退出清理所属 ACP 任务。新建页对 ACP/OpenCode 不再提示自动发送草稿。
- 构建 `run.GxPCyP`；SSH 管理/通道 `run.UFf1BR` 1 项、共享对话 GUI `run.Db0fQZ` 1 项通过，无失败/跳过。服务器新表单和新建跳转本身尚待点击/截图验收。
- 构建保护触发后仅删除14个无容器引用的旧专用测试镜像，固定基线和最近镜像/报告保留；清单 removed-images-20260924-server-ui.json。未发布。

## 2026-09-24 服务器真实 Gemini ACP 与工具审批

- `843c54742b0a44c7551c849e7576873edd441ced` 扩展 Gemini 原生测试到真实隔离 SSH：远端 HOME/GEMINI_CLI_HOME 与假 API 配置独立，RemoteAcpTasks 认证/新建/发送，批准前文件不存在，allow_once 后实际 shell 写标记、functionResponse 回传、end_turn/Completed。
- 构建 `run.tDKxgH` 与 Gemini 变体成功；GeminiAcpConversationNativeTest `run.uUuEe4` 1 项通过，无失败/跳过，包含本地与远端两套原生流程。远端 hostKey/home/任务索引正确，关闭任务后 SSH 仍可执行。证据 `.artifacts/gemini-native/remote-turn.json`。
- 运行器、SSH、工具执行真实，模型 HTTP 为回环 fixture。服务器 UI 与终端认证、公网账号、Windows/macOS 仍待完成，未发布。

## 2026-09-24 服务器 ACP 会话管理基础

- `158dd99e5e1a25913575c2171b1871c9483374df` 新增 RemoteAcpTasks：远端目录/home 身份、每主机独立创建日志、显式认证、原生 session/new、持久任务记录、模型记录回调，以及按 Conn 关闭所属控制器。明确认证拒绝保留连接，其余未知创建要求核对。
- 构建 `run.XJDVT5`；RemoteAcpTransportTest `run.saIk0E` 1 项通过，无失败/跳过。真实隔离 SSH + fixture 新增 Grok 任务创建/磁盘索引/远端路径/恢复标记/断开清理断言，并保留 Gemini/Hermes 通道互不干扰验证。
- 尚未接 AppState/新建 UI、远端终端认证和真实运行器会话请求，不开放未完成入口。未发布。

## 2026-09-24 服务器 ACP 专属通道基础

- `8e13a41`/`9ee5c6a2da8a6a520d5d58f883af76474c155bad` 增加 RemoteAcpTransport：先核对可执行入口，Shell.q 保留目录/argv，经独立 SSH exec 和 Python supervisor 转发协议；EOF/信号清理所属进程组，不接管现有 Agent；原生 stderr 不进入通道日志。
- 构建 `run.1at9hN`；RemoteAcpTransportTest `run.33JtwH` 1 项通过，无失败/跳过。真实隔离 SSH + ACP fixture 验证引号/空格目录、Gemini/Hermes argv、关闭一客户端保留另一个和 SSH、断连回收进程。
- 尚未接服务器会话管理/新建 UI/终端认证，也未验证远端真实运行器；不开放未完成入口。未发布。

## 2026-09-24 Gemini 原生工具审批与结果回传

- `91c5c69269921ff6988dc5525e7f014563ccbfbd` 扩展真实 Gemini 0.34.0 会话：HTTP fixture 从原生 functionDeclarations 选择 run_shell_command，要求写容器内标记。确认批准前文件不存在，从实际 options 选择 kind=allow_once 的原始 proceed_once，之后必须文件内容正确、functionResponse 回到模型，最终 end_turn/Completed。
- 构建 `run.mXHtQw`，GeminiAcpConversationNativeTest `run.sN9AXL` 1 项通过，无失败/跳过。原生审批 ID 为数字0，选项分别为 allow_always、allow_once、reject_once，验证了原始 ID 与范围映射。证据 `.artifacts/gemini-native/approval.json`、approved-turn.json。
- 原生 CLI、工具执行与 ACP 真实；模型 HTTP 为回环 fixture，未消耗额度或操作宿主文件。Windows/macOS 与真实账号仍待验收，未发布。

## 2026-09-24 ACP 已确认模型切换的保存失败语义

- `eea1f73`/`2a3e2b6cf14f14f841c61a17880167e6cf7b063c` 记录原生模型切换 ACK 阶段。ACK 后本地状态/索引保存异常不再被错误分类为选项拒绝；明确提示原生已确认、本地同步失败，并暂停发送。
- 构建 `run.j47XCt`，AcpTaskControllerTest `run.dSo0k6` 4 项通过，无失败/跳过。新增回调抛 IllegalArgumentException 时原生缓存仍为新模型、控制器未就绪且不宣称拒绝的断言。未发布。

## 2026-09-24 Gemini 真实 ACP 完整文本轮次

- `db476e6e326b78b7f76fed057f1f9c05ba2a48ac` 使用真实 Gemini CLI 0.34.0、进程内假 GEMINI_API_KEY/回环 GOOGLE_GEMINI_BASE_URL/GEMINI_MODEL，实际 LocalAcpTasks 认证、新建和控制器发送；Google 协议 HTTP fixture 核对 x-goog-api-key，并返回固定 SSE 内容。
- 产品构建 `run.ossZQ2` 与 Gemini 变体成功；GeminiAcpConversationNativeTest `run.Ty8gGR` 1 项通过，无失败/跳过。真实请求 /v1beta/models/gemini-2.5-flash:streamGenerateContent，收到 GEMINI_NATIVE_TURN_CONFIRMED、stopReason=end_turn、RuntimeTurnState.Completed，任务记录模型正确，未生成 OAuth 凭据。证据 `.artifacts/gemini-native/native-turn.json`。
- 模型服务为本地 fixture，不是 Google 官方账号/配额验收；原生工具审批、模型切换与 Windows/macOS 仍待验证。未发布。

## 2026-09-24 ACP 明确模型拒绝后的继续发送

- `8dfa00fb55fee03b686c2dd89a42c353140acaca` 对 session/set_model 的 -32601/-32602 明确拒绝释放设置操作占用，保留原模型；控制器提示选择未被接受而非停用会话。超时、内部错误继续按未知处理，不自动重发。
- 构建 `run.Djfz3i`，AcpClientTest `run.steoXx` 8 项、AcpTaskControllerTest `run.CERNSz` 3 项全部通过，无失败/跳过。新增拒绝 provider/b 后仍保持 provider/a，并可正常提交下一轮的 fixture 断言。未发布。

## 2026-09-24 Hermes 原生模型切换

- `7e39714` 为无 configOptions 的运行器补 models/session/set_model 客户端、控制器、灰色菜单和任务记录同步；新配置接口仍优先。原生 Hermes 0.21.4 的 config handler 不是模型切换接口，不能替代其 set_session_model。
- 测试改用原生 providers 命名端点声明两模型。`run.P9r0OL` 暴露同模型后缀跨供应商有多条记录，测试选择有歧义；`2236a2a05a9a300b87a509c4941c5bcbafad82b0` 选择列表中的完整 custom:fixture:fixture-hermes-alt ID 后，原生测试 `run.VXGz31` 1 项通过，无失败/跳过。
- 构建 `run.idTEx7`，切换前 HTTP 请求 fixture-hermes，切换后请求 fixture-hermes-alt；两轮完成，持久模型为完整原生 ID。证据 `.artifacts/hermes-native/model-switch.json` 与 models.json。模型服务仍是回环 fixture，不是公网账号验收。
- 磁盘保护触发后仅移除 29 个无容器引用、标签/源版本/专属标签匹配的旧测试镜像，保留最新5个和两固定基线；清单 removed-images-20260924-model-switch.json。未发布。

## 2026-09-24 Hermes 真实 ACP 完整文本轮次

- `47c88888d4af881c050a26cc964a80df448701ac` 新增真实 Hermes 0.21.4 回环模型测试。按固定源码的原生 config.yaml/.env 格式配置隔离 custom 提供方，LocalAcpTasks 识别原生认证方法、authenticate、创建会话，控制器发送并接收实际 ACP 回复。
- 产品构建 `run.aTLmDv` 与 Hermes 变体成功；HermesAcpConversationNativeTest `run.O2cxsT` 1 项通过，无失败/跳过。HTTP 服务核对模型 fixture-hermes 与假 Bearer 头，实际请求2次，收到 HERMES_NATIVE_TURN_CONFIRMED，原生 stopReason=end_turn，队列 RuntimeTurnState.Completed，持久任务 key 一致。记录模型为 custom:fixture-hermes；证据 `.artifacts/hermes-native/native-turn.json`。
- Hermes 本体与适配代码真实运行，模型端点是本地 fixture；不代表公网供应商、Nous Portal 登录、原生工具审批/模型切换或 Windows/macOS 已验收。未发布。

## 2026-09-24 Gemini 原生认证拒绝与创建恢复

- `c3f6d8e1da5c49f567020a7b9496a8dad2f7d2be` 扩展真实 Gemini 0.34.0 测试：未登录 session/new 返回标准 -32000；通过实际 LocalAcpTasks 创建得到先登录提示，保留初始化/认证选项，任务索引为空，未知创建标记为空，未写 oauth_creds.json。
- 产品构建 `run.DXkmzY`，Gemini 变体构建成功，GeminiAcpNativeTest `run.CLVZyU` 1 项通过，无失败/跳过。证实认证拒绝可恢复，不代表 Google 登录、订阅或模型请求可用。未发布。

## 2026-09-24 Gemini 官方原生 ACP 握手

- `87171ee142a532405e0c5f468b061c14123af41c` 增加 Gemini 原生测试镜像：固定 Node 22.14.0 与官方 npm @google/gemini-cli 0.34.0，包锁保存在镜像 /opt/gemini/runtime/package-lock.json；runner 校验专属 runtime/source 标签。用户已安装版本的官方 config.ts 确认 --acp 为当前入口。
- 产品构建 `run.2o1a5T`，Gemini 变体构建成功，GeminiAcpNativeTest `run.gD3tSz` 1 项通过，无失败/跳过。真实 Node+官方入口经 LocalAcpTransport 协商协议1，返回认证方法与能力，未创建 oauth_creds.json，关闭后所属进程退出。
- 原生提供 Google 登录、Gemini API Key、Vertex AI、Gateway；仅表示运行器可提供的认证方式，不证明订阅权益/登录成功。证据 `.artifacts/gemini-native/initialize.json` 与 build.log。三种 ACP 运行器现均有真实握手证据，但真实账号登录、完整模型请求和 Windows/macOS 仍待验收。未发布。

## 2026-09-24 Hermes 原生终端配置入口

- `cd0adf1d3805d33d8d6a5d901ab6b7586cb2b00b` 原生测试声明 auth.terminal，从固定 Hermes 0.21.4 的 initialize 读取 terminal 方法，使用产品 acpTerminalAuthPlan 与 LocalAuthenticationTerminal 启动同一运行器。实际显示 Select provider 菜单并保持交互等待，随后关闭；未选择供应商、未填写凭据。
- 产品构建 `run.riiF5o`；专用 Hermes 镜像配方成功（日志 /root/.cache/yxi-isolated-tests/hermes-terminal-build.log）；HermesAcpNativeTest `run.ZHKq8t` 1 项通过，无失败/跳过。菜单记录 `.artifacts/hermes-native/terminal-setup.txt`，包含 Nous Portal、OpenAI 等原生选项，不代表这些账号已登录。
- 仅验证真实入口与 PTY 通路；实际配置成功后的会话/模型请求，以及 Windows/macOS 仍未验收。未发布。

## 2026-09-24 认证窗口取消与进程树回收

- `3acf5073f6c1fd8dc616c0038cf4a085aa78d1b3` 扩展真实窗口测试：PTY 程序启动等待子进程，发送 WINDOW_CLOSING 后必须返回取消而非成功，并等待父/子 ProcessHandle 均不再存活。
- 构建 `run.sSJmcj`，AcpAuthenticationDialogTest `run.t0zPhP` 2 项通过，无失败/跳过，正常键盘确认和取消回收分别覆盖。测试在隔离 Linux 环境；Windows/macOS 进程树与真实 Hermes 配置仍待验收。未发布。

## 2026-09-24 认证终端窗口真实键盘验收

- `8436b93716cde4f18081144d75c8237edfbb3337` 增加 AcpAuthenticationDialogTest：真实 PTY fixture 要求 stdin/stdout 为 TTY，在实际 JediTerm 窗口显示中文+ANSI 颜色，Robot 点击并输入 ok/Enter，断言原生接收 ok 和窗口回传退出码0。
- 构建 `run.orL5ur`，窗口测试 `run.ybNjcC` 1 项通过，无失败/跳过；截图 `.artifacts/acp-auth/terminal.png` 已目视，中文无方块/乱码，终端区域显示正常。
- 仅 Linux 隔离窗口和测试程序，不是 Hermes 真实配置或 Windows/macOS 安装包验收。未发布。

## 2026-09-24 ACP 终端认证窗口与重连接入

- `5a401d51ab8db1fd0fbbc1f85edd5155291d255c` 新增 JediTerm+本地 PTY 的认证 DialogWindow；选择 terminal 方法后关闭旧 ACP 连接，终端退出码0才重新连接/初始化，取消或非零退出不继续。LocalAcpTransport 连接声明 auth.terminal 能力；普通 ACP Client 默认仍不声明。
- 修正 PTY 启动跨协程取消的所属进程回收，窗口销毁关闭 widget/连接器；复用原 TermSettings 的中文字体配置。认证 UI 不记录终端输出，不以文本推断登录成功。
- 新固定 PTY 依赖基线下离线构建 `run.xvlM3D` 成功；管理器 `run.SJmUxT` 4 项、真实 PTY `run.JSuZeU` 1 项、启动传输 `run.JaPC3u` 1 项通过，无失败/跳过。尚未完成认证窗口 GUI 点击、Hermes 实际配置以及 Windows/macOS 打包/原生终端验收。未发布。

## 2026-09-24 本地交互 PTY 连接器

- 引入固定 Pty4J 0.13.4，LocalAuthenticationTerminal 复用 JediTerm TtyConnector，直接 argv/env 启动、UTF-8 输入输出、窗口尺寸、真实退出码与所属进程清理。初次测试编译发现 TtyConnector 非 AutoCloseable，`ea6f75eeda4495d588db7f6d6fffec683c630acc` 补齐生命周期接口。
- 构建 `run.wBuI0e`；LocalAuthenticationTerminalTest `run.cDZlJN` 1 项验证真实 isatty、中文确认往返、退出码7；LocalAcpTransportTest `run.0r0S3o` 1 项和原生 Grok `run.WsQPDQ` 1 项全部通过，无失败/跳过。
- 依赖下载仅显式构建开关允许，测试始终断网。新固定依赖基线记录在 isolated-tests README；终端 UI、认证退出后重连、Windows ConPTY/安装包和 macOS 尚待验证。未发布。

## 2026-09-24 ACP 终端认证路由与启动计划

- `dafd371ce70024d03def32c6d77ef723efc31c54` 依据 https://agentclientprotocol.com/protocol/v1/authentication 区分 agent/terminal 类型，terminal 不再误发 authenticate RPC。抽出 AcpLaunch.environment，构造同一运行器+原 ACP 参数+认证参数的 TerminalAuthPlan，应用原生 env 覆盖，拒绝描述替换 command，并限制参数/环境大小。
- 构建 `run.CFJFEo`；计划测试 `run.AScHlm` 1 项、启动传输 `run.fvSpo1` 1 项（覆盖三引擎）、客户端 `run.F0SqXr` 7 项通过，无失败/跳过。路径含空格和 Hermes home 保持已验证。
- 尚未实现交互终端宿主、退出码核对和重新初始化，仍不宣称 terminal auth 能力；Hermes 登录未完成。未发布。

## 2026-09-24 Hermes 官方原生 ACP 握手

- `e7e0f8a` 增加专用 Dockerfile.hermes、受标签/源码 pin 校验的 Hermes run variant、HermesAcpNativeTest；固定官方源码 5a3e03ef37462000b5b13d03915eb3e7b1633b6f（0.21.4），仅在构建镜像安装 core+acp 依赖。首次普通 wheel 安装失败，官方 setup.py 明确禁止普通 wheel；`38b7abb` 改为其支持的 editable 安装并保留完整日志。
- Kotlin 测试镜像源 e7e0f8ab0b178c1734dbc7386004eb740edf19a1，构建 `run.TZ9fJB`；Hermes 变体基于同一已编译镜像，runner/配方为 38b7abb，原生测试 `run.guwlk9` 1 项通过，无失败/跳过。实际产品 LocalAcpTransport 启动 /opt/hermes/bin/hermes acp，协议1握手成功，关闭后进程退出，测试网络隔离。
- 原生返回 load/resume/fork/list、image 能力以及 type=terminal 的 hermes-setup（args --setup）认证方法，说明当前通用 RPC 登录按钮仍需要专门的终端认证适配；不能算登录或模型调用完成。证据 `.artifacts/hermes-native/initialize.json`、build.log，依赖冻结清单在测试镜像 /opt/hermes-packages.txt。未改宿主安装、未登录真实账号、未发布。

## 2026-09-24 ACP 已确认模型记录同步

- `c870cc53455bf94b38005066c528d260044bf1d8` 创建时优先读取 configOptions 中 model 类别，其次旧 models.currentModelId，无原生证据才记 native-default。原生配置通知/切换确认后通过控制器回调更新任务索引，任务 key 保持不变。
- 构建 `run.ZQ1rNj`；LocalAcpTasksTest `run.gBeT4n` 3 项、AcpTaskControllerTest `run.Bs3g6R` 3 项通过，无失败/跳过。三引擎 fixture 都验证初始具体模型和切换后的磁盘记录。真实运行器模型切换仍待验证，未发布。

## 2026-09-24 ACP 结束轮次的过期审批清理

- `9045cd5e26f4e755db544f35821433798a29fd6b` 原生终止回执确认后，以 cancelled 答复该会话残留审批并清空界面；不选择 allow 选项。清理失败关闭连接并提示核对，同时保留已确认的轮次完成结果。
- 构建 `run.GBCUyu`，AcpTaskControllerTest `run.HDCldN` 3 项通过，无失败/跳过。新增审批尚未作答时收到 end_turn 的用例，断言队列 Completed、客户端/界面均无残留审批、回传无 optionId。未发布。

## 2026-09-24 ACP 多轮状态与消息标题

- `714cfbd681724badd707a2dd25cfd0cba9687bb1` 新轮次清空旧 stopReason 并显示等待回复；流式文本显示正在生成（取消/审批状态保留），停止回执才显示结束。消息标题使用你与具体运行器名称。
- 构建 `run.bEqtBM`；控制器 `run.UpnL2a` 2 项、完整窗口 `run.THtga5` 1 项通过，无失败/跳过。覆盖第二轮状态不继承第一轮结束、80 段流式回复状态，以及此前模型/审批/键盘/滚动回归。未发布。

## 2026-09-24 ACP 长回复滚动交互验证

- 新增长回复 80 段 + 原生鼠标滚轮测试。首轮 `run.NZUmTO` 暴露底部定位不稳定；`45cc265` 改为带视口偏移的 footer 定位。`run.6MN28N` 与 `run.iesGnp` 继续暴露桌面滚轮上滚未停跟随，保留失败布局与截图。
- 最终 `689d1a3e245beb6a2337fbfdaa668f8fe5c20cbb` 直接监听向上 Scroll 指针事件，并只在向下滚动到底时恢复跟随；结合已有位置/拖动观察。测试先确认第 80 段收到，再检查真实列表底部、上滚暂停、追加文字后 index/offset 不变以及恢复跟随后到底。
- 构建 `run.bEfS6r`；AcpConversationUiTest `run.vPdhk6` 1 项通过，无失败/跳过，仍包含模型/审批/键盘回归。`.artifacts/acp-ui/acp-scroll-reading.png` 已目视，阅读状态显示回到最新入口。Windows 实机滚轮/输入法仍未验收，未发布。

## 2026-09-24 ACP Markdown 与滚动跟随

- `211f43d9325de4a90eb1854837584b55e6325f39` 助手消息复用 AssistantBody（GFM/CJK/工作台字号），工具与用户正文保持文本。使用会话持久视图状态、底部锚点与仅用户滚动触发的跟随开关，提供回到最新消息；消息/审批项使用分开的 UI key 命名空间。
- 构建 `run.Z71Hnj`，AcpConversationUiTest `run.y8GuuM` 1 项通过，无失败/跳过，含模型、审批、键盘和带 Markdown 的完整回复。截图 `.artifacts/acp-ui/acp-markdown.png` 已目视核对标题、列表、代码块无裁切。
- 用户上滚后流式更新不抢滚动、超长消息底部等专项交互尚待测试；Windows 与真实三家模型请求仍未完成。未发布。

## 2026-09-24 ACP 草稿与键盘发送

- `7f0a50610c37ef95685003b59be3bedf781337c8` 输入框改用共享 chatDrafts/TextFieldValue，保留页面切换草稿；Enter/NumPadEnter 发送、Shift+Enter 换行、composition 非空时不截获确认键。发送先同步入持久队列，成功后清空草稿并交由控制器作用域执行。
- 构建 `run.g9K2nO`；AcpConversationUiTest `run.Wya9P4` 1 项通过，无失败/跳过：真实模型菜单点击、审批 once、Shift+Enter 未发请求、Enter 仅发送一次并完成队列。Windows 真实输入法尚未验证。
- `.artifacts/acp-ui/acp-model-menu-selected.png` 已目视核对，确认入口和选中行均为灰色小圆角，补齐上轮颜色修改的截图验证。未发布。

## 2026-09-24 ACP 模型菜单点击与选中态

- `482e0db` 扩展 GUI fixture：实际点开模型菜单并选择 Model B，断言请求 configId=model/value=provider/model-b，随后继续审批 once 点击。首次截图未覆盖弹出层且触发器仍有蓝色点击背景。
- `43d91d4c53cdbd3a8fd68ec456a70d3ffb30a547` 触发器改用 QuietChoice，完整屏幕截图覆盖 Popup；构建 `run.yHcrz8`，AcpConversationUiTest `run.zp2vco` 1 项通过，无失败/跳过。截图 `.artifacts/acp-ui/acp-model-menu-neutral.png` 已目视：触发器为灰色小圆角，菜单无裁切。
- 目视发现选中行用了 surface2（白色），随后改为与 QuietChoice 相同的 Tokens.selected 灰色；此末次颜色修改尚未重新截图。真实运行器模型切换及 Windows 验收仍待完成，未发布。

## 2026-09-24 ACP 原生配置选项与模型选择

- `2e13ed650f5e14022a8603e75d6fa3beeac374be` 依据 https://agentclientprotocol.com/protocol/v1/session-config-options 接入 configOptions、session/set_config_option 与 config_option_update。支持原生顺序、分组 select 值、完整依赖配置替换，忽略未支持类型；不宣称 boolean 能力。有 configOptions 时优先显示该菜单而非重复 modes。
- 控制器切换与发送串行并等待事件 fence；未知结果停止发送。UI 使用运行器名称和值，未返回选项时不补造模型。
- 构建 `run.WtcU7D`；客户端 `run.IcuvCk` 7 项与审批 UI `run.6eWEyh` 1 项通过，无失败/跳过。新测试覆盖分组模型及模型改变后思考强度可选值替换。模型菜单本身的 GUI 点击与原生配置切换仍待验收。未发布。

## 2026-09-24 Grok 原生认证拒绝与界面恢复

- 真实 1.0.41 未登录 session/new 返回 ACP -32000 Authentication required（首次探索 `run.WlH63j`）；并非会话已创建。`f718ddf` 引入 AcpRpcException，LocalAcpTasks 对明确认证拒绝清理 pending 日志并保留连接/登录选项，提示先登录；其他未知失败继续保护。
- `b6d2dbffce425438097e5527562af7aadda7103c` 清理重试时旧错误提示并显式指定原生测试 Unit（此前 `run.3M3abi` 无测试被发现，不算通过）。最终构建 `run.WgqdXl`，原生 Grok `run.zDGtGT` 1 项、管理器 `run.Aixfy6` 3 项通过，无失败/跳过，真实拒绝后没有任务记录和未知创建标记，登录选项仍保留。
- 为恢复构建空间，删除 44 个标签/源版本吻合且无容器引用的旧 Yxi 测试镜像，保留最新 5 个及固定基线，报告未删；清单 /root/.cache/yxi-isolated-tests/removed-images-20260924-acp.json。未登录真实账号、未发模型请求、未发布。

## 2026-09-24 Grok 官方原生 ACP 握手

- 从官方安装器 https://x.ai/cli/install.sh 声明的 GCS 源获取稳定 1.0.41，仅缓存 ELF 到 /root/.cache/yxi-native-tests/grok-1.0.41/grok；未运行宿主安装器/程序。SHA256 9ce03ed23e16ea01072b4496263d6213a27899e1e3e107f008d36edf82e70407，165967424 字节，来源清单 `.artifacts/grok-native/source.json`。
- `734083b66839d1cae065ad1d47455db4c64b5438` 原生测试在网络隔离空 HOME 运行产品 LocalAcpTransport：协议版本 1、agentCapabilities 与 authMethods 返回，未生成 auth.json，关闭后所属进程退出。构建 `run.ixHJJk`，GrokAcpNativeTest `run.eLVjlO` 1 项通过，无失败/跳过。
- 原生返回 grok.com 认证方法；初始化 `_meta.modelState` 含模型/思考强度能力（`.artifacts/grok-native/initialize.json`），后续模型适配应读取能力，不能凭静态名称猜测。仅握手已证实，未登录、未创建会话、未调用模型，不代表订阅可用。未发布。

## 2026-09-24 ACP 取消与已排队审批竞态

- `315672d37c7af6dd640a833fc4b2eb0c3f9e75ed` 展示审批前核对 cancelling 与客户端待审批表；已进入事件队列、但后来被取消的审批不会重新显示。
- 测试刻意阻塞 UI 消费，等待 reader 登记审批后取消，再排空事件；要求界面无审批且原生收到 cancelled。构建 `run.XglxL2`，控制器 `run.kRZMC2` 2 项与正常审批 GUI `run.Gi74rw` 1 项通过，无失败/跳过。
- 属于 fixture/隔离 GUI 验证；三家原生 ACP 与 Windows 验收仍待进行。未发布。

## 2026-09-24 ACP 审批页渲染与点击验收

- `85cebe7` 新增真实 Compose 窗口+Robot 点击：同名允许选项展示不同权限范围，点击本次允许后必须返回原生 optionId=once。初版 `run.AGoKiW` 通过，但目视发现胶囊按钮过宽。
- `040909db500745e104dff23017cc726ad7f8518e` 改为 8dp 小圆角、紧凑 FlowRow 可换行排列。构建 `run.qiTKFT`，AcpConversationUiTest `run.6alsZp` 1 项通过，无失败/跳过；截图 `.artifacts/acp-ui/acp-approval-compact.png` 已目视核对，无裁切，范围标签清楚。
- 使用协议 fixture 和 Linux 隔离窗口；不代表 Windows DPI/IME 或 Hermes 原生审批已验收。未发布。

## 2026-09-24 ACP 对话模式菜单接入

- `302dd04bf541da87a31571503b62853705c17ab5` 对话页读取原生 availableModes/currentModeId，菜单调用控制器 changeMode；切换与发送串行，等待 ACK 和事件 fence 后更新显示。未知结果禁用继续发送，原生模式通知同步到 Compose 状态，不提供虚构模式。
- 构建 `run.Ul7S7L`；控制器 `run.fuM1VC` 2 项、客户端 `run.lvr23p` 6 项全部通过，无失败/跳过。覆盖控制器模式切换后继续多轮与客户端通知/超时规则。
- 尚需菜单 GUI 点击/截图、模型选择、三家原生运行器与实际认证验收。未发布。

## 2026-09-24 ACP 准备连接回收与创建核对入口

- `dd147b7c497881ba992fe937cd169edcdc0f313b` 增加准备连接 generation 检查与 abandonPreparation，关闭窗口清理准备中的客户端，已移交控制器的会话不受影响；晚到连接/创建结果不继续发布成可操作会话。创建未知状态提供勾选核对后解除入口，不自动重发旧消息。
- 构建 `run.BL4YJY`；LocalAcpTasksTest `run.DdJ5Fn` 3 项通过，无失败/跳过，新增准备取消只关闭对应客户端、已拥有会话不被误关闭的断言。晚到结果分支和窗口点击尚需专项 GUI 验收。
- 三家原生运行器、模型/模式控件及完整界面验收仍待完成。未发布。

## 2026-09-24 ACP 本地工作台预览入口

- `ff46a437353d0ea283d3e5dfa019736f2d9ce8b4` 接入 AppState 的 ACP 管理与退出清理/运行计数；本地运行器卡提供连接预览、原生认证方式、创建与任务列表；会话页显示文本/工具/原生审批、发送和停止。发送由控制器作用域持有，页面作用域只等待结果；关闭进程的旧记录不提供假恢复。
- 构建最初被 4GiB 磁盘阈值拦截；仅清理 42 个有隔离标识且无活动容器的已完成测试 context，保留报告/镜像/生产数据，恢复约 5.5GB 可用。随后构建 `run.u9tPiR` 成功；管理器 `run.ugWB8H` 2 项、控制器 `run.FvrYJs` 2 项通过，无失败/跳过。
- 仍待 UI 点击/截图验收、取消连接时 prepared 进程回收、未知创建的界面核对入口、模式/模型选择、附件/Markdown/滚动，以及三家原生安装登录对话实测。入口为预览，不等于六运行器完整交付。未发布。

## 2026-09-24 本地 ACP 会话管理

- `498a444` 增加 LocalAcpTasks：连接与认证分离、原生认证方法显式调用、创建前持久日志、创建确认后的任务索引与控制器、失败关闭所属进程、重启未知创建保护；索引支持 gemini/grok/hermes。未返回模型时记录 native-default，provider 为 native，不冒充官方订阅。
- 首次 `run.D4sM4m` 只发现 1 项测试；`1e8fe61c4d06090b8ef28cf1a16ff0da3f9975b9` 修正 Kotlin 测试返回 Unit 后，构建 `run.G9vJUh`，LocalAcpTasksTest `run.T6PNKx` 2 项通过，无失败/跳过。
- 覆盖三引擎 fixture 创建/索引持久化、不自动登录/发送、未知创建跨重启禁止隐式重试。尚未接入 AppState/实际新建界面，未做三家真实运行器登录或模型调用。未发布。

## 2026-09-24 ACP 流式事件完成顺序

- `51babcdef832953a582a10a1cbdb72e74f115676` 增加内部类型化事件 fence：prompt 回执后等待此前收到的流式事件被控制器消费，再完成队列并释放下一轮。一个客户端仅允许一个事件消费者；关闭连接会失败所有待确认 fence。
- 构建 `run.shX8fq`，控制器 `run.FeBk3z` 2 项、客户端 `run.KT8qS5` 6 项全部通过，无失败/跳过。多轮测试现在紧邻发送最后文本和 stopReason，完成时必须已经保留文本；不再依赖人工等待显示。
- 属于协议/控制器 fixture 验证，实际运行器登录、新建及 GUI 仍待接入。未发布。

## 2026-09-24 ACP 会话控制器初步接入

- yxi_pilot 交付控制器草案后触发 429，主线程停止其重试并接手测试。`0695159` 引入控制器，修正多轮助手文本共用 ID 和 ACP 嵌套工具文本解析；支持队列、流式文字、工具状态、原生审批、取消等待 stopReason、未知投递持久化与禁止重发。
- 第一轮 `run.f6I4Af`：超时测试通过，多轮测试因测试线程跨 Swing 读取 Compose 列表失败。`b9ee5d6ac278991c279c08d5fc91cdccd85ea0ac` 将测试与 UI 状态统一到 Swing 调度器；构建 `run.JkYYWV`，AcpTaskControllerTest `run.Ebyp9Z` 2 项通过，无失败/跳过。
- 控制器调用与状态读取应在 Swing/UI 调度器；尚待最终流式事件与 prompt 回执的排空顺序、审批竞态专项验证、持久会话管理与 UI 接入。未运行真实 Gemini/Grok/Hermes，不算六运行器入口完成。未发布。

## 2026-09-24 ACP 审批卡片组件

- `5979e8ac59350eb6eb295d0eb00e3a0aa9ff848e` 添加 AcpPermissionCard，保留原生按钮名称并显示 allow_once/allow_always/reject_once/reject_always 的不同范围，回传原始 optionId；重复 ID 或未知类型只显示错误与取消，不生成批准选项。
- 构建 `run.BU3TH8`，AcpPermissionCardTest `run.yD6x0i` 1 项通过，无失败/跳过。只证明选项映射与校验，未做组件截图或真实审批点击验证。
- 组件尚待 ACP 控制器和会话页接入；yxi_pilot 的控制器草案已开始落盘，完整测试/说明尚未交付，未计为完成。未发布。

## 2026-09-23 ACP 原生模式通知同步

- `97a0519dddbd4759e7f909ef9603fedd13336e22` 接入 session/update.current_mode_update，保存原生当前模式；切换 ACK 不覆盖等待期间收到的较新通知，并兼容 session/new 完成附近到达的模式通知。
- 构建 `run.9xGG9A`，AcpClientTest `run.XsQ2SK` 6 项通过，无失败/跳过。包含请求 plan 期间原生通知 ask 后仍显示 ask 的断言。
- 已将独立 AcpTaskController 草案与 fixture 测试任务派给 yunxi/yxi_pilot，输出目录 windows-test-reports/acp-controller；尚未接收审核，不计完成。未发布。

## 2026-09-23 ACP 会话模式基础

- `fbb1c338755ebf8055d594c75baa538ecdbf57c8` 从 session/new 保存原生 availableModes/currentModeId，并提供 session/set_mode；拒绝未公布选项，等待切换确认期间阻止 prompt，超时保留未知状态以避免按错误权限模式继续发送。
- 构建 `run.pCyrcM` 成功；AcpClientTest `run.4D8v5E` 6 项通过，无失败/跳过，覆盖模式确认、超时阻止发送及既有审批/取消。此为协议 fixture 验证，不是 Gemini/Grok/Hermes 原生实测。
- 六运行器入口仍缺 ACP 会话控制器、原生模式变更通知同步、认证/模型/历史界面及各原生运行器端到端验收。未发布，未开放未完成的新建入口。

## 2026-09-23 远端缺失变量失败与恢复验收

- `7fb7395b0cd690af64469be9a80af849dfd41946` 扩展真实 SSH + OpenCode HTTP 测试：先登记不存在的环境变量，任务创建明确返回服务器缺少变量，任务索引/控制器为空，恢复标记为空，SSH 仍可执行；修正引用后同一任务管理器成功创建。
- 构建 `run.uZ6Pv5`；OpenCodeMcpHttpHeadersNativeTest `run.CW8in0` 1 项通过，无失败/跳过，包含此前本地/远端 HTTP 认证头与文件保持检查。测试全部在隔离容器中，未触及生产会话。尚未发布。

## 2026-09-23 服务器 OpenCode HTTP 共享任务创建

- 行为 `e5fe39a`、测试修正 `0ef35d32ba934dff7345cbd79b4b160f774dfa01`：SSH supervisor 通过 stdin 接收引用配置和变量名，使用远端自身环境，明确报告缺失变量/已有 inline 配置。RemoteOpenCodeTasks 检查同名冲突，专属进程加载并核对连接后创建会话；UI 开放服务器 HTTP 引用。
- 首轮 `run.eiKMx1` 因 SSH fixture 路径不在 /sandbox/tmp 被隔离检查拒绝，未到产品流程；修正路径并增强所有 HTTP 请求头检查后重跑。
- 构建 `run.F4QHrz`，HTTP 本机+真实 SSH 任务创建 `run.AkQEJD` 1 项，SSH 生命周期回归 `run.4QDW1u` 1 项全部通过，无失败/跳过。使用隔离 OpenCode 1.18.32 与假凭据；未动生产服务。
- 仍待缺失远端变量/已有 inline 配置的原生错误路径专项测试、配置变化竞态处理、启动绑定持久审计、Windows 表单验收及 Claude HTTP 验证。未发布。

## 2026-09-23 本地 OpenCode HTTP 共享任务创建

- `14b1cbcb11d3e40af565cca0e3c30c330696c615` 接入 LocalOpenCodeTasks：基线进程检查原生同名 MCP；有 HTTP 头引用时关闭基线、以私有配置启动专属进程，重新核对模型与连接，再创建和持久化会话。其他 MCP 仍用动态登记。界面开放本地 OpenCode 头引用，服务器保持明确限制。
- 构建 `run.Rt5U8b`；HTTP 原生测试 `run.9puehx` 1 项通过（现在包括真实 models/create/任务索引/控制器路径及配置文件不变）；对话审批原生回归 `run.wDQRTr` 1 项通过，均无失败/跳过。测试使用真实 OpenCode 与本地模型 fixture，不是公网模型验收。
- 尚需服务器 SSH 接入、预检与重启间配置变化处理、HTTP 启动绑定持久审计、Windows 新表单交互验收。当前启动层拒绝已有 inline 配置，尚未实现用户原配置合并。未发布。

## 2026-09-23 OpenCode 私有启动配置 HTTP 引用

- `898bd623e5f9c23053e248521f869cca835d0cf7` 增加 OpenCodeStartupMcp 与本机服务启动参数，通过 OPENCODE_CONFIG_CONTENT 传引用文本，由原生加载器展开。缺变量或已有非空 inline 配置时明确拒绝，避免丢失用户配置；无全局文件写入。
- 构建 `run.IK6s3o`；OpenCodeMcpHttpHeadersNativeTest `run.cnSCuQ` 1 项通过，无失败/跳过。真实 1.18.32 启动、HTTP MCP 握手收到完整假 Bearer 头，状态 connected，文件字节不变。
- 此为启动层支持，尚未接 LocalOpenCodeTasks/RemoteOpenCodeTasks，也未开放 UI；接入前必须保留原生同名冲突预检、配置变化处理和创建日志。SSH supervisor 尚无该参数。不得据此宣称用户已可从界面创建 HTTP 共享 MCP 会话。未发布。

## 2026-09-23 变量输入界面与 Codex HTTP 认证头验证

- UI `a39a6d3`：添加可折叠的变量输入，按 stdio/HTTP 区分变量名和请求头映射；说明目标机器与完整头值语义，拒绝大小写重复头名；含 HTTP 头引用时禁用尚未接通的 OpenCode 选择。构建 `run.DQVENR`、现有机器隔离 UI 回归 `run.4XjPH0` 1 项通过；新增表单交互与 Windows 目视验收仍待完成。
- 原生测试 `79c5002d552fb4616aa9fe7c46a144ce2d65fa23`：真实 Codex 0.153.4 通过生成的进程覆盖读取环境变量，回环 HTTP MCP 服务实际收到完整 Bearer 假令牌；config/read 与启动参数无令牌字面量，原配置文件字节不变。
- 构建 `run.qOjnxq` 成功，CodexMcpHttpHeadersNativeTest `run.wdCRa8` 1 项通过，无失败/跳过。仅证明初始化 HTTP 请求头传递；未覆盖缺失变量、Claude HTTP、OpenCode HTTP、实际第三方 OAuth 或真实订阅推理。未发布。

## 2026-09-23 OpenCode 动态变量引用修正

- 实现 `8e215a99e5def96f92b93f1db78929248fc234e9`：OpenCode 1.18.32 动态 POST /mcp 不做文件配置插值。程序型共享 MCP 改为继承目标服务器进程环境，不再注入字面占位符；HTTP 头变量在动态写入前明确拒绝，待启动配置路径接入。生成器仍保留原生文件配置格式。
- 隔离构建 `run.Ux7YbN` 成功；OpenCodeMcpBindingsTest 2 项（`run.o29MlW`）、OpenCodeSharedMcpNativeTest 2 项（`run.6qhIcM`）均通过，无失败/跳过。真实 OpenCode 子进程必须读到测试环境值才能启动 MCP，并完成原生工具调用与结果返回；模型端点为本地 fixture。
- yxi_pilot 源码报告保存在 `.artifacts/shared-mcp-references/opencode-dynamic-env.md`。其建议只有 opencode.json 可用过于绝对：启动期 OPENCODE_CONFIG_CONTENT 同样存在插值路径，后续应采用进程私有配置并先核对原生同名冲突，不能覆盖用户全局文件。
- 仍未完成 HTTP 认证引用、缺失变量提示、输入界面与 Windows 实机验收；本轮未发布。

## 2026-09-23 Codex 原生变量引用读回验证

- 提交 `4167bf25ef976b3631db9e327345dffda33e2fcb`：在现有隔离原生测试中为共享 stdio MCP 声明 PATH 引用，实际启动 Codex 0.153.4 app-server，校验 config/read 并通过本地任务创建路径；继续验证项目同名冲突与配置/登录文件未被改写。
- 构建 `run.O3ihJl` 成功；`LocalOfficialCodexNativeTest`（`run.FWvr2I`）1 项通过，无失败/跳过。这证明当前 env_vars 字符串数组读回与我们的校验兼容；不证明 HTTP 认证头、变量缺失或真实订阅推理。
- yxi_pilot 已交付变量验收矩阵，继续追踪 OpenCode 动态 POST /mcp 是否执行变量插值。此结论确认前暂不开放变量输入 UI，避免保存后原样发送占位符。yxi 已交付 Windows 发布验收清单，尚待主线程逐项复核。

## 2026-09-23 共享 MCP 变量引用基础

- 实现提交 `5a34d323ce139fae8e32dba790537ed26799a86a`：定义保存插件专用环境变量名及请求头变量名，按 Claude/Codex/OpenCode 格式生成配置；不保存变量实际值。注册时复制集合，Codex 读回只接受声明的引用并拒绝额外认证字段。
- 隔离构建 `run.upcaT9` 成功；`LocalCodexProfilesTest` 5 项通过（`run.lJkGvT`），`SharedMcpRegistryTest` 4 项通过（`run.Ompk10`），均无失败和跳过。
- 尚未完成：界面输入、三家真实运行器的变量展开和缺失变量行为验收。尤其需要验证 Codex 读回的 env_vars 规范化形态，以及 OpenCode 动态 MCP API 是否展开引用；当前测试只证明配置层校验，不代表原生认证可用。未发布。

## Codex 终端新建与项目层冲突检查

`584fc75`统一DesktopLaunchPlan参数传递：权限、Claude配置路径、Codex MCP覆盖全部作为独立argv传入，不把值拼进内层shell代码。Codex原始终端新建读取同一共享意向；先准备实际目录/独立worktree，再按该cwd读取原生有效配置并拒绝同名/保留名，随后启动。准备阶段不创建tmux会话。

同时修正本地官方Codex和服务器结构化Codex的共享预检/回读，config/read带目标cwd，包含项目配置层。LocalOfficialCodexNativeTest新增可信项目.codex/config.toml中已禁用的同名MCP，确认原生读回后拒绝覆盖，项目/全局/登录文件不变。

构建run.o0JmYT通过；启动与argv隔离run.k5yTMl 1项（扩展Codex覆盖值含空格/引号/命令替换字面量、目录准备不启动会话）、真实Codex项目冲突run.itLU33 1项、DesktopLauncherTest run.03BIE6 3项均通过，无跳过。尚未验证新增Codex TUI入口完整人工审批/Windows流程，本地旧CLI Codex任务入口与结构化工作台仍需统一；完整共享授权和生命周期、其余运行器目标均保留，未发布。

## 服务器 Claude 新建接入共享 MCP

`b74b0cb`新增RemoteClaudeSharedMcp：定义通过SFTP写入请求专属600配置，校验回读与摘要；DesktopLaunchPlan在实际工作目录/独立worktree准备后执行限时限长原生同名检查，再把配置路径作为独立argv传给Claude，保留权限模式和提示词字面量。主侧栏/项目/协作组新建入口传入当前服务器意向，配置变化会更换启动请求ID；旧请求文件不被覆盖。共享页开放服务器Claude。线路切换脚本仅接受Yxi管理的私有MCP文件，并保留其参数；其它自定义配置路径仍不自动接管。

首次run.lzv5QU没有发现测试，原因是Kotlin方法推断返回异常对象；`c63f3cb`明确Unit返回后，构建run.mH80RV通过。RemoteClaudeSharedMcpTest run.BVC8Gq 1项（私有SSH+假CLI+真实tmux，验证600文件、重试身份、argv字面量/防注入、配置冲突）、真实Claude2.1.280线路回归ConversationRouteApplyTest run.NzjyMh 1项（重启参数保留MCP、原文件不变）、DesktopLauncherTest run.NnvdG3 3项全部通过，无跳过。

尚未完成此新增服务器入口的完整真实模型/Windows GUI验收；带MCP参数的原地回退兼容仍需核对，当前严格上下文保护不会静默丢旗标。Codex原始终端创建路径与结构化工作台的共享配置覆盖范围也需继续统一。授权/升级/停用/卸载及完整共享市场导入仍未完成，未发布。

## 本地 Claude 任务接入共享 MCP 快照

`23d3f65`新增ClaudeSharedMcp预检：用当前目录下的原生claude mcp get逐项核对名字，已存在或未知错误均不覆盖；输出限长、限时、仅在内存检查，不持久化原生配置内容。通过后生成任务私有mcp.json，以--mcp-config添加，保留原有其它原生设置。LocalAgents读取本地Claude意向并保存定义快照，继续旧任务使用原快照，取消预检可清理自有进程。共享页开放本地Claude任务选择，服务器Claude仍未开放。

首次原生回归run.aIjjSq在预检失败：2.1.280的missing文本改为No MCP server named，而非原预期found with name。主线程只读核对官方二进制字符串后，`4158fab`兼容明确带目标名的两种缺失提示，并关闭预检过程自动更新。未知名/权限错误/连接失败不会被当成可用名称。

最终构建run.zr62oe通过；ClaudeSharedMcpTest run.gdpc1o 1项、真实LocalClaudeAgentNativeTest run.q6cqNa 1项、LocalAgentOutcomeTest run.IP5vVB 4项通过，无跳过。原生测试确认任务加载共享配置文件、快照保存重读及退役意向后续聊、模型原配置不变；测试使用隔离Claude2.1.280与本地模型fixture。当前接入的是既有本地CLI任务入口，不代表完整Claude本地原生历史/交互式审批工作台已完成；服务器Claude、共享授权/生命周期与Windows完整验收仍待完成，未发布。

## 服务器 Codex 共享 MCP 与任务快照

`54df5c4`将SharedMcpRegistry注入CodexWorkspace新建路径，按服务器hostKey取活动Codex意向；启动前核对原生同名/内置服务，使用已验证的MCP参数生成器，兼容原有独立provider私有启动文件。每次线程/轮次变更前回读MCP配置，拒绝未经核对的会话MCP覆盖。CodexTaskRecord增加共享定义快照并严格解析；恢复任务和切换模型线路继续用任务快照，不自动采用后来修改的共享列表。共享页开放服务器Codex新会话选择；旧登记无快照字段仍按空列表读取。

构建run.vCNe6b通过；CodexProfileLaunchTest run.0rulBQ 2项、CodexWorkspaceTest run.wP4AsU 14项、真实SSH+Codex独立线路回归run.SPgHon 1项全部通过，无跳过。原生回归增加共享定义快照保存/重读，在退役共享意向后重连旧任务并继续原线路/历史；同时保留API凭据隔离、模型切换和全局文件不变检查。测试用隔离容器和本地模型fixture。未完成Claude正式入口、所有MCP连接状态/授权流程/运行中生命周期与Windows验收，未发布。

## 本地官方 Codex 新会话接入共享 MCP

`25bfb3b`将sharedMcp登记注入LocalCodexTasks；本地Codex新会话获取活动意向后，先用原生官方连接读取有效配置，拒绝已有同名服务和codex_apps保留名，再以进程级覆盖启动。每次写入前同时核对官方提供方/ChatGPT账号与共享MCP的command/args/url/enabled，并拒绝未声明的env/header/token覆盖；创建回执前后检查共享配置快照。共享页开放本地Codex选择，服务器Codex及Claude仍标接入中。

构建run.HTVfTG通过；LocalCodexProfilesTest run.d4HsPT四项、LocalOfficialCodexNativeTest run.0JvjOK一项通过，无跳过。原生测试使用隔离合成ChatGPT身份，验证覆盖配置回读、官方提供方保持、共享登记参与实际新建，以及原生全局配置/登录文件不变。此轮核对配置和新建，不等于所有MCP服务器连接状态/真实订阅推理均已验证；旧同名配置拒绝覆盖，尚无导入接管流程。未发布。

## 共享配置页与 OpenCode 新会话自动加载

`25a9c45`将共享登记接入产品：Plugins增加共享配置页，按本地@local或当前服务器hostKey分别添加URL/stdio定义、选择OpenCode新会话、退役/恢复并查看历史。初次保存不启用任何运行器；Claude/Codex正式应用入口仍标接入中。LocalOpenCodeTasks和RemoteOpenCodeTasks在创建时读取当前机器的活动意向、通过独立服务加载MCP并保存回执；非connected阻止创建；创建前后核对配置快照，变化时保留原生创建记录供核对。已运行会话不随意向编辑自动变更。

构建run.VzYBCE通过；本地真实创建/审批回归run.i25x0e和SSH真实创建/生命周期run.IQWL6z各1项通过，增加从共享登记自动加载并写connected回执断言。`7a97a3c`窗口点击run.EW7e7X通过。`39fe858`补恢复核对对话框、目标机器文字和打开服务器原生插件时重置共享页；最终构建run.AUa0OL，SharedMcpUiTest run.ylZOpj及NativePluginIsolationTest run.GSNYxl各1项通过，无跳过。原生插件回归首次run.nSpMpl因调用命令缺少native binary挂载在启动检查失败，补参数后通过，未为此改产品代码。共享页截图已查看，.artifacts/shared-mcp-ui/shared-mcp-config.png。

当前仍只是通用MCP手动登记与OpenCode新会话接线；插件市场资源自动导入、Claude/Codex正式应用、需要OAuth的共享服务授权流程、在运行会话更新/停用/卸载、完整Windows验收均未完成。不把三个独立原生调用测试等同于完整共享产品交付，未发布。

## Codex 原生共享 MCP 调用与审批补齐

`7ca899b`新增SharedMcpSettings.codexArguments，生成进程级-c MCP覆盖，argv仍独立，不写原生全局配置。首次exec测试run.VqagDd失败：原生已发现/尝试工具，但approval_policy=never拒绝需要批准的MCP；模型fixture错误重复相同调用直至超时。未将失败当成通过。

`5e4ba9a`改用原生app-server、on-request与既有CodexTaskController，等待真实mcpServer/elicitation/request后明确批准一次；模型fixture遇到失败工具结果立即报错，不循环。产品本地/服务器Codex会话补CodexMcpElicitationButtons，普通空form支持仅本次/拒绝/取消，不发送persist元数据；需要字段输入或URL授权的其它表单仍禁用空批准并显示未接入。

构建run.rekJti、CodexSharedMcpNativeTest run.SIwHwO一项通过、无跳过。原生请求证据codex-mcp-approval.json包含serverName=shared_echo、mode=form、tool_params=codex-native-call；批准后实际tool_result含YXI_SHARED_MCP，轮次完成且全局配置逐字节不变。使用隔离容器和本地Responses模型fixture，不是用户真实账号/付费模型。Claude、OpenCode、Codex分别已有同一测试资源的原生调用证明，但尚非同一目标机器一次安装后三者完整启停/升级/卸载和产品UI验收，未发布。

yxi_entertainment后续Responses fixture任务因其额度429未交付，主线程已接手完成；未重试其配额或切换账号。yxi_pilot的配置层级复核仍在进行。

## OpenCode 原生调用共享 MCP

`8c59140`将共享MCP原生验证扩展到工具调用：回环Chat Completions fixture从真实请求工具列表选择yxi_echo，只有收到原生role=tool且含YXI_SHARED_MCP返回值后才输出成功标记。使用与Claude相同的shared_mcp_fixture.py资源，由OpenCodeMcpBindings动态加载；权限按ask处理，仅允许该共享测试工具。

构建run.b0RJXY通过，OpenCodeSharedMcpNativeTest run.29rlTZ两项全部通过、无跳过。原生OpenCode1.18.32验证加载/握手/工具发现、实际调用与结果回传、持久队列Completed、项目配置完整字节不变。模型为本地fixture且容器无外网；不代表真实订阅调用。Claude与OpenCode已有同一测试资源的原生调用证据，但Codex及同机三运行器统一安装/启停/升级/卸载UI仍待完成，未发布。

认证引用报告已返回；主线程发现“Codex项目层不承载MCP”可能混淆[projects]信任表与.codex/config.toml，已要求yxi_pilot沿loader重新核对并区分main与当前原生测试版本0.153.4。未采用该未经确认结论修改产品行为。

## Claude 原生调用共享 MCP 测试

`dcbc660`新增ClaudeSharedMcpNativeTest与仅回环Anthropic模型fixture。使用与OpenCode相同的shared_mcp_fixture.py资源，经SharedMcpSettings生成Claude原生mcp-config；严格加载测试配置且仅允许mcp__shared_echo__yxi_echo。模型fixture只有收到实际tool_result内YXI_SHARED_MCP标记才返回SHARED_MCP_NATIVE_CONFIRMED，避免模型文本自行声称成功。

构建run.ARxLQA、原生Claude2.1.280测试run.Qqw8qN一项通过、无跳过。验证MCP配置被原生接受（含type:stdio）、工具真实执行并返回结果、最终确认文本及配置文件字节不变。使用无外网容器、虚拟密钥和本地模型回复，不是用户真实账号或付费模型请求。此轮证明Claude配置/工具调用路径；生产Claude应用入口、Codex接入、OpenCode模型调用以及同机三运行器完整共享仍待验收，未发布。

yxi_entertainment已接下一项独立工作：准备Codex Responses SSE的共享MCP调用fixture，主线程审核后才在隔离容器执行。

## 共享 MCP 版本替换、回滚与退役记录

`f49600a`补齐Agent审查指出的版本模型缺口：逻辑身份按机器/插件/来源，活动版本唯一；replaceVersion原子退役旧版本并激活新版本，保留历史定义；回滚不能覆盖历史内容，并继承当前desiredRunners。retire/restore修改意向记录，不删除历史或原生认证。历史版本不能直接save修改，同机同名/同逻辑身份活动版本仍互斥。OpenCode加载接口拒绝传入退役记录。旧索引缺少retired字段时默认活动，保持兼容。

构建run.Rn7feq通过；SharedMcpRegistryTest run.0Xo1hL四项、OpenCodeMcpBindingsTest run.jv1vho一项通过，无跳过。覆盖升级、修改运行器选择后回滚、历史篡改拒绝、退役/重读/恢复、并行活动版本拒绝及既有隔离/加载保护。这里只完成登记生命周期，原生运行器的实际版本切换/停用/卸载、完整共享UI和三运行器工具调用仍待接入；未发布。

yxi_pilot继续核对三运行器环境变量/HTTP认证引用及项目覆盖语义，报告目标windows-test-reports/shared-mcp-fixture/credential-references.md。不复制现有原生账号令牌。

## OpenCode 共享 MCP 动态加载与原生验证

`b20f59e`接入OpenCode /mcp状态和动态add，并新增OpenCodeMcpBindings。限定机器及desiredRunner、检查原生同名冲突；服务级互斥保护检查/写入/读回；发送前写独立操作记录，失败持久化unknown，禁止用旧记录重发或覆盖旧进程记录。只读observedStatus不会自动确认或重试。当前为自有进程内动态配置，不改原生全局配置，不把connected当作OAuth成功或模型调用证明。

构建run.Grx8nD、OpenCodeMcpBindingsTest run.DT5qsp 1项通过。`15e9bb8`纳入yxi_entertainment交付并经主线程审读的标准库stdio MCP echo fixture及原生测试。第一次run.BDp539已连接成功，但项目配置字节不变断言失败：OpenCode启动时给空配置自动补schema。`be95867`改为初始完整schema配置，继续保留完整字节比较；构建run.xu5Qlu与真实OpenCode1.18.32测试run.E64fGi 1项通过、无跳过，验证握手/工具发现、操作记录connected、拒绝重复加载、项目配置未改变。未进行模型工具调用。

官方实现核对：OpenCode v1.18.32的server/routes/instance/httpapi/handlers/mcp.ts与mcp/index.ts，add更新InstanceState.config而非调用全局配置写入。仍需Claude/Codex接入、三者实际调用同一资源、升级/停用/卸载及共享插件UI。yxi_pilot审查确认版本替换/回滚模型缺口；其enabled覆盖风险已由当前拒绝原生同名覆盖防住，后续更新适配仍需保留用户原生状态。未发布。

## 共享 MCP 资源登记与原生配置投影

`a301384`新增 SharedMcpDefinition/SharedMcpRegistry/SharedMcpSettings。资源身份包含主机、插件、来源及版本，同机名称冲突和过期revision禁止覆盖；定义保存一次，desiredRunners分别登记Claude/Codex/OpenCode意向。提供stdio argv和HTTP URL到三家配置字段的投影，不拼接shell命令，不读取或复制原生账号令牌。索引损坏保留原件，备份恢复后要求核对。当前没有把desired称为applied/verified。

构建run.Q5reVQ与SharedMcpRegistryTest run.Z39IrK三项通过，无跳过。覆盖同插件本地/两服务器隔离、多个运行器意向、过期写/同名冲突、参数原样保留、HTTP配置及损坏索引保护。仍缺配置应用/回读、环境与授权引用、版本升级/回滚、用户界面和三运行器实际调用共享资源验收；URL暂不接受查询参数，不能宣称全部MCP兼容或插件共享完成，未发布。

yunxi组继续协作：yxi_entertainment准备最小stdio MCP测试服务（仅echo，无文件/网络/命令执行）；yxi_pilot独立审查本轮登记模型及原生格式。产物写到windows-test-reports/shared-mcp-fixture/，尚未验收。

构建前磁盘门槛再次触发。检查所有目标标签/来源revision和容器引用后，移除62个未被容器引用的旧Yxi测试镜像，保留最近4个与两个基础版本；再按精确cache ID及Yxi Gradle描述过滤清理77个旧缓存记录，保留报告与清理清单。空闲恢复至13,962,051,584字节。清理清单位于/root/.cache/yxi-isolated-tests/removed-image-inventory-20260923.json和removed-cache-inventory-20260923.json；未全局prune或触碰生产容器。

## 官方插件图标扩充与 SVG 视口修复

`68f43b7`整合 yxi_pilot 收集的 GitHub、Slack、Dropbox、Google Drive、Notion、Linear 官方资源。主线程逐项核对 manifest 的 SHA256，保留原始文件和 SOURCES.md 来源记录；按插件名称与官方域名双重匹配，加上原有 Canva/Gmail 共8个内置官方图标。构建run.HUp0vH、图标回归run.q0U8K5通过后实际看图发现SVG尺寸问题，未将解码成功当成视觉成功。

`83388f7`按SVG原始宽高/viewBox等比居中缩放，修复Dropbox偏小、192px Drive被裁切；更新缓存版本，SVG资源禁用Git换行转换以保留原字节。构建run.n54MiA、PluginIconsTest run.faEf1n四项通过（含8个官方图标、尺寸像素检查、官网回退/缓存及资源边界），无跳过。渲染结果已查看，修正图位于.artifacts/plugin-brand-fixed/。不宣称整个4230条目录均有离线图标，也不等于插件共享后端完成；未发布。

## ACP 取消与晚到审批

`1a67d05`串行处理取消与权限回复，取消后到达的session/request_permission回复cancelled；已取消会话不再批准新请求。仍等待原生prompt终态，不把cancel通知当结束。构建run.OMo7vD、AcpClientTest run.Q4kpQj五项通过，无跳过；属于协议fixture，三运行器原生ACP验收仍待完成。

## Gemini/Hermes/Grok 本机 ACP 启动与六运行器检测

`00410f6`新增 LocalAcpTransport，以明确 argv 启动自有进程并串行写stdin；关闭只回收自有进程，初始化失败会清理。不自动认证或切换计费；Gemini --acp、Hermes acp、Grok --no-auto-update agent stdio。Gemini/Hermes数据目录用各自原生环境变量，不复制认证。Grok本机检测补齐官方.grok/bin及GROK_BIN_DIR，排除npm包猜测；版本命令用--no-auto-update version，远程解析补.grok/bin。六种运行器均进入本机发现清单，但发现不等于完成会话适配。

构建run.Uf8l8K通过；LocalAcpTransportTest run.hh8mAd 1项（循环三种引擎）、LocalWorkspaceReadOnlyTest run.AizQgd 6项通过，无跳过。子进程fixture验证参数、cwd、Gemini/Hermes数据根和只关闭自有进程；未验证三种真实原生ACP/登录/模型推理，未开放其新建或发布。

服务器Agent报告已返回：grok-hermes-protocol.md的命令经官方Hermes ACP页及xAI headless-scripting.md复核，已用于本轮实现。shared-mcp-review.md存在OpenCode项目配置层级的过宽结论，已要求原Agent纠正并补OAuth删除行为依据；修订报告已取回待后续实施审查。yxi_pilot已继续收集六种插件官方图标，产物目录windows-test-reports/plugin-brand-assets/，仅审核后集成。

## ACP 通信基础与 yunxi Agent 分工

`5238892`新增 AcpClient：JSON-RPC 2.0初始化、显式认证、session/new、prompt、cancel、原生权限选项回复；请求ID区分数字/文本，写入串行化，读帧限长，事件拥塞明确失败而非静默丢失。客户端未实现的文件/终端能力不声明，不支持请求返回method-not-found。prompt超时保持会话未确认，不自动重发；取消通知不冒充原生轮次结束。

构建run.fPjXdq、AcpClientTest run.0P5R3r四项通过，无跳过。验证权限归属/选项、不同类型ID、取消后等待原生响应、超时不重发、拒绝未实现能力。仍为内存协议fixture，尚无Gemini/Hermes生产transport及原生验证、认证/界面接入，未发布。

用户再次明确可派任务给yunxi组。核对tmux归属后派给cc-yxi_entertainment(%9)共享MCP适配只读检查，cc-yxi_pilot(%10)Grok/Hermes原生协议官方资料核对。要求不改共享源码、不构建/安装/操作其它会话，独立报告到 /root/src/workspace/yunxi/windows-test-reports/acp-and-plugins/；已确认两者开始工作，报告尚未返回，不视为验收完成。

本轮构建因可用磁盘不足4GiB在启动前被拦住；检查后仅删除65份已完成且image.json带Yxi隔离标签的run.*/context副本（约1.74GB），保留报告、镜像和基础缓存。清理后空闲约5.73GB并完成构建。未清理其他应用镜像或生产数据。

## Gemini 本机识别与官方订阅说明更正

`e1205b9`新增Gemini本机/PATH/npm识别，Windows解析 @google/gemini-cli 的 bin 字段为明确Node+JS参数；GEMINI_CLI_HOME作为home根再追加.gemini，与本机0.34.0源码一致。缺少Node仍记录已发现安装并说明原因。本机实际定位D:/qianduantool/nodejs的npm包0.34.0；显式Node执行--version持续未返回，核对PID/父PID/命令后仅结束该探测进程。未据安装清单宣称运行可用，未改真实配置或认证。

发现此前官方方案说明过时：Google 2026-05-19公告明确2026-06-18起个人免费/Pro/Ultra停止由Gemini CLI服务，迁至Antigravity CLI；企业Standard/Enterprise及付费API继续支持。旧geminicli.com/plans页面仍列个人方案，不能忽略更明确的迁移公告。官方配置卡与official-provider-defaults.md已修正为企业订阅及独立官方API项，不自动回退API或替换运行器。来源：https://developers.googleblog.com/an-important-update-transitioning-gemini-cli-to-antigravity-cli/ 。

构建run.ylMkKu、LocalWorkspaceReadOnlyTest run.W4R7nT五项通过，无跳过；覆盖Gemini npm带空格路径/自定义数据根/缺少Node及已有只读功能。官方ACP文档和本机源码确认--acp入口，但ACP适配尚未实现。Antigravity消费者订阅接入需继续核对原生能力，不把Gemini旧登录能力当成当前可用订阅。未发布。

## OpenCode 项目导航与待处理状态

`5f5fc3f`将远端 OpenCode 任务纳入 ProjectTree 的项目分组/计数/搜索/折叠，加入小圆角灰底选中态、重命名、置顶、收藏、归档和移动显示分组；收藏/置顶区可进入同一登记会话。审批、提问或未知投递显示待处理；未完成轮次显示运行，已归档但需处理的任务不会被普通列表隐藏。补齐项目新建对话的OpenCode回调；协作组协议尚未适配时仍不开放组内创建，此处显示分组不是服务器协作成员登记。

构建 run.1jVQo5 通过；OpenCodeNavigationTest run.9GRzUj 1项、新建弹窗既有回归 run.T3Bcvh 2项通过，无跳过。验证未知投递/归档可见、接收与完成状态转换、导航重读及不同主机不串收藏。新行菜单全部点击与Windows视觉验收未在本轮完成；未发布。其余运行器、共享插件后端、官方账号应用等完整目标继续保留。

## 服务器 OpenCode 新建入口与任务工作台

`7e65b84`接入 RemoteOpenCodeTasks/RemoteOpenCodePane，服务器主新建弹窗的OpenCode选择可进入模型读取与创建，创建提示词保存草稿而非立即运行。远端任务按 hostKey、用户、原生数据目录和会话 ID 登记；侧栏列出并搜索服务器OpenCode任务。复用本地OpenCodeConversationPane和控制器处理聊天/审批/问题/停止。关闭/断开连接释放所属服务。终端DesktopLaunchPlan仍只支持两种，未把OpenCode错误塞进Claude终端路径；独立worktree/协作组创建未开放。

构建 run.h90CmK 通过。原生SSH任务模型读取/创建/保存/主机过滤与生命周期 run.KN4hTk 1项、含主机身份隔离的旧索引回归 run.IMfVzb 6项、新建弹窗既有回归 run.EJaucu 2项、共享聊天真实窗口回归 run.o16Lbw 1项全部通过，无跳过。原生运行器1.18.32在隔离容器中执行；新远端模型选择页完整GUI点击、Windows实机、真实订阅推理以及跨重启继续仍待验收。项目分组新建入口的OpenCode回调尚需统一，当前主侧栏入口已接通。未发布。

## OpenCode 远端 SSH 服务与转发

`fecb50b`新增 RemoteOpenCodeServer，独立 SSH exec 启动监督进程、通过stdin传递随机服务密码、只监听远端回环地址，再租用独立本地转发。检查启动身份/工作目录及认证health。关闭只释放此次服务/转发，stdin断开或SSH关闭触发监督进程清理自有进程组。尚未挂到服务器新建入口。

构建 run.nNje2C 通过；RemoteOpenCodeServerTest 在 run.b9b4YW 1 项通过、无跳过。隔离容器中真实私有SSH与原生OpenCode1.18.32：两个服务并存，创建并读回会话；关闭第一个后其PID退出、第二个仍健康、SSH主连接仍可执行命令；断开SSH后第二个PID也退出。未在hk13生产环境拉起服务，未公开端口或修改其登录。远端任务登记、界面及原生状态适配仍待接入，未发布。

## OpenCode 原生任务管理、审批与完成链路

`d474de3`新增 OpenCodeNativeConversationTest。无网络容器中使用真实 OpenCode 1.18.32，通过本机 OpenAI-compatible fixture 返回 bash 工具调用及最终文本，运行 LocalOpenCodeTasks.models/create 和 OpenCodeTaskController.enqueue/sendNext/replyPermission/refresh 完整链路。配置和标记文件仅位于 /sandbox/home/native-conversation。

构建 run.7Bad8t 通过；原生测试 run.23XWtX 1 项通过、无跳过，13.5 秒。核对保存的原生任务身份、审批前文件不存在、once后原生bash产生文件、最终回复和 Completed、重新读取 InstructionQueue 仍为 Completed；模型接口收到2次请求。测试使用真实运行器与工具执行，但模型响应为本地fixture，不代表真实订阅/第三方在线推理、Windows进程及完整GUI均已验收。未发布。

## OpenCode 本地任务登记与界面接线

`34633de`接入 LocalOpenCodeTasks、原生模型读取/新建弹窗、OpenCodeConversationPane。任务记录加入 engine/provider，旧 Codex 默认值和 key 保持兼容；OpenCode 使用独立索引文件及 key。创建写前日志、保存原生 ID/工作目录后才开放控制器；未知创建与索引恢复需要人工核对。AppState 管理服务关闭，检测到本机 OpenCode 的安装行提供新建按钮。

基础聊天界面连接持久队列、定时读回状态、三种审批、多问题/多选/自定义回答、拒绝及停止；Enter/Shift+Enter/IME保护沿用本地输入逻辑。重启后记录保留，但尚不自动接管原生会话。服务器创建仍未开放。

构建 run.9zkZHy 通过；索引回归 run.3z70Z8 6 项、真实 Compose 窗口点击 run.VJKtOU 1 项通过，无跳过。窗口fixture验证连点仅一次投递、草稿清空及回复显示；截图已查看，.artifacts/opencode-ui/opencode-conversation.png。此轮没有验证整个新建弹窗与真实运行器组合、真实模型推理、审批全流程、Windows GUI，不能宣称 OpenCode 完整交付。附件/Markdown/后端消息形态/跨重启续聊等继续按PRD，未发布。

## OpenCode 持久化发送与原生记录核对

`a9508cd`新增 OpenCodeTaskController：复用 InstructionQueue，生成并保存原生格式消息 ID，携带明确供应商/模型发送；204仅记录提交结果待核对。读回同一会话、相同ID/文本/模型的原生用户消息后确认接收。助手完成记录须关联 parentID、具有完成时间和结束原因；工具调用步骤结束不等于轮次完成。未知投递和未完成轮次阻止后续发送，不自动重试。审批/提问/停止入口串行化并刷新原生记录。

构建 run.CPa25Q 通过，控制器回归 run.2LxzKT 1 项、协议回归 run.ebk6O4 4 项通过，无跳过。覆盖提交后暂缺历史、阻止重复和后续发送、历史匹配确认、工具步骤不提前完成、终态后第二条发送及重启保留未知结果。均为协议fixture；原生模型推理、更多错误/中断状态、界面和任务登记尚待完成。未开放创建或发布。

## OpenCode 审批、提问与原生模型目录

`f999049`：按照官方 v1.18.32 SDK 的 /permission 和 /question 接入列表/回复/拒绝；回复前核对 sessionID，明确区分 once/always/reject，多问题答案按原生数组顺序发送。模型按 connected 供应商筛选，保留供应商身份与真实 model.id，不把连接状态冒充订阅权益。

构建 run.dptNeJ 通过；OpenCodeClientTest 在 run.Rweb9h 4 项通过，覆盖跨会话审批阻止、三种回复、多问题及多选答案、拒绝问题、已连接模型与既有生命周期/失败不重试。真实 OpenCode 1.18.32 回归 run.acRXor 1 项通过，增加空审批/提问列表、供应商模型读取。两组均无跳过。原生测试未触发真实模型审批或推理，因此不能宣称审批端到端完成；队列、界面与事件仍待接入，未发布。

协议来源：https://raw.githubusercontent.com/anomalyco/opencode/v1.18.32/packages/sdk/js/src/v2/gen/sdk.gen.ts 。

## OpenCode 1.18.32 原生建会话验证

`023678a`：使用官方发布包 opencode-linux-x64-baseline.tar.gz（SHA256 763af386ef88a8cab18df00fcf055690e5a55e31a7088beabe02307142a6adce），仅缓存到服务器 /root/.cache/yxi-native-tests/opencode-1.18.32，再只读挂入无网络隔离测试容器；没有在宿主启动或安装到用户 PATH。

构建 run.6YvzOa 通过，OpenCodeNativeTest 在 run.3ZI5sY 1 项通过、无跳过。原生 health 回报 1.18.32；使用 LocalOpenCodeServer 和 OpenCodeClient 实际创建空会话，回读同一 ID、工作目录、会话列表和空消息，确认不处于运行状态。使用独立 HOME/XDG 路径，无真实账号、无模型请求。证据证明原生启动/认证/空会话读写链路，不证明推理、审批、Windows进程、服务器会话集成或界面完成。

后续继续连接审批/提问、模型选择、持久化投递与新建界面；六运行器完整可用仍未达到，未发布。

## OpenCode 本机服务进程管理（开发中）

`d36c475`新增 LocalOpenCodeServer：显式 loopback/动态端口/关闭 mDNS、独立随机 Basic Auth 密码，只启动选定的可用运行器。限长读取启动输出、认证健康检查后返回客户端；失败、超时及关闭只回收自有进程。未修改原生配置或认证文件。

隔离构建 run.URXqfr 通过；LocalOpenCodeServerTest 在 run.vkuGl5 三项通过、零跳过。真实子进程 fixture 验证认证/参数/健康、正常关闭、启动超时清理，以及无关进程仍存活；启动公告只接受精确 loopback 地址。不是原生 OpenCode 验收，尚缺原生版本验证、审批事件、持久队列、新建/对话界面及远程服务生命周期，未开放创建或发布。

启动格式核对官方源码 https://raw.githubusercontent.com/anomalyco/opencode/dev/packages/opencode/src/cli/cmd/serve.ts 和 cli/network.ts（2026-09-23）；实际安装版本仍需实测。

## OpenCode 原生协议客户端（开发中，未开放创建）

`bceaa03`新增 OpenCodeClient，支持供应商、会话/消息/状态读取以及创建、异步发送、停止。请求明确携带工作目录和模型，使用 loopback Basic Auth，禁止重定向，写操作不做应用层自动重试。资料与待接入层见 research/opencode-session-adapter.md。

隔离构建 run.4EbLvh 通过，OpenCodeClientTest 在 run.uOocyO 两项通过；HTTP fixture 覆盖认证、Windows目录传递、模型/文本、创建/发送/停止以及拒绝重定向和重复写入。尚未验证真实 OpenCode 运行器；自有进程、原生审批/事件、持久队列与界面接入仍未完成，serverCreation 保持关闭。本轮不代表六运行器新建完成，未发布。

## 本地附件发送路径（2026-09-23）

`a70aa89`：turn/start 与 turn/steer 根据实际传输位置校验附件。本机要求当前系统可读取的绝对文件路径，图片使用 localImage；其他文件引用明确标为本机。服务器路径仍由远端使用，不错误检查本机是否存在。

隔离构建 `run.YUEarr` 通过；`LocalAttachmentInputTest` 在 `run.gIVO2C` 3 项通过、0 跳过，覆盖图片输入、本机文件/缺失文件/目录/相对路径、远程路径兼容。此轮在 Linux 容器执行，尚不能证明 Windows 实机附件发送；草稿快照、附件选择/粘贴/预览接入仍待完成，未发布。

# Windows 工作台实施记录

## 本地输入快捷键（开发分支，未发布）

`a5ef8bc`接入Enter/小键盘Enter发送、Shift+Enter换行；存在TextFieldValue输入法composition时不截获Enter。按键与按钮复用同一条同步持久化队列入口，不另走直发路径。离线协议fixture的多轮ID改为递增，避免重复ID掩盖第二轮验证。

构建`run.qU3Vcj`通过；实际Compose窗口键鼠回归`run.baF87I`通过，验证Shift+Enter只换行、不多发请求，随后Enter只发送一条并清空已持久化草稿；会话控制器回归`run.98G85h`通过。中文输入法采用与既有输入器一致的composition保护，Windows实际IME仍待验收。附件缩略图、粘贴和@引用尚需接入本地附件存储及Windows原生路径校验，未把键盘改动当作附件功能完成。

## 本地新建与对话界面连接（开发中，未发布）

新增本地“新建对话”弹窗，读取官方模型、核对本机目录后调用已有官方创建与登记逻辑；创建本身不发送提示词。新会话页面接入既有`CodexTaskController`和持久队列，支持文本发送、手动队列、停止、命令/文件审批、额外权限与用户问题表单。发送先同步保存队列再清空草稿，连点不重复投递；创建也有提交保护。未持有的已登记会话不直接恢复写入，提供只读历史入口。

创建结果未知和索引备份恢复均提供明确的人工核对入口；索引恢复审核标记改为跨重启持久化，避免重开应用就解除保护。新鲜空会话尚无历史时不开放“核对状态”，避免原生历史接口暂不可读导致发送界面失去就绪状态。

`0a0190a`构建`run.5bad5Q`，界面发送`run.dmilax`和原生官方创建`run.yAw19o`通过；`7bd1c7e`构建`run.ZbfudL`、双击发送界面回归`run.G3BWFs`通过，确认只有一次turn/start且草稿进入持久队列后清空，流式结果能显示。界面测试使用协议fixture，原生创建测试使用隔离合成身份；均不证明真实官方订阅模型推理已验收。截图已查看，位于`.artifacts/local-conversation/local-conversation.png`。最终`2486cf0`构建`run.7Y8Owm`、界面回归`run.VUsBAP`1项、含跨重启恢复审核的登记回归`run.llxhp1`5项全部通过，无跳过。

尚缺：统一聊天输入器的附件/@引用/快捷键与Markdown表现、模型切换、跨重启续聊及外部占用核对、第三方本地线路、其它运行器和Windows完整GUI验收；当前不能标记完整L2或整体PRD完成。

## 选中态统一为小圆角中性加深（开发分支，未发布）

用户指定参考Codex侧栏的小圆角灰底选中态。新增共享`QuietChoice`，桌面筛选选择统一8dp圆角、中性选中背景、正常文字/图标色，无彩色选中描边、阴影或额外加粗。侧栏导航、会话行、WorkbenchTabs、插件切换、分类、运行器及其他同类FilterChip入口一并更新；交互回调、禁用状态和原有可访问性语义继续沿用。业务状态提示和输入焦点样式不是选中态，本次不混改。

`a388e02`构建`run.6FXOKr`通过，既有新建弹窗/路径回归`run.LM8mzw`2项、原生插件安装隔离/界面回归`run.SVpYbX`1项通过。新建弹窗和插件页实际截图已人工核对，选中灰底、小圆角、正常字重且无蓝色选中边框；截图在`.artifacts/quiet-selection/`。本轮是可逆样式调整，不新增镜像样式常量的单元测试；完整Windows DPI验收仍待完成。

## 六种运行器的内置官方配置（开发分支，未发布）

用户要求默认提供官方订阅，OpenCode/Hermes若有官方服务也加入。已核对官方资料并落实[官方默认项规则](official-provider-defaults.md)：Claude、ChatGPT、Google账号/订阅、Grok、OpenCode Go和Nous Portal均提供首选内置项；OpenCode Zen作为单独按量服务，不冒充订阅。Grok Build官方启动命令已确认为`grok`，只更新探测命令，不据此开放未完成的会话适配。

新增`OfficialProviderProfiles`与配置页固定卡片，无需手动新增线路；使用独立官方身份ID，与空profileId沿用外部配置分开。卡片明确标注账号和线路尚未核对，不自动修改旧会话或共享配置。当前完成的是内置定义和展示；各运行器实际登录、权益读取、默认选择应用及切换仍待逐一接入，不能把卡片显示当作官方模型请求验收。

`235579e`构建`run.aqmdnh`、启动校验回归`run.SprErP`3项通过。静态说明卡不另写镜像常量的测试；完整界面及各服务真实登录/调用仍待验收。未购买订阅、未修改真实登录或发送模型请求。

## 插件按机器共享与分类整理（用户澄清，开发中）

用户明确插件应由Claude/Codex/OpenCode等共用，不应把市场标成Codex专属。新增[共享插件PRD](shared-plugin-prd.md)，将每台机器的共享资源登记、运行器接入和服务授权分开；保留本地与当前服务器隔离。当前后端主要来自Codex原生目录，Claude服务器目录仍为另一路入口，跨运行器共享调用未实现，不能仅靠改名宣称已共享。

市场组标题去掉“适用于Codex”，改为发现/已安装插件；具体接入状态留在详情。新增按用途固定排序的14类、数量筛选、中文分类搜索和未知分类归并。只读核对实际目录的15种原分类均已映射（Engineering并入开发工具），没有按插件名称猜分类。内置安卓模拟器纳入开发工具的筛选和计数，不再在所有分类上方常驻。

`30c3627`构建`run.Dci9C2`、分类规则`run.WLoWMy`2项及目录状态`run.vuOQQd`2项通过。`9c5b16a`将内置工具纳入同一筛选，构建`run.gdHFZH`及原生安装/机器隔离/界面回归`run.ubmZdr`1项通过，截图已查看。服务器暂存的旧运行器目录切换补上“目录来源/原生安装记录”说明，明确它不是跨运行器兼容开关；该纯文案补充未额外重跑。改动未发布，完整共享后端仍按新PRD继续。

## 新建运行器入口统一与支持状态（开发分支，未发布）

用户反馈新建只显示Claude/Codex。已确认`NewSessionDialog`和`DesktopLaunchPlan`只接了这两种，`Session.agent`也只区分两类；配置页已有六种不代表四种其它运行器已完成会话适配。

新增`RunnerCatalog`，新建和配置页使用同一份六种目录与官方图标；允许选中其它运行器查看真实说明，创建适配未完成时禁用提交，保留原有后端阻止错误启动。已有安装与创建支持分开显示；只用固定命令查询可执行文件，不执行AI任务。初始传入其它运行器时不再静默回退Claude。

只读核对hk13还发现Codex安装在`~/.local/bin`但不在非交互PATH。安装检查和实际启动改为共享路径解析，覆盖已核对的`.local/bin`、OpenCode和Hermes常见路径；Grok的启动命令未确认，不猜测。`d03b218`构建`run.EDuQJO`，启动校验`run.cTYl2F`3项、路径/真实弹窗渲染`run.Vf3N2W`2项通过。最终`a74a622`调整选择态与统一高度，构建`run.1fY5wj`、2项回归`run.D1FDtN`通过；六种官方图标、选中态、未接入提示及禁用创建按钮截图已人工核对，保存在`.artifacts/new-session-runners/new-session-runners.png`。未在生产服务器启动新Agent。

这一步不等于六种均可新建。OpenCode/Gemini/Grok/Hermes的启动协议、原生会话、状态识别与独立配置仍是待完成事项；必须补完并实测后才能打开创建能力，不能仅翻转标志位。界面修改未发布。

## 插件官方图标与位置说明修复（开发分支，未发布）

用户反馈市场全部显示拼图图标。只读核对本机原生目录：4230条中4211条提供logoUrl、4045条提供官网；Canva/Gmail目录CDN请求在本机实测403，旧实现直接静默回退占位图。未读取账号令牌或修改插件安装状态。

`b4b3317`保留官网/深色logo/composer图标元数据，增加HTTPS跳转、官网icon声明与favicon回退，支持PNG/ICO/SVG并渲染成64px透明PNG；请求并发4、单文件512KiB、缓存7天/32MiB。Canva/Gmail使用已核验官网原始ICO并记录来源及SHA256，匹配名称和发布方域名，确保这两个示例不依赖失败CDN。其它插件仍依赖发布方资源可用性，未宣称4230条图标全部实测成功；不以随机聚合站图片冒充官方图标。

列表组合标签“本地电脑 · Codex”改为“适用于 Codex”，安装位置留在详情和安装按钮，明确说明供Codex会话使用。详情同时显示品牌图标。本地/当前服务器原安装隔离语义不变。

构建`run.oP1btS`通过；图标原始资源解码、官网回退、缓存、SVG及URL边界测试`run.krCNZN`3项，目录元数据和安装状态回归`run.6E8O7I`2项全部通过，无跳过。Canva/Gmail真实图标渲染结果已目视核对，保存在`.artifacts/plugin-icons/plugin-canva.png`、`plugin-gmail.png`。尚未打包发布，完整市场Windows点击/滚动目视验收仍待完成。

## 本地会话创建登记与Golutra参考（开发中）

`LocalCodexTasks`已连接官方配置、实时原生模型目录和既有`CodexTaskController`：创建时先持久化请求意图，收到原生ID后登记用户/系统/数据根/目录/模型，再核对原生返回的线路、模型及目录后开放控制器。索引损坏不覆盖，创建结果未确认跨重启保留并阻止自动重复创建；仅人工核对后可解除。退出保护计入创建和本地活动会话，并关闭自己持有的启动中进程。

首版`6bc597d`构建`run.WjcutS`、登记测试`run.plV6gf`及原生创建回归`run.FE4nSB`通过。最终`9408291`构建`run.c4AjRJ`，登记/身份隔离/损坏保留/未确认恢复/关闭后拒绝创建测试`run.IuqNMu`4项、真实Codex原生创建与登记回读回归`run.LdQ7LP`1项通过，无失败或跳过。原生测试使用隔离的合成身份，只创建空会话，没有真实订阅模型推理。新建/发送UI、已有会话接管、跨应用互斥及每Agent第三方线路仍未完成。

用户追加Golutra参考；已克隆固定提交`8b68a14183afa6ec26f9905f2a81cbd91ed1b35b`作静态源码核对，并完成[参考评估](research/golutra-reference-review.md)。借鉴运行器注册、派发分层、状态与协作交互，保留Yxi现有PRD及技术栈；其BSL源码未移植。静态核对不作为该项目运行验收，插件市场占位和终端文本接入不替代我们的原生历史/真实插件能力。

## 本地官方模型目录接入（开发中）

`6c5cdca`把官方连接的`model/list`接到本地配置页，支持分页、去重、隐藏模型过滤、搜索选择及原生默认模型选择；不从全局第三方模型名推断默认值。空列表、重复游标或超过20页均报错，不生成虚假的默认模型。切换运行器时取消请求并清空旧模型状态；刷新失败保留当前列表并显示错误，手填非列表ID显示无效，后续创建必须通过`selectedOfficialModel()`校验。

构建`run.g7sgHE`通过；目录分页/空列表/循环游标测试`run.zZDowi`2项通过，包含原生模型目录读取的官方连接回归`run.3v1gv2`1项通过。测试仍使用隔离身份，未执行真实订阅模型推理。配置页新按钮/选择器已编译，完整Windows点击与视觉验收尚未完成；新建发送和原会话续聊仍未开放，不把此步计为完整L2。

## 本地官方订阅进程与发送校验（开发中）

新增`LocalCodexProfiles`，原生数据根保持不变；官方模式在进程参数中移除旧API地址、选择内置openai提供方及官方登录地址，并剥离继承的API Key/联合身份环境变量。没有新增强制登录方式或重写auth/config。连接后及后续新建/续聊/分支/发送前校验账号类型和有效配置，旧会话另外核对实际提供方。resume/fork必须显式指定官方提供方，额外会话配置目前仅允许已接入的思考强度字段，不能重新注入第三方端点。

`e8e7a06`编译`run.rxYRcd`，规则测试`run.8YReW4`及原生隔离测试`run.EsoSc8`通过；证明旧第三方端点被进程级覆盖，API Key账号被拒且已有auth/config字节不变。`1ee2bf0`编译`run.JDFGo3`，3项规则测试`run.waY0fj`、原生测试`run.0VTtPr`通过，补了离线ChatGPT测试身份的通过路径。`e78f99d`追加会话级覆盖拦截及真实原生客户端的guard接线测试，构建`run.MWhp1o`、3项规则测试`run.Ak5uSC`、原生测试`run.kCnU9O`全部通过，无失败或跳过。

边界：正向测试身份为隔离的合成JWT，只核对本机原生接口，不发模型请求，不能据此声称真实官方订阅模型发送已验收。尚需官方模型目录选择、发送/登录UI、独立第三方配置、本机续聊控制权和真实请求回归；当前本地产品页仍只读。已有全局第三方模型名不能直接作为官方新会话默认模型，后续由所选原生模型目录确认。

## 本地续聊 L2：通道接入与认证边界（开发中）

`49443b6`将`CodexAppServer`的传输抽象为SSH/本机进程两种通道，保留SSH调用兼容；本地通道只持有自己启动的进程，参数数组与显式环境启动，不经过shell。本机目录按本机路径语义校验，后续可复用已有会话控制器的审批/取消/投递状态。构建`run.EoM1XZ`通过；真实本机Codex通道与历史保持测试`run.XOb2iI`1项、原SSH接口回归`run.JINHBq`6项全部通过，无跳过。只读页面尚未开放发送，L2未完成。

官方订阅接入前对测试版本0.153.4源码进行核对，发现强制登录类型不匹配会执行注销。因此不会仅硬加`forced_login_method=chatgpt`或硬写普通API地址。后续需验证进程级端点覆盖、原生账号类型、环境变量隔离、每Agent模型及原会话控制权；完整依据和下一步门槛见[认证边界复核](research/native-codex-auth-boundary.md)。未改用户共享登录或配置，也未对真实历史执行恢复/新建。

## 本地工作台 L1 开工（2026-09-23，开发分支未发布）

用户授权复核后直接实施，并将机械任务交hk13的yunxi分组Agent。`cc-yxi_entertainment`完成紧凑映射只读审查；报告提出的Codex列表URL无法编辑、失效列表弹层未关闭已修复。`cc-yxi_pilot`完成安装路径与离线样例矩阵；据此补正npm原生PE入口直接执行和Windows Hermes目录。两者没有改共享代码或发布产物。报告保存在hk13 `windows-test-reports/local-workspace-l1/`，本地副本在工作区`.artifacts/local-workspace-l1/`。

`8c836ce`新增本地顶级位置、运行器检测/选择入口、原生账号状态、项目目录登记、Codex历史列表/搜索/归档/分页阅读。执行位置不伪造成SSH主机；本地配置页不能落到服务器配置。本机启动默认本地，升级保留已保存位置；探索旧入口转到本地。Windows npm解析官方包清单并直用原生exe或Node参数数组，路径含空格/中文不经shell拼接。版本检查限时5秒、输出16KiB；历史请求限时15秒、响应8MiB，暴露的方法白名单只有初始化和读取，没有start/resume/login/config-write。

`8c836ce`构建 `run.igmikq`，4项发现/协议/导航测试 `run.Sg8VrP` 和紧凑页面真实交互 `run.ujxMkx`通过。原生入口修正后 `4c8324d`构建 `run.VbbPU3`，4项复测 `run.Wo9qNP`通过。原生历史界面测试初次 `run.WihQeb`因测试资源路径取错类失败（产品代码未执行），已修正；`1a292fa`构建 `run.U2XNvM`、原生测试 `run.0cqFen`通过，验证Codex真实进程产生的历史能读回、模型请求数不增加、配置和原会话日志哈希不变。该测试使用独立HOME、网络隔离容器和本机回环模型fixture，不用真实用户凭据。首轮截图窗口超出虚拟屏幕右缘，已减小测试窗口；`6814bef`构建 `run.R6463T`、原生测试 `run.Bc4pYn`通过，工作台/历史截图完整且已目视检查。截图本地路径 `.artifacts/local-workspace-l1/local-workspace.png` 和 `local-native-history.png`。

Windows实测使用`4c8324d`的实际编译类：识别Codex PATH版0.155.0-alpha.16、npm版0.149.0、桌面附带0.155.0-alpha.16，以及npm Claude 2.1.228。三个Codex入口都找到23条未归档历史，截图中的“微调PPT第七页内容”“这个方向怎么样”“acquire和order”均匹配，原生分页读取20轮；全局config.toml哈希不变。只输出数量与账号方案，不输出历史正文或凭据；未创建/恢复真实任务。证据 `.artifacts/local-workspace-l1/windows-readonly-result.json`。旧npm版有效provider接口无法确认，保留未知状态。

此阶段仍为只读历史接入；没有完成官方订阅默认发送、原会话接管、跨应用互斥、WSL探测或其他运行器历史适配。旧本地任务界面保持原执行规则，并未成为官方默认入口。完整Windows GUI/DPI、完整原生工具/附件展示及L2–L5均待完成，不标记整个L1或PRD完成。

## 紧凑模型映射复核（开发分支，尚未发布）

按配置页 PRD 将模型表格改为固定120dp角色列、等宽名称/请求模型列、104dp的1M列；宽屏控件36dp、行距8dp，窄屏转44dp角色卡片。原来字段下面的模型选择器替换为输入框内箭头和可搜索浮层，支持上下键、Enter和Escape；纯模型ID与1M标记分开显示。默认模型移到表格下方；工具按钮对齐右侧，列表URL收入请求选项。

一键设置先选择模型，保留自定义显示名称和角色既有1M状态；可撤销，但手动编辑后失效，避免旧快照盖掉后续编辑。复核修复空模型被编码成单独 `[1m]` 的问题，并归一化旧重复后缀。模型列表URL作为线路元数据持久化，不进入原生 settings；目录比较包括该字段，Codex独立配置模型发现使用该值。相同端点刷新失败保留先前列表，端点/认证/URL变化仍清空旧列表，避免跨供应商误选。

首版 `d1a7797` 编译 `run.iLTe58`，真实 Compose 界面测试 `run.VVnrjh` 通过。复核提交 `7fa9029` 编译 `run.19d3tJ`；界面测试 `run.Npuhpz` 1项、SSH目录URL保存/读取 `run.3Gl7nN` 1项、1M声明处理 `run.vEtiXG` 2项、配置编辑 `run.yobCw9` 5项，共9项通过，失败/错误/跳过均为0。追加长ID悬停显示与完整值选择 `ae82402` 编译 `run.VkDXHi`、界面测试 `run.9AEYeF` 通过；长ID浮层、宽窄/深色及实际供应商编辑页截图已人工查看。实际深色截图另发现页标题未继承正文色，已在供应商编辑页限定内容色；`8c836ce`的`run.ujxMkx`复测通过，深色实际页面标题已目视确认可读。

本轮范围为紧凑模型映射和相关数据保存修正；不是整个供应商PRD完成。尚缺Windows DPI/输入法、错误状态截图和整页点击保存重开/切换主机的GUI验收；SSH目录回读不替代GUI流程验收。当前一键设置保留自定义名称，未提供覆盖选项；内部仍兼容原有带标记字符串，不是完整的独立模型能力存储迁移。

## 1.4.14 已发布（2026-09-23）

冻结提交 `cc422150d795fc58448adca6bd0a85a183b7a1cd`，Windows构建 `35816868110` 全部成功，含托盘中文字体、安装后启动、凭据及浏览器检查。传输任务 `35820353156` 成功，原始ZIP `910634830` 字节、SHA256 `41b2280adc09e43f7c1da80a286e6f91abbd1f2ab2028e6b4c523fae66911738`；hk13 的 Yxi Agent 已完成校验、原子发布、回滚保留和临时SSH凭据清理。本机已删除一次性GitHub Secret并核对公网：版本及更新说明均为1.4.14，安装器200、328335016字节。

发布包 `Yxi-1.4.14-full.nupkg` 为323813032字节、SHA256 `9d0c66602c664a9344c7a7b5fd81d39a82d61949563f78a256cde5248795394c`；安装器SHA256 `63ba9a5188f51a75c2c65618805a8a464d3cd69bba2c0d9926fa302eb610ca67`。证据：hk13 `/root/src/workspace/yunxi/windows-test-reports/release-1.4.14/result.json`。1.4.14仅为冻结的托盘补丁，不含其后新增定时任务、新本地工作台PRD或紧凑模型映射。完整PRD和Windows目视/DPI验收仍未全部完成。

## 探索中的定时任务（开发中，尚未发布）

新增“探索 → 定时任务”，可创建、编辑、暂停/恢复和删除计划、查看执行记录。支持一次、每小时、每天、每周，保存计划时区；每天/每周按日历推进。由正在运行的 Yxi 客户端调度，退出/休眠期间不执行，恢复后每计划最多补一次，离线/会话忙跳过当次。尚未实现服务器常驻 cron、系统计划任务或关闭客户端后继续运行。

本地目标使用 Codex/Claude 任务，每次新开任务；服务器目标绑定原服务器身份和终端 runtimeId，或绑定已有 Codex 任务。终端使用既有投递校验并区分已投递/已完成；Codex 目标需在 Yxi 中打开过且连接可用，不为计划修改原会话自动投递设置。服务器端定时投递尚未完成完整双主机 E2E 验收。

执行前原子保存认领记录和下次时间，进程中断后暂停原计划且标记结果未确认，不自动重放；备份恢复时停止调度，明确报错。用户可核对目标会话后标记已人工核对，再恢复计划。尚未发送且中途失败的排队指令会取消，避免以后意外发送。暂停仅阻止后续触发；已启动的本地任务可在本地 Agent 页停止。

`9b1c9c8623fab0f5493f23be4e2c734b3abefa08` 编译 `run.3eCB73`，调度持久化/错过周期合并/夏令时/备份恢复测试4项 `run.VpkbJw` 通过，真实 Codex 定时执行与页面渲染 `run.4CeqgP` 通过。最终文案与未发送指令取消改动 `f00c75ba3d048a552989f4fe5fcd83c3dfbb321a` 编译 `run.hOHl5X`、4项调度测试 `run.Z8uggQ`、真实 Codex 定时执行及界面渲染 `run.abldkU` 复测通过。定时任务不包含在已冻结的 1.4.14 托盘补丁中。

## 托盘菜单中文方框修复（历史准备记录；已随1.4.14发布）

1.4.14 补丁冻结提交 `cc422150d795fc58448adca6bd0a85a183b7a1cd`，Windows CI `35816868110` 已完成，含原生托盘中文字体和Windows公钥检查；发布证据见上方。构建链接：https://github.com/liang-senbei/yxi/actions/runs/35816868110 。

用户截图中的托盘右键菜单中文为缺字方框。AWT PopupMenu/MenuItem 未设置字体，未继承 Compose 字体。现显式按系统菜单字号选择已安装且覆盖菜单字符的物理 CJK 字体，并应用到菜单及每个菜单项。本机 Windows JDK 21 检查选中 Microsoft YaHei UI，四个菜单项字符覆盖和原生菜单 peer 创建通过；检查已在 Windows CI 通过，修复随1.4.14上线。尚缺用户此台电脑更新后的菜单屏幕目视确认，不能将字体覆盖检查等同于该机器最终显示结果。

范围：完整落实 `windows-workbench-prd.md` v0.3。开发分支 `codex/windows-workbench`，与 hk13 主工作树隔离。以下勾选仅代表具体实现状态，不代表整体已完成。

用户追加：美观性与设计感是核心验收；待处理提示词参考输入框上方的紧凑指令条，纳入W10与M1。需实现编辑、撤回、按真实能力引导、状态确认与去重；当前只有部分排队显示，不算完整实现。

## 探索、设备连接与本地 Agent（2026-09-22，尚未发布）

本地任务完成判定已改为同时核对进程退出码、原生完成回执和会话身份。退出码为零但运行器明确报错仍显示失败；缺少完成回执、会话不匹配或格式不足时显示“结果未确认”，不允许作为已完成会话继续。错误说明和输出摘要保存在任务记录中，重新打开后保留；未知结果不会自动续发。`edd7874` 的原生 Codex 正常完成/续聊回归 `run.58pVBx`、Claude 回归 `run.HVjPJp` 均通过。最终 `3f26dde40c130cc66725cb58be43ea4af72afba2` 编译 `run.2VVonu`，结果判定 4 项测试 `run.YERKeA`、受控 CLI 的失败/缺回执及重开记录测试 1 项 `run.THp2m4` 均通过、无跳过。受控 CLI 只验证失败协议与应用状态，不冒充真实模型服务故障演练。

Windows 公钥更新补强：不再直接覆盖 `authorized_keys`，先校验 Ed25519 公钥结构，再用独占文件锁串行合并、同目录临时文件原子替换，并保留上一个版本 `.yxi.bak`。重复添加不改内容和备份；替换失败保留当前公钥。使用 .NET DACL 写入代替需要额外审计权限的 `Set-Acl` 路径。本机 Windows PowerShell 临时目录测试已验证首次创建、已有内容保留、备份、幂等、无效公钥拒绝、两个后台进程并发添加、受保护 ACL 和替换失败后的保留/临时文件清理（`dev/test-link-windows-keys.ps1`）。未修改真实 SSH 服务、公钥或防火墙。该测试已接入 Windows 打包工作流，远程 CI 尚未执行；仍不代替 UAC 安装与跨账号提权实机验收。

侧栏新增“探索”，下设“连接”和“本地 Agent”。连接页按当前服务器只读查询 Tailscale JSON，区分在线设备和活跃链路，不替用户配置 Tailscale。参考文档：https://tailscale.com/kb/1080/cli 。

双向 SSH 使用独立、由 Yxi 持有的后台传输，复用已核验的主机密钥信任，30 秒保活，不创建不可追踪的 `ssh -f` 孤儿进程。远端反向端口自 2222、本地服务转发端口自 5901 分别递增并持久化；绑定冲突时尝试下一组。连接成功前用服务器端专用私钥经反向端口登录本机，并校验本机主机公钥及远端监听为回环地址。停止只释放自己的转发和独立连接；退出 Yxi 会断开，公钥不删除。服务器当前配置地址用于建立隧道，需要公网地址时先修改并核验服务器配置；尚未做独立公网地址覆盖或系统服务常驻。

双方只交换公钥。Windows 安装脚本参考用户提供的 OpenSSH 脚本整理为内置非交互步骤，使用系统可选组件安装 OpenSSH Server，已有服务直接复用，不依赖用户桌面的固定绝对路径；没有照搬原脚本的 GitHub latest ZIP 下载路径。UAC 提权保留原用户 SID/配置目录，按普通用户或管理员组写入授权文件，输出可核对的结果文件；本机只读检查已用 Windows PowerShell 实跑，修复了非管理员读取主机公钥失败及 CLIXML 进度干扰 JSON 的问题。未实际运行提权安装或修改此电脑服务/公钥。macOS 分支要求用户先自行开启远程登录。Windows 密钥规则参考：https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh_keymanagement 。Windows 提权安装、另一管理员账号授权、macOS 实机仍待验收，Windows 功能下载不可用时没有离线安装回退。

本地 Agent 使用 Codex / Claude Code 原生 CLI，支持工作目录、输入、运行、停止、日志及任务结果记录，并已接入已完成会话的多轮续聊。会话 UUID、用户输入和结果持久化；重开记录后用明确的原生 ID 恢复，不使用 `--last`。续聊保留此会话的最新上下文，不能当作回退历史轮次；同一会话并发续发被拦截。运行结果和记录保存完成后才释放运行状态，未知结果不自动续发。继承本机运行器登录和权限，不自动切换 bypass。本机 AI 协助入口预填只读部署诊断任务，固定安装步骤直接执行脚本。崩溃后未确认轮次的核对恢复、交互审批 UI、系统服务托管及 Windows npm `.cmd` 包装器仍未接入；退出时停止本应用启动的 Agent 进程。

续聊验证：`c0c26f0a7f1fd5359b41c2c5815b10d0bf720c31` 编译 `run.0rsw0Z`；Codex 原生进程完成后重新加载登记并续聊通过 `run.mqgyIy`，Claude 对应流程通过 `run.djbb2m`，各 1 项无跳过。验证原生 ID 不变、模型后续请求实际含上一轮上下文、输入/输出登记恢复及 Codex 重复续发拦截；属于隔离 API/CLI 测试，未代表完整 Windows GUI 重启验收。

`2736e29879ad024305b76486f2a37365530dc08a` 补强了启动过程取消时的进程持有与退出检查，编译 `run.OLPV8z`，Codex 续聊及停止流程复测 `run.YXHrv9` 通过；未对取消发生的每个线程时序做穷举验证。

真实隔离 SSH 测试发现 JSch Ed25519 可以生成但不能序列化私钥的问题；已在内存完成序列化后再落盘，必要时回退 RSA 4096，并校验已有公私钥，保留损坏/缺失情况下的原文件，不静默轮换身份。

验证：`ecb8452cb649d2bc4adfb50a387d6cdc70a2cccd` 编译 `run.1t5GTM`；真实双 SSH 环境公钥认证、双向转发、反向登录、冲突清理及保留原工作区连接通过 `run.041gpz`（1 项）；端口和 Tailscale 状态测试 `run.nODBfw`（2 项）；真实本地 Codex 对接回环模型接口及页面渲染 `run.agwBYS`（1 项）。最终生产代码 `154296892ca9b6b7c8c23365acd67dbd7012aaf8` 编译 `run.crMtik`，本地 Codex 完成/提前停止/输入未泄漏为命令/不重写配置及软件渲染复测 `run.xVof4J` 通过（1 项）。截图位于各 run 的 results，均为隔离样例，不是生产连接截图。

本地 Claude Code 2.1.278 的实际 stdin 输入、原生供应商配置读取、流式输出和配置保持不变也已通过（`45ca34dba3642567ed6213ee291af855c09afe02`，编译 `run.HXRhzX`，测试 `run.wNfhJh`，1 项无跳过）。这两次后续提交只新增和修正测试；生产代码仍与 `1542968` 相同。早期 `run.c81Kuz` 暴露私钥序列化问题、`run.zlXEaY` 暴露测试类加载器资源路径问题，均未计为通过。

## Codex 原生插件目录与机器范围（2026-09-22，尚未发布）

插件范围现统一为“本地插件 / 服务器插件”，市场与已安装共用范围。本地控制器全局保留，切换服务器不改变本地列表；服务器目录及安装调用使用当前服务器连接，同一服务器重连复用操作状态。顶部布局已收紧，支持分类双列、搜索、原生目录名称/说明及发布方图标 URL。Codex 与 Claude Code 目录分别展示，不把运行器专属插件声明为通用插件。

Codex 接入真实 `plugin/list` / `plugin/install`，通过本机原生进程或目标机器的 SSH app-server 工作，不复制 Codex 缓存包、账号凭据或专有运行时。安装前复查目录身份和策略；写请求前保存待确认记录，超时不自动重发，安装后回读原生列表。连接入口只使用运行器返回的 HTTPS 授权地址。已安装不代表服务已连接，同一账号的云端服务连接也不保证按机器隔离。尚未支持 Yxi 内完整 OAuth 连接管理、原生插件卸载/升级、所有第三方运行器通用加载或插件市场来源管理。

本机只读实查返回 3 个 Codex 市场、4431 个条目；hk13 的原生目录与本机不同。数量仅为当前环境快照，不表示每个插件都已测试或可移植。公开协议与本机生成 schema 已核对；官方文档：https://learn.chatgpt.com/docs/plugins 。

生产代码 `17777b212a02fb98f1006d04e742f6cf8129385d` 编译通过（`run.CeeEAi`）；真实 Codex 0.153.4、SSH、双 HOME 隔离安装与 Compose 渲染测试通过（`run.wMtHti`，1 项），策略解析和损坏安装记录恢复测试通过（`run.qRbfXJ`，2 项）。人工查看了新布局截图，示例市场仅含一个测试插件。未在本机账号或 hk13 生产 HOME 安装插件；尚未完成 Windows 图形界面完整验收，也未发布。

Claude 市场回归在 `run.xPt7yJ`、`run.fWLQXr` 生成全部截图后停滞，分别核对测试容器完整 ID 与专属标签后停止，未计为通过。仅测试改用软件渲染后，`aac3242b88eac393af57ce5061e6ed0af7190850` 编译通过（`run.0KM5kN`），同一回归测试通过（`run.1l0gpI`，1 项、无跳过），覆盖真实 Claude 目录、本地/服务器切换、清除旧主机项目上下文及浏览无安装副作用。该提交仅改测试渲染设置，生产代码与上述 `17777b2` 相同；这不证明生产 GPU 路径不存在问题。

## 早期插件市场入口（2026-09-22，尚未发布；上方增量取代其范围筛选）

插件页新增并列的“插件市场 / 已安装”入口，市场支持统一搜索和“全部 / 本地 / 服务器”筛选。服务器部分接入现有 Claude Code 市场目录与安装流程，按钮明确显示目标主机；本地目前展示 Android 模拟器内置入口，不代表已支持本地第三方插件安装。搜索覆盖名称、ID、市场与说明，不能识别的来源不显示为可安装。

`60f5323a9ea97f65571f3831d586946aaf1e890a` 编译通过（`run.6UndFv`），`PluginMarketplaceUiTest` 在无外网隔离容器中使用真实 Claude CLI、本地样例市场和真实 SSH，验证目录读取、不同范围的界面渲染、主机上下文切换及浏览不触发安装（`run.x4mlmt`，截图位于其 `results`）。已人工查看市场与已安装视图截图；这不是 Windows 全流程安装验收。另一次 hk13 只读目录实查返回 `ready`、310 个条目、1 个市场，未执行安装；目录数不表示兼容性已全部验证。

## 独立 Agent 配置增量（2026-09-22，尚未发布）

Claude 原生会话及工作台管理的 Codex 对话已接入独立供应商配置。真实 CLI / app-server 隔离测试覆盖不同地址与密钥、模型列表来源、换线、历史保留、重连及手动模型选择持久化；未修改服务器全局运行器配置。测试中复现并修复了 Codex 重连回到全局默认模型的问题。具体提交、证据目录与限制见 [Agent 配置实施记录](agent-configuration-progress.md)。

跨运行器的新对话与摘要交接、原有终端 Codex 会话迁移、Claude 独立配置下的自动回退兼容，以及完整 Windows UI 验收仍未完成。此增量不改变整个 PRD 的范围或完成条件。

测试镜像已压平并验证，后续构建使用固定依赖基线，避免增量镜像层不断累积；操作记录见 [隔离测试说明](../dev/isolated-tests/README.md)。生产镜像、数据卷及发布回滚文件均未在该操作中删除。

## 历史改动（2026-09-13）

- 已实现：服务器配置原子写入、有效备份、损坏恢复、保留损坏文件、读取失败告警；无法恢复时阻止覆盖。
- 已实现：旧配置迁移验证目标后才删除源；新旧有效配置不同则保留双方。
- 已实现：主机表单保存失败保留输入；变更颜色/删除/装公钥后的本地保存错误有反馈。
- 已编写：CI安装后exe冒烟门禁、smoke标记检查；发布脚本拒绝无安装验证的历史构建。Windows CI尚未运行。
- 已编写：登录回调页不提前声称成功，错误内容转义，请求方法/路径校验。完整交互登录仍待测。
- 已改造：Linux桌面E2E仅结束自己启动的PID，使用独立home及显示，不触碰其他会话锁或Java进程。
- 已实现：左上主机选择器、所有主机视图、新对话入口、记住上次选择、可关闭的启动重连及已有会话恢复；仍需双主机实机验收。

## 验证

- 最终本轮 `:desktop:test :desktop:packageUberJarForCurrentOS` 成功，34个测试，0失败/错误/跳过，包含7个新增持久化场景和登录回调安全测试。
- Linux Xvfb独立环境启动及导航冒烟通过；已查看启动截图，主机下拉/新对话可见。环境仍有部分中文缺字，不能作为Windows字体/DPI验收证据。
- 未触发生产发版，未修改在线会员、主机凭据或既有Agent会话。

## 第二轮：文件工作区（2026-09-13）

- 已实现文件右栏、标签、拖宽/收起、窄窗口单栏、Markdown表格和标题、源码/分栏、图片预览、本地另存与复制路径。
- 文件列表/对话Markdown链接接入右栏；文档相对链接基于文档所在目录解析。源码选段可带主机/路径/行号/版本加入当前任务输入区，不自动发送。
- 文档状态按hostId+task+canonicalPath隔离，切换任务保留未保存编辑；对话草稿也移到工作区状态，避免窄窗口打开预览时丢失。
- 远端轮询更新：未编辑时跟随新内容；双方改动时显示冲突，保留用户编辑和远端内容；Ctrl+S通过受保护临时文件、内容哈希检查和原子替换保存，保留原权限。
- 桌面Markdown运行库需要Java21，实际打开页面时在旧Java17抓到UnsupportedClassVersionError；现已统一desktop工具链/CI为21，并在安装冒烟中渲染Markdown。hk13测试使用独立缓存JDK，不改变服务器默认Java。
- 40个桌面测试通过；5个真实Python保存脚本集成测试通过；真实SSH/SFTP界面验收通过：打开Markdown表格、外部改动自动更新、全选替换后Ctrl+S（核对整个远端文件）、未保存内容遇远端修改保留双方、选段引用确实进入对应任务草稿。最后一张截图已人工检查。证据：`/tmp/yxi-doc-e2e.log` 与 `/tmp/yxi-doc-evidence/`；没有发送到测试会话。
- 仍未完整覆盖W08：本机文件提供器、相对图片加载、语法高亮/搜索/跳行、应用退出时未保存编辑保护、跨任务双主机实机矩阵仍需补齐。Windows字体/DPI与安装尚待实机验收；Linux测试环境中文字体不全。
- 文档主机地址/用户名绑定已在第三轮补齐；完整双主机矩阵尚待验收。此开发分支未发布，不能据现有单机验收宣称跨主机场景全部完成。

## 第三轮：视觉导航与 Windows 启动（2026-09-13）

- 新增统一 WorkbenchTabs：工作区和文档视图使用紧凑、中性色的分段导航，去掉满宽蓝色下划线和圆点选中标记；工作区顶部显示项目路径。
- FileDocument记录打开时的地址/端口/用户名。主机ID不变但地址或用户被修改时，旧文档拒绝读取/写入，保留已有内容供另存；别名修改不影响继续使用。已加回归测试。
- 41项桌面测试通过，Linux打包和真实SSH文件预览/修改/冲突/引用E2E通过。截图使用隔离FONTCONFIG_FILE加载Noto CJK，中文已完整显示；字体包仅解包到测试缓存，没有替换服务器默认字体配置。
- 本机Gradle首次下载发行版超时，改用hk13 `-Pyxi.os=win` 生成Windows专用uber jar，再用本机Microsoft JDK21的jpackage生成app-image，带ALL-MODULE-PATH运行时。该构建是开发验证产物，不是已经发布的Setup。
- 本机原生 `YxiWorkbench.exe --smoke` 在独立LOCALAPPDATA/APPDATA下退出0并输出smoke ok（含Markdown渲染），未替换已安装的Yxi。证据在本地 `tmp/windows-smoke-cd05e85a635b4a749e4014f2ac302b8a/`；app-image在 `tmp/workbench-native-build/YxiWorkbench/`。
- Windows完整安装/升级、视觉截图与125%/150%/200%缩放尚未完成。后续继续线路/模型、项目树、队列、浏览器与插件等完整PRD；这轮不视为最终视觉定稿。

## 剩余验收（保持PRD完整范围）

### 第四轮：模型与线路面板（2026-09-13）

- Lines/LinePresets从app移入core，文件内容保持100%一致，Android引用包名不变。桌面新增模型与线路页面，可从工作区右上角或账号菜单进入。
- 支持Claude/Codex线路清单、新增/编辑、模型ID、API key/Auth token分别保存、连通性探测、Claude项目或用户级应用、恢复运行器默认配置。列表保存前检查是否被别人改动，写后回读；展示「配置回读，尚未验证请求」。检测到目标范围有Agent工作时拒绝应用。
- 当前桌面测试共47个：46个普通测试通过，1个隔离SSH测试在普通运行中按设计跳过；该SSH测试已通过独立fixture脚本单独执行。验证真实SFTP目录、项目覆盖/继承、Codex添加/撤销、原权限和原模型保留。
- fixture使用单独的临时localhost SSH服务、临时密钥和独立HOME，未改生产Agent配置。首次fixture位于/tmp被sshd StrictModes拒绝，已移入权限700的专用缓存目录；没有关闭主机指纹验证或StrictModes。
- 桌面打包通过；真实SSH文件工作区到线路页的导航E2E与中文截图通过。截图为只读浏览，未在生产主机点击应用或保存。
- W09仍未完成：真实API密钥/模型请求验证、提供方能力与模型列表、CLI实际生效/重开会话闭环、导入导出/备用策略、双主机矩阵。线路清单删除与原子写入已在第五轮实现；运行器配置文件（特别是Codex多文件变更）的事务/失败恢复仍需继续加固。Android完整构建回归也仍待SDK环境验证。

### 第五轮：线路清单可靠提交（2026-09-13）

- 新增共享RemoteAtomicJson：SFTP只上传到权限600的临时文件；服务端锁、内容摘要、JSON校验、私有备份完成后才原子替换。凭据不进入命令行参数。结果丢失时明确标记未确认，不自动重复提交。
- saveList支持expected清单，保护从界面加载之后发生的变更；旧Android调用仍可使用原签名并获得原子写入，但要完整覆盖旧界面编辑期间的并发保护，仍需在Android调用处传expected。
- 清单结构错误、非对象条目、缺失/重复ID不再被静默略过；读取失败时拒绝覆盖。已只读核验现有hk13清单ID合法。
- 新增「移除记录」及影响说明，只修改线路清单，不自动改变已应用的运行器配置或撤销提供方密钥；失败原因在确认弹窗内可见。
- 隔离SSH测试通过：过期版本提交、损坏JSON、异常清单、受阻目录均返回失败并保留数据；正常更新产生备份，文件权限600；移除记录后运行器配置原样保留。普通桌面测试与打包通过，中文导航/文件/线路页面E2E及截图通过。
- 锁保护协作写入者，对其他编辑器的变更在提交前再次核对；不声称跨任意外部进程的通用文件系统CAS。用户可操作的备份恢复界面尚未实现。

### 第六轮：项目树与任务整理（2026-09-13）

- 左栏按完整工作目录形成可折叠项目层级；同名目录显示完整路径区分。置顶任务单列，支持上下调整、修改显示名称、归档/恢复及全部/待处理/归档筛选；搜索覆盖显示名称、主机、会话名和路径。
- 本地workspace.json通过DurableFile原子保存和备份；损坏文件不以空状态覆盖。运行中/待处理任务不能从菜单归档；已经归档的任务若变为需要用户处理，会重新出现在全部/待处理视图。
- SessionProbe追加tmux服务进程ID/会话ID/创建时间组成的runtimeId，旧格式仍可读取。整理记录按主机端点、用户名及runtimeId隔离；同名会话重新创建不继承旧归档。未知runtimeId时不开放持久化整理操作。
- 对话草稿、文档标签和引用切换到实例标识；检测到同名会话已经换成新实例时暂停旧对话入口，要求重新选择。启动恢复优先匹配保存的实例标识。
- 51项普通测试通过，隔离SSH测试在普通运行按设计跳过。真实SSH界面操作已验证：文件引用正确路由、任务置顶写入本地、归档/恢复及置顶保留；tmux测试会话始终存在。截图复核了项目层级与恢复后的置顶区域。最后的标题一致性修改也编译、测试与打包通过。
- 仍待完成：项目仓库根识别/手动管理、历史已关闭任务浏览、跨主机完整矩阵、运行器新建适配、持久化未发送草稿及其余PRD。runtimeId是当前tmux实例标识，不等于跨服务器迁移或历史恢复的永久任务ID。

### 第七轮：真实网页预览与反馈（2026-09-13）

- 接入JCEF/Chromium，native按平台打包；浏览器实例独立请求上下文。输入远端端口或回环URL会走SSH隧道；普通URL标明本机访问。多个转发租约用系统分配端口，关闭旧租约不会删除新连接的转发。
- 右栏支持地址、前进/后退、刷新/停止、外部打开、元素高亮和反馈。选中文字排除表单敏感值；页面内容作为引用进入正确任务草稿，不自动发送。反馈文字与选取内容在收起/恢复时保留；页面重新加载后旧选择标为之前的参考。
- 真实原生集成测试通过：页面渲染、WebSocket跨SSH更新、断开重连后加载、上下文Cookie隔离、元素选择、密码内容排除与file导航拒绝。
- 最终普通测试发现57项，其中55项通过、2项需fixture的集成测试按设计跳过；两项已分别在隔离环境通过。新增转发测试还确认同一个远端端口可获得不同本机端口，重复关闭一个租约不影响另一个。
- 产品界面E2E通过：真实右栏显示实时页面、选中标题、填写反馈并进入对话、收起再打开后页面和反馈仍在、复制页面文本核对、关闭浏览器后应用正常退出。截图在本地tmp/workbench-evidence/browser-feedback.png与browser-reopened.png；远端证据/tmp/yxi-browser-final-ui。
- 发现并修复JCEF退出锁序死锁：CefApp.dispose持app锁等待client列表，而原生关闭回调反向等app锁。已保留线程转储，调整为client清理完成后再dispose app，测试退出成功。失败的旧测试工作进程在留存转储后才被定点终止。
- W06仍未完成：Windows浏览器原生/DPI/输入法完整验收、浏览器多标签与持久配置、开发服务器启动/构建状态与无HMR刷新降级、区域截图、更多跨域/iframe场景。基本CSS试调已在第八轮实现，字体家族/文字内容等更完整的编辑能力仍待补齐。内核升级策略和外部网页安全审查也属于发版门禁。未发布新版。

### 第八轮：样式试调（2026-09-13）

- 支持字号、文字/背景颜色、内外边距、元素间距和圆角。数值输入、滑杆及颜色预设直接更新当前选中DOM元素；面板标为临时预览，不伪称已修改源码。试调值可随反馈进入任务草稿，样式建议始终以引用数据呈现。
- 操作按选取token与请求ID确认；属性白名单、数值范围和颜色格式双向校验。元素被替换时不按同名selector套用旧操作；页面相关样式被其它代码修改时停止旧试调。
- 撤销按连续滑动手势分组；重置按受影响的CSS长属性恢复值和优先级，保留无关样式。重置后不再把后来的页面改动误还原为旧值。
- 原生浏览器集成测试覆盖实际computed字号、同一手势撤销、混合padding优先级恢复、无关outline保留、重置后的外部修改、同名元素替换拒绝和原有密码保护。
- 59项普通测试通过，2项隔离测试在普通运行中按设计跳过；浏览器隔离测试单独通过。产品E2E将字号28→36，截图确认页面变化，服务端index.html哈希保持不变，草稿包含font-size:36px；收起恢复与退出也通过。
- 视觉收尾：试调与意见输入分模式，保持主要操作可见；补齐Material容器色，移除默认紫色混入。Windows/DPI专项、更多字体/文字编辑及其它PRD仍未完成，未发布。

### 第九轮：退出保护与弹层（2026-09-13）

- 应用退出时汇总文件编辑、未发送草稿、未加入对话的网页反馈；允许继续编辑或明确丢弃退出。文件保存、浏览器准备与样式确认进行中时不允许丢弃。添加反馈到草稿不等于已发送，仍进入退出检查。
- 关闭单个网页预览时，未提交反馈先提示保留；收起会保留页面与编辑状态。
- 实测发现CEF原生画面覆盖Compose弹窗，改为统一WorkbenchDialog和原生浮层租约；对话框及已知菜单出现时临时隐藏浏览器/终端画面，保留其会话。嵌套浮层关闭前不会提前恢复原生层。
- 修复窗口唤醒动作抢走退出弹窗焦点的问题。产品E2E确认网页提示完整可见、Escape取消后内容保留、再次确认丢弃后正常退出。截图位于/tmp/yxi-exit-final-ui。
- 67项普通测试通过，2项隔离测试在普通运行中按设计跳过。此轮未做Windows专门复验。
- 仍待完善：崩溃/重启后的草稿持久恢复、其它编辑表单与写操作的统一登记、更新安装入口保护、网站自身表单的beforeunload流程及Windows原生终端弹层验证。完整PRD未完成，未发布新版。

- M0：Windows安装/升级实际CI；登录完整状态机、端口恢复、凭据加密与测试账号；主机保存实机重启、自动重连、导入导出。
- M1：主机选择器、项目/任务身份与树、置顶/归档、运行器探测与新任务、线路/模型适配和请求验证、Markdown及文件右栏编辑/引用/冲突。
- M2：插件安装和服务器/项目绑定、运行器识别；浏览器内核验证、远端隧道/HMR、实时版本状态、评论/选择回传、样式试调。
- M3：Agent协作、投递去重与预算停止、信箱/工单等安卓生产力缺口、权益服务及幂等验收。
- 跨阶段：Windows中文输入/DPI、两主机无串台、发布与回滚证据、最终按PRD逐项审计。
- 待业务定义：Laize对应运行器；三个月权益档位/算法/资格；官方分支含义。其它工作持续推进，不据此缩小目标。

## 第十轮：新对话运行器选择（2026-09-13）

- 新对话增加 Claude Code / Codex 分段选择，服务器绝对目录检查，启动期间锁定表单与关闭按钮；沿用服务器登录和权限配置。
- 每次新建使用独立请求标识；同一窗口的重复请求复用既有会话并校验实际目录。不同路径同名目录、同路径新任务均不再碰撞。
- 启动前检查 tmux / 可执行文件，直接传递已转义参数；启动后检查存活与工作目录，再刷新取得真实会话。未确认结果时保留窗口并给出核对标识，不构造虚假成功状态。
- 侧边栏使用可改名的“新对话 · 运行器”标题，内部标识用于准确寻址。
- 隔离 SSH 测试使用私有 TMUX_TMPDIR 和模拟运行器，验证双运行器启动、重试仅两个会话、中文/空格/单引号/命令替换字符路径、目录冲突、缺少运行器不创建目录、提前退出。证据：hk13 /tmp/yxi-launch-test.log，测试服务器不连接生产 Agent。
- 常规测试共71项，69通过、2项外部环境测试跳过。真实运行器登录/推理成功、跨重启请求恢复、“Laize”适配和 Windows 界面验收仍待完成；此轮不代表完整新对话需求或完整 PRD 完成。
- 本轮 Linux 桌面打包成功：`:desktop:test :desktop:packageUberJarForCurrentOS`，日志 hk13 `/tmp/yxi-launch-final.log`。此产物不作为 Windows 安装验证。

## 第十一轮：W10 指令队列状态层（2026-09-13）

- 新增 InstructionQueue 持久化账本，指令绑定任务身份、ID、正文、附件引用、版本与状态。ID重复且内容相同返回原记录，内容不一致拒绝。
- 编辑仅改正文并保留附件；撤回/重排仅限本地未投递记录。版本检查防止旧编辑窗口覆盖已开始投递的内容；同一任务按序投递，未知结果阻止后续投递，其他任务独立。
- beginDelivery必须完成原子写入后才允许网络适配器执行IO。投递中退出恢复为Unknown；从旧备份恢复的Local也进入Unknown，避免把旧备份当成可安全重试的证据。没有有效备份的损坏文件拒绝覆盖。
- 运行器确认接收需要非空回执；未确认条目不能再次开始投递。当前仅提供状态层API，尚未实现权威回执适配器与人工核对流程。
- 4项新增测试覆盖附件保留、编辑/投递竞争、跨任务隔离、FIFO及重排、同ID去重、断线未知阻挡后续、空回执拒绝、旧备份重放防护与写入失败不释放网络操作。全量测试75项：73通过、2项外部环境测试跳过；日志hk13 /tmp/yxi-queue-tests.log。
- 未接入ChatPane、AppState或线上发送；现有SessionProbe.send仍把部分无法确认的画面作为成功，下一步必须替换桌面发送适配，不能据此标记远端已接收。待处理指令条的视觉与交互、附件保活、跨重启任务定位及完整W10验收仍未完成。

## 第十二轮：W10 指令条与桌面终端投递接入（2026-09-13）

- 队列账本接入 AppState 和 ChatPane。指令先保存再清空输入与已上传附件；忙碌/离线/存在未确认前序时保留为本地待发送。恢复加载不会自动投递，当前下一轮发送需要点击指令条“发送”。
- 输入框上方同宽轻底色卡片，三条摘要后折叠；独立编辑弹窗可查看全文、保留附件引用、保存、撤回与上移。主草稿不参与编辑。附件等待文案改为“等待上传”，与指令队列分开。
- 桌面投递不再使用旧 SessionProbe.send 的重复补回车/不确定成功路径。新终端适配器校验任务实例、输入可借用状态、投递前画面与附件可读性；只发送一次字面文本和一次回车。tmux执行回执只说明已投递到终端，仍记录为待确认，不冒充运行器接收/引导。
- 未确认条目可查看原因、人工核对后解除阻塞。人工处理写入独立Resolved状态，不记作Accepted，不自动重发。投递中加入退出操作保护；对话记忆状态改按端点/运行实例隔离。
- 暂停桌面旧的三天附件清理，避免删除持久队列引用。其他客户端/外部清理仍可能删除附件，投递会检查可读性；完整附件租约/保活尚未实现。
- 隔离SSH终端接收器测试通过：错误实例、变化画面、缺失附件均在投递前拒绝；真实PTY收到的完整文本与单次回车符合预期。日志hk13 /tmp/yxi-queue-delivery.log。常规测试和Linux桌面打包通过，日志 /tmp/yxi-queue-final.log。
- 原生1400×900显示中的实际应用窗口已截图检查，待处理4条显示前3条并支持展开，未遮住输入区。初始证据 /tmp/yxi-queue-ui-evidence/01-restored.png；编辑/撤回交互验收继续中。
- 仍未完成：自动下一轮调度、运行器权威回执和引导协议、远端队列编辑/撤回、目标状态条、已结束任务的队列找回入口、附件租约、Codex各版本输入识别、发送前画面校验后的TUI状态竞争，以及Windows DPI/IME专项。不得将此轮等同完整W10或完整PRD完成。
- 原生交互最终通过：编辑完整替换后核对instructions.json、主草稿通过剪贴板核对未变、上移核对ID顺序、撤回核对仅一条Cancelled且其余三条保留。截图已人工检查，证据hk13 /tmp/yxi-queue-actions-final/；首次脚本全选后缺少等待导致追加而非替换，已补等待与选区截图后重跑通过。没有向生产Agent发送测试指令。

## 第十三轮：Windows 本机原生验证（2026-09-13）

- 用Windows目标依赖构建最新工作台jar，在本机JDK21 jpackage生成独立YxiWorkbench app-image，包含全部Java模块。测试通过exe启动，配置、缓存与用户目录隔离，未覆盖已安装Yxi或连接配置中的服务器。
- 新增显式 --browser-smoke 测试入口，使用本机HTTP测试页验证JCEF原生库加载、页面实际加载、动态字号40px、Chromium非空PNG及内核退出。测试失败以非零退出码返回，避免jpackage把测试异常变成阻塞的JVM错误框。
- 首轮在浏览器创建/页面加载前发起getText导致超时；改为等待加载回调。随后DOM验证成功，但AWT桌面截图是全黑，不能作为视觉证据；改用Page.captureScreenshot捕获Chromium合成表面，并校验PNG尺寸与非空像素。
- 最终Windows exe检查全部通过：smoke ok、browser native page loaded、browser native pixels ok、browser native render and live style ok、browser native shutdown ok。已人工查看实际PNG，中文标题完整，LIVE_STYLE=40px与画面一致。
- 证据目录（本机）：../tmp/windows-native-20260913-pixels/，含app-image、isolated-profile、smoke-out/err.txt、browser-smoke-out/err.txt、browser-native.png、artifact-sha256.txt。测试jar SHA256：8A38489E98A2F95440F9A315231D868E6194BE45F4718E1D8907E95EDCB57373。远端构建日志：hk13 /tmp/yxi-windows-pixels-build.log。
- 新增dev/windows-native-verify.ps1，可用新输出目录重复生成测试包和验证。CI安装后也要求浏览器内容、像素、退出标记，并保留PNG/日志；脚本和CI PowerShell语法已检查。GitHub CI本轮未执行，不能把本机app-image验证当成Setup安装升级验证。
- 范围限制：本轮浏览器是原生内核测试窗，不等同整个Windows右侧预览交互验收。完整工作台的多主机SSH网页、IME、DPI125/150/200%、多屏、对话框层级、Setup安装升级及所有其它未完成PRD项继续保留；未发布新版本。

## 第十四轮：桌面信箱（2026-09-13）

- 账户页增加始终可见的信箱入口，显示独立的未读/未领取计数。接入列表、分页、正文、发件人、保留时间、附件、阅读确认、领取与删除确认；兑换码显示真正code字段，可选中或复制。余额附件按分转元，会员按天，曦光按张展示。
- 宽屏左右列表/详情，窄窗口切单栏；刷新失败保留列表，刷新后消失的邮件清除旧详情。分页游标兼容数字和字符串，拒绝未前进的游标。编辑/网络操作不会默认为空列表或已领取。
- 新增core MailApi，无账号状态、无自动重试，桌面通过MeAuth认证传输。请求前后检查当前账号，忽略切换账号后的旧回复；HTTP错误、缺失领取/删除确认与未知结果均保留原界面状态。已读和领取分别更新，不能相互代替。
- 有可领取附件时禁止删除；确认弹窗支持保留，接口失败直接显示在弹窗内。领取后依服务回执更新余额/曦光计数，返回账户时刷新资料；仅凭终端、HTTP不明结果或解析失败不能显示领取成功。
- 3项MailApi测试覆盖空值、数字邮件ID/游标、兑换码不能当成可自动领取权益、未知写入结果不重试、删除409、缺失确认字段、请求参数编码及最小请求正文。既有AccountApi HTTP传输复用；此轮不是生产HTTP集成测试。
- 新增MailPaneFixtureTest与dev/desktop-mail-e2e.sh，使用独立profile和Xvfb、完全内存模拟服务。实际Compose界面完成阅读、领取、打开删除确认、取消未发送删除请求、再次确认只删除目标邮件；fake后端断言领取一次、删除一次、另一封仍存在。截图已查看。
- UI+全量测试与Linux打包通过：79项，77通过、2项外部环境测试跳过；证据hk13 /tmp/yxi-mail-final-evidence/（含test-results快照、actions.txt、4张截图）。随后补数字游标兼容并通过3项定向API测试及重新打包，日志 /tmp/yxi-mail-cursor.log。
- 本轮未连接生产信箱、未发放真实权益、未向任何人提工单。工单仍为后续缺口；真实登录/授权、账号切换全过程、Windows信箱DPI/IME/复制、Android完整编译及会员规则仍待验收。MeAuth原有令牌明文存储、刷新持久化和登出竞态需要另行修复，不能因新增账号检查就认为完整登录已完成。

## 第十五轮：工单契约与持久草稿（2026-09-13）

- 已只读核对服务端契约：hk13 /root/src/workspace/yunxi/logto_yxi/design/support-tickets.md。用户可提交、分页读取、标记已读、追问；追问已关闭工单会重开。关闭接口仅属于管理员，不在客户端虚构“关闭”按钮；429改提示在已有工单补充，避免要求用户执行不存在的关闭操作。
- 新增core SupportApi，提交只包含用户显式提供的category/text/version/device，追问只包含text；没有自动采集主机、密钥、对话或设备信息。按Unicode码点校验2000字，超过限制直接拒绝，不截断正文。
- 区分明确拒绝与操作结果不明。断线/超时/5xx/成功回复缺必要字段都不会自动重试；追问回执必须属于目标工单。未知分类、状态、回复角色原样保留，不假定是已回复或官方回复。
- 新增SupportDrafts：按账号及目标工单隔离，保留正文及用户可见元数据，版本检查防旧表单覆盖提交；begin成功持久化后才允许调用网络。Sending重启或旧备份恢复转Unknown，不自动回到可发送；只有显式人工核对可解除。明确拒绝保留可编辑正文，确认成功保留回执记录。
- 7项新增测试覆盖最小请求字段、2000个emoji不截断、超限/控制字符不发请求、429与未知结果区别、无自动重试、参数编码/数字游标、异工单回执拒绝、账号和回复草稿隔离、重启/备份防重放及旧编辑竞争。
- :desktop:test通过：86项，83通过、3项外部环境测试跳过，日志hk13 /tmp/yxi-support-tests.log。测试使用注入模拟传输，无生产工单、客服消息或管理员动作。
- 本轮为状态层和接口层，尚未接入AppState、MeAuth工单路径、页面、自动保存编辑缓冲与退出保护。下一步必须将界面完整接入并验证真实交互；此轮不等同W07工单完成。服务器未提供幂等提交键，不能以本地草稿ID冒充服务端去重。

## 第十六轮：工单页面与工作区接入（2026-09-13）

- 账户页增加工单入口及未读计数；MeAuth认证请求范围增加用户工单路径。工单页支持列表/分页、分类、新建、可选版本和设备说明、官方/用户回复、已读确认、追问关闭工单重开。未知状态和来源不冒充官方或已完成。
- SupportWorkspace连接接口和持久草稿。输入先保留在全局编辑缓冲，再保存到本机；保存失败不丢输入、不发请求，离开页面仍可找回。提交前保存成功才开始网络操作，已开始的提交在切页后仍保存回执；结果不明不会自动重发。
- 支持按当前账号查找草稿和待核对记录。未知结果只允许人工核对后结束草稿或恢复编辑；确认对话展示对应内容，按钮纵排，长摘要限制五行。没有调用管理员关闭接口，也没有新增不存在的服务端幂等参数。
- 未保存编辑缓冲与进行中的提交纳入退出保护。已成功落盘的草稿可随应用正常退出；返回工单页恢复同一记录。登录/登出和令牌层原有竞态仍未因这一接线解决。
- 实际截图发现新建表单高度过大，调整为可换行的紧凑分类行、并排可选字段；1100×800测试窗中提交按钮直接可见。只读状态保留文本选择；窄布局仍待Windows不同缩放验收。
- 新增3项工作区测试：未知提交禁止重发/跨账号提交、离开编辑器后回执仍落盘、保存失败保留内容且不调用接口。新增原生Compose模拟服务测试：打开已关闭工单并标读、追问后服务状态open、新建Bug工单、仅提交显式字段、两条回执重读本机文件均为Confirmed。
- 自动化脚本dev/desktop-support-e2e.sh完成实际输入与点击；全量90项，87通过、3项外部环境测试跳过，Linux打包通过。证据hk13 /tmp/yxi-support-final-evidence/ 与 /tmp/yxi-support-final.log。已人工查看表单截图；最后限定核对摘要五行后，:desktop:compileKotlin通过（/tmp/yxi-support-final-compile.log）。
- 测试全部使用注入模拟服务与独立profile/Xvfb，没有发送真实工单、客服追问或修改生产账号。生产登录后端整链路、Windows工单DPI/IME、长文本频繁保存性能、未知结果核对全过程及其它未完成PRD项目继续保留；此轮不代表完整目标完成，未发布新版。

## 第十七轮：登录会话与回调可靠性（2026-09-13）

- 新增AuthSessionStore，以短临界区维护登录代次；网络操作在锁外进行。退出或新登录后，旧令牌刷新、账号资料和账户页面请求/计数回写均需通过代次校验，不能恢复旧会话。信箱/工单页面及认证闭包按账号+登录代次重建。
- 令牌使用原子替换，写入失败不再只记日志后假定成功。不读取旧备份恢复轮换过的refresh token；新登录从空记录生成凭据，不能继承旧账号refresh token。刷新响应无法保存时阻止继续复用旧令牌并提示重新登录。
- 退出先使内存会话失效，再写退出标记和空登录记录。旧文件清除失败但标记成功时，当前版本重启也拒绝自动恢复；两种写入都失败则明确要求修复存储/清理记录，本进程仍保持退出，不能宣称已经完成持久清除。新登录仅在新凭据写入和退出标记移除成功后发布登录状态。
- 等待浏览器时可取消本次登录，关闭监听并使旧尝试失效；界面用独立请求编号防迟到结果覆盖新一轮busy/error。准备登录与等待授权分别提示。资料加载错误保留可见原因，并提供重试/返回登录。
- 回调明确绑定127.0.0.1，与注册redirect URI一致。等待有效state的授权码或拒绝结果，忽略无关请求/错误state/重复或畸形参数；拒绝同时含code和error的回复。首行和请求头限长，单连接读取和整体等待均有限时，断开的探测连接不会终止登录。
- 新增6项凭据/会话测试与3项真实本机Socket测试：旧刷新不能恢复退出状态、新旧账号隔离、不恢复过期备份、写入失败不能发布登录、退出文件锁定时标记有效、存储全失败提示；favicon/旧state/重复参数后正常回调仍成功、空连接释放、关闭监听终止等待。测试仅使用临时文件、虚构令牌和本机随机端口。
- 更新一条旧测试：原来期望畸形参数被部分丢弃后继续解析code，现在要求整条畸形/重复参数回调拒绝；正常回调和拒绝授权测试保留。全量99项，95通过、4环境测试跳过，Linux打包通过，日志hk13 /tmp/yxi-auth-verified.log。
- 未完成：Windows用户级DPAPI保护和旧明文迁移、刷新请求已送出但响应丢失时的持久恢复协议、真实Logto授权与撤销、Windows取消/端口占用/IME/UI全过程。临时网络失败保持原有不误退出策略，但不能据此宣称轮换令牌的所有未知结果都已处理。没有访问真实账号令牌、登录生产账号或授予权益，完整PRD目标继续进行。

## 第十八轮：Windows 账号凭据保护（2026-09-13）

- Windows的MeAuth选择WindowsCredentialProtector，通过JNA5.17.0调用CryptProtectData/CryptUnprotectData，使用UI_FORBIDDEN，不使用LOCAL_MACHINE，附加Yxi账号凭据用途熵。其他平台保留原有存储方式，不声称具备Windows保护。
- 格式为auth.json.protected中的yxi-dpapi-v1封装。先校验加密/解密往返，原子写入后回读校验，最后才把应用的旧auth.json清空为{}；不生成明文备份。迁移期间旧文件变化则保留；已有加密/明文记录冲突需重新登录，不能静默挑选。
- 加密记录无法解析或解密时不回退旧明文。旧明文清理中断后可再次校验并完成清理。退出无需解密即可清理两份应用记录，同时沿用退出标记；新登录写入与迁移失败都不能发布成功状态。
- 临时明文字节数组用后清零，但不宣称Java字符串或系统历史副本已被物理擦除。当前迁移范围是Store.dir/auth.json；未遍历用户备份或历史漫游目录，SSH密码/私钥等也不在本轮范围。
- 依据：[Microsoft CryptProtectData](https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptprotectdata)；[JNA 5.17.0 Crypt32Util源码](https://raw.githubusercontent.com/java-native-access/jna/5.17.0/contrib/platform/src/com/sun/jna/platform/win32/Crypt32Util.java)。用户级保护依赖Windows账号上下文，不等同跨机器/跨用户可用的通用密钥。
- 全量104项测试，100通过、4项外部环境测试跳过（/tmp/yxi-dpapi-tests.log）。随后补目的文件损坏与清理中断恢复测试，7项CredentialFile定向测试全部通过（/tmp/yxi-dpapi-migration-tests.log）。单元测试使用测试codec，不冒充真实DPAPI。
- Windows本机实际exe验证：虚构旧令牌迁移后明文为空、轮换密文不含新令牌明文、篡改blob被DPAPI拒绝；第二个独立进程读取正确轮换令牌，并完成退出后空状态及再次登录。主界面smoke和Chromium内容/像素/退出也通过。没有读取或修改真实账号凭据。
- 本机证据：../tmp/windows-dpapi-20260913/（两阶段credential日志、synthetic-credentials、app-image、浏览器PNG、哈希文件）。测试jar SHA256：CCB6BA2454F77F7F75F00C6F8C672021852BE136B8921787757F64B6BD805FF3；远端Windows构建日志 /tmp/yxi-dpapi-windows-build.log。
- windows-native-verify.ps1与CI安装后检查增加凭据两进程门禁；本机脚本实际通过，CI PowerShell语法通过，GitHub CI尚未执行。未覆盖安装版、未发版；真实Logto授权/撤销、刷新响应丢失恢复、Windows完整UI与其它PRD需求继续推进。

## 第十九轮：额度未知状态（2026-09-13）

- 找到共享解析与桌面显示的错误组合：quota缺失、remaining:null和unlimited:true均变成quotaRemaining:null，界面据此一律显示不限。现在只接受显式布尔unlimited:true作为不限，并记录剩余额度/总额度是否真实已知。
- 保留原Me字段以兼容调用方，追加quotaUnlimited/quotaRemainingKnown/quotaLimitKnown；非法类型、负数、超Int范围与小数不静默变成零。真实remaining:0仍是耗尽。
- Windows增加quotaSummary：未知显示“额度暂不可用”，有限但总数未知时不补“共0次”，耗尽明确显示，恢复信息保留完整服务端时间。安卓资料页与会员页同步判断显式不限及未知状态，避免两端继续把未知当权益。
- 4项新增测试覆盖缺失对象/JSON null、显式不限与字符串伪布尔、真实零额度/未知总数、负数/小数/错误字符串/整数溢出。:desktop:test全量110项，106通过、4环境测试跳过；日志hk13 /tmp/yxi-quota-tests.log。
- Android构建入口检查`:app:compileDebugKotlin --dry-run`在任务依赖阶段失败：SDK location not found（/tmp/yxi-quota-android-check.log）。安卓两处显示调整尚未编译验证；没有生成或发布新APK，也未改变真实用户权益。
- 本轮只修复资料额度。钱包、其它计数与时间的统一呈现，以及Windows完整UI/服务集成验收仍需继续核对，不能把这项修复当作全部账户数据已完成验收。

## 第二十轮：网页截图复制（2026-09-13）

- 右侧预览工具栏增加“复制截图”，导航按钮改为紧凑图标，使常见宽度下的操作可见。复制后提示在对话输入框Ctrl+V添加；不自动上传或发送，也不向剪贴板附加隐含URL/文本数据。
- 提取BrowserCapture复用Chromium Page.captureScreenshot，捕获当前可见网页区域；BrowserNativeSmoke也使用同一路径。使用内存ImageInputStream，先检查格式与尺寸再解码，限制响应大小和总像素；合法纯色网页不被误当成无效截图。
- 截图串行执行，重复操作可重建已关闭DevTools客户端。BrowserPreview跟踪截图状态、页面版本、真实URL和实例；变化后拒绝结果。切任务/移除面板取消旧作业，截图忙碌状态纳入预览关闭和主窗口退出保护。
- 3项新单元测试验证图片剪贴板没有文字负载、PNG像素正确、畸形/超大尺寸被拒绝、兼容两种DevTools结果封装。全量113项，109通过、4项外部环境测试跳过；Linux打包通过（/tmp/yxi-capture-final.log）。
- 原生BrowserIntegrationTest在独立SSH/显示环境中连续截图两次，并验证图片剪贴板格式；原有WebSocket、选择、试调等测试继续通过（/tmp/yxi-capture-native.log）。
- 真实产品按钮在隔离Xvfb中点击，通过xclip读取系统image/png，PIL检查尺寸与非空像素，已查看输出。证据hk13 /tmp/yxi-copy-screenshot-ui/；本机tmp/workbench-evidence/copied-page.png与screenshot-toolbar.png。测试页为合成内容，未读取用户Windows剪贴板或上传图片。
- dev/desktop-document-e2e.sh增加YXI_TEST_SCREENSHOT模式，并更新紧凑导航后的选择按钮坐标。首轮采用手动点击再继续验证，脚本已固化同一坐标；窗口数据与图片均来自真实渲染，不是UI设计稿。
- 本轮是可见区域复制，未实现区域框选、截图与URL/元素/评论的一体附件、Windows系统剪贴板到附件的完整E2E，以及所有导航竞争时序。现有Ctrl+V附件入口可由用户主动使用，不能据此宣称完整W06截图反馈闭环已验收。完整PRD继续推进，未发版。

## 第二十一轮：项目预览地址持久化（2026-09-13）

- 新增ProjectPreviews，显式保存起始地址，落盘到project-previews.json；沿用原子写入/备份恢复，损坏时阻止覆盖，旧编辑值不能覆盖新设置。支持端口简写和HTTP(S)地址，拒绝文件协议及URL内用户名密码。
- 通过现有projectKey绑定服务器ID、端点、SSH用户和规范化项目路径；同服务器同项目的新任务复用地址，重命名显示名不丢失配置，不同目录/端口/用户不串用。
- 浏览器标题栏增加“项目地址”设置，保存并打开、移除记录、错误保留输入。只有用户显式保存时更新记录，重定向和正常导航不会写成项目起始页。
- 再次打开预览时自动访问已保存地址，远端端口等待SSH连接可用；不覆盖用户正在输入的地址。任务目录变化时阻止旧设置提交。移除/重新打开后以浏览器映射中的当前实例为准，避免记住已关闭对象。
- 3项新测试覆盖跨端点/项目恢复、旧编辑冲突、移除只影响目标、非法URL、存储损坏不覆盖。全量116项，112通过、4环境测试跳过，Linux打包通过（/tmp/yxi-project-address-build.log）。
- 原生E2E实际打开设置、填写测试服务端口、校验本地记录为远端逻辑URL；退出应用并等待进程结束，再以同一隔离profile启动。点击网页预览后不输入地址，复制页面文字核对Before the edit成功；已查看重启后截图。证据hk13 /tmp/yxi-project-address-final/ 与 /tmp/yxi-project-address-final.log；本机tmp/workbench-evidence/project-preview-restored.png。
- dev/desktop-document-e2e.sh加入YXI_TEST_PROJECT_PREVIEW模式，测试profile明确关闭托盘隐藏；已固化UI动作，并补提交前读取输入值的断言以避免错误端口访问。初次定位弹窗等待超时后，自动化重跑完整通过。
- 当前只保存预览地址，开发服务需已运行。启动命令、工作目录启动器、健康进程复用、构建日志/版本状态与自动刷新控制仍未实现；不能视为W06-A全部完成。Windows完整项目恢复与其它PRD验收继续推进，未发布。

## 第二十二轮：开发服务管理底层（2026-09-13）

- 新增PreviewServicePlan和打包资源preview-service.py，通过现有SSH通道执行。使用服务端用户目录下的独立tmux socket，检查目录归属/权限/标记，拒绝socket符号链接，不使用普通Agent的tmux通信文件。
- 会话创建时原子设置项目、配置指纹和starting环境标记；设置退出后保留终端，再运行用户前台命令，收到执行回执后标记launched。状态区分starting/running/exited，失败保留退出码及最近200行、至多32000字符日志。
- 相同项目与配置的现有会话返回复用，不再次执行命令；配置改变返回冲突。停止在同一tmux连接里校验项目、配置与会话实例，旧实例请求和无归属会话不能停止当前会话。
- 启动前探测IPv4/IPv6回环端口，有现有服务则返回port-busy，不抢占端口或杀进程。此探测不是HTTP健康检查，也不能证明最终监听端口属于启动命令；running只表示托管会话正在运行。
- 首轮定位到tmux选项命令不接受=name目标，改为使用明确的会话/窗口/面板ID；随后将归属标记前移到创建时，避免初始化中断后丢失归属。当前服务器实测tmux3.4。
- 隔离SSH测试通过：一次启动、重复复用、日志DEV_READY、配置冲突、停止后新实例、旧停止请求被拒、exit7和错误日志保留、占用端口拒绝、无归属会话不被接管/停止、普通测试会话列表不变。所有进程/端口均为独立fixture，清理仅针对该fixture的socket。
- 新增3项计划/回执校验测试；全量119项，115通过、4环境测试跳过，Linux打包通过。最终证据hk13 /tmp/yxi-preview-service-verified.log、/tmp/yxi-preview-service-package.log；没有向生产Agent发送指令。
- 尚未接入项目设置中的命令保存、启动/停止按钮、状态轮询和日志界面。仅支持前台命令，会话停止不保证任意守护化后代全部回收；HTTP就绪与监听进程归属、初始化中断恢复、跨版本兼容及完整W06-A仍待完成，不能据本轮声称项目预览启动闭环已交付。

## 第二十三轮：开发服务界面接入（2026-09-13）

- 右侧新增开发服务配置入口，保存工作目录、前台启动命令和监听端口；ProjectServices按项目键保存，Windows复用CredentialFile用户级保护。保存不执行命令，状态栏再提供启动操作。
- 接入只读轮询、启动、按当前显示实例停止、退出会话清理、日志和预览。预览优先使用显式项目地址，无地址时使用已配置服务端口。running明确显示为进程运行中，不冒充HTTP已就绪。
- 查询/停止协议改为只传项目标识和必要实例/配置标识，避免每次轮询携带完整命令。配置缺失时也可检查项目已有会话。配置变更显示与运行会话的差异。
- 控制器串行处理请求，变更操作与读取分开计数；开始/停止请求可等待正在进行的检查，不会因后台轮询短暂禁用而丢失点击，同时拒绝重复变更。操作失败原因独立保留，成功的状态轮询不清掉它。
- 未保存配置输入保存在工作区并纳入退出提示；进行中的服务变更纳入退出保护。项目变化时禁止把旧表单保存到新项目。日志使用等宽字体并去掉显示末尾空行，更多菜单沿用原生浮层保护。加载失败不再残留“准备浏览器”状态。
- 原生界面首轮完成配置/启动/HTTP页面/日志/停止；自动化复跑暴露轮询占用导致启动点击未生效，已修正并增加并发检查场景。最终脚本dev/desktop-service-e2e.sh实际点击和输入，核对UI_DEV_SERVICE_READY页面文字、持久配置、停止后会话不存在且端口已关闭，完成正常浏览器退出。
- 测试使用独立SSH服务、用户目录、tmux socket和Xvfb，运行自建HTTP测试服务，没有操作生产开发服务。证据hk13 /tmp/yxi-service-ui-verified/（图片/测试日志），全量日志 /tmp/yxi-service-controls-final.log；本机tmp/workbench-evidence/service-final-running.png和service-final-logs.png已查看。
- 新增配置重读/旧编辑拒绝、编辑内容保留测试；原生测试还核对操作错误不会被查询覆盖，以及查询进行时启动仍完成。全量122项，117通过、5环境测试跳过，Linux打包通过。
- 仍未完成自动HTTP就绪与监听进程归属验证、启动后自动打开策略、构建中/失败与代码版本关联、无HMR自动刷新、守护化进程族完整回收、服务配置恢复/移除流程和Windows专项。当前通过前台HTTP服务验证基本闭环，不能据此认定完整W06-A或PRD已完成，未发布新版。

## 第二十四轮：服务就绪与自动打开（2026-09-13）

- 受管服务创建时保存监听端口元数据。运行状态查询在Linux读取/proc进程关系、起始时间、监听socket和FD归属；只有候选监听都能关联到会话进程树时才发HTTP请求，请求后再次核对进程及socket标识。检测不把其他进程的端口当成成功。
- 支持IPv4、IPv6回环和IPv4映射IPv6记录；首次测试发现JVM监听表现为映射地址，已修正地址字节序和匹配逻辑。HTTP只检查受核对回环地址的根路径，限制超时、不跟随重定向、读取少量响应；返回检查时间、入口及状态码。
- UI区分等待监听、归属待确认、HTTP响应待核对和HTTP可访问；明确这是根路径检查，不等于代码版本或构建已同步。没有/proc或旧会话缺端口元数据时保持未确认。
- 用户点击启动后，只有同一会话实例、同一配置就绪才自动打开一次。只自动打开匹配的HTTP回环地址，localhost落实为已验证的具体IP；公网/反向代理、不同端口/协议仍用手动预览。保留原始编码路径、查询及片段，不把%2F改成路径分隔符。
- 停止、配置/实例变化或用户编辑地址会取消待打开意图；浏览器初始化未完成则等待。旧URL的加载错误不再覆盖另一个URL的状态。
- 原生隔离测试确认：仅有运行进程但未监听时不就绪；无关HTTP监听出现时不发送任何探测请求；IPv6服务的进程归属与HTTP200通过。原生报告已保存在fixture目录，证据 /tmp/yxi-ready-native-final.log 及 fixture.fQ9cAp/test-results。
- GUI测试移除了手动预览点击，仍验证UI_DEV_SERVICE_READY载入、就绪被观察、停止后端口关闭。截图已查看：本机tmp/workbench-evidence/service-ready-automatic.png；远端 /tmp/yxi-ready-final-ui/。新增3项自动地址选择测试；全量125项，120通过、5环境测试跳过，Linux打包通过（/tmp/yxi-ready-package.log）。
- 仍未覆盖自定义就绪路径、TLS/认证页面、代理或容器不在子进程树的监听、所有端口重绑定时序和Windows客户端专项。就绪检查是时间点证据，不代表未来连接永不变化；构建版本关联与无HMR自动刷新仍未实现。没有检查或改动生产开发服务，完整PRD继续推进。

## 第二十五轮：可配置就绪路径（2026-09-13）

- 服务配置增加就绪路径（GET），可使用/health等专用入口。查询和日志刷新都使用当前路径，服务端回传实际检测路径；界面及自动打开要求检测结果与当前路径一致，不能沿用旧结果。
- 检测路径不进入启动命令指纹，单独修改不重启服务。旧v1配置默认/，保存升级v2，避免旧客户端悄悄丢弃新增字段；启动命令和目录保持。
- 路径限定同源的/开头目标，拒绝绝对外部URL、//、控制字符和片段；URL编码与查询保留，Unicode转ASCII编码，编码后也限长。状态说明只显示查询之前的路径。
- 隔离SSH测试使用真实HTTP进程：根路径503、专用路径200，切换检测路径后正确变为可访问，再检查根路径仍是503，运行实例不变。证据hk13 /tmp/yxi-health-path-native.log。
- 实际GUI填写并保存/health/ready，模拟服务只在该路径通过检测，自动打开另一个/app预览入口；脚本没有手动预览点击，仍核对UI_DEV_SERVICE_READY及停止。截图已查看：本机tmp/workbench-evidence/custom-readiness.png；远端 /tmp/yxi-health-path-ui/。
- 追加路径校验/编码及v1升级测试；全量127项，122通过、5环境测试跳过，Linux打包通过，日志 /tmp/yxi-health-path-final.log。
- 仍未支持HTTP认证头、TLS探测、自定义成功内容判据、代码版本/构建状态关联、非HMR自动刷新及Windows专项；就绪路径返回200不等同全部页面或代码已完成同步。完整PRD继续推进，未发版。

## 第二十六轮：字体类型试调（2026-09-13）

- font-family加入样式白名单与选择时计算样式。提供系统字体、无衬线、衬线、等宽四种类型；实际字形由设备字体决定。原自定义字体栈继续显示，不自动选成其它类型。
- 字体使用下拉选择，禁用像素滑杆；未选择有效字体时不可试调。菜单仍使用原生浮层保护，切属性关闭旧字体菜单。只接受列出的字体值，不允许拼接额外CSS或外部字体URL。
- 沿用原声明/优先级记录、撤销、重置及加入对话草稿。样式仍明确是临时效果，不代表源文件已保存。
- 原生BrowserIntegrationTest确认计算字体切为serif，撤销恢复sans-serif及important。真实产品UI通过键盘选字体并试调，已查看衬线标题截图；加入对话后复制草稿核对font-family:serif，未发送给Agent。
- 全量128项，123通过、5环境测试跳过，Linux打包通过（/tmp/yxi-font-build.log）。原生测试 /tmp/yxi-font-native.log；UI /tmp/yxi-font-ui-keyboard/；本机tmp/workbench-evidence/font-trial-verified.png及font-feedback-verified.png。
- 初次菜单定位等待超时后改为完整键盘自动化，流程通过并保留脚本YXI_TEST_FONT入口。未实现自定义字体名/文件、文字内容试调、所有设备字体可用性与WindowsDPI专项；完整PRD继续推进，未发版。

## 第二十七轮：网页文字试调（2026-09-13）

- 样式面板加入“文字”，支持最多1000字符、保留空白与空字符串；Enter换行，Ctrl+Enter或试调按钮应用。纯文本以原Text节点data修改，拒绝混合子元素、输入框、隐藏/私有节点，不解析HTML，不替换元素或事件处理器。
- 复用撤销/重置历史，核对原节点身份及当前值；外部代码修改文字或替换节点后拒绝覆盖。选择不支持文字的元素时禁用文字入口，CSS试调仍可用。
- 反馈用独立纯文本段落记录JSON引号包围的原文/新文，空字符串和字面标记可辨；不会把text-content当作CSS输出。只加入对话草稿，不自动发送或修改源码。
- 原生BrowserIntegrationTest验证字面<b>标签仍为文本、原点击事件保留、撤销恢复原文、外部更新后重置拒绝、嵌套span不被压平及密码字段不进入文字元数据。隔离日志 /tmp/yxi-text-native.log，fixture.VP41iV。
- 实际UI通过文字菜单、Ctrl+Enter把标题改为A clearer heading，并加入草稿核对原文/新文；脚本核对测试HTML文件哈希不变。已查看本机tmp/workbench-evidence/text-trial-verified.png与text-feedback-verified.png，远端证据 /tmp/yxi-text-ui/。
- 全量129项，124通过、5环境测试跳过，Linux打包通过（/tmp/yxi-text-build.log）。新增规范化/反馈测试覆盖空白、空字符串、px结尾文字、长度与空字节限制。
- 当前支持单个纯文本节点，尚未覆盖富文本编辑、源码落实后的版本反馈闭环、Windows文字输入/DPI及完整PRD验收。未发布新版。

## 第二十八轮：主机插件状态面板（2026-09-13）

- 配置页新增主机插件入口与刷新，以卡片展示Claude插件安装记录的版本、用户/项目范围、项目路径、安装目录及用户默认启停状态；切换连接会清空旧快照，未读到回执显示失败。
- 新增只读Python采集脚本，仅返回明确允许的安装字段，不返回settings中的环境变量或其它配置值。安装目录存在仅显示待运行器验证；用户默认状态不冒充项目最终生效状态。损坏记录/格式异常以警告显示，不等同未安装。
- hk13实际Claude 2.1.267的plugin help/list/install help已核对，当前plugin list --json返回空列表；未安装、卸载或启停生产插件。
- 隔离临时目录测试覆盖A/B记录隔离、项目范围、显式停用与未知状态区别、损坏配置与安装记录、秘密不返回。桌面解析测试覆盖空回执非空列表与三态启用状态。
- 本轮建立状态展示基础；完整安装/配置/测试/启停/升级/卸载、运行器验证、项目覆盖、Codex适配、恢复版本及真实UI/Windows视觉验收仍待完成，不能据此认定W03已完成。
- 验证结果：131项桌面测试中126通过、5环境测试跳过，Linux打包成功；日志hk13 /tmp/yxi-plugin-inventory-build.log。界面尚未进行实际交互与视觉验收。

## 第二十九轮：插件运行器列表核对（2026-09-13）

- 采集安装记录时增加固定argv的claude plugin list --json只读查询，工作目录固定当前SSH用户家目录，12秒超时，返回内容限制4MiB。CLI错误原文不传回客户端，防止诊断中夹带配置值。
- 按ID、作用范围、安装路径、项目路径匹配，不同项目记录不互相确认；区分查询未知、列表未找到、已列出、运行器报告错误，并保留CLI明确启用/停用/未知。已列出不表示当前会话已加载或插件功能测试通过。
- 使用独立HOME与CLAUDE_CONFIG_DIR构造无执行内容的临时安装记录，真实Claude 2.1.267 list返回enabled=true但errors包含缺少marketplace；采集器正确保留reported-error。未改变生产插件配置。固化dev/test-plugin-cli.py；普通Python测试补齐项目匹配与错误内容不返回断言。
- UI接入上述状态文字，实际交互/视觉验收仍待完成；后续需安装与变更账本、验证和恢复流程、项目最终配置、Codex插件适配。
- 验证：131项桌面测试中126通过、5环境测试跳过，Linux打包通过；日志hk13 /tmp/yxi-plugin-runner-build.log。

## 第三十轮：插件面板布局与原生界面验证（2026-09-13）

- 插件页采用固定标题/搜索区和LazyColumn滚动卡片，名称与市场来源分层；圆角卡片、字母标记、版本/范围和异常色使记录更容易比较。项目与安装路径允许选取复制，长内容自动换行。
- 搜索同时匹配插件ID、来源、项目及路径，显示总记录数与需检查数量，无匹配结果显示空状态。状态采集与展示组件分离，便于用隔离快照验证实际布局。
- 新增PluginUiFixtureTest及dev/desktop-plugin-e2e.sh，在隔离Xvfb真实Compose窗口测试820/460两种宽度；只使用虚构插件和独立profile，不连接或修改生产插件。两个尺寸的刷新回调点击断言通过。
- 全量132项测试中126通过、6环境测试跳过，Linux打包成功（/tmp/yxi-plugin-ui-build.log）；另行运行插件UI测试通过。宽窄屏截图均已查看，长名称/来源/路径未截断，状态清楚，证据本机tmp/workbench-evidence/plugins-820.png与plugins-460.png。
- 这轮仅验证插件展示组件，不代替完整配置页导航/多主机往返、WindowsDPI/键盘/深色验收。安装、启停、升级和恢复等W03流程继续推进，未发布新版。
- 追加原生搜索检查：两种宽度实际输入zzzz后，截图确认无匹配文案且旧卡片消失；刷新点击仍通过。证据/tmp/yxi-plugin-ui-search/及本机plugins-search-460.png、plugins-search-820.png。

## 第三十一轮：插件启停操作底层（2026-09-13）

- 新增plugin-operation.py与PluginOperationPlan，prepare返回配置路径/当前显式状态/配置及安装记录指纹；set使用明确user/project/local范围及固定argv的Claude enable/disable；status可按原操作ID查询。
- 服务端私有plugin-operations目录以文件锁串行本工具的变更。执行前检查指纹、原子保存配置恢复副本和started意图，再调用CLI；结束读取配置核对目标布尔值，成功只表示configured，不表示当前会话已加载。
- 同一操作ID不重复执行，不同目标复用ID被拒绝；超时/异常/CLI回执与配置不符保留unknown，重启遗留started查询也按unknown处理。CLI输出不返回客户端，恢复副本留在服务器权限600的私有目录；新增备份不在项目仓库内。
- 拒绝不支持的配置主目录、明显符号链接、非规范化项目目录和不唯一安装记录；读取配置最多4MiB。文件锁仅协调Yxi自身，不能阻止外部CLI/编辑器在检查后改文件，未宣称跨进程原子事务。
- dev/test-plugin-operation.py在独立HOME/CLAUDE_CONFIG_DIR中实际验证用户停用/启用、project/local停用，原env字段保留；模拟验证一次执行、同ID回执、目标冲突、过期指纹、超时不重发、恢复副本内容及权限。未修改生产插件。
- 桌面测试与Linux打包成功（/tmp/yxi-plugin-operation-build.log）。尚未接入客户端持久操作账本/按钮/退出保护，也未实现恢复按钮、运行器加载验证、安装升级卸载流程及Windows专项；完整W03继续推进。

## 第三十二轮：插件启停界面与客户端操作记录（2026-09-13）

- 插件卡片接入启用/停用，prepare后弹窗显示目标主机、插件、作用范围和配置路径，确认时复核当前连接。作用范围沿用安装记录，不能把项目安装无意改成用户默认。
- 新增PluginOperations客户端持久账本，先保存操作ID/请求再发送；独立协程接收回执，离开页面不取消提交。按主机端点键隔离，单主机有未确认操作时禁用新提交，保留原ID供查询。
- 界面显示操作中、设置已更新需新会话验证、拒绝或待确认；成功触发安装列表刷新。运行中操作纳入退出保护，不因切换页面丢失。未知结果不自动重发。
- 启动时sending转unknown。主文件损坏或缺失且存在旧备份时，恢复前写持久needs-review标记，阻止重启后以旧账本再次提交。当前需要人工核对服务器记录，尚无解除该标记的产品流程。
- 新增测试验证重启保留ID、A/B隔离、未知阻止新发送、查询完成解除阻止、损坏恢复以及再重启仍保留待核对状态。首次编译缺少Swing协程扩展导入，修正后测试/打包通过。
- 本轮是界面与持久化接线，启停弹窗到隔离SSH的完整原生点击流程、恢复按钮、安装升级卸载和Windows专项仍待验证，不能视为W03全功能完成。未修改生产插件。
- 最终验证：133项中127通过、6环境测试跳过，Linux打包成功；日志hk13 /tmp/yxi-plugin-toggle-verified.log。

## 第三十三轮：插件停用完整原生流程（2026-09-13）

- 新增PluginToggleFixtureTest和独立Xvfb脚本，经现有隔离SSH fixture打开真实PluginInventoryPane，使用测试HOME内插件记录与真实Claude CLI；未连接生产Agent或修改生产插件。
- 实际点击停用打开目标确认，Escape取消后断言服务器配置逐字不变且客户端无操作记录；再次确认后等待回执落盘，断言configured、目标enabledPlugins=false、其他permissions字段保留。
- 截图确认完成后安装列表自动刷新，运行器与用户默认均显示停用；确认弹窗改用中文作用范围。已查看本机tmp/workbench-evidence/plugin-toggle-disabled.png及确认界面；最终远端fixture.aGEx18，日志/tmp/yxi-plugin-toggle-ui-final/test.log。
- 首轮弹窗关闭后点击太快，补焦点等待；第二轮测试错误地把Compose内部弹窗当作AWT独立窗口，改按实际绘制坐标点击后完整通过。这两次是测试定位问题，没有重复生产变更。
- 本轮验证用户级取消/停用闭环；启用、project/local范围已在前轮CLI测试，尚未完成它们的原生点击、多主机切换、断线后查询/重启恢复界面及Windows专项。恢复、安装、升级、卸载和完整W03仍需推进。
- 最终全量134项中127通过、7环境测试跳过，Linux打包通过（/tmp/yxi-plugin-toggle-e2e-package.log）。补截图渲染等待后原生测试再次通过，fixture.MAMUmY与/tmp/yxi-plugin-toggle-ui-screens/test.log；已核对最终中文确认弹窗截图。

## 第三十四轮：恢复插件操作前设置（2026-09-13）

- 服务端增加restore动作，按原操作ID定位服务器保存的目标与副本，不接受客户端指定任意恢复文件。仅允许恢复已确认configured操作，核对当前配置/安装记录指纹与原操作after一致，再校验副本与before指纹。
- 恢复前为本次恢复单独保存当前内容及started账本，重复相同ID返回已记录结果；原来不存在的配置恢复为不存在。恢复后回读核对，状态为restored；不能把恢复配置等同正在运行的会话已重新加载。
- 客户端增加恢复设置按钮与目标确认说明，连接可用且无未确认操作时可用；恢复结果落盘并刷新列表。已确认configured/restored的操作遇到查询网络失败时保留既有确认状态，避免误降为未知并阻塞新操作。
- 隔离Python/真实CLI测试追加恢复原内容、恢复幂等与查询、后续编辑冲突保留、副本被篡改拒绝、原文件不存在时恢复删除。首轮篡改测试因重新序列化改变字节指纹而先触发changed，改保存精确after字节后验证副本校验分支通过。
- 当前文件锁只协调Yxi，恢复前再次检查仍不提供对不使用同一锁的外部编辑器的原子CAS保证；运行器实际加载、恢复弹窗完整原生点击和Windows专项待验证。本轮未实现插件包版本回滚，安装升级卸载仍在完整W03范围内。
- 最终134项中127通过、7环境测试跳过，Linux打包成功，日志hk13 /tmp/yxi-plugin-restore-final.log。

## 第三十五轮：恢复设置的原生界面验收（2026-09-13）

- 在真实Compose→隔离SSH流程中追加停用后恢复。先取消恢复，确认仍只有原停用记录且配置保持停用；再次打开并确认恢复，核对服务器配置与最初文本逐字一致。
- 重新实例化客户端PluginOperations，核对恰好两条持久记录且末条restored；结果截图显示已恢复，列表重新读取用户默认启用，并保留模拟插件原有市场来源错误，不将配置恢复显示成运行器可用。
- 首轮沿用较窄停用弹窗坐标点到了恢复弹窗取消按钮，现明确将该路径作为取消恢复验收，并按实际较宽弹窗定位确认按钮。最终完整流程通过。
- 证据hk13 /tmp/yxi-plugin-restore-ui-final/test.log、fixture.lNMman；已查看本机tmp/workbench-evidence/plugin-restored-verified.png，恢复弹窗证据plugin-restore-confirm-verified.png。本轮仅扩展原生测试和进度文档，未变更生产代码或重复无关打包。
- 尚未完成Windows恢复、多主机切换/断线查询的原生验收、插件包安装升级卸载与完整W03；仍未发布新版。

## 第三十六轮：服务器插件目录浏览（2026-09-13）

- 核对当前Claude CLI的plugin list --available --json实际结构为installed/available；新增独立只读采集脚本，当前hk13成功读取295条目录记录，未执行安装或更新市场命令。
- 配置页增加浏览插件目录，按当前连接读取、可刷新和搜索名称/市场/简介；卡片展示名称、市场、简介、版本与来源。版本缺失显示目录未提供，不把目录条目显示为已安装或兼容。
- 目录读取固定argv，15秒超时、8MiB响应上限；只返回白名单字段，HTTP来源链接剥离用户名密码、查询参数与片段，不返回headers和CLI错误原文；完整条目指纹用于后续安装前版本核对。
- 新增Python测试覆盖来源脱敏、版本未知、条目变更指纹与格式异常；在真实服务器目录上采集结果ready/295。目录描述按普通文字展示，不执行其内容。
- 当前仅有目录浏览，尚未接入安装目标/权限/兼容性详情、安装操作账本、安装后加载验证以及目录页原生视觉/Windows测试；完整W03仍待推进。
- 桌面测试与Linux打包通过，日志hk13 /tmp/yxi-plugin-catalog-build.log。

## 第三十七轮：插件安装执行底层（2026-09-13）

- 操作协议新增prepare-install/install，核对目录条目指纹与目标配置/安装记录指纹；同作用范围已有安装先返回already-installed，避免把升级混入新安装。
- 安装前保存配置、安装登记原文副本和started操作记录，使用明确scope的固定CLI argv。执行后再次查询Claude列表，目标ID/范围/项目唯一匹配且无errors、安装目录存在才返回installed；该状态不代表登录或插件功能测试通过。
- 复用操作ID幂等与status查询；超时保留unknown不自动重发。CLI不自动接受市场声明的shell命令或headersHelper，后续仍需明确的权限确认流程。
- 新增dev/test-plugin-install.py：在临时HOME与本地测试市场实际注册并安装一个无执行内容的测试插件，验证条目变化拒绝、识别结果、重复请求/查询、已安装拒绝；模拟超时核对同ID仅执行一次。原启停/恢复脚本同时通过，未安装生产插件。
- 实测当前CLI会从available删除已安装条目；据此将已安装检查前移。向其他范围再次安装同一已装插件仍需目录/来源恢复适配，不能声称全部范围复用已完成。
- 本轮尚未把目录页安装选择/权限详情/确认与客户端账本接上，也未实现安装失败清理、包版本回滚及Windows专项。完整W03继续推进。
- 桌面测试与Linux打包通过，日志hk13 /tmp/yxi-plugin-install-build.log。

## 第三十八轮：目录页安装确认与结果跟踪（2026-09-13）

- 目录卡片接入安装到此主机，支持用户级、项目级、本地项目级范围；项目范围输入服务器绝对路径。第一步只prepare核对，第二步显示明确主机、市场、配置路径后才提交。
- 确认时复核当前连接，提交沿用客户端先落盘/独立协程/退出保护与服务器操作ID；installed作为独立终态持久保存，查询网络失败不降级已确认结果，成功触发目录刷新。
- 目录页显示操作结果与查询入口；安装记录页也能识别installed结果。安装完成仅声明运行器已识别，提醒新会话测试，不等同登录/功能正常。显式命令来源仍需单独授权，当前按钮不可用。
- 目录缺少完整权限、依赖和兼容性声明时如实显示未知，本轮没有完成manifest级权限解析。安装后失败清理、跨范围复用、升级卸载与包回滚仍未完成。
- 新增安装结果持久化与查询失败保留终态测试；安装确认弹窗/实际CLI的完整原生点击、窄屏及Windows专项尚待验证，未安装生产插件。
- 最终135项中128通过、7环境测试跳过，Linux打包成功，日志hk13 /tmp/yxi-plugin-install-ui-build.log。

## 第三十九轮：目录安装的原生流程与空状态（2026-09-13）

- 新增PluginInstallFixtureTest，真实目录页通过隔离SSH读取临时本地市场，打开范围选择、核对目标，再取消；核对无客户端操作且插件仍可安装。重新确认后核对installed落盘、CLI识别、安装登记唯一、重读本地账本仍为installed。
- 已实际查看目录、范围选择与目标确认截图，路径完整；初轮人工驱动真实点击通过，fixture.LWx5GM及/tmp/yxi-plugin-install-ui/test.log。将坐标步骤固化到desktop-plugin-install-e2e.sh，以ready/cancel-verified同步，测试失败清理仅针对本轮进程组。
- 安装成功后目录移除已装条目，原空白提示改为圆角空状态卡片，配置页提供查看主机插件入口；搜索无结果和没有可安装条目分别说明。
- 本轮只覆盖用户级本地市场安装，远程Git/命令来源、项目范围原生点击、多主机/断线恢复和Windows专项仍待验证。未安装生产插件，完整W03继续推进。
- 自动化首次复跑在截图完成、窗口关闭后，Gradle测试worker卡在JVM Shutdown.halt0；jhsdb诊断未发现Java锁死，根因未确定。保留/tmp/yxi-install-sa.txt等诊断并停止该测试worker，未重发原安装。脚本新增180秒/10秒强制退出边界；之后fixture.ViNmP2完整复跑39秒正常退出，日志/tmp/yxi-plugin-install-ui-bounded/test.log，最终空状态截图已查看。更正：用户已说明所讨论的退出中断由关机引起，上述观测不足以认定应用缺陷，撤回据此新增的退出故障排查项；保留原始日志备查。
- 最终全量136项中128通过、8环境测试跳过，Linux打包成功，日志/tmp/yxi-plugin-install-e2e-package.log；本机最终截图tmp/workbench-evidence/plugin-installed-verified.png。

## 第四十轮：干净提交构建与Windows原生复核（2026-09-13）

- 从52045a1导出的源码包暴露gradlew换行问题：索引LF，但Windows git archive在缺少该文件属性时导出CRLF，Linux报cannot execute: required file not found。新增.gitattributes中android/gradlew text eol=lf，提交0b9f98e。
- 从0b9f98e重新导出完整源码，在hk13全新/root/.cache/yxi-windows-build-0b9f98e中解压，确认gradlew为LF并完成Windows uber jar构建；未依赖长期工作目录的临时修正。日志/tmp/yxi-windows-0b9f98e-build.log。
- 下载后核对Windows jar SHA256：0f82e290b689034bde56f4c9ca3dfbf2cdb6a0879cd7e6854aa2077cabfe4a6d；与服务器一致。通过windows-native-verify.ps1在Windows本机创建独立app-image。
- 实际exe完成smoke、虚构令牌的DPAPI迁移/轮换/篡改拒绝、跨进程重读/退出/重登、原生浏览器载入/非空像素/40px样式/退出检查。已查看浏览器像素截图，未访问真实账号或用户剪贴板。
- 产物本机tmp/windows-0b9f98e-native/YxiWorkbench/YxiWorkbench.exe；该目录包含所有检查日志、browser-native.png、artifact-sha256.txt与source-manifest.json。没有替换已安装Yxi，没有生成或发布新Setup版本。
- 本轮证明当前完整源码可构建并通过Windows启动/凭据/浏览器冒烟，不等同插件完整操作、所有UI/DPI/IME、SSH多主机、升级安装或完整PRD验收。上述专项和W03剩余能力仍需继续。

## 第四十一轮：插件卸载执行与文件副本（2026-09-13）

- 核对当前CLI卸载参数，新增明确scope的uninstall动作，固定--keep-data且不传--prune，保留持久数据与未指定依赖。复用prepare指纹、操作ID和查询，不把同范围重复操作重新执行。
- 执行前保存配置、安装登记和插件目录tar副本，记录SHA256；副本权限600，保存在服务器私有操作目录。限制128MiB/20000项，拒绝特殊文件，不跟随目录/文件符号链接读取外部内容；副本失败不调用卸载。
- 卸载后核对目标范围的登记已不存在且CLI退出成功，才返回uninstalled；失败或未知保持unknown。当前该终态尚未接入客户端按钮/状态映射。
- 临时市场实际安装后卸载测试通过：重复原ID返回uninstalled、真实CLI列表目标消失、其他enabledPlugins字段保留、持久数据文件存在；核对副本权限、hash及plugin.json内容。原启停/恢复测试回归通过，未卸载生产插件。
- 包副本目前仅保留可供后续恢复的数据，尚未实现卸载后恢复/重新安装UI；并发外部文件改动的一致性、所有作用范围、Windows专项和完整W03仍待完成。
- 桌面测试与Linux打包通过，日志hk13 /tmp/yxi-plugin-uninstall-build.log。

## 第四十二轮：卸载界面与操作终态（2026-09-13）

- 插件卡片加入卸载入口，与启用/停用共享准备流程，确认弹窗明确主机、范围、配置路径，以及保留数据、不清理依赖、先保存副本。目录不存在时暂禁用卸载，需要后续残留登记清理流程。
- uninstalled加入客户端持久终态及查询失败不降级逻辑；卸载完成刷新主机列表，目录重新获取可安装项，状态说明保留数据与副本。原生测试脚本增加180秒超时边界。
- 新增终态持久/查询失败保留检查；扩展原生测试为恢复后取消卸载、再次确认卸载，核对剩余配置、目标登记、插件tar副本及重读客户端记录。
- 桌面测试与Linux打包通过（/tmp/yxi-uninstall-ui-build.log）；真实界面测试结果另记。Windows卸载、缺失目录清理、包恢复、升级和完整W03仍待完成。
- 原生全流程已通过，日志/tmp/yxi-uninstall-ui-verified/test.log，fixture.oAB6jd。实际确认取消不改配置、确认后目标安装记录为空且Read权限保留、tar副本存在、重读末条uninstalled。已查看本机tmp/workbench-evidence/plugin-uninstall-confirm.png及plugin-uninstalled-verified.png。

## 第四十三轮：插件升级执行与版本回执（2026-09-13）

- 核对CLI update支持scope并要求新会话应用，新增update动作。沿用配置指纹检查和私有配置/登记/插件包副本，执行前备份失败不调用更新；不自动接受来源变化带来的安装命令授权。
- 记录beforeVersion/afterVersion，不根据按钮点击猜测新版本；CLI退出成功且目标安装目录存在才返回updated，结果可按原ID查询/去重。未据此声明插件功能、依赖或登录正常。
- 临时本地市场从1.0.0改到1.1.0并更新测试市场索引后，真实插件update通过；验证版本回执1.0.0→1.1.0、重复ID返回结果、旧包副本manifest仍为1.0.0，以及其他插件设置保留。随后原卸载/安装与启停/恢复测试通过，未升级生产插件。
- 升级目标由CLI当前市场提供，当前未实现安装前展示精确目标版本/源码提交、市场并发变化固定、updated客户端终态/UI、包回滚及Windows专项；完整W03仍待推进。
- 桌面测试与Linux打包通过，日志hk13 /tmp/yxi-plugin-update-build.log。

## 第四十四轮：插件更新入口与版本回执展示（2026-09-13）

- 卡片增加更新入口，确认弹窗显示主机/范围/路径和列表记录版本；明确请求市场提供版本、最终版本以回执为准，不捏造升级目标版本。现有会话不自动重启。
- updated成为客户端持久终态，保存服务器beforeVersion/afterVersion并在结果栏展示；旧账本无新字段仍可读取，未知版本显示未知。查询网络失败保留已确认状态和版本，成功刷新安装列表。
- 持久化测试扩展到updated及版本字段跨重读保留；新增更新按钮后同步调整原卸载测试位置。桌面测试/打包通过（/tmp/yxi-update-ui-build.log）。
- 更新按钮到实际市场升级的原生点击、精确目标版本固定、包回滚、Windows专项与完整W03仍待验证，不能将前轮CLI升级测试冒充本轮界面升级验收。
- 原启停/恢复/卸载原生回归正常完成，日志/tmp/yxi-update-ui-regression/test.log，fixture.lWMQ7G。仅用于验证新增按钮后旧流程未受影响。

## 第四十五轮：插件更新的原生流程验收（2026-09-13）

- 扩展真实安装fixture：先通过目录安装1.0.0，再修改临时市场插件与索引到1.1.0，通过该测试市场的CLI索引更新后切到主机插件页，实际点击更新及确认。
- 核对updated回执、beforeVersion=1.0.0、afterVersion=1.1.0；重新查询安装登记并直接读取安装目录plugin.json，均为1.1.0。新建PluginOperations读取本地文件，确认版本回执保留；这属于持久记录重读，不等同完整程序重启验收。
- 已查看确认与结果截图：本机tmp/workbench-evidence/plugin-update-confirm.png和plugin-updated-verified.png；界面结果和卡片版本一致，运行器列表显示识别且启用。
- 自动化完整执行并正常退出，日志/tmp/yxi-plugin-update-native/test.log，fixture.CjtwDX。本轮仅扩展测试/脚本与进度文档，没有重复无关生产打包，也没有更新生产插件。
- 仍需精确升级目标版本固定、包回滚、远程来源/项目范围、多主机断线以及Windows更新专项。完整PRD继续推进。

## 第四十六轮：插件包回滚底层（2026-09-13）

- rollback按已确认updated/uninstalled操作定位副本，核对配置/登记after指纹、原配置/登记before指纹与tar SHA256；后续配置变化或副本被改动时拒绝恢复。
- 恢复文件写入私有restored/操作ID新目录，并把原登记的目标安装路径指向该目录，避免覆盖共享缓存或用户后来编辑的目录；同时恢复原配置和登记，回读两者确认后记录package-restored。
- 解包限制成员数/总大小/路径前缀，拒绝绝对路径、..、重复规范化路径和链接父目录；先恢复常规文件，再恢复硬链接/符号链接，不让归档链接重定向写入。保留常规文件权限但不恢复setuid等特殊位，链接本身保留但不跟随读取。
- 临时市场真实测试验证升级1.1.0回到1.0.0，以及卸载后恢复1.0.0，CLI能重新读到登记与文件；重复ID不重复解包、篡改归档拒绝、后续配置改动保留。新增test-plugin-package.py验证普通文件/权限/链接及路径逃逸拒绝，原启停恢复回归通过。
- 配置与登记是逐文件原子写入，尚非跨文件事务；中断可能留下待确认状态，当前不自动继续恢复。新目录恢复对依赖绝对原安装路径的插件仍需验证。package-restored客户端/UI、崩溃恢复、Windows专项与完整W03仍待推进。
- 发现本地Python测试在资源目录生成__pycache__，新增资源打包排除规则，防止测试缓存混入应用。最终包检查另记。
- 桌面测试/打包日志/tmp/yxi-plugin-rollback-build.log；追加回读验证后Python回滚测试和最终打包通过（/tmp/yxi-plugin-rollback-package.log）。逐项检查jar：必要plugin-operation.py存在，__pycache__/pyc为零。

## 第四十七轮：插件包恢复入口与持久终态（2026-09-13）

- updated/uninstalled结果栏增加恢复插件包，确认弹窗显示主机、范围、原配置路径及恢复版本，说明独立恢复目录与后续配置变化拒绝覆盖。提交仍沿用原操作来源和新操作ID。
- package-restored接入客户端持久终态，保存恢复版本并刷新安装列表；查询网络失败不会抹掉已确认结果。持久化测试覆盖恢复版本及重读状态。
- 桌面测试与Linux打包通过（/tmp/yxi-rollback-ui-build.log）。扩展原生安装fixture到更新后包回滚，真实界面结果另记。
- 当前仅显示最近操作的恢复入口，历史操作选择、跨文件中断恢复、绝对路径依赖插件、Windows回滚与完整W03仍待推进。
- 原生完整安装→更新→回滚通过且正常退出，日志/tmp/yxi-rollback-native/test.log，fixture.pwM9BS；断言实际plugin.json为1.0.0、路径属于plugin-operations/restored，登记及持久记录一致。已查看本机tmp/workbench-evidence/plugin-rollback-confirm.png与plugin-package-restored.png。

## 第四十八轮：插件操作历史（2026-09-13）

- 主机插件页增加操作记录弹窗，按主机端点键过滤、最近在前，可搜索插件/编号；展示动作、范围、版本、目录和可选择复制的操作ID。
- 可查询历史回执，已确认的设置变更/升级/卸载记录可进入相应恢复确认；恢复沿用服务器原指纹检查，不绕过后续变更保护。未知操作存在时仍阻止该主机新变更。
- 已拒绝终态在查询不可用时不降级未知，避免旧拒绝操作重新阻塞主机；拒绝记录不提供无意义的服务器查询按钮。新增测试验证A/B历史隔离、顺序与拒绝状态跨重读保留。
- 桌面测试/打包通过（/tmp/yxi-plugin-history-build.log），原生历史弹窗验证另记。历史恢复选择的完整点击、缺失本地记录对账、跨文件中断恢复和Windows专项仍需推进。
- 原生弹窗打开/关闭先通过（fixture.rGiIt2）；增加滚动条后默认渲染测试在窗口关闭阶段再次卡住，按超时终止，并核对环境后清理该测试worker。根据已安装Skiko 0.150.1字节码确认SKIKO_RENDER_API支持SOFTWARE_COMPAT，仅为Xvfb交互脚本设置默认值，正式应用设置不变。软件渲染复跑fixture.YRl5RH在59秒正常结束，日志/tmp/yxi-history-software-final/test.log；已查看滚动后的较早安装记录截图tmp/workbench-evidence/plugin-history-older.png。更正：用户已说明所讨论的退出中断由关机引起，撤回默认渲染存在退出故障的推断，并移除脚本中据此设置的软件渲染默认值。此前软件渲染结果仍仅证明对应测试范围，不扩展为GPU/Windows验收。
- 最终Linux打包通过，日志/tmp/yxi-history-final-package.log；首次全量桌面验证日志/tmp/yxi-plugin-history-build.log。

## 第四十九轮：按用户说明更正退出记录与测试缓存（2026-09-13）

- 用户明确说明先前退出中断来自关机。已更正第三十九/四十八轮及handover中的故障归因，撤回应用退出缺陷判断；原始测试日志仅保留作为中断记录。
- 删除Xvfb安装脚本中据此新增的SOFTWARE_COMPAT默认值；不修改正式应用渲染偏好。保留一般性的测试时限，避免自动化无限等待。
- 复核发现Gradle把新隔离目录的UI测试判为UP-TO-DATE，导致没有启动窗口。将各类fixture标识与显式渲染参数声明为Test输入，确保新隔离环境触发实际执行。
- 缓存修正后的默认渲染复核实际走完界面步骤并生成history-closed，但测试进程未在时限内结束；本次不计为通过，也不将此测试环境结果作为正式应用故障结论。终止后仅按精确fixture环境校验清理对应worker。日志/tmp/yxi-history-default-executed/test.log，fixture.zbsxa3。
- 下一项优先补齐PRD W01-A的服务器秘密系统保护：当前Store仍通过DurableFile保存hosts.json，密码字段尚未接入现有DPAPI能力；本轮仅核对代码，不访问用户密码。
- 缓存配置修改后普通桌面测试137项中129通过、8环境测试跳过；日志/tmp/yxi-fixture-input-tests.log。未把普通测试结果扩展为默认渲染完整退出验收。

## 第五十轮：Windows主机凭据保护（2026-09-13）

- 新增HostConfigFile并接入Store：Windows完整服务器列表保存为hosts.json.protected，活动备份也为密文；使用独立Yxi/host-credentials/v1 DPAPI用途，与账号令牌用途隔离。非Windows继续原JSON存储，但拒绝把已存在的受保护记录静默读成空列表或覆盖。
- 首次迁移先保护并回读校验当前列表，再为旧main/bak/damaged保存加密迁移副本，核对原内容未变化后才清成[]。不同有效主记录、保护失败、非UTF-8损坏文件均保留并拒绝覆盖；不把损坏保护文件回退为明文。
- 保留服务器稳定ID与连接字段，复用原格式校验和受保护备份恢复；迁移副本用于保留旧历史内容，不自动将旧密码恢复成当前值。账号令牌原DPAPI用途默认值不变。
- 两项新增测试覆盖主/旧备份/损坏副本迁移、受保护备份恢复、无保护器拒绝覆盖、保护失败和明文冲突。全量139项中131通过、8环境跳过，Linux打包通过（/tmp/yxi-host-protection-build.log）。
- Windows本机通过dev/HostProtectionNativeCheck.java调用本轮编译类，实际DPAPI迁移后main/bak清空，独立Java进程成功重读，错误用途被拒绝，随后受保护保存成功。仅虚构服务器密码，不读取用户真实记录；证据目录tmp/host-native-20260913含测试profile/class及jar SHA256。
- 本轮是Windows系统加密组件及数据层验证，不等同最新完整exe升级迁移验收。历史漫游目录的额外副本/冲突、真实多主机重启、旧版本降级、外部私钥文件管理与完整W01-A仍待核对；未替换用户安装版。

## 第五十一轮：旧漫游主机配置迁移（2026-09-13）

- Store将hosts迁移从通用明文复制改为HostConfigFile.importLegacy，主文件、bak和damaged的加密副本只写LocalAppData目标目录，验证后才把旧源清空为[]；其他known_hosts/偏好仍沿用原校验移动。
- 有效主文件优先，损坏/缺失时可用有效备份并提示恢复；新旧有效列表不一致时保留双方。已经清空的旧标记不会覆盖本地受保护列表，重复迁移可继续完成。
- 源文件先快照，保护后清理前再次核对；修复本地和漫游迁移中可能清理新写入内容的时序窗口，发现变化时保留源。非UTF-8数据仍拒绝清理。
- 两项新增测试覆盖坏主文件/好备份、本地归档位置、重复导入、有效数据冲突、迁移期间外部改写不被清空。全量141项中133通过、8环境跳过，Linux打包通过（/tmp/yxi-host-roaming-build.log）。
- Windows通过扩展HostProtectionNativeCheck实际验证漫游main/bak清空、本地两份加密导入副本、原有效列表不变及跨Java进程重读/用途隔离/保存。仅虚构数据，证据tmp/host-roaming-native-20260913及artifact-sha256.txt；未读取真实服务器密码。
- 仍需完整exe升级/多主机重启、历史副本选择恢复与所有受保护活动文件缺失场景。尤其主配置和活动备份均缺失但保留迁移副本时，需要增加明确缺失提示，避免把旧[]标记当成新空列表；下一步继续处理，完整W01-A尚未完成。

## 第五十二轮：活动主机配置缺失的显式恢复（2026-09-13）

- 修复活动密文/备份全缺失时误把旧[]标记当空配置的问题：发现迁移副本则阻止普通读取/空写入，非Windows也不能覆盖仅剩受保护副本的配置。
- 新增副本候选列表，只展示服务器名称/地址/端口和数量，不展示密码；无法解密或校验的副本标为不可选。恢复前校验密文指纹与目标仍缺失，新旧有效明文冲突时拒绝覆盖。
- 侧栏出现恢复入口，用户明确选副本后恢复受保护主配置，保留副本并更新列表；已有连接时先要求断开，当前恢复不自动连接。仍遵守启动时的既有重连设置，未改变全局偏好。
- 单元测试覆盖缺失后空写入被拒、无保护器不覆盖、预览不含密码、错误指纹拒绝、明确恢复及活动文件存在时拒绝覆盖。全量142项中134通过、8环境跳过，Linux打包通过（/tmp/yxi-host-recovery-build.log）。
- Windows扩展NativeCheck通过：测试profile删除活动密文/备份后空写被拒，明确选择原加密副本成功恢复，并重读得到原虚构记录。证据tmp/host-recovery-native-20260913及artifact-sha256.txt，未操作真实用户配置。
- 目前只允许活动文件均缺失时恢复；活动密文与备份均存在但不可读、历史副本时间/来源细化、多进程外部并发、完整exe恢复按钮/重启和Windows布局仍待验收，完整W01-A继续推进。

## 第五十三轮：Windows应用入口的多主机跨进程验证（2026-09-13）

- 新增--host-smoke入口，严格要求Windows、显式fixture根目录、匹配的user.home/APPDATA/LOCALAPPDATA，以及首次创建的测试标记；测试专用分支在Updater/连接/窗口逻辑前执行，不连接测试服务器。
- 第一exe进程从虚构漫游目录导入两台服务器，核对ID、用户名、地址、端口与密码，保存别名与重连偏好；第二exe进程重新加载Store，核对两台记录、修改后的别名与偏好仍在。旧漫游main/bak清空，当前本地hosts相关文件不含测试明文密码。
- windows-native-verify.ps1接入host-smoke/host-reopen并保留原启动、凭据、浏览器检查。基于干净Git提交e0ad813的完整源码归档，在hk13新目录构建Windows jar，构建日志/tmp/yxi-windows-e0ad813-build.log。
- Windows本机实际app-image exe完成六项检查并正常退出；jar SHA256为a320bcf7274bc47fb1b46578b271bbd9fad774402f1dedca9d026f9807740ffb，与服务器一致。证据tmp/windows-e0ad813-native含分项日志、artifact-sha256.txt和source-manifest.json。
- 这是实际exe入口及Store的跨进程迁移/保存检查，不等同恢复按钮完整点击、真实SSH重连或所有DPI/IME验收；测试程序仅在脚本注入的隔离环境中运行，没有替换用户安装版，完整PRD继续推进。

## 第五十四轮：主机恢复弹窗交互与布局（2026-09-13）

- 恢复弹窗可注入只读副本来源用于隔离验证，正式入口仍读取Store；可验证副本优先展示，保持初始不选中，无法解密/校验的副本不可选。增加滚动条与圆角卡片。
- 首次截图发现滚动条fillMaxHeight把短内容撑到340dp，改为匹配已测得父容器高度，少量副本不再出现大段留白，超长内容仍有高度上限。
- 新增HostRecoveryUiTest：使用虚构保护器和临时文件构造一个有效双主机副本及一个无效副本，实际窗口Escape取消后仍待恢复；重新打开、键盘明确选择后恢复，断言文件重读与原记录一致。首次键盘顺序落到取消按钮，修正为正确的Tab顺序后通过。
- 软件渲染的隔离Xvfb交互正常结束，日志/tmp/yxi-host-recovery-ui-final/test.log；已查看本机tmp/workbench-evidence/host-recovery-verified.png。测试只验证Compose交互和数据层回调，真实DPAPI由前轮Windows验证覆盖，不能将此轮视为Windows恢复按钮验收。
- 仍需长列表/不同DPI/IME、完整Windows侧栏入口及恢复后应用重启专项；正式用户配置和连接未被操作，完整PRD继续推进。
- 全量143项中134通过、9环境测试跳过，Linux打包成功，日志/tmp/yxi-host-recovery-ui-package.log。

## 第五十五轮：服务器导入导出与手动验收交接

- 主机菜单增加导出/导入。导出仅白名单元数据，不带密码或私钥路径，并阻止覆盖应用配置目录及已引用私钥。
- 导入支持当前导出格式和旧版列表，先展示新增/重复/标识冲突预览；同地址端口用户名的记录跳过，已有认证保持不变。新服务器不带认证，点击连接先要求编辑认证信息。
- 确认保存前核对现有列表未发生变化；取消不保存。原导入文件不被修改。
- 用户明确要求停止主动测试、交叉验证和专项检查，之后以功能实现为主。已整理design/windows-manual-verification.md，后续验收由用户执行并反馈问题。本轮之前已写入的测试保留，不再追加验证流程。

## 第五十六轮：输入历史

- 输入框增加历史入口，从当前任务已有持久指令记录中搜索和复用文字，追加时保留草稿；显示记录状态，不自动发送，不复制旧附件。
- 本轮未运行测试、编译或交叉验证，用户验收步骤已追加到windows-manual-verification.md。


## 第五十七轮：对话搜索与提示词复用

- 对话输入工具增加搜索，可匹配当前已加载的用户消息、Agent回复、工具、思考和提示记录，定位并展开工具分组。
- 输入历史支持查看全文、选择文字和确认替换草稿；不自动发送。模型信息移入可换行区域，发送工具保持独立。
- 按用户要求未运行验证，操作步骤已加入手动验收文档。


## 第五十八轮：服务器地区标签

- 主机记录新增地区标签，支持编辑、保存、选择器与侧栏展示、按地区搜索，以及导入导出保留。旧记录默认空标签。
- 仅修改标签不触发现有连接重建；未运行验证，步骤已追加手动验收文档。


## 第五十九轮：文档内容查找

- 右侧文档支持查找栏、Ctrl+F、前后匹配和行号提示，定位时切换源码并选中、滚动至文字。支持Enter/Shift+Enter，避免搜索框Ctrl+A触发全文选择。
- 未运行验证，交由用户按手动验收文档检查。


## 第六十轮：网页响应式尺寸预览

- 右栏提供自适应、1440×900、768×1024、390×844视口，按面板大小缩放显示，并在浏览器重新打开或尺寸变化时应用。
- 使用浏览器Emulation尺寸覆盖接口，不改网页源码；不是完整手机环境模拟。切换尺寸后旧元素选择提示过期，便于重新选择再反馈。
- 未运行验证，具体交互效果由用户按手动文档验收。接口参考：https://chromedevtools.github.io/devtools-protocol/tot/Emulation/ 。


## 第六十一轮：网页明暗主题预览

- 右栏增加系统、亮色、暗色切换，通过浏览器媒体偏好显示页面对应样式，不强制改写网页CSS或Yxi主题。
- 状态随当前预览实例保留，页面重新载入后重新应用。未运行验证，操作说明已加入手动文档。


## 第六十二轮：网页内查找

- 右栏网页支持文字查找、前后匹配、大小写开关，Enter/Shift+Enter定位，Escape关闭。关闭或离开面板时清理查找高亮。
- 未运行验证，用户验收步骤已写入手动文档。


## 第六十三轮：收拢预览工具栏

- 尺寸与主题设置默认折叠，保留当前设置摘要；展开可调整或恢复默认，给网页更多显示空间。
- 未运行验证，交由用户验收。


## 第六十四轮：桌面语音输入

- 输入框增加麦克风入口，Java Sound录制PCM/WAV，使用当前服务器yxi-asr转写，结果先编辑再加入草稿。
- 录音保存在内存，通过SSH送至服务器私有临时目录；服务器命令结束后清理，失败时另做尽力清理。取消释放设备，不自动发送文字。
- 当前为服务器识别路径，本机离线模型和服务安装引导仍需继续；未运行验证，相关步骤写入手动验收文档。


## 第六十五轮：麦克风选择

- 语音弹窗列出录音设备，可刷新并保存选择；录音使用指定设备，设备消失时提示，不自动换用其他麦克风。
- 未运行验证，验收步骤已更新。


## 第六十六轮：语音服务安装指引

- 语音弹窗可打开目标服务器安装说明、导出自带运行脚本与独立安装器的zip、复制安装命令。打包复用仓库server/yxi-asr，导出时统一LF。
- 安装脚本使用用户目录venv和SenseVoice模型，不注册Agent钩子；系统依赖由用户准备，模型目录替换前保留旧目录。应用不自动执行安装。
- 未运行验证，步骤已加入手动验收文档。


## 第六十七轮：连续语音与重试

- 多段识别追加到已有编辑文字，失败后保留本段内存录音供手动重试或丢弃，不要求用户重新录制。
- 成功或关闭时清理录音；未处理录音纳入退出提示。未运行验证，步骤已交给用户。


## 第六十八轮：语音文字取用

- 识别结果支持复制、清空和撤销清空；清空后仍可编辑，新录音结果到达时结束旧清空撤销状态。
- 未运行验证，用户验收步骤已追加。


## 第六十九轮：任务快速切换

- 增加侧栏入口和 Ctrl+K 弹窗，跨已加载的服务器会话搜索任务名、项目路径、服务器名称和地区；置顶优先，可包含归档，Enter 打开首项。
- 复用已有任务选择逻辑恢复项目与主机范围。未运行编译、测试或交叉验证；验收交给用户。

## 第七十轮：排队指令快捷操作

- 指令条加入行内撤回、更多菜单，以及上移、下移、移到本地待发送首位。撤回后可撤销，恢复原条目和附件；不触发自动发送。
- 排序仍仅作用于本地待发送指令，状态待确认条目继续阻塞投递，避免把本地排序宣称成远端引导。
- 未运行编译或验证，操作步骤交给用户。

## 第七十一轮：整页设计反馈

- 网页工具栏增加整页反馈，不需先选元素；记录页面地址、标题、服务器、项目、时间与视口/主题设置，和用户修改要求一起加入当前任务草稿。
- 暂未加入的文字保留在任务预览状态中，并纳入关闭提醒；不会自动发送。元素反馈也补齐项目和显示设置，默认文案去掉主动验证要求。
- 未运行编译或测试。整页反馈文字目前仅在应用内存中保留，尚未持久化。

## 第七十二轮：文档查找替换

- 文件右栏增加替换入口与 Ctrl+H，可替换选中匹配项或全部匹配，沿用查找的不区分大小写规则，按字面文字匹配。
- 提供撤销本次替换；出现后续编辑时禁用该撤销，避免覆盖新编辑。替换仅改变编辑缓冲区，手动保存后写回服务器。
- Ctrl+A 改为仅在文档编辑器聚焦时接管，查找/替换输入框各自保留全选行为。未运行验证。

## 第七十三轮：预览空间调整

- 右侧拖动宽度在松手时保存到本地设置，下次启动恢复。网页和文件面板增加展开/恢复分栏，展开时占用主工作区，保留任务草稿与文件编辑状态。
- 窄窗口仍采用单面板布局。未运行编译或验证。

## 第七十四轮：终端投递记录

- 发送前在服务端用户目录以任务实例与指令 ID 的摘要登记投递，重复 ID 不再写入终端；完成文字与回车后写完成记录。仅保存摘要与时间，不保存提示词正文。
- 状态待确认弹窗增加查询投递记录，可区分完整写入、登记后中断及无记录。查询不重发、不自动解除阻塞，也不将终端完成等同运行器接收。
- 这仍不是运行器回执协议，自动按轮投递与引导尚未完成。未运行编译或测试。

## 第七十五轮：保留投递核对信息

- 服务端查询结果及查询时间随原指令持久化，重启后可继续核对；人工解除阻塞保留查询信息。旧记录缺少字段时按空值读取。
- 无法识别服务器回复时显示查询失败，不再当作无记录；查询期间指令状态改变时不覆盖新状态。未运行验证。

## 第七十六轮：线路应用目标

- 应用线路时固定运行器、项目范围与线路清单快照；执行中禁止切换运行器选项。工作中检查按目标运行器和范围筛选，不再被另一种运行器的无关任务阻塞。
- 确认框显示模型、运行器和已知受影响任务，运行中任务标注需等待。仍区分配置写入与请求生效，未运行验证。

## 第七十七轮：多模型线路管理

- 线路可复制为独立草稿，复用同服务器的端点和认证后修改模型；保存前不新增记录、不应用配置。高级配置深复制，避免副本编辑改变原对象。
- 增加备注编辑和展示，按名称、地址、模型、备注搜索；按钮行可横向滚动。未运行验证。

## 第七十八轮：推理强度与 Codex 模型缺口

- Claude 线路编辑接入已有 extra.effortLevel 写入路径，可填写或移除推理强度并在卡片显示。
- 当前源码 Lines.applyCodex 只写提供方和认证，未应用 extra.model；界面已明确模型仅保存为记录，尚不能声称 Codex 模型切换完成。后续需要补齐 TOML 模型写入及默认恢复。
- 未运行验证。

## 第七十九轮：Codex 模型写入

- Codex 应用线路写入模型和 model_reasoning_effort；配置回读匹配加入这两个字段，避免同端点同密钥不同模型被误认同一线路。编辑界面开放推理强度。
- 原有 model_provider、被覆盖的 model/推理强度顶层行保留为 Yxi 注释，移除管理块时还原；未指定模型或强度时保留用户原值。顶层多行字符串目前拒绝自动改写。
- 替代上一轮模型仅保存的限制。共享核心同时影响 Android 的线路应用；未运行编译、测试或真实配置切换。

## 第八十轮：插件说明预览

- 安装记录增加使用说明入口，从对应服务器插件目录读取 README 与 manifest 描述，展示主机、范围、版本及路径，Markdown 可直接预览和选择文字。
- 按需只读，不执行插件命令；长说明显示前 128 KiB，缺少文件或读取失败显示提示，可重新读取。未运行验证。

## 第八十一轮：插件说明加入任务

- 插件说明弹窗可填写具体要求，将安装主机、范围、目录、版本及 README 摘录加入打开弹窗时的当前任务草稿；任务上下文改变时禁用。
- 保留原草稿，说明以引用内容标注，正文摘录限制 12000 字符；不自动发送、不执行插件。未运行验证。

## 第八十二轮：桌面协作组视图

- 工作台增加协作组弹窗，读取当前服务器 groups.json，显示分组、组规、成员路径与会话状态，点击可进入成员任务。未找到的成员明确显示未知/离线可能性。
- 本轮为只读协作入口；未实现组编辑、负责人、定向消息与自动协作预算。未运行验证。

## 第八十三轮：协作组编辑

- 新建协作组、选择成员和编辑组规，保存到当前服务器 groups.json；保留其他组与文件额外字段，使用临时文件替换。
- 编辑前后对比当前组的成员和规则，检测到其他客户端修改时提示刷新；这不是跨客户端事务锁，极短并发写入窗口仍存在。保存不广播消息。未运行验证。

## 第八十四轮：移除协作组

- 增加移除分组确认界面，展示成员与只读组规。仅移除服务器分组及对应组规，保留任务、文件和其他组；不停止 Agent，不广播消息。
- 复用组内容变化检查和临时文件保存路径。未运行验证。

## 第八十五轮：协作消息记录

- 协作组入口可读取服务器 hub.log 最近 256 KiB，支持按成员、组名或正文搜索并保留相邻行；文本可选择复制，支持刷新。
- 沿用原始多行日志展示，不虚构消息 ID、回复关系或处理回执。日志包含整台服务器记录，不按当前组伪造过滤归属。未运行验证。

## 第八十六轮：成员任务指派

- 协作成员卡片可填写指派要求，生成带唯一 ID、组名、来源与目标项目的本地待发送指令，跳转目标任务处理；复用现有持久队列，不覆盖草稿。
- 明确以用户身份发起，不代表来源 Agent 发言；不自动投递。尚未接入 Agent 间回复关联、汇总与自动协作。未运行验证。

## 第八十七轮：组内指派追踪

- 指令持久记录增加协作组、主机身份和来源任务字段，组视图按主机与组名显示本机指派、真实队列状态及目标入口。
- 旧指令缺少关联字段继续可读，但不从正文猜测归组；人工核对不显示为已完成。组名复用时仍会显示历史同名组的本机记录。未运行验证。

## 第八十八轮：协作投递事件

- 仓库 yxi-hub 每次投递生成 UUID，正文附消息 ID，向 hub-events.jsonl 记录发起者、接收者、时间及尝试/终端完成/未知阶段；追加记录使用文件锁，消息正文仅在开始事件保存。
- 桌面协作记录支持切换传统日志与带 ID 事件源。尚无接收回执、回复关联或重复调用去重；终端写入不代表处理完成。
- 未部署服务器脚本，未向 Agent 发送消息，未运行验证。

## 第八十九轮：按消息 ID 回复

- yxi-hub 增加 reply 命令，只查找发给当前会话的原消息，精确定位原发送者并重新检查当前同组关系；事件保存 replyTo，正文附回复 ID。
- SessionStart 协作说明加入回复用法。仍以会话名识别服务器成员，跨重建实例的隔离和重复投递去重尚待补齐。未部署、未发消息、未运行验证。

## 第九十轮：协作消息卡片

- 按消息 ID 合并事件，显示发送者、接收者、正文、最新投递阶段与时间；原消息/关联入口按 ID 搜索直接回复。
- 缺正文、未知阶段和截断行明确提示，不以终端写入冒充处理完成。只在当前读取范围搜索，未运行验证。

## 第九十一轮：协作消息固定 ID 去重

- say/reply 可传第四个参数作为本次消息 UUID；日志锁内登记并比较同 ID 的发送者、接收者、正文和回复关系，完整投递不重复输入，未知状态拒绝重发。
- 不传 ID 仍生成新 ID；all 群发尚无调用级去重。去重依赖保留事件日志，截断或删除日志将削弱历史去重；轮转方案仍待补齐。未部署或验证。

## 第九十二轮：群发批次去重

- all 支持显式组名和第四参数批次 UUID，保存发起者、正文与成员快照，按批次为成员派生固定消息 ID；沿用单条消息去重。
- 同批次内容/成员改变时拒绝，成员读取直接解析 JSON；批次日志需保留。未知投递成员仍会阻塞本次重试，不猜测补发。未部署或验证。

## 第九十三轮：协作任务实例关联

- 投递事件保存发送方与接收方 tmux 实例身份；固定消息 ID 重试同时比较实例，发送文字与回车使用已定位 pane 并检查实例。
- reply 拒绝缺少实例字段的旧消息，以及任一方已被同名新任务替代的消息。不是跨检查与发送的原子协议，仍需后续运行器通道解决竞争窗口。未部署或验证。

## 第九十四轮：协作消息暂停控制

- yxi-hub 增加 pause/resume/status <组名>，用户目录保存状态；每条消息投递前重新检查同组关系及暂停状态。双方共享任一暂停组时停止新投递。
- 暂停不杀会话，不删除队列和记录；已经开始投递或运行中的任务不受此开关回滚。桌面控制入口尚待接入。未部署、未验证。

## 第九十五轮：桌面协作投递控制

- 协作组读取新版 yxi-hub status，展示开放/暂停/未确认状态及服务端时间；提供暂停、恢复和刷新。接口无有效回复时不给出成功结论。
- 旧服务器提示升级，不自动部署；开关只控制后续 hub 消息，不中断 Agent 或发送本地队列。未运行验证。

## 第九十六轮：协作消息与时间预算

- hub limit <组名> <消息数> <分钟> 设置从当前时刻起的新预算；投递登记锁内按消息 ID 计数，达到条数或期限拒绝新投递。群发按接收成员逐条计费式计数（不涉及货币）。
- pause/resume 保留预算，未知投递也占用次数以免反复重试突破限制；重复完成消息不重复计数。预算依赖保留日志，不是模型 token/费用预算。桌面设置待接入，未部署或验证。

## 第九十七轮：桌面协作预算设置

- 服务器状态返回预算能力、已登记消息数和到期状态；桌面显示额度、截止时间，到限时不再显示开放。
- 支持设置新的消息数/分钟预算，明确重新计数且保留暂停；旧服务器不显示未支持的设置操作。未部署或验证。

## 第九十八轮：回复循环限制

- 回复链最多 16 条，发现循环引用或缺失祖先记录时拒绝继续；同链第三次完全相同的正文（忽略首尾空白）阻止投递。
- 指纹取用户/Agent 原正文，不包含动态消息 ID 前缀。不同措辞重复、重新 say 开链不在此检测范围，仍需消息预算；旧日志无指纹不推断重复。未部署或验证。

## 第九十九轮：协作脚本导出

- 桌面打包配套 yxi-hub，可导出 LF 脚本、复制用户目录安装命令；保留旧文件备份。安装说明包含 PATH、依赖和已有钩子路径要求。
- 不自动部署、不注册钩子、不发消息；新安装的自动上下文注入仍需接入。未运行编译或验证。

## 第一百轮：协作上下文钩子配置

- 独立 configure-hub.py install/remove 注册用户目录 Claude SessionStart 钩子，已有简单 hub context 命令迁移到本地新版路径，保留其他钩子，写前备份原配置。
- 桌面提供导出与执行说明；不自动运行。移除仅去掉本地新版命令，旧命令可从备份恢复。Codex 上下文注入仍待接入，未打包、部署或验证。

## 第一百零一轮：任务启动提示词

- 新建 Claude/Codex 会话支持可选启动提示词，以独立参数传给运行器，并明确创建后可能开始工作。空内容沿用空会话。
- 启动请求身份包含提示词编辑状态，原请求重试仍复用同名任务；参数使用选项结束标记和 shell 引用。协作组自动加入/上下文生成尚待接入。未运行验证。

## 第一百零二轮：组内新建成员

- 协作组新建 Agent 预填可编辑的组规、队友与 hub 用法；启动前将唯一会话名加入服务器分组，支持 Claude/Codex 启动提示词。
- 缺组或分组变化时拒绝启动。启动失败可能留下离线成员，界面说明可移除；不是原子启动事务。Codex 启动后动态上下文更新仍依赖主动查询，未运行验证。

## 第一百零三轮：协作组界面分区

- 成员、指派、组规和投递控制分为标签页；组选择与管理操作可横向滚动，减少长列表堆叠。成员状态使用已有中文标签，增加空列表指引。
- 投递控制按需进入读取，未运行界面或编译验证。

## 第一百零四轮：协作负责人

- 分组增加 owners 字段，桌面可指定/取消负责人，移除该成员时清空负责人；组视图、成员卡片和启动上下文显示负责人。hub context 同步注入职责提示，不扩大权限。
- 共享核心读写保留 owners，Android 新版编辑成员/删除组同步清理；旧版客户端仍可能丢失新字段。未部署或验证。

## 第一百零五轮：负责人汇总入口

- 指派页可请负责人汇总，预填成员快照、最近 20 条本机指派 ID/状态/摘要及交付要求，供用户编辑后加入负责人待发送队列。
- 不自动发送或合并改动；明确队列状态不是工作完成凭据，负责人不在线时禁用。未运行验证。

## 第一百零六轮：独立工作树启动

- 新建任务可选 Git worktree，从源仓库 HEAD 创建同级独立目录，detached HEAD 启动；不带入未提交内容，不自动合并或清理。
- 同一启动请求复用所属仓库一致的工作树，拒绝已有非工作树/符号链接目标。适用于组内成员和普通任务；失败留下目录供用户处理。未运行验证。

## 第一百零七轮：Git 改动预览

- 工作台增加改动标签，读取当前任务仓库文件状态，可切换未暂存/已暂存差异并打开文件右栏。差异禁用外部 diff/textconv，长内容截取前 256 KiB。
- 未跟踪文件只列出并提供打开，不伪造 Git diff；无暂存/提交/撤销操作。未运行编译或功能验证。

## 第一百零八轮：差异阅读层级

- Git 差异区分新增、删除、块位置和文件头，显示当前读取部分的增删行数，保留单段文字选择复制；截取内容明确标注统计范围。未运行验证。

## 第一百零九轮：差异反馈到对话

- Git 差异页可填写修改意见，将服务器、仓库、文件、暂存范围和最多 12000 字符差异引用加入当前任务草稿，并返回对话；保留原草稿，不自动发送。
- 反馈引用是当时展示内容，不证明文件之后未变化。未运行验证。

## 第一百一十轮：无热更新页面自动刷新

- 预览设置可启用每 5 秒重载，适用于无 HMR 的开发页面；加载、重连、截图、元素选择、未提交反馈和弹窗期间暂缓。离开预览组合后停止计时。
- 默认关闭，恢复默认会关闭；刷新会重载页面，不代表源文件保存或构建已成功。未运行验证。

## 第一百一十一轮：工作台顶栏整理

- 主视图标签保留并支持窄区横向滚动，网页预览保持直接入口，模型线路/协作组/任务切换/快捷键收入更多菜单；项目路径单独一行显示。
- 四位测试 Agent 已接单，目前仅 cc-yxi 报告显示构建进行中，尚无测试结论。本轮中枢未运行测试。

## 第一百一十二轮：改动来源上下文

- Git 改动页显示分支/分离 HEAD 与提交简码；反馈带完整提交和服务器读取时间，替代点击反馈时才生成的时间。无提交仓库保留明确状态。
- 元数据和差异是同次读取中的连续查询，不是仓库原子快照。四位 Agent 测试仍在进行，中枢未重复运行。

## 第一百一十三轮：延后线路切换

- 应用确认框新增等待任务空闲后应用，固定服务器、线路与范围；中枢协调等待、取消和结果展示。检查生成/交互状态、任务实例与可输入画面后执行既有配置写入。
- 原线路清单变化时取消，写入结果不明不自动重试；等待只在应用运行期间有效，不持久恢复。终端检查与配置写入不是原子轮次协议，仍有并发开始请求窗口，不能等同严格服务端 turn 边界。
- 中枢未运行测试，交登录/线路 Agent 后续针对该新增路径回归。

## 第一百一十四轮：延后操作退出状态

- 退出检查显示等待线路的目标并说明退出后不继续执行；配置实际写入期间计入运行中操作，禁止正常丢弃退出。
- hk13 默认 tmux 服务再次查询仍不可用。现存构建日志显示 desktop:test FROM-CACHE/BUILD SUCCESSFUL，属于缓存结果，不能当成本轮完整回归通过。未重跑测试。

## 第一百一十五轮：工作台延后切换状态

- 任务工作台显示目标主机、线路、作用范围和等待/写入状态，提供取消等待；完成或失败提示可关闭，不必返回线路页面查看。
- 写入中显示进度标识，不提供取消写入入口。未运行测试，由线路 Agent 统一回归。

## 第一百一十六轮：任务视图上下文

- 中枢按主机和任务实例保存主视图、文件/网页侧栏和展开状态，切换回来恢复；新任务从对话视图开始，手动断开前记录当前布局。
- 本轮为应用内布局记忆，不承诺重启后恢复；草稿和文档沿用既有任务独立状态。交 UI Agent 后续回归。

## 第一百一十七轮：工作台首页

- 未选任务时展示服务器连接信息、新建/服务器/任务切换入口，以及最多六个可继续任务；置顶优先，已归档任务不混入。替换原单行空白提示。
- 首页继续展示延后线路状态，窄高度可滚动。UI 实现由中枢完成，待代理回归。

## 第一百一十八轮：浏览器地址栏输入法

- 根据 UI Agent B15 走读发现，地址栏 Enter 仅在无输入法组合时触发打开，与页面搜索现有处理一致。Windows 真机输入法行为仍待代理/用户验收。
- Agent D1 预算日期修复等待独立提交；B16 全文缓存建议暂不采用，文本改变仍需重新扫描，不能宣称解决按键性能。

## 第一百一十九轮：整合 Agent 日期修复

- 审阅并整合 cc-yxi_entertainment 独立工作树 D1 补丁：无预算截止时间不再显示 Unix 起点，有效时间按系统时区显示。来源为该 Agent 工作树 diff，并非已通过真机回归的提交。
- 修复由中枢统一提交，通知 Agent 不重复合入；测试仍由代理执行。

## 第一百二十轮：整合代理成果并修复改动列表

- 整合 logto 的线路 TOML 测试及默认线路钥匙清理（原提交88e4bef、7ffd10f），pilot 的 reserved 提示（f2777eb）。沿用各自报告，不在中枢重跑验证。
- 修复 UI Agent 已复现 N3：按 porcelain XY 分别过滤已暂存/未暂存文件，未跟踪只在未暂存出现；切换范围清除旧选中项，刷新后不显示已离开范围的差异。
- Windows 打包脚本14bdd0b仍缺显式版本参数、完整配置隔离和私有工具路径，未合入执行；交 cc-yxi 收尾。新集成版本回归继续委派 hk13 四位 Agent。

## 第一百二十一轮：Windows 首包产物已生成（2026-09-19）

- 应用固定源码3152af040da1b8316ecc8cbb009a8c3836c182a6，Windows JAR传输完成，SHA256 fecaf5f0d63237e5c973318d965d22e964a0a3eb3874e530c3a83a8caedff5db。
- 本机使用JDK21 jpackage生成package/Yxi原生app-image，使用官方vpk1.2.0私有NuGet包net8版本生成Releases/Yxi-win-Setup.exe；无SDK环境无需全局安装工具。
- 交付根目录：C:/Users/dfhzw/Documents/ChatGPT/Yunxi/artifacts/windows-3152af0/package。已附独立profile启动脚本、README和源码清单。未启动应用、未安装、未执行Windows回归、未签名/发布。
- 首包不包含后续d43449e/ee677ae执行层变更，完整PRD仍未完成。后续验证继续由hk13 Agent负责并明确Windows环境限制。

## 第一百二十二轮：整合延后线路回归

- 整合 logto 的a71d67d为3480a84，包含状态/退出保护测试、直接suspend执行层测试与隔离SSH六阶段。Agent报告5项状态测试、1项纯执行测试及fixture运行2项全部通过；中枢未重跑。
- 实际运行器输入态门和Windows UI仍未验证；首包3152af0不包含d43449e/ee677ae。完整PRD仍未完成，运行器回执与自动队列继续实施。

## 第一百二十三轮：移除连接取消等待

- 处理 logto 回归观察 D1：延后线路的原连接从应用移除时取消等待并说明未写入配置；同一连接普通断网仍等待重连。不修改已交付3152af0首包。
- 仅改变写入前等待阶段；新增针对性回归委派logto，中枢不重跑测试。

## 第一百二十四轮：后台任务轮次结束提醒

- 共享SessionProbe将同次快照已读取的done事件暴露给桌面，Conn常驻刷新层触发完成通知，移除ChatPane局部忙闲猜测通知。无新增SSH轮询通道。
- 首次连接只建立事件基线；同连接重连保持游标，过滤早于当前tmux实例创建的旧事件，同会话同时间事件去重。沿用现有通知偏好及跳转，不将Stop当作指令接收回执。
- 依赖服务器yxi-hook，设置页说明该条件；只覆盖快照保留的最近事件，不承诺长离线全历史补发。未改当前试用包，验证委派hk13。

## 第一百二十五轮：Codex 结构化通道底层

- 新增CodexAppServer SSH stdio客户端：请求/响应关联、初始化、thread启动/恢复/读取、turn启动/引导/中断，服务端通知和审批请求保留为事件流，不自动批准。沿用服务器权限/模型配置，无自动重发。
- 依据官方 https://learn.chatgpt.com/docs/app-server 与Agent本机0.153.4 schema。修正接口理解：thread/start仅建会话，不能代表指令已受理；接收/完成必须分开关联turn请求。
- 本轮是通道底层，尚未连接工作台与持久队列，不宣称自动队列已完成。未启动真实app-server或模型请求；下一步接控制器与审批UI，模拟协议回归交pilot。

## 第一百二十六轮：持久轮次状态

- 指令队列新增运行器turn ID及进行中/完成/失败/中断状态，接收凭据和完成凭据分开保存；进行中的已接收轮次阻止普通下一条投递。显式引导仅允许加入已知活动轮次，不能跨过Unknown条目。
- 完成事件按任务及turn ID关联，重复/未知轮次不修改队列；旧数据缺字段按None读取。指令条保留进行中项，不因Accepted立即消失。
- 这是结构化适配执行模型，控制器接线尚未完成，旧终端路径不伪造回执。定向验证交pilot。

## 第一百二十七轮：结构化任务控制器

- 新增CodexTaskController串联RPC与InstructionQueue：投递前持久化、响应后绑定turn、完成事件更新终态；处理完成通知先于请求响应的顺序，失败/中断暂停自动派发。
- 支持显式引导、中断、用户答复请求及thread/read核对已知轮次；断线不重发未知指令。审批保留为待处理请求，附件尚未适配时明确拒绝而不降级丢弃。
- 工作台入口及持久thread登记尚待接线，不能称端到端自动队列已完成。未启动真实模型请求，模拟回归交pilot。

## 第一百二十八轮：Codex 任务入口与对话

- 新增 Codex 任务页，账户菜单及工作区更多菜单可进入；按服务器列出本地登记任务，支持绝对目录创建、恢复连接和对话记录。保持当前工作台视觉方向。
- 接入持久指令条、手动发送、显式自动逐轮发送、下一条引导和中断；草稿纳入现有退出保护。切换页面不关闭运行控制器。流式文字独立累积，不受原始事件最近200条截断影响。
- 命令和文件审批由用户单次允许/拒绝，其他请求保留详情并提示尚未适配；不自动批准、不默默丢附件。新页面暂为文字对话，右侧文件/网页与该任务上下文的接线、附件及其他输入请求尚未完成。
- 本轮未执行测试、真实模型调用或更新试用包。编译交cc-yxi，协议/队列定向回归交pilot；现有用户试用包仍为3152af0。

## 第一百二十九轮：Codex 任务侧栏接线

- Codex任务页接入文件路径打开、Markdown/源码编辑侧栏与网页预览，复用既有保存冲突处理、实时刷新、项目服务及页面反馈。窄窗口可展开/收起返回对话。
- 文档以主机端点和Codex任务键隔离；文件引用、网页选区与整体反馈写入该任务草稿。预览公共组件改为接收任务键、目录和引用回调，旧终端入口继续通过适配调用，不伪造tmux Session。
- 文件树/对话内链接快捷打开及附件仍待接入。cc-yxi正在修复上一头e32cae6的编译反馈；本轮侧栏尚未验证，未替换试用包。

## 第一百三十轮：项目文件、对话链接与提问答复

- Codex任务增加项目文件列表，复用SFTP浏览/上传/下载入口，点击文件进入任务侧栏；Markdown对话中的相对/绝对路径链接打开远端文件，常规网页链接使用系统打开。
- 接入item/tool/requestUserInput选项及文字答复，按问题ID提交；秘密输入遮挡且不进入聊天草稿，未选答案不默认提交。其他未知请求继续明确提示未支持。
- 编译Agent定位e32cae6中的autoDispatch setter JVM重名，014b43f最小修复已审阅并准备合入；其编译通过证据仅覆盖e32cae6+修复。当前新增功能仍待远端定向验证。

## 第一百三十一轮：运行过程卡片与审批上下文

- 对话时间线新增命令执行和文件改动卡片，显示进行中/完成/失败/拒绝；展开可看命令目录、输出和退出码，文件改动可查看差异并打开文件。
- 文件/命令审批按itemId关联过程卡片，默认展开对应内容，避免仅看到笼统批准按钮。命令长输出明确标注仅保留末尾64Ki字符，完整输出仍以服务器为准。
- 历史恢复同时读取这些过程项；流式命令/文件输出更新对应卡片。实现依据hk13现有Codex导出的ThreadItem schema。未运行真实命令/模型或本地测试，定向检查继续交Agent。

## 第一百三十二轮：对话阅读位置

- 新回复自动跟随末尾；向上滚动阅读历史时暂停跟随，显示“回到最新消息”。消息与审批使用不同列表键，避免相同原始ID造成Compose列表键冲突。
- 鼠标滚轮、拖动及任务切换纳入跟随状态处理，文件列表打开时不抢占滚动；此轮为交互实现，待Agent验证。
- 编译Agent已回报90c9c58单次编译成功（仅编译证据，非功能验收）。用户现有试用包保持3152af0。

## 第一百三十三轮：Codex 图片附件

- 增加图片选择/剪贴板粘贴、上传进度、失败重试和移除；按任务保留内存附件草稿并纳入退出提示。上传沿用现有SSH附件管线，切换页面不取消上传；上限10张。
- PNG/JPEG/WebP上传成功后才能连同正文加入持久队列；允许仅图片输入。turn/start和turn/steer使用localImage服务器路径，投递状态变化前校验格式，不降级为纯路径文本。
- 待发送附件不运行过期清扫。普通文件附件、历史图片缩略图及Ctrl+V快捷键尚待接入；当前按钮可粘贴截图。未运行真实模型或本地测试，待pilot模拟协议检查和UI Agent集中验证。

## 第一百三十四轮：语音、提示词历史与输入快捷键

- Codex任务复用现有语音输入弹窗与任务提示词历史，识别/选择结果先进入对应任务草稿，由用户确认入队。
- Ctrl+V粘贴图片（普通文本粘贴保持输入框默认处理）；Ctrl+Enter加入队列，中文输入法仍在组合文字时不触发。按钮与快捷键共用上传完成检查和持久队列入口。
- 语音服务、真实麦克风和Windows输入法仍需真实环境验收；中枢未运行测试。新试用包尚未生成。

## 第一百三十五轮：按任务编号恢复

- 新增恢复入口，可从当前服务器的已有Codex任务编号恢复历史、持久登记并继续对话，不创建替代线程、不自动发送指令；已有本地登记直接复用。
- 新建后登记失败保留的编号可填入恢复入口；对话提供复制任务编号，便于换客户端后恢复。校验服务器返回的线程ID与绝对工作目录，跨服务器登记继续隔离。
- cc-yxi回报8834380单次编译成功；插件/通知Agent完成定向协议和Linux UI流程，托盘气泡/点击跳转未验证。新恢复入口交空闲logto Agent做隔离定向检查，不重复pilot现有协议套件。

## 第一百三十六轮：Codex任务整理

- 新增按名称/项目路径搜索、显示名修改、置顶及本机归档/恢复，复用现有持久导航记录；不删除服务器线程，不停止正在进行的轮次。
- 任务名限制宽度并省略，菜单承载整理动作。修正项目文件/复制编号被放入新建表单的问题，移回已选择任务的工具栏，消除未选择任务时访问编号的路径。
- 未跑本地验证；集中编译交cc-yxi，新功能按已有UI验证任务覆盖。

## 第一百三十七轮：Codex模型选择

- 新增服务器model/list分页读取和模型/思考强度菜单，不写死模型目录；列表读取失败明确提示，可重试。沿用任务模型选项表示不覆盖运行器当前选择，不宣称恢复服务器初始默认。
- turn/start携带显式model/effort，提交时固定本轮选择；turn/steer沿用当前轮次。UI说明用于之后发送的轮次，已有队列将在发送时使用当前选择。
- 线路设置入口接现有Routes页。第三方provider配置仍沿用服务器现有配置，本轮不宣称已实现运行中app-server热切provider。模型选择状态为当前连接内设置，重连后沿用运行器已保存的任务模型。
- 依据服务器CLI导出v2 schema，未真实调用模型或本地测试；协议签名变更需pilot同步后做定向模拟检查。

## 第一百三十八轮：新对话直达Codex工作台

- 左侧新对话选择Codex后可进入对话工作台，带入服务器、目录及提示词；提示词保留为可编辑草稿，创建后仍由用户确认入队。
- 保留现有终端启动入口，独立工作树和协作组创建继续走原有支持路径，不把不支持的配置静默丢弃。本轮仅前端入口，冻结的运行器协议未改。
- 已有任务的侧栏不会遮住新建表单；UI检查交既有Agent批次，不要求逐提交重跑。

## 第一百三十九轮：任务整理记录读取失败保护

- 修复WorkspaceNavigation读取失败后仍允许重命名/置顶/归档写入的问题：读取原文件及备份均失败时保留错误并拒绝后续覆盖，不再用空内存状态覆盖旧整理记录。正常读取/备份恢复不受影响。
- 协议Agent任务范围收紧为已有用例优先执行；恢复专项由logto承担，避免重复写整套Workspace测试。未跑本地测试，损坏记录后点击整理操作的检查留给验证清单。

## 第一百四十轮：第二份Windows候选包

- accfaf5的Windows目标jar由cc-yxi构建，本机传输完成；打包脚本生成app-image及Setup，未运行Smoke、未安装或发布。SHA256为8d37e4018f7440f983d4d56479bb476366997c33a81197fd7d88912446561d04。
- 本机交付目录：父工作区artifacts/windows-accfaf5/package；Start-Yxi-Trial.cmd使用独立trial-profile。保留旧3152af0包。Setup未签名，新包未做Windows实机启动验证。
- 通用协议30项有阶段性通过记录，但消息合并首轮失败原因仍待Agent最终说明；不将候选包或编译成功视为整份PRD完成。恢复专项4项范围见验证汇总。

## 第一百四十一轮：Codex侧栏宽度调整

- Codex任务页补齐与原工作区一致的可拖动分隔线，按显示密度换算并保存预览宽度；窄窗口/展开模式不显示分隔线。
- 保存失败使用工作区错误提示，避免持久化失败无反馈。实现未重新打入accfaf5候选包；待下一次集中编译/UI检查，不改变协议测试基线。
- PRD未定义的“三个月版本”与“Laize”已向用户发出澄清问题，现有功能继续推进，不据猜测发放权益或换启动命令。

## 第一百四十二轮：Codex项目Git差异

- Codex任务工具栏接入现有GitChangesPane：使用该任务的服务器及目录显示已暂存/未暂存差异，文件可打开到右侧，差异引用进入对应任务草稿并返回对话。
- 查看改动时不触发对话自动滚动；原有Git状态读取和操作范围未变。仅界面接线，未更改冻结的协议代码，未重打候选包；下一批集中检查覆盖该入口。

## 第一百四十三轮：Codex待处理任务提示

- 任务列表显示等待答复、投递待核对、运行中及本地排队状态，新增“待处理”筛选便于找到审批/未知投递任务；状态来自实际控制器和持久队列，不从屏幕文字猜测。
- 同时委派pilot在隔离空配置下用实际Codex命令检查新建空线程的read/resume兼容性，仅握手与线程元数据，不发送模型请求；补足模拟测试不能证明的兼容性边界。

## 第一百四十四轮：真实CLI新建空线程兼容修复

- pilot用Codex CLI0.153.4、临时HOME/CODEX_HOME和空凭据执行元数据流程，发现thread/start返回idle、turns=[]，随后thread/read(includeTurns=true)却报-32601 list_turns is not supported yet；新进程resume后read成功。没有turn/start或模型请求。
- 新建路径现在直接用thread/start权威返回的空闲、空历史快照初始化控制器，不追加这个不兼容的read；已有任务仍按恢复/读取核对。仅ID一致、idle、空turns且本地无指令时可使用新建快照。
- accfaf5候选包包含旧路径，可能新建后提示读取历史失败；已登记任务可从任务列表再次连接恢复。修复尚未打入包，需下一候选版；不能因模拟30项通过忽略真实CLI错误。

## 第一百四十五轮：修复候选包8035b87

- cc-yxi构建8035b87成功；Git二进制增量补丁约1.1MB，本机对旧jar副本应用，重建SHA256与远端完整jar一致：3dccce6b0938d4757b29fd529a6ac291b7f4be638a8f1434dc8537e73cabf870。
- Windows app-image和未签名Setup已生成，交付父工作区artifacts/windows-8035b87/package/Start-Yxi-Trial.cmd。独立配置，旧包保留；未执行Smoke、未启动应用或安装发布。
- 新建空线程兼容修复已包含，定向检查仍在Agent侧；不宣称真实模型/Windows全部验收完成。

## 第一百四十六轮：线路页纳入Codex对话影响范围

- 从Codex任务进入线路页默认选择Codex，并可返回原入口；修复之前可能显示Claude配置、返回终端工作区的问题。
- 配置确认列出同主机的已登记Codex对话，明确暂停自动队列及需重新打开核对生效。直接应用和延后应用均把活动轮次/审批/未确认投递纳入等待条件，不仅看tmux会话。
- 仍不宣称运行中provider热切换或旧thread提供方已改变；真实生效需后续适配/核对。本轮未运行测试，定向检查交Agent；未重打8035b87候选包。

## 第一百四十七轮：待回答问题的草稿保留

- 问题答案改为跟随控制器中的待处理请求保留，滚动离开卡片或切换文件/网页侧栏不再丢失未提交答复；请求被解决、提交成功或控制器关闭时清除。
- 答复仅驻留内存，不写聊天草稿/持久文件；秘密输入仍遮挡。定向检查留Agent，不要求增加全套回归；候选包未更新。
- UI Agent当前临时fixture失效后的操作标未测，要求停止坐标校准并交已有证据，不扩大验证。

## 第一百四十八轮：线路配置后的新任务入口

- Codex线路页可直接以当前服务器配置准备新任务，带入原项目目录，保留旧线程和草稿；明确需要先应用配置，新任务不自动继承原对话。
- 与左侧新对话共用prepareCodexTask，避免主机/目录传递出现两套行为。只准备创建表单，不自动创建或发送，不宣称旧任务已热切提供方。
- 延后线路切换尚未完成时入口禁用，避免误用旧配置。未重新打包，相关入口随下一批集中检查。

## 第一百四十九轮：Windows1.3.0已上线

- 用户明确要求尽快上线后冻结5426c28，GitHub正式Windows流水线35440483403全部成功。官网更新清单已切到1.3.0，安装包HTTP200；发布时间2026-09-19 19:48北京时间。
- 发布先放版本化包及Setup，最后原子替换更新清单，保留旧包和1.2.0回滚备份。完整包SHA256为7C8A44A7A5DE80B43DE02DC650AA6AFE8DED2D548E3DBFBB56D57C878AF6A226。
- 本轮上线目标已完成；整份PRD仍有已知滚动问题、提供方热切换、业务定义及未验场景，未将长期目标标为完成。详情见windows-1.3.0-release.md。

## 第一百五十轮：上线后滚动问题修复

- 按UI Agent报告修正回到最新落点：滚向末尾占位条时增加一个视口偏移，让布局钳制到真实内容末端，避免变高Markdown/展开卡片使落点偏短。
- 回到最新按钮依据实际是否仍可下滚显示，不再仅依赖followLatest标志；点击直接滚动。到达实际底部重新挂跟随，不再要求恰好采样到isScrollInProgress。
- 改动仅开发分支，未替换已上线1.3.0。后续只做该交互的定向检查，未在本机运行验证。

## 第一百五十一轮：Codex普通文件附件

- Codex附件入口支持文档/代码等普通文件上传，沿用上传进度、重试、移除和持久队列；PNG/JPEG/WebP仍使用localImage，其余文件通过独立text输入提供JSON编码的名称与服务器绝对路径，明确内容未内嵌。
- UI说明普通文件由Agent按服务器权限读取，不宣称所有格式已被解析。相对/控制字符路径仍在投递前拒绝；已有负例更新为非法路径，普通文件不再作为“不支持扩展名”拒绝。
- 未运行本地测试；定向序列化与队列检查交pilot。新功能不改变线上1.3.0，后续集中出包。

## 第一百五十二轮：问题答复纳入退出草稿保护

- Agent提问中已输入但尚未提交的答案计入退出草稿提示，空答案不计数；请求完成/控制器关闭后的清除会同步更新状态。
- 仅统计是否存在答复，不把内容或秘密字段写入日志/磁盘。复用已有退出弹窗，未增加新的确认层级。未运行本地测试，线上1.3.0不变。

## 第一百五十三轮：模型选择与运行器报告区分

- 模型栏新增运行器最近报告的provider/model，来源为thread/start或读取结果；选择菜单仅改变后续请求参数，不自行改写“最近报告”。缺少字段不伪造默认模型。
- 接收model/rerouted通知时展示运行器调整结果，旧读取响应不覆盖更新的模型通知。此处只展示运行器报告，不验证第三方底层模型身份、不触发自动线路切换。
- 未运行本地测试，合入下一批定向检查；线上1.3.0不变。

## 第一百五十四轮：按任务保留阅读位置

- Codex对话滚动位置与跟随开关移入AppState，按服务器/线程生成的任务键隔离，切任务、切线路页、展开预览后返回可保留本次应用内的阅读状态。
- 正在阅读历史的任务返回后不强制跳到最新；主动开启跟随的任务保持跟随。仅保存内存UI状态，不增加磁盘记录或复制消息内容。
- 未运行本地测试，留下一批UI定向检查；线上1.3.0不变。

## 第一百五十五轮：真实目标状态条

- 按服务器CLI schema接入thread/goal/get与updated/cleared通知，显示已有目标的objective、实际状态、服务器累计耗时/token用量/可选预算；与本地指令队列分开展示。
- 无目标不显示空目标卡；读取失败明确提示可重试，不阻断对话。较旧读取响应不覆盖新通知，不用本地计时伪造后台进度。
- 本轮只读已有目标；创建/暂停/继续/停止的执行语义仍待运行器适配确认，不放空操作按钮。未运行本地测试，定向协议检查交Agent，线上1.3.0不变。

## 第一百五十六轮：输入历史保留执行结果

- 历史区区分已接收、进行中、完成、失败和中断；失败原因与中断说明写入对应指令记录，重开应用仍可查看，不把失败轮次仅标为已确认。
- 完成通知早于接收响应时，暂存事件保留失败原因；本轮完成凭据与接收凭据仍分开。
- 仅附件的输入也进入历史，可按附件名搜索；复用文字按钮在无文字时禁用，不假装已重新附带文件。未运行本地测试，待下批定向检查，线上1.3.0不变。

## 第一百五十七轮：本轮额外权限申请

- 按CLI导出PermissionsRequestApprovalResponse/ServerRequest接入item/permissions/requestApproval，完整展示所申请权限，由用户明确选择仅本轮授予或不授予。
- 响应固定scope=turn，不提供会话级永久允许，不自动批准。未知权限字段不开放允许按钮；其他未知请求仍保留原来的未支持提示。
- 本轮未运行本地测试，权限响应形状及拒绝路径留Agent定向检查，未发布到线上1.3.0。

## 第一百五十八轮：桌面会员/余额券兑换入口

- 根据W07已有盘点，补上“我的”页面兑换码卡，使用现有POST /api/me/redeem及账户会话代次保护，不新增权益规则或自动赠送。
- 保留连字符，仅去首尾空白并转大写，支持后端4–40位契约；重复兑换/撤销/余额券结果分开表述，成功后刷新账户。账号变化时不把旧回复显示到新账号。
- accountRequest白名单仅新增精确的redeem POST，不扩大到整个/api/me。未调用真实兑换接口、未授予任何生产权益；后续由logto Agent用假响应核对。钱包/订单仍是独立剩余项。

## 第一百五十九轮：置顶通知过滤与单任务静音

- 设置增加只提醒置顶任务，未置顶任何任务时保持全部提醒；任务菜单增加静音/恢复，使用现有持久导航记录，不按易复用会话名保存。
- 终端任务完成/待输入/审批通知携带稳定任务键，先应用全局模式与任务过滤，再设置点击目标；点击只定位同一任务实例，不回落到同名新任务。无托盘时不登记虚假点击目标。
- 本轮接入旧终端任务通知；Codex原生对话通知接线仍需补齐后再集中发布。未运行本地测试，线上1.3.0不变。

## 第一百六十轮：Codex后台通知接线

- Codex控制器的实时完成/失败/中断事件及新待处理请求接入通知，与页面是否选中无关；历史reconcile不补发完成提醒，同一控制器内终态通知按轮次去重。
- 使用稳定任务键共用置顶过滤和单任务静音，点击通知定位所属服务器上的Codex任务；已不存在的任务不回落到其他会话。通知仅展示任务名，不把审批正文或秘密字段放入托盘。
- 未运行本地测试，后台事件与过滤按下一批最小模拟检查；线上1.3.0不变。

## 第一百六十一轮：信箱筛选与保留期限提示

- 增加全部/未读/已读筛选，未读数量取服务器计数；明确筛选的是已加载邮件，分页未完时保留继续加载入口，不把当前页空结果宣称为全局无未读。
- 正在阅读的邮件不会因标已读立即从未读视图消失；领取后同步本地已读标记，计数仍以响应为准。
- 显示邮件剩余保留天数，临期且有兑换码/待领取附件时提示；提示先保存兑换码，不把邮件保留期限等同于兑换码有效期。未解析的时间仍保留原始到期字段显示。
- 未运行真实账号或本地测试，下一批检查只覆盖筛选/日期纯逻辑；线上1.3.0不变。

## 第一百六十二轮：钱包与订单读取

- “我的”新增钱包与订单页，复用账户余额和自动续费状态，调用安卓版已有GET /api/shop/orders展示订单与兑换码复制；账户切换沿用owner/generation隔离。
- 余额保持分单位换算，负余额正常显示；不增加充值入口、不发起购买或真实扣费。刷新失败与空订单分开呈现，不把接口错误当作无订单。
- 本轮先接只读钱包/订单；自动续费修改及商城购买仍待后续接入。未访问真实订单、未运行本地测试，线上1.3.0不变。

## 第一百六十三轮：自动续费设置

- 钱包页接入安卓版现有PATCH /api/me/wallet，开关只在服务器返回明确autoRenew后更新；展示到期扣余额的含义和服务器当前报价，无报价时不开放开启，但允许关闭已开启状态。
- 失败/网络不确定不自动重试，提示刷新核对；账户回写仍按owner/generation保护，余额保持分单位与负数语义。
- 没有调用真实续费接口、没有实际扣费或修改用户设置；本轮仅实现产品入口。待logto Agent假响应核对，线上1.3.0不变。

## 第一百六十四轮：兑换结果确认文案

- 修正静态检查指出的空2xx回复兜底成功：没有可识别结果字段时显示未确认，不宣称已增加权益；兼容服务器msg、重复/撤销标记与旧会员tier字段。
- 解析失败和网络失败统一建议先刷新账户核对，不把缺字段错误一概描述成断网。未调用真实兑换；该改动留开发分支。

## 第一百六十五轮：商城请求与购买身份基础

- 按安卓版Shop接口实现商品目录/购买回复解析，价格和余额只取服务器分单位字段；余额不足明确展示差额，结果缺关键字段不宣称购买成功。
- 新增本地购买请求记录，发送前可落盘owner/itemId/requestId/报价；同账号未确认请求未解决前不得新建购买身份。记录不含兑换码或凭据，读取损坏时保留原文件并拒绝覆盖。
- 本轮仅基础接口和持久状态，尚未接入购买界面或真实账户请求，不代表商城闭环已完成。没有购买或扣费，线上1.3.0不变。

## 第一百六十六轮：商城购买界面接线

- 钱包接入商城目录、余额、商品期限和服务器价格，购买前明确确认商品/金额；发送前重新读取目录，变化则要求重新确认，之后先保存请求编号再发购买请求。
- 未确认请求按账号保留，可用同编号显式核对/重试；不会自动购买或重试。用户核对订单后可结束本机重试，说明不等于取消服务器订单或退款。
- 购买成功展示服务器订单/码/实际扣款并更新余额；同编号重放明确不重复扣款。运行中的请求纳入退出保护，账号切换沿用会话代次守卫。
- 未调用真实购买、未扣任何余额；后端接口不提供原子报价锁，本地目录复核不能等同于服务端锁价，异常金额会明确提示。未运行本地测试，待Agent假响应核对；线上1.3.0不变。

## 第一百六十七轮：输入历史保留设置

- 设置提供一直保留/3/7/30天，默认一直保留，不替用户删除已有内容。选择期限后按最后状态更新时间清理已取消、人工处理或有明确终态的记录正文、附件引用和详细回执。
- 保留ID、任务、最终状态及内容摘要用于幂等比较；相同ID/内容仍命中原记录，不因清理再投递。待发送、进行中、Unknown、仅确认接收但无终态、无时间旧数据保留。
- 启动及每小时按用户设置处理，常规备份同步轮换；备份更新失败提示并保留重试标记。服务器转录、远端附件和诊断损坏副本不删除。
- 未运行本地测试，核心去重/状态保护需Agent定向核对后再发布；线上1.3.0不变。

## 第一百六十八轮：终端对话缓存基础

- 新增最近6个任务的进程内转录缓存，保存解析器、已解析条目/上下文和已提交字节位置；按稳定任务键接入，文件身份另行核对。
- 读者代次令牌阻止旧页面的延迟解析污染新读者；解析状态与续读位置一起提交，不用仅接收但未解析的位置做缓存断点。
- 本轮仅缓存基础，ChatPane接线尚待完成，不宣称已减少网络读取。未运行本地测试，线上1.3.0不变。

## 第一百六十九轮：终端对话增量缓存接线

- ChatPane接入最近6个稳定任务实例缓存，返回时先显示已解析内容，再核对当前转录路径和长度，从缓存提交位置尾随增量；无稳定实例ID时不复用缓存。
- 文件更换或长度缩短会换解析器、清旧缓冲并重新定位尾部。批次携带缓存实例/读者代次，过期解析不会覆盖新文件或新读者；缓存位置只随解析提交推进。
- 不新增后台常驻转录流，离开页面仍取消当前流，保留纯内存缓存。未运行本地测试，交Agent做最小缓存/续读检查，线上1.3.0不变。

## 第一百七十轮：Windows系统语音回退入口

- 语音弹窗增加Windows语音模式，聚焦同一草稿编辑框，用户按Win+H使用系统听写；与服务器录音模式明确分开，未处理服务器录音时不能切模式。
- 明确系统语音需要联网/系统权限，不称离线识别；不经过SSH服务器，也不自动发送给Agent。沿用草稿编辑/复制/加入输入框流程，离线SSH也可打开Codex任务语音弹窗。
- 官方依据：https://support.microsoft.com/en-us/accessibility/windows/use-voice-typing-to-talk-instead-of-type-on-your-pc 。本轮未启动系统听写或访问麦克风；Windows文本输入兼容性需用户实机验收。独立离线ASR模型仍未实现。

## 第一百七十一轮：收藏启动入口

- 终端任务菜单可收藏主机/目录/运行器，任务不在线后在同主机侧栏“收藏·未启用”保留入口；离线先连接，点击会刷新确认原实例是否仍在线，在线则打开，否则预填新建表单。
- 新会话创建成功后，收藏绑定以一次导航写入迁移到新实例；原任务记录和队列不迁移。明确说明新会话不自动恢复旧对话/协作组，不冒充会话续接。
- 既有协作组编辑继续使用服务器Groups协议，本地收藏不另造服务器分组表。未启动真实会话或本地测试，线上1.3.0不变。

## 第一百七十二轮：可配置本机离线ASR

- 语音弹窗新增本机离线模式，配置已有whisper.cpp CLI及模型；复用录音和编辑草稿，不经SSH、不自动切换到云服务。程序/模型路径可保存，模型与程序不随本次代码自动下载。
- 通过参数数组直接启动进程，WAV转换为16kHz单声道16位；取消/超时终止本次进程，尝试删除本次临时音频/结果/日志，内存音频副本清零。失败保留原录音供用户重试。
- 依据官方 https://github.com/ggml-org/whisper.cpp 与 examples/cli/cli.cpp 的-m/-f/-l/-otxt/-of参数。未在用户电脑安装模型、未录音或运行识别；路径/输出/取消用模拟CLI交Agent检查，真实麦克风和模型由用户后续验证。
- 这是需本机配置的离线路径，不宣称开箱即用或所有模型兼容；线上1.3.0不变。

## 第一百七十三轮：Windows1.4.0已上线

- 冻结d8b343a，正式CI35446691527构建和安装启动流程全部成功。产物由hk13后台断点续传取得，完整文件902853012字节；先发布包和Setup，最后原子切换更新清单。
- 2026-09-19 21:59北京时间公网清单回读1.4.0且完整包SHA256一致，安装包版本链接HTTP200。旧1.3.0备份及历史包保留。
- 该批已实现功能上线；未解决业务定义和未覆盖的实机场景继续按1.4.0范围文档列出，不将整份PRD目标标为完成。

## 第一百七十四轮：同线程重连配置参数基础

- 根据CLI导出ThreadResumeParams与ConfigReadParams补上恢复时的modelProvider/model/effort覆盖，以及按项目目录读取有效配置字段。
- 仅提取提供方、模型和思考强度，不记录完整配置响应或凭据。旧resume调用保持无覆盖参数的原行为。
- 尚未接UI，不宣称已完成同线程切线；交Agent隔离配置检查同ID恢复和提供方返回，禁止turn/start与真实模型调用。

## 第一百七十五轮：项目标题菜单
- 项目标题新增更多菜单，展示服务器、完整路径、任务/运行中数量，并复用服务器协作组与组规编辑入口。
- 编辑项目可持久保存本机显示名称，空值恢复目录名；折叠状态写入保留名称等其他项目字段。
- 未在本机运行验证，交hk13 Agent集中检查；本批尚未发布。


## 第一百七十六轮：项目内新建任务
- 项目标题增加新建按钮，更多菜单同步提供新建任务；预填当前项目目录，复用Claude/Codex运行器选择和独立worktree表单。
- 实际CLI检查确认纯元数据新线程尚无rollout，无法跨进程恢复；同线程切线仍待有历史线程验证，不据协议形状宣称动态生效。


## 第一百七十七轮：项目搜索联动
- 侧栏搜索支持本地项目名称；同名项目用路径区分。菜单数量取当前服务器完整会话快照，避免搜索结果数量冒充项目总数。


## 第一百七十八轮：恢复历史缺失提示
- 根据hk13实际CLI检查，已有任务打开与按编号恢复统一解释no rollout found，保留编号并提示确认原服务器/账号；不自动创建替代任务或重发原队列。
- 同线程切线的动态生效仍未证明，本轮没有把该能力标记完成。


## 第一百七十九轮：区分当前连接配置与线程历史
- hk13本地假Responses服务证实有历史同ID恢复可切provider，resume顶层返回新provider，而thread元数据仍保留原provider。后续请求命中新provider回环端口。
- 新建/打开/恢复保留顶层modelProvider和model，界面独立显示本次连接配置，历史读取不覆盖它。此改动位于1.4.1冻结之后，不混入该安装包。


## 第一百八十轮：同一对话应用当前线路
- 对话新增显式应用入口：空闲且审批处理完后暂停自动发送，核对已有历史，重建连接读取目录有效配置，按同线程编号恢复并显示顶层当前配置。
- 草稿/附件/队列键不变；状态待确认或无历史任务不关闭重连，失败显示错误且不自动重发。未发布，交Agent定向检查。


## 第一百八十一轮：线路读取失败保留原连接
- 先建立候选连接读取有效配置，成功后才关闭空闲原连接；配置读取失败时原对话继续可用。
- 线路保存提示指向同对话应用入口，保留新建任务选项，不再一概要求丢开原对话。


## 第一百八十二轮：插件结果人工核对与账号身份
- 插件操作新增服务器锁下review回执，保留原记录；缺失回执写墓碑阻止迟到原请求。客户端记录为人工已核对，不冒充安装成功，界面提供明确确认入口。定向检查交cc-logto_yxi。
- 我的页面区分Yxi账号、当前服务器SSH身份、当前Codex连接提供方；按所选服务器匹配任务，认证状态未查询不冒充已登录。
- /api/me明确401后续期并重试一次，再次401才按generation和同access退出；网络故障保留登录，轮换仍同步。交Agent核对并发与过期响应。
- 本轮均在1.4.2冻结之后，尚未发布。


## 第一百八十三轮：插件恢复定向证据
- cc-logto_yxi隔离HOME和假CLI的29项服务端检查通过，含锁下人工核对、missing墓碑、迟到原请求拦截、已完成记录不改和busy拒绝。
- 客户端ledger新4例与既有3例通过，测试已合入；人工核对状态持久化、解除阻塞且不被迟到unknown降级。认证查询和账号401处理仍在单独检查。


## 第一百八十四轮：协作回复入口
- 已有事件按原接收实例和当前组匹配，生成可编辑用户要求并入待发送队列；未假报Agent已回复。hk13静态接线与编译通过。
- 入队后关闭协作弹窗直达目标任务；取消则回原历史页。消息卡片默认展示，旧日志保留切换。该批尚未发布。


## 第一百八十五轮：用户回信收件视图
- 开发分支增加回给用户筛选和数量，识别user-inbox阶段为回信已保存，不等同已读。查看原消息时退出收件筛选，避免原用户消息被隐藏。
- reply-user服务器通道交cc-logto_yxi隔离分支实现与定向检查，不改已发布1.4.4、不发送生产消息。桌面展示先按约定事件字段接线。

## 2026-09-22 当前交付状态（优先于以上历史快照）

官网公开 feed 本轮复核为1.4.11；冻结5d9fa2e，CI35640436282成功。详细产物及校验见 windows-1.4.11-release.md。后续回退及模拟器开发提交尚未发布，不以开发分支版本号推断线上内容。

| 用户要求 | 当前证据与未完成部分 |
| --- | --- |
| 更新保留登录与服务器 | 1.4.10迁移到Windows用户目录.yxi，保留旧文件，含失败恢复与Yxi自生成SSH密钥路径更新；候选迁移9例通过。真实升级后用户体验尚待反馈，不保证服务端过期令牌仍可用。 |
| 对话同步及时完整 | 1.4.9发布增量显示、字节续接、纯转录流、重复key修复、底部跟随；首次仅最近400行，未实现完整历史分页。 |
| 自动接续排队、调整方向 | 1.4.8起自动接续，1.4.9含任务级开关。侧边聊天入口未实现；Claude调整方向仍是终端适配，非原生turn/steer。 |
| 清爽配置/头像/插件分类 | 新服务器+运行器+供应商首页、头像全图标、本地/服务器插件分类、移除底部配置/我的行已随1.4.11发布；真实Windows视觉与交互仍需验收。 |
| 多运行器供应商配置 | Claude/Codex已有配置实现；Gemini用户级编辑随1.4.11发布，但多供应商CRUD未完成，OpenCode/Grok Build/Hermes尚未接入真实应用逻辑。不能以选择chip当完成。代理UA/header/body等用户明确功能仍未完成。 |
| 对话界面精确回退续聊 | 开发线已接入一键入口、原图片预览与选择，并通过真实 Claude 2.1.278 + SSH 的文字连续回退、图片保留/全部移除、运行器恢复核验、指定阶段断线恢复和不重复发送测试；Read 结果与增量/冷加载分支通过。Compose 编辑组件渲染、输入、按钮、图片预览/删除已验证。Windows 全界面联动、首轮、非图片附件、Codex 和更早中断阶段自动恢复仍未完成。开发改动未发布；详见 windows-rewind-verification.md。 |
| 一键Android模拟器 | 开发线有环境发现、进程启动停止、日志、硬件加速检查、指定设备安装APK/启动包、官方工具包下载界面。SDK组件/镜像安装、AVD创建并启动已进入发布后的开发线；尚未完成真实Windows首装验收。项目构建运行、内嵌画面与Agent工具桥接仍未完成。 |
| 网页单击侧栏/Ctrl外部 | 1.4.9发布链接路由；中文标点及www裸URL修复已随1.4.11发布。 |
| 自动Agent协作完整流程 | 既有消息通道不等于自动协作引擎；轮次预算、自动调度与循环终止闭环仍未全部完成。 |

开发主线保留完整PRD范围；模拟器、运行器适配及精确回退不是后续发布时可自动删去的需求。隔离修复版只限定该包的内容，不缩减总目标。

资源维护：确认1.4.8/1.4.9 deployment-result与回滚备份完整、下载ZIP无进程占用后，仅删除这两版stage下artifact.zip，释放1,807,249,518字节。对应release-files、官网版本包、回滚目录及部署记录保留。清理后hk13磁盘可用约7.5GiB，内存available约15GiB；继续错峰构建，不清业务目录。

后续资源维护：磁盘降至约5.4GiB可用时，逐一核对1.4.10-run35620405512与1.4.11-run35640436282的下载完成记录、发布结果、release-files中安装器与完整包SHA256、对应backup-complete，并通过fuser确认下载ZIP无人占用。仅删除这两个stage的artifact.zip，释放1,809,188,135字节。发布文件、回滚备份、部署证据和业务目录均保留。缺少原始下载ZIP是本次有意清理，不应据此重启旧下载任务。
