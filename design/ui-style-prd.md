# PRD：界面风格切换与「Code 风格」

版本：0.12 · 2026-10-15 · M2 收尾：外壳、会话列表、本机和服务器的 Code 对话页都已落地（Windows 1.4.16 之后的 36c2d92a 起，本机对齐 P1–P6b 和 M2 a–e 见交接目录），M2-f 把有意偏差、文档更正和技术发现收进新加的 §14；§8 的 M2、Mac1 两行按现状改。v0.11 2026-09-29 · Mac1 第一轮合进了 M1 的工作区：打包（ad-hoc 签名的 dmg）、钥匙串、登录 shell 的 PATH、macOS CI 任务，在 Mac mini 真机上验过；合并后 Windows 的门禁、M1 单测、M0 比对和切换用例又重跑了一遍，都过了（§5.7、§8）。原生窗口 / 菜单栏 / Dock、⌘ 快捷键、签名公证还没做，也都没提交。v0.10 M1 加了应用级的切换用例 `StyleSwitchStateTest`：轮切 20 次，草稿、界面状态、连接对象都在（§4.3 ②）；夹具补丁之后又重跑了门禁和 M1 单测，都过了。M1 还没提交。v0.9 M0 的离屏截图夹具做好了，经典基线已录（12 张）；在 M1 的代码上比对，12 张全部 0 差异（§4.3、§8）。v0.8 M1 的代码写完，§4.2 改成按实际实现写，§3 补上 M1 之后的变化，§12-1 已修。v0.7 用户定了 §10-12（Mac 只出 Apple 芯片 arm64）和 §10-14 的测包机（经 hk13 反向隧道连的 Mac mini），M1 开工；顺带更正 §3 的写盘失败描述和 §4.2 的引用数。v0.6 用户拍了第五轮（5 张），素材共五轮（可用 50 张）和一段录屏，量进 [规格表](ui-style-spec.md) v4：补了对话视图子菜单、带工具调用的三档对比、工具摘要行和工具组卡片（命令框、输出、文件内容框），更正了子菜单间隔、发送图标灰度、输入卡文字起点和代码块圆角。第四轮（v0.5）补了思考内容、排队、分支下拉与 worktree、会话行 hover 与 tooltip、消息间的纵向节奏，细线规则推广到任意缩放（§11）。同日用户要求也做 Mac 版：新增 G6 和 §5.7，§3、§6～§11 补了 Mac 的现状和计划。用户找到的几个 Claude Code 源码 / 重写 / 分析仓库，处理办法见 §2-6、§13。基准环境仍是系统缩放 125%、窗口最大化（§7）。Windows 浅色的必拍项齐了；还缺：深色（只有 1 张，其余按结构推算，第五轮用户先跳过）、Mac 素材全缺（§9）。「系统」字体、DevTools 精确值（要重启 Claude Desktop，时间由用户定）是可选项。

输入：用户本轮要求——Windows 版本地与远程服务器开发，UI/UX 与功能对齐 Claude Desktop 的 Code 模式（像素级）；先加主题切换，新样式算一个新主题，旧的保留；先出 PRD。2026-09-28 追加：Mac 版也要做（G6）。本 PRD 扩展 [Windows 工作台 PRD](windows-workbench-prd.md) 与 [本地工作台 PRD](local-workspace-prd.md)，不缩小二者范围。已有的设计变量见 [桌面版参照](desktop-reference.md)（2026-09-08 拆包，Claude 1.49585.0）。本轮另做了两项只读调研（官方公开文档与更新日志、开源复刻项目），要点已并入正文，来源见 §13。对照基准版本：Claude Desktop 2.9939.2（2026-09-24，与本机安装版一致）。

## 1. 目标与非目标

| 编号 | 目标 | 验收要点 |
|---|---|---|
| G1 | 设置 → 外观新增「界面风格」：经典 / Code 风格，与现有明暗（跟随系统 / 浅 / 深）正交 | 4 种组合即时生效、重启保留；经典逐像素不变（§7） |
| G2 | Code 风格的布局、组件、交互对齐 Claude Desktop 的 Code 标签，手感做到原生级、和官方客户端没有可感知的差距 | 按 §7 的尺寸 / 颜色 / 交互逐项验收，不以"看着像"验收；Yxi 的名称和图标始终可见（§2-3） |
| G3 | Code 风格下，本机任务（Codex / Claude / OpenCode / ACP）与 SSH 远程主机会话用同一套外壳和组件 | 两类会话在同一侧栏、同一对话组件里呈现，差别只在环境标签 |
| G4 | 补齐 Code 模式有、Yxi 缺、用户会实际用到的能力（§6） | 每项有真实数据链路；做不到的不放入口 |
| G5 | 第三方模型配置：Yxi 已有的「线路」（参照 CC Switch）做成 Code 风格的设置页，可选从本机 CC Switch 导入（§5.6） | 本机与 SSH 会话都能切换；密钥不进日志、不进命令行 |
| G6 | macOS 版：同一套代码出 Mac 包，经典和 Code 两种风格都能用；窗口、菜单、快捷键、字体按 Mac 习惯（§5.7）；只出 Apple Silicon（§10-12） | 签名并公证过的 dmg 能在没装过 Yxi 的 Mac 上装、开，SSH 与本机任务的主流程走通；密钥不明文落盘；和 Windows 不同的地方逐项列在规格表 §7，没列的按 Windows 的值验 |

本期不做：
- 手机端（`android/app`）的 Code 风格。
- Anthropic 云端沙箱（Cloud 环境）。Yxi 的"远程"就是用户自己的 SSH 主机。
- 为了像而放空入口（沿用工作台 PRD §5：不为模仿截图添加空的 PR、定时任务、探索入口）。
- Anthropic 的名称、Logo、专有字体、插画、整段原文文案（§2）。
- Mac App Store 上架：沙盒会限制调用用户自己装的 `claude`、`ssh`，所以走 Developer ID 签名 + 公证、官网分发（§5.7）。

## 2. 边界：怎么学才合规

1. **不拆包、不反编译、不读 JS 源码**（Anthropic 条款禁止逆向工程）。可以做的：在用户授权下观察本机正在运行的 Claude Desktop（2.9939.2）；用官方 MCP 调试文档写明的开发者模式（`developer_settings.json` 里设 `allowDevTools: true`）打开 DevTools，只在 Elements / Computed 里读渲染出来的数值（尺寸、颜色、字号、过渡时长）。当前版本还支不支持这个开关，M0 实测。[桌面版参照](desktop-reference.md) 里已有的数值只当起点，以实测为准。
2. **不做脚本化访问**：不批量、不无人值守地自动操作，不抓接口，不注入脚本，不代用户自动发消息（条款禁止用脚本等非人工方式访问）。本机客户端上，我截窗口图，按人的节奏点开本地菜单、下拉、设置页看状态；要发消息才会出现的状态（运行中、审批、排队）由用户手动发。其他参考来源：官方文档与更新日志、官方视频、用户在测试机上操作时的被动截屏。
3. **不带品牌资产**：界面名称不含 "Claude"；不用星芒 Logo、Anthropic Sans / Serif、插画，也不搬官方图标和 SVG（Yxi 继续用 Material Icons）；品牌色位置（如发送按钮）用 Yxi 自己的颜色（§10-2）。颜色、间距、布局这类功能性数值可以对齐。
   - Agent SDK 的品牌规范要求第三方产品"保持自己的品牌，不得让人以为是 Claude Code 或任何 Anthropic 产品"，列出的不允许做法里还有"模仿 Claude Code 的视觉元素"。Yxi 驱动的是用户本机安装的 `claude` CLI（stream-json），没有用 Agent SDK，这条规范是否约束 Yxi 未核实。"像素级复刻"与这条规范之间的边界，见 §10-1。
4. **参考截图不进仓库**（仓库公开）：只放在本机仓库外，用户的素材文件夹我只读、不删；我量的时候生成的裁图放临时目录，量完即删；仓库里只留量出来的数值。
5. **Yxi 的硬规矩优先于像素复刻**（STYLE.md §0、[视觉重排](windows-visual-refinement.md)）：不做循环装饰动效（参考里循环动画的状态图标改成静态"运行中 · 12s"真实计时）；终端永远深底；危险动作多一步确认；失败都要说出来。冲突处按 Yxi 规矩，并在 §5 标出偏差。
6. **不看、不用泄露的 Claude Code 源码**（用户找到的仓库见 §13）：公开能看到不等于有许可，拿来参考有版权风险；而且那是 CLI 的源码，Code 模式的界面在 Desktop 客户端里，不在 CLI 里，对复刻界面没有帮助。Yxi 对接 CLI 只依据官方公开文档和本机实测。clean-room 重写的开源项目（如 claurst，GPL-3.0）只看它写的行为说明，不搬代码：搬了 GPL 代码，Yxi 就得整体按 GPL-3.0 分发。

## 3. 现状（2026-09-27，读 `desktop/codex/windows-workbench` b1f44afa）

- `Theme.kt`：`Tokens` 共 14 个语义色加 `dark`，hover / selected / border 由 textPrimary 的 5% / 10% / 8% 派生；深浅两套值写死（取自手机端 `Palette.kt`）。`YxiTheme` 读 `Store.pref("theme","system")` 映射成 Material3 colorScheme；Typography（正文 14/22 等）和 Shapes（10 / 14 / 18 / 22）写死在函数里。
- `Store.prefs` 是 `mutableStateMapOf`，现有明暗切换已经即时生效，新偏好可以走同一机制。`setPref` 写盘失败时不切换，只把 `Store.warning` 设成「偏好未保存：原因」；这条提示只在侧栏底部和设置 → 常规显示，在外观页改设置时看不到。M1 之后，常规以外的设置页也在标题下方显示它（§4.1）。
- 耦合点：`Radius`（14dp）、`RadiusComposer`（22dp）、`Mono` 是顶层常量，Theme.kt 以外共 20 处引用、分布在 6 个文件；用了 `Mono` 的顶层 `CodeStyle`（Markdown.kt）又在 9 个文件里被引用；`Color(0x…)` 硬编码在 EffortControl（11 行）、Model（2）、TermPane（1）、StyleTrialControls（1）。`Tokens.current`（382 处）和 `MaterialTheme.typography`（669 处）天然随主题走。侧栏宽、行高、间距散在各组件里，没有 token。M1 之后，这三个常量和 `CodeStyle` 改成跟着风格走的 getter；侧栏宽、顶部条高、会话头高进了 `Metrics`（§4.2）。
- 窗口：默认 1200×800（与 Claude 相同），无边框自绘 `TitleBar`，外面包一层 `Zoomed` 缩放；外壳入口是 `App(state)`（`Main.kt:140-145`）。
- 对话面板至少 7 个，各自解析、各自渲染：`ChatPane`（SSH 远程）、`ClaudeConversationPane`、`CodexWorkspacePane`、`AcpConversationPane`、`OpenCodeConversationPane`、`LocalConversationPane`、`LocalWorkspacePane`。
- 设置 → 外观目前只有一行「主题：跟随系统 / 浅色 / 深色」。M1 之后是「界面风格」「明暗」两行（§4.1）。
- 测试：desktop 只有 `kotlin("test")` + JUnit 5；已有 env 门控的真窗口截图夹具（`HostRecoveryUiTest`：`application{}` + `Robot` 截屏）；没引 Compose UI 测试库。M1 加了 4 个单测类（§4.3），其中一个用离屏的 `ImageComposeScene`，没加新依赖。M0 另加了一个 env 门控的离屏截图夹具 `ClassicThemeShotsTest`（§4.3），M1 在同一个夹具上加了应用级的切换用例 `StyleSwitchStateTest`（也是 env 门控）。

