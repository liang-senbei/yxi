# 移动设备插件接入方案

状态：可行性已调研，尚未实现。补充主 PRD 的插件与右侧预览能力；本轮优先完成现有桌面对话修复，不因这项调研安装模拟器或启动额外服务。

## 目标体验

工作台右侧增加“设备”面板，用户选择设备后可以边聊天、边看应用、边操作。顶部显示设备名称、系统、连接位置和状态，提供安装应用、启动、截图发送到当前对话、日志入口。错误在面板内说明；没有环境时展示安装指引，不展示假的运行画面。

设备绑定到当前任务，切换任务不将操作发往上一台设备。切换人工操作与 Agent 操作时明确展示当前控制方，断线期间停止发送输入，不积压点击后重放。

## 接入顺序

1. Android 真机：ADB 负责设备与安装，scrcpy 用作显示和控制能力的候选。需要额外实现 Yxi 面板的视频显示及输入桥接，现成 scrcpy 窗口不等同于已完成内嵌。
2. Windows 本地 Android Emulator：读取已配置的设备，按需启动/停止，保留 ADB 安装、日志能力。运行环境检查包括 SDK、系统镜像与虚拟化支持。
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

正式集成时锁定版本并保存对应开源声明；本次未复制第三方实现或再分发 SDK/系统镜像。
