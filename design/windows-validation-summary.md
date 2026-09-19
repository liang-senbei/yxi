# Windows 工作台验证补充 · cc-yxi_entertainment（Linux E2E 部分）

配套 `windows-manual-verification.md`：该清单面向 Windows 真机手动验收；本文件记录在 Linux（Xvfb）上已可自动化验证的部分及其结论，真机项仍以清单为准。
完整报告与截图证据：`/root/src/workspace/yunxi/windows-test-reports/cc-yxi_entertainment.md`。

## 测试对象与环境

- 第一轮基线 `be45eb32cfaab4ebd8412bc4f7d82ddad71e84c1`（2026-09-15）；第二轮基线 **`d7e15f698f28253e537ac78d6e2b0a3ddcda0e7a`**（2026-09-19，分支 `codex/windows-workbench-review`）。独立 worktree 测试，未动主仓工作树。
- Compose Desktop uber jar（v1.2.0），JDK 21；Xvfb 隔离显示 + xfwm4 + xdotool 驱动真实 UI 点击/键盘。
- 远端侧 fixture：临时 HOME + 随机端口 sshd + 临时密钥 + fixture 自有 tmux 套接字（显式 `-S` 绝对路径创建/清理，全程零默认套接字操作）。
- E2E 脚本：远端 Agent 分支 `codex/windows-fix-cc-yxi_entertainment`（a9b272f）的 `dev-ent/`，测试脚本尚未合入中枢分支（10 个脚本：doc-e2e-mainpath / doc-e2e-narrow[2] / collab-e2e / git-e2e[2|3] / welcome-e2e[2|3]）。

## 已验证（Linux 可覆盖部分）

| 清单对应轮次 | 验证点 | 结论 |
| --- | --- | --- |
| 右侧文件与 Markdown（59 行起）、文档查找替换（第七十二轮） | 打开远端 Markdown 预览、远端更新自动刷新、桌面全选改写 + Ctrl+S 原子落盘（远端内容逐字节断言）、并发冲突条幅 + 本地/远端分栏比对、选中引用到对话 | ✅ E1 PASS |
| UI 与 Windows 体验（窄窗口部分） | 820px compact 档 DocumentPane 全宽覆盖对话区（`App.kt` compact 设计一致）、恢复宽度后分栏与会话状态保持 | ✅ E2 PASS |
| 桌面协作组～协作投递控制（第八十二～九十五轮） | 顶栏协作组入口、组 chip 切换、投递控制页、暂停/恢复组消息（fake yxi-hub 调用日志断言 `pause`/`resume` 落地） | ✅ E3 PASS |
| 桌面协作预算（第九十六、九十七轮） | **D1 修复回归**：有 `deadline` → 「截止 2030-12-30 22:26」本地时区格式；无 `deadline` 字段 → 不显示截止。基线 jar（be45eb3）复现缺陷「截止 1970-01-01T00:00:00Z」及 UTC 串不本地化 | ✅ 修复有效，缺陷画面级坐实 |
| Git 改动（进程文档标注未运行验证项） | 改动页文件列表与 porcelain 状态码、已暂存/未暂存 diff 渲染与增删着色、untracked 提示文案 | ✅ E4 功能渲染 PASS；发现 N3（见下） |

## 第二轮（d7e15f6，2026-09-19）

| 清单对应轮次 | 验证点 | 结论 |
| --- | --- | --- |
| Git 改动（N3 修复 `d7e15f6`） | 四态 fixture（staged-only / worktree-only / MM / untracked）：双 tab 列表按 porcelain X/Y 列过滤；切 tab 清选中（「打开文件」行与 diff 消失）；SSH 还原文件后点刷新，旧文件从列表消失且旧 diff 不残留；MM 两半 diff 各自正确（index vs HEAD / worktree vs index） | ✅ E5 PASS |
| 工作台首页（第一百一十七轮 `cd9ae59`） | 未连接：居中欢迎页 + 连接提示 + 新建任务/切换任务置灰；连接后：主机名标题 + 「继续工作」列表（任务名 + 主机·目录 + 状态），点击行直达任务 | ✅ E6 PASS |
| 任务视图上下文（第一百一十六轮 `b6fbf1b`） | 任务 A 设「文件」tab + 打开文档侧栏 → 切任务 B 默认对话视图且无侧栏泄漏 → 切回 A 主视图与文档侧栏同时还原 | ✅ E6 PASS |
| B15 修复核对（`e57c7d9`） | 地址栏 Enter 加 `composition == null` 守卫，与建议一致（代码级） | ✅ 静态核对；真机 IME 未测 |

