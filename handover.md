# handover · Yxi

## Windows 工作台 PRD 实施中（2026-09-13，独立分支）

工作方式更新：用户明确要求以功能实现为主，不再主动运行测试、交叉验证或专项检查。后续需要验证的内容写入design/windows-manual-verification.md，由用户自行验收、反馈问题；不要继续扩展边界验证。

第七十四轮：终端发送增加服务端指令摘要登记与完成记录，核对弹窗可查询；重复 ID 不再写入终端。终端写入不等同运行器回执，未运行验证。

第七十三轮：保存右栏拖动宽度，网页与文件面板加入展开和恢复分栏。未运行验证。

第七十二轮：文件右栏增加 Ctrl+H 查找替换、替换选中项、全部替换和单次撤销；编辑器 Ctrl+A 限定正文焦点。未运行验证。

第七十一轮：增加整页反馈弹窗和页面上下文引用，保留未加入的文字并纳入关闭提醒；元素反馈增加项目和视口/主题设置。未运行验证。

第七十轮：指令条增加行内撤回与撤销、更多菜单、下移和移到待发送首位；保留附件与原指令身份，仅修改本地队列，不自动发送。未运行验证。

第六十九轮：增加 Ctrl+K 与侧栏任务切换入口，跨已加载会话搜索任务、项目路径、服务器和地区，支持置顶优先与包含归档。未运行验证，步骤已加入手动验收文档。

第六十八轮：语音文字增加复制、清空及撤销清空；编辑框清空后仍保留，避免删完后无法继续输入。未运行验证。

第六十七轮：语音支持多段追加，识别失败保留当前录音供手动重试或丢弃；识别成功及关闭时释放录音，未用录音纳入退出提醒。未运行验证。

第六十六轮：语音窗口加入服务器安装指引，可导出包含yxi-asr及独立安装脚本的zip并复制命令；用户上传后自行执行，应用不自动安装或改Agent钩子。未运行验证。

第六十五轮：语音输入增加麦克风选择和刷新，选择保存到本地偏好；指定设备缺失时提示重选，不静默改用默认设备。未运行验证。

第六十四轮：桌面语音输入接入Java Sound录音、当前SSH服务器yxi-asr转写、可编辑结果及加入草稿；不自动发送。录音120秒上限，关闭释放设备，录音/识别和待用文字纳入退出提示。未运行验证；本机离线ASR与服务安装引导仍待补齐。

第六十三轮：网页尺寸和明暗控制收拢为默认折叠的预览设置，显示当前设置摘要并提供恢复默认，减少工具栏占用。未运行验证。

第六十二轮：网页预览加入页面查找、大小写选择、前后匹配及Enter快捷操作，使用内嵌浏览器查找能力，关闭时清理高亮。未运行验证，验收步骤已追加。

第六十一轮：网页预览增加系统/亮色/暗色偏好切换，使用prefers-color-scheme模拟，不修改源码或Yxi主题。页面需自身支持对应样式；切换后旧元素选择过期。未运行验证。

第六十轮：网页预览加入自适应/桌面/平板/手机视口，通过浏览器尺寸覆盖并缩放至当前面板；切尺寸后旧元素选择标为过期。仅响应式尺寸，不模拟手机UA或触摸。未运行验证，验收步骤已追加。

第五十九轮：文档右栏加入查找/Ctrl+F、上一个/下一个和行号提示；定位时切源码、选中文字并滚动到位置，不改正文。搜索框Ctrl+A不再误选全文。未运行验证，验收步骤已追加。

第五十八轮：服务器增加地区标签，编辑、持久保存、选择器/侧栏显示、搜索和导入导出均已接入；仅修改地区不重连。未运行验证，验收步骤交给用户。

第五十七轮：加入当前已加载对话的搜索/定位，工具命中会展开分组；输入历史可展开全文及确认替换草稿。模型/上下文信息移到可换行区域，避免挤压发送工具。未运行验证，步骤已写入手动验收文档。

第五十六轮：输入框增加本任务输入历史，直接复用已有持久指令记录，支持搜索和追加到当前草稿，显示待发/撤回/待确认等状态；不自动发送，不复用旧附件。按用户要求未执行验证，验收步骤已写入手动文档。

第五十五轮：主机菜单接入无认证信息导出、导入预览和重复项跳过；ID冲突分配新ID，不覆盖现有认证。导入的无认证主机点击连接时先打开编辑。新增手动验收文档覆盖完整PRD。按用户最新要求，后续验证交由用户，不再追加测试流程。

第五十四轮：主机恢复弹窗优化有效副本排序/圆角/滚动条，修复短列表空白过高；隔离真实Compose键盘取消、选择、恢复及数据重读通过，截图已查看。143项中134通过、9环境跳过，Linux打包通过。该UI测试使用虚构保护器，不冒充Windows恢复入口或长列表/DPI验收。

第五十三轮：Windows实际exe加入严格隔离host-smoke/host-reopen，两台虚构服务器迁移、别名保存与跨进程重读通过，密码/ID/端口/偏好一致，旧漫游明文清空。干净提交e0ad813构建后六项原生检查全通过，证据tmp/windows-e0ad813-native。恢复UI点击、真实SSH重连和DPI/IME等仍待验收，未替换安装版。

第五十二轮：活动主机密文/备份缺失且存在迁移副本时，阻止读取为空或空写覆盖；侧栏可选择副本，预览不含密码、指纹/目标校验后恢复，不立即连接。142项中134通过、8环境跳过，Windows原生缺失保护与明确恢复通过。完整exe恢复UI、坏活动文件恢复、历史副本元数据及重启仍待验收。

第五十一轮：旧漫游hosts改为直接导入受保护本地存储，main/bak/damaged先留本地加密副本后清空；损坏主文件可恢复备份，冲突/迁移中改写不清理。141项中133通过、8环境跳过，Windows虚构漫游迁移及跨进程验证通过。下一步补活动密文及备份全缺失时的明确提示/历史恢复，避免旧[]标记被当成新空列表；完整exe升级等仍待验收。

第五十轮：Store主机列表接入HostConfigFile，Windows使用独立用途DPAPI，当前/活动备份密文；旧main/bak/damaged先保存加密迁移副本再清空，失败或冲突不覆盖。139项中131通过、8环境跳过，Linux打包通过；Windows原生虚构数据迁移、跨Java进程读取、用途隔离/保存通过。完整exe升级、旧漫游副本/冲突、多主机重启及外部私钥管理仍待验收。

第四十九轮：按用户说明将先前退出中断更正为关机影响，撤回应用故障判断和测试软件渲染默认值。发现新fixture被Gradle缓存跳过，已把隔离标识声明为输入；普通桌面137项中129通过、8环境跳过。默认渲染复核虽完成界面步骤但未在时限内退出，未算通过，未归因为正式应用缺陷。下一优先项是W01-A主机密码的系统保护，当前仍未接DPAPI。

第四十八轮：插件历史按主机隔离、最近在前，支持搜索/查询/恢复入口与可见滚动条；拒绝回执不会因查询失败变成未知阻塞主机。桌面测试及打包通过，软件渲染原生滚动/关闭验证通过（fixture.YRl5RH）。用户已说明所讨论的退出中断源于关机，撤回应用故障推断；测试脚本已恢复默认渲染。历史恢复点击、缺失记录对账及Windows专项仍待完成。

第四十七轮：updated/uninstalled可从界面恢复插件包，确认目标及恢复版本，package-restored持久保存并刷新列表。真实安装→1.1.0更新→旧包1.0.0回滚通过，实际文件位于独立恢复目录、CLI和本地重读记录一致；截图已查看（fixture.pwM9BS）。桌面测试/打包通过。历史操作、中断恢复、Windows与路径依赖插件专项仍待完成。

第四十六轮：包回滚底层可从updated/uninstalled副本恢复到私有新目录，核对tar/配置/登记指纹，重新指向原版本并回读确认。真实升级/卸载回滚到1.0.0及归档链接/越界/篡改/配置冲突测试通过。资源打包排除Python缓存，最终jar保留脚本且无pyc。客户端回滚UI、跨文件中断恢复、路径依赖与Windows专项仍待完成。

第四十五轮：安装原生测试继续走到更新：测试市场提供1.1.0，真实按钮确认后回执1.0.0→1.1.0、实际plugin.json与登记版本一致，重读客户端记录仍保留1.1.0。确认/结果截图已查看，fixture.CjtwDX正常退出。仅测试/脚本扩展；精确目标固定、包回滚、完整进程重启与Windows升级专项仍待完成。

第四十四轮：插件卡片新增更新确认，updated回执保存并展示实际前后版本，旧记录兼容、查询失败保留已确认版本。桌面测试/打包与原启停→恢复→卸载原生回归通过（fixture.lWMQ7G）。实际升级UI、精确目标版本、包回滚及Windows专项仍待验证，未升级生产插件。

第四十三轮：升级底层update复用文件/登记备份、指纹和幂等回执，记录实际前后版本。临时市场真实1.0.0→1.1.0升级通过，旧包manifest仍1.0.0、其他插件设置保留，启停恢复回归及桌面打包通过。updated客户端/UI、精确目标版本确认、包回滚和Windows专项仍待接入，未升级生产插件。

第四十二轮：插件卡片接入卸载确认和uninstalled持久终态，成功刷新列表，终态不因查询断网降级。真实UI取消/确认卸载通过，核对目标消失、其他权限保留、包副本与重读账本，截图已查看（fixture.oAB6jd）。桌面测试/打包通过。缺失目录残留清理、包恢复、升级及Windows卸载专项仍待完成。

第四十一轮：卸载底层接入明确scope、keep-data且不prune，先保存配置/登记与有限大小的插件tar副本及hash。真实临时市场卸载后CLI目标消失、其他插件设置/数据保留、副本内容权限校验通过，原启停恢复回归与桌面打包通过。卸载UI/终态、包恢复与Windows专项未接入，未卸载生产插件。

第四十轮：从完整Git归档构建发现gradlew被Windows转换CRLF，新增LF属性修复。0b9f98e干净源码在新目录构建Windows jar，并在本机独立exe通过启动、DPAPI迁移/轮换/跨进程、浏览器像素/40px样式/退出检查。产物tmp/windows-0b9f98e-native，含源码/产物哈希清单；未替换安装版。插件完整Windows交互、DPI/IME/SSH/升级及完整PRD仍待验收。

第三十九轮：目录→范围→核对→取消→确认安装的原生SSH流程通过，核对CLI识别及客户端installed持久状态；目录空状态改卡片并提供主机插件入口。固化脚本加入超时边界。该轮退出中断按用户后续说明更正为关机影响，不作为应用退出缺陷；原始诊断仅保留备查。136项中128通过、8环境跳过，Linux打包通过。Windows/项目范围/远程来源及权限专项继续推进。

第三十八轮：目录页接入安装按钮、范围/项目路径选择及两步目标确认，提交沿用持久操作账本，installed终态与失败查询保留已有结果。成功刷新目录并提示新会话测试，权限/依赖/兼容性仍明确未知。135项中128通过、7环境跳过，Linux打包通过。安装原生点击、manifest权限、失败清理/升级卸载与Windows专项继续推进，未安装生产插件。

第三十七轮：安装prepare/install底层接入目录与配置指纹、配置/登记副本、操作账本和CLI安装后识别。临时本地市场真实安装、重复/查询、已安装拒绝、目录变化及超时不重发通过；启停恢复回归与桌面测试/打包通过。当前CLI安装后从available移除条目，已提前识别同范围已安装；跨范围复用仍待适配。目录UI安装确认/权限、失败清理和包回滚未接入，未安装生产插件。

第三十六轮：配置页增加服务器插件目录浏览/刷新/搜索，展示市场、简介、版本与来源，未知版本不猜测。真实hk13只读采集295条，新增元数据脱敏/格式/指纹测试通过，桌面测试和Linux打包通过。尚未接入安装确认、安装动作及目录原生视觉验收，未安装生产插件。

第三十五轮：原生插件测试扩展到取消恢复→确认恢复，服务器配置逐字还原；重新打开客户端账本确认两条记录且末条restored。结果界面自动刷新并保留插件原有来源错误，不冒充可用。隔离SSH原生测试通过，fixture.lNMman；生产代码无新增改动。Windows/多主机恢复、安装升级卸载继续推进。

第三十四轮：增加恢复操作前插件设置，核对当前after指纹及原副本before指纹，独立记录恢复操作，支持幂等查询和原文件不存在的恢复；界面增加恢复确认。隔离测试覆盖原文恢复、后续编辑冲突、篡改副本拒绝、撤销新建配置。已确认回执不会被网络查询失败降为未知。134项中127通过、7环境跳过，Linux打包通过。恢复原生点击/Windows、外部不加锁编辑器并发CAS及插件包回滚仍待完成。

第三十三轮：用户级插件停用的真实Compose→隔离SSH→Claude CLI闭环通过；取消后配置逐字不变且无操作记录，确认后configured落盘、目标停用、其他权限保留，界面自动刷新为停用。确认弹窗中文范围及结果截图已查看。134项中127通过、7环境跳过，另行原生停用测试通过，Linux打包通过。其他作用范围/启用的UI、多主机/断线恢复与Windows专项仍待完成。

第三十二轮：插件卡片启停和目标确认弹窗已接线；客户端账本先落盘再发出，跨页面接收结果，重启未知不重发，可查询原ID，运行中纳入退出保护。损坏/缺失主记录恢复前写永久待核对标记，防止旧备份丢失操作后重发。新增持久化测试通过，最终133项中127通过、6环境跳过，Linux打包通过。完整原生点击/SSH启停、恢复操作及Windows专项仍待完成，未操作生产插件。

第三十一轮：新增插件启停操作协议prepare/set/status，固定CLI参数和明确scope，配置/安装记录指纹检查、私有恢复副本、操作账本与幂等查询；中断结果未知不重发。真实隔离CLI验证用户启停、project/local停用，其他配置字段保留；另覆盖旧指纹、操作冲突、备份权限和超时重试。桌面测试/打包通过。尚未接入UI及恢复按钮，运行器加载、外部编辑并发和完整W03仍待验证，未修改生产插件。

第三十轮：插件面板增加搜索、记录摘要和滚动圆角卡片，名称/来源分层、路径可复制。真实Compose的820/460宽度截图已核对，刷新按钮点击断言通过，输入zzzz后两种宽度均显示无匹配且旧卡片消失。132项中126通过、6环境跳过，另行原生UI测试通过，Linux打包通过。完整主机往返、Windows/深色专项及插件变更流程仍待完成。

第二十九轮：插件状态增加Claude CLI只读列表核对，按ID/范围/路径/项目匹配，区分未知、未找到、已列出和错误；启停保留三态。独立HOME的真实CLI验证发现已列出仍可能带marketplace错误，已正确保留，未操作生产插件。131项中126通过、5环境跳过，Linux打包通过。实际UI验收、安装变更/恢复流程与Codex适配仍待完成。

第二十八轮：配置页新增主机插件状态卡片与刷新，展示Claude安装记录、范围、目录和用户默认启停；读失败明确提示，目录存在不等于运行器可用。只读采集白名单字段，不返回配置秘密。隔离目录测试与131项桌面测试（126通过、5环境跳过）、Linux打包通过。安装/启停等变更动作、运行器验证、Codex适配及UI/Windows专项仍待完成，未修改生产插件。

第二十七轮：网页预览支持纯文本元素的文字试调，Ctrl+Enter应用，保留空白/空字符串，支持撤销与重置；只修改原文本节点，保留结构和事件，外部更新后拒绝覆盖。反馈草稿分开记录原文/新文与CSS。真实UI确认标题即时变化、反馈入草稿且HTML源文件哈希不变；原生浏览器验证字面HTML、点击事件、撤销与外部更新保护。129项中124通过、5环境测试跳过，Linux打包通过。富文本、源码落实闭环与Windows文字/DPI验证仍待完成。

第二十六轮：样式试调增加系统/无衬线/衬线/等宽字体类型选择，保留原字体信息，复用撤销/重置与草稿反馈。原生验证serif生效、撤销后sans-serif及important恢复；真实UI键盘选择后，草稿核对font-family:serif通过。128项中123通过、5环境测试跳过，Linux打包通过。自定义字体、文字内容试调及Windows字体/DPI完整验收仍待完成。

第二十五轮：服务配置新增GET就绪路径，默认/，旧配置可读并在保存时升级v2。路径变更不重启命令，但旧路径结果不再触发就绪或自动打开；限制同源路径、保留URL编码。隔离测试验证根路径503/健康路径200、实例不变，真实UI用/health/ready检测后自动打开/app。127项测试中122通过、5环境测试跳过，Linux打包通过。自定义认证/TLS、构建版本与非HMR刷新仍未完成。

第二十四轮：开发服务新增Linux监听归属与HTTP根路径检查，核对会话进程树、PID起始时间、socket inode并在请求后复核；支持IPv4/IPv6及映射地址，未匹配监听不发HTTP探测。用户启动后，同实例/配置检查通过才自动打开匹配的回环地址；保留编码路径参数，停止或手动输入取消待打开意图。IPv6原生测试、无手动预览点击的UI流程与125项测试（120通过、5跳过）、Linux打包通过。构建版本、非HMR刷新、代理/容器/TLS就绪仍待补齐。

第二十三轮：右侧开发服务配置/启动/停止/检查/日志/预览入口已接入，配置按项目保存，Windows沿用DPAPI文件保护。查询/停止不再携带启动命令；操作失败提示不会被轮询抹掉。修复轮询期间点击启动被忽略，改为单个变更操作等待检查完成。隔离SSH+真实HTTP服务的完整UI流程、停止后端口关闭验证通过；全量122项中117通过、5环境测试跳过，Linux打包通过。自动就绪/构建版本关联、无HMR刷新和Windows专项仍待完成。

第二十二轮：新增PreviewServicePlan与服务端Python管理脚本，独立tmux通信文件、项目/配置环境标记、实例校验、启动中/运行/退出状态和最近日志。启动前端口检查，已有配置不重复启动，旧停止请求/无归属会话拒绝操作；运行不等于HTTP就绪。真实隔离SSH场景及119项桌面测试（115通过、4跳过）、Linux打包通过。命令保存、启动按钮、HTTP就绪/监听归属及进程族回收尚未接入，未操作生产Agent。

第二十一轮：项目预览地址可显式保存/移除，按主机端点、SSH用户和完整项目路径隔离；浏览跳转不自动改书签。打开预览时恢复访问，输入中的手动地址优先；目录变化阻止旧设置提交。原生界面保存远端端口后完整退出、重新启动应用并打开预览，无需输入即恢复测试页面。116项中112通过、4环境测试跳过，Linux打包通过。启动命令/开发服务进程管理、构建状态及Windows项目地址专项仍待完成。

第二十轮：右侧预览新增复制当前网页截图，导航改紧凑图标；截图经Chromium合成表面、内存PNG解码与尺寸限制后只以图片进入剪贴板，不自动上传/发送。切任务取消旧作业，加载/URL/实例变化拒绝过期结果，截图操作纳入关闭/退出保护。原生重复截图/私有剪贴板测试及真实UI到X11系统PNG导出通过；113项中109通过、4环境测试跳过，Linux打包通过。区域框选、结构化截图附件和Windows剪贴板实测仍待完成。

第十九轮：资料额度区分明确不限、有限额度与数据未知。共享Me模型保留旧字段并追加已知性标记；缺失/错误/负数不再变成不限或零，桌面未知总额不显示“共0次”，恢复时间不再截掉时分和时区。安卓两处说明同步。110项测试中106通过、4环境测试跳过；Android编译任务依赖检查明确报SDK location not found，未宣称安卓编译通过。

第十八轮：Windows账号令牌接入用户级DPAPI（JNA5.17.0，禁止UI、不使用LOCAL_MACHINE），加密写到auth.json.protected。迁移先保护/回读校验再清空旧auth.json，异常不降级明文、不恢复旧备份。Windows独立exe的迁移、轮换、篡改拒绝、跨进程读取、退出/重登及浏览器检查通过；仅用虚构令牌。CI新增凭据门禁未运行；SSH秘密、历史漫游/备份排查、刷新未知结果和真实OIDC验收仍未完成。

第十七轮：登录会话增加代次保护，旧刷新/资料/账号请求不能越过退出或重新登录；令牌原子写入、不从旧备份恢复，新登录不能继承旧refresh token。退出标记可阻止清理失败后的自动恢复，双写失败明确提示。回调改IPv4固定端口、忽略无关/错误state/重复参数、限制请求长度和读取时限；界面支持取消、资料重试和返回登录。99项测试中95通过、4环境测试跳过，Linux打包通过。DPAPI加密、刷新响应丢失后的安全恢复和生产OIDC/Windows专项仍待完成。

第十六轮：账户页工单入口和完整用户端界面已接入（列表/新建/已读/回复记录/追问/草稿恢复/人工核对）。SupportWorkspace在提交前保存，离开页面仍完成回执持久化；保存失败的输入保留并纳入退出提醒。原生模拟界面新建和追问各一次、重开状态及两条持久回执验证通过；90项测试中87通过、3环境测试跳过，Linux打包通过。生产登录工单、WindowsDPI/IME、长草稿保存性能和现有认证持久化/刷新竞态仍未完成，没有发送真实客服消息。

第十五轮开始工单：核对hk13 logto_yxi/design/support-tickets.md，用户接口仅提交/列表/已读/追问，关闭属于管理员；追问关闭工单可重开。新增SupportApi与SupportDrafts，区分服务拒绝/未知结果，提交前原子保存，账号/工单隔离，旧备份与重启不自动重发。7项新增测试通过，全量86项中83通过、3环境测试跳过。尚未接入账户工单UI/认证请求/退出保护，不能认定工单功能可用；没有发送真实工单。

第十四轮：账户页接入桌面信箱，支持分页、阅读、领取、兑换码显示/复制和带确认的删除；宽屏列表/详情、窄屏单栏。共用AccountApi.Mail模型及新增无登录态MailApi契约层，按当前账号过滤过期回复。模拟接口和真实Compose界面的已读→领取→取消删除→确认删除通过；79项测试中77通过、2外部环境测试跳过，最终数字游标兼容追加定向测试/打包通过。未操作生产邮件或权益，工单、生产登录验证、Windows信箱专项和现有令牌持久化/刷新竞态仍待补齐。

第十三轮：最新功能在Windows本机生成独立app-image，实际运行exe的主界面smoke与Chromium本机页面/40px样式/非空像素/退出检查全部通过。修正检查程序过早读取页面和Windows桌面黑屏截图两处验证缺陷，改等load回调及CDP原生像素截图。CI已增加安装后浏览器门禁但尚未执行；未替换用户安装版，Setup升级、工作台DPI/IME/SSH预览专项仍待完成。

第十二轮：指令账本已接入输入框上方的三条折叠列表，支持独立编辑、上移、撤回；真实UI核验保存内容、主草稿保留、顺序与单条撤回落盘均通过。桌面改为单次文本/回车投递，先检查实例/画面/附件；终端回执保持待确认，人工解除使用独立状态。隔离PTY测试、73项普通测试和Linux打包通过。运行器权威回执、自动下一轮、目标条、附件租约、Windows专项等仍未完成，未发布。

第十一轮开始W10：新增持久化本地指令账本，包含指令ID去重、任务隔离、附件引用、编辑/撤回/重排与版本竞争检查。投递前原子落盘；投递中重启或恢复旧备份转为待确认，禁止自动重发。该状态层尚未接入ChatPane和运行器回执，不代表可用队列UI；原有发送路径仍须替换。新增4项持久化/竞争测试通过。

第十轮：新对话可选择Claude Code/Codex，启动前检查tmux与运行器，使用独立请求标识避免同名目录冲突并支持同窗重试。只有刷新取得真实会话才进入任务；新增可读默认名称。隔离SSH/私有tmux/模拟运行器验证了启动、重试、特殊路径、冲突及提前退出；71项测试中69通过、2个外部环境测试跳过。真实CLI认证与推理、Laize定义、跨重启启动请求恢复及Windows交互验收仍待完成。

第九轮增加文件/草稿/网页反馈退出保护，进行中的保存和网页确认不能直接丢弃。统一弹窗/菜单浮层，修复CEF遮挡确认按钮及唤醒窗口抢焦点。67项普通测试与实际取消→保留→丢弃退出流程通过；持久恢复、更新入口、网站表单及Windows专项仍待补齐。

第八轮：网页7项CSS试调、手势撤销、重置和样式反馈已接入；实际DOM效果与源码修改明确分开。同名元素替换拒绝旧操作，混合CSS优先级和无关属性得到保留。59项普通测试、原生样式集成与字号28→36的产品截图/源码哈希/E2E通过。Windows专项与其它PRD仍在推进。

第七轮接入真正的Chromium网页右栏与SSH端口租约，Linux原生验证了WebSocket实时更新、重连、Cookie上下文隔离、元素反馈及收起恢复。JCEF退出锁序死锁已通过同步client清理后再退出内核修复；实际产品界面退出验证通过。Windows浏览器及DPI、多标签、样式试调/截图、开发服务器管理等仍待完成，未发布。

第六轮：完整目录项目树、置顶顺序、显示名称、归档/恢复与筛选，整理记录原子落盘。SessionProbe增加可选runtimeId，防止同名重建会话继承旧状态；文档/草稿同步按实例隔离。51个普通测试、真实SSH文件引用与置顶→归档→恢复UI流程通过，服务器测试会话未被停止。历史任务、项目根识别等仍待补全。

第五轮加固线路清单：expected版本校验、SFTP临时上传、服务端锁/摘要/JSON校验、私有备份与原子替换；异常条目不静默跳过。桌面增加清单记录移除，运行器现有配置不动。隔离SSH异常/冲突/权限/删除测试及桌面构建、中文E2E通过。运行器配置多文件事务、真实模型请求、恢复UI及其余PRD仍未完成，未发布。

第四轮接入模型与线路面板，Lines/LinePresets原样移至core。桌面支持编辑模型和认证方式、项目/用户范围、应用配置与回读；范围内有运行任务时拒绝应用。46个常规测试＋1个独立SSH配置集成测试通过，线路页截图验过；真实提供方请求和CLI生效未验证，清单原子写入/回滚仍待补齐，不能宣称W09全部完成。

第三轮：紧凑分段导航统一工作区/文档样式，测试字体补齐中文；文档增加端点身份守卫，41项测试通过。Windows专用jar经本机JDK21 jpackage打成独立app-image，独立配置下启动smoke退出0，含Markdown渲染。未安装/发布新版本，完整Windows视觉/DPI与升级仍待验收。

第二轮接入文件右栏：Markdown表格/源码/分栏、远端自动更新、版本冲突、原子保存、源码选段引用和任务间草稿保留。运行时实测发现Markdown桌面库需要Java21，desktop/CI已同步升级，Android/core不变。40个单测和5个保存脚本集成用例通过；SSH预览截图已确认渲染与自动更新。更多实现/验收及未完成项见下述进度文档。网页浏览器、线路和插件等仍未完成，不得直接作为完整工作台发布。

用户已授权按 `design/windows-workbench-prd.md` v0.2 构建完整工作台。当前实施分支 `codex/windows-workbench`，未发布新包，未修改正在运行的 Agent。逐项剩余工作见 `design/windows-workbench-progress.md`，不能把这次基础修复视为整个 PRD 完成。

本轮：主机原子保存/备份恢复/迁移保源/保存错误提示；主机选择器与新对话入口、启动重连及会话恢复；登录回调不提前声称成功及错误内容转义；CI 安装后 exe 冒烟和发布门禁；隔离桌面 E2E 的 home/display/PID。34 个桌面测试通过，Linux jar 打包及 Xvfb 冒烟通过；Windows安装、完整登录和两主机恢复仍待实机验证。

修复依据：旧迁移嵌套 runCatching 即使 copyTo 失败仍会删源；读取异常被当作空列表；备份恢复前不能保存空列表覆盖原数据。Windows/Linux传输脚本的换行通过 `.gitattributes` 固定LF。

## 基础信息
> 🔑 **一句话讲清这个项目**：什么都不装，SSH 本来就能「你去看」——看会话、进去问 Claude、
> 渲染成对话界面、翻文件、传附件，**整个 App 几乎都能用**。
> **`yxi-hook` 只买「主动」两个字**：让手机在 Claude 需要你时**主动响**。
> 不装是**监视器**（你去看它），装了才是**遥控器**（它来找你）。
> `yxi`（agent）纯属省往返，可以完全不装。详见 PRD 附录 H.0。

- **是什么**：手机指挥台 —— 复刻 Moshi（手机开终端、管 tmux、给 Claude Code 下指令和远程批权限），**去掉它的整个云端层**。
- **面向全球用户，但不跑任何后端**：每个用户连自己的服务器（PRD §2.6）。分发走 **GitHub Releases**，不上应用商店。**第一期只做 Android**（iOS 装不了 Release 的 APK，PRD §2.5）。
- **技术栈**：客户端 = **Android 原生 APK**（Kotlin + Compose + Material 3；SSH 用纯 Java 的 `mwiede/jsch`，
  终端用 **`org.connectbot:termlib`**（Compose 原生终端控件，不是 WebView），
  markdown 用 `multiplatform-markdown-renderer-m3`，ed25519 靠 BouncyCastle）。
  服务器端 = **只有一个 `yxi-hook`**（往 `~/.yxi/events.jsonl` 追加写，App `tail -f`）。
  **传输走 SSH，不开任何新端口、不要证书。**
- **部署在哪**：**默认哪台都不用装。** 会话看板、对话渲染、发指令、文件模式全部用现成的
  `tmux` / `~/.claude/projects` / sshd 自带的 SFTP。只有「手机主动响」需要在那台机器上装 `yxi-hook`。
- **开发回路**：本机 `/dev/kvm` 可用、嵌套虚拟化已开 → **AVD 模拟器硬件加速**，`adb install` 迭代（MuMuPlayer 无 Linux 版）。
- **鉴权**：复用 SSH 公钥认证，私钥存 Android Keystore。**不需要 CA 证书 / mTLS / token / Tailscale / 改 ufw**——见 PRD §2.3。手机丢了 = 删一行 `authorized_keys`。
- **怎么跑**（2026-09-07 更新，cc-Bug_solverYxi 摸出来的实际流程 + cc-Yxi_pilot 的锁规矩）：
  ⚠️ **`dev/run.sh` 已经过期**：它 `ensure_emu` 调 `dev/avd.sh` 拉**本机** AVD，而测试早搬去 **Mac mini** 了，本机连 adb 都没有。
  真实流程（本机构建 → 传到 Mac → 在 Mac 上装和跑）：
  ```bash
  # 0. 先占机器（三个 agent 共用这一台，今天互相顶掉过三次）
  ssh mac "~/yxi-build/emu.sh claim cc-你的名字"
  # 1. 本机构建两个包
  cd android && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
  # 2. 传过去（各一个）
  scp app/build/outputs/apk/debug/app-debug.apk mac:/tmp/x.apk
  scp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk mac:/tmp/x-test.apk
  # 3. 装 + 跑（EMU_WHO 必带，否则被自己的锁拦住）
  ssh mac "EMU_WHO=cc-你的名字 ~/yxi-build/emu.sh install /tmp/x.apk"
  ssh mac "EMU_WHO=cc-你的名字 ~/yxi-build/emu.sh adb install -r -t /tmp/x-test.apk"
  ssh mac "EMU_WHO=cc-你的名字 ~/yxi-build/emu.sh adb shell am instrument -w \
      -e class app.yxi.某测试类 app.yxi.test/androidx.test.runner.AndroidJUnitRunner"
  # 4. 下机
  ssh mac "EMU_WHO=cc-你的名字 ~/yxi-build/emu.sh release"
  ```
  · **全套是 256 条**（1.1.18 全绿那次），不是 15 条。发版前必须跑一次全套（见下面那条 ⚠️）。
  · ⚠️ **debug 没有 `applicationIdSuffix`**，跟 release 签名不同 —— 装 debug 会**顶掉机器上的正式包**
    （`emu.sh install` 遇到签名冲突会自动卸了重装，数据一起没）。跑完记得把 release 装回去。
  · ⚠️ **模拟器是 `-no-audio` 起的，也没有马达** —— 声音和震动在这台机器上**验不了**
    （音游打击音、抽卡短片的音轨都属于这类）。看到「声音没问题」的结论，多半是没验，不是验过了。
  · ⚠️ **别在跑 `am instrument` 的时候点屏幕 / force-stop**：被打断的表现是「某一条莫名 Process crashed」，
    不看时间线根本想不到是人为。锁就是为这个加的。
  APK 产物 `Yxi-0.1.0-debug.apk`，手机直接下的地址见下面「APK 分发」。