- 全局快捷键已有（`Shell.kt:197`，表在 `Shortcuts.kt`）：Ctrl+N 新会话、Ctrl+K 任务切换、Ctrl+B 侧栏、Ctrl+J、Ctrl+Tab / Shift+Tab、Ctrl+Alt+A 跳到待处理、Ctrl+, 设置、Ctrl+/ 快捷键表、Ctrl+= / − / 0 缩放、Ctrl+1…9、F5。
- 用户数据目录（`Store.dir`）：Windows 是 `%USERPROFILE%\.yxi`，其他系统是 `~/.config/yxi`（`Model.kt:64`），没有环境变量覆盖。测试一旦调 `setPref` 就会写用户真实的 `prefs.json`。M1 之后，偏好读写抽成了 `PrefStore`（`Prefs.kt`），单测可以指向临时文件；但构造 `AppState` / `Store` 的测试仍然读写 `Store.dir`，在本机跑时要重定向目录（§4.3）。
- macOS（2026-09-28 补读，同一提交）：
  - 能打 Mac 的 jar（`-Pyxi.os=mac`，带 macos-arm64 的 JCEF 本机库），但没有安装包：打包只配了 Msi / Exe（`build.gradle.kts:99`），没有 dmg、icns、签名、公证；CI 只在 windows-latest 上打包。
  - 已经分平台处理的：数据目录；单实例锁；找本机运行器（PATH 分隔符、`.exe` 后缀）；本机会话文件的标识；打开浏览器（`open`）；一键配置本机 SSH；远端是 Mac 时的命令差异（`Slave.kt`、`TailscaleStatus.kt`）。
  - 在 Mac 上关掉或不出现的：开机自启（走注册表，非 Windows 灰掉）；自动更新（Velopack，找不到 `Update.exe` 就不查）。本机听写只在 Windows 上要求 `.exe`，Mac 上配一个可执行的 whisper-cli 应该就能用，没实测。
  - 要改的：
    - 凭据加密只有 DPAPI（`CredentialFile.kt:25`），其他系统不加密：`hosts.json` 里的 SSH 密码、`auth.json` 里的账号会话在 Mac 上是明文。
    - 窗口是 `Undecorated` 自绘（`Main.kt:105`），Mac 上没有红绿灯，也进不了原生全屏。
    - 全局快捷键只认 Ctrl（`Shell.kt:200`）；只有对话里粘贴图片同时认 ⌘（`ClaudeConversationPane.kt:283`）。
    - 从 Finder 启动的 App 拿不到终端里的 PATH。运行器查找只补了 `~/.local/bin` 和 Windows 的 npm 目录，用 Homebrew、nvm 装的运行器多半找不到，npm 装的还要能找到 `node`（按代码推断，没实测）。
    - 关窗后的提示是按 Windows 托盘写的。

## 4. M1：风格切换（先做）

### 4.1 设置项

设置 → 外观：

| 行 | 选项 | 偏好键 | 默认 |
|---|---|---|---|
| 界面风格（新增，放第一行） | 经典 / Code 风格（预览） | `uiStyle` = `classic` / `code` | `classic` |
| 明暗（原「主题」，只改名） | 跟随系统 / 浅色 / 深色 | `theme`（键名不变） | `system` |

- 选中即生效，下一帧重绘，不重启、不重连。
- 切换不丢任何状态：草稿、输入历史、附件、已连主机、运行中任务、待审批、当前会话、右栏标签都在 `AppState` / 控制器里，不属于主题层。只允许纯视图状态（例如某列表的滚动位置）重置，具体哪些会重置在验收时列清楚。
- 写盘失败：不切换，并用 `Store.warning` 提示"界面风格没存上：原因"。`setPref` 的其他失败也一并改成提示（STYLE §0"一切失败都要说出来"）。
  - 实现（M1）：`PrefStore.set` 先写盘、后改内存，写不进去内存值不动，单选还停在旧值。`Store.setPref` 返回 null 或给人看的原因，原因同时进 `Store.warning`；之后任何一个偏好存上了，只清掉偏好自己留下的那条提示，别处的提示不动。
  - 提示显示在侧栏底部；设置页盖住了侧栏，所以常规以外的设置页在标题下方也显示一次（danger 色）；常规页照旧由用户数据区显示。没有提示时，设置页和原来一样。
  - 明暗行失败时提示"明暗没存上：原因"，其他偏好仍是"偏好未保存：原因"。
  - 输入历史的保留档位没存上时，不按新档清理历史。原来即使写盘失败也照样清理。
  - `prefs.json` 读不出来又没有备份时，报"无法读取桌面偏好"，之后的写入都拒绝，不拿新内容盖掉坏文件（`DurableFile` 原有行为，M1 加了单测）。
- 未知值（手改 prefs.json、从新版降级）按经典处理。
- 不加专用快捷键，Ctrl+, 进设置已经够用。
- M1 阶段的 Code 风格只换 token（颜色、字号、圆角、尺寸），结构不变，所以挂"预览"。结构替换在 M2 / M3 完成。

### 4.2 数据模型

实现（M1，`Theme.kt`）：

```kotlin
enum class UiStyle(val key: String, val label: String) { Classic("classic", "经典"), Code("code", "Code 风格") }  // UiStyle.from：认不出的值按经典
@Immutable data class Metrics(val sidebarWidth: Dp, val titleBarHeight: Dp, val headerHeight: Dp,
    val messageMaxWidth: Dp, val rowHeight: Dp)          // 后两个留给 M2 的 CodeShell，经典填 Dp.Unspecified
@Immutable data class ThemeSpec(val style: UiStyle, val tokens: Tokens, val colors: ColorScheme, val typography: Typography,
    val shapes: Shapes, val radius: Dp, val radiusComposer: Dp, val mono: FontFamily,
    val body: TextStyle, val code: TextStyle, val metrics: Metrics)   // body / code = Markdown.kt 的 BodyStyle / CodeStyle
val LocalThemeSpec = staticCompositionLocalOf { ThemeSpec.classicLight }
val Radius: Dp @Composable @ReadOnlyComposable get() = LocalThemeSpec.current.radius      // 调用点写法不变
```

- `YxiTheme` 按（风格 × 明暗）从四份预先算好的常量里挑出 `ThemeSpec`（`ThemeSpec.of` 只挑不算），同时提供 `LocalTokens`（`Tokens.current` 的调用点不改）和 `MaterialTheme`。
- 另加 `YxiTheme(style, dark)` 显式参数重载。测试和截图夹具只用这个重载，不读写用户的真实偏好。
- 经典风格的值从改动前的 `Theme.kt` / `Markdown.kt` / `App.kt` / `Shell.kt` 原样搬，一个值都不改，这是零回归的前提；`ThemeSpecTest` 把这些值钉死了。
- `Radius` / `RadiusComposer` / `Mono` 改成 `@Composable` getter，顶层 `BodyStyle` / `CodeStyle` 也一样。非 Composable 作用域的引用只有一处：`Markdown.kt` 的 `inlineMd` 里的行内代码，改成直接写 `FontFamily.Monospace`。两种风格的 mono 目前都是它：经典的直接断言，Code 的经 `spec.code` 断言。M2 要是把 Code 的等宽字体换成具体字体（§5.3：Windows 用 Consolas，macOS 用 SF Mono / Menlo），`inlineMd` 就得改成由调用方传字体进来，否则行内代码不会跟着换。ChatPane.kt 里有个同名的局部函数 `Mono(text)`，不受影响。
- `Tokens` 只增不改：原来的 15 个参数，顺序和含义都不变；新加的语义位是带默认值的参数，默认值就是改动前的派生公式或借用的位：
  - hover / selected / border = textPrimary 的 5% / 10% / 8%
  - sidebar / codeBg / composer / menu = surface1，dialog = surface3
  - diffAdd / diffDel = success / danger
  - brand / onBrand = accent / onAccent
  - composerRing = border，composerRingFocused = accent 的 60%
- 经典风格不传这些参数，所以一个像素都不变；Code 风格全部覆盖成实测的不透明色（§5.2）。没有单独加 focusRing：M1 只用到输入卡的聚焦外环（composerRingFocused），其他控件的焦点环等 M2 做控件时再定（规格表 §1.2）。
- M3 的 colorScheme 由 tokens 生成：surfaceContainer = menu、surfaceContainerHigh = dialog。经典对应的还是原来的 surface1 / surface3，菜单和对话框因此能跟着风格走。
- M1 接进 spec 的位置：
  - `Markdown.kt`：正文 / 代码字号，代码块底色
  - `GitChangesPane.kt`：diff 色
  - `ChatPane.kt`：输入卡的底色和外环，发送键的品牌位
  - `App.kt`：侧栏宽、会话头高
  - `Shell.kt`：顶部条高、底色
  - `Sidebar.kt`：侧栏底色
  - 其余的等 M2 / M3 换外壳时再接。
- 硬编码色：状态色、会员色这类固定语义的不动；界面底色 / 文字色改走 token；终端配色不动（永远深底）。
- 结构分叉只在外壳层做一次：`App` 按 `spec.style` 选 `ClassicShell` 或 `CodeShell`（M2 起）。业务组件、控制器、状态两种风格共用，行为不按风格分叉。Code 专用组件放在 `Code*.kt`（包是扁平的，靠前缀区分）。

### 4.3 M1 验收

1. 经典 × 浅 / 深：改动前后在同机、同 DPI、界面缩放 1.0 下截图逐像素一致（设置 → 外观页除外，它多了一行）。夹具按 M0 定的方案；如果沿用 `HostRecoveryUiTest` 的 env 门控真窗口方案，要改成按窗口实际边界截图（现在截的是固定矩形）。场景：空工作台、SSH 会话对话、本地工作台列表、审批卡、设置 → 常规、终端。
2. 四种组合来回切 20 次：草稿、附件、连接、运行中任务都还在，日志无异常。这项测试走显式参数重载或临时偏好，不碰真实 `prefs.json`。
3. 单测：偏好读写与未知值回退、spec 选择、写盘失败会提示。
4. `:desktop:test` 与 `dev/test-workbench.ps1` 门禁通过。新测试类要不要进门禁清单见 §10-4。

M1 在本机（Windows）验到哪一步（2026-09-28）：
- ③ 由 `ThemeSpecTest`（6 个用例）和 `PrefStoreTest`（4 个）覆盖。§12-1 由 `NativeHistoryClickTest`（2 个）覆盖。
- ① 的纯数据部分由 `ThemeSpecTest` 覆盖：经典的配色、字体、形状、尺寸，以及新语义位的默认值，都钉在改动前的值上。
- ① 的截图部分由 M0 的夹具 `ClassicThemeShotsTest` 覆盖，结果：打到 M1 的代码上跑比对，12 张全部 0 差异。
  - 做法：离屏 `ImageComposeScene`，2560×1380、density 1.25（= 125% 下最大化的 2048×1104），CPU 光栅，不开原生窗口，也不抢焦点。
  - 数据：全部合成，不连主机、不扫描、不起进程。测试装了一个 `SecurityManager`，只拦 `ProcessBuilder.start` 和非回环连接；只有常规页允许被拦下的 `reg query`（开机自启按「未开启」画）。
  - 开关：用 env 门控，不设就跳过；也不在门禁清单里（§10-4）。三个 env 的用法见测试类的 KDoc。
  - 基线：在 b1f44afa 上录，放在仓库外的 `E:\work\workspace\yxi\shots\m0-classic\{light,dark}`。
  - 基线自检：在 b1f44afa 上连跑 3 次比对，都是 0 差异；改 1 个像素或删掉 1 张基线，都会报失败。
  - 基线只对这台机器、这套系统字体、这个夹具路径有效（`fixture.txt` 里记了路径和系统）。换机器或换路径要重录。
  - 覆盖不到的：终端字符区（JediTerm 是 Swing 重量级组件，内容又要真的 PTY）、审批卡在对话里的实际位置（只拍了组件级）、SSH 在线态，以及 hover、菜单展开和原生窗口边框。
  - 夹具靠 `SecurityManager` 拦截，工具链升到 JDK 24+ 以后要换方案（JEP 486）。
- ② 分两层验过。
  - 主题层：`ThemeSwitchTest` 用离屏的 `ImageComposeScene`，经显式重载把四种组合轮切 20 次，确认每次都换到对应的 spec，而且 `remember` 的对象、`LaunchedEffect`、`DisposableEffect` 都没有重建。
  - 应用级：`StyleSwitchStateTest` 在 M0 夹具的合成状态上挂整个 `App`（SSH 对话页，输入框里有一句草稿），走真实的 `Store.setPref`，按经典浅 → Code 浅 → 经典深 → Code 深轮切 20 次。每一步都核：`LocalThemeSpec` 跟着偏好变；回到同一组合时和第一次逐像素相同，两种风格画出来不同；语义树全程是同一组 130 个节点 id（id 跟着 LayoutNode 走，子树拆掉重建就会换号）；草稿还是原来那个 `MutableState`，文本没变；连接对象、会话、标签页都没换；没有起进程，也没有外连。运行日志里除了 JDK 对 `SecurityManager` 的弃用警告，没有别的警告或异常。
  - 应用级还走了一遍用户路径：打开设置 → 外观 → 切 Code → 切回经典 → 关设置。画面和节点 id 回到初始；整个设置阶段只拦下一次 `reg query`（自启动开关的初值），说明对话框本身也没重建。
  - 还没验的：附件只靠节点 id 不变来论证（ChatPane 子树原地重组，里面 `remember` 的附件暂存和滚动位置就还在），夹具里没有塞真附件；连接在线、运行中任务要真会话，只能进 hk13 的容器（§8）。
  - 这个用例不需要基线，只和同一次运行里的第一张图比，所以不受换机器、换字体的影响；但它和 `ClassicThemeShotsTest` 共用夹具 profile 和 `Store` 单例，不能在同一个 JVM 里跑，靠 `YXI_THEME_SHOT_MODE=switch` 二选一（§10-4）。
