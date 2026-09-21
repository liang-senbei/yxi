# 移动设备插件接入方案

状态：可行性已调研，尚未实现。用户已明确要求对齐 ZCode 的“直接运行模拟器”体验，不能以真机投屏或只连接已有设备替代。

## 目标体验

工作台右侧增加“设备”面板，用户选择设备后可以边聊天、边看应用、边操作。顶部显示设备名称、系统、连接位置和状态，提供安装应用、启动、截图发送到当前对话、日志入口。错误在面板内说明；没有环境时展示安装指引，不展示假的运行画面。

插件入口为“Android 模拟器 → 立即运行”。首次使用由 Yxi 检测环境、展示所需下载与磁盘空间并提供一键准备入口，协助安装 SDK/模拟器/镜像和创建默认虚拟设备；许可证由用户确认，虚拟化或系统重启要求明确说明。准备完成后直接启动模拟器、构建项目、安装并打开应用，后续复用设备一键运行。不要求用户先自己打开 Android Studio 配好 AVD；首次必要的系统安装步骤不等同于以后每次重复配置。

同一对话内支持“把这个应用跑起来”触发上述流程，以及截图、点击、日志和修改后重新运行。Agent 工具注册与可见模拟器必须同时交付，不能以只有 MCP 工具列表作为完成依据。

设备绑定到当前任务，切换任务不将操作发往上一台设备。切换人工操作与 Agent 操作时明确展示当前控制方，断线期间停止发送输入，不积压点击后重放。

## 接入顺序

1. Windows 本地 Android Emulator：首要交付，包含首次环境准备、创建虚拟设备、启动/停止、构建安装运行与画面操作，不限于读取已配置设备。运行环境检查包括 SDK、系统镜像与虚拟化支持。
2. Android 真机：作为补充，ADB 负责设备与安装，scrcpy 用作显示和控制能力的候选。需要额外实现 Yxi 面板的视频显示及输入桥接，现成 scrcpy 窗口不等同于已完成内嵌。
3. 远程 Mac 的 iOS Simulator：由 Mac 上的 Xcode/Simulator 运行，Yxi 提供远程管理、画面和操作入口。Windows/Linux 主机不提供原生 iOS Simulator。
4. Mobile MCP 作为 Agent 设备工具候选，提供截图、读取 UI、点击、滑动和应用管理。MCP 控制工具与面板画面是两个接口，分别接入。

## 运行位置与资源

- 默认模拟器放在用户 Windows 电脑，iOS 放在指定 Mac；hk13 保留现有 Agent/代码职责。
- 远程 Agent 到本地设备需要受控连接桥接，不能把远端安装 MCP 等同于可访问本地手机。
- 不公开暴露 ADB 端口；连接绑定设备宿主和任务。插件状态区分工具安装、设备连接与实际可操作。
- 模拟器按需启动，停止由 Yxi 创建的实例；用户已有实例只连接，不擅自关闭。显示资源占用，避免后台多开。

## 后续验收

单设备连接与截图、手动点击、Agent 点击、安装应用、断线重连、任务切换隔离、关闭面板后资源释放。自动操作必须以指定设备上的真实结果为准；仅 MCP 列出工具不算完成。由测试 agent 执行必要验证并提供人工验收文档。

## 调研来源

- Android Emulator 加速要求：https://developer.android.com/studio/run/emulator-acceleration
- Apple Xcode 与 Simulator：https://developer.apple.com/documentation/safari-developer-tools/installing-xcode-and-simulators
- scrcpy：https://github.com/Genymobile/scrcpy
- Mobile MCP：https://github.com/mobile-next/mobile-mcp
- ZCode 官方插件说明：https://zcode.z.ai/cn/docs/plugin （明确 Android/iOS 插件支持对话内构建运行、安装启动与界面验证；iOS 标注 macOS）。官方市场仓库当前树仅找到这两个插件的图标，未找到其实现目录，不能据此声称可直接复用完整代码：https://github.com/zai-org/zcode-plugins

正式集成时锁定版本并保存对应开源声明；本次未复制第三方实现或再分发 SDK/系统镜像。