### SSH 接入（App 连这台机器用）
| 端口 | 用途 |
|---|---|
| **22** | 常规 |
| **8443** | **备用** —— 手机在移动网络下 22 出站常被运营商屏蔽，症状是 App 报「连不上」(TCP 超时) 而服务器侧一切正常。两个端口是同一个 sshd、同一把主机密钥，App 里只改端口号即可 |

⚠️ 端口由 `/etc/systemd/system/ssh.socket.d/yxi-altport.conf` 决定（**socket 激活**），
往 `sshd_config` 写 `Port` 无效且会跟 socket 抢端口把 22 一起搞挂。见 TROUBLESHOOTING #67。

## 已发布

**1.1.27 / versionCode 183**（2026-09-12 发，tag `v1.1.27`，改动区间 `git log 8e57cd7..v1.1.27`）：**连接不稳大修**（新 `agent/NetWatch.kt` 盯系统默认网络，换网/断网**立刻断掉重连**、不等 15s×2 心跳，退避 0.5s 起最长 5s，后台盯梢最长 20s 且换网即重试；老板 09-08 报的「连接断了，正在重连」）· **同名工具卡合并**：夹着失败的一串也合成一张，卡上红字标「N 失败」· **`:core` 拆分随版上车**（09-08 以来桌面版的提交里所有动 `core/` 的全带进这版，全套 **316 条**发前绿兜底）。
⚠️ NetWatch 模拟器验不了切网，**真机行为等老板升级后切 Wi-Fi/飞行模式试**（最坏是重连手感不对，不崩数据）。
· 发版链路首次全程在 mac mini：构建 → apksigner 验签（指纹一致）→ 装机实跑 → 全套 316 绿 → 三组员 `yxi-hub` 确认无未交改动 → publish。
⚠️ **历次 `v1.1.x` tag 实际都没推上 remote**（这次发现 remote 一个 tag 都没有），这版起补打；历史 tag 未回填，等老板拍板。
⚠️ `dl-token` 的**文件内容是下载目录名**（`0fmWY…`）——丢了重建别留空，空内容会把包同步到 `/var/www/yxi/` 根目录（这次踩了，错位文件已清）。

**1.1.26 / versionCode 182**（2026-09-07 深夜发，tag `v1.1.26`，`git log v1.1.25..v1.1.26`）：**连击边框流光**（到第二档四边一圈流动彩光，档位越高越亮）· **癫狂背景 20 种**（`WildBg{A,B,C,D}.kt`，四个子代理各 5 种；calm 时 ≤0.5Hz）· 走网络同步上线：**多根判定线的舞铺到全部 17 首的认真 / 癫狂谱**（`multiline.py`：每首 2~3 段分裂 / 斜副线 / 一分为三）、17 张 wild 谱每张随机 3~4 种背景。
⚠️ 发布时公网 403（rsync 带过去 0600），已 chmod 并在 install.sh 修（#318）；真下核过 182、sha256 一致。
· 发前全套 311 绿；opus 审查 20 种背景无崩溃 / 底色齐 / 两处 calm 频率已修。

**1.1.25 / versionCode 181**（2026-09-07 晚发，tag `v1.1.25`，`git log v1.1.24..v1.1.25`）：**从机**（主机卡片 → 内网设备 → 跳一层看另一台机器的会话，pilot）· **长按「对话」翻自己发过的话改成落文件、保留三天**（pilot，老板 09-07 要的；只追加、补齐三个没记账的发送口子）· 上传压测的跳过判据改成「有没有显式传 stressKey/stressHost」（logto 修的 512ddb2，pilot 撞到并报的，见 #316）· **癫狂背景纯色硬切换色钉死 2.5Hz、关闪屏开关时 0.5Hz**（Entertainment 指出）· **看板模型名不再等一分钟才出来**（Bug_solver 47da0dd）。
· 发前全套 311 绿（含 pilot 新加的用例）。

**1.1.24 / versionCode 180**（2026-09-07 晚发，tag `v1.1.24`，`git log v1.1.23..v1.1.24`）：**「癫狂」难度的舞台特效**（谱面 `stage` 关键帧：镜头 zoom / 拉伸 / 转 / 抖、闪屏、背景硬切网点 / 射线轮 / 螺旋点阵 / 纯色、音符忽大忽小、色相；手指反变换、减弱动效不闪；`ui/rhythm/WildBg.kt`）· 手感面板「闪屏 / 抖动」开关（光敏）· **会话看板「官方登录」显示具体模型**（Bug_solver 551b846）。同一天走网络上线：17 首的 wild 谱（53 张）、煉獄 表演关、9 首新曲。
⚠️ **真机没验的**：闪屏强度 / 频率的观感（模拟器截图只看得到静帧）；癫狂难度真手打。
· 发前全套 287 绿；opus 审查无阻塞（提了闪屏开关，已加）。

**1.1.23 / versionCode 179**（2026-09-07 傍晚发，tag `v1.1.23`，`git log v1.1.22..v1.1.23`）：判定线 **alpha 关键帧**（闪烁 / 渐隐，表演关的「闪闪闪」）· 谱面里**不认识的 op 一律跳过**（老客户端遇到新 op 原来会当 move_y 把线甩飞）。同一天走网络上线的：9 首新曲（17 首 35 张）、关卡设计手册、levelkit 自定义关卡接口、showpiece 表演关生成器。
· 发前全套 274 绿。

**1.1.22 / versionCode 178**（2026-09-07 下午发，tag `v1.1.22`，按 `git log v1.1.21..v1.1.22` 核）：**「我的」加娱乐中心入口（功能格两行三格、手柄图标），云曦节拍从活动中心搬进去** · **游戏 HUD 得分左边放自己的头像** · 狂热演示只在无线时刻半空爆 · 线立起来时爆点 / 碎裂不压扁 · 关卡清单刷新后台跑完为止 · **抽卡黑屏真根因**（Bug_solver b7571a7：fd 被提前关掉；Entertainment 模拟器验过短片能放、「跳过」能点）· **站内信 30 天清理 + 兑换码正确显示 / 复制**（Entertainment 264ea06）。谱面侧同一天走网络发的：12345 狂热谱改成跟节奏（逐小节节奏模板）、分裂线。
⚠️ **真机没验的**：音效 / 震动；HUD 头像在真机上戴装扮框的样子（模拟器是默认头像）。
· 发前全套 274 绿。

**1.1.21 / versionCode 177**（2026-09-07 下午发，tag `v1.1.21`）：老板下午一批 —— **关卡联网**（公网清单 `yxi.keuury.com/rhythm/songs.json`，App 进选曲页拉清单、谱以清单为准、清单独有的曲子点下载；发布脚本 `design/music/publish_songs.py` 先跑 logto 的 rhythm-sync 再公开；规格 §6.4）· **多判定线**（谱面 judges/from/to + 音符 line，全局轨号方案；狂热谱 4 个副线窗口）· **12345 狂热谱**（units 851，服务端已同步）· 狂热演示半空提前爆 · 曲子能量包络驱动底光 + 命中闪加强 · 判定线整条留在屏幕里（大角度外侧轨点不到的 bug）· 选曲页音块图例 · 难度名 / 谱数按每首 diffs 算 · **配置 / 我的下拉刷新**（Bug_solver，0aa1dd1 + ae044f1，模拟器实机验过）· **抽卡底部播放器进度条 + 卡牌库命座**（Entertainment，7f69e23）。（按 `git log v1.1.20..v1.1.21` 核的，#290）
⚠️ **真机没验的**：音效 / 震动、狂热谱真手打、12345 狂热整局的 too_fast 门槛（elapsed 理论上高出门槛 10%+，模拟器整局已验 初雪）。
· 发前全套 274 绿；四轮 opus 审查（狂热 / 多线 / 联网 / swipe）抓到 3 个真崩溃或死局，都修了（#300 #301、路径注入、下载死局）。
· 加谱后「全 S 限定」门槛自动 16 → 17 张（待老板拍板）。

**1.1.20 / versionCode 176**（2026-09-07 中午发，tag `v1.1.20`）：老板上午一口气点的音游改动全在——**轨道 4→12 条、音符加长 30%、轨间线去掉**（16 张谱经 `lanes.py` 铺开，units 不变，服务端没动）· **swipe 划法放宽**（最近 0.18s 位移 > 3% 屏宽；按住再划 / 不抬手连扫都算，#299）· 竖校准线加粗抽长、判定线编舞重写（倾 / 升 / 横移 / 立竖保持整段 / 翻面，`choreo.py`）、立竖时轨道压进屏高、无线时刻只认最高档 35%/下 · **「▶ 演示」自动全完美**（不上报不计成绩）· 命中音四种一块一种（tick 原声 · swipe 光尘 · trace 风铃散 · slide 樱瓣；20 款试听页 `/lab/shatter-sfx.html`）· 左下角 Yxi Dancing Beat、爆点 1.1、旧谱下落 1.55、线的慢摆 + 呼吸 + 弹簧回正 · 手指判定放宽到相邻轨 · 判定日志 logcat `YxiRhythm` · **微曦客户端**（Entertainment）· **长按「对话」翻自己发过的话**（pilot）· **语音插到光标处 + 说满后新字可见**（Bug_solver，模拟器实机验过，中文输入法 / 语音本体只能真机）。
⚠️ **真机没验的**：四个命中音、震动、触摸手感（模拟器 -no-audio 没马达）；swipe 在模拟器上三种划法（快甩 / 按住 0.8s 再划 / 不抬手右→左）全中，真手指等老板。
· 发前全套 274 条全绿（含 Bug_solver 新加 5 条）；三批改动各过一轮 opus 审查（漏项 / 编舞 / 12 轨 + 演示 / swipe），阻塞项都修了。

**1.1.19 / versionCode 175**（2026-09-07 凌晨发，tag `v1.1.19`）：**云曦节拍大改** —— 画面按老板在网页试验台上拍板的全部重做（白发丝判定线会跳舞、薄片音符、两款 swipe 标记、九款点击特效 + 八款碎裂随机、长按持续特效、背景随连击变亮、竖向校准线按档概率、无线时刻、评价词字体）· **新增五首曲子**（魔王魂免费曲，署名「音楽：魔王魂」，服务端 16 张谱）· swipe 判定放宽、trace 按着就算 · **功能文档 `design/rhythm-spec.md`** · 网址预览卡跟在对应段落下面（pilot）· 抽卡：擦星星 + 短片收尾 + 祈愿四个修（Entertainment）。
⚠️ **真机没验的**：音游手感（音效延迟 / 震动 / 触摸）、抽卡短片的声音 —— 模拟器 -no-audio 没马达。老板真机验。
⚠️ 加了十张谱的两个连带后果待老板拍板（见「待老板拍板」）。
· 发前全套 269 条全绿；E2E 抓到并修了 #293（外来曲提前结算被服务端判 too_fast）。

**1.1.18 / versionCode 174**（2026-09-06 发，tag `v1.1.18`）：**抽卡出货动画**（Entertainment）—— 擦星星（立绘呼吸、星尘）→ 白闪 → 按稀有度放老板录的短片 → 结算卡，十连跑通 · **祈愿逐项排查四个真 bug**（Entertainment）—— 曦光不够只把按钮变灰（静默失败）/ 十连结算页不能滚、一划关窗 / 保底那一发看不出来（服务端加 `byPity`）/ 卡牌库印「27 / 9」· **换模型说 not found 其实没换**（Bug_solver，#287）。
⚠️ **抽卡和祈愿那批只过了模拟器，真机没看过**（Entertainment 标明的）。
· 全套 256 条发前跑过全绿；核「哪些进了包」从这版起用 tag 区间（#290）。

**1.1.17 / versionCode 173**（2026-09-06 发）：**修「点了发送，话还留在框里」的真根因** —— Claude Code 忙时消息进排队，它把占位提示 `Press up to edit queued messages` 画进输入框，判据当成了草稿 → 补回车 → 报失败 → 老板再发 → 同一句进服务器五次（#285）。音游：**四种音块各有各的打击音**（Entertainment，make_sfx.py 合成）· **玩的时候右滑不再拉开侧边栏**。
⚠️ 发这版时到 hk13 的链路突然只剩几 KB/s，脚本第二趟上传挂死。`Yxi-173.apk` 第一趟已完整落地且 sha 一致，收尾（拷直链 / 翻清单 / 改按钮 / 公网核对）是手工按脚本步骤走完的。
⚠️ 这版起**发版前先跑全套**（255 条，#286）。

**1.1.16 / versionCode 172**（2026-09-06 发）：**云曦节拍第二轮** —— 四种音块（点 / 滑 / 拖 / 挥，各自配色和命中特效）· 判定范围放宽 50%（老板拍板）· 连击 8 / 15 / 30 三档分数倍率 + 评价词 · 上报改判定序列 `hits`。**临时会话点一下直接进**（固定 opus + low，选择弹窗删了）。
⚠️ **音游有两处模拟器验不了、等老板真机**：手感（音效 / 震动）· 界面上的倍率角标和评价词（模拟器 input tap 抖动一两百毫秒、对不准开局，打不出 3 连）。倍率的分档和数值本身由服务端回归覆盖（9 连手算断言 132051 + 满连分档分布）。
⚠️ **审查各拦下一条**：音游结算页用老公式，加倍率后分数会先掉一半再跳回；临时会话的 `tempBusy` 在协程被取消时不复位，那个入口从此点不动。

**1.1.15 / versionCode 171**（2026-09-06 发）：云曦节拍改横版（判定线会动、命中特效重做、打击音效 + 震动、手感面板六项自己调）· **对话里网址出预览卡片**（默认「点了才抓」，设置 → 界面可改）· 修：上传附件后点发送话又回到输入框 · 授权途中换网被误报失败 · 点切模型老说它正忙着 · 终端状态行时有时无。
⚠️ 安全要点见 TROUBLESHOOTING #283（链接预览的闸门被九种写法绕过 + 跟跳转能打到云元数据）；发送不等重连的根因见 #282。

**1.1.14 / versionCode 170**（2026-09-06 发）：扫一扫（相机扫码）· 云曦节拍（音游）· 聊天气泡装扮 · 任务看板 · 「我的」页分块 + 我的资料 / 账号中心 · 侧边栏主机段可收起 + 内网标记 · 头像框。
修：传大文件/多图失败（断点续传）· 改了凭据不自动重连 · 终端状态行时有时无 · 顶栏模型名跟不上 `/model` · 换模型不显示「默认」· MCP 认证回填没反应。
**这一版审查拦下了三批里的三批**（都是 E2E 能跑通、发出去会出事的）：扫一扫的相机泄漏（退出后绿点常亮、要杀进程才释放）· 音游把已打出的 S 显示成 A · 「我的」那批里两条自己制造的回归（对着登着的用户写「未登录」；跨批修复把「上下文吃紧」的判据打歪了）。
⚠️ **`git add -A` 会扫走队友工作树里的改动** —— 35b51ca 就这么把 pilot 的扫一扫修复卷进了 cc-Yxi 的提交（内容没错，说明漏了，已 amend 补上）。共用工作树里提交一律按 hunk 挑（`hunks.py list|take`）。
⚠️ **`install.sh --publish` 会核包里的 versionCode** —— 这次真拦住了一次：改完版本号那次 `assembleRelease` 没重建，拿的是 05:22 的旧包（169）。它报「不是你要发的那个包」，没发。重建后才对。

## 待发版（攒着）

> 组规（老板 2026-09-05）：**所有组员都改好了才构建新版本，别频繁发**。修完一处先记在这儿，一行一条、写清是谁的；
> 发版前 `yxi-hub all` 问一圈都齐了再 build + publish；**发完就清空这一段，同时把版本号那两行提交掉**（1.1.15 两样都漏了，见 TROUBLESHOOTING #284）。
> 例外只有线上崩溃 / 数据风险，破例前先跟老板说。

**（空）** —— 攒的三项（NetWatch / :core 拆分 / 工具卡合并）已随 **1.1.27** 发布（2026-09-12，见「已发布」）。

**不用发版就上线的（09-07 下午，走网络清单）**：9 首新曲（魔王魂 ×4：煉獄セレナーデ / ときめき☆ラビリンス / ハニーベイビー！マジカルガール / 宵闇の輪舞曲；甘茶 ×2：ピコピコディスコ / レトロパーティー；OtoLogic ×3（CC BY 4.0）：Candy Crush / ドタバタレース / 前線へ突撃せよ），每首 easy + hard，`design/music/remote/<id>/`（gitignore，源在 incoming/）。清单 17 首 35 张，服务端已同步。
  · ⚠️ **授权**：条款快照在 `design/music/licenses/`。PeriTune 的 Dreambyte 做好后**撤了**（条款写「楽曲は必ずコンテンツの背景（BGM）として使用してください」，音游不算 BGM）；甘茶条款页写「商用利用可」、没有 BGM-only 禁令，但调研代理提到它某处有「何かのBGMとしてお使い下さい」的说法 —— **有歧义，老板知悉**。魔王魂 / OtoLogic 干净。
  · ⚠️ **BPM**：只有煉獄セレナーデ页面标了 BPM（172，librosa 估成 112，已按 172 重出）；其余按 librosa 估的（tempogram 复核过），magical 129 / picopico 117 / rondo 99 有二义（可能是 199 / 123 / 198），真机打着不对拍就报我。
  · 老板要的「有名的二次元名曲」：调研结论只有 **t+pazolite「without Permission」**（条款明写「音楽ゲーム／個人商業問わず無許可無償」）—— 要买 BOOTH Dev Kit ¥1,000 或 Bandcamp $8.99，等老板买。

⚠️ **发版前先跑一次全套**（`connectedDebugAndroidTest`），这一步以前不在关卡里 ——
两条测试从 09-05 起一直红着没人发现（#286）。UploadStress 那 7 条要先配主机，配法见 #286。

**待老板拍板（加了十张谱的两个连带后果，logto 2026-09-07 指出，不是 bug）**：
- 「全 S 限定装扮」的门槛从 6 张谱变成 16 张（判据是「每张谱都领过 S 奖」，加谱自动变难；已领到的人不受影响）。要维持原难度得改规则（比如「任意 6 张 S」或按曲子算）。
- 音游可拿的曦光从 18 涨到 48（每张谱首次 B 给 1、首次 S 给 2 × 16 张）——和「抽卡越抽越多」是同一个池子，收紧经济时要一起算。

**待办（不急）**：
- **音游上报的幂等重试**：`Rhythm.submit` 每次调用都生成新的 requestId，失败后没有保留 —— 所以不会命中服务端的 replay 分支（Entertainment 在微曦那边抓到的 replay 漏字段问题不影响我们），但反过来：用户超时再交一次会被当成**两局**。要么保留 requestId 重试，要么结算页不提供重交。**查过了：ResultCard 只在 LaunchedEffect 里交一次（RhythmScreen.kt:899），没有重交入口**，失败就成绩只留本地 —— 现在不会算成两局。以后要加「重交」按钮，必须沿用同一个 requestId。
- **指纹框会无限弹、取消也关不掉**（Entertainment 2026-09-06 在模拟器上撞到：一台指纹对不上的主机 → 弹框 → 取消 → 盯梢服务重连 → 再弹，界面卡死）。真用户换了主机密钥也会被困住。要么取消后这台主机这次运行里不再自动重连，要么弹框只弹一次、后续静默记状态。文件是 pilot 的（Connect / HostsScreen），动之前先说。

**不发版的两条**：
- 待办：看板头部主机名太长会折成两行挤按钮（截图 Thor-h/e）。
- **待老板拍板**：抽卡界面改 activetheory.net 风格。结论没变 —— 他们那根脊椎是 Blender 雕的美术资产（`spine.bin` + KTX2 贴图），程序化到不了那个精细度，要做得我们自己出一根。素材和商标不能拿。提案和对照图在实验室，源码 `design/wish-activetheory-cards.src.html`。

## Windows 桌面版（`android/desktop/`，2026-09-08 起）

老板 09-08：「构建一个 Windows 版本，像 Claude Desktop / ChatGPT 的 Windows 版那样」；09-08 晚追加：「参考 codex 和 Claude desktop 的 exe，可以下载下来逆向看看」→ 拆安装包出的对照报告在 `design/desktop-reference.md`，桌面版形态（打包 / 布局 / 视觉 / 快捷键）按它对齐。Compose Multiplatform（JVM）+ 共用 `:core`，
计划 / 模块 / 构建命令见 `android/desktop/README.md`。

**做到哪（09-08 晚，第二轮按拆包报告对齐，四个代理并行做完已合并）**：
- 窗口壳（`Main.kt` / `Shell.kt`）：自绘标题栏（`WindowDecoration.Undecorated`，40dp 拖拽区，三键自绘）、托盘（AWT `TrayIcon`，单击唤回）+ 关窗留托盘、单实例（`%LOCALAPPDATA%\Yxi\lock` + 回环端口唤醒）、快捷键（Ctrl+N/B/J/Tab/1…9/Alt+A/,//、F5、缩放）、设置（`Settings.kt`：托盘 / 开机自启 reg / 主题 / 通知三档）、快捷键表（`Shortcuts.kt`）、< 700dp 自动收侧栏。
- 左栏（`Sidebar.kt`）：主机分组（状态点 / 颜色条 / 右键菜单）→ cc-* 会话行（`SessionState` → 等待批准 / 需要用户输入 / 正在运行 / 已完成 / 空闲 徽标）；`Conn`（`Model.kt`）自动重连（1→2→4→8→10s 退避，指纹变了 / 认证失败不重连，Claude 文案 + 「我确认过了，删除旧指纹」）；会话变成需要处理时托盘通知。
- 对话（`ChatPane.kt` / `Approval.kt` / `Markdown.kt`）：审批卡（工具名 + 命令原文 + 真实选项映射「允许一次 / 不再询问 / 本会话允许 / 拒绝」，Enter 第 1 项 / Esc 拒绝）、会话头连接徽标 + 重新连接、断线不清屏且续尾随、轮次完成 / 等待批准通知、主题 token 落地、悬停复制。
- 主题（`Theme.kt`）：Claude 暖灰浅 / 深两套 token，跟系统或手选。
- ⚠️ **全部只编译过 + jshell 逻辑自查，没在有显示器的机器上跑过**（Mac 隧道 09-08 晚一直丢包）。第二轮 opus 审查进行中。真机最先要验：Enter/Esc 焦点、Ctrl+Tab 是否被 AWT 吃掉、undecorated 最大化、托盘。
- **老板 09-08 晚：「手机上 yxi 导航栏的功能都要做，要有自动更新；这些活交给组里其他 agent 做」「Windows 版先不做娱乐部分，先做主功能」** → **分工（cc-Yxi 定，09-08 晚）**，都在 `android/desktop/` 里改，各自只 add 自己的文件，编译 `./gradlew -q :desktop:compileKotlin`，桌面版不走手机的发版流程（CI 产物 → hk13 `/var/www/yxi/desktop/`）：
  - **cc-Yxi_pilot**：导航壳（对齐手机四栏「会话 / 主机 / 配置 / 我的」：左栏底部加「配置」「我的」入口，`App.kt` 归 pilot）；工作区「文件」模式（浏览 / 预览 / 上传 / 附件，照手机 `FilesScreen` / `FileViewer` / `Attachments`，走同一条 `Conn.ssh` 的 SFTP）；「配置」页（照 `ConfigScreen`：技能 / MCP / 子 agent / 命令 / 权限 / 钩子 / 记忆 / 插件，core 的 `ConfigRemote` 可直接用）；Mac mini 上第一遍真机（`-Pyxi.os=mac` 打 jar，隧道慢时用 rsync 断点续传）。文件：`App.kt`、`Sidebar.kt`、`SessionsPane.kt`、`HostsPane.kt`、新 `Files*.kt`、`Config*.kt`。
  - **cc-logto_yxi**：「我的」主功能——Logto 登录（系统浏览器 + 本机回环端口回调，照报告 §2.1 Codex 的 1455 做法；不要内嵌 webview）、资料 / 会员 / 额度 / 工单 / 邮件；`Account.kt` 的 HTTP 层抽进 core（Android 特有的留 app）；服务端自动更新源（hk13 `/var/www/yxi/desktop/` 放 Velopack 的 `releases.win.json` 或 `latest.json`，nginx 已白名单）。文件：新 `Me*.kt`、`Account*.kt`、`core/agent/Account*`。
  - **cc-Bug_solverYxi**：自动更新客户端收尾（cc-Yxi 的打包代理正在做 Velopack 一键 Setup.exe + `Update.kt`，合并后 hub 通知接手）、`publish.sh` 发布脚本、审查遗留（P2：undecorated 最大化盖任务栏；`%APPDATA%` 明文密码 vs `%LOCALAPPDATA%`；`reg add` 引号）、Windows 真机验证（老板装了会反馈）+ 桌面 E2E。文件：`Update.kt`、`Shell.kt`、`Main.kt`、`Settings.kt`、`build.gradle.kts`、`.github/workflows/desktop.yml`、`publish.sh`。
  - **cc-Yxi_Entertainment**：桌面娱乐（音游 / 抽卡 / 云曦 / 商店 / 深渊 / 活动）**先不做**，继续手机端任务。
  - 公共文件（`ChatPane.kt` / `Approval.kt` / `Markdown.kt` / `TermPane.kt` / `Model.kt` / `State.kt` / `Theme.kt`）谁要改先在 hub 说一声。
- **老板 09-08 晚：「现在这种 exe 的构建方式是 codex / Claude desktop 同款吗，我希望现代点的构建方式」** → 事实：两家都是 Electron；Claude 用 Squirrel 一键 Setup.exe + 后台差量更新（per-user、无向导无 UAC），Codex 走微软商店 MSIX。我们是 Compose（共用 core）+ jpackage MSI（向导式、无自动更新）。**决定：技术栈不换，打包换成 Velopack（Squirrel 的现代继任者）一键 Setup.exe + 应用内自动更新**，做不通退回 per-user MSI + latest.json 静默升级；商店 MSIX 以后再说。代码签名（SmartScreen）要老板拍板买哪种，见 desktop/README。
- **Windows 包已经能出**：GitHub Actions `desktop.yml`（手动触发或推 tag `desktop-v*`，windows-latest 打 MSI + uber jar，`--smoke` 在 Windows 上打印了 smoke ok）。
  发布：`gh run download <run-id> -R liang-senbei/yxi -n Yxi-windows -D <目录>` → `scp` 到 `hk13:/var/www/yxi/desktop/`（chmod 644，#318）→ 公网 **https://yxi.keuury.com/desktop/Yxi-1.0.0.msi**。
  nginx 白名单在 hk13 `/etc/nginx/snippets/yxi-dl.conf` 的 `location /desktop/`（根目录 `location /` 是 404 白名单，新路径都得单列）。
- **✅ 1.0.1 已发到公网（cc-Bug_solverYxi，09-08 晚）**：
  · **装机地址 https://yxi.keuury.com/desktop/Yxi-win-Setup.exe** —— Velopack 一键装（无向导、无 UAC，装进 `%LOCALAPPDATA%\Yxi`）
  · 更新源是同目录的 `releases.win.json`（客户端每 6 小时查一次，比版本高就静默下 nupkg）；**旧的 `Yxi-1.0.0.msi` 原样留着**，老板手上那份不受影响
  · 出处：CI run `34250917867`（main `57c65ff`）；发布命令就是 `android/desktop/publish.sh <run-id>`（rsync 无 --delete，只增不删）
  · 核过两件：公网真取 `Setup.exe` / `releases.win.json` 都是 200；`releases.win.json` 里写的 SHA256 跟 hk13 上 nupkg 真算出来的**逐字相等**（对不上客户端会静默不更新，是那种没人报错的坏法）
  · ⚠️ **还没有人在真 Windows 上装过它**：客户那台 `han` 当前 offline；老板电脑只能传文件、不能装（隧道那头是 cmd，装机让老板自己来）。**「装得上」和「应用内自动更新真的能跳版本」这两条都还欠着**，等老板反馈或 han 上线。
- **桌面 E2E 冒烟 `dev/desktop-e2e.sh`（cc-Bug_solverYxi）**：起一块**自己的** Xvfb（默认 `:97`，`:99` 是全组共用的，两个人同时点会串）→ 跑 uber jar → 等窗口 → 点「配置」「我的」→ 逐张截图到 `/tmp/yxi-e2e/` → 查日志异常（Skia 回落那两句是正常的）。**改了桌面版界面，合并前跑一遍** —— #328 证明了「编过 + 逻辑自查」漏得掉整类问题（弹层被排到屏幕外，进了组合、状态也对，就是没像素）。⚠️ 它**验不了最大化**（这套环境 setExtendedState 之后状态回 0，带不带边框都一样，见 #327）。
⚠️ **桌面版的关卡：改了界面，合并前跑一次 `dev/desktop-e2e.sh`**（跟手机端「发版前跑全套」对齐，cc-Yxi_pilot 建议）——
判据是「起得来 + 点得动 + 日志没有意外异常」，退出码能直接卡。#328 证明了「编过 + 逻辑自查」漏得掉整类问题：
弹层被父布局排到屏幕外，进了组合、状态也对，就是没有像素，**只有把它画出来才看得见**。
⚠️ 它验不了最大化（这套环境 setExtendedState 之后状态回 0，带不带边框都一样，见 #327）——那条只有真 Windows。

- ⚠️ **除了 Windows 冒烟（开窗 3 秒），没在有显示器的机器上真用过**：下一步 Mac mini 上 `java -jar … --smoke` + 连真主机走一遍主机 → 会话 → 对话；opus 审查过一轮（3 处已修）。
- 没做：带口令的私钥、`user@host:port` 整串粘贴拆分、账号（Logto）/ 会员 / 额度、通知托盘、深色主题。

- **桌面版第二轮：P0 三个缺口（2026-09-11，老板：「安卓版很完善了，按手机版 PRD 继续推进 Windows 版」）**。对照 PRD §5.1 逐条盘过，桌面版缺的是 **用量显示（P0-13）、附件与图片（P0-10）、一键装公钥（P0-14）**，这轮全补上：
  · **用量显示**（新 `UsageUi.kt`）：主机分组头下面一条紧凑条（5h 窗口进度 + 剩余 + 今日花费），点开详情弹窗（5h 窗口 / 今天 / 近 7 天比例条 + 合计），数据走 core 的 `Usage.probe/today/daily`（ccusage 读那台机自己的 `~/.claude`）。**探不到 ccusage 就整块不画**；5h 窗口没有 active block 时只显示今日（`blocks` 为 null 不再挡住整条）。刷新循环挂侧栏组合（30s 一查、5 分钟一刷），侧栏收起就停。
  · **附件与图片**（新 `Attach.kt` + ChatPane 输入区）：输入框左边回形针选文件（AWT FileDialog 多选）、**剪贴板有图时 Ctrl+V 直接贴图**（截图直进对话，Codex 手感）；chips 显示进度 / 失败原因，✕ 取消；发送时走 core 的 `Attachments.header`（`[图片1.png] 路径` 映射贴正文前），传着的不让发。历史消息里的引用照手机端 `parseRefs` 渲染：图片 SFTP 拉回来真显示（进程级 LRU 缓存 `RefImages`），附件显示名字、点一下复制路径。
  · **协议层搬进 core**：`Attachments.kt` / `Uploader.kt` 从 app 模块 `git mv` 到 core（唯一改动 `app.yxi.ui.t` → `Tr.t`，Android 启动时 `Tr.fn` 已接到同一个翻译），手机桌面同一条上传管线（重试 / 断点续传 / 卡死看门狗全保留），FQCN 没变，Android 侧零改动。桌面新增 `AttachmentsTest` 把线上的协议钉死（头格式 / parseRefs 连发合并 / renumber / dirFor 白名单 / remotePath 清洗）——`safeName` **允许空格**（只禁 shell 元字符和 `/`），别再猜错。
  · **一键装公钥**（新 `InstallKey.kt` + HostsPane `CopyIdDialog` + 主机菜单「装公钥免密…」）：用密码连一次，把 Yxi 的公钥 append 进目标机 `authorized_keys`（core 的 `installPublicKey`），装成自动把主机切到桌面版自己的私钥 `Store.dir/id_ed25519`（jsch `KeyPair.genKeyPair` 生成 ed25519，失败退 RSA 4096；跟用户 `~/.ssh` 的主密钥分开）。指纹核对复用侧栏那套 `FileHostKeys` 弹窗 —— 没核对过指纹的机器绝不写公钥。HostForm 密码模式下加了一句指引。
  · **验证**（迁移后第一次在这台机上跑全套）：`:desktop:compileKotlin` 绿、`:desktop:test` 绿（25 个，含新协议 6 个）、`dev/desktop-e2e.sh` 过门禁；另外**真连了一次**：hosts.json 塞 hk13 自己 → 指纹弹窗 → 连上列出全部 cc-* 会话 → 用量条「今日 $1.67」→ 弹窗三条数据对 → 贴图上传 → 发进 tmux 靶会话，pane 里收到的正是 `[图片1.png] /root/src/tmp/e2e-scratch/….png` + 正文（文件真在服务器上，发送时编号 0→1 重排也对）。⚠️ 桌面 tofu 方块是**这台 Xvfb 环境缺 CJK 字体**，Windows 真机没这回事（「配置」「我的」一直渲染正常）。
  · **抓到一个真 bug（#328 的教训再现）**：`UsageStrip` 原来把「快照为空就 return」放在 `LaunchedEffect` **前面** —— 刷新循环根本不进组合，永远没有第一轮数据。**「还没数据时也要先跑起来的循环」必须在 early return 之前组合**。只有真跑才看得见，编过 + 逻辑自查又一次漏掉。
  · **环境（09-09 迁移后丢了构建链，已按 desktop-e2e.sh 的文档装回）**：`openjdk-17-jdk-headless` + `openjdk-17-jre`（⚠️ 只装 headless 会 `HeadlessException: no headful library support`，缺 `libawt_xawt.so` —— 必须再装非 headless 的 jre）+ `xvfb xdotool xfwm4 imagemagick xclip`。gradle 用 `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`。
  · 没做（下轮候选）：diff 查看器（PRD P2，手机端 GitDiff 已有可搬）、Snippets 常用语、TempSessions、从机（Slave）、文件页 md 渲染/源码切换对齐手机 FileViewer、装公钥后深色模式下的成功态文案、`%APPDATA%` 密码接 DPAPI（Bug_solver 的 P2 遗留）。