- 夹具补丁和 `StyleSwitchStateTest` 加进来以后又重跑了一遍：M1 的 13 个用例全部通过；门禁 19 个类、111 个用例全部通过，没有跳过。M0 比对（compare）和切换用例（switch）各跑一次，也都通过。
- 完整的 `:desktop:test` 在本机不能直接跑：`AcpConversationUiTest`、`OpenCodeNavigationTest` 等用例要求设了 `YXI_ISOLATED_TEST_RUN`、并且 `user.home` 是 `/sandbox/home`，有的还开真窗口、用 `Robot` 点击。这些只能在 hk13 的 `dev/isolated-tests` 容器里跑（§8），动容器要先问。
- 在本机跑会构造 `AppState` / `Store` 的测试（例如 `NativeHistoryClickTest`）时，要把 `YXI_SUBSCRIPTION_UI_FIXTURE` 设成一个临时目录的绝对路径。`tasks.test` 会把 `user.home`、`HOME`、`USERPROFILE` 指到它下面的 `profile`，`APPDATA` / `LOCALAPPDATA` 指到 `profile\Roaming` / `profile\Local`，这样 `Store` 就不会碰真实的 `~/.yxi`。门禁照项目原样跑，不做这层重定向。

## 5. Code 风格规格

来源标注：**实测** = 2026-09-27～28 从用户在本机分五轮拍的 Code 页截图和录屏量出（2.9939.2，浅色为主、深色 1 张，系统缩放 125%，窗口最大化 2048×1104 CSS，默认字体），单位 CSS px（= dp），明细和量法见 [规格表](ui-style-spec.md)；09-27 在聊天首页取的色已在 Code 页复核。**推算** = 没有素材，按浅色的结构和参照推出（目前只用在深色，规格表 §1.9）。**官方** = 官方公开文档与更新日志（§13）；**参照** = [桌面版参照](desktop-reference.md) 的 CDS 值（1.49585.0）；**待测** = 还没有素材（规格表 §6）。

- 优先级：实测 > 官方 > 参照。例如实测页面底是 #FCFCFB，参照把这个值记在 sidebar（surface-1）。
- 官网营销页和社区主题常用暖米色 #FAF9F5 配 clay #D97757，与实测的 App 中性灰不符，不采用。

### 5.1 布局骨架

```
侧栏 288   │ 顶部条 36（只有窗口按钮）                 ─ □ × │ 右侧面板（可选）：
图标行 36  │ 会话头 48：环境 · 标题 · 标签 … 右侧图标        │ 独立圆角卡片，离窗口
分段控件   │ （无分隔线，对话流在下方 16 内渐隐）            │ 右、下各 8；改动 /
+ 新会话   │                                                 │ 文件 / 预览 / 终端，
分组·筛选  │   对话流 768，主区内水平居中                    │ 可上下分屏；另有
会话列表   │   （滚动条贴主区右缘）                          │ 计划 / 任务（官方）
           │   审批卡（有待处理时，停靠在输入卡上方）        │
───────────│   输入卡 768×44（底部停靠）                     │
账号行 44  │   页脚：权限模式 · + …… 模型 · 强度 · 用量环    │
```

- 实测（2048×1104 窗口）：没有横贯的标题栏，侧栏从窗口顶开始；主区顶部 36 只放窗口按钮，会话头占 36–84。对话列、审批卡、输入卡同宽 768（设置里「对话宽度 = 窄」，这一项同时管对话列和输入卡），在主区里水平居中；停靠区不随对话滚动。主区 = 侧栏右缘到右侧面板左缘；主区不够宽时两侧各留 40，其余给对话列（推测规则是「宽 = min(768, 主区 − 80)」）。坐标见规格表 §2。
- macOS：窗口按钮是左上的红绿灯，顶部条右侧不放按钮；菜单进系统菜单栏；其余布局同上。差异见规格表 §7.1。
- 侧栏顶部的分段控件在参考端用来切换两种工作模式，Yxi 没有对应物。建议 M2 不放（沿用工作台 PRD §5，不为像而放空入口）；以后如果要做"本机 / 远程"筛选再考虑复用这个样式。
- 侧栏：窗口宽度小于 700 时自动收起；Ctrl+B 手动开关（Yxi 已有，`App.kt:66`）。实测收起态：侧栏整个隐藏，侧栏图标行的四个图标（菜单、侧栏开关、后退、前进）原地不动，会话头内容左移到 x 17.6，对话列在整窗居中（规格表 §2）。参考端在多窄时自动收起没有素材，沿用 Yxi 的 700。
- 会话工具栏（官方）：
  - 标题点击改名、分支名点击复制、视图菜单、dev server 下拉、在 IDE 中继续。
  - 开 PR 后出现 CI 状态栏。
- 会话头（实测，规格表 §3.9）：左边环境图标、标题、箭头按钮（点开是标题菜单：打开方式、重命名、分叉、对话视图、输出风格、归档、删除）、标签 chip；右边四个图标按钮：终端、改动、预览、更多（⋮，里面有「文件」）。
- 右侧面板（官方，v1.2581.0 起）：
  - 面板有 diff、浏览器、终端、文件、计划、任务（含子任务和后台 shell）。可拖动换位置、拖边缘改大小、弹出成独立窗口。
  - Yxi 已有 GitChangesPane / BrowserPane / TermPane / FilesPane，缺计划、任务两个面板（M4）。
- 右侧面板（实测，规格表 §3.12）：
  - 不贴边：独立的白底圆角卡片（圆角 10，外环 + 淡阴影），顶 y 44，离窗口右、下各 8；头部高 32，下方没有分隔线。
  - 宽（2048 窗口下）：改动 / 文件 / 预览 ≈1040，终端 ≈578；能不能拖宽没测。同时开两个时上下分屏，相距 12。
  - 从会话头右侧的图标打开，面板开着时对应图标是激活态（浅蓝底）。
  - 参考端的终端面板是白底。Yxi 的终端保持深底（§2-5），这是有意的偏差。
- [桌面版参照](desktop-reference.md) 说"没有右侧 diff 面板"，与官方文档不符。推测 Code 界面是从 claude.ai 远程加载的，拆本地包看不到；以官方为准。
- 环境对应：Local 对应本机，SSH 对应已添加的主机；Cloud、WSL 本期不做。

### 5.2 颜色（初值）

| Token | 浅色 | 深色 | 来源 |
|---|---|---|---|
| 页面底 | #FCFCFB | #151515 | 实测；深色参照记的 #0B0B0B 不对 |
| 侧栏底 | #FBFBF9 | #111111（比主区深） | 实测 |
| 分隔线 | #E2E2E1，1 物理像素细线（规格表 §0.4） | #292929 | 实测 |
| 卡片 / 面板 | #FFFFFF | 浮层、审批卡、右侧面板 #20201F；代码块 #1A1A19 | 浅：实测 / 深：代码块实测，其余推算 |
| 用户消息底 / 凹陷面 | #F0F0EF（= 黑 5% 叠在页面底上，与参照「neutral-900 的 5%」一致）；统计卡、表头、行内代码也用它 | #212121 | 实测 |
| 浅填充 | #F3F3F3（菜单 hover、命令块、设置页的分段控件轨道、设置行分隔）；侧栏的分段控件轨道是 #EDECE8 | ≈#2A2A29；侧栏轨道 #1F1F1F | 浅：实测 / 深：侧栏轨道实测，其余推算 |
| 浮层 | #FFFFFF；外环黑约 10%，阴影 `0 1.5 6 rgba(0,0,0,.067)` + `0 8 24 rgba(0,0,0,.126)` | #20201F | 浅：实测，与参照 `0 8 24 #0000001F` + `0 2 6 #00000014` 基本一致 / 深：参照，等于实测的输入卡填充 |
| 控件边框 / 输入卡外环 | #E6E6E6 / 聚焦 ≈#BCBCBB（黑约 24%）、失焦 #DFDFDE | 控件边框待测；输入卡填充 #20201F、外环 #313131 | 实测 |
| 文字 主 / 次 / 弱 | #0B0B0B / #52514E / #898781 | #F0EFEC / #C3C2B7 / #898781 | 实测，与参照一致 |
| hover / selected | 侧栏行 #F0EFEC / #EDECE8；浮层项 hover #F3F3F3；设置导航选中 #E3E3E2；都是不透明值 | 侧栏行 ≈#222221 / #343434 | 浅：实测（新会话行和会话行的 hover 都是 #F0EFEC）/ 深：选中实测（≈16%，参照 15%），hover 按参照 7.5% 推算 |
| 强调（链接、焦点） | #184F95，填充 #2A78D6；焦点环：#2A78D6 细线，外面一圈淡蓝光晕（规格表 §1.2） | #6DA7EC | 浅：实测，与参照一致 / 深：参照 |
| 主按钮（审批「允许一次」等） | 底 #0B0B0B，字 #FFFFFF；不可用底 #9D9D9D | 待测 | 浅：实测 |
| 热力图 | 空 #D8D8D8；有数据 #86ACEA / #6394E3 / #2566D0 | 待测 | 浅：实测 |
| 危险 / 成功 | #8E2626 / #006300 | #EC7E7E / #0CA30C | 危险：实测（行内代码字、执行失败、菜单「删除」），与参照一致 / 成功：参照 |
| Git 新增 / 删除 / 修改 | #1E9E3C / #CD2054 / #98801F | #32D74B / #FF2C56 / #FFD014 | 参照；浅色的新增 / 删除经设置页 diff 预览、审批卡、改动面板实测确认；修改没见到 |
| 品牌位（发送按钮等） | Yxi 铜色 #E08B57（手机端「等你」小组件用的就是这个铜），图标 #4D2600（对比约 5.0:1；白字只有约 2.6:1，不用）。不用参照里的 clay #D97757 | 同左 | §10-2。参考端思考行和回合结束处的星形图标就是 #D97757（深浅一样），Yxi 换成自己的静态图标（规格表 §5） |
| 状态点 | 沿用 STYLE §1.2：等你 = 琥珀、干活中 = 青、刚跑完 = 铜、闲置 = 灰 | 同左 | Yxi；尺寸和实心 / 空心的画法借参考端（规格表 §3.5） |

- 状态点的含义两种风格一致，只按底色微调明度。
- 实测的分隔线相当于主文字色约 10.5% 叠在侧栏底上（深色 #292929 也是约 11%），所以 Code 风格用不透明值覆盖 `border`，不沿用经典的 8%。浅色的 hover / selected 也比派生值偏暖，同样用不透明值覆盖（§4.2）。
- 深色只有 1 张会话页素材：实测的面比参照浅一档（页面底 #151515，不是 #0B0B0B），侧栏比主区深。没量到的先用推算值，做深色时补拍核对（规格表 §1.9）。
- 其余颜色（图标变体、不可用、diff 行底色、琥珀、滚动条、权限模式标签等）见规格表 §1。

### 5.3 尺寸与字体

| 项 | 值 | 来源 |
|---|---|---|
| 正文字号 / 行高 | 14 / 20（紧凑 13 / 19） | 实测（对话字号「中」），与参照的舒适档一致；紧凑档是参照 |
| 代码 | 对话代码块和工具卡片的命令、输出、文件内容 ≈12～13 / ≈17（参照写的对话代码块 13、紧凑 12，对得上）；命令块 ≈12～13、diff 与文件面板 12，行步距都是 16.8；终端 ≈11 / 15.2 | 实测 |
| 小标题 / 标题 / 说明 | 15 / 22 / 12（紧凑 14 / 20 / 11） | 参照 |
| 实测字号 | 对话里的 markdown 标题 16 粗；设置分组标题 16 半粗；搜索输入 16；新会话问候语 ≈20；自定义页标题 ≈28；时间、快捷键提示、标签 ≈12～13；键帽 ≈11～12 | 实测，明细见规格表 §1.8 |
| 字重 | 400 / 500 / 580 / 600 | 参照；580 能否渲染待测，不行取 600。截图只分得出常规 / 半粗 / 粗 |
| 圆角 | 弹窗 12；浮层、菜单、审批卡、右侧面板、输入卡、用户消息 10；主按钮、统计卡、工具组卡片、命令框、代码块 8；审批卡主按钮、侧栏选中行 6；描边按钮、命令块、chips 5；全表见规格表 §1.7 | 实测（v2 横截面拟合，比 v1 小 1～2）；精确值以 DevTools 为准 |
| 圆角（参照） | 基础 / lg / 输入卡 / 卡片 = 8 / 10 / 14 / 12（紧凑 6 / 7 / 12 / 10；卡片 = 基础 + 4） | 参照；输入卡、卡片都比实测大，以实测为准 |
| 侧栏宽 | 288（含右缘细线） | 实测，与参照一致 |
| 顶部条 / 会话头 | 36 / 48（合计 84；没有横贯的标题栏） | 实测；取代参照推断的"标题栏约 58" |
| 默认 / 最小窗口 | 1200×800 / 600×400 | 参照；Yxi 默认已是 1200×800 |
| 对话列宽 | 768（对话宽度「窄」，同时管输入卡和审批卡；「中」「宽」没测）；主区不够宽时两侧各留 40（§5.1） | 实测 |
| 段落步距 | ≈30；消息与消息之间 ≈46～48（含操作行的位置，操作行不显示也照留，规格表 §3.4） | 实测 |
| 思考内容 | 14 / 20 斜体，弱色；不缩进，没有标题、边框、底色；只在「思考」「详细」两档显示（§5.4） | 实测；中文是合成斜体，Yxi 照做还是只斜西文，M3 定 |
| 输入卡 | 768×44（单行；多行怎么长高没拍到） | 实测 |
| 侧栏行 | 高 26.4；步距：导航行 26、会话行 28 | 实测 |
| 右侧面板 | 顶 44，离窗口右、下各 8；头部高 32；宽 ≈1040（终端 ≈578），2048 窗口下 | 实测（规格表 §3.12） |