## 发现的缺陷

- **D1（已由中枢整合 `955d13f`，渲染级回归通过，未重复提交）**：投递控制预算无 `deadline` 字段时显示 1970；有 `deadline` 时显示 UTC ISO 串不按本地时区。修复后两种输入均正确。
- **N3（GitChangesPane；已由中枢在 `d7e15f6` 修复，E5 四态回归通过）**：首轮发现文件列表不按 tab 过滤——python 端无条件返回全部 porcelain 条目，`staged` 只影响 diff 命令。首轮证据 `/tmp/yxi-git2-ent.CNcL1Y/02、03`。
- **B15（已由中枢在 `e57c7d9` 修复）/ B16（仅报告）**：地址栏 IME 守卫已修；DocumentPane 行号栏 O(n) 重算仍未修（性能，低）。

## 明确未测（需 Windows 真机或外部资源）

- 真机 DPI 100/125/150/200% 及 `narrow<700dp` 分支可达性（Linux density=1 下最小窗宽 720dp，该分支不可达，实为 DPI 缩放场景）。
- 中文 IME 全链路（含 B15 修复后地址栏 Enter 的实际行为）、JCEF Windows 原生库、触摸/HiDPI 渲染。
- 语音录音→识别链路（无麦克风与 yxi-asr 服务；仅静态审查通过）。
- BrowserPane 网页交互 E2E（查找/视口/主题/自动刷新；两轮时间盒未执行，静态审查 S9 通过）。
- 任务视图 `previewExpanded` 展开状态维度（需网页预览展开场景；主视图 + 文件侧栏两维度已验证）。

## Codex批次编译（2026-09-19）

构建Agent cc-yxi报告功能冻结头4281b22的Windows目标`:desktop:compileKotlin`为BUILD SUCCESSFUL（EXIT=0，0错误）。范围仅为编译，未生成新版Windows安装包，不代表协议、UI或真实模型全流程通过。pilot协议模拟和logto恢复路径检查仍在进行。

交付使用说明见 [下一版试用说明](windows-next-trial-guide.md)，当前已交付试用包仍为3152af0。

## Codex恢复专项（2026-09-19）

cc-logto_yxi提交586fe48：CodexWorkspaceRecoverTest 4通过/0失败，本地进程模拟运行器，无真实模型/网络/登录。覆盖成功恢复并登记（无thread/start或turn/start）、同主机重复登记、无效输入ID拒绝、同ID跨主机不复用。依赖pilot测试适配155f926；本次仅审阅报告与适配差异，待与pilot通用测试一并合入。服务器返回错误ID/cwd、Windows恢复UI不在通过范围。

pilot通用协议首轮30项有7项失败，仍在区分测试预期与实际缺陷，不能据恢复专项推断整体通过。候选accfaf5构建成功，传输仍在进行。

## Codex协议最终报告（2026-09-19，候选基线accfaf5）

- pilot在accfaf5+测试适配上完成30/30：客户端6、控制器13、工作区5、队列6。合入971b62e至7bd7a6c，默认生产连接行为未变。缺少本地Python时模拟进程测试按项目惯例跳过，纯状态测试仍执行。
- 首轮历史重复来自错误夹具：同一历史item被放入两个轮次。Agent恢复旧数据可稳定复现、改为每轮独立items即通过，生产controller未修改；此前失败不能当作产品消息重复的已证缺陷。
- 范围是离线模拟协议，未覆盖真实服务器登录/模型请求、读响应在途时的并发分支、Windows网页/麦克风/输入法；30项通过不等于整个自动队列或PRD已验收。

## 后续界面与测试合入编译（2026-09-19）

cc-yxi在96f4c3d集中执行compileKotlin与compileTestKotlin，BUILD SUCCESSFUL（EXIT=0、0错误）。覆盖候选包后侧栏调宽、Git改动入口与合入的六项测试提交；未执行测试或重新打包。9972b65待处理筛选晚于此基线，不在本条证据范围。

UI Agent已报告b769db8上翻暂停在流式输出中保持阅读位置；返回最新等部分操作尚有坐标校准问题，等待当前最后一遍的正式结果，不把未命中点击当作通过。