- **桌面版第三轮：对话界面学 ZCode / Codex（2026-09-11，老板发来两张截图：「看看 ChatGPT 和 zcode 的 ui……逆向学习一下，也要有我们 yxi 自己的风格」）**。09-08 的拆包报告（§2.3 Codex 视觉规范：灰阶 / 字号 / 圆角 / composer 投影）直接当规格用，没重新拆包；对照截图把 ZCode 的版式学到手，配色仍用 Yxi 的 Claude 暖灰 token（自己的底子）：
  · **composer 卡片化**（两家最大的共性）：OutlinedTextField 换成一张圆角卡（surface1 底 + 描边，聚焦描边变 accent），无边框输入区 + 底部功能行 —— 左「+ 附件」，右侧 **模型 / 强度 / 模式 / 上下文 chips + 圆形 ↑ 发送**（Codex 的 ↑）。chips 数据来自 `Transcript.Incremental.ctx`（解析转录顺带就有的：模型名、max/high/mid→最大思考/高强度/中等、plan→计划模式、上下文 tokens），零额外请求；空值不显示（宁缺勿假）。Enter/Esc 的审批语义不变。
  · **工具调用行变轻**（ZCode 式）：原来是常驻描边卡片，现在一行轻量行（状态点 + 工具名 + mono 摘要 pill，「完成」字删了 —— 绿点本身就是信息），点开才出详情；同名合并组同款。思考过程折叠行加 ✳ 前缀、摘要行也收掉。
  · **忙时计时行**（ZCode 的「工作中 48秒」）：`BusyLine` 本地计时（转录里没有这个数据，计时器就是唯一来源），带 live 状态文字。
  · **会话头**（ZCode 顶栏形态）：任务名 + 主机 pill（圆角 50 的小胶囊）+ 连接徽标；窗口标题（Shell）本来就会带会话名。
  · **侧栏搜索框**（ZCode 搜索 / Codex 过滤）：主机列表上方一个小 pill 输入框，按会话名 / 目录 / 主机名滤，主机组滤空整组藏掉。Ctrl+K 聚焦搜索还没绑（快捷键表要一起改，下轮）。
  · **验证**：compileKotlin / 25 个单测 / desktop-e2e 门禁全绿；真连 hk13 截图验收 —— 会话头 pill、composer 聚焦态、glm-5.3-flash · 最大思考 · 上下文 38K chips、用量条「5h 剩 0h00m 今日 $1.68」全部按设计渲染（这轮 5h 窗口真烧满了，条和文字都对）。

- **通知对齐 ZCode（2026-09-12，老板截图：「这个提示我们电脑上要做」）**：完成通知改成 ZCode 的措辞——标题「任务已完成」、正文「任务: <会话名>」（原来是「<会话名> 轮次完成」+ 转录尾巴）；等批准/等输入 = 标题徽标文案（等待批准/需要用户输入）、正文「任务: X · 详情」，都带 hostId+会话名。**点气泡/Toast 跳会话**：TrayIcon 加 ActionListener（Windows 上点系统通知触发）→ `Notify.clicked()` = `Shell.show()` 叫回窗口 + `Notify.open(hostId, session)`（Main 接线：按 id 找到主机/会话 `state.select`，select 本身会把页面拉回工作区）。⚠️ toast 的渲染和点击只能在真 Windows 上验（Xvfb 没有系统托盘，`SystemTray.isSupported()=false`，YxiTray 直接不装）——发版后老板装机顺带验。

- **桌面版换皮：跟手机版同款（2026-09-12，老板：「windows 的 app 的 ui 也还是用手机版同款的」）**。桌面 `Theme.kt` 的 token 取值整体搬自手机端 `ui/theme/Palette.kt`，桌面组件只认 Tokens，所以换的只是值：accent = 手机 Copper（深 #FFB787 暖铜 / 浅 #0B57D0 Google 蓝）、success = Teal、warning = Amber（「需要你动手」独占色相的规矩照旧）、danger = DiffDelFg；用户气泡 = CopperContainer（深 #6D3A10+FFDCC4 字 / 浅 #D3E3FD+041E49 字，手机端「你的消息」同源），新增 `userBubbleText` / `onAccent` 两个 token。圆角照手机 YxiShapes 调大（基础 8→14、composer 14→22）。正文照手机 AiryType 的「呼吸感」提到 15/24（Markdown.kt BodyStyle——手机原话：光换色不改行距，看着还是「另一个 app」）。发送圆钮点亮时用 Copper 底 + OnCopper。结构不动：上一轮学的 ZCode 版式（composer 底部功能行 / ctx chips / 轻工具行 / 会话头 pill / 搜索框）全保留。**深浅两套都在 Xvfb 真连截图验过**（prefs.json theme=dark/light 各跑一遍：暖铜描边、tab 铜下划线、青色已连接点、浅色蓝灰侧栏+Google 蓝 tab 全对）。

- **ThinkingGlow 搬上桌面（2026-09-12，老板：「手机版对话界面思考时有渐变色流动，直接 copy 那份小巧思」）**：新 `desktop/ThinkingGlow.kt`，从手机端 `ui/ThinkingGlow.kt` 原样移植（桌面没有系统动画缩放，motion 恒 true）：待机 = 底部一团蓝色聚光（0.55）；思考 = 650ms 铺满顶部 + **色相 6 秒一圈蓝→青→绿→黄→橙→粉**（四团光各自独立相位 7.3/9.1/11.7/13.9s，互不成整数倍——手机端 #186 的教训一起搬了）；等你拍板 = 不循环、定在琥珀；回答到达 = 退到 0.35 让位正文。`glowBrush`（composer 同色相流动底，透明度压在 0.10/0.16）也一起接上。手机端的 `GlassPill` 玻璃壳（呼吸光晕/斜向光带）**没搬**，想要的底下那份代码还在。挂在 ChatPane 对话画布 Box 的最底层（matchParentSize，不参与布局）。**验证**：单测/e2e 全绿；Xvfb 真连截图 + 相隔 4 秒两帧像素 diff（64% 采样点在变）证明光是活的；「思考满色相」那档要等真有个会话在跑才能看到，逻辑逐行照抄手机端，风险低。

- **五代理交叉审查 + 发版 1.1.0（2026-09-12，老板：「五个子代理交叉验证没问题后就直接发新版」）**。五个只读审查代理并行（①core 移动对安卓的影响 ②附件/装公钥/用量逻辑 ③对话 UI+Glow 移植忠实度 ④通知/窗口壳 ⑤发版流水线），无 P0，**7 处 P1 全修**（d7cd992）：
  · **装公钥假成功（最重）**：core 的 `exec` 把连接类异常吞成空串、`installPublicKey` 从不抛——append 根本没执行也报「装好了」，还会把主机切到坏密钥。修：写完**回读验证**（`grep -qxF` + echo 哨兵，line 是 base64 无单引号可安全内插）；`ensure()`/`toConfig()` 的文件 IO 圈进失败路径（原来裸奔在 try 外，异常直接带崩进程）；`@Synchronized` 防并发生成钥匙；早退路径也补私钥权限。
  · **ChatPane 会话状态键**：`remember(session.name)` 全部改 `remember(conn.host.id, session.name)`——两台主机同名会话（tmux 名=cc-<目录名>，同名太容易）会串 draft/暂存附件，**把 A 机的附件路径发给 B 机的 Claude**。
  · **Enter 绕过上传门**：圆形按钮的 canSend 有 `!uploading`，键盘 Enter 没有——附件传着按 Enter 会发出映射不全的消息。send() 开头补同样的门。
  · **Ctrl+V**：keydown 里的 `clipboardImage()` 会完整解码图片（大图卡 UI、按键重复贴好几张）。改成便宜的 `isDataFlavorAvailable` 预检 + `pasteBusy` AtomicBoolean 防重入，真正的取图+编码一次性在协程里做。
  · **通知 click 劫持**：click 载荷在三档守卫**之前**赋值且永不过期——「从不」档下从没弹过通知，双击托盘图标却会跳到一个不相干的会话。修：先过守卫再武装 + 10 秒时效（AWT 区分不了点气泡和双击图标）。
  · 小修：UsageCache 容错改「重抛 CancellationException」（不然侧栏收起会把好缓存覆盖成全 null）；glowBrush 端点是手机年代的绝对像素，宽 composer 卡会出现「冻结线」，改 Mirror 平铺；通知 detail 先压一行再截（防 emoji 代理对截半）；装公钥弹窗 busy 中禁用取消；删死代码 lastAssistant。审查还确认了：ThinkingGlow 移植逐参数忠实（桌面还顺手修了手机端负 hue 的 bug，可反向移植）；installPublicKey 的指纹弹窗链路真实有效；升级链 1.0.1→1.1.0 无断点。
  · **安卓侧回归一处**：core 移动后 ShareActivity 冷启动没接 Tr.fn（SEND 入口可从全新进程进），EN 用户的分享文案回退中文——一行补上（审查①抓的，只有 EN 用户+分享冷启动能撞到）。
- **左下角账号行 + 版本号（2026-09-12，老板发 Codex 截图：「点击头像会有设置展开」，且要显示版本号）**：侧栏最底加一行（Codex 同款）——字母头像 + 名字 + 会员档 + 版本号，点开向上弹菜单：版本信息行（不可点）/ 使用情况 / 配置 / 设置… Ctrl+, / 退出登录（未登录则显示「登录」）。头像用首字母，不异步拉网络图（完整资料在「我的」页）；版本取 Updater.version（jpackage.app-version；桌面直接 java -jar 跑时没有这个属性，显示「开发版」）；设置弹窗底部也加了同一行版本。MeAuth.load 挪进侧栏启动时拉一次（原来只在 Me 页拉，账号行首屏才有的看）。Xvfb 截图验收：菜单向上翻转、各项可点。已有 1.1.0 的用户要等下一个版本（1.1.1+）才看得到这行。



- **桌面终端换真终端（2026-09-12，老板发截图：「终端显示有点奇怪，终端模式不要输入框，要像服务器本地终端/VSCode remote」）**：TermPane 整个重写（d810525）——JediTerm（JetBrains intellij-dependencies 仓，`org.jetbrains.jediterm:jediterm-core+ui:3.3`，Swing 控件用 `androidx.compose.ui.awt.SwingPanel` 嵌进 Compose）+ core 的 `openPtyCommand("tmux attach …")` PTY 直连。全色彩、全键盘直通、拖窗即 resize（Shell.resize）、**输入框删除**；切走即断 attach（tmux 会话照活）。踩的坑，个个记牢：① jediterm 的 POM 是残缺的——**core 不声明、slf4j 也不声明**，必须显式引 `jediterm-core:3.3` + `jediterm-ui:3.3` + `slf4j-api`（+`slf4j-nop` 静音），漏了就是运行时 NoClassDefFoundError（org/slf4j/LoggerFactory 那种）；② 仓库的 jar 下载偶发 0 字节，curl 要 `-L` + 重试；③ `TerminalColor(int)` 是**调色板索引**不是 RGB——把 0xEBE1D9 塞进去会在渲染时越界断言炸掉整个 EDT（表现：终端面板空白），RGB 用 (r,g,b) 三参；④ **TtyConnector.read 必须 UTF-8 解码**（InputStreamReader 挂流上，官方 ProcessTtyConnector 同款）——latin1 逐字节塞会让中文全乱码；⑤ SwingPanel 在 `androidx.compose.ui.awt`（1.12 里不在 desktop 包）。**验收**：Xvfb 真连 cc-boomassets_logto → 终端 tab 显示彩色 Claude Code TUI → xdotool 敲 JEDITERM-中文-OK-4471 正常回显执行；桌面输入法中文进 tmux 的体验等真机（NSimSun 首选字体，缺字形的符号可能显示方框）。手机端反向可借鉴：JediTerm 不用动 tmux 的 window-size，attach 断开自动回弹。⚠️ CI「真装真启动」步骤仍压在 hk13 工作区未提交（.github/workflows/desktop.yml，workflow scope 阻着），谁先补了 scope 谁推。


- **桌面登录 invalid_redirect_uri 修复（2026-09-12，老板真机报 Logto 错误）**：桌面登录的回调端口是**随机**的（`ServerSocket(0)`），而 Logto 的 redirect_uri 必须与注册的精确匹配——「Yxi App」里只注册了手机的 `io.yxi.app://callback`，桌面的 `http://127.0.0.1:<随机端口>/callback` 永远对不上。修两头：
  ① Logto 侧给「Yxi App」（Native，g6ydpvvn833z0ua19n6hz）**补注册 `http://127.0.0.1:1455/callback`**。⚠️ 这版 Logto 的 PATCH /api/applications/{id} 要把 redirectUris 放 **`oidcClientMetadata`** 里，放顶层会被静默忽略（管理台/文档说的顶层字段在这版不好使）。M2M 凭据在 `/etc/yxi-member.env`（LOGTO_M2M_ID/SECRET，换 token 走 127.0.0.1:3201/oidc/token）。
  ② 桌面 MeAuth 回调端口改**固定 1455**（照原定 Codex 的做法），绑定失败给人话报错。
  核验：授权端点对 redirect_uri=http://127.0.0.1:1455/callback 从 400 报错变 **303 进登录页**。⚠️ 这个 redirect 注册**没进任何仓库**——Logto 是运行态配置，重装/换实例要重新注册（logto_yxi 仓的文档也该补一行）。

- **发版 1.2.0（2026-09-12，run 34683867397，head 517cfd6 绿）**：1.1.1 之后的全部——真终端（JediTerm PTY）、左下角账号行+版本号（设置弹窗也有版本）、登录固定 1455、ZCode/Codex 版式、手机同款皮肤（暖铜/参考款浅色）、ThinkingGlow、通知跳转。发布链路：bump → workflow_dispatch → 本机（老板 Windows）gh run download → Setup.exe --silent 真装 → current/Yxi.exe --smoke 打出 smoke ok → scp Releases/ 到 /var/www/yxi/desktop/ → 公网 feed=1.2.0、nupkg SHA256 逐字核过（9BAEAF32…1EB）、官网下载页的版本 chip 现取 feed 自动跟上。用户升级路径：1.1.1 的应用每 6 小时/启动 10 秒查 feed，弹横幅重启即升；1.1.0 及更早的受损安装直接重跑 Setup.exe。

- **下载页挂上 Windows 版（2026-09-12，老板：「把 windows 的下载挂载到 yxi.keuury.com」）**：首页下载区在 Android 卡和 iPhone 灰卡之间加了 **Windows 卡**（同款样式：徽标/「现在可用」badge/chips/一键安装说明），按钮直链 /desktop/Yxi-win-Setup.exe；首屏幽灵按钮加「用 Windows？装桌面版」；区块小标题改成「Android 和 Windows 现在就能装」。版本号 chip 现取 /desktop/releases.win.json、大小 chip HEAD Setup.exe（和安卓侧 latest.json 的取法同款，发新版不用动页面）。注意：这个 landing 页**只在 hk13 /var/www/yxi/index.html**，不在仓库里（site/ 只有 privacy/terms）——改前备份 index.html.bak-win-20260912。


- **1.1.0 真机「Failed to launch JVM」→ 1.1.1 热修（2026-09-12，老板装机截图报错）**。根因：Compose 的 createDistributable 精简 runtime **缺 java.net.http 模块**，Update.kt 的 HttpClient 一碰类初始化就 NoClassDefFoundError。CI 的 --smoke 和 hk13 的全部验证都用完整 JDK 跑 uber jar（模块永远在），只有真机的 jpackage runtime 会炸——「到处都绿、真机第一枪就倒」的教科书案例。排查路径（都值得复用）：解包 1.0.1/1.1.0 两个 nupkg 逐文件对比（cfg 结构/文件清单/CRC/runtime 文件数全一致→排除包体）→ 在老板机器上用 ctypes 直接加载 jvm.dll/jli.dll（正常→排除依赖 DLL）→ **直接跑装好的 Yxi.exe --smoke，完整堆栈当场现形**（launcher 弹框不打印，但子进程的异常会走到 stdout）。修：desktop 的 nativeDistributions 加 includeAllModules = true（不再精简，Setup.exe 114MB→141MB），bump 1.1.1（688c372）。**验证闭环就在老板这台报错机器上做的**：静默装 1.1.1 → 装好的 Yxi.exe --smoke 打出 smoke ok 退出码 0 → 已发布，公网 feed=1.1.1、SHA256 逐字核过。⚠️ 遗留两件：① CI 加「真装真启动」步骤（干净 runner 上静默装 Setup 后跑 Yxi.exe --smoke）的改动**推不出去**——hk13 和本地的 GitHub 凭据都没有 workflow scope，改 .github/workflows 的提交被 remote 拒；补 scope（gh auth refresh -s workflow）后把 hk13 工作区里 .github/workflows/desktop.yml 的未提交改动推掉即可。② 精简 runtime 的正确姿势是 modules("java.net.http") 按需声明（省 ~40MB），现在偷懒打全了。

- **发版 1.1.0（0bee8a3）**：`packageVersion` 1.0.1→1.1.0（三处同源：cfg→vpk→运行时）。走 `workflow_dispatch`（tag 触发从未实测过，首发不冒险）；publish.sh 在哪台机都跑不起来（hk13 缺 gh、本地 Git Bash 缺 rsync——以后要么 hk13 装 gh 要么本地补 rsync），这次用本地 `gh run download` + `scp` 手工替代（rsync 无 --delete，scp 等价）；发布后核 releases.win.json 版本字段与 nupkg SHA256。**已发（run 34635459274，head 0bee8a3 绿）**：公网 releases.win.json = 1.1.0，nupkg SHA256 与 feed 逐字相等（8EBE0D54…C01A），Setup.exe 200（114587572 字节，比 1.0.1 变化）；旧 Yxi-1.0.0.msi 原样保留。真机验收清单：装 Setup.exe / 1.0.1 自更新跳 1.1.0 / 系统通知措辞与点击跳转 / 真批一次权限 / 思考满色相背景光。

## 音游（云曦节拍）设计拍板 —— 2026-09-06，老板在网页试验台上选的

> **试验台 2026-09-06 晚封版**（老板：「现在试玩台没问题了」），tag `bench-final-20260906`。两个没单独拍的默认值就此定下：音符用薄片（高度 1）、无线时刻最高档每一下 25% 触发。**下一步：按试验台重写 App 的音游画面**（cc-Yxi 做，判定 / 算分 / 上报不动；谱面参数和编舞关键帧的契约找 logto）。
> 试验台：`design/rhythm-bench.src.html` → `bench-assemble.py` → 线上 https://yxi.keuury.com/lab/rhythm.html
> 画廊：碎裂 `/lab/shatter.html`（20 款）· swipe 标记 `/lab/swipe.html`（20 款）· 点击特效 `/lab/hit.html`（30 款）· **碎裂音效 `/lab/shatter-sfx.html`（20 款，09-07 老板要的，待挑；源 `design/sfx/shatter/`，四个家族 gen_*.py 可重跑，`assemble_page.py` 拼页）**
> 模块在 `design/shatter/`、`design/swipe/`、`design/hit/`，统一契约 `draw(ctx, …, t, rng)`：无状态、确定性随机、t=1 全透明。

- **背景**：对话页 `ThinkingGlow` 的移植（四团光独立相位、色相 6 秒一圈蓝→青→绿→黄→橙→粉）；连击越高从「待机 .55」升到「思考 1.0」；跳档闪「等你拍板」的琥珀。半径 / 明度按深底横屏改过（原数会糊成一块灰）。
- **判定线**：纯白发丝线，不发彩光（对着老板录的 Phigros 逐帧抄的）。
- **音符**：薄片胶囊 + ‹ › 尖角；四色 tick 淡橙 / slide 浅蓝 / trace 樱粉 / swipe 浅绿。
- **swipe 的左右标记**：「反向尾迹」「风偏」两款**随机**出现（老板从 20 款里收藏的）。
- **碎裂（音符本体散掉）**：方粒四散 · 切片错位 · 冲击光尘 · 扫描消散 · 溶解成光 · 樱瓣飘落 · 水晶棱柱 · 压扁弹回 —— 八款**随机**（老板从 20 款里收藏的）。
- **点击特效（线上那一下）**：快门 · 六边框 · 三角翻转 · 均衡器 · 方粒 · 火花 · 螺旋尘 · 聚爆 · 碎环 —— 九款**随机**（老板从 30 款里挑的；Phigros 式那款退役，「其他的粒子效果有点素」）。slide 按住期间复发的小号爆点也从这九款里出。
- **规则**：slide 按住期间不再出任何别的块；**trace 不用点**，落线时手指按在那条轨上就算（= Phigros 的 drag）；判定窗 120 / 240ms；连击 8 / 15 / 30 三档倍率 + 评价词（老板的参考图字体：粗斜体白字 + 首字母橙 + 黄橙蓝三道斜线）。
- **老板调好的一组数（2026-09-06，已设为试验台默认）**：爆点 大小 1.1 / 粒子 12 / 时长 0.6s · 热度 强度 1.1 / 满热连击 30 / 底光亮度 1.75 · 校准线 摆动 1 / 回正 2.7 / 谱面摆动 1.7 · 音符 宽度 1.2。
- **下落时长（1.55s）和密度（1.25）是谱面·难度的参数**（老板：「根据关卡和难度来定义更好，比较容易随时改动」）—— 进 App 时从谱面 / 难度配置读（logto 的谱面契约），不做全局常量。
- **判定线编舞**（老板）：线会转、上下左右走、翻面，甚至立起来成一条持久的竖线；**音符永远垂直于线、沿法线飞来**（场地整体画在线的坐标系里，输入也按线的坐标系算——线立着时「左右滑」= 屏幕上下滑）。试验台里「不动 / 轻 / 疯 / 竖线」四档；进 App 时编舞是**谱面数据**（关键帧：角度 / 位置 / 时长）。
- **无线时刻**（老板）：只在最高档（≥30 连击）可能触发（试验台：每一下 25%），**一局最多一次**；判定线闪烁 0.8s 后消失，之后**全屏皆可校准**（点哪都算、swipe 不看方向、trace 任何手指按着都算），随机持续 10~13s，结束前线闪回来。
- **名字**：左下角英文 **Yxi Dancing Beat**（老板要英文、yxi…dancing）。**连击数字和倍率用评价词那套字**（粗斜体白字 + 深色描边）。
- **九款点击特效统一手感**（宿主层包的，不改各组代码）：尺度归一到 ≈4.6u、共用淡出包络（头 0.15 全亮后 (1-t)^1.2）、共用起手白芯闪。
- **命中时的竖向校准线**（老板）：在音符中心竖一道白线再淡出（垂直判定线、±10° 偏转、0.28s），**概率按档位走 30% → 50% → 70% → 90%**。
- **slide 按住期间有持续特效**（老板：不然长按没体验感）：线上那点呼吸发光 · 每 0.35s 一圈涟漪 · 光屑沿长按体上飘 · 体内光带上流 · 每 0.22s 在线上补一次小号点击特效（Phigros 的做法）。
- **swipe 的判定放宽**（老板）：时间窗 ±360ms（其它音块 ±240），手势门槛划过 3% 屏宽、0.45 秒内。进 App 时 `Rhythm.kt` 的窗口要按种类分（现在是一刀切的 GOOD_MS）。
- **底光节奏**（老板定稿）：每次打中闪一小下（150ms，+0.15）；跳档闪得更猛（600ms，+0.9，带琥珀）**而且亮度保持在新档上**（每档 +0.2，断连归零）。
- ⚠️ **手感（判定宽窄 / 音效延迟 / 震动）不在网页上定**，那三样浏览器和原生差最多，留在 App 的手感面板。

## 音游画面按试验台重写 —— 已完成（cc-Yxi，2026-09-06 晚 → 09-07 凌晨；在待发版清单里）

**目标**：`ui/RhythmScreen.kt` 的画面部分照 `bench-final-20260906` 重写；判定 / 算分 / 上报（`agent/Rhythm.kt` 的 hits 序列、`Live`、`hitLane`/`judgeMisses`）不动，服务端不用改。
**拆法**（新包 `ui/rhythm/`，每个文件能单独编译）：
1. `StageGlow.kt` —— 底光（ThinkingGlow 移植：四团独立相位、色相 6s 一圈、连击→亮度、每下小闪、跳档猛闪+琥珀+台阶）
2. `Notes.kt` —— 薄片音符 + ‹› 尖角 + trace 抓握纹 + swipe 两款标记（反向尾迹 / 风偏，随机）
3. `HitFx.kt` —— 九款点击特效 + 宿主层统一（尺度归一 / 淡出包络 / 起手白芯）
4. `Shatter.kt` —— 八款碎裂（命中时音符本体散掉）
5. `Words.kt` —— 评价词 / 连击数字 / 倍率的字（粗斜体白字 + 深描边 + 首字母橙 + 三道斜线）
6. `RhythmScreen.kt` 改：判定线纯白发丝；编舞（去 ±9° 限制、加 MOVE_X、竖线 / 翻面；输入按线的坐标系）；长按持续特效；竖向校准线按档概率；无线时刻；trace = 落线时按着就算；swipe 窗口 ±360ms + 手势 3% / 0.45s；slide 期间不出别的块（这条是谱面生成的事，标给 Entertainment 的脚本）
7. `Rhythm.kt` 改：`LineOp.MOVE_X`；`Chart.approach`（谱面字段，没有就用默认）；`SWIPE_MS`
**关卡**：opus 审查 + Mac 模拟器 E2E（判定序列长度 == units 不变、服务端不打回）+ 全套测试；真机由老板验手感。
**接手要点**：Entertainment 交接的六条坑在 2026-09-06 的 hub 消息里，摘要：固定深色不用主题 getter · 衰减按时间 · 每帧状态别进 composition · `next[lane]` 只让 judgeMisses 写 · 覆盖层走 Dialog · 结算用 currentScore()。

## 进度
- ✅ **1.1.12（versionCode 168）已发布**（2026-09-05）：
  - **对话页跳到几周前对话修复（#273 真根因）**：心跳空行字节漂移 → offset 超文件大小 → GNU tail `file truncated` 从 0 重放。修：空行不计字节 + `streamFrom` 起点夹到文件大小。Mac E2E 三轮全过。
  - **一键装机不再顺带装 OpenCode / Hermes**：bootstrap.sh 改 opt-in。
  - **时区设置**：设置 → 界面 → 时区，全 App 绝对时间走 `agent/Tz.kt`。
  - **工单中心搬进会员服务 + 分类**（cc-Yxi_Entertainment）：走 `/api/support/tickets`，分类 + 未读红点 + 回复串 + 追问重开。
  - **线路 v2**（cc-Yxi_pilot）：整段 settings 片段 + 预设 + 五开关 + 高级 JSON。
- ✅ **1.1.11（versionCode 167）已发布**（2026-09-04）：
  - **立绘改成整图不裁**（老板：「不要截图、不要截一部分出来，要保证它是完整的」）。
    ⚠️ 量过：九张原图**全是 16:9 横构图**（1.7917），**不是 9:16** —— 老板自己也不确定，别硬套。
    卡面重做成 960×536 整图（九张 626KB，比裁过的还小），卡牌库从两列竖版改成**一列横版 16:9**，
    渲染 `Crop` → **`Fit`**。教训见 TROUBLESHOOTING #251（先看素材再定版式）。
    ⚠️ **原图在 `/root/src/tmp/Yxi/0904-23*.png`，那是附件暂存区、3 天自动清**。
    cc-logto_yxi 已把 1100 宽的缩图收进 `logto_yxi/design/art/`。要长期用从那儿取。
  - 修 **#250 会话里点开侧边栏关不掉** —— Material3 的「点遮罩关闭」跟 `gesturesEnabled` 绑在一起，
    一律 false 把开和关一起掐了。改成 `gesturesEnabled = drawer.isOpen`。
- ✅ **1.1.1（versionCode 157）已发布**（2026-09-04）：**祈愿记录页**（`ui/WishHistory.kt`，
  由 cc-Yxi_pilot 写，我接的入口和译文）。一次祈愿一张卡，四档颜色跟抽卡当场一致 ——
  ⚠️ 为此把 `WishScreen` 的 `rank/rarityColor/rarityLabel` 从 private 开成 internal：
  **同一个金色不能有两份**，抽卡当场一个金、翻记录另一个金，人只会以为自己记错了。
  实测：真十连的 10 条全在（7 个曦光 · 蓝色开屏 NEW · 紫色 Pro 体验 1 天 · 红色云曦 NEW）。
- ✅ **1.1.0（versionCode 156）已发布**（2026-09-04）：**祈愿开张 + 一批 bug**。
  - **《神之冠冕》卡牌库**：老板给的 **9 张立绘**已切成竖版卡面打进包
    （`res/drawable-nodpi/card_<id>.webp`，共 1.2MB）。名单 10 位，首期 UP 云曦；
    幻蝶没立绘，用几何纹章顶着。未获得 = 去饱和压暗，一眼分得出。
  - **抽卡动效**（老板要「够吸引人」）：暗场 → **星轨划过，颜色 = 这一批最高稀有度** →
    光爆 → 立绘整张升起 → 其余逐条翻面。随时点一下跳过；系统关动效就直接给最后一帧。
    **真十连实测过**：保底第 10 抽出云曦，红星轨 → 红光爆 → 云曦立绘，全对。
  - 四档配色按老板定的：红=角色 金=曦光 紫=稀有装扮 蓝=普通装扮。
  - **价钱照抄服务端**（`tenPullCost` / `singlePullCost`，ultra 十连九折）——
    ⚠️ 客户端一行乘法都没有：算折扣 = 改包就能白嫖；而且对方踩过「页面说 9 实际扣 10」。
  - 修 **#248 多图分享报「没送出去」其实文件已传上去**（9 图分享压力测试通过：9/9、1.5 秒、消息真提交）
  - 修 **#249 ↓ 按钮点了没反应**（`when` 有两支是空的，只有注释没代码）
  - 修 从祈愿/邮件/趋势等整页点底部导航不跳转（这个坑今天犯了两次，已收敛成 `closeOverlays()`）
  - 修 签到拿的曦光不同步到祈愿页；输入框附件卡死导致发送按钮永久变灰且不说话
  - 会话卡**右滑 = 打开侧边栏**（跟手 + 左边露 ☰），不再是右滑收藏
- ⬜ **待办**：紫/蓝档装扮素材（终端配色 / 气泡 / 快捷语包 / 那 7 支闲置开屏）做出来给 logto 上池；
  角色「起源动画」由 cc-logto_yxi 做（从光丝里织出立绘），做完我照规格移植；
  抽奖历史页派给了 cc-Yxi_pilot。