- 密度：Code 页是舒适档（对话字号「中」= 14 / 20）；「小」「大」两档没测。
- 字体：界面用 Segoe UI，中文回退微软雅黑 UI；等宽用 Consolas（参照的等宽栈在 Windows 上一般落到它）。macOS 用系统字体（SF Pro，中文苹方），等宽 SF Mono、取不到用 Menlo，要真机核（规格表 §7.1、§7.2）。不打包 Anthropic 字体。实测桌面版设置里有「界面字体」三档（默认品牌字体 / 系统 / 读写障碍友好），另有「代码字体」（代码和终端共用）。五轮参考图用的都是默认品牌字体，字宽不可比；第四、五轮参考端的界面是英文，字宽也不和前三轮比。要比字宽，可选再用「系统」拍 3～4 张，核实它在 Windows 上是不是落到 Segoe UI（§9）。
- 衬线：实测新会话页问候语是无衬线，只有自定义页标题用衬线；Yxi 一律无衬线（§10-7 已定），不随包带字体。

### 5.4 组件

| 组件 | Code 风格要点（官方文档 + M0 截图） | Yxi 现状 | 里程碑 |
|---|---|---|---|
| 新会话 | 侧栏顶部「+ 新会话」+ Ctrl+N；发第一条消息前在输入区选环境、文件夹、分支 / worktree、模型、权限；首次进入文件夹要确认信任 | Ctrl+N 已有；信任确认待核实 | M2 |
| 会话列表 | 按状态 / 项目 / 环境筛选，可按项目分组；置顶（前 20 条）；行上有"需要输入"和未读标记，hover 出归档；点标题改名。实测：分组标题右侧的图标常显，当前文件夹所在的分组有新建 / 搜索 / 筛选三个，其余分组只有新建；会话行前是状态圆点（实心 / 空心）；标题过长不用省略号，右端渐隐；hover：底 #F0EFEC，右端出「⋮」、渐隐区加宽给它让位，鼠标停着时在行右侧弹出完整标题的 tooltip（规格表 §3.5、§3.1） | SessionsPane、ProjectTree，各面板各一套 | M2 |
| 需要你处理 | 行上的"需要输入"标记，对应 STYLE §1.2 的"等你" | Ctrl+Alt+A 能跳转 | M2 |
| 搜索 | Ctrl+K 命令面板，能搜到工具输出和已归档会话。实测：按窗口居中的弹窗，宽 672、顶 264，遮罩黑 40%（规格表 §3.6） | Ctrl+K 任务切换器（TaskSwitcher）：搜 SSH 会话的标题、会话名、目录、主机，可勾选包含归档；不搜本地任务和对话内容 | M2 换样式；M4 扩大搜索范围 |
| 会话工具栏 | 见 §5.1：标题菜单 + 右侧四个图标（规格表 §3.9） | 各面板不一致 | M2 |
| 空态（新会话页） | 实测：没有会话头标题；左对齐的问候行；本机用量统计卡（概览 / 模型，全部 / 30 天 / 7 天，3×2 数字块 + 26×7 热力图，只在有真实数据时显示）；输入卡上方一行 chips（环境、文件夹、分支 + worktree 勾选框、添加文件夹）；分支下拉向上展开，搜索框在最下面、离 chip 最近，打开时焦点就在搜索框上（推测），当前分支打蓝勾（规格表 §3.1、§3.8）；输入卡停靠在底部，不居中（规格表 §3.8） | 待核实 | M2；统计卡 M4（要有本机历史的真实数据） |
| 输入卡 | 圆角卡片（实测 768×44）；「+」菜单（附件、斜杠命令等），可拖入文件；@ 文件 / 会话；卡片下方是页脚行：左边权限模式（Ctrl+Shift+M）和「+」，右边模型（Ctrl+Shift+I）、强度（Ctrl+Shift+E）、上下文用量环；这些浮层都向上展开、盖住输入卡（规格表 §3.1、§3.2）；发送的回车图标聚焦时深一档、失焦时浅一档，和有没有文字无关；运行中发送按钮变成停止方块；Enter 发送不能打断输入法；参考端输入为空且失焦时，占位符有时换成一句按对话生成的建议，Yxi 不做，保持固定占位符（规格表 §3.2） | ChatPane（SSH）的输入卡已是圆角卡片 + 底部模型 / 强度 / 模式 / 上下文 chips + 圆形发送；Attach / AttachmentMentions / DraftAttachmentTray（附件部分未发版）；其他面板待核实 | M2 |
| 排队 | 运行中发的消息排在对话里；第一条有「立即发送」，Ctrl+Enter 打断当前回复并立即发送；可编辑、删除、拖动排序。实测：排队的消息就是普通的用户消息，按顺序排在对话末尾，没有托盘、标签；队首那条下方有弱色小字「立即发送」；最后一条后面是「4s · 等待中…」式的状态行；停止按钮一直在（规格表 §3.4） | InstructionStrip：发送、调整方向（发给正在跑的这一轮）、编辑、上移 / 下移、移到首位、撤回；只接入 ChatPane、ClaudeConversationPane、CodexWorkspacePane；没有打断再发（Ctrl+Enter），没有拖动。Code 风格下排队项改画成对话末尾的消息，动作不变 | M2 |
| 用户消息 | 实测：底 #F0F0EF，右缘贴对话列右边，圆角 10，单行高 36，多行保留换行、行步距 20；下方一行是相对时间和复制 / 重试 / 分叉，什么时候显示没摸清，Yxi 按 hover 显示（规格表 §3.4） | 经典用 userBubble 色 | M3 |
| 助手正文 | 无气泡 markdown；复制等操作 hover 才露出。实测：鼠标停在回复上时，下方出现复制 / 分叉 / 置顶和相对时间；思考行形如「5s · 18 tokens · 思考中…」，图标是参考端的品牌星，Yxi 换掉（规格表 §3.4、§5） | Markdown.kt（AssistantBody） | M3 |
| 对话视图 | 三档，Ctrl+O 切换：普通（工具调用折叠成动词短语摘要）/ 思考（再显示思考）/ 详细（逐条展开）。实测：入口在会话标题菜单的「对话视图 >」，子菜单三项、当前档打勾；设置里有默认值，在会话里改只影响那个会话。思考是弱色斜体的纯文本，按时间穿插在回合里（工具结果回来后还会再出一段）。普通：不显示思考、不留占位，工具调用只剩折叠的摘要行；思考：显示思考，摘要行仍折叠；详细：显示思考，工具组卡片和里面每个条目都展开（规格表 §3.1、§3.4、§3.9） | 各面板各自处理；本地历史显示成"运行器记录：reasoning" | M3 |
| 工具行 | 一行：图标 · 动词短语 · 对象 · 状态 / 耗时，点开看输入输出。实测：一个回合里连着的几次调用合成一条摘要行（逗号连接的过去式短语，没有图标），14 弱色，后跟折叠箭头，点开变次色；点开后下方出现撑满列宽的工具组卡片：透明底、细线外框、圆角 8，条目之间细线分隔；执行命令 = 白底命令框（shell 着色）+ 等宽次色输出，读文件 = 路径行 + 带行号的内容框。有改动时形如「已编辑 X，用了 1 个工具 +1 −1 >」（规格表 §3.4） | 各运行器不统一 | M3 |
| 待办 / 子任务 | 官方放在右侧任务面板（子任务、后台 shell）；对话流里有没有清单卡待测 | 零散；本地历史里的 subAgentActivity 没渲染 | M3 / M4 |
| 审批卡 | 官方：手动模式先给 diff，每处改动可接受 / 拒绝；首次访问外部网站：仅此一次 / 总是允许 / 拒绝（Enter = 仅此一次）；多选题逐题作答。实测三种卡都停靠在输入卡上方、不进对话滚动区，与输入卡同宽，圆角 10：执行命令（标题、说明、提示、灰底命令块；拒绝 + 允许一次）；改文件（内嵌 diff；拒绝 + 总是允许 + 允许一次）；多选题（选项带复选框、「其他」文本框；跳过 + 提交，没选时提交不可用；答完在对话里留一个已答气泡）。按钮带键帽。首次访问网站的卡没拍到（规格表 §3.3） | Approval.kt；Enter / Esc 行为不变，只换样式；键帽只显示 Yxi 实际生效的键，不照搬参考端的 1 / 2 / 3 / Ctrl+Enter | M3 |
| 改动 | 官方：对话里有"+12 -1"指示器，点开右侧 diff 面板：左边文件列表、右边改动；可逐行评论，Ctrl+Enter 一次提交。实测：工具行末尾带「+N −N」；有改动时输入卡上方出现分支改动条（分支名、改动数、「创建 PR」、关闭）；改动面板里是文件标题行，下面紧跟这个文件的 diff，素材只有一个文件，多文件怎么排、左边文件列表都没见到（规格表 §3.3、§3.12）。「创建 PR」没有真实链路就不放（§1） | GitChangesPane；Codex 的 fileChange 已能解码；没有逐行评论 | M3 / M4 |
| 右侧面板 | 见 §5.1：独立圆角卡片，改动 / 文件 / 预览 / 终端，可上下分屏（规格表 §3.12） | GitChangesPane、FilesPane、BrowserPane、TermPane（深底） | M4 换样式 |
| 运行中 | 实测参考端是一条状态行：循环动画的品牌图标 + 文字 + 真实秒数（形如「正在压缩会话… · 9s」），结束时 ≈200 ms 淡出。Yxi 结构照做，图标换成静态的，秒数每秒刷新 | 参照是动画图标，这里按 §2-5 故意偏离 | M3 |

### 5.5 统一对话项（M3 的前提）

7 个以上面板各自解析、各自渲染。Code 风格要是每个面板再画一遍，结果一定不一致。所以 M3 先抽一层只读视图模型：

- `TranscriptItem` 密封接口，分为 User、AssistantText、Thinking、Tool、Todo、SubAgent、Approval、Change、Notice。字段在 M3 开工时定。
- 每类运行器各写一个适配器，转成 `TranscriptItem`：SSH 转录、Claude、Codex app-server、OpenCode、ACP、本地原生历史。`CodexTaskController.decodeMessage` 里现成的 commandExecution / fileChange / userMessage 解码直接复用，不重写。
- Code 风格组件只认 `TranscriptItem`。经典风格组件不强制迁移，零回归优先。
- 审批项只带 id，动作仍然走现有控制器。
- 适配器写成纯函数，每类运行器拿真实样例（脱敏）做单测。

### 5.6 第三方模型配置（G5）

Yxi 已经有这套能力，叫「线路」，本来就是参照 CC Switch 做的，不另起炉灶。

- 现状：`RoutesPane`（映射区行序 Sonnet / Opus / Fable / Haiku / Subagent）、`LinePresets`（138 个模板）、`ProviderModelPicker`（可搜索的获取模型下拉）、`ConfiguredModels`、`ClaudeSubscriptionSettings`（切回官方订阅时清掉第三方变量）、`CredentialFile`。更正（10-15 逐项核代码）：线路连同密钥实际存在各服务器的 `~/.yxi/lines.json`，本机 Claude 只走官方订阅；本机 Claude 任务启动时按会话传 `--settings`。
- Code 风格下：放进设置 →「模型供应商」，用 Code 风格的列表和表单重排；输入卡的模型下拉顶部显示当前供应商，可以直接切换。
- 实测参考端的账号菜单里有「推理配置」入口；用第三方推理时，账号菜单头显示「名称 · 推理来源」，模型 chip 显示第三方模型名（规格表 §3.1）。建议 Yxi 也在账号菜单放一个「线路」入口，账号行显示当前线路名；只加入口和显示，线路逻辑不动。
- 从 CC Switch 导入（M4，可选；做之前要先定两件事：加不加 sqlite-jdbc 这个依赖，以及导入的线路写到哪台服务器——线路存在服务器上，见上）：用户点了才做。只读打开本机 `~/.cc-switch/cc-switch.db`，只取 Claude 类供应商的名称、地址、模型映射和密钥，先列出来让用户勾选；密钥只存进 Yxi 自己的凭据存储，不打印、不写日志。
- 防呆：Fable 槽和主模型相同时给警告。这种写法会让 Claude Code 往系统提示词里注入 Fable 身份，模型会自称 Fable；CC Switch 的 Zhipu GLM 等模板就是这么写的，导入时也要检查。
- SSH 远程主机：沿用现有的线路应用逻辑（`ConversationRouteApply`），M4 核实密钥不会出现在命令行参数和 tmux 历史里。
- 不搬：CC Switch 的本地代理与故障转移、MCP / 提示词 / Skills 管理、用量计费（Yxi 已有 UsageUi）。

### 5.7 macOS 版（G6）

参考端和 Yxi 在 Mac 上都还没有素材，下面按现有代码（§3）和 Mac 的平台习惯定范围；界面上和 Windows 不同的地方以规格表 §7 为准。分两步，和 M1～M5 并行、互不阻塞：Mac1 让经典风格在 Mac 上能装、能用、密钥不明文；Mac2 补齐 Windows 独有的功能。Code 风格的 Mac 外壳（红绿灯、菜单栏、⌘ 键帽）从 M2 起跟着做。

Mac1 打包：
- `build.gradle.kts` 的 `nativeDistributions` 加 `macOS {}`：目标格式 Dmg、icns 图标、bundle ID。jpackage 只能在 macOS 上打 dmg，CI 加一个 macOS 任务。
- Developer ID 签名 + Apple 公证，否则 Gatekeeper 拦着打不开（§10-13）。hardened runtime 要给 JVM 开 JIT 相关的 entitlements。JNA、Skiko 的本机库要么在包里逐个签，要么关掉库校验，哪种能过公证要实测。JCEF 是第一次用时才从 jar 解压到数据目录的（`BrowserRuntime.kt:67`），不在 .app 里，.app 的签名管不到：要么打包时预先放进 .app 一起签，要么关掉库校验，同样实测定。
- 只出 Apple Silicon（§10-12 已定）；最低系统版本取 JDK 21 和所带 JCEF 两者要求里高的那个，写进 `LSMinimumSystemVersion`。
- 不上 Mac App Store（§1）。

Mac1 运行时：
- 凭据：新增基于钥匙串（Keychain）的 `CredentialProtector`，`forPlatform()` 在 Mac 上返回它。启动时把已有的明文 `hosts.json`、`auth.json` 迁进去，再删掉明文；钥匙串不可用时不存密钥、说出原因（STYLE §0）。这是 Mac 包发版的前提。
- PATH：启动时用用户的登录 shell 读一次 PATH（带超时），再补 `/opt/homebrew/bin`、`/usr/local/bin` 等常见目录，运行器查找和子进程都用它。
- 窗口、菜单栏、Dock、⌘ 快捷键、关窗行为：按规格表 §7.1、§7.2 做，经典风格同样适用。
- 只在 Windows 上生效的设置（开机自启、自动更新）在 Mac2 之前隐藏，不留灰的入口。Android 模拟器等工具面板里写死了 Windows 路径的，在 Mac 上逐个核实，走不通的不放入口。

Mac2：
- 开机自启：`~/Library/LaunchAgents` 下写 plist，或用 ServiceManagement，实现时选定。
- 自动更新：Velopack 支不支持 jpackage 打出来并公证过的 .app 没核实；不支持的话先做「有新版本 → 打开下载页」。
- 本机听写（Yxi 已有的 DesktopLocalAsr）：Mac 上配 whisper.cpp 的 CLI，实测一遍。

Mac1 验收：在没装过 Yxi 的 Mac 上装公证过的 dmg，第一次打开不弹拦截；连 SSH 主机、跑本机 Claude / Codex 任务、审批、贴图片附件都走通；数据目录里找不到明文密码；有在途工作时 ⌘Q 先确认。

Mac1 第一轮（2026-09-29，Mac mini 上实测，没提交）：
- 打包：bundle ID `app.yxi.desktop`；图标由现有的 512 源图生成，最大只到 512。`LSMinimumSystemVersion` 定为 12.0：实测 Mach-O 的 minos，启动器、libjvm、libskiko 是 11.0，JCEF 的几个是 12.0，取高的。没有证书时 createDistributable 出的 .app 就是 ad-hoc 签名加 hardened runtime，带 4 条 entitlements：JIT、未签名可执行内存、关库校验、麦克风。dmg 不用 packageDmg，改用 hdiutil 出：jpackage 出 dmg 要靠 osascript 让 Finder 摆图标，无人值守时会卡住。Yxi.app 415 MB，dmg 307 MB。挂载 dmg 拷出来的 .app 过了 `codesign --verify --strict`，`--smoke`、`--browser-smoke` 都过；uber jar 的冒烟也过。spctl 判为 rejected：没有 Developer ID，签不了名、公证不了（§10-13）。
- 关库校验去不掉：ad-hoc 签名没有 Team ID，开着 runtime 时连自带 JDK 的 libjli 都加载不了。JCEF 运行时（306 MB）第一次启动时才解压到数据目录，是另一个 Team ID 签的。有了 Developer ID 以后，再实测「打包时预先放进 .app 一起签」能不能去掉这条。
- 公证的入口在打包脚本里留好了（`-Pyxi.mac.sign`、`YXI_MAC_NOTARY_*`），但 Compose 的 notarizeDmg 按设计公证的是 packageDmg 的产物（没实测）；CI 的 dmg 由 hdiutil 出，正式公证时是直接对它跑 notarytool + stapler，还是改回 packageDmg，到时实测定。
- 钥匙串：`MacKeychainCredentialProtector` 在钥匙串里放一把 AES-256-GCM 主密钥（service `app.yxi.desktop`），用它加密 `hosts.json`、`auth.json`。解密时找不到主密钥就报错，不新建；钥匙串锁着时不落盘，也不回退明文。Windows 的记录（格式 `yxi-dpapi-v1`）拿到 Mac 上会被格式检查挡住。用临时钥匙串文件测了 7 例，全过，登录钥匙串测前测后一致。App 里还没走过真实登录钥匙串那条路（`--credential-smoke` 会碰登录钥匙串，没跑）。ad-hoc 签名每次打包 cdhash 都会变，钥匙串可能每换一版就再弹一次授权框（推断，没跨版本实测）。
- PATH：`LoginShellPath` 启动时在后台起一次登录 shell（`-lic`，5 秒超时）读 PATH，再补上 Homebrew 的目录。11 个起运行器、起子进程的文件改成用它；Windows / Linux 原样用继承的环境。10 例单测在 Mac 上过了 9 例，剩下 1 例是非 Mac 专用、按设计跳过。
- CI：desktop.yml 新加 macos job（macos-15），依次跑回归、打包、重签出 dmg、冒烟，上传 `Yxi-macos-arm64`。job 设了 `continue-on-error`，失败不挡 Windows 发布；但 publish.sh 要等整个 run 结束，也就是要等 macOS 这个 job 跑完或超时（上限 60 分钟）。job 超时以后整个 run 的 conclusion 会不会还是 success，没查证；社区的说法是超时会记成 cancelled。几步各自的超时加起来 65 分钟，比 job 的 60 分钟上限还长。这个 job 还没在 GitHub 上跑过。
- 还没做：§7.1、§7.2 的原生窗口 / 菜单栏 / Dock、⌘ 快捷键、关窗行为；隐藏只在 Windows 上生效的设置；签名和公证。SSH、本机任务、审批、附件在 Mac 上都没走过。

## 6. 功能对照与缺口

✅ 已有 · ◐ 部分有 · ✗ 没有 · ？ 待核实。原则：先换样式、再补缺口；补缺口必须有真实数据链路。