### 测试号自助工具（cc-logto_yxi 提供，做 E2E 用）
```
ssh hk13 "cd /root/src/workplace/logto_yxi && python3 scripts/test-reset.py yxi-app-test@mail.yxi.keuury.com"
ssh hk13 "cd /root/src/workplace/logto_yxi && python3 scripts/test-grant.py yxi-app-test@mail.yxi.keuury.com --tickets 50 --tier ultra"
```
复位会把档位打回 free；要测九折记得补 `--tier ultra`。⚠️ 抽到云曦之后保底不再给她，
调红色出货动效要先 reset。
- ✅ **1.0.11（versionCode 155）已发布**（2026-09-04）：**祈愿（抽奖）+ 活动中心（签到）**。
  接口契约由 cc-logto_yxi 定稿：`logto_yxi/design/wish-checkin.md`。
  **接口一上线这两页自己就亮，不用再发版**（页面是动态读接口的）。
  - 代币叫**曦光**（1 曦光 = 1 抽），签到攒、**不卖钱**（合规 + 只保留兑换码一条收款路）。
  - ⚠️⚠️ **摇号在服务端，客户端一行随机数都没有** —— 奖池里有真东西，客户端摇 = 改包就能中头奖。
  - ⚠️ **概率公示读的就是发奖那个接口**（`/api/wish/pool` 的 `rate`），不在客户端写死。
  - ⚠️ **抽奖带 `requestId` 幂等键**：抽奖不是幂等操作（每抽扣一张曦光），网络重试会扣两次。
    一次「点击」一个 uuid，失败重试**复用同一个**，成功才换新的。
  - ⚠️ **奖池不放余额**（对方加的一条，草案里的洞）：余额等价现金，接在签到这个免费水龙头下游
    就是直接印钱，且随开号数量线性放大。会员天数可以放但服务端硬卡每账号每月 ≤3 天。
  - ⬜ **UP 是角色**（老板 2026-09-04）：第一个角色「云曦」。一个角色 = 一套装扮
    （立绘头像 + 专属光环 + 专属开屏 + 主题配色）。**立绘需要美术**，我画不了人物；
    先用「花瓣纹章」占位，等图。已发给 cc-logto_yxi 定 `kind: "character"` 和 `up` 字段。
- ✅ **1.0.10（versionCode 154）已发布**（2026-09-04）：**兑换成功动效「玻璃药丸展开」**
  （老板选定，规格与参考实现由 cc-logto_yxi 给：`logto_yxi/design/redeem-success.md`）。
  转圈 → 打勾 → 药丸横向展开，欢迎语从里面长出来；那颗药丸**就是输入框那颗 GlassPill**，
  三个循环（呼吸 2.6s / 光带 3.8s / 色相 6s）展开后继续跑。见 `ui/RedeemSuccess.kt`。
  - **两套文案各自实测过**（用后台真发的码，不是预览）：会员码 →「Welcome to / Yunxi Pro」+
    「会员有效期至 2026-09-05」；余额券 →「余额到账 / ¥1.00」+「当前余额 ¥1.00」，配色跟当前档位走。
  - ⚠️ 流动层不透明度 **0.20**，不是 STYLE.md §2.2 的 0.38 —— 那个值是盖在整页光晕上的，
    药丸近白底再叠 0.38 会变成一颗实心橙药丸。
  - ⚠️ 展开宽度用 `TextMeasurer` **实测文字宽**算，不写死倍数（「Yunxi Ultra」比「Yunxi Pro」长）。
  - 音效是**合成**的（AudioTrack，白噪声起音 + C6 + G6），和「勾开始描出」的 1020ms 对齐，**跟随系统静音**。
  - **重兑不放动效** —— 那一次什么都没加，庆祝它是骗人。

### 测试账号自助复位（做 E2E 时用）
cc-logto_yxi 提供，**不用再找他**：
```
ssh hk13 "cd /root/src/workplace/logto_yxi && python3 scripts/test-reset.py yxi-app-test@mail.yxi.keuury.com"
```
不传码面 = 清全部兑换记录；也可只清指定码。护栏：只认测试号前缀、拒绝 admin 账号。
测试账号密码在本机 `/root/.secrets/yxi-app-test.txt`（600）。
- ✅ **1.0.9（versionCode 153）已发布**（2026-09-04）：修**兑换框把码「整理」坏了**——
  它按「3-4-4-4」重排后再发给服务端，于是**只有恰好长成那样的码能兑**。
  现在原样收发，格式让服务端判（TROUBLESHOOTING #246）。
  **两条兑换分支已用后台真发的码实测**（cc-logto_yxi 给的通用测试码，9-11 失效）：
  - `YXITEST-BAL-100` → 「余额到账 ¥1.00 —— 当前余额 ¥1.00」，**档位不动**；
  - `YXITEST-PRO-1D` → 「兑换成功：PRO 1 天，到期 2026-09-05」，**余额不动**；
  - 「我的」上：会员 Pro（头像出现 Pro 光环）· 钱包 ¥1.00 · 邮件红点 2 · 两封信内容正确。
- ✅ **1.0.8（versionCode 152）已发布**（2026-09-04）：
  - **修掉一个潜伏很久的严重 bug**：**连过 SSH 之后整个进程的 HTTPS 全废**
    （账号接口 / 检查更新 / 会员中心悄悄失败，日志里只有一句「没拿到 token」）。
    根因是为 Ed25519 动 JCA provider + 安卓 `DefaultSSLContextImpl` 懒初始化且**失败被永久缓存**，
    所以出不出事取决于「第一次 HTTPS」和「第一次连 SSH」谁先谁后。见 TROUBLESHOOTING #245。
    ⚠️ 强制登录上线之后这个 bug 会把人**卡在门外**，是这一版最要紧的修复。
  - **「我的」做成个人主页**：资料卡（头像/昵称/签名/UID）· 会员中心 · **钱包余额** ·
    **趋势**（按天的 token 用量柱状图 + 每天花了多少美元 + 那天用了哪些模型）·
    四宫格（邮件 / 祈愿 / 活动中心 / 工单）· 最底下「设置」。
    **设置独立成一页**（侧边栏齿轮或「我的」底部进），装的是版本 / 手机功能 / 界面 / 关于。
  - **钱包 + 站内信接上后台**（形状由 cc-logto_yxi 定并已上线，见 `logto_yxi/design/wallet-mail.md`）：
    `wallet.balanceCents`（**分**，整数）· `unreadMail`（图标红点）·
    `GET /api/mail?before=<id>` / `POST /api/mail/<id>/read`。
    兑换码**按 `kind` 分支**：`balance` 走「余额到账 ¥X」，`membership` 走原文案。
  - **头像能换了**：相册选图 → 裁方 → 存本机。⚠️ **只在这台手机上**，跨设备要等对象存储
    （R2 凭据卡在老板那儿，见 cc-logto_yxi 2026-09-04）。
  - 界面：竖线全去掉（主机卡 / 会话卡），收藏·置顶·两者·无 = 淡蓝·亮橙·薄荷·白，四种一眼分得开。
  - ⬜ **祈愿 / 活动中心只有 UI**：后台没定形状（老板只说「先做图标」），点进去写「还没开」。
- ✅ **1.0.7（versionCode 151）已发布**（2026-09-04）：
  - **登录门禁**：进 App 先看本机有没有 refresh token，没有就整页挡住（用户拍板「没登录不允许使用」）。
    判据是**本机凭证不是网络请求** —— 拿网络当门禁 = 地铁里打不开自己的 App。返回键退出 App。
    ⚠️ **全链路实测过**（模拟器 + 真账号）：门禁 → 浏览器 → Logto 登录 → `io.yxi.app://callback`
    回跳 → 换 token → 门禁自动让开 → 冷启动不再要求重登。
    ⚠️ 令牌**强制轮换**，并发刷新会撤销整条授权 —— 刷新已串行化，见 TROUBLESHOOTING #239。
  - **开屏动效换成新 logo**（旧的四支画的还是上一版手写 Yunxi 字）：
    **深色只播「显影」，浅色随机「翻面 / 聚合」**，照实验室定稿逐帧移植（`ui/splash/SplashLogo.kt`）。
  - 界面：主机卡和会话卡的**竖线全去掉**（用户：「很丑」），在用的主机改成跟输入框同一条流动渐变，
    收藏 / 置顶改成淡淡染底；对话页顶上那条状态改成药丸。
  - 修：输入框换行那一下键盘会自己收起（#241）、刚登录完却显示「未登录」（#240）。
  - 构建慢的事一起治了：去掉 `--no-daemon`（堆已封 2GB + 空闲自杀），**空构建 30 秒 → 4 秒**，见 #244。
- ✅ **1.0.6（versionCode 150）已发布**（2026-09-04）—— 安全加固版，**换了签名密钥，必须卸载重装**：
  - **命令注入修完并实测封死**：会话名从 `tmux list-sessions` 和服务器上的 `~/.yxi/events.jsonl`
    （**agent 自己就能写**）一路流进 `tmux -t '…'`。现在所有插值点只走 `ssh/Shell.kt` 的 `q()`，
    两处信任边界再用 `safeName()` fail-closed。**红队 E2E 实证**：造带载荷的会话名 + 投毒事件各打 7 发，
    服务器上一个标记文件都没生成；对照组（合法名字）全部正常出现/弹通知。见 [SECURITY.md](./SECURITY.md)。
  - **开了 R8 混淆 + 资源压缩**：类名 136→6、`.kt` 文件名 3→0，APK 52MB→41MB。
    ⚠️ 混淆踩了 termlib 那个坑（TROUBLESHOOTING #234），**改 proguard 规则后必须真机跑一遍终端**。
  - **换了正式签名密钥**（RSA 4096 / 30 年，口令随机），密钥与口令在仓库外 `~/.secrets/`（600），
    已备份到用户电脑并记进他的基础设施笔记。**签名变了 = 装不上旧包，用户要卸载重装、公钥重新贴**。
  - **实验室 WebView 加了出网围栏**（CSP `connect-src 'none'`，`ui/WebFence.kt`）——
    实测原来 agent 写的页面能开 WebSocket 出公网（TROUBLESHOOTING #236）。
  - 顺手修：中文会话名被白名单误杀（30 个只显示 22，#235）、对话页状态带钻进状态栏（#237）。
  - 对话页顶上那条状态改成**药丸**，页眉 ⚡ 改成描边图标（用户：「看起来很违和」）。
- 📄 **安全审计与红队报告：[SECURITY.md](./SECURITY.md)** —— 威胁模型、每条问题的根因与修法、
  **红队实证的对照实验怎么打的**，以及一条有意暂缓的缺口（装机脚本没校验）。改动 App 前先看它。
- ⬜ **待办：开屏动效进 App**。实验室里已定稿三支（翻面 / 聚合 / 显影），规则也定了：
  **深色主题走「显影」，浅色随机播「翻面」或「聚合」**。目前只在实验室里，还没进 App 启动流程。
- ✅ **已完成**：**Moshi Android 3.10.0 APK 逆向**（`/root/inbox/base.apk`，解包 `/root/inbox/apk/`；Expo/RN + Hermes，字符串表可读 → 挖出会话枚举命令、云端+本地网关接口清单、Inbox SQLite 表结构，见 PRD §1 与附录 A）；[PRD.md](./PRD.md)；[PLAN.md](./PLAN.md)；全部技术前置在本机验证（PLAN §4）
- ✅ **方案已定稿（客户端形态换过一次）**：PWA → **Android 原生 APK**。原因：浏览器强制 CA 证书，走 SSH 就没有这个限制 → 整个证书 / Tailscale / 公网暴露的问题链消失（PRD §2.1）
- ✅ **App 内必须实现 SSH**（`mwiede/jsch`，纯 Java 不用 NDK）。**这是刚需，不是可选**：要能连**任意服务器，包括以后新增的**，在 App 里现加（PRD §2.4）
  > ⚠️ 早期版本一度写成"App 内不实现 SSH，只连自己的服务器"——**那是错的，已纠正**。别再退回那个结论
- ✅ **三个决策全部落定**：①先 Android，iOS 继续用原版 Moshi（装不上自签 App）②不用 Tailscale / CA 证书 ③安卓侧不并行用 Moshi
- ✅ **G1 完成**（2026-08-22）：Gradle 工程建好，debug APK 编出并在模拟器跑通，M3 深色主题生效。
  `android/`（AGP 9.3.1 · Kotlin 2.4.10 · Gradle 9.7.1 · compose-bom 2026.08.00 · **compileSdk/targetSdk 37** · minSdk 26）。
  配色写在 `android/app/src/main/kotlin/app/yxi/ui/theme/Color.kt` —— **改配色只改这一个文件**。
  产物 `Yxi-0.1.0-debug.apk`（11 MB）。踩的 3 个坑见 TROUBLESHOOTING #9–#11。
- ✅ **G2 基本完成**：**真终端跑起来了** —— `org.connectbot:termlib`（Compose 原生终端控件）
  接上 SSH shell channel，tmux attach 成功、**彩色输出正常**、连接稳定、URL 自动检测。
  ⬜ 剩：**IME 通路**（软键盘打字）——归到 G9 终端打磨一起做；真机上手指点才是真验证。
  ⚠️ 这一段挖出 5 个坑，其中 **TROUBLESHOOTING #16（jsch 写包路径非线程安全）是全项目最阴的一个**。
- ✅ **G3 完成**（实测通过）：App 内生成 ed25519 密钥（Keystore 加密保存）· 主机列表与加主机 UI
  （任意 IP / **任意端口** / 用户名 / 密码或密钥）· 一键装公钥 · **`known_hosts` 指纹校验**。
  验收全过：① 指纹与服务器 `ssh-keygen -lf` 一致 ② 二次连接不再询问
  ③ **篡改指纹后直接拒绝，不给"仍然连接"的口子** ④ **连上真实远程机 `station`（公网、只装了公钥）**。
  ⚠️ 挖出**两个安全漏洞**：TROUBLESHOOTING #21（双重编码导致 CHANGED 永远检测不到）
  和 #22（jsch 在 CHANGED 时也会问，点一下就能绕过）。**两个都是「专门测反向用例」才发现的。**
- ✅ **G4 完成**（实测通过）：会话看板三段分组（等你/干活中/已完成/空闲）+ 不进终端给**任意**会话发消息。
  一次 SSH 往返拿全部（带版本号的 marker 分段，抄 Moshi）。**服务器上不用装任何东西** ——
  `tmux list-sessions` 和 `~/.cloud-status` 都是现成的。
  实测：本机 16 个会话、station 3 个会话（远程、公网）都正确分组；
  长按会话发 `touch /tmp/yxi-g4-sent`，服务器上文件出现、tmux 有回显。
- ✅ **G5 完成**（实测通过）：**对话渲染模式**——读 `~/.claude/projects` 下的转录 JSONL（`tail -n N -f`），
  **不刮屏**。消息气泡 / 思考默认折叠 / markdown 渲染（`com.mikepenz:multiplatform-markdown-renderer-m3`，
  Compose 原生，不用 AndroidView 包 Markwon）/ 工具卡片（Bash 铜色 · Edit/Write 青色 · 带完成状态）。
  输入框走 `tmux send-keys` 打进活着的会话 —— **不重新实现 agent 协议**，
  所以 Claude Code 的配置、权限、MCP、skills 原样生效。
  解析分层抄 Lucarne 的 `agent-sessions`：原始层与语义层分开，`Unknown` 是兜底不是终点。
- ✅ **四个安全/正确性问题全部收口**（G1–G5 一程挖出来的）：
  - jsch 的 Session 写包路径**不是线程安全**的 → `Shell.write/resize` 上 `Mutex`（#16）
  - `HostKey.getKey()` 已经是 base64，重复编码让指纹校验失效（#21）
  - `StrictHostKeyChecking=ask` 在 **CHANGED 时也弹窗**，用户点一下就连上 → 短路直接拒（#22）
  - 共用连接件漏掉「装公钥」这个调用点 → 认证覆盖做成 `connect(auth)` 参数，
    **`ui/` 和 `term/` 下再没有裸 `SshSession(`**（#24 / #25）
  - `KnownHostsTest`（5 条仪器测试）钉住这几条分支，且**已用变异测试确认断言会红**（#26）
  - 错误文案统一走 `Connector.explain()`：`UnknownHostException` 会直接告诉用户
    「这栏要填 IP 或域名，SSH 别名在手机上不解析」（#27）
- ✅ **公钥界面**：点整块复制到剪贴板 + 「换一把」（带不可逆后果说明的确认框）。
- 📦 **APK 分发**：手机上「检查更新」走的是**公网 HTTP**，不是这台开发机。
  - **公网下载点在 hk13（服务集群机 `64.90.25.56`）**，nginx **:8899**，
    包在 `/var/www/yxi/<token>/Yxi.apk`。token 见 `/root/.yxi/dl-token`（600，**仓库外**）。
    `dl.keuury.com` 的 vhost 已经备好，只差 A 记录（CF token 有 IP 白名单，本机加不了）。
  - ⚠️ **hk13 上还跑着别人的东西**（human_register / api.omggrow.com / inbox.omggrow.com …），
    动它的 nginx 前先读 `sites-enabled`，**绝不能加 `default_server`**，见 TROUBLESHOOTING #90。
  - **`server/install.sh --publish` 会自动推过去并比对 sha256**，推不动会吼。
    别再手动 scp —— 手动那次就出过「本地新、公网旧」（#69）。
  - 另一条路是 **App 内自更新走 SFTP** 读开发机的 `~/.yxi/Yxi.apk` + `latest.json`，
    不过公网、不用 token，防火墙后面照样能用。两条路的包由 `--publish` 保证是同一个。
- ✅ **G6 完成**（实测通过）：**工具卡片按工具定制 + 点选项**。
  - 卡片：Bash（命令横滚不折行 / stdout·stderr 分开 / `Exit code N` 提取）、Edit（真 diff，
    走 `toolUseResult.structuredPatch`）、Write（新建 vs 覆盖）、Read（行数 / 图片尺寸）、
    Agent（同步 vs 后台）、AskUserQuestion（问题 + 你选了啥）、ExitPlanMode（计划 markdown）。
  - **点选项**：手机上点第 2 项 → 服务器转录里落 `"先做哪一块？"="三模式切换"`。**闭环实测通过。**
  - ⚠️ **关键发现**：待答的 `tool_use` **不落盘** —— Claude Code 要等工具跑完才写进 JSONL。
    所以「此刻在等你」只能抓屏幕（`tmux capture-pane`）。**转录是权威的历史，屏幕是唯一的「此刻」。**
  - 按键协议全部实测：单选送数字即确认；多选送数字是勾选、`Right`+`1` 才提交；ExitPlanMode 同一套。
- ✅ **G7 完成**（验收通过）：**文件模式**。走 SFTP，**服务器上不装任何东西**。
  - 验收标准原文是「在没装任何东西的 station 上打开一个带图的 README.md，图片能显示出来
    （相对路径解析对了）」—— **过了**：`docs/shot.png` 这个相对路径的图在手机上显示出来了。
    靠的是库自带的 `ImageTransformer` 钩子（`transform(link)` 拿到的就是 md 源码里那个原始字符串，
    库不做任何 URL 拼接），相对路径由 `Paths.resolve` 按 md 所在目录解析，有测试盯着。
  - 目录树（目录在前、符号链接解引用、大小）、面包屑跳转、**直接输入路径 + 最近 8 个目录**、
    md「阅读⇄源码」、图片、JSON 折叠树（默认只展开第一层）、极简语法高亮（注释/字符串/数字/关键字）。
  - **只读**，不做写删。**收藏没做**（要落盘，等有真需求）。
  - ⚠️ 遗留清理项：测试期间把**模拟器的**公钥装进了 station 的 `~/.ssh/authorized_keys`
    （`yxi@android` 那行）。G8 之后要删掉 —— 那不是用户手机的钥匙。
    测试样本留在 `station:/tmp/yxi-g7/`。
- ✅ **G8 完成**（验收通过）：**三模式切换 + D-Pad**。
  - 验收标准原文「用 D-Pad 在 Claude Code 的权限提示里上下选 + Enter 确认」—— **过了**：
    手机上按 ↓ 再按中央 ⏎，服务器转录里落 `"D-Pad 的两个角默认放哪组键？"="ctrl-c + tab"`。
  - **切换不断连**：连接、SFTP 通道、终端仿真器全部提到 `Workspace` 这一层，
    三个模式只是换画面。实测切到对话再切回来，`tmux attach=1` 全程没断。
  - D-Pad 是**一个手势不是五个按钮**：按下按方位判方向、压住连发、手指推向别处就跟着换。
    两个上角可配置（长按换）。
  - 「对话模式不可用」不是灰着不说话，**点了会告诉你为什么**。
  - ⚠️ 这一程挖出的最阴的一个是 #35：**节流没有尾随刷新**，
    表现是「忙的会话正常、闲的会话永远空白」。
- 🔄 **G9 大部分完成**（三条验收里两条过、一条只能真机验）：
  - ✅ **键盘工具条**：`Ctrl`(粘滞) esc tab ⇧tab ^C ^D ^Z ^L ^R **^B(tmux 前缀)** 方向键
    home/end/pgup/pgdn 和 `| / ~`（这几个符号在手机输入法里要翻两页）。
    **实测 ^C 中断了 `sleep 300`**。粘滞 Ctrl 不碰 termlib 内部 ——
    `onKeyboardInput` 回调本来就在我们手里，下一个字节 `and 0x1f` 即可，**任何输入法都适用**。
  - ✅ **中文显示**：宽字符对齐、日文韩文 emoji 都正常。
  - ✅ **断线重连**：心跳 2s×2 判死 + 看门狗 600ms 轮询 + 退避重连。
    **实测 2.9–3.4 秒恢复**（目标写的是 2 秒，没做到，分解见 TROUBLESHOOTING #41）。
    **「光标位置不丢」是满分** —— tmux 重新 attach 把整屏连回滚一起带回来。
  - ⬜ **中文输入（IME 通路）真机才验得了**：模拟器上 `adb shell input text` 打不了中文，
    而且实测它连 ASCII 都没能进远端 —— 这条只能你在荣耀 Magic7 上试。
  - ⬜ 横竖屏尺寸同步没单独验（模拟器锁竖屏）。
- ✅ **G10 完成**（实测通过，含公网远程机）：**手机主动响**。
  - **服务器侧只有一个文件**：`server/yxi-hook`（~90 行 python）+ `server/install.sh`
    （幂等 / 自动备份 / 写完校验 JSON，坏了自动回滚 / `--uninstall` 一键摘）。
    不占端口、不起守护进程、不用 systemd。
  - hook 只写**值得让手机响的**两种事件（`Stop` / `Notification`）——
    `PreToolUse` 每秒好几次，写进去等于把手机变成骚扰源。
  - App 侧前台服务常驻 SSH 通道 `tail -n 300 -f`。**不走 FCM**（D8）。
  - **补收漏掉的事件**：按「上次看到的时间戳」过滤，比记文件偏移稳
    （hook 超 5 MB 会砍前半段，偏移就废了）。实测：App 没开时写的事件，开了之后收到了。
  - 实测：**本机 + station（公网另一台）两条通道同时盯**；在 station 上写一行事件，
    手机弹出「atf翻译 需要你」；**点通知直达那个会话的对话界面**。
  - 系统低内存杀掉后 `START_STICKY` 15 秒自己回来（实测）。
  - ⚠️ **扛不住 force-stop** —— 而荣耀 MagicOS 的后台管控就是 force-stop，
    且**掐掉之后不会有任何提示**。所以第一次打开铃铛时会弹一个对话框让你去放行后台。
    这不是「优化建议」，是这个功能能不能用的前提。
  - ⬜ **锁屏通知**只能真机验（模拟器没配锁屏）。
- ✅ **G11 完成**（两条验收都过）：**在通知上批权限**。
  - **架构跟 PRD 不一样，是有意改的**：PRD 写的是 hook 阻塞 570 秒等回答、超时输出 `"ask"`。
    那条路上「自动放行」是**一个 if 写错就会发生**的事。
    改成 **hook 只发通知，权限决定完全不经过它** —— 手机上按的按钮是往 TUI 送一个按键，
    跟人在键盘上按是同一条路。于是「自动放行」**在结构上不存在**。
  - 通知按钮上的选项**从屏幕上读**，不预设：权限提示是
    `1. Yes` / `2. Yes, and always allow…` / `3. No` —— 把「拒绝」写死成 2
    等于**永久放行这一类操作**（#47）。读不出来就不给按钮，只能点开去看。
  - **送键前会重新抓一次屏确认**号码和文案没变 —— 从发通知到你按下可能过了几分钟，
    那个提示可能已经换成另一个了。
  - ✅ 验收①：手机通知上点「1. Yes」→ 服务器上 marker 文件出现、提示消失、Claude 继续。
  - ✅ 验收②：`server/test_yxi.py` 三条全过，含端到端的
    `test_approval_fail_closed`（`~/.yxi` 不可写 + 没人回答 → 命令没执行、提示还挂着）。
  - ⚠️ 顺带修了个会要命的：**权限提示的脚注没有 `to navigate`**，
    G6 的解析器原本完全认不出它（#46）—— 最该认出来的一种。
- ✅ **G12 完成**（三样，两样实测、一样真机才验得了）：
  - ✅ **附件**：上传到 `/root/src/tmp/<会话名>/`，编号「图片1/附件1」，
    发送时正文前面带 `[图片1] <绝对路径>` —— **不把内容塞进对话，Claude 自己去 Read**。
    实测：手机传一张截图 → 那边的 Claude 把截图内容描述出来了。
    3 天清理的 `find` 每个参数都是刻意的（路径写死 / `-xdev` / 不跟符号链接 / 删前记日志）。
  - ✅ **用量**：`ccusage blocks --active --json`（schema 抄自本机能跑的 `cc-quota`）。
    会话看板上是详情卡、主机列表上是细线（读缓存，**不为显示用量额外建连接**）。
    **探测不到就整块藏起来** —— 这台机器上没 npm 装不了 ccusage，所以真实状态就是不显示；
    用一个临时 shim 验过「有数据」那条路的渲染，**验完立刻删了**。
  - ⬜ **语音**：接了系统 `RecognizerIntent`。对话模式把识别结果**填进输入框**、
    终端模式**弹确认框**（识别错一个字在服务器上就是另一条命令）。
    模拟器没有语音引擎，**只验了点下去不崩** —— 真正的识别只能你在真机上试。
- ✅ **自更新**（后加的）：`./server/install.sh --publish <apk> <code> <name> [说明]`
  把包和清单摆进 `~/.yxi/`，手机连上就看到横幅、走 **SFTP** 下载。
  **不查 GitHub Release**：仓库私有、API 要 token，而把 token 塞进 APK 等于公开它；
  而且这个 App 除了那条 SSH 之外本来没有任何网络面。
  检测 / 下载（sha256 一致）/ 完整性 / 权限处理都实测过；
  **最后那一下装不上是模拟器图形安装器的问题**（同一个文件 `adb pm install -r` 装得上），
  真机没验过 —— 见 TROUBLESHOOTING #56。
  ⚠️ **以后每次发包 `versionCode` 必须 +1**，手机只比这个数。
- ✅ **G13 完成**（实测通过）：**悬浮排列会话切换**（D18/D17）。
  - 工作区顶部点会话名 `▾` 唤出卡片轮播；卡上是那个会话的**实时屏幕缩略**（`capture-pane`，
    只抓当前页和左右邻居，全抓的话 20 个会话每 5 秒就是 20 次往返）。
  - 视差：邻居缩到 0.86 + 压暗，内容比卡片慢一拍。**系统关了动画就一律不做位移和缩放** ——
    这不是体贴，是无障碍要求。
  - **上滑 = 归档（本地名单，服务器一根毛没动）；杀会话要长按 + 二次确认。**
  - 看板上加了「悬浮」入口，和列表并存。
  - ⭐ **连接改成按 host 记，不按 (host, session) 记**：换会话时
    **SSH 连接数实测全程不变**，终端靠 `tmux switch-client` 切过去（一次往返）。
    之前是整个 Workspace 重建 = 重新握手 + ed25519 认证 + 起登录 shell，模拟器上两三秒。
  - ⚠️ 挖出两个叠在一起、症状完全一样（「滑不动」）的 bug，见 TROUBLESHOOTING #60 #61。
- ✅ **G14 完成**（验收通过）：**底部导航「会话 · 主机 · 设置」+ 设置页**。
  - **只在外层**，进工作区整屏让位。实测：终端里弹软键盘，底部栏不出现、
    键盘工具条正好落在键盘上方（靠 `imePadding()`，见 #62）。
  - 会话页带主机下拉 `dev ▾` —— 换主机不用退出去；不再需要「主机列表→看板」的下钻。
  - 设置页：**版本号（0.3.0 / versionCode 4，从 BuildConfig 读）**、主动检查更新、
    公钥（查看/复制/换一把）、后台放行状态、关于。
  - ⚠️ 更新检查三种结果分清楚了，都实测过：
    有新版本 / **✓ 已是最新（服务器上就是 0.3.0）** / **✗ 没查到：…（读不到）**。
    「没查到」绝不显示成「已是最新」。
- ⬜ **剩下的**：
  - **真机四件事**（模拟器都验不了）：中文输入 · 锁屏通知 · 语音识别 · **自更新最后那一步安装**
  - 横竖屏尺寸同步没单独验（模拟器锁竖屏）
  - 重连 2.9–3.4 秒，目标写的 2 秒 —— 分解见 #41，两条能更快的路都有代价，没走
  - TROUBLESHOOTING #50（LazyColumn 里读 state 加的 item 不出现）**只记了现象和绕法，没查根因**
  - 清理：station 的 `~/.ssh/authorized_keys` 里那行**模拟器的** `yxi@android` 要删
  - 发布前：GitHub token 轮换、仓库拆公开/私有

- ✅ **修好「用户真机连不上」（2026-08-23）**：根因**不在 App**，在 `dev/seed.sh` ——
  它按注释 `yxi@android` 过滤 `authorized_keys`，而 **`KeyManager` 给每台安卓设备
  写的注释都是 `yxi@android`**，模拟器和用户真手机撞了。于是每跑一次开发脚本，
  就把用户手机的公钥从本机和 station 上删一次；症状是他那头「连不上 + 检查更新失败」，
  服务器这头**查什么都正常**（端口通、外部机器 SSH 得通、fail2ban 没封他），
  唯一线索是 sshd 日志里他那把钥匙的指纹**一次都没出现过**。
  修法：模拟器专用标签 `yxi@emulator`，`server/test_yxi.py::test_seed_never_evicts_a_real_phone` 守住。
  同时暴露出 **测试盲区**：seed 里预置的主机一直是 `10.0.2.2`（模拟器→宿主机回环），
  「App 走公网 IP 连这台服务器」这条路一次都没跑过 —— 已在 seed 里补一台真公网 IP 的主机，
  并实测通过（连接 + 检查更新）。见 TROUBLESHOOTING #64 / #65 / #66。

- ✅ **开发者模式（2026-08-23）**：设置页**连点三下版本号 + 口令**（口令只存哈希，
  见 `DevMode.HASH`；明文不进 git）→ 「跑一次诊断」：解析地址 → 连 TCP → SSH 招呼 →
  认证，逐步计时报错，再挨个探同一个 IP 上的 `本机端口/22/8443/8899/443/80`，
  最后给一句结论 + 一键复制。**做它的理由是排查「用户连不上」时服务器侧是瞎的** ——
  包没飞到就等于什么都没发生，而「超时 vs 拒绝」「哪个端口通」「WiFi vs 移动网络」
  这些决定性信息全在手机上。诊断文本不含密码和私钥。见 TROUBLESHOOTING #70。

- ✅ **对话模式补上「此刻」（2026-08-23）**：三件事。
  ① **排队中的输入**（用户在 Claude 忙时打的字）此前**一条都不显示，处理完也不显示** ——
  它们在转录里的类型是 `queue-operation` / `queued_command` 而不是 `user`，
  解析器静默丢了。现在排队时显示成虚线气泡，被处理时转成正常消息。
  ② **状态词**（`✽ Scampering… (4m 48s · ↓ 10.2k tokens)`）—— 这个转录里没有，
  只有屏幕有，用 `tmux capture-pane` 抓（`Live`）。长工具调用时没有它，界面看起来就是卡死。
  ③ **进对话不再一闪一闪跳** —— 历史灌完之前瞬移不做动画。
  见 TROUBLESHOOTING #72 / #73。
- ✅ **会话可置顶**（📌，按主机分开存在手机本地）：置顶的**从原组取出**单独放最上面。
  22 个会话时留在原组只加图标等于没置顶。

- ✅ **对话可读性 + 连接寿命（2026-08-23，0.5.1）**：
  ① **工具卡默认折叠成一行**（`Bash  cat > /tmp/… 完成`），点开才展开。
  一个回合十几条 Bash/Read 会把正文挤没 —— 出错的和「要你拿主意」的两类**不折叠**。
  ② **连接提到 tab 切换之上**（`rememberHostSession`）：切「设置↔会话」不再重连，
  实测来回 8 次新增认证 **0 次**。
  ③ 「后台不受限制」入口修好 —— 原来跳的是「已放行应用列表」，我们还没放行所以找不到自己。
  ④ 状态词压字重不压字号（量过是 12sp，比正文小；显得大是视觉重量）。
  见 TROUBLESHOOTING #74 / #75。

- ✅ **0.5.2（2026-08-23）四个修复**：
  ① **markdown 标题不再是 45sp** —— 库的 M3 默认把 `#`/`##` 映射到 displayLarge/Medium
  （57sp / 45sp，正文才 16sp）。那套字号是给落地页大标题用的，聊天气泡里一个 `##`
  就占半屏。见 `ui/MarkdownStyle.kt`。
  ② **「排队中」不再永久挂着** —— 出队判据从 `remove` 改成「这句话有没有真的作为
  用户消息出现过」。实测真实会话 35 enqueue / 29 remove，剩下 13 条早就处理完了。
  ③ **「终端起不来」的假错误** —— `runCatching` 把 `CancellationException` 也吞了，
  一次正常的取消被写成用户可见的错误且永久留在界面上。同一写法全仓有四处。
  ④ **终端按真实尺寸开** —— 控件量尺寸发生在 shell 建好之前，回调被丢且不再触发，
  tmux 永远停在 80x24 而控件只有 55 列，画面整个是花的。
  见 TROUBLESHOOTING #76 / #77 / #78。

- ✅ **0.5.3（2026-08-23）**：连接失败**自动重试**（指数退避 1s→15s，指纹变了才停）。
  此前失败一次就把「连不上」钉在界面上、再也不会自己清 —— 手机上网络时断时续，
  等于把一次抖动变成一次永久故障。同时修掉「吞掉 CancellationException」的**第五处**
  （前四处写的是 `status =`，这处写 `error =`，按写法 grep 漏了）。
  新增 `app.yxi.ssh.catching {}`：不吞取消的 `runCatching`，
  **凡是「失败要显示给用户」的地方一律用它**。
  诊断报告加一行「界面 已连上 / ✗ 此刻显示：…」—— 报告要报告**被抱怨的那个东西**的状态，
  不是它自己另测一遍的结果。见 TROUBLESHOOTING #79。

- ✅ **0.5.4**：对话加「↓ 一键到底部」（只在没在底部时出现）；进对话**精确停在最后一条**；
  会话页连不上时给一个手动「重连」按钮。滚到底这件事踩了四个坑，
  真正的元凶是「定位发生在历史还在灌、布局还在变的时候」——
  把 `settled` 也当成 key、灌完再定位一次才收敛。见 TROUBLESHOOTING #80。

- ✅ **0.5.5 —— 连接自愈**：用户报「连上了，返回来又连不上，只能重启 App」。三个洞叠在一起：
  心跳 `2s×2=4s` 判死对手机太狠（终端要这么灵敏，常驻那条不该）；
  `rememberHostSession` 连上就 `return`、之后死了没人管；
  看板刷新失败只写「刷新失败」而 `ssh` 仍非 null，连重连入口都不出现。
  现在：心跳做成构造参数（终端 2s / 常驻 15s×2），连上之后 `while (isAlive) delay(3s)` 守着，
  掉了自动重连。**实测从服务器 `kill -9` 掉那条 sshd，37 秒后自己回来，界面全程没报错。**
  下拉刷新兼作手动重连（没连上=重连，连上了=立刻刷）。见 TROUBLESHOOTING #81。

- ✅ **0.5.6 —— 修「计划批准框认不出来」**：Claude Code 2.1.241 的计划批准框脚注是
  `ctrl+g to edit in VS Code · ~/.claude/plans/xxx.md`，`to cancel` / `to navigate`
  **两个已知锚全部落空** → `Prompt.parse` 返回 null → **整个框在手机上不存在，用户批不了计划**。
  改用**光标行 `❯ N.`** 做退路锚（那个形态在全部五份真实抓屏里都在）。
  是 iOS 那边的 ios-parsers 实测发现的，我在真机上复核并修的安卓。见 TROUBLESHOOTING #82。

- 🚧 **iOS 版第一版（2026-08-23，`ios/`，跟 `android/` 完全分开）**：五个子代理并行做的。
  **能验证的部分验了**：`swift build --target YxiKit` → 430/430；`swift test` → **98 条全绿**
  （这台 Linux 上装了 Swift 6.0.3，`/opt/swift/usr/bin`）。测试样例全部从真机抠。
  **编不了的部分**（SwiftUI / 真机 SSH / 分发）共 **20 处标了「未验证」**。
  ⚠️ **卡在没有 Mac** —— 完整 App 一行都没编过。见下面「## iOS 版」。

- ✅ **0.5.7 —— 终端「历史」模式**：点键盘条上的「历史」，之后在终端上**上下滑动翻页**。
  同一个需求先后走错两条路：让控件自己滚（`ScrollController` 是 Kotlin `internal`，
  **字节码里却是 public，看字节码会得出相反结论**）、驱动 tmux copy-mode
  （进得去但 `[0/0]` —— Claude Code 占**备用屏**，输出根本不进 tmux 历史）。
  正解是送 PageUp/PageDown 给那个全屏程序自己。见 TROUBLESHOOTING #83。

- ✅ **0.6.0 —— 对话三件（2026-08-23）**：用户一次点了三件，都做完了。
  ① **注入内容单独渲染**：队友消息 / 任务通知 / 系统提醒 / 命令输出在转录里**也是 `user` 类型**，
  此前一视同仁做成用户气泡 —— 屏幕上一坨 `<agent-message from="…">` 顶着「你说的话」的样子
  （真实会话里 33 处）。现在是折叠卡片。**注意队友消息走的是 `queue-operation` 不是 `parseUser`**。
  ② **增量解析**：老做法每 300ms 把整个缓冲重解，实测 `tail -n 800` = 4.17 MB，
  等于每秒重嚼三次 4 MB。改成 `Transcript.Incremental` 只喂新行；
  ⚠️ 回填工具结果**必须换新实例**（`var` 就地改 Compose 看不见，卡片永远停在「进行中」）。
  ③ **进会话不再新建连接**：此前打开一个会话要新建 **2 次** SSH 认证，实测降到 **0 次**。
  做法是 MainActivity **预热第二条**连接，而**不是共用看板那条** ——
  共用的话终端通道出事会把看板一起拖死（#16）。代价是每台主机多一条闲连接。
  见 TROUBLESHOOTING #86 / #87。
- ✅ **0.6.4 —— 输入这一块的三件（2026-08-24）**：
  ① **终端用回手机原生输入法**。根因不是「中文支持没做」，是 termlib 把 `inputType` 报成
  `VISIBLE_PASSWORD | NO_SUGGESTIONS`，输入法当密码框处理**直接不给候选词**。
  那个值写死在库里没有参数（`javap` 翻遍了），**唯一的拨杆是 compose mode，而它默认是关的**。
  现在拿到 `ComposeController` 就开。代价：回车整行提交（`Key.Enter → commit()`），
  vim/less/y-n 这种逐键交互要点掉工具条上的 `整行`。见 TROUBLESHOOTING #93。
  ② **对话里的斜杠命令提示**（`agent/Slash.kt`）。打 `/` 弹候选，点一下填进草稿。
  **不拦任何输入** —— 送出去的还是 `tmux send-keys`，自己写的斜杠命令照打照样能用。
  已实测 `send-keys -l '/context'` + Enter 能真的在 TUI 里跑起来。
  打全了就收起提示条（否则点完候选它还挂着挡输入框），这条**依赖「没有命令名是另一个的前缀」**，
  有测试盯着。
  ③ **html 文件能看渲染后的样子**，跟 md 共用同一个「阅读 / 源码」开关。
  用系统 WebView，**JS 关死、baseUrl 传 null**（远端任意文件，不能让它的脚本在 app 里跑）。
  代价是外链 CSS/图片不加载 —— 这条路上只有一条 SSH 连接，没有网络。内联 `<style>` 正常。
- ✅ **0.6.5 —— 对话里的复制与撤回（2026-08-24）**：
  ① **长按自己的消息 → 复制整段**；**AI 的输出改成原生文本选择**（`SelectionContainer`）——
  想要的多半是里面一个 URL 或一段命令，整段复制反而要回头删。
  两者互斥：长按被文本选择消费掉了，所以 AI 那一支不能再挂 `combinedClickable`。
  ② **排队中的消息长按 → 收回改一改**。协议是实测的：`Up` 弹回输入框、`C-u` 清空
  （见 [SessionProbe.popQueue]）。⚠️ **`Up` 全有全无，收不了单独一条** ——
  文案因此写「收回改一改」，收回来的拼成多行进手机的输入框。
  顺带补上转录解析漏掉的 **`popAll`**（第四种 queue operation），
  漏了它撤回后的气泡永远不消失，见 TROUBLESHOOTING #95。
  ③ **附件图片点一下能放大看**。胶囊拆成两个热区：点名字预览、点 ✕ 删掉
  （原来整块都是删除，想确认传对没有，一点就没了）。
  预览读**手机本地**那份（`Staged.localUri`），不从服务器拉回来 —— 白跑一趟还慢。
- ✅ **0.6.6 —— 连接真的持久了（2026-08-24）**：切 tab、进出会话都不再重连。
  病根是 `SessionsScreen` 在 `onDispose` 里断了一条**参数传进来的**、
  由 `MainActivity` 持有的共用连接（而同一个参数的 KDoc 正写着「连接不能跟着断」）。
  判据是**谁建的谁收** —— `disconnect()` 的六个调用点里只有这一处错。
  顺带把会话列表也挂到 `MainActivity`：活得比界面久的数据不能存在界面里，
  否则切回来是空列表、要等一次往返才有内容（就是之前说的「骨架屏闪光」）。
  验的方式是盯 TCP 源端口，不是看界面 —— 见 TROUBLESHOOTING #96。
- ✅ **0.6.7 —— 只通知置顶的会话（2026-08-24）**：设置页新增开关，**默认开**。
  ⚠️ **一条都没置顶时故意不生效**（照常全部通知）—— 否则新装的人什么都收不到
  而设置页绿勾还亮着，就是 #91 那个坑；设置页把这句明写出来。
  判断抽成纯函数 `Pinned.shouldNotify`，因为它错了的表现是「全静音且无报错」，
  差点栽在 `cc-` 前缀上（置顶存全名、`EventService` 里另有个短名变量），见 TROUBLESHOOTING #97。
- ✅ **0.6.8 —— AI 回复里的文件路径能点了（2026-08-24）**：点一下直接跳进文件模式，
  目录就进目录、文件就直接打开（`agent/Linkify.kt` + `FilesScreen(jumpTo=)`）。
  做法是**渲染前改写 markdown 源码**成链接、再用自己的 `LocalUriHandler` 接住点击 ——
  库的 `MarkdownAnnotator` 走不通（节点粒度太碎，单个节点看不出是路径）。
  认路径**故意保守**：至少两段、认汉字但不认中文标点、行内代码连反引号一起包。
  三个坑都是真句子逼出来的，见 TROUBLESHOOTING #98。
  顺带修掉 #99：工作区开着时点通知跳会话纹丝不动（`remember(host.id)` 少了会话这个 key）。
- ✅ **0.7.0 —— 简体中文 / English 切换（2026-08-24）**：设置页最下面切，**立刻生效不用重启**，选择落盘。
  界面、通知、SSH 报错、工具卡片全覆盖（**271 句**）。
  做法是 `t("中文原文")` + `ui/En.kt` 一张表，**中文原文就是 key**，查不到原样显示中文。
  为什么不用 `strings.xml`（插值 / 非 composable / 起 id 三笔账）写在 `ui/I18n.kt` 的类注释里。
  代价是没有编译期检查 → **`dev/i18n-check.sh`** 扫源码比对，加上 `I18nTest`
  盯占位符个数、空译文、译文里残留中文。
  ⚠️ 翻译不能放在 enum 常量参数 / object `val` 里（一次性求值，语言冻住），见 TROUBLESHOOTING #100。
  开发者模式那一页**故意不翻**（排查用，中文更准）。
- ✅ **0.7.1 —— 常驻通知变成「实时活动」，给灵动岛/灵动胶囊铺路（2026-08-24）**：
  常驻那条不再是死的「盯着 N 台机器」——有会话等你时变铜色、写出是哪几个，
  批完自动消掉。同时带上 `EXTRA_REQUEST_PROMOTED_ONGOING` 请系统提升。
  ⚠️ **提升是请求不是命令，系统不给是静默的**，所以开发者模式的诊断里加了「胶囊」一行，
  发完回头查 `FLAG_PROMOTED_ONGOING` 有没有被盖上 —— **让手机自己回答这个问题**。
  见 TROUBLESHOOTING #101。
- ✅ **0.7.2 —— 对话右上角显示上下文和今日花费（2026-08-24）**：
  `上下文 656K   今日 188.6M · $125.30`。
  ⚠️ **上下文不问服务器**：最后一条 assistant 消息的 `usage` 三项相加，
  顺着已有的转录流解出来，零额外往返（只看 `input_tokens` 会得到 1）。
  **不给百分比** —— 窗口大小转录里没有，实测同一模型名下窗口不同，算错比不算危险。
  今日花费走那台机器的 `ccusage daily`，日期在服务器上算（时区）。
  顺带修掉「ccusage 探测路径太窄，node 装在 /opt/node*/bin 时用量整块默默不显示」。
  另外 `agent/Quota.kt` 已经写好并有测试（`/usage` 面板的解析 + 跑一次的协议，
  实测那条命令不花钱、不写转录），**但还没接到界面上**。见 TROUBLESHOOTING #102 / #103。
- ✅ **0.7.3 —— 草稿不丢 · API 报错单独渲染（2026-08-24）**：
  ① 打了一半的字**落盘**（`ui/Drafts.kt`，按主机+会话分键），
  切模式 / 退出去 / App 被杀都还在 —— 实测 force-stop 重启后仍在。
  存三处：打字防抖、`onDispose`、发出去后立刻清。少一处都会在某种走法下丢字。
  ② `API Error: …` 不再当正文渲染，改成红框 + `!` + 等宽的故障卡片。
  判据用转录自己的 `isApiErrorMessage` 标志，**不匹配文本**
  （否则用户问「529 是什么意思」，回答会被渲染成故障）。
  见 TROUBLESHOOTING #104 / #105。
- ✅ **0.7.4 —— 真订阅额度（5 小时 + 本周）（2026-08-24）**：
  看板顶上的卡片点一下 → 借一个**闲着且输入框是空的**会话跑一次 `/usage` → 两行真额度。
  ⚠️ `/usage` 不花钱（$0.0000）、不写转录、Esc 就关，所以「点一下跑一次」是干净的。
  ⚠️ **安全闸实测拦住了**：点的时候有两个会话输入框里有用户没发完的字，都没被碰，
  所有转录里没有一条 `/usage` 消息。
  顺带把那条误导的标签改了：「5 小时窗口」画的其实是**时间**过了多少，不是额度 →「窗口已过」。
  见 TROUBLESHOOTING #106。
- ✅ **0.7.5 —— 手机上换模型（2026-08-24）**：对话右上角显示当前模型，点一下弹选单。
  ⚠️ **点一下 = 只换这个会话**（方向键挪过去再送 `s`）；
  「同时设为默认」要单独勾 —— 因为**直接送数字等于改账号默认**，
  连 `~/.claude/settings.json` 的 `model` 都会被改写。见 TROUBLESHOOTING #108。
  实测：选 Fable → 会话里显示 `for this session only`，账号默认仍是 `opus[1m]`。
  窄窗口下会折行 + 截断（`… +2 models`，行首是 `↓`），已按方向键滚动收集并滚回原位。
- ✅ **0.8.0 —— 界面风格可切换，先来一套 浅色（参考款）（2026-08-24）**：
  设置页「界面风格」里切，立刻生效、落盘。
  实现的关键不是配色本身，是**把 `Color.kt` 里的顶层常量改成读 `LocalPalette` 的取值器** ——
  全 app 250+ 处 `Copper` / `Dim` / `SurfaceContainerLow` **一个字都不用改**。
  编译器会替你找出 5 处非 composable 的用法（`Highlight.of` 等），标注一下就行。
  ⚠️ **终端永远深底**（ANSI 彩色在浅底上读不了），浅色下切终端会亮暗跳一下，设置页写明了。
  顺带发现并修掉：命令输出里的 ANSI 转义被纯文本卡片原样画出来。
  见 TROUBLESHOOTING #109 / #110。
- ✅ **0.8.1 —— 主机页两档额度 + 修掉排队气泡不消失（2026-08-24）**：
  主机行现在画 5 小时 / 本周两档，各带**已用 + 剩余** + 重置时间 + 「几分钟前查的」。
  查到的额度落盘（`QuotaCache`），否则主机页永远看不到会话页查的结果。
  ⚠️ 同时修掉一个老 bug：**`dequeue` 不带 content**（实测 1094 条一条都没有），
  而斜杠命令被本地消化、永远不会作为 user 消息出现 →「排队中」气泡永久挂着。
  改成真 FIFO（enqueue 进队尾 / dequeue 弹队头），容器从 Set 换 List
  （Set 会把同一句话排两次合成一条）。见 TROUBLESHOOTING #111 / #112。
- ✅ **0.8.2 —— 四件（2026-08-24）**：
  ① 对话状态条在**窄屏**上不再消失（判据从「脚注有 esc to interrupt」改成「状态行本身的形态」，
  窄屏脚注会被截断，见 #113）。
  ② 终端不再花屏：桌面同时 attach 把窗口撑宽 → `attach -d` 让手机独占 + 关掉那条 tmux 状态栏（#114）。
  ③ 附件上传不再「要好几次」：每次开新 SFTP 通道 + 试两次 + 失败报真原因（原来复用坏通道且静默失败，#115）。
  ④ 设置页照参考款 加了引导图标、粗标题、大留白（改 Card 一处，七节全变，#116）。
- ✅ **0.8.3 —— 设置收起成行 + 主机页长按看额度（2026-08-24）**：
  ① 设置默认一条条收起，点标题才展开（`Card` 加 `startExpanded`/`subtitle`，收起显示一句副标题）。
  ② 版本那节标题从「这个 App」改成「版本」。
  ③ **主机长按 → 展开 5h/7d 两档额度**（已用+剩余+重置时间）+「改主机」。
  ④ **额度查询重写成 `claude -p "/usage"`** —— 不借会话、不打扰任何东西、不花钱，
  彻底解决「会话全忙时借不到、查不了」。`borrowable` 留给了 `/model`。见 TROUBLESHOOTING #117/#118。
- ✅ **0.8.4 —— 历史滑动平滑 + 会话页撤额度（2026-08-24）**：
  ① 终端历史模式的「档位感」修了：从 PageUp（一下 8 行）改成**鼠标滚轮**（一下 1 行），
  映射到手指位移就平滑了。Claude Code 认滚轮（它为点选项开着鼠标追踪）。见 #119。
  ② **会话页顶上不再显示额度**（用户要求），只留主机页长按那台机器时查。
  删掉 UsageCard/UsageStrip/UsageCache + 会话页 Usage.probe 轮询，见 #120。
- ✅ **0.8.5 —— 会话卡：撤按钮，长按改拖排序（2026-08-24）**：
  删掉卡片上的 `开终端`/`回它一句` 按钮 + 长按回复。改成 **轻点=进对话（在里面回它），长按=拖动排序**。
  Pinned 从 Set 改成**有序 List**（次序即数据）。拖动只在置顶组内，`rememberUpdatedState` 修了
  「拖再远只动一格」（协程闭包捕获旧 tops）。实测单格/多格拖都对、重装后次序还在。见 #121。
- ✅ **0.8.6 —— 额度显示订阅档位（Max 20x/5x/Pro）（2026-08-24）**：
  额度卡顶上加「档位 · Claude Code」头（学 Moshi）。档位在 `~/.claude/.credentials.json` 的
  `rateLimitTier`，不在 /usage —— fetch 时**只 grep 那两个字段**（绝不 cat，里面有 token）跟 /usage 拼一起解析。
  ChatGPT/Codex：只能读本机装了的，本机没 codex，不做。见 TROUBLESHOOTING #122。
  ⚠️ **DNS 还是加不了**：用户给的 CF 令牌是 R2 的，能读 zone 但没 DNS:Edit（#123），
  `dl.keuury.com` 仍差一条 A 记录（要 Zone:DNS:Edit 令牌，或面板手加 → 64.90.25.56，DNS only）。
- ✅ **0.8.7 —— 修长按松手误开对话（2026-08-24）**：
  #121 上线后长按不拖就松手会误开对话（`clickable` 不认长按，抬手照样点一下）。
  修法：拖动一起来就 `openEnabled=false` 掐掉 `clickable`（`dragIndex>=0` → 整组轻点关掉）。
  实测三态齐全：轻点进对话、长按+拖排序、长按松手不开对话。见 TROUBLESHOOTING #124。
  **已发 code 41 到公网**（hk13:64.90.25.56，sha256 校验一致）。
- ✅ **0.8.8 —— 图钉下面加回「回它一句」快捷按钮（2026-08-24）**：
  0.8.5 把卡片上的回复按钮撤了（改成「点卡片进对话去回」）；用户要一个**不进对话、
  直接甩一句就走**的快捷入口。加回来，位置在**图钉正下方**一个气泡按钮（`Glyph.Chat`）。
  区分：**轻点卡片 = 进对话细聊；气泡 = 弹底部输入框送一句（`SessionProbe.send` → tmux send-keys）**。
  卡片布局从「Column + 顶栏 Row」重构成「Row(左内容 weight1 / 右竖排 图钉+气泡)」，文字不再和按钮抢位。
  ⚠️ 气泡/图钉各自 `clickable` 会**消费**点击，不冒泡到卡片的 onOpen —— 点它们不会顺带开对话。
  实测六态齐全（scratch `cat` 会话验的送达）：气泡弹框、送达 pane、框自关、点正文进对话、长按拖排序都对。
  **已发 code 42 到公网**（sha256 校验一致）。SendSheet 复活，见源码尾部。
- ✅ **0.8.9 —— 修「有时闪退」（2026-08-24）**：dropbox 里挖出崩溃都在 SSH 层 ——
  连接半路断了（锁屏/切网/服务器掐空闲），下一个 SSH 操作抛 `session is down`/`Broken pipe`，
  **从协程逸出没人接 = 闪退**。看门狗最长 30 秒才判死，这窗口里任何 exec 撞上就崩。
  修法（源头兜，别指望 30+ 调用点各自加 try）：`SshSession.exec` 整段 try —— 取消照抛、
  连接类失败吞掉返回空（跟 `Shell.write` 一个路子）；`ChatScreen` 那条裸 `stream().collect`
  用 `catching` 包住（看聊天时连接抖一下就崩的那条）。见 TROUBLESHOOTING #125。
  **实测**：杀连接/冻结连接/看聊天时杀连接三种走法都不崩，日志里能看到 `exec 挂了…session is down`
  被兜住、随后自动重连。**已发 code 43 到公网**（sha256 一致）。
- ✅ **0.9.0 —— 会话新增「实验室」页（2026-08-24）**：会话导航栏从 3 个变 4 个
  （终端/对话/文件/**实验室**）。实验室 = UI 实验的展示台，一张张 demo 卡，点开满屏跑：
  加载 UI 合集 / 弹簧动效 / 网页效果（WebView JS 开着）/ 新页面原型。**加新实验 = 往
  `LabScreen.experiments()` 加一个 `Experiment`**。纯本地不碰 SSH。`Mode` 枚举加 `Lab`、
  `when(mode)` 加一路分派、`ModeSwitcher` 自动多一个 tab。实测四个 demo 都跑通。
  ⚠️ WebView demo 别用 canvas / 动画背景层（不合成），只用纯色+transform，见 TROUBLESHOOTING #126。
  **已发 code 44 到公网**（sha256 一致）。
- ✅ **0.9.1 —— 实验室支持 GIF（2026-08-24）**：加了「动图 GIF」实验，`assets/lab_sample.gif`
  用 `ImageDecoder`→`AnimatedImageDrawable` **原生播放**（API 28+；26/27 退回 BitmapFactory 首帧），
  **没引任何图片库**。证明实验室能吃 GIF 这类现成媒体格式，不止手写 Compose。已发 code 45。
- ✅ **0.9.2 —— 实验室：Yxxxxxi 加载动画候选 + 审核勾选（2026-08-24）**：抄 Dribbble「字顶上滚东西」
  的 loading，改成「Yxxxxxi」——`YxiLoader.kt` 里 **5 版候选**（顶部传送带/扫光填充/小球跳过/
  字母波浪/底部点阵），`HorizontalPager` **左右滑着挑**（像刷小红书），标序号 + 圆点。每版底下
  **审核勾选**：勾了写进**连的那台服务器** `~/.yxi/lab-approvals.txt`（一行一个 id，见 `agent/LabApprovals.kt`）——
  **这样开发者 `cat` 一下就知道用户审核过哪几个**（勾选落服务器不落本地，就为了我读得到）。
  实测：5 版都跑通、滑动/序号/圆点对、勾选后文件里出现 `loader-1-topconveyor` 等。已发 code 46。
- ✅ **0.9.3 —— 实验室加图标候选 + 参考动效收藏（2026-08-25）**：
  ① **Yxi 图标 4 候选**（`LogoConcepts.kt`）：❯提示符/对话气泡/Y字标/遥控，**Compose Canvas 画的**
  （`LogoMark`，`PathParser` 解 SVG 路径 + 珊瑚渐变底），实测跟网页提案 `mark.html` 一样质感。
  也放进网页版给对比：hk13 上 `http://64.90.25.56:8899/mark.html`（临时预览，选定后可撤）。
  ② **参考动效收藏**：把用户发的两个 Dribbble GIF（`assets/ref_submit.gif` 提交→进度→成功/失败、
  `ref_blob.gif` 液态球→打勾）摆进实验室能播；勾选 = 「想复刻这个」。
  两个都走 `LabApprovals`（logo-N-* / ref-N-*），`cat ~/.yxi/lab-approvals.txt` 读用户勾了啥。已发 code 47。
- ✅ **0.9.4 —— 实验室「在线实验」：内容从服务器读，推新设计不用更新 App（2026-08-25）**：
  用户问「以后要更新 App 才能在实验室看到吗」→ 不用了。`agent/LabRemote.kt` 读连的那台机器
  `~/.yxi/lab/manifest.json` + 素材（html/gif/image/note），`ui/RemoteLab.kt` 渲染（html 走 WebView JS 开、
  gif/图 base64 经 exec 传回来）。**我往 `~/.yxi/lab/` 丢东西、用户点刷新就见，不发版不更新。**
  勾选审核照走 `LabApprovals`。已 seed 两个**参考动效的真复刻**（不是播 GIF）：
  `submit.html`（GIF A：按钮→进度→成功/失败，状态驱动）、`blob.html`（GIF B：液态球 loading，
  **打勾只在加载真完成时画一次**——用户指出直接用 GIF 会循环闪勾，这版把勾挂到完成事件）。
  实测：改服务器 html + 点刷新即更新（不重装）；blob 的勾像素级验过只在 done 帧出现。已发 code 48。
- ✅ **0.9.5 —— 表格横滑看全 + 变形提交按钮（2026-08-25）**：
  ① **聊天里 markdown 表格**原来每格单行截成省略号，读不到。换掉库默认表格（`markdownComponents(table=…)`）：
  自己画的 `MarkdownScrollTable`（`ui/MarkdownTable.kt`）—— 定宽列 + 单元格换行 + 整表横向滚动，
  从 AST 取原始表格文本 `getTextInNode` 自己按 `|` 切（`parseMdTable`）。实测窄表看全、宽表左右滑。
  ② **变形提交按钮** `ui/SubmitButton.kt`（复刻用户选中的 Dribbble GIF A）：点→morph 进度条→✓成功/✗失败重试，
  接**真状态**不是播动画（work 返回 Result，可喂真实 progress）。接进用户选定的三处：**回它一句的发送**
  （成功✓自动收起 sheet；exec 静默失败，靠 isConnected 判成败）、**下载更新**（真实百分比进度）、**装公钥**。
  实测回它一句：发送→✓已送达→自动关，消息真的到了。已发 code 49。
- ✅ **0.9.6 —— 对话加载体验修好（2026-08-25）**：用户报「加载慢 / 先加载旧对话 / 跳底部要点好几次」。
  ① **先加载旧的**根因：head（tail -60 最新）先显示没错，但随后 `tail -n N -f` 流从**最老**开始吐，
  每批 `items=inc.snapshot()` 把最新顶成「只含最老几行」——用户看着从旧滚到新。改成 inc 后台默默攒，
  等它**追上 head 最新那条**（uuid key 对上）才交出完整列表；在那之前界面稳停 head=最新。
  ② **跳底部要点好几次**：加了 `stick`（粘底）状态 —— `snapshotFlow{isScrollInProgress}` 每次滚停按落点
  更新 stick=atBottom；点 ↓ 直接 stick=true。粘着时每条新内容自动跟到底，一次点到位不用再点。
  ③ backlog 800→400，传输/解析减半。实测：一进来就在最新（无 ↓ 按钮）、点一次 ↓ 到底、表格也正常。已发 code 50。
- ✅ **0.9.7 —— 对话跳底部真·一次到位（2026-08-25）**：0.9.6 修了大半但用户报「活跃会话还是点好几次」。两处根因：
  ① **scrollToEnd 提前退出**：懒加载下面几项没组合时 `canScrollForward` 会提前报 false，传进来的下标又过时，
  于是只滚一点就 return。改成：**自己读 `layoutInfo.totalItemsCount` 不信外面的下标**，边滚边发现新项就重跳，
  要求**连续两帧**都到底才算真到底，最多 60 帧。（改成无参 `scrollToEnd()`）
  ② **stick 被程序滚动误关**：`snapshotFlow{isScrollInProgress}` 每次滚停都 `stick=atBottom`，活跃会话里程序滚
  常在「刚到底又被新内容顶起」间落定→被判不在底→stick 关→跟随停。改成**只有用户拖动（DragInteraction）才改 stick**。
  ③ 一批多条一次涌入时，最后一条高度在首次滚动后才定，补一个 `delay(120)` 再滚一次贴死底。
  实测（scrolltest 30→40 条 + 突发追加）：一进来在最新、一次点到底、流入自动贴底最后一条完整。已发 code 51。
- ✅ **0.9.8 —— 实验室大改：从「写死的 demo 画廊」变「按类型分栏的产物库」（2026-08-25）**：
  **撤掉所有内嵌 demo**（删了 YxiLoader/LogoConcepts/RemoteLab.kt + 三个 bundled gif）。实验室现在全从服务器读。
  顶层 = **栏目**（按 type 分：图像/矢量/动图/视频/网页/文字，只有有内容的类才出现），
  **左滑露出置顶/删除**（`SwipeActions` 自绘，Animatable+detectHorizontalDrag；删调服务器 `yxi-lab rm`，置顶存本地 `LabPins`）；
  点栏目 → 详情列表，每条：预览（图/网页/动图/文字）+ **由谁生成**（manifest 的 `by`）+ **北京时间**（`at` unix→Asia/Shanghai）+ 勾选审核。
  `yxi-lab add` 第 5 参数 = 由谁生成；已更新 nanobanana 的 CLAUDE.md 让它出图带 "参考款 · nanobanana"。
  实测：4 类分栏、左滑置顶(📌浮顶)/删除(服务器同步没了)、点开图像栏看到真图+由谁+北京时间。已发 code 52。
- ✅ **0.9.9 —— 更新下载改到后台，切页面不断（2026-08-25）**：用户报「点更新后切进会话再退出，下载就停了」。
  根因：下载挂在更新横幅的 `rememberCoroutineScope` + 界面持有的 SFTP 通道上，一进会话看板销毁 → 协程取消、通道关闭 → 静悄悄断。
  修：下载搬进单例 `object UpdateDownloader`（app 级 `SupervisorJob` scope，永不取消），状态 `mutableStateOf` 放单例、横幅只读它画进度；
  通道自己从常驻 `shared.session`（MainActivity 导航之上持有）现开一条 SFTP。回看板时 `LaunchedEffect(ssh)` 重查、横幅摆回、`mine` 命中续显实时进度。
  顺手把 `SubmitButton` 拆出纯视觉 `MorphButton`（状态外传）给横幅复用。已发 **code 53 / 0.9.9**（本地 `~/.yxi/` + hk13，sha256 一致 9cfded23778c）。见 TROUBLESHOOTING #127。
- ✅ **0.9.10 —— 实验室素材「保存原画到本地」（2026-08-25）**：用户要图/GIF/视频等能把原画直接下到手机。
  每个素材卡加一个「保存原图/保存 GIF/保存视频/下载到本地」按钮（复用 `SubmitButton`：点→进度条→✓已存到相册）。
  **存的是原文件字节**（不是预览缩图）：`LabRemote.download` 走 **SFTP 流式**下（`.yxi/lab/<file>` 相对路径 jsch 自解析到 home）——
  **不用 base64 经 exec**，那个会把大文件（视频）截断。`MediaSaver`（新）用 MediaStore 分区存：图→相册 Pictures/Yxi、
  视频→相册 Movies/Yxi、其它→下载 Download/Yxi，**Android 10+ 不要任何权限**。
  实测（模拟器连 10.0.2.2=本机）：点保存原图 → `/sdcard/Pictures/Yxi/…jpg` **943575 字节，跟服务器原文件一模一样**（原画），
  MediaStore 登记 mime=image/jpeg、按钮转「✓已存到相册」。已发 **code 54 / 0.9.10**（本地 + hk13，sha256 一致 660fb4f57b3f）。
  ⚠️ ponytail 天花板：只做了 API 29+（用户机是 15）；26–28 会明确报「需要 Android 10+」，要支持再加动态存储权限。
- ✅ **0.9.11 —— 一批小巧思（19 项，调研后用户拍板全做，2026-08-25）**：四路 subagent 调研（代码盘点/同类App/CC能力面/手机端）汇总后落地。清单见 `POLISH-CHECKLIST.md`。
  **通知**（`watch/EventService.kt`+`AnswerReceiver`）：拆 CH_NEEDS(急促两下)/CH_DONE(轻一下)两频道给不同触感；加 RemoteInput「回一句」到 needs+done（顺带白送语音）；「静音」动作+`ui/Mute.kt`；等待计时+跨 2/5/10/20/40 分升级重提醒(escalate)；30s statusLoop 数「在跑」喂常驻通知。
  **聊天**（`ChatScreen`/`ToolCards`）：TodoWrite 渲染成 ☐▶☑ 清单+顶栏「正在做…」；忙时 LiveStatus 加「■停」(发 Escape)；上下文数 ≥15万染琥珀、点发 /compact；`Snippets.kt` 常用语 chip（草稿框+回复 sheet，可编辑）；危险审批(`Risky`正则)先验指纹(`Biometric` 框架 API28+)；PendingCard「看改动」→`GitDiff` DiffSheet(+绿-红@@青)。
  **看板**（`SessionsScreen`）：相对活跃时间`ago()`；悬浮卡实时状态词/耗时(`Live.doneFor`)；每会话静音(卡片🔕+回复sheet开关)；每主机稳定配色`hostColor()`；「＋」从手机拉起会话(最近目录→tmux new+claude)。
  **新组件**：`ShareActivity`(分享文字/图片/文件到某会话)、`widget/WaitingTile`(QS磁贴)、`widget/WaitingWidget`(桌面小组件，RemoteViews无新依赖)——后两个读 EventService 写进 prefs 的已知态。
  **没做**（用户说大赌注先不做）：PreToolUse 阻塞式远程审批、连接健康点+自动重连；Wear OS（荣耀表非 Wear OS）。
  实测（模拟器）：ShareActivity 全流程（选会话+预览+相对时间）、看板配色点/＋、聊天常用语 chip 均正常，三个新组件已注册、无崩溃。已发 **code 55 / 0.9.11**（sha256 一致 a9133f7788d1）。
  ⚠️ 现有已很完整：终端快捷键条(`KeyBar.kt` esc/tab/^B/方向/^C…)、语音(RecognizerIntent)、灵动胶囊(promote)本就有，本次没重做。
- ✅ **0.9.12 —— 对话里下载文件 + 「配置」tab（2026-08-25/26）**：
  **① 对话里下载文件**：文件查看器（`ui/FileViewer.kt`）顶栏加「下载」按钮 —— 从对话点文件路径就能到这，把**整个原文件**下到手机：图/视频进相册、csv/xlsx/pdf/zip 等进「下载」目录(Download/Yxi)。复用现成 `Sftp.download`(流式) + `MediaSaver`；`MediaSaver.mimeOf` 加了 xlsx/csv/pdf 等办公/数据格式表(各机型 MimeTypeMap 不一致)。实测：csv 下到 `/sdcard/Download/Yxi/`，字节一字不差。**全程走 SSH/SFTP,不碰公网 HTTP**(那只给网页装包)。
  **② 「配置」tab**（底部导航 会话/主机/**配置**/设置）：分服务器、分工具(Claude Code `~/.claude` / Codex `~/.codex`)浏览 + 编辑 agent 配置：技能/MCP/子 agent/命令/权限/钩子/记忆/插件。工具无关——哪台装了哪个才显示(现在三台都只有 Claude Code,Codex 没装)。**插件自带的技能/命令也枚举**(installPath 下 glob)。
  - `agent/ConfigRemote.kt`：一次 SSH 抓取(python3 heredoc)→ 结构化 JSON。**密钥服务器侧就打码**(env 值 + 键名含 key/token/secret/password/auth → ••••)，`.credentials.json`/`auth.json` **根本不读**。`save()` = json 先 `JSONObject` 校验 → `cp` 备份成 `<file>.yxi-bak-<ts>` → SFTP 写回。
  - `ui/ConfigScreen.kt`：主机头+下拉 → 工具段 → 可展开类目 → 项 → 详情(md 渲染/mono 文本；编辑取原文明文，结构化文件横幅提醒)。
  实测(模拟器连本机)：配置 tab 显示 Claude Code 的 记忆/设置/技能6(全是 ponytail 插件的)/插件1；开 skill 看 SKILL.md(md 渲染)；settings.json **env 值已打码 ••••**；造个测试 skill 改一行保存 → 磁盘内容变了 + 生成 `.yxi-bak-` 备份(内容是原文)。已发 **code 56 / 0.9.12**(sha256 一致 d42590007f9b)。
  ⚠️ 用户拍板范围=**查看+编辑**、工具通用框架；大赌注(PreToolUse 阻塞审批/连接健康)仍不做。编辑 settings.json/config.toml 这类结构化文件风险高——已上 json 校验+备份，但 toml 只备份没校验(Codex 没装,没实测)。