| 能力 | 状态 | 依据 | 里程碑 |
|---|---|---|---|
| 本机 / SSH 远程环境 | ✅ | 本地工作台 + SSH 主机 | — |
| Cloud / WSL 环境 | ✗ | 本期不做 | — |
| 新会话选文件夹 + 信任确认 | ◐ | 信任确认待核实 | M2 |
| 分支 / worktree 选择 | ◐ | DesktopLauncher、SessionsPane | M2 / M4 |
| 会话状态（等你 / 干活中 / 刚跑完） | ✅ | STYLE §1.2 | M2 换样式 |
| 筛选 / 分组 / 置顶 / 归档 / 改名 | ◐ | `state.navigation` 已有置顶、归档、标题，入口分散在多个面板；按状态 / 环境筛选、按项目分组待核实 | M2 |
| 命令面板搜索 | ◐ | TaskSwitcher（Ctrl+K）只搜 SSH 会话的标题 / 会话名 / 目录 / 主机，可勾选包含归档 | M2 / M4 |
| 导入 CLI 原生历史 | ◐ | LocalCodexHistory（只读） | M3 |
| 附件 / @ 文件 | ◐ | Attach、AttachmentMentions；更正（10-15 逐项核代码）：「@」只补全草稿里已附的附件，不搜工作区文件 | M2；@ 工作区文件待定 |
| @ 其他会话 | ✗ | 核过没有（10-15） | M4，先补拍参照端 |
| 斜杠命令菜单 | ✅ 未发版 | M4k：内置表挪进 core；SSH Claude 加主机 ~/.claude 的命令和技能（ConfigRemote），本机 Claude 用 initialize 回包的 commands，ACP 用 available_commands_update；Codex app-server、OpenCode 没有来源，不弹 | M4 |
| 模型 / 推理强度 | ✅ | ChatPane 输入卡的 chips；另有 ConversationModelMenu、CodexModelPicker、EffortControl，各面板用法待核实 | M2 统一进输入卡 |
| 第三方模型配置（线路） | ◐ | RoutesPane、LinePresets、ProviderModelPicker 已有；没见到从 CC Switch 导入；Fable 防呆待核实（§5.6） | M2 换样式；M4 导入 |
| 权限模式 | ◐ | `PermissionMode` 已覆盖官方 5 种 + dontAsk；ChatPane 的模式 chip、ConversationPermissionMenu。官方按文件夹记住上次的模式（Plan 只作用于当前会话）；Yxi 每次从运行器屏幕读取当前模式；M4i 起 SSH 新会话页按文件夹记住（计划、完全访问不记） | M2 统一进输入卡；M4i 已做 |
| 用量环（上下文 + 套餐） | ◐ | ChatPane 有「上下文 Nk」chip（只有 token 数，没有百分比）；UsageUi 显示那台机的 5 小时窗口 / 今天 / 近 7 天花费；更正（10-15）：UsageStrip 组件已经没有调用方 | M2 |
| 停止 / 排队 | ◐ | InstructionStrip 已有发送、调整方向、编辑、排序、撤回（§5.4）；LocalConversationPane、AcpConversationPane、OpenCodeConversationPane 没接；没有打断再发（Ctrl+Enter）和拖动 | M2 |
| 对话视图三档（Ctrl+O） | ✅ 未发版 | M3g：六个 Code 对话页共用 TranscriptView，设置 → 外观可选默认档（M3h） | M3 |
| 对话宽度 / 字体等外观设置 | ◐ | 实测参考端有：界面字体三档、对话字号小 / 中 / 大、对话宽度窄 / 中 / 宽（同时管对话列和输入卡）、默认对话视图、代码配色浅 / 深 + diff 预览、代码字体（代码和终端共用），见规格表 §3.7。M4q 做了 Code 风格的设置弹窗和其中的对话字号、对话宽度、默认对话视图（存 `Store.pref("ui.chatFontSize")` / `("ui.chatWidth")`，只在 Code 风格生效）；不做界面字体（§10-7 不随包带字体）、代码配色（Yxi 没有可换的代码配色）、代码字体（终端是 JediTerm 的 Swing 组件，字体在建终端时由 TermPane 的设置写死，要换得重建终端；只换得了代码块的不放入口，PRD §1）。全局缩放照旧（Ctrl+= / - / 0，0.8–1.5，存 `Store.pref("zoom")`） | M3 / M4q |
| 消息操作（复制 / 重试 / 分叉） | ？ | 参考端每条用户消息下方有一行（规格表 §3.4）；Yxi 有回退（Rewind.kt），复制、分叉待核实 | M3 核实 |
| 工具行统一 | ◐ | 见 §5.4 | M3 |
| 待办清单 | ？ | ChatPane 有命中，未核实 | M3 核实 |
| 子任务 / 后台 shell（任务面板） | ◐ | 本地历史有数据，没渲染 | M3 / M4 |
| 计划面板 | ✗ | — | M4 |
| 审批卡 | ✅ | Approval.kt | M3 换样式 |
| 多选题卡 | ◐ | Approval.kt、ChatPane 有命中；CodexQuestionForm.kt | M3 |
| 回退（Rewind） | ✅ | Rewind.kt、ConversationRewind.kt 等 | M3 换样式 |
| 改动 diff 面板 | ✅ | GitChangesPane | M3 / M4 |
| diff 逐行评论 | ✅ 未发版 | M4j：点行号区开评论框，Ctrl+Enter 一起加入对话 | M4 |
| 提交 / 推送 / PR / CI 状态栏 | ◐ | 更正（10-15）：CodexWorkspacePane 的「命中」只是一个按钮，没有状态栏。M4p 做了只读的分支改动条（规格表 §3.3：分支名、+N −N、点开改动面板、可关），「创建 PR」没有真实链路不放；官方的 CI 状态栏（带自动修复 / 自动合并开关）需要那台机装 GitHub CLI（gh） | M4；PR / CI 要 gh 登录了才做 |
| 文件 / 浏览器 / 终端面板 | ✅ | FilesPane、BrowserPane、TermPane（深底） | M4 换样式 |
| 面板拖动 / 弹出成窗口 | ✗ | — | M5 之后再评估 |
| 分屏（Split View）/ 侧边对话（side chat） | ✗ | — | M5 之后再评估 |
| 定时任务 | ✅ 未发版 | ScheduledTasksPane；M4l：Code 外观的列表页、立即运行、完成通知 | M4 |
| 本机用量统计卡（新会话页） | ◐ | 参考端：概览 / 模型，全部 / 30 天 / 7 天，数字块 + 热力图（规格表 §3.8）；M4n：SSH 主机装了 ccusage 时显示（Usage.daily，近半年），本机运行器还没有 | M4；只用真实数据，没有数据就不显示 |
| 自定义页（技能 / 插件，可上传本地插件） | ◐ | 参考端侧栏有「自定义」入口（规格表 §3.10）；Yxi 有插件页（PluginsPane：本地 / 服务器、市场 / 已安装 / 共享配置），技能能从服务器 ~/.claude 读（ConfigRemote）；M4o 做 Code 外观 + 技能页签，上传本地插件不做 | M4 |
| 在 IDE 中继续 | ✅ 未发版 | M4m：本机会话头「打开方式」——登录 shell 的 PATH 里找到的编辑器、在访达 / 资源管理器中显示；SSH 会话照旧复制路径 | M4 |
| 桌面通知 | ✅ | Notify.kt | — |
| 快捷键 | ◐ | 已有 Ctrl+N / K / B / , / /、Ctrl+Tab、Ctrl+1…9；官方另有 Ctrl+O、Ctrl+W、Ctrl+Shift+I / E / M / D / B、Ctrl+反引号（终端）、Ctrl+反斜杠（关面板）；实测还有 Ctrl+Shift+P（预览）、Ctrl+Shift+F（文件面板） | M4 对表；已有键位不改（如 Ctrl+J） |
| 自绘标题栏 | ◐ | Windows：Main.kt `TitleBar`；Mac 上要改成系统标题栏、内容伸到标题栏下面（§5.7） | M2 换样式；Mac1 |
| 默认窗口 1200×800 | ✅ | `Main.kt:72` | — |
| Mac 安装包（dmg、签名、公证） | ◐ | 有了 ad-hoc 签名的 dmg 和 macOS CI 任务，Mac mini 上挂载、拷出、启动都过了；没有 Developer ID，签不了名也公证不了，Gatekeeper 会拦（§5.7），没提交 | Mac1 |
| Mac 凭据加密（钥匙串） | ◐ | 有了钥匙串主密钥 + AES-GCM 的实现，Mac mini 上用临时钥匙串测了 7 例，全过；App 里还没走过真实的登录钥匙串，没提交（§5.7） | Mac1，发版前提 |
| Mac 窗口、菜单栏、Dock | ✗ | `Undecorated` 自绘，没有红绿灯 | Mac1 |
| ⌘ 快捷键 | ◐ | 只有对话里粘贴图片认 ⌘ | Mac1 |
| Mac 上找运行器 | ◐ | 已分平台；登录 shell 的 PATH 和 Homebrew 目录已经合进找运行器和起子进程的环境，Mac mini 上单测过了；还没在 Mac 上实际找过 claude、codex，没提交（§5.7） | Mac1 |
| 数据目录、单实例、打开浏览器、一键配本机 SSH | ✅ | 已分平台（§3），没在 Mac 上实测 | Mac1 实测 |
| Mac 开机自启 / 自动更新 | ✗ | 注册表 / Velopack 的 Windows 包；Mac 上已灰掉或不查 | Mac2 |
| 已有的本机听写（Mac） | ？ | DesktopLocalAsr 只在 Windows 上要求 `.exe`，Mac 没实测 | Mac2 |

本期不做：听写 / 语音输入、iOS 模拟器、跨会话消息卡片、「在 Web 中继续」（依赖云端）。

## 7. 像素级验收：怎样才算"一模一样"

基准环境（2026-09-28 用户定）：Windows 11，系统缩放 125%，窗口最大化（2048×1104 CSS px），Yxi 界面缩放 1.0。下表和规格表里的 px 都是 CSS px，等于 Yxi 的 dp（1 px = 1.25 物理像素）。浅色逐项比；深色只比规格表 §1.9 标「实测」的项，其余目视（深色只有 1 张素材，用户说深色不常用）。

| 维度 | 容差 | 说明 |
|---|---|---|
| 几何（区块位置、宽高、内边距、圆角） | ±1px | 侧栏、标题栏、会话头、对话列、输入卡、消息块、工具行、审批卡 |
| 平涂颜色 | 每个 sRGB 通道 ±2 | 取区块内部像素，避开抗锯齿边缘 |
| 字号、行高、基线 | ±1px | 字体不同，字形本身不比 |
| 阴影、抗锯齿、字形 | 只目视 | Compose 与 Chromium 的渲染差异无法逐像素比 |
| 交互 | 逐项对表 | hover / 焦点 / 按下 / 禁用、键盘路径与焦点顺序、折叠展开、菜单弹出位置、中文输入法组字；过渡时长和缓动曲线按 DevTools 读到的值（循环装饰动效除外，§2-5） |

- 流程：参考截图 → 量成规格表 `design/ui-style-spec.md`（只有数值，没有截图）→ Yxi 截图夹具（env 门控）拍同一场景 → 脚本量出边界和颜色 → 与规格表比对 → 报告列出超差项。
- 参考图以桌面 App 截图为准；浏览器页面（Gecko / Chromium、Linux 字体）只作补充。
- 经典风格：改动前后同机比，像素零差异（§4.3）。
- 100% / 150% / 200% 缩放，以及 1200×800、1280×720、1920×1080 窗口（后两个是工作台 PRD 要求的）：只验布局（不裁切、不重叠、不截断），不比像素。
- 基准原定 100%、1200×800 与 1440×900。五轮参考图都是在用户常用的 125% 最大化下拍的，2026-09-28 改成现在的基准，那两套不再拍。
- 依赖窗口尺寸的布局（弹窗居中、对话列变窄、侧栏自动收起）只有最大化这一种实测。其他尺寸按 §5.1 和规格表 §2 推出的规则验，同样只验布局。
- Yxi 最大化时按屏幕可用区收窗、不盖任务栏（`Main.kt` 的 `fitMaximized`），尺寸应与参考端一样是 2048×1104，M0 做夹具时实测确认。
- macOS：基准环境等有 Mac 素材时再定，暂按规格表 §7.3（系统默认的 Retina 缩放、按住 Option 点绿灯铺满屏幕、浅色）。量法同上，物理像素 ÷ 2；取色前先转成 sRGB。没有 Mac 素材之前，Mac 只验规格表 §7.1 列的差异和布局，其余数值和 Windows 一致。

## 8. 里程碑

| 里程碑 | 内容 | 出口 |
|---|---|---|
| M0 基准 | 拿到参考图（§9）；量出所有"待测"；定截图夹具方案：真窗口 `Robot`、离屏 `ImageComposeScene`（这两种不加依赖）、Roborazzi Desktop（要加依赖，§10-8），各试一次看稳不稳；录经典基线截图 | 规格表量完；夹具可重复运行。2026-09-28：规格表 v4 已出（[ui-style-spec.md](ui-style-spec.md)），§9 清单的必拍项齐了（深色用户先跳过，未读 / 置顶碰到再拍）；夹具定为离屏 `ImageComposeScene`（`ClassicThemeShotsTest`，env 门控），同日在 b1f44afa 上录了经典基线：6 个场景，明暗各一张，共 12 张，放在仓库外。连跑 3 次比对都是 0 差异（§4.3）。真窗口 `Robot` 和 Roborazzi 没试：离屏方案已经够稳，也不用加依赖 |
| M1 风格切换 | §4 全部，加上 Code 风格 v0（只换 token，挂"预览"）；顺手修 §12-1 | §4.3 全部通过。2026-09-28 开工，分支 `claude/code-theme`（从 b1f44afa 开）。同日代码写完：编译通过，新加的 13 个单测和门禁都过了，还没提交。截图比对用 M0 的夹具跑过，经典 12 张全部 0 差异。应用级的切换验证 `StyleSwitchStateTest` 也过了：轮切 20 次，草稿、界面状态、连接对象都在；只剩连接在线和运行中任务要进 hk13 的容器（§4.3） |
| M2 Code 外壳 | CodeShell：标题栏、侧栏、会话头、空态、输入卡；本机与 SSH 主机共用 | 骨架量值达标（§7）。2026-10-15：外壳、侧栏（本机 / SSH）、新会话页、各运行器的 Code 对话页都已落地；会话头量值核过（规格表 §3.9，±1 内）；两侧边距统一成 40；深色强度条修了。有意偏差和发现见 §14。量值以 Windows 截图基准为准，10-15 起另有一套 Mac 截图基准（字体渲染不同，两边不互比） |
| M3 对话流 | §5.5 统一对话项，加 §5.4 的各组件；修 §12-2 | 7 类会话在 Code 风格下显示一致，量值达标 |
| M4 补缺口 | §6 里标 M4 的项、右侧面板、快捷键对表 | 每项都有真实链路 |
| M5 全量验收 | 基准环境下浅、深两套按 §7 比；100 / 150 / 200% 和 1200×800、1280×720、1920×1080 只验布局；本机运行器与 SSH 主机端到端各走一遍 | 去掉"预览"；默认风格另议（§10-5） |
| Mac1 能用 | §5.7 的 Mac1：dmg + 签名 + 公证、钥匙串、PATH、原生窗口 / 菜单栏 / Dock、⌘ 快捷键、macOS CI；先用经典风格 | §5.7 的 Mac1 验收。2026-09-29 第一轮做完了打包（ad-hoc 签名的 dmg）、钥匙串、PATH、macOS CI 任务。在 Mac mini 真机上：19 个类的回归 111 例，加上 2 个新类，全过；从 dmg 拷出来的 .app 过了冒烟和浏览器冒烟。合进 M1 的工作区以后，Windows 的门禁、M1 单测、M0 比对、切换用例又重跑了一遍，都过了。原生窗口 / 菜单栏 / Dock、⌘ 快捷键、签名公证（缺 Developer ID）还没做。（更正：第一轮已随 0ce31da6 提交，原文「没提交」已过时；macOS CI 任务在 36c2d92a） |
| Mac2 补齐 | §5.7 的 Mac2：开机自启、自动更新、本机听写 | 每项有真实链路，走不通的不放入口 |

- Mac1、Mac2 和 M1～M5 并行，互不阻塞。M2 起每个里程碑改外壳时，Mac 上同时做（差异按规格表 §7.1）；有了 Mac 素材以后，Mac 也按 §7 验。
- 每个里程碑都单独可发版、可回退。M5 之前默认风格仍是经典。
- M0 不改产品代码。
- 涉及 native / SSH / PTY 的自动化测试按交接约定只在 hk13 隔离环境跑，不用真实令牌。hk13 是 Linux，Mac 上的这类测试在 Mac mini 上跑（§10-14）：只用独立目录和隔离的 HOME，钥匙串测试用临时钥匙串文件，不碰登录钥匙串。

## 9. 参考怎么拿（M0）

用户已授权用本机 Claude Desktop（2.9939.2）调研，它就是 §7 的基准。测试机网页端是 Free 账号、进不去 Code；换成有权限的账号后只当补充（网页 + Linux 字体，字宽不同）。官方配图只能看结构。