- ✅ **0.9.13 —— 通知显示「要你决定什么」（2026-08-26）**：用户报「通知说需要决策，但没说决策什么，回复不了」。**根因是 0.9.11 我的 Phase-4 回归**：重构成 `postNeeds`/`postDone` 时把事件的 `detail`（Notification message）漏掉了，只剩「等你决定」。
  修：① `EventService` 把 `detail`（为什么找你）+ 新的 `preview`（Claude 最后说的一句）穿进 `postNeeds`/`postDone`，折叠行就显示「要你决定什么」，展开显示 preview+屏幕提示+位置+等待时长；`WaitCtx` 存 detail/preview 给升级重提醒。② **服务器 `yxi-hook` 加 `preview` 字段** —— 从 `transcript_path` **tail 末尾 64KB**取最后一条 assistant 文本，**纯读文件、零 token/零 API**。
  实测（模拟器）：造 needs 事件带 preview → 通知 `android.text` 直接是那句「…rm 掉可以吗？」，不再是「等你决定」。已发 **code 57 / 0.9.13**（sha a1515c141bd9）。
  ⚠️ **配套**：`yxi-hook` 已更到**本机**（手机盯的就是这台；station/inst2 没装 hook）。手机连别的装了 hook 的机器要一起更 `~/.local/bin/yxi-hook`。旧 hook 也不会崩，只是没 preview。
- ✅ **0.9.14 —— 聊天顶栏「⚡模式」快切（2026-08-26）**：用户要便捷切模型/模式(1M、最大思考、ultracode)且能叠加。
  `ui/Modes.kt`：底部弹出 `ModeSheet`，大 chip 一点就把对应**斜杠命令**发进会话(`SessionProbe.send`)，**点了不关面板**——好连点**叠加**(各模式是独立斜杠命令,`/model`+`/effort`+`/ponytail` 各走各的)。命令**可编辑**(「名字|命令」一行一个,存 prefs)。默认:`1M 上下文|/model opus[1m]`、`最大思考|/effort max`、`高强度|/effort high`、`ultracode|/ponytail ultra`、`普通|/ponytail`。入口=聊天顶栏「⚡模式」(在模型名旁)。
  ⚠️ **默认命令是最可能的猜测**——不同 Claude Code 版本/习惯,`/effort`、`/model <arg>`、ultracode 具体命令可能不一样,所以做成**可编辑**,用户进去改成真能用的那句。实测(模拟器):⚡模式 chip 在、sheet 五个 chip 都对(标签+命令)、编辑弹窗能开;**没在真会话上点发**(会真切模型/模式),send 本身是proven。已发 **code 58 / 0.9.14**(sha 0586a8d034de)。
- ✅ **0.9.15 —— 切完模型立刻显示 + ⚡模式默认命令修正（2026-08-26）**：用户报「切到 Opus 5 了，模式旁边还显示 opus-4-8」。
  **先查清:那次不是 bug** —— 模型是**按会话**的：他在 Yxi 会话切的，而 App 当时显示的是 **claude_desktop**（另一个已在跑的会话，实测其转录最后仍是 `claude-opus-4-8`）；`/model` 回执写的也是「saved as your default for **new sessions**」，不动已跑的会话。
  **但顺带暴露两个真问题，都修了**：
  ① **顶栏模型名会滞后**：它取自「最后一条 assistant 消息」的 model，切换不改写旧消息 → 切完没回话前还显示旧名，用户会以为没切成。修：`Transcript` 认 `/model` 回执（命令输出里的 `Set model to …`）并覆盖 `Ctx.model`（`parseInto` 里加 `lastCtx`）。
    ⚠️ 两个坑（都写进测试了）：**不能先整体 clean ANSI** —— 别名 `claude-opus-5[1m]` 里的 `[1m` 跟加粗序列一样，会被吃成 `claude-opus-5]`；改成**在原文匹配、只摘首尾加粗标记**。正则还要**以 `<` 收尾**，否则把 `</local-command-stdout>` 吃进模型名（**测试抓出来的**）。`Kept model as …`＝没切，不能误判。
  ② **⚡模式默认命令是错的**：`/model opus[1m]` 实测回「Kept model as Opus 4.8」=没切；转录里核到能用的是全名形式 **`/model claude-opus-5[1m]`**。`/effort max|high|mid` 转录里确认真在用（13/5/6 次），另加了「中等」。
  测试：`TranscriptTest.切完模型还没回话也显示新模型`（4 个断言）；**全套 111 个测试通过**。已发 **code 59 / 0.9.15**（sha 5c71833edd80）。
- ✅ **0.9.16 —— 顶栏显示本会话的模型 + 模式（2026-08-26）**：用户要「模型显示要显示本对话的模型和模式」。
  数据**全在转录里，零额外开销**：`effort`（`max`/`high`/`mid`）在**转录行顶层**（跟 `type`/`uuid` 平级，**不在 message 里**——找错地方永远是空）；模式来自单独的 `{"type":"mode","mode":"plan|normal"}` 行（**没有 message 字段**，得在「非消息行静默跳过」之前接住）。
  `Transcript.Ctx` 加 `effort`/`mode` 两个字段；`parseInto` 加 `lastMode`，模式行来得比回话晚也立刻反映。顶栏在模型名后显示「最大思考/高强度/中等」+「计划模式」（Copper 色），normal 不显示（默认态不占位）。
  ⚠️ 那一行现在有**五格**（⚡模式/模型/强度·模式/上下文/今日），窄屏会挤没左边的 → 整行改成**可横滑**（`horizontalScroll`）。
  测试：`TranscriptTest.顶栏带思考强度和模式`（模式行在回话前/后两种顺序都验）；**全套 112 个测试通过**。实机(模拟器)确认顶栏渲染成 `⚡模式 opus-4-8 最大思考 上下文 693K 今日 …`。已发 **code 60 / 0.9.16**（sha f1627f946683）。
- ✅ **0.9.17 —— 顶栏再加 ponytail 强度（2026-08-26）**：接 0.9.16 那条待办。
  **比原计划更省**：本来打算 SSH 读 `~/.claude/.ponytail-active`（每次进会话多一个请求，而且那文件是**全局的**、未必等于本会话）；查下来它每次注入的 `PONYTAIL MODE ACTIVE — level: x` **就落在转录里**，于是**零额外请求、而且是本会话的**。
  `Ctx` 加 `ponytail` 字段；`parseInto` 用正则**直接扫原始行**（`PONYTAIL MODE [A-Z]+[^:]*level:\s*([A-Za-z]+)`）——不钻 hook_success 的 JSON 结构（那是插件实现细节，会变）。
  ⚠️ **不一定读得到**：它只在会话开始/换模式/提交提示时注入，实测同一会话相邻两次可隔 ~3000 行，超出 App 的 tail 窗口就读不到 → **读不到就空着不显示**（沿用「宁可不显示也不显示假的」）。空等级的注入（实测真有 29 条）不会冲掉已知值。
  验证：`TranscriptTest.认得出ponytail强度`（4 组断言）+ **正则跑真实转录：122 条真注入全中、全部解出 `full`，17 条未匹配都是我自己的 grep 命令文本被记进转录，正确忽略**；**全套 113 个测试通过**。已发 **code 61 / 0.9.17**（sha b092dba11a94）。
  ⚠️ 顶栏那行没再视觉复核（模拟器 App 数据又被重装清空）——渲染走的是跟「最大思考」同一条 buildList 分支，那条 0.9.16 已实机确认。
- ✅ **0.9.18 —— 修「按下时高亮是个方块」（2026-08-26）**：用户报长按各种可点的东西，变色的是个长方形块而不是按钮本身。
  根因：`Surface(shape = X, modifier = Modifier.clickable{})` —— Surface 只裁**内容**，`clickable` 在它**外面**，波纹画在矩形边界里。全 App **48 处**都这么写。修法：`clickable` **紧前面**加 `.clip(X)`。
  ⚠️ 第一版脚本插到链首，遇到链里有 `.padding()` 的等于没修（波纹变成 padding 后的小矩形）——必须紧挨 `clickable`，改了 24 处位置。详见 TROUBLESHOOTING #129（含验证手法）。
  验证：脚本复查「有 shape 且 clickable 却没 clip 的 Surface」= **0 处**；非 Surface（带 shape 背景的 Box/Row）也扫了 = 0 处；**113 个测试通过**；实机 `input motionevent DOWN` 按住截图 + 像素 diff：变化区域**四角未被涂到**、且与胶囊边界吻合，放大目视确认是**胶囊形高亮**。已发 **code 62 / 0.9.18**（sha d6edd7e28d19）。
- ✅ **0.9.19 —— 修「会话卡时间不对」（2026-08-26）**：用户报卡片写着 14 小时前/1 天前，但那些会话刚聊过。
  根因：`lastActivity` 只取 tmux 的 `#{session_activity}`，而它会陈旧到离谱 —— 实测 `claude_desktop` tmux 说 2 天前、cc-state 的 ts 说 **7 天前**，而转录**1 分钟前**还在写。**转录 mtime 才是权威**（Claude Code 每说一句都写它）。
  修：抓取脚本加一段列「项目目录 → 最新 .jsonl mtime」，`SessionProbe.lastActivityOf()` 取 `max(tmux, 转录)`，读不到退回 tmux。⚠️ 用 `find -printf | awk` 一次扫完（**8ms**）而不是每目录 ls+stat（**230ms**，看板每 5 秒一次受不了）；非 GNU find 就输出空 → 优雅降级。
  验证：新 `ActivityTest`（4 例，含真实的 claude_desktop 数据）；**全套 117 个测试通过**；实机确认卡片从「14 小时前/1 天前」变成 **5/7/24 分钟前**。已发 **code 63 / 0.9.19**（sha 73f6173d354a）。见 TROUBLESHOOTING #130。
  ⚠️ 遗留：`state`（等你/干活中）也来自 cc-state，同样可能陈旧，本次没动 —— 哪天「分组不对」先怀疑它。
- ✅ **0.9.20 —— 修「点发送再切走，消息丢了」（2026-08-26）**：用户原话「要在对话里面等几秒再返回才算发给 agent 了」。
  两层病根：① 发送跑在对话界面的 `rememberCoroutineScope`，切走即取消，而草稿在点击那刻已清空并落盘 → 话**既没发出去也没了**；② `SessionProbe.send()` 是「打字 + 回车」两步，中途取消 = 字进去了回车没送，卡在对方输入框里。
  修：`send()` 整段 `withContext(NonCancellable)`；新增 `ui/Sender.kt`（app 级 scope，仿 `UpdateDownloader`），**发失败把话还回草稿**并提示。
  实测：临时 tmux 靶子会话 → 输入 `YXISENDPROOF42` → 点发送后**立刻返回（零等待）** → 会话里收到**且被执行**（command not found）✓。**120 个测试通过**。已发 **code 64 / 0.9.20**。见 TROUBLESHOOTING #131。
  ⚠️ 判据推广：**任何「点一下就走」的动作**都不能挂界面 scope。已排查：装公钥/附件上传在 sheet 里（点完不会立刻销毁）、下载更新已是 app scope。
- ✅ **0.9.21 —— 设置里加「工单中心」（2026-08-26）**：用户要一个地方收集 App 的不足，方便查阅更新。
  `agent/Tickets.kt` + 设置页 `TicketsCard`：写一条 → 追加进**连着那台服务器**的 `~/.yxi/tickets.jsonl`（JSONL，只追加），
  **自动带上版本号 + 机型**（不带的话回头对不上是哪版的毛病）；卡片里同时列出已提的（北京时间）。
  **为什么存服务器而不是手机本地**：存本地只有本人看得见 = 等于没提。**为什么不开公网接口**：Yxi 无云后端，公网 POST 要防刷+隐私，与「不依赖第三方」冲突；走已有 SSH 通道零新基建。
  ⚠️ **局限**：APK 分享给别人后，他们的工单落在**他们自己的服务器**上，我们看不到。要收外部反馈得另在下载机(hk13)开收集端点 —— 那是另一件事，没做。
  查阅方式：`cat ~/.yxi/tickets.jsonl`。实测：App 里提交 → 服务器文件里出现带 version/device 的 JSON 行 → 按钮转「记下了」、列表显示「已提 1 条」✓。新增 `TicketsTest`（shell 单引号转义 + 坏行跳过），**全套 120 个测试通过**。已发 **code 65 / 0.9.21**。
- ✅ **0.9.22→0.9.25 —— 更新改走公网下载页 + 上 HTTPS 域名（2026-08-28）**：用户问「换个客户不就搞不了了」，确实——旧做法查的是**所连服务器**的 `~/.yxi/latest.json`，别的客户机器上没包。
  **现在**：`Update.checkPublic/publicVerbose` 查 **https://dl.keuury.com/latest.json**（根目录由 nginx 别名指向当前发布目录，**不用带 token**），下载走 HTTP 流式；公网不通才回落到服务器 SFTP（防火墙后仍可用）。
  **域名/证书**：CF 加 A 记录 `dl` → 64.90.25.56（灰云）→ 源站 `certbot --nginx` 拿 Let's Encrypt 证书（到期 2026-11-26，自动续期）→ http 301 跳 https。nginx 里 `server_name dl.keuury.com` 的块早就写好了，只差 DNS。
  ⚠️ 中途为明文 HTTP 加过 `network_security_config.xml`（只豁免单域名），**上了 HTTPS 后已整个删除**。
  ⚠️ 关键 bug：第一版把公网检查写在 `LaunchedEffect(ssh)` 里，**没连主机就查不到更新**——已拆成独立 effect；设置页「检查更新」也不再要求已连接。
  验证：**藏掉服务器 latest.json + 不配任何主机**，App 仍查到 0.9.24 并下完 33846290 字节（与公网一字节不差）、拉起安装器 ✓。**120 个测试通过**。已发 **code 69 / 0.9.25**。见 TROUBLESHOOTING #132。
  ⚠️ 老链接 `http://64.90.25.56:8899/<token>/` **继续保留**（已装旧版的人靠它更新）。
- ✅ **GitHub 恢复同步（2026-08-28）**：远端曾停在 0.8.6，落后十几个版本。已提交并推送 0.8.7→0.9.21（推前扫过密钥/密码/token，干净）。
  ⚠️ 顺手把一个**有效的 GitHub token** 从 `mail` 仓 remote URL 的明文里摘掉，改存 `~/.git-credentials`（600）+ `credential.helper store`，以后各仓都能直接推。
  ⚠️ **该 token 早前在聊天里贴过、且仍然有效，建议轮换**。
  **纪律：以后每次发版顺手 commit + push，别再攒。**
- ✅ **0.9.26 —— 选择器大修（2026-08-28）**：用户报「只有选择没有问题」「点一个选项要等十秒」「回不到上一题」「支持多选吗」。
  **拿真机样本修的**：起了个临时会话让真 Claude Code 出一个「两问题 + 第二问多选」的 AskUserQuestion，**并把窗口缩到 46 列复现窄屏**，抓下 4 份原始屏幕固化成 `PromptRealTest`。
  ① 问题正文丢失：窄屏脚注折成两行，前半行被当成最后一项的说明（截图里就是它）→ 新增 `isFooterish` 过滤；标题改成**按段收集再拼**（长问题会折行）。
  ② 延迟：老写法送键后 `delay(500)` 只抓一次，抓到旧屏就得等被 `busy` 停住的轮询恢复（闲时 2.5s）→ 新增 `awaitChange()`（130ms 连抓、指纹一变就返回），待答挂着时轮询 2.5s→0.7s。**实测 ~10 秒降到 ~3 秒**（余下是 SSH 往返，本地抓屏 0ms）。
  ③ 回上一题：TUI 本就支持（`←  ☐ 名字  ☒ 配色  ✔ Submit  →`），已解析成 `Pending.tabs` 并给出「← 上一题 / 下一题 →」+ 标签状态（☑ 已答）。
  ④ 多选：本来就解析 `[ ]`/`[✔]`，补了「提交答案」按钮和一行说明；提交不再硬编码「Right 一次」，改成一路 → 直到 `review` 页再选 `Submit answers`。
  实测（模拟器连真会话）：标题、标签栏、← 上一题、多选勾选（☐蓝色/☑绿色/☐紫色）全部正常；**127 个测试通过**。已发 **code 70 / 0.9.26**。见 TROUBLESHOOTING #133。
- ✅ **0.9.27 —— 延迟根治：屏幕改成「服务器变了才推」（2026-08-28）**：用户追问「延迟真的没办法解决吗」。有办法，而且是治本的。
  0.9.26 只是把轮询调快（~10s→~3s）；本因是**每问一次就是一个 SSH 往返**，而抓屏本身 **0ms**。
  新增 `SessionProbe.watchScreen()`：一条长连通道，服务器侧自比对，**没变不过网**；实测静止零推送、变化 **206ms** 到达。送键后不再自己抓屏（`waitScreenChange` 只读本地状态）。轮询保留为回落。
  ⚠️ 踩坑一：flow 体默认在**收集方线程**跑，`readLine()` 阻塞主线程 → **ANR**，必须 `.flowOn(IO)`。
  ⚠️ 踩坑二：解析也不能在主线程（它忙时屏幕每 0.2s 变一次）→ 在 IO 上解析完再给界面，并 `.conflate()`。
  ⚠️ 量法教训：`uiautomator dump` 一次 **2.4–2.8 秒**，拿它量 0.3 秒的东西量出来全是噪声。分段量才对：TUI 重绘 100ms + 推送 206ms ≈ **0.3–0.4 秒**端到端。
  **127 个测试通过**；实机确认卡片正常、无 ANR。已发 **code 71 / 0.9.27**。见 TROUBLESHOOTING #134。
  📎 顺手加了 `dev/emu-restore.sh`：模拟器 `adb install` 常把 App 数据清空，这脚本一把恢复（授权公钥 + 写回 hosts.json）。
- 📎 **给 nanobanana 的模子文档**：`/root/src/workspace/nanobanana/YXI-MOLD.md`（174 行）—— 工具链/可抄文件/发布/纪律/踩坑 + **「在哪测怎么测」详版**（模拟器、UI 自动化的坑、端到端验证招式）；并在它的 `CLAUDE.md` 里加了指路。
  ⚠️ 模拟器踩坑：`install -r` 那次变成**全新安装**（uid 变了），App 数据被清空。恢复办法：读 App「公钥」界面的公钥追加进本机 `~/.ssh/authorized_keys`，再用 `adb shell run-as app.yxi` 直接写 `files/hosts.json`（UI 自动化填表会因软键盘顶起布局而串行到同一个输入框）。
- ✅ **`yxi-lab` CLI —— 让别的 agent 把产物推进实验室（2026-08-25）**：用户问「别的 tmux（如 nanobanana 出图）
  能不能把生成的东西放实验室」。能 —— 在线实验本来就读 `~/.yxi/lab/`。做了个 `/root/.local/bin/yxi-lab`：
  `yxi-lab add <文件> [标题] [说明]`（图/GIF/网页/文本，新的排最前，自动写 manifest.json）、`list`/`rm`/`clear`、
  `yxi-lab approved`（读用户勾了哪些）。别的 agent 一行就推，用户 App 刷新即见、勾选审核。
  实测：PIL 造图 → `yxi-lab add` → 手机实验室第一张就是那图、渲染正常、能勾选。
  ⚠️ 图走 base64 经 SSH 取，>3MB 会慢（脚本会提醒）。要让某 agent 常态这么干，往它项目的 CLAUDE.md 加一句即可。
  ⚠️ 发版又差点栽 #107：改完版本号没重编就 publish，APK 还是旧 code。**每次 publish 后必用 aapt2 核 APK 实际 versionCode**。
  ⚠️ WebView 在 RemoteLab 容器里会把短内容竖直居中/顶部裁切，做全屏 html 别指望精确布局，留余量。
  ⚠️ 图标定了 → 把选中那版转成真自适应图标（改 `res/drawable/ic_launcher_fg.xml` + `ic_launcher_colors.xml`）。
  ⚠️ 选定用哪版接到真加载态（连接中/重连中）时，改 `YxiLoader.YxiLoader()` 里默认调的那个变体。
- ✅ **下载站升级成正经网页（2026-08-24）**：原来公网只是个裸文件直链（`return 404` 的根）。
  现在 hk13 的 `/var/www/yxi/index.html` 是一张**深色下载页**（Yxi logo + 版本/大小/更新说明现取
  自 `latest.json` + 大按钮 + 4 步安装引导）。nginx snippet `yxi-dl.conf` 改成：`/`=首页、
  `/Yxi.apk` 和 `/latest.json`=干净公开直链、老 token 路径保留（已分享的链接不断）。`:8899` 和
  将来的 `dl.keuury.com` 共用这个 snippet。已 `nginx -t` + reload，实测首页/直链/版本都对。
  ⏳ **就差 HTTPS**：要 `dl.keuury.com` A→64.90.25.56 的 DNS 记录（R2 令牌改不了 DNS，见 #123），
  记录一通就 certbot 签证书（hk13 已有 certbot，别的站在用）。在此之前站是活的、只是 HTTP + 靠 IP:8899 访问。
  ⚠️ **App 内更新走 SFTP**（连的那台的 `~/.yxi/`），跟 hk13 这套 HTTP 分发**互不相干**，改这边不影响升级。
- ✅ **yxi-hub 多组成员（2026-09-02）**：一个会话在多个组里时，`who`/`context` 按组分开列、`say` 的消息带共同组名
  `[同组 组名 · 谁]`、`all` 必须指明组（#211）。服务器侧改动，已装到本机 `~/.local/bin/yxi-hub`。
- ✅ **下载站隐私政策 / 服务条款页（2026-09-04）**：Google 登录同意屏幕要的 `https://yxi.keuury.com/privacy` `/terms`（cc-logto_yxi 代老板提的）。源在仓库 `site/privacy.html` `site/terms.html`，线上在 hk13 `/var/www/yxi/`，nginx 显式 location 在 `snippets/yxi-dl.conf`。中英双语、短；改内容改那两个文件再 scp 上去。
- ℹ️ **会员服务的错误文案约定 & 联调账号**（logto_yxi 2026-09-04 定）：
  **错误一律优先显示服务端的 `msg`**（中文、他们维护）。同一个 `409 code_redeemed` 会有两种 msg：
  「已经被别人使用了」（一码一人被抢）/「名额已经领完了」（限量码满）—— 本地写死一句必然说错，App 里的兜底文案
  只在服务端没给 msg 时才用。⚠️ **「你自己已经兑过这张码」不是错误**：那是 `200 + replay:true`，按成功显示，别弹成失败。
  **哪些字段正常就是 null**（契约的一部分，写死解析的前提）：`membership.expiresAt`（没兑过码 / 永久授予时配 `neverExpires`）、
  `quota.limit`+`remaining`+`nextRefreshAt`（ultra 全 null，**判断不限要看 `quota.unlimited`**）、
  `profile.nickname`+`avatar`+`signature`+`email`（用户没设 / 没绑邮箱；社交注册的通常带昵称和头像）。
  **恒定有值**：`userId`(=sub)、`tier`、`isAdmin`、`quota.unlimited`、`quota.used`。
  → 新用户第一次进「我的」四个 profile 字段可能全空，界面每处都有空态；解析统一走 `Account.str()`（`optString` 读 null 给的是 `"null"`）。
  **接口已冻结**（logto_yxi 2026-09-04）：`GET /api/me`、`PATCH /api/me/profile`、`POST /api/me/redeem` 形状不再变，
  **只加字段、不改不删**，加之前会先说 —— 所以 App 这边解析可以按当前字段写死，不用做兼容分支。
  ⚠️ **测试账号已被删**（老板要求清掉所有验证账户，Logto 用户数 0、码 0）。要再联调**先跟 logto_yxi 说一声**，
  他们 10 秒重建一个带码的账号；`/root/.secrets/yxi-test-account.txt` 里那份已经失效。
- ⚠️ **hk13 挂了会挂掉哪些**（2026-09-04 05:30 UTC 实际发生过一次，整机不可达；站长机同时不通，疑似同一供应商故障）：
  下载站 `yxi.keuury.com`（新装 + 自更新）、登录 `auth.yxi.keuury.com`、会员服务 `api.yxi.keuury.com` 全断。
  **App 的核心不受影响** —— 看板/对话/终端/文件/实验室走的是 SSH 直连用户自己的机器，跟 hk13 没关系。
  降级行为（已确认）：检查更新静默失败不打扰；会员中心显示**上次缓存**的档位/资料；兑换和改资料报「连不上服务器」；
  ⚠️ **网络错误不会把人踢下线** —— `token()` 只在 refresh 被 400/401 明确拒绝时才 `signOut`，超时/连不上（code 0）保持登录态。
  agent 侧无事可做：机器不可达只能等供应商恢复或老板在控制台重启，别反复重试。
- ✅ **1.0.5（2026-09-04）**：
  ① **附件名带后缀**（用户）：`图片1.png` / `附件2.pdf`，给 Claude 的路径映射也带 —— 它一眼知道该按图片读还是按文档读。
  ② **通知悬浮条不再赖着**（用户：「不主动移就一直在」）：原来一律 `CATEGORY_CALL` + 全屏意图 = 系统按**来电**处理，
     悬浮条钉住不走。改成：**黑屏/锁屏才按来电**（要的就是点亮屏幕），**亮屏时按普通消息** —— 悬浮条几秒自己收，
     通知照样留在状态栏。⚠️ 安卓没有「设置悬浮条停留几秒」的接口，能控的只有别把它标成来电。
  ③ **会话页加 ☰**：会话里也能拉侧边栏。工作区从「提前 return」挪进抽屉里；
     ⚠️ 会话里**关掉边缘手势**（`gesturesEnabled=false`）—— 那个动作归系统的右滑返回，抢了两个都不好使。
  ④ **会员中心接购买**（logto_yxi）：`GET /api/purchase` 公开接口，价格 / 店铺链接 / 微信号 / 二维码**全从服务器拉**，
     一个字不写进 APK（老板改价换码不用发版）。不接支付：去发卡站或加微信买码，回来兑。
  ⑤ **封禁**：产品级封禁时 `/api/me` 照常返回、多一个 `bans` —— 会员中心显示原因和到期。
     全局封禁拿不到原因（令牌当场作废），走「登录失效了」那条。
  ⑥ **档位光环**（规格 logto_yxi 给）：pro 蓝环 12 秒一圈 / ultra 琥珀 6 秒 + 外圈薄雾呼吸；系统开减弱动效就停转。
  ⑦ **免费档最多绑 2 台主机**：⚠️ **客户端软限制**，改 APK 能绕过，老板知情 ——
     主机和密钥按设计只存手机本地，**绝不为了堵它把主机列表传服务端**（隐私政策白纸黑字写着配置只存本地）。
  ⚠️ 这一版**没在模拟器上验**（用户中途叫停了模拟器），只做了编译验证。
- ✅ **1.0.4（2026-09-04）—— 换 logo**：用户定稿的两片渐变花瓣（原图 `design/logo.png`，1254×1254 透明底 PNG，
  取自他电脑 `E:/资料/yxi_logo/定稿.png`）。生成了三套：自适应图标前景（108dp 画布里内容占 **62%** ——
  ⚠️ 铺满会被启动器的圆形遮罩切掉花瓣尖）、themed icon 的单色剪影、通知栏小图标（纯白剪影，系统只看 alpha）。
  底色 `ic_launcher_bg` 从 #E9E9E9 改成 **#F4F6FA**：纯白会吃掉左边那片浅蓝花瓣的边，原来的灰又压得发闷。
  模拟器启动器里确认过（圆形遮罩下不裁）。
- ✅ **1.0.3（2026-09-04）**：
  ① **语音识别可以自己选**（用户）：自动 / 手机上算 / 服务器上算 / 系统识别，存 `asr.engine`，
     对话页按选的那套走；选了但用不了（模型没下 / 那台没装 yxi-asr）点麦克风会直说，**不偷偷换一套**。
  ② **主机列表里在用的那台一眼认得出**（用户：多台时用不同颜色）：底色换成强调色容器 + 「在用」小标 +
     每台左边一条自己的色条（`hostColor` 从 id 派生，跟看板/配置页那颗点同色）。
  ③ **终端那行状态词进对话页了**（用户：「Mustering…（32s · token）这些要在对话里显示」）：
     输入框上面一行 `✽ Mustering… (13m 42s · ↓ 22.6k tokens)`，跑完变「刚跑完 · 13s」。
     ⚠️ 之前只有会话切换卡（`Switcher`）上有，对话页里从来没有过 —— 而对话页恰恰是盯着它干活的地方。
     来源是 `tmux capture-pane` 解析的 `Live.status`（转录里没有这行，只有屏幕上有）。
  ⚠️ `Hint2` 是纯文本，写 `**粗体**` 会原样显示星号（截图里看出来的），新文案别用 markdown。