| 手段 | 能量什么 | 谁来做 |
|---|---|---|
| 窗口截图（PrintWindow，无损 PNG） | 几何、颜色、字号 | 用户拍，我量（五轮都是这样）；我自己拍要先问，别抢焦点 |
| DevTools 读 Computed（§2-1） | 精确尺寸、颜色、字体栈、过渡时长 | 我读；打开开关要重启 Claude Desktop，先问用户；开法见 §13 |
| 逐帧截图 / 短录屏 | 展开、弹出、切换的动效 | 用户录，我量 |
| 点本地菜单、下拉、设置页 | hover / 焦点 / 菜单状态 | 我，按人的节奏（§2-2） |
| 发消息 | 运行中、审批、排队等状态 | 用户手动 |

进度（2026-09-28）：用户分五轮在本机手动拍了 50 张可用的 Code 页截图和一段约 8 分钟的录屏（浅色为主、深色 1 张，系统缩放 125%、窗口最大化、默认字体；第四、五轮参考端的界面是英文），已量进规格表 v4；本机账号有 Code 权限。下面清单的必拍项都齐了，剩下的 ◐ 是碰到再拍的。清单外还拿到了搜索、账号菜单、上下文用量浮层、筛选菜单、自定义页、语言弹窗、插件上传、会话头的两个菜单、右侧面板（终端 / 文件 / 预览 / 分屏）、侧栏收起和若干动效。深色只有 1 张，用户说深色不常用，其余按浅色的结构推算，做深色时再补拍核对（规格表 §1.9）。DevTools 要重启 Claude Desktop，什么时候重启由用户定，目前算可选。截图按系统缩放换算成 CSS px；DevTools 的 CSS px 不受缩放影响。Mac 素材一张都没有（§10-14）。

截图清单（基准环境：125%、最大化、浅色。✅ 拍到 · ◐ 部分 · ✗ 没拍到）：

| # | 场景 | 状态 |
|---|---|---|
| 1 | 空态（新会话、还没发消息） | ✅ |
| 2 | 新会话时环境 / 文件夹 / 分支下拉展开 | ✅ 分支下拉里只有一个分支 |
| 3 | 进行中的会话：有工具调用、有改动数（"+N −N"） | ✅ 工具行带 +1 −1，另有分支改动条 |
| 4 | 同一会话切到对话视图三档 | ✅ 第五轮补齐：子菜单展开 1 张；带工具调用的对话普通、普通档点开卡片、思考、详细各 1 张 |
| 5 | 审批卡：改文件、执行命令、多选题各一张 | ✅ |
| 6 | 右侧改动面板（文件列表 + diff） | ◐ 只有一个文件，没见到文件列表 |
| 7 | 侧栏：同时有等你、运行中、未读、置顶的行，外加一行 hover | ◐ 有等你、运行中、选中、会话行 hover；缺未读、置顶 |
| 8 | 模型 / 强度 / 权限下拉各展开一次 | ✅ |
| 9 | 排队：运行中再发一条 | ✅ 连发了三条 |
| 10 | 设置 → 外观页 | ✅ |

必拍项齐了。深色第五轮用户先跳过，做深色时再拍；第 6 项的文件列表、第 7 项的未读和置顶碰到再拍（规格表 §6）。可选：界面字体切「系统」拍 3～4 张，比字宽（§5.3）；排队的「立即发送」是不是常显、点了做什么。

- 存 PNG，不要 JPG（有损压缩会改颜色）；截图前关掉 HDR 和颜色滤镜。
- 外观设置和前五轮保持一致：界面字体「默认」（只有比字宽的那几张切「系统」）、对话字号「中」、对话宽度「窄」、默认对话视图「普通」，其余除明暗外保持默认；界面语言最好切回中文，和前三轮一致（第四、五轮是英文）。App 版本号变了要记下来，规格表按 2.9939.2 冻结（§11）。
- 用一个不含敏感内容的示例项目。截图放在仓库外（用户的素材文件夹，我只读）；我量的时候生成的裁图放本机临时目录，量完即删；仓库里只进数值（§2-4）。
- 测试机上我只截屏，不点击、不注入脚本（§2-2）。
- Mac：要一台装了参考端、账号有 Code 权限的 Mac，拍法见规格表 §7.3；同一台最好也能装 Yxi 的 Mac 包做对照（§10-14）。

## 10. 决定（1～12 已定，13 待定，14 定了一半）

用户 2026-09-27 答复了 1～11：第 3 条改为用本机客户端（§9）；第 9 条保持 `desktop-reference.md` 现状；第 10 条就叫「Code 风格」；其余按下面的建议执行。另加 G5（§5.6）。12～14 是 2026-09-28 为 Mac 版（G6）加的；同日用户定了 12（只出 arm64）和 14 里的测包机（Mac mini），13 和拍参考图的 Mac 还在等。

1. **"像素级复刻"的边界**。建议：布局、尺寸、交互对齐；名称、Logo、字体、品牌色刻意区分（§2-3）。Agent SDK 品牌规范是否约束 Yxi 未核实；如果约束，Code 风格要主动拉开距离（比如换强调色、空态不用问候语），G2 相应放宽。
2. **品牌位用什么颜色**（发送按钮等）。建议用 Yxi 铜色；clay #D97757 是参照里记录的品牌色，不用。
3. **参考图走哪条路径**（§9）。建议 A；A 不行再走 B；C 兜底。
4. **截图测试进不进门禁**。真窗口夹具需要桌面会话，GitHub windows-latest 能不能跑没验证过。建议先用 env 门控、不进门禁，M0 在 CI 上试过再定。M1 新加的 4 个单测类（§4.3）也还没进门禁清单，一并等这条定。其中 `NativeHistoryClickTest` 会构造 `AppState`，要进门禁的话，门禁也得给它配临时 profile。M0 的截图夹具 `ClassicThemeShotsTest` 是离屏的，不需要桌面会话；但还是要非 headless 的 AWT，基线也只对录制它的那台机器的字体有效。要进门禁或 CI，得在那台机器上另录一套基线。M1 的应用级切换用例 `StyleSwitchStateTest` 用的是同一个夹具，同样是 env 门控、不在门禁里。它不需要基线，但和 `ClassicThemeShotsTest` 共用夹具 profile 和 `Store` 单例，同一次运行只能跑一个：`YXI_THEME_SHOT_MODE=switch` 时只跑它，否则只跑截图比对。
5. **M5 之后的默认风格**。建议仍是经典，Code 风格由用户自己选，用一段时间再议。
6. **手机端跟不跟**。建议本期不跟（§1）。
7. **空态要不要衬线字**。已定（2026-09-28，按 M0 实测）：不用。Code 页的新会话问候语是无衬线，只有自定义页标题用衬线；Yxi 一律无衬线，不随包带字体。
8. **截图夹具**：Roborazzi Desktop（Apache-2.0，自带录制 / 比对 / 差异图；Desktop 支持标着实验性，要加依赖和 Gradle 插件并锁版本），还是用 `Robot` / `ImageComposeScene` 自己写（零新依赖）。建议先自研，因为对照规格表量值的脚本本来就要写；自研不稳再上 Roborazzi。
9. **公开仓库里的 `design/desktop-reference.md`**。它已在 `origin/main` 等公开分支上，写着 app.asar 的解包方法和安装包下载地址，与 §2-1 的立场矛盾，也有条款风险。建议保留数值和结论，删掉解包方法和下载来源，或者整份移到本机私有目录。历史里的旧版本只能靠改写 git 历史清掉，不建议。
10. **显示名"Code 风格"**。"Code"与官方标签同名，容易被当成官方主题。可以换成"工作台风格"这类中性名字；只改显示名，偏好值 `code` 不变。
11. **开发分支**。建议 PRD 定稿后从 b1f44afa 新开 `claude/code-theme` 做 M1。原计划在 `claude/local-mode` 上做的"本地模式第一阶段"已并入 §12，不再单独做。
12. **Mac 版先出哪种芯片**（2026-09-28 定：只出 Apple Silicon arm64）。Intel 版要另带一套 x64 的 Skiko / JCEF 本机库，多一条 CI 任务，jpackage 也打不出两种芯片合一的包，只能分成两个 dmg。有用户要 Intel 再加。
13. **Apple 开发者账号**（待定）。签名和公证要加入 Apple Developer Program，年费以 Apple 官网为准。个人账号的签名显示本人姓名；组织账号要邓白氏编码。证书和公证凭据只放 CI 的 secrets，只给发版任务用，不进仓库。没有账号就只能发没签名的包，Gatekeeper 会拦，用户得去「系统设置 → 隐私与安全性」手动放行。这样只够内部试用，过不了 Mac1 验收。2026-09-29 Mac1 第一轮实测：Mac mini 上 `security find-identity` 找到 0 个可用的签名身份，dmg 只有 ad-hoc 签名，spctl 判为 rejected。要的是一张 Developer ID Application 证书；公证还要 Apple ID、App 专用密码和 Team ID。打包脚本里入口已经留好：`-Pyxi.mac.sign=true -Pyxi.mac.signIdentity=…`，环境变量 `YXI_MAC_NOTARY_APPLE_ID` / `_PASSWORD` / `_TEAM_ID`。要在 Mac mini 上试公证，得先由机主本人执行 `sudo xcodebuild -license`：现在没同意 Xcode 许可，xcrun 跑不起来（rc=69），notarytool、stapler 都用不了。
14. **Mac 机器**（测包机 2026-09-28 定了，拍参考图的 Mac 待定）。要两类：
    - 拍参考图的 Mac：装 Claude Desktop，账号有 Code 权限（§9）。
    - 测 Yxi Mac 包的真机：跑 Mac1 验收；native / SSH / PTY 的测试要隔离环境（§8）。定为一台 Mac mini（M4、16 GB、macOS 27.0），用户授权经 hk13 的反向隧道登录。它是共享机器，Yxi 只在独立目录里跑，用隔离的 HOME 和临时钥匙串，不用真实令牌、不用 sudo。机上没有签名身份，Xcode 许可也没同意；公证要等 §10-13，并由用户在机上同意 Xcode 许可。
    - 这台 Mac mini 能不能装参考端、兼做拍参考图的机器，由用户定。
    - CI：GitHub 托管的 macOS 运行器，公开仓库免费；私有仓库按分钟计费，macOS 比 Linux、Windows 贵，以 GitHub 的计费页为准。

## 11. 风险

| 风险 | 影响 | 对策 |
|---|---|---|
| 官方界面更新频繁 | 量好的值过时 | 规格表以 M0 截图时的版本为基准冻结（目前是 2.9939.2），并记下版本号；M5 前按当时的新版复核一次，差异单列 |
| 外观太像，引出商标或条款问题 | 返工、下架 | §2 的品牌区隔；§10-1 先定边界。先例（第三方报道，未核实）：2026-01 Anthropic 要求 Clawdbot 改名，后改为 Moltbot |
| 字体不同（系统字体 vs Anthropic Sans） | 字宽不同，换行位置不同 | 五轮参考图用的都是默认品牌字体，第四、五轮还是英文界面，字宽都不可比；可选再用「系统」字体拍 3～4 张（§9）；不拍就只比几何和基线（§7），换行差异记为已知偏差 |
| Compose / Skia 与 Chromium 渲染差异 | 阴影、抗锯齿对不上 | 只目视（§7） |
| 非整数缩放（125% / 150%） | 1px 分隔线落在半像素上发虚 | 实测参考端在 125% 下的分隔线正好是 1 个物理像素：浏览器内核把宽 w 的 border 画成 max(1, ⌊w × 缩放⌋) 个物理像素。Yxi 的分隔线照这条规则写成 `(max(1f, floor(density)) / density).dp`（100%、200% 时就是 `1.dp`），卡片和浮层的外环用 `1.dp`（规格表 §0.4）。125% 是基准，按规格表比；其他缩放只验布局 |
| 两套外壳 | 维护量翻倍，行为慢慢分叉 | 只在外壳层分叉；业务组件、控制器、状态共用（§4.2） |
| 7 类会话数据形状各异 | 适配器漏字段，Code 风格里显示缺内容 | 每类运行器用真实样例做适配器单测（§5.5） |
| 测试误写用户真实偏好 | 改掉用户的设置 | 夹具只用显式参数重载；写盘逻辑抽成可注入目标文件的函数，单测只写临时目录。M1 已抽成 `PrefStore`；本机跑会构造 `AppState` 的测试时，用 `YXI_SUBSCRIPTION_UI_FIXTURE` 隔离 profile（§4.3） |
| 范围膨胀 | "一模一样"没有终点 | 以 §6 的表为准，新增项先改 PRD 再做 |
| 有 Mac 测包机，没有 Mac 素材 | Mac 上的视觉差异只能按平台习惯推，量不了 | 测包机是 Mac mini（§10-14）；拍参考图的 Mac 定之前，Mac 只验规格表 §7.1 的差异和布局，数值沿用 Windows 的（§7） |
| Mac 上凭据明文 | 数据目录里能直接读到 SSH 密码和账号会话 | 钥匙串做完之前不发 Mac 包（§5.7）。2026-09-29：实现和测试用例都有了，没提交；App 里还没走过真实的登录钥匙串 |
| Temurin 没有自定义标题栏的 API | 红绿灯的位置挪不了；参考端要是挪过，就对不齐 | 用 fullWindowContent + 透明标题栏（规格表 §7.2），对不齐记为已知偏差；非要对齐，换 JetBrains Runtime 或经 JNA 调 AppKit，另议 |
| hardened runtime / 公证拦 JVM | JIT、本机库加载失败，闪退或公证不过 | 从最小的 entitlements 起，按实测逐项加，过了公证、跑通 Mac1 验收再写死进打包脚本（§5.7）。2026-09-29：在 ad-hoc 签名下实测，4 条 entitlements 就够用，其中关库校验去不掉；这 4 条已经先写进了打包脚本，公证还没试 |
| Display P3 | 截图取色偏艳；Yxi 画出来的颜色可能也偏 | 取色前转成 sRGB（规格表 §7.3）；Skia 有没有做色彩管理，在真机上核（规格表 §7.2） |
| 从 Finder 打开时 PATH 不全 | 找不到 Homebrew、nvm 装的 `claude`、`codex`、`node` | 启动时读一次登录 shell 的 PATH，补常见目录（§5.7） |