- ✅ **1.0.2（2026-09-04）—— 语音改成点按 + 「我的」页 + 一个「装了等于没装」的老 bug**：
  **语音**：按住说话 → **点一下开始、再点一下结束**（用户：「长按太麻烦」）。录音时**整条输入框变成实时波形**
  （`Recorder.level` 是真的峰值，不是循环动画 —— 麦克风被占着时波形是平的，一眼看出没收到声音）+ 计时 + 红方块停止。
  ⚠️ 修掉一个从写下来就没生效的 bug：`recording` 状态**从来没被置 true** —— 红麦克风、录音态全是死的（#232）。
  ⚠️ 更要命的一个：`yxi-asr` 装在 `~/.local/bin`，而**非交互 `ssh exec` 的 PATH 里没有这一条** →
  App 一直判定「服务器没装语音识别」，默默退回系统识别；而用户的手机 GMS 是关的、系统识别根本不存在 = 麦克风点了没反应。
  探测和调用都补上 `export PATH="$HOME/.local/bin:…"`（#233）。
  **底部导航「设置」→「我的」**（Profile）：页面顶上是资料卡（头像/昵称/签名/**UID，长按复制**）+ 会员卡，
  下面按 QQ 那种分组：反馈与版本 / 手机功能 / 界面 / 关于与帮助。
  **修**：在会员中心点底部导航不跳走（那一层盖着，现在点导航先退出它）。
  **兑换码**：加 `403 code_not_started` →「这张码还没生效」；成功文案用返回的 tier（通用码可能发 ultra）。
  实测确认给 logto_yxi：ultra 的 `quota.unlimited=true`，连改两次资料都是 200，不再有 429。
- ✅ **1.0.1（2026-09-04）—— 账号这条路真机跑通了**（cc-logto_yxi 给了测试账号，在模拟器上端到端过了一遍）：
  登录（邮箱+密码 → 回调 → 存 token）→ `GET /api/me` → 兑换码（pro / ultra 各一张）→ 改签名 → 再改一次撞配额上限，全过。
  结论回给 logto_yxi：**兑换后 tier 立刻变**（pro 到期 10-05；再兑 ultra 从 10-05 往后叠到 11-05）、
  **额度按新档位的周期回满**（pro 的下次刷新是 09-15，ultra 显示「资料改多少次都行」）。
  路上修掉三个：
  ① **签名框里赫然写着 `null`** —— `optString` 读 JSON null 得到的是字符串 `"null"` 不是空串；所有可空字段统一走 `str()`。
  ② **兑换码输入会串位**：原来每敲一个字就把整串重排再写回输入框，输入快一点（adb 灌 / 粘贴）就乱 —— 实测 18 个字符 9 个位置对不上。
     改成**只存纯码、连字符用 `VisualTransformation` 画**（不再改值）。
  ③ 服务端新加了 `quota.unlimited` 布尔，改成先读它（原来的 `isNull` 写法也对，但这个更明确）。
- ✅ **1.0.0（2026-09-04）—— 账号登录 + 会员中心接上后端**（cc-logto_yxi 的会员服务已上线）：
  **登录**：Logto OIDC + PKCE，**手写的，没引 SDK**（离线构建加不了依赖）——
  `agent/Account.kt`：`/oidc/auth` 拉浏览器 → `io.yxi.app://callback`（manifest 里的 intent-filter，MainActivity 是 singleTask 走 onNewIntent）
  → `/oidc/token` 换 access/refresh/id token，存 App 私有 SharedPreferences；access 快过期自动用 refresh 续，续不上就当掉登录。
  ⚠️ 回调必须校 `state`（对不上直接扔，实测拿假回调打过：不存任何 token）。
  **会员服务**（base `https://api.yxi.keuury.com`，Bearer 用户 access token）：
  `GET /api/me` = 会员中心整页（档位 / 到期 / 配额 / 资料）；`PATCH /api/me/profile` 改昵称+签名；`POST /api/me/redeem` 兑换码。
  ⚠️ **一次提交算一次配额**（免费每月 1 次、pro 每月 2 次、ultra 不限）→ 编辑框是「一起改、一次保存」。
  ⚠️ **必须发真正的 PATCH**：试过 `POST + X-HTTP-Method-Override` → 服务端 404（只有 PATCH 路由）。
  ⚠️ `quota.remaining: null` = ultra 不限；用 `optInt` 读会得到 0（= 用完了），意思正好反过来，必须先 `isNull` 判。
  兑换码输入自动大写 + 每 4 位补连字符；同一个人重兑同一张码服务端幂等（`replay:true`，不重复加天数），文案照实说。
  **还没做**：自定义头像上传（等 Logto 配 R2；现在只显示社交注册带来的头像）、支付（所以档位卡片是「即将开放」，
  升级只能靠兑换码）。契约全文在 hk13:/root/src/workplace/logto_yxi/handover.md「会员服务」一节。
- ✅ **0.9.99（2026-09-04）—— 会话卡的手势和标记全改**（用户拍板）：
  **左滑 = 拉出一排按钮**（收藏 / 置顶 / 终止），不再是「滑到底直接弹终止确认框」；**右滑 = 快捷收藏**（不变）。
  一次只开一张（开关状态在父层）；面板开着时点卡片先收起，不会顺手进对话。终止仍然走确认框。
  **卡片上的 ☆ 和图钉去掉了**，改成左边一条**色条**：收藏 = secondary，置顶 = tertiary，两者都有 = 两色渐变。
  ⚠️ 色条画在卡**里面**（左 4dp）：第一版画在 padding 外（-12dp）想蹭出去，被 Surface 的圆角裁没了，屏幕上啥也没有。
  置顶区（状态视图）的卡也套了左滑面板 —— 图钉没了，那儿不套就没法取消置顶；跟长按拖排序不冲突（触发方式不同）。
- ✅ **0.9.98（2026-09-04）—— 页眉留白 / 跟随抽动 / 显示旧对话（都在模拟器上量过）**：
  ① 页眉高度从 Column 的 padding 挪进**列表的 contentPadding** —— 原来那是永久空出来一条，页眉一收就露底色（用户：「X 的就不会」）；
     页眉现在悬浮着，所以给它加了不透明底 + 底边一小段渐变，正文从底下滚过去不会糊在标题上；
  ② 跟随不再瞬移：看得见最后一条就只补差的那几十像素（原来 `scrollToItem(last)` 会把最后一条的**顶部**顶到屏幕上沿，
     长回复时中间那一帧甩到别处 = 「一跳一跳」），远了才一次跳到位；跟随用 170ms 动画滑过去；
     而且**攒 110ms 再追**（一批内容常分几次到、队列里的消息还会一冒一消，每次都追就是一秒抽三次）；
  ③ 显示的是旧对话却不吭声：转录换文件了就把内存里那批**立刻扔掉**（那是别的对话），
     磁盘缓存/内存快照先画出来时明说「这是上次的内容，正在取最新…」，真内容到了才撤（用户截图：「怎么会加载很久很久之前的对话」）。
- ✅ **0.9.97（2026-09-04）—— 侧边栏学 QQ + 会员中心**（用户给了 QQ 侧边栏截图对比）：
  顶上「我」（头像 / 昵称 / 个性签名，点开就改；头像从相册选，压到 256px 存 `filesDir/avatar.png`，全在本机）→ 主机切换 →
  功能入口（主机 / 配置 / **会员中心**，彩色细线图标 + ›）→ **最底下一行是 设置 / 夜间 / 版本**。不做钱包、不做相册（用户明确不要）。
  **会员中心** = 免费 / Pro / Ultra 三张卡，写清每档有什么；订阅没开放，按钮是「即将开放」，不做假的下单流程。
  账号和订阅字段在 logto 那边做，已通过 yxi-hub 同步过去（free|pro|ultra 三态 + 头像/签名要有存的地方）。
  底部导航和抽屉的图标全部改成**自己画的线图标**（`ui/LineIcons.kt`，24 格路径描边）—— 原来那几个 Unicode 字符「太难看了」。
- ✅ **0.9.96（2026-09-04）—— 上下栏学 X 的手感 + ↓ 闪的真因（模拟器实测）**：
  ① 收起改成**跟着手指连续走**（不再是过阈值啪一下），松手过半才收干净，下栏 ×1.7 比上栏退得快（用户对着 X 慢动作提的）；
  ② 正文顶部留白恒定 = 页眉高，栏是**盖**在正文上的 —— 栏走正文一动不动，没有「突然多出一块字」；
  ③ **↓ 一直闪的真因**：条目的进入动画会**反复重放** —— 只要那条被重新组合（列表跳位置、滚出去又滚回来）就再淡入一次，
     一屏同时来一下就是「整屏白一下再淡回来，一秒两次」。改成**一条只放一次，永远**，且只有末尾三条内新来的才动画（#227）；
  ④ 粘着（跟随）时不显示 ↓ 按钮（它闪就是因为它在跟着）；跟随只往前滚，绝不往回；
  ⑤ 通知重复提醒：升级重提醒从 2/5/10/20/40 分钟五次改成**只补一次**（10 分钟），同一件事 5 分钟内再来只静默更新不再响；
  ⑥ 输入框里去掉「清空」（逃生口留在设置的开发者卡片）。
  ⚠️ 这一版是**在模拟器上实测过的**（dev/avd.sh + 造了个会不停出字的假会话 /root/flicktest，逐帧量位移）。
- ✅ **0.9.95（2026-09-04）—— 用户录屏里的一串**：① 收起方向反了 → 改成下滑收、上滑展开、到底展开；② 页眉收起时顶上留白 → 对话模式页眉悬浮
  （movableContentOf 在 Column / 顶部叠层之间搬家），正文按 topInset 动画下移，收起时滑到状态栏底下；③ 输入框「黑色阴影里一块白」→ 透明 Surface + elevation 的
  阴影透出来（#225），改成自画呼吸光晕；④ 输入框做成 **玻璃壳** `GlassPill`：半透明底 + 色相流动 + 上亮下暗立体 + 斜向光带扫过 + 渐变细边 + 外圈呼吸光；⑤ 点 ↓ 跳底部之后按钮一直闪（#226：底部留白跟着栏变 + 程序滚动也走 nestedScroll，自激）。
- ✅ **0.9.94（2026-09-04）**：修 0.9.92 引入的「输入框文字删不掉」（#224：单行 / 多行两支各一个 BasicTextField，换排版就换实例）——改 movableContentOf 同一实例搬家、多行排版粘住到清空；多行排版加「清空」；开发者卡片加「清空所有输入框草稿」逃生口。
- ✅ **0.9.93（2026-09-04）—— 组规**（用户：想给分组「注入」指令，不知道放 hook 还是 CLAUDE.md、不想先建文件）：
  `groups.json` 加 `rules{组名: 文本}`，服务器 `yxi-hub context`（SessionStart 钩子，startup / resume / clear / compact 都触发）把本组组规
  跟同组名单一起注入；新增 `yxi-hub rules` 给 agent 自查。手机：分组视图每个组头旁「组规」药丸 → 编辑框，保存 / 存并发给在跑的成员
  （`Groups.tellRule` 走 send-keys）。不建文件、不改 CLAUDE.md。
- ✅ **0.9.92（2026-09-04）**：状态栏跟 App 融为一体（用户对比 Gemini 截图）：工作区不再吃状态栏 inset，光晕铺到最顶上，页眉自己 `statusBarsPadding`；状态栏区域盖一层底色→透明的渐变，页眉收起后正文滑到状态栏下面会被压淡；主题里的白色 statusBarColor 改透明。
- ✅ **0.9.91（2026-09-04）**：输入区学 Gemini（用户截图）：整块（快捷语 + 附件条 + 输入胶囊）**悬浮**在对话上面，底下的字从淡渐变里透出、列表底部按输入区实际高度留白；胶囊更圆（32dp）带阴影、底色 surface；打多行时换成两段式：文字在上占满、＋ / 🎤 / 发送沉到下面一排。
- ✅ **0.9.90（2026-09-04）**：学 Threads 的侧边栏（用户发视频）：看板左上角 ☰ 或从左边缘划，抽屉从左滑入、主页面被推向右（`ModalNavigationDrawer` + 按 currentOffset 偏移 Scaffold）。抽屉里**放什么用户还没定**，先放：品牌 + 版本、主机切换、主机/配置/设置入口。
- ✅ **0.9.89（2026-09-04）**：学 X 的小巧思（用户发视频）：对话页上划时页眉 + 模式条 + 输入栏一起收起，下滑展开；到底或键盘开着时一律展开。nestedScroll 看方向，方向一换重新累计、过 28dp 才动。
- ✅ **0.9.88（2026-09-03）**：看板加「搜索」药丸：展开输入框，按会话名 / 目录 / 状态词过滤，跟「目录」筛选叠加；歇掉的收藏也按名字筛。
- ✅ **0.9.87（2026-09-03）**：「目录」筛选改成**每一级祖先**都能选（/opt、/opt/workspace、/root、/root/src、/root/src/workspace…），下拉里按深度缩进、带个数（用户：粗到 root / opt，细到 root/workspace）。
- ✅ **0.9.86（2026-09-03）—— 重进对话秒开**（用户：「退到看板再进 Yxi / logto 要等『读取会话状态』」）：`agent/ChatMemory`
  按 (主机, 会话) 留最近 6 个会话的条目 + 解析器 + 转录**字节位置**；重进先原样摆出上次的，再 `tail -c +N -f` 只拉增量
  （`TranscriptStream.tailStart / streamFrom`），转录文件换了才走老路（#221）。
- ✅ **0.9.85（2026-09-03）**：看板加「目录」筛选药丸：列出各会话目录的上一级（带个数），选一个只看那棵树下的会话（含歇掉的收藏），按主机记住；标题下写「N 个在 X 下 · 共 M 个」。看板**默认分组视图**（用户定的），切过的按记住的来。
- ✅ **0.9.84（2026-09-03）**：对话页顶上那条「⚡模式 / 模型 / 思考 / 上下文 / 今日」默认收起，页眉右侧加 ⚡ 按钮点开（记住选择）；展开后照旧可点切换（用户：常驻太难看）。
- ✅ **0.9.83（2026-09-03）**：输入框封顶 7 行、超了框内滚（用户截图：一大段话把整屏占满，对话全看不见）；圆角 28dp 代替 Pill。
- ✅ **0.9.82（2026-09-03）**：① 语音：模型装好后麦克风从「点一下开系统识别」变成「按住说话」，原来没人说 —— 用户「下了模型语音就没用了」。
  现在就绪时提示一次、点一下（<300ms）也提示「要按住」。② 对话页的光晕（ThinkingGlow）挪到 Workspace 整层垫底，铺到页眉和模式条
  （用户圈图：「顶部那部分也带上」）；ChatScreen 只报状态（onGlow），模式条底色改半透明。
- ✅ **0.9.81（2026-09-03）—— 多文件上传重做**（用户：「一次上传多个还是有 bug，上传中要有 ✕ 取消该文件」）：
  每个文件选中即出一张小卡（名字 + 进度环 + ✕），各自一个协程、Mutex 排队顺序传；✕ 通过 SFTP 进度回调返回 false 中止 jsch 的 put，
  并删掉服务器上传了一半的文件；失败留在卡上点名字重试；还有在传的时候发送按钮禁用。`Sftp.write` 加进度回调、`Sftp.rm`、
  `Attachments.remotePath`。**分享面板**：原来只注册 `SEND`（单文件），相册一次分享多张走 `SEND_MULTIPLE` 根本进不来 —— 补了 intent-filter，
  ShareActivity 顺序传、一条消息带全部（#220）。同样没模拟器 E2E。
- ⚠️ **0.9.79 / 0.9.80 发布时没做模拟器 E2E**（2026-09-03）：Mac 反向隧道断了，Android 模拟器只在 Mac 上跑（本机跑会把服务器拖死，#200/#206），用户拍板「可以发布，Mac 隧道不管了」。已验的是：0.9.78 那套流程（装机卡 / 装机框 / 登录）、`yxi-lab update`、Archify 试作、探活命令原样输出、编译 + i18n。**没在真机上点过的**：探测框、装机表单、主机面板「探测 / 装机」、实验室三个按钮的弹框、全屏捏合、SVG、拉起 --resume。下次 Mac 回来先把这几处过一遍。iOS 继续冻结（D24），不再构建。
- ✅ **0.9.80（2026-09-02）—— 实验室「让 agent 画图」（D29）**：页顶「查明并画出来」「把结构画成图」+ 卡片「更新」，
  弹框里可填提示词（不填默认执行）、选派给哪个会话，发出去后每 20 秒自动刷 10 分钟。提示词在 `agent/LabPrompts.kt`，
  规矩跟 `yxi-lab spec` 一致；服务器侧新增 `yxi-lab update <id> <文件>`（原位替换，id 不变）。全屏：图片捏合缩放 + 拖动，
  网页开 WebView 缩放；SVG 改走 WebView（原来 BitmapFactory 解不开）。**试作**：用 Archify 给 Yxi 画了一张架构图推在本机实验室「架构图」组
  （Node 18 能跑；要剥 Google 字体引用；showcase 校验很严，改了四轮）。
- ✅ **0.9.79（2026-09-02）—— 装机做「傻瓜化」**（用户要的）：
  · **探测框**（`ProbeDialog`）：现探 tmux / Claude Code / Codex 装没装、登没登录、系统与架构、有没有 Node（不需要）；
    下面一张表单 **都装 / 只装 Claude Code / 只装 Codex** + 「一键装机」，**默认不执行**。`bootstrap.sh` 用 `YXI_NO_CODEX=1` / `YXI_NO_CLAUDE=1` 跳过。
  · 三个入口：看板（什么都没装 → 装机卡；装了但没有会话在跑 claude / codex → 一行「探测 →」）、
    **主机页长按面板常驻「探测 / 装机」**（现连现探，关框断开）、「＋」开会话时没装的那个 agent 写「没装，点我去装」。
  · 「配置 → 连接」里每行的「安装」只装那一个。
  · 「拉起」歇掉的 Claude 收藏时接上该目录最近的转录（`claude --resume <uuid>`，#218），裸装 claude 的客户机器也能接回。
- ✅ **0.9.78（2026-09-02）—— 新客户开箱 + Codex**：
  · **一键装机**：`server/bootstrap.sh`（公网 `https://yxi.keuury.com/bootstrap.sh`，`./server/install.sh --publish-server` 发布，
    `--publish` 发 APK 时也顺手发）—— tmux / curl / git / python3（系统包）+ Claude Code（官方原生安装器）+ Codex（GitHub 静态二进制）
    + Yxi 服务器侧工具，**不需要 Node.js**，幂等。手机端：看板同步后发现 claude / codex 都没有 → 「一键装机」卡（`SetupCard`），
    手机自己从公网取脚本、heredoc 塞进服务器、nohup 跑、每秒 tail 日志（`agent/Setup.kt`，#215）。全新 Ubuntu 24.04 容器实测装通（#216）。
  · **Codex 会话**：命名 `cx-<目录>`（Claude 是 `cc-`），窗格里跑的命令是 `codex` 也认；看板卡片标「Codex」芯片、
    点开默认终端、对话页置灰并说明；「＋」开会话时两个都装了才给选。**没有**状态源 / 通知 / 对话视图（yxi-hook 是 Claude Code 的钩子）。
  · **登录从手机做**：「配置 → 连接」最上面两行 Claude Code / Codex：没装→「安装」（同一个装机框），装了→「登录」：
    Claude 是 `claude auth login` 打 URL、页面给码、粘回去；Codex 是 `codex login --device-auth` 设备码（#217）。已登录显示账号、可「退出」。
  · exec 一律先 `export PATH=$HOME/.local/bin:$PATH`（非交互 ssh 不读 .profile，装在那儿的 claude / codex 原来探不到）。
  · **Mac 模拟器对着干净容器全程验过**：装机卡 → 装机框（1 分钟装完）→ 卡片消失 → 连接面板两行「登录」→ Codex 设备码出来、
    Claude 给码框出来（粘错码服务器那头报 Invalid code，App 提示重粘）→ ＋ 选 Codex 开 `cx-demo` → 直接进终端见 Codex 登录菜单 →
    看板卡片带「Codex」芯片；内部 `yxi-auth-*` 会话不再上看板。真登录（要用户账号）没验，只验到给码/给 URL 那一步。
- ✅ **0.9.77（2026-09-02）**：文件浏览大目录不卡（#213）、换目录清屏；Markdown 图片按大小分流（#214：>8MB 不加载，
  其余占位慢加载、降采样）。
- ✅ **0.9.76（2026-09-02）**：文件预览里 Markdown 图片**真显示了**（#212：图片缓存挂在 FileViewer 作用域上，
  模拟器验证过三种写法）；会话卡片瘦身（☆/图钉收进标题行、回一句缩到路径行末、状态色点、「等了 N」药丸），
  高度少约三分之一。
- ⏳ **Active Theory 风开屏（2026-09-02）**：用户要「像 activetheory.net 那样但配我们 logo」。三版网页预览已推到实验室
  （组「Active Theory 风」：深空墨光 / 虹环 / 霓虹笔画，深空底 + 荧光粒子 + 色差环 + 胶片颗粒，触摸有反应），
  Mac 模拟器里看过都能画。源文件在 `design/lab-previews/`。**等用户挑**，挑中再做原生版。
  ⚠️ 这三版是深底的，App 默认浅色 —— 上线时开屏单独走深底即可，不必改主题。
- ✅ **0.9.75（2026-09-02）**：开屏动效**四款随机轮播**（`Splash.ROTATION`：粒子聚合 / 星尘汇聚 / 旋涡 / 星点闪现，
  不连续重复；用户在实验室里选的），三款新原生实现 `SplashStardust/Vortex/Sparkle.kt`（子代理从网页版移植），
  Mac 模拟器里冷启动五次截帧验证过。笔触书写 / 墨迹落定 / Y 落下留着备用。
- ✅ **0.9.74（2026-09-02）**：实验室网页预览**真修好了，Mac 模拟器里验证过**（#209：WebView layoutParams 要
  match_parent，否则 100vh = 0）。⚠️ 界面验证以后走**用户 Mac 上的模拟器**（`~/yxi-build/mac-emu.sh`，#206），本机不起模拟器。
- ✅ **0.9.73（2026-09-02）**：文件预览里 Markdown 的 HTML `<img>` 也能显示（`agent/MarkdownFix.kt`，#208）。
- ✅ **0.9.72（2026-09-02）**：实验室网页预览再修两条根因（#207）+ 把 JS 报错显示在卡片上当 DevTools。
  ⚠️ 本机模拟器已判定不可用（#206），界面问题靠 App 内诊断 + 用户截图。
- ✅ **0.9.71（2026-09-02）**：实验室规范化（D27）：`server/yxi-lab` v2 进仓库（`spec` 契约 / `template` 骨架 /
  `check` 校验 / `add --aspect --group`，install.sh 会装）；App 端统一卡片模板（标题+类型 · 按比例定高的预览框 ·
  说明 · 谁/何时 · 采纳/全屏/存本地）；修了 WebView 反复重载导致的空白（#205）和竖图裁切。
- ✅ **0.9.70（2026-09-02）**：开屏动效定了 **粒子聚合**（`Splash.DEFAULT_KEY = "particles"`，冷启动播），
  笔触书写 / 墨迹落定 / Y 落下留着备用，光晕绽放淘汰删掉；实验室按服务器隔离（D26）：栏目置顶按主机存，
  标题下标明是哪台的。
- ✅ **0.9.69（2026-09-02）**：工作区页眉可收起，收起只留会话名（`Prefs.folded`）；开屏动效的 App 内置卡撤掉（D25），
  五个方案改成 HTML 从 `yxi-lab` 推到实验室审，采纳后改 `Splash.DEFAULT_KEY` 发版。
- ✅ **0.9.68（2026-09-02）**：开屏 logo 动效五个方案（`ui/splash/Splash*.kt`，五个子代理各写一个：
  笔触书写 / 光晕绽放 / 墨迹落定 / 粒子聚合 / Y 落下字展开），摆在实验室「开屏动效 · 待你审」卡里能播能选；
  选中的（`Splash.chosen`，prefs `splash`）冷启动时由 `SplashGate` 盖着播一遍。⏳ **等用户挑**，挑之前默认不播。
- ✅ **0.9.67（2026-09-02）**：附件头在正文中间也认（#201）；抓取不完整不再当成零个会话（#202）；
  设置「手机主动响」加「弹窗（横幅）」状态 + 「发一条试试」（#203）。只更新安卓（D24）。
- ✅ **0.9.66（2026-09-02）**：连着 ≥3 条同名、已完成的工具卡合成一张（`groupToolRuns` /
  iOS `ChatRow.group`），点开铺开，进行中和出错的不合；输入框底色跟页面光晕同一套色相流动
  （`glowBrush` / iOS `GlowPill`）；GitHub 登录 `BROWSER=true`（#199）。两端都编过。
- ✅ **0.9.65（2026-09-02）**：配置页分成「连接」和「Agent 配置」两块。「连接」（`agent/Connect.kt` +
  `ui/ConnectPanel.kt`）：GitHub 设备码登录（git + GitHub MCP 一起）、二十来个公开远程 MCP
  （Notion / Linear / Sentry / Jira / Stripe / Zapier / Vercel / Figma …）一键 `claude mcp add` + `login`，
  回调靠 SSH 端口转发接到手机（#198）；免认证的 Context7 / DeepWiki / Hugging Face 直接加；自定义地址。
  Gmail / Slack / 日历只在 claude.ai 订阅登录下有，面板里说明了。
  iOS 同步有了（`YxiKit/Agent/Connect.swift` 有测试 + `UI/Config/ConnectPanel.swift`，Mac 上编过）——
  ⚠️ iOS **没做端口转发**（Citadel 只给积木），MCP 授权完要把 localhost 地址粘回来。
- ✅ **0.9.60 ~ 0.9.64（2026-09-02）**：终止走 `cloud-forget`（#188）；通知图标换新 logo + 官网换 logo/favicon（#189）；
  附件发出去第一帧就是缩略图（#190）；**照参考款 录像逐帧抄的动效**：光晕待机在底部、忙了迁到顶部、
  干活中色相循环、回答到达退掉，新消息滑入淡入（#191）。`/model` 菜单只在会话启动时读配置（#187）。
  0.9.63：光晕铺整页，输入框上方那道色差没了（#192）；仓库里旧产品名全部换成「参考款」，只改名字（#193）。
  0.9.64：通知着色改品牌蓝；锁屏小图标显示旧的是 **ROM 缓存**，包里已核实是新 Y（#194）。
  官网首屏加了同一套流动光晕，底边 mask 淡出（#195，已部署）。
  ⏳ **iOS 还没跟上这一轮**（缩略图 / 光晕 / 滑入 / 手机端语音 / 新 logo / 默认浅色）—— 本机编不了 SwiftUI，
  要推上去跑 CI；用户说 GitHub token 要先换，所以还没推。
- ✅ **0.9.53 ~ 0.9.59（2026-09-01 ~ 09-02）**：
  - 同组 agent 互发**多行**消息卡在对方输入框（#174）：Claude Code 把连着来的一大块当**粘贴**，
    紧跟的 Enter 被吞进粘贴块。`yxi-hub` 和手机端 `send` 都在文本和回车之间 `sleep 0.4`。
  - 语音识别三层（#175 #177）：**手机上算**（sherpa-onnx + SenseVoice，APK 28→49MB，
    模型 153MB 首次下载）→ 服务器 `yxi-asr` → 系统识别。按住说话，结果只填输入框。
    ⚠️ release 只打包 arm64，**模拟器上没语音**；debug 补回 x86_64。
  - 发出去的图显示缩略图、点开放大（#179 #184）；思考时多色**流动**背景光（#180 #185 #186）。
  - 新 logo（`design/logo-yunxi.png`，#181）；默认浅色、名字不再提 参考款（#182）。
  - 「未启用」在没同步到之前不下结论（#183）；冷启动骨架 / 断线提示（#170）。
  - `/model` 菜单定成 `["default","fable-5-1[1m]","opus-4-6[1m]","sonnet[1m]","haiku"]`（#187）。
  - 服务器：swap 8G→16G（`/swapfile2`，已进 fstab）。

- ✅ **0.9.52：一次传多个 + 安装器能重拉（2026-09-01）**
  - **附件一次能选多个**（用户要的）：安卓换 `GetMultipleContents`、iOS 换
    `photosPicker(maxSelectionCount:)` + `fileImporter(allowsMultipleSelection:)`。
    传的时候**顺序传不并发**（#172）：并发时每个协程算 `idx` 都读到同一份 `staged`，
    五张全叫「图片1」；顺序传每轮读得到上一轮结果。失败**攒起来一次报**（「5 个里有 3 个没传上」），
    界面上带「传着… 3/5」。
  - ⚠️ iOS 顺手修了个还没露头的坑（#171）：相册给的每张都叫 `image.png`，
    而远端路径是「秒级时间戳-文件名」—— 同一秒选的几张会写到**同一个路径**互相覆盖。
    改成毫秒 + 文件名带批内序号。
  - **「点拉起安装器没反应」**（#173）：那个绿按钮是 `MorphButton` 的**成功态**，
    `clickable(enabled = Idle || Fail)` —— **根本不可点**，而文案写着「拉起安装器」。
    真根因更深：下载跑在 app scope 上要好几分钟，下完那一刻用户多半已经切出去了，
    而 **Android 10 起后台不许起 Activity，`startActivity` 静默失败**。
    现在 `MorphButton` 有 `okTap`，`UpdateDownloader` 留着下好的文件（`ready`）+ `installNow()`，
    回到这屏点一下就再拉一次，**不用重下 28 MB**。文案改成「已下好 · 点一下安装」。
  - ⚠️ **iOS 那两处界面改动没有编译验证过** —— `swift build` 在 Linux 上只编 `YxiKit`，
    SwiftUI 那个 target 只能在 Mac / GitHub macOS runner 上编（#160 就是这么漏的）。
    逻辑层 314 个测试全过，但界面要 push 触发 iOS CI 才算数。

- ✅ **0.9.50 / 0.9.51：四个用户报的 bug + 一次我自己造成的事故（2026-09-01）**
  - **一键收拾以前收的是它自己**（#165）：`ps -eo args=` 会把执行扫描的那条 awk 列出来，
    而它命令行里写着 `GradleDaemon` —— 于是把自己认成 Gradle 守护进程。
    用户看到的「可以收拾 1 类 · 约 7 MB」就是它自己那三个临时 shell。**这按钮从上线起是空的。**
  - **新增两类可收拾的**：「闲置会话」和「一直霸着 CPU 的进程」。
    会话走 `cloud-forget`（移出自动恢复名单 + 杀，**对话存档保留**），不按 pid 杀 ——
    否则 `cloud-watchdog` 15 秒就把它 `--resume` 拉回来。
  - ⚠️⚠️ **我用 tmux 的 `session_activity` 判闲置，杀掉了用户正在用的 `cc-hexingyang`**（#166）。
    那个时间戳没人 attach 时不更新，而这条坑 `SessionProbe.lastActivityOf` 的注释里是我自己写的。
    代码里改成 `max(tmux 活动, 转录 mtime)`、转录按 sessionId 找、busy/waiting 跳过、
    **查不到就不列（fail-closed）**。
  - **会话 cd 过就再也找不到转录**（#167）：改成按 `sessionId` 找（`~/.claude/sessions/`
    下那些 json 里有 `tmux` 和 `sessionId`，转录文件名就是 `<sessionId>.jsonl`）。
  - **`[Image: …]` 冒充用户说话**（#168）：那些消息带 `isMeta: true`，图片注解整条丢、
    其余画成「系统消息」。
  - **冷启动一块空看板**（#170）：`Recent` 落盘（带时间戳，>24h 不给），
    断线时摆上次那份并标「N 分钟前的状态」；**真的一无所有**才画 `BoardSkeleton`
    （骨架卡片跟真卡片同形同位，关了系统动画就不扫光）。
  - ⚠️ 踩了个 Kotlin 的坑（#169）：**块注释会嵌套**，注释里写 `sessions/*.json`
    那个 `/*` 开了内层注释，把整个文件吃掉了，报错却指向几十行外的 `{`。

- ✅ **官网换成真机截图 + 参考款 配色（2026-09-01）**：`yxi.keuury.com` 首屏那台手机、
  以及滚动走廊的四个场景（看板 / 审批 / 分组 / 体检），原来都是**手写 HTML 画的仿真界面**，
  现在换成**真截图** —— 安卓模拟器跑真 App、连演示账号 `demo@本机`、拍下来的
  `/var/www/yxi/shots/{board,approve,group,health}.webp`（600×1300，各 25~36 KB，共 110 KB）。
  相框改成 `aspect-ratio:1080/2340`，跟截图同比例，所以一个像素都没裁。配色是 参考款那套
  （`#346BF0` / `#4893FC` / `#BD99FE`，渐变只用在标题第二行和主按钮两处）。
  ⚠️ 加 `/shots/` 踩了两个坑，都记在 TROUBLESHOOTING：**#162** 那份 snippet 是白名单，
  不登记的路径一律 404；**#163** Cloudflare 连 404 都缓 4 小时，改文件名比清缓存省事。
  ⚠️ **截图怎么拍见 #164** —— 这台机器 steal 50%，模拟器一被点就 ANR，
  能改 prefs 就别点屏幕（比如切分组视图是写 `shared_prefs/yxi.xml` 的 `boardview:demo`）。
  ⚠️ 演示数据是**编的**：`demo` 用户的 `~/.claude/sessions/*.json`（会话状态，看板**优先读这份**）、
  `~/.cloud-status/*.json`、`~/.yxi/events.jsonl`（卡片上那句「在干什么」）、`~/.yxi/groups.json`
  和五个 tmux 会话，里面没有任何真实客户 / 项目名。**要重拍先刷新这些时间戳**，
  否则卡片上会写「18 小时前」。

## 读写信息在哪
| 路径 | 性质 |
|---|---|
| `~/.cloud-status/<会话>.json` | 【只读源】`cc-state` 写的会话状态，Phase 1 的数据源 |
| `~/.claude/projects/<项目>/<uuid>.jsonl` | 【只读源】Claude transcript，Phase 5 Chat View 的数据源 |
| `~/.claude/settings.json` | 【要改】Phase 3/4 往里注册 `yxi-hook` |
| `/root/.local/bin/{cc-state,hub,cc-quota}` | 【只读源】复用的现成件 |
| `~/.yxi/events.jsonl` | 【本项目自有】事件流，hook 追加写、App `tail -f` 读。超 5 MB 自截断 |
| `~/.yxi/answers/<id>` | 【本项目自有】审批回答，App 写、hook 读完即删 |
| `/root/src/tmp/<项目>/` | 【本项目自有·**会被自动删**】手机上传的附件暂存区，**保留 3 天**。`/root/src` 不是 git 仓库，安全 |
| Android Keystore 里的 SSH 私钥 | 【App 内·硬件保护】导不出来；撤销 = 服务器删 `authorized_keys` 一行 |
| `~/.ssh/authorized_keys`（本机 / station / inst2） | 【要改·**共享状态**】`yxi@android` = 用户真手机，`yxi@emulator` = 模拟器。**`dev/seed.sh` 只许动 `yxi@emulator`**，动了另一个就等于把用户踢下线，见 TROUBLESHOOTING #65 |
| `/root/inbox/base.apk`、`/root/inbox/apk/` | 【参考】原版 Moshi Android 3.10.0 及其解包，逆向证据来源 |

## 界面视觉稿

十一张安卓界面稿在 **[claude.ai/code/artifact/e2546d9f-3fb1-44b1-92d5-18736ade8d1e](https://claude.ai/code/artifact/e2546d9f-3fb1-44b1-92d5-18736ade8d1e)**（私有）。
源文件在 `design/*.dc.html` + `design/canvas.json`，**改动要改源文件再重新生成**，成品页 `yxi-app-design.html` 已 gitignore。

**视觉方向**（我定的，用户认可）：暖色深底 + 终端气质。`Space Grotesk`（界面）配 `JetBrains Mono`（代码/路径/时间）。
两个强调色同亮度同彩度、只变色相：铜色 `#e08b57`（主操作、用户消息）、青色 `#35b1a1`（干活中、Edit）。
**琥珀 `#d5a244` 专留给「等你」**——全 app 只有需要用户动手时才出现这个色。
**没有克隆 Claude 的品牌**，Yxi 有自己的身份。

十一张：会话看板 · 对话模式（主界面）· 交互卡片（AskUserQuestion / ExitPlanMode）· 终端模式+键盘工具条 · **文件模式** · **Markdown 阅读视图** · **D-Pad 方向键盘** · **悬浮排列会话切换** · 附件与语音三态 · 锁屏审批 · 主机管理。

## 视觉稿批注页（评审用）

**`http://<公网IP>:8899/<token>/`** —— **可交互原型**（悬浮排列真能滑、带视差；模式切换真能跳；思考条能展开；滚动入场动画）+ 手机上点任意位置钉批注，落 `design/review/pins.json`，
**Claude 直接读这个文件**就知道是哪张稿、哪个坐标、什么问题，不用用户描述位置。

- `design/review/serve.py` —— stdlib http.server，只读画板 + 一个写 pins 的接口
- `design/review/build.py` —— 把 `design/*.dc.html` 的画板抽出来拼成单页，
  ⚠️ **各文件的 `<style>` 必须作用域隔离**（Terminal 和 DPad 都定义了 `.k`，不隔离会互相覆盖）
- systemd `yxi-review.service` —— **2026-09-03 已按用户要求关停并取消自启**（实验室接管了审核，D25/D26；pins.json 自 8/22 没动过）。要用回：`systemctl enable --now yxi-review`。
- ⚠️ **token 在 `design/review/.token`，已 gitignore**。页面无敏感内容、不执行任何东西、
  路径穿越和越界 POST 都返回 404

**改完稿要重新发布 Artifact，批注页会自动跟着更新**（它每次请求都重新抽取源文件）。

## 视觉方向：**Material 3 深色**（已定）

**M3 的核心是用面的明度分层代替描边**——全部 1px 边框已去掉：
页面 `#16130f` → 卡片 `#1e1b17` → 控件 `#221f1b` → 抬起 `#2d2925` → 最高 `#383430`

圆角 22~28px、控件一律药丸 100px、留白 18px、块间距 22px、正文 15px。
强调色：铜 `#ffb787` · 青 `#8fd8c6` · **琥珀 `#ffc46b`（等你，全 app 只在需要你动手时出现）**。

**接受的代价**：同屏信息量比原方案少约三分之一 → 所以**会话看板保留紧凑列表视图**，
悬浮排列是另一种视图而非替代。

改样式请改 `design/*.dc.html` 源文件（`design/apply_m3.py` 是当初批量转换的脚本，留档）。

- ✅ **iOS 版补齐到与安卓同级（2026-08-28）**：用户要「像素级复刻安卓版」。
  这一轮补上**文件浏览**（面包屑/跳转/多格式查看/保存到手机）、**实验室预览**
  （html·会动的 GIF·图片，保存原文件走 SFTP 而不是存预览那份，左滑置顶/删除）、
  **配置页可编辑**（json 先校验 + 改前备份两道保险）、**盯屏推流**（轮询兜底）、
  **TodoWrite 卡 + 顶栏「正在做」**、**本对话的模型/强度/模式**、**看 git diff**。
  逻辑层 **158 个测试全过**；界面在 CI 的 iPhone 16 模拟器上编译+截图。
  剩下没做的只有「分享到会话」（要单开 Share Extension）和小组件。
  ⚠️ 途中抓到两个隐蔽 bug，都记进 TROUBLESHOOTING：清 ANSI 的正则写成
  raw string 会被 ICU 吃掉整个模型名；`CODE_SIGNING_ALLOWED=NO` 顺带废掉钥匙串。

## iOS 版（`ios/`）—— 状态与硬约束

> ⚠️ **2026-09-02 起冻结（D24）**：只更新安卓。下面是冻结时的状态，仅供有人要重新捡起来时参考。

**目录**：`ios/`，与 `android/` 完全分开，互不引用。SwiftPM 双 target：
- `YxiKit` —— 纯逻辑 + SSH（Citadel / swift-nio-ssh）。**不依赖 UIKit/SwiftUI，Linux 上能编能测**
- `Yxi` —— SwiftUI app，**只有 Xcode 能编**

**验证边界**（务必分清，这是这份交付最要紧的一件事）：

| | 状态 |
|---|---|
| `YxiKit`（SSH / 密钥 / 主机存储 / 转录解析 / 屏幕解析 / 配置 / 实验室） | ✅ **真编译真测试**，158 条全绿（Linux 上跑） |
| `Yxi`（SwiftUI 全部界面） | ✅ **在 GitHub 的 macOS runner 上真编译、装进 iPhone 16 模拟器、逐页截图** |
| 本轮（0.9.64）补的界面：缩略图 / 光晕 / 滑入 / 按住说话 / 新图标 / 浅色默认 | ✅ **在用户的 Mac 上 `xcodebuild` 真编译过**（#196 的流程）；❌ 没进过模拟器、没截过图 |
| 真机 SSH / SFTP / tmux | ✅ **CI 里连的是真 sshd**（见下）——不是假数据 |
| 分发（装到用户手机上） | ❌ 仍然需要开发者账号或每 7 天重签，见下面三条硬约束 |
| 推送 | ❌ 未做 |

**iOS 这轮补齐了什么（2026-09-02，0.9.64 / build 108）**：附件缩略图（`AttachThumb.swift`，
`YxiKit.Attachments.parseRefs` 有测试）；对话页背景光 `ThinkingGlow.swift`（参数同安卓）；新条目滑入
（`.transition(.rise)`）；按住说话 `MicHold.swift`（系统 `SFSpeechRecognizer` 离线识别，**不扛安卓那个
150MB 模型**，只填输入框不发送；Info.plist 加了麦克风/语音两条）；App 图标（`App/Assets.xcassets`，
单张 1024）；配色改成浅/深两套动态色、**默认浅色**（设置页「外观」切，终端永远深底）。
⚠️ Linux 上的 xcodegen 现在会崩（#196），**工程在 Mac 上生成**。

在 Linux 上验逻辑层（秒级，日常就用它）：
```bash
export PATH=/opt/swift/usr/bin:$PATH
cd ios && swift build --target YxiKit && swift test
```

**界面怎么验（没有 Mac 也能验）**——`.github/workflows/ios.yml`：
macOS runner 编译 → 起 iPhone 16 模拟器 → 装上去 → **在 runner 上现起一台
真 sshd + tmux + 样本文件**，App 用**自己的密钥**连 `127.0.0.1`
（模拟器与 Mac 共用网络栈，等同安卓模拟器的 `10.0.2.2`）→ 逐页截图传回 artifact。

⚠️ 两个绊过的坑，改 CI 前先看：
- `CODE_SIGNING_ALLOWED=NO` 会把 entitlement 一起关掉 → 钥匙串全线 `-34018`
  → `Vault`/`KeyManager` 失效 → 认证层整个废掉。模拟器要 **ad-hoc 签名**
  （`CODE_SIGN_IDENTITY=-`）。TROUBLESHOOTING #135
- SwiftUI 那半**在 Linux 上编不了**，所以它的编译错误只有推上去才知道，
  一轮约 6 分钟。多攒几处改动再推，比一处一推划算。

**三条硬约束（`ios/docs/` 里有完整论证和出处）**：

1. **必须借一台 Mac** 才能编出 App。这是唯一的硬门槛，绕不过去。
2. **分发**：免费 Apple ID 签名 **7 天失效**（要连 Mac 重刷）；¥688/年 的账号一年刷一次。
   安卓那套「服务器放 APK 点一下就装」**没有等价物**；App 也**不能自己装更新**。
3. **手机主动响能做，而且零成本** —— 自建 `bark-server`（MIT）→ Apple APNs → App Store 的
   Bark App。**已复核**：`apns/apns_certs.go` 内嵌 Bark 自己的 APNs 私钥，所以不需要开发者账号。
   两个代价：① 锁屏上是 **Bark 的图标和名字**，跟你别的告警混在一栏；
   ② 那把私钥**全世界自建用户共用一把**，Apple 一旦吊销则所有自建 server 同时哑掉。
   免费路线下**通知上不能直接批准**（Bark 没有自定义按钮），要「点通知 → 开 Yxi → 按」。

⚠️ **服务器侧零改动**：`server/yxi-hook` / `install.sh` 一个字没动，安卓版不受影响。
推送是一个**可选的独立脚本**，装不装都行。

## ⚠️ 定位前提已经变了（2026-08-29 调研）

**官方自己做了「手机上管 Claude Code」。** Claude Code 2.1.251 内置
`claude remote-control`，帮助第一行原话：

> Remote Control - Control local sessions from **claude.ai/code or the Claude mobile app**

它在几块上**比 Yxi 强**，而且追不上（三周内 20+ 条相关更新）：
原生对话/工具卡片（不用解析 TUI）、远程批权限**无限期挂起 + 断线补发**
（我们架在 hook 的 600 秒上，是硬上限）、跨机器统一会话列表。

**但它硬编码排除了一批人**，这是 Yxi 结构性的市场（二进制原话，已亲自核实）：

- 「Remote Control is only available with **claude.ai subscriptions**.」
- 「connected through an **enterprise cloud gateway** … does not support Remote Control」
- 「(`_CLAUDE_CODE_ASSUME_FIRST_PARTY_BASE_URL` does not apply to Remote Control.)」
  ← **连绕过的后门都专门堵了**，说明是主动排除不是遗漏

即：**用第三方中转 key / Bedrock / Vertex / 企业网关的人，官方那套永远用不了。**
（v2.1.196 起的策略。本项目的用户自己就在这个人群里：站长机走第三方中转、
手机是 GMS 默认关闭的荣耀。）

**官方还明确不做的**：真终端 / 任意 tmux 全景（它只给「一个 Claude Code 会话的窗口」，
没有 shell）、多 agent、不经过 Anthropic 服务器（RC 期间 transcript 全量存它那儿）。
另：官方文档写死 **one remote session per interactive process** —— 「一屏看清所有机器
所有会话谁在等你」它结构上给不了。

### 由此得出的方向（尚待用户拍板）

1. **定位从「Claude Code 手机客户端」改成「SSH 终端 + 多 agent 指挥台」** ——
   终端那块（G2/G8/G9）已经做完，且是官方明确不做的，是唯一不会被抹掉的沉没投入。
2. **目标用户 = 官方明文排除的那批**：中转 key / Bedrock / 内网 / 受限地区 / 非 GMS。
   对他们 Yxi 不是「更好」，是**唯一选项**。
3. **别再往正面重合处投工时**：对话渲染精细度、工具卡片完整度、权限转发可靠性 ——
   保持够用即可。尤其那条 600 秒的远程审批，应从「卖点」降级成「够用的兜底」。

⚠️ **未验证、且只有用户能验的**：他的荣耀手机（非 GMS + 大陆 IP）到底能不能用官方那套。
这一次实测能一次性定下方向 —— 过不去，则第 2 条从「一个细分市场」升级成「Yxi 存在的
全部理由」。

## 决策记录（用户拍板过的，按时间倒序 —— 改动前先看这里，别推翻已定的）

| # | 决定 | 理由 / 出处 |
|---|---|---|
| D29 | **实验室加三个「让 agent 画图」的入口（架构调整，D27 之内的例外）：页顶「查明并画出来」（architecture-verifier）「把结构画成图」（diagram-generator），每张卡「更新」；点了把一整段任务说明发进会话（可附提示词，不附 = 默认执行），agent 在服务器上画、`yxi-lab add / update` 推回来。App 仍不带任何技能、渲染器或内容；全屏可捏合缩放拖动** | 用户 2026-09-02：「实验室里预制 architecture-verifier / diagram-generator … 点击直接驱动 AI 把该会话变成可视化架构图 … 做一个按键方便按项目更新来更新图表，也可以输入部分提示词，不输入就默认执行 … 全屏模式下支持滑动预览」。技能本体在服务器（Archify 需要 Node；MIT），`yxi-lab update <id>` 原位替换。 |
| D28 | **Codex 是二等公民：会话按前缀分开（`cc-` Claude / `cx-` Codex），Codex 只有终端 + 登录，不做对话视图、状态源和通知；新机器从手机一键装机（`bootstrap.sh` 公网分发，不依赖 Node.js）** | 用户 2026-09-02 站在新客户视角问：「没装 claude code 也没装 codex 会显示什么 / 有没有一键装机（客户连 nodejs 都没有怎么办）/ 会话要不要按 codex 和 claude 分开 / 支持两家认证」。Codex 的转录格式、钩子跟 Claude Code 都不通用，先把「能装、能开、能看、能登录」做扎实；对话视图 / 通知等用户要了再做。 |
| D27 | **实验室 App 端冻结：不允许直接修改，只提供 `yxi-lab` 接口（`spec` / `template` / `check` / `add --aspect`）。以后 AI 生成新内容只推、不改 App；除非实验室架构本身要调整，且那要用户先拍板** | 用户 2026-09-02：「实验室需要规范，模板和 UI/UX 做好，给出 API 接口方便后面的 AI 生成上传展示，不要每次改一遍实验室」。契约在 `server/LAB.md`（= `yxi-lab spec`）。 |
| D26 | **实验室跟着服务器走：每台服务器各自一个实验室，互相隔离；实验室里的内容永远不嵌进 App** | 用户 2026-09-02 拍板。内容在各台的 `~/.yxi/lab/`，置顶/采纳按主机分开存；App 只放定稿（D25 的延伸）。 |
| D25 | **给用户审的东西一律走实验室推送（`yxi-lab add`），不写进 App** | 用户 2026-09-02 重申（开屏动效那次我写进 App 被打回，#204）。审的东西不发版、可随时推撤；App 只放定稿。 |
| D24 | **iOS 冻结：以后只更新安卓，不再给 iOS 补功能；iOS 没改动就不动它的版本号** | 用户 2026-09-02 拍板（「以后只更新安卓的吧，别更新苹果的了，苹果的版本号也别变了如果没更新的话」）。iOS 停在 0.9.66 / build 110，代码留着、CI 留着，**新功能不再做 iOS 版**，`ios/project.yml` 的版本号也不跟着安卓走。 |
| D23 | **设置页要显示版本号并能主动查更新** | 用户补充。现在的更新检查是**进会话看板时被动跑一次**，没有主动入口，也看不到自己装的是哪一版。<br>⚠️ 三种结果必须分清：有新版本 / 已是最新 / **连不上没查到** —— 把「没查到」显示成「已是最新」是在骗用户。<br>版本号从 `BuildConfig` 读，不写死。 |
| D22 | **底部导航只在「外层」出现：会话 · 主机 · 设置** | 用户拍板（方案 A / 三格）。<br>**进工作区就整屏让位** —— 终端最缺竖向空间，而软键盘弹起时底部栏会和键盘工具条、系统手势条挤成四层。<br>模式切换 `[终端│对话│文件]` **留在顶部不动**：它是「看哪一面」，跟底部栏的「在 app 的哪儿」是两条轴，放一起会打架。<br>「会话」页带主机下拉 → 换主机不用退出去。 |
| D21 | **补上 D18/D17：悬浮排列会话切换**（先做这个，再做底部栏） | 用户拍板。**换会话是最高频也最费劲的动作**（返回→看板→在 20 个里滚→点），比加导航栏收益大。<br>而且它是早就批过的稿子，不用重新决策。<br>⚠️ 连一并要改：`Workspace` 目前按 (host, session) 记连接，改成**按 host 记** —— 同一台机器上换会话就不用重连。 |
| D20 | **SSH 密钥用 ed25519，靠 BouncyCastle 支撑** | Android 的 JCA 没有 `Ed25519` 签名算法，jsch 认证必失败。<br>试过 `net.i2p.crypto:eddsa`（算法名对不上，无效）和退回 ECDSA（可行但没必要）。<br>**注册 BouncyCastle 即解决**，代价 APK +3 MB。TROUBLESHOOTING #12 |
| D19 | **用量显示，按服务器关联** | 数据源 = `ccusage`（`remote-dev-station/bin/cc-quota` 已在用）读本机 `~/.claude`，**天然按服务器分，零关联工作**。转录里每条 assistant 消息自带完整 `usage` + `model` → **本地算钱，不调 API**。<br>两层：主机列表紧凑条 / 会话看板详情卡。中转站余额记 P2（token 留服务器，不进 App）。PRD 附录 K |
| D18 | **悬浮排列的会话切换** | 像手机后台：卡片轮播 + `capture-pane` 实时缩略预览。与列表视图并存（`[列表│悬浮]`）。<br>⚠️ **上滑 = 归档，不杀 tmux 会话**（不可逆操作绝不能是滑动手势；杀会话要长按+二次确认）。PRD 附录 J.3 |
| D17 | **滑动切卡的视差过渡** | 三层不同速度：焦点卡 1.0x / 邻居 0.86x+缩放+压暗 / 背景 0.3x。`ViewPager2` + 自定义 `PageTransformer`，无额外依赖。<br>⚠️ **必须尊重系统「移除动画」设置**——对前庭障碍用户视差会引发不适。PRD 附录 J.2 |
| D16b | **视觉方向定为 Material 3 深色** | 用户说想学 参考款。参考款 好看是因为它是 M3 Expressive 的样板实现，而 M3 是 Google 公开给第三方用的设计系统 → 学 M3 正当，**未克隆 参考款 界面**。PRD 附录 J.1 |
| D16 | **D-Pad 方向键盘** | 圆形四向 + 中央 Enter，两个上角可配置槽位（抄 Moshi 逆向所得）。我们加：对话模式也能唤出、长按连发、按住拖动持续导航。<br>⭐ **顺手兜底 Phase 3 的 AskUserQuestion 选项风险**——TUI 菜单本来就是 ↑↓+Enter。PRD 附录 I |
| D15 | **推翻服务器侧的过度设计**（用户质疑「为什么要 agent」） | **唯一必须装的是 `yxi-hook`**（Claude Code 只调 settings.json 里的 hook，SSH 替代不了）。<br>`yxi-inbox` 守护删掉 → 换成追加写的 `~/.yxi/events.jsonl` + `tail -f`。<br>`yxi-agent` 降级成**可选脚本**，只为省往返，不是能不能用的前提。<br>服务器侧 ~340 行 + systemd + unix socket → **~140 行 + 两个文件路径**。PRD 附录 H |
| D14 | **文件浏览与阅读**，作为第三个模式 | `[终端│对话│文件]`。**走 SFTP → 不需要 `yxi-agent`**，任何 SSH 主机可用。md 支持「渲染 ⇄ 源码」切换（用户说的「人类易读模式」）、图片、JSON 折叠树、代码高亮。**P0 只读**。<br>渲染库 Markwon，**与对话模式共用一套**。PRD 附录 G |
| D13 | **附件/图片暂存区** | 传到 `/root/src/tmp/<项目>/`（项目 = tmux 会话名去 `cc-` 前缀，如 `cc-Yxi`→`tmp/Yxi`）。每条消息内编号「图片1/附件1」方便引用，**3 天自动清理**。PRD 附录 F |
| D12 | **语音输入提为 P0** | 系统 `SpeechRecognizer` 为主 + 输入法兜底。⚠️ **命令行模式下自动发送必须禁用**——识别错会直接在服务器上执行。PRD 附录 E |
| D11 | **双模式切换**：命令行模式 / 对话渲染模式，都是一等公民 | 顶部分段控件。切换不断连、记住每会话偏好、没 `yxi-agent` 时置灰并说明。PRD 附录 D.5 |
| D10 | **Chat View 提为 P0 主界面** | 用户要求「像原生 Claude App 一样，不是裸终端」。数据源 = 转录 jsonl（不刮屏）。**改变阶段顺序**：终端打磨往后放。PRD 附录 D |
| D9 | **灵动胶囊先不做**，但**不是做不了** | 荣耀确实开放第三方接入（小鹏 App 已接），但走开发者平台合作，已接入的都是大厂。主功能跑通后可再试 |
| D8 | **推送用前台服务，不用 FCM** | FCM 会强制我们长期跑中转服务器；且用户主力机 **荣耀 Magic7** 的 GMS 默认关闭、需国际网络才可用 → FCM 不可靠。PRD §2.7 |
| D7 | **只做 Android，iOS 第一期不做** | 苹果不允许从 GitHub Release 安装，与 D6 本质冲突。PRD §2.5 |
| D6 | **分发走 GitHub Releases**，不上应用商店 | 省掉审核 / 隐私政策 / 合规追赶 |
| D5 | **面向全球用户，但不跑任何后端** | 每个用户连自己的服务器。需补界面多语言（中/英）。PRD §2.6 |
| D4 | **SSH 客户端不砍**，多主机提到 P0 | 要能连 `station`/`inst2`/… **以及以后才有的新服务器**。<br>⚠️ 早期一度错写成「App 内不实现 SSH」，**已纠正，别再退回**。PRD §2.4 |
| D3 | **传输走 SSH**，不要 CA 证书 / mTLS / 新监听端口 | SSH 自带双向认证。PRD §2.3 |
| D2 | **客户端做 Android 原生 APK**，不做 PWA | 浏览器强制 CA 证书；原生走 SSH 就没这限制。PRD §2.1 |
| D1 | **不并行用原版 Moshi**（安卓侧） | 避免两套 hook 抢 `PermissionRequest` |

> **纪律**：用户在对话里拍的每个板，**当场追加到这张表**，再去改 PRD/PLAN 对应章节。
> 只改章节不记这里 → 三个月后没人知道「为什么当初这么定」。

## 用户环境（影响技术选型）

- **手机：荣耀 Magic7（MagicOS）** —— 项目的目标设备和主测试机
  - GMS 默认关闭、需手动开且要国际网络 → **FCM 不可靠**（D8 的直接依据）
  - MagicOS 后台管控严（自启动 / 关联启动 / 后台活动都要手动放行）
    → **前台服务保活的最严苛测试场**。Phase 4 就在这台机上验，别用宽松环境自欺
  - 有**灵动胶囊**（D9）

## 开源参照（PRD 附录 B 有全表）
> **已实读源码**（第一版只是书签清单）。完整结论见 PRD 附录 B，两条更正：
- ⭐ **`termux/terminal-view` + `terminal-emulator` 是 Apache-2.0，不是 GPL-3.0**（`LICENSE.md` 里有豁免，继承自 `jackpal/Android-Terminal-Emulator`）→ **许可顾虑消失**。而且带 `maven-publish`，是按可发布库模块组织的
- ⭐ **ConnectBot 不「更老」**，是 Kotlin + Compose + DI（`compose.bom` / `material3` / `navigation.compose`，`data/ di/ service/ transport/ ui/`）
- **Phase 1 照着 ConnectBot 的 `transport/` 写**：`AbsTransport.kt` / `TransportFactory.kt` / **`SSH.kt` 60 KB**（Apache-2.0 可直接抄）。它还有 `JumpHostProxyData.kt` **跳板机**支持 → 我们记为 P1
- **SSH 库仍选 `mwiede/jsch`**，理由变硬：`org.connectbot:sshlib` 搜不到 `SFTPv3Client`，而我们的文件模式刚需 SFTP（Phase 1.1 实测确认）
- ⭐ **`tuchg/Lucarne` 的 `agent-sessions` crate 比我们的 Chat View 设计更周到**（支持 claude/codex/copilot/cursor/参考款/grok/pi 七家）：原始层与语义层严格分开、`Unknown` 有升级纪律、shell 语义在解析层就抽出来。**三条都该抄**，见 PRD 附录 B.4

## GitHub 耦合
- 仓库：**`liang-senbei/yxi`（私有）**。⚠️ `/root/src/CLAUDE.md`（含明文密码，权限 600）**在父目录、不在本仓**，不会被提交。
- **深耦合 `remote-dev-station`**（`/root/src/workspace/remote-dev-station`）：复用它的 `bin/cc-state`、`hub/`、`bin/cloud-sesslist`、`bin/cc-quota`；它的 `phone/README.md` 是**原版 Moshi** 的配置 runbook（本项目是它的替代品，不是补充）。
- 端口需避开 `Anthropic-Inspector`（80/443/7800）。

## 已知的机器级敞口（与本项目无关，但更要紧）

- **2026-09-03 整机冻住 50 分钟**：宿主机抢 CPU（steal 42% + iowait 20%，我们自己 user 1%）→ 内核 soft lockup → 03:50 起任何端口都连不上 → 04:39 硬重启（新内核 6.8.0-138 顺带生效）。判法 `sar -u -s 03:20:00 -e 04:40:00`、`journalctl -b -1 -p err`。**该催服务商迁实例**，本项目无能为力。另：`yxi-review.service`（批注页，8899）从 8/23 07:29 起因端口被别的进程占着，每 3 秒崩溃重启一次、共 27.7 万次、刷了 2.1GB journal，重启后正常。已加 drop-in `/etc/systemd/system/yxi-review.service.d/limit.conf`（60 秒内失败 5 次就停），journal 清到 300M。
  另：VNC 桌面（remote-dev-station 的 `cloud-vnc.service`，:5901 只监听本机，自带 Chrome 空跑占约四分之一核）**2026-09-03 已按用户要求 `systemctl disable --now` 关掉**；要用回：`systemctl enable --now cloud-vnc`。Yxi 里的登录（GitHub / MCP / Claude / Codex）都不靠它。
本机 sshd 同时开着 root 登录和密码认证，公网 22 端口每天被僵尸网络爆破数千次
（`journalctl` / `auth.log` 里可查，前十来源合计三千余次失败）。
用户手机端已在用**公钥**认证 → **关掉密码认证不影响使用**。已告知用户，由其决定。
> 具体主机地址见 `/root/src/CLAUDE.md`（不入库）。