## 12. 顺手处理的在途问题

这两项原本是 `claude/local-mode` 分支上"本地模式第一阶段"的内容，现在并入本 PRD，不再单独做。

1. **本地工作台侧栏点原生会话没反应**（M1 已修）。b1f44afa 上，`LocalWorkspaceSidebar` 的点击（`LocalWorkspacePane.kt:238`）只设了 `state.page` 并调 `workspace.openThread(thread)`，没有清 `state.localSelectedTaskKey`。之前选过某个任务的话，`LocalWorkspacePane` 开头的提前返回（:29-52）仍然命中，页面停在旧任务上。修法：点击逻辑抽成 `openNativeHistory`（`LocalWorkspacePane.kt`），先把 `localSelectedTaskKey` 置空再读历史；任务本身不停，回任务列表还在。单测是 `NativeHistoryClickTest`。这个问题只读代码确认过，没有实机复现；修好后也还没有实机点过。
2. **本地历史里出现"运行器记录：reasoning"**（随 M3 修）。`NativeHistoryItem` 的兜底分支（:210-217）不认识 reasoning / commandExecution / subAgentActivity，只显示类型名。M3 改用 §5.5 的适配器正常渲染。

## 13. 调研来源

2026-09-27 只读调研。以下链接和版本号是当天抓取的，没有逐条复核；M0 开工前复核关键条目。

- 官方文档：`code.claude.com/docs/en/` 下的 desktop、desktop-quickstart、permission-modes、claude-code-on-the-web、web-quickstart、whats-new。
- 更新日志与公告：`claude.com/docs/cowork/changelog`、`claude.com/blog/claude-code-desktop-redesign`、support.claude.com 的 Release Notes 与外观设置文章。
- 品牌规范：Agent SDK 文档里的 branding guidelines，`code.claude.com/docs/en/agent-sdk/overview#branding-guidelines`（§2-3、§10-1）。
- 开源项目：
  - 1code（Apache-2.0，已归档，与 Code 模式对齐度高）、Nimbalyst（MIT，活跃）：只借功能组织思路，不搬代码和图标。
  - claudecodeui、opcode（AGPL）、CodePilot（BSL-1.1）：许可证限制多，不借代码。
- 工具与字体：Roborazzi（Apache-2.0，§10-8）；Source Serif 4、Noto Serif SC（OFL，原是 §10-7 的衬线候选，已定不用）。
- 社区整理的"暖米色 + clay"令牌与实测不符，不采用（§5）。

2026-09-28 补（没有抓取核实）：

- 用户找到的 Claude Code 相关仓库，按用户的描述分三类，我都没打开（§2-6）：
  - 泄露源码的镜像（两个，其中一个已因 DMCA 删除源码）：专有代码，不看、不用，仓库名不记。
  - clean-room 重写：Kuberwastaken/claurst（Rust，GPL-3.0，自称不含泄露代码）。只看它写的行为说明，不搬代码。
  - 架构分析、源码考古：thtskaran/claude-code-analysis、674019130/learn-real-claude-code。只当线索，结论要在官方文档或本机实测里核实；它们讲的是 CLI，不是 Desktop 的界面。
- Yxi 对接 CLI 的依据：官方文档里的 cli-reference、headless、agent-sdk（路径凭记忆写的，没抓取）加上本机实测。stream-json 的控制协议（`can_use_tool` 等）文档写得少，以后改 `ClaudeControlClient.kt` 时可以参考官方 SDK 的公开仓库，先核它的许可证。
- Mac 打包：Compose Multiplatform 的 native distributions 文档（macOS 签名、公证）、Apple 的公证文档（notarytool）、Velopack 的 macOS 文档、JDK 的 `java.awt.Desktop` / `Taskbar`。Mac1 开工时读，现在都没读。

2026-09-28 抓取核实：

- DevTools 开关：`modelcontextprotocol.io/docs/2026-07-28/tools/debugging` 的「Using Chrome DevTools」。在配置目录建 `developer_settings.json`，内容是 `{"allowDevTools": true}`（Windows 是 `%APPDATA%\Claude\`，macOS 是 `~/Library/Application Support/Claude/`），然后按 Ctrl+Alt+I（macOS 是 ⌘⌥I），会开两个 DevTools 窗口：主内容和标题栏。文档没单独说要不要重启，按它对改配置的说法，完全退出再开（关窗口不算）。本机是 MSIX 安装加第三方网关模式，`%APPDATA%\Claude` 不存在，在用的配置目录是 `%LOCALAPPDATA%\Claude-3p`；文件放哪儿才生效、2.9939.2 还认不认这个开关，都没验证（§2-1、§9）。只用 Elements / Computed，用完删掉这个文件。
- 图片输入：`platform.claude.com/docs/en/build-with-claude/vision`。图片可以用 base64、URL 或 file_id 传；每张不超过 10 MB（直连 API）或 5 MB（Bedrock、Google Cloud），都按 base64 编码后的大小算；一次请求里超过 20 张图（之前轮次重发的也算），每张长边要 ≤2000 px，否则报错；超出模型分辨率上限的图先缩小再看；GIF 只看第一帧；模型读不到图片元数据。`code.claude.com/docs/en/agent-sdk/streaming-vs-single-mode` 的 Image Uploads 示例是文字块在前、base64 图片块在后，Yxi 本机路径（`ClaudeControlClient.kt`）也是这样发的。

## 14. M2 落地记录：有意偏差、文档更正、技术发现（2026-10-15，M2-f）

细节和逐步验证在交接目录（hk13 `/root/src/workspace/yunxi/yxi-handover/`）的 `yxi-交接.md` §9 和 `progress-m2.md`；这里只收结论。

### 14.1 有意偏差（和参照端不同，故意的）

- 侧栏：每台 SSH 主机加一行主机标题（参照端没有主机层）；主机组尾留 6；分组标题不显示成员数；行右端小字是运行器名（不写时间、目录末段）；Codex / OpenCode / ACP 行不写副标题，状态只看圆点；行 ⋮ 浮层按内容宽 200–264，目录项放不下时前省略。
- 会话头：SSH 会话头保留连接状态和「重新连接」，没有标签 chip；本机 / 服务器结构化运行器的会话头不放「返回」、不加标题菜单（改名、置顶、归档在侧栏行的 ⋮ 里）。本机页右端只有「改动」「文件」（本机对齐 P6a / P6b，「文件」直接放图标，服务器页收在 ⋮ 里）。
- 新会话页：提示词回车只建会话、放进新对话的输入框，不直接发出（参照端直接发）；本机首页不选模型，用运行器报的默认模型，建好后在对话页改。
- 输入区：模型和强度共用一个 300 宽浮层（参照端两个）；本机 Claude 改强度要点「应用」，Codex 点了就生效；页脚没有用量环（本机拿不到用量）；运行中右端是停止方块（本机有中断动作，SSH 对话页没有）。
- 排队：画成对话末尾右对齐的气泡，停靠区里固定、不随对话滚动；操作行常显，比参照端多「调整方向」「发送」。
- 自动接回：本机 Codex、ACP、OpenCode 的 Code 页进页面自动接回一次，本机 Claude 在原生历史读好、只有一个匹配运行器时自动恢复一次（本机对齐 P1b / P3 / P4 / P5）；失败只留原因和按钮，不重试。服务器 Codex 页同经典，不自动连接。
- 附件：本机 Codex 只收图片（走快照，每次最多 4 张）；服务器走 SFTP，任意文件。
- 改动 / 文件视图仍是经典组件（同服务器 Codex 页），M3 统一。
- 深色的几处值是推算的（激活底、浮层、强度条轨道 #343434 等），等深色素材再核。
- 右侧面板（M4a）：卡片头部只有关闭，没有参照端的「弹出」「最大化」（Yxi 没有独立窗口）；宽度按规格固定（改动 / 文件 / 预览 1040、只有终端时 578.4），不能拖宽，经典的分栏拖动只留在经典；主区放不下「卡片 + 360 宽的对话」时卡片铺满主区。面板里面的内容（终端、文件、改动、预览、已打开的文件）还是原来的组件，M4 后面几步换外观。
- 右侧面板快捷键（M4e）：同参照端用 Ctrl+Shift+F 开文件面板；Windows 微软拼音默认也用 Ctrl+Shift+F 切简繁，开着中文输入法时会被输入法先吃掉（并切到繁体）。先照参照端，收到反馈再加别的键；会话头 ⋮ 里的「文件」照样能点。
- 设置弹窗（M4q，规格表 §3.7）：对话字号「小 / 大」、对话宽度「中 / 宽」参考端没量过，先取 13 / 16 和 896 / 1024，待量后改 `CodeAppearance.kt` 的两张表；字号是对话内容整体按比例缩放（行高同比，≈18.6 / ≈22.9），参考端是不是也这样没核。开关打开的样子没拍到，取强调蓝 #2A78D6 轨道、白钮在右。导航多了两个组名（账号与连接、其他）和跳页分区右端的「›」；搜索框加了一圈 #E6E6E6 细线。没有界面字体、代码配色、代码字体三项（§6）；「关于」仍是经典的版本与更新组件。
- 进行中的工具摘要行（M3h-3）：只用主色字（参照端录到的最深像素 #0B0B0B），不做参照端「推测」的流光——动效没录到，而且 09-20 的反馈要求去掉循环波纹和呼吸闪烁（design/windows-feedback-20260920.md 第 2 条）。

### 14.2 文档更正

- §8 的 Mac1 行写「没提交」已过时：第一轮在 0ce31da6，macOS CI 任务在 36c2d92a。
- 桌面版参照说「没有右侧 diff 面板」与官方不符（§5.1 已注），以官方为准。
- 深色页面底是 #151515，不是参照里的 #0B0B0B（§5.2 已注）。

### 14.3 技术发现（以后改界面要知道的）

- 桌面端 `TextOverflow.StartEllipsis` 实测按末尾省略，要前省略得自己用 TextMeasurer 二分（CodeKit.startEllipsized）。
- clickable 按下就把焦点拿走，可聚焦 Popup 不清主层焦点：浮层关掉后要自己把焦点还给输入框（CodeFocusReturn / CodeInputArea，M2-a）。
- 一行中文夹拉丁字时回退字体会把行高从 16.0 撑到 16.8：比两张图的字墨高低差 0.8 时，先看语义节点再下结论。
- 指令队列的附件路径必须以 / 开头（全局一份 instructions.json，旧版也读）：Windows 本机图片走快照引用 `/yxi-local-codex-image/…`，发送时再换成真实路径。
- ACP 的 session/load 会先用 session/update 回放整段对话再回包：回放期间事件通道要背压，回包里的配置覆盖回放的旧值。
- OpenCode 的会话存在它自己的数据里，接回 = 重新起服务、按 id 读回；refresh 一开始就把 ready 置 false，界面上看 ready 的地方会闪。
- 离屏截图夹具（ImageComposeScene）在 macOS 上跑：存服务器配置会去问登录钥匙串（会弹框、测试卡死），登录 shell 读 PATH 会起 zsh；夹具里用 fixtureOverride（直通保护）和 LoginShellPath.skipForFixture 绕开，生产不变。Windows 和 Mac 的截图基准各录各的。
- 第一帧时 LaunchedEffect 还没跑：靠「加载中 / 出错」两个状态判断「已经读好」会在第一帧误判，要单独记一个「读成功过」。

