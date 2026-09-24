# Windows 1.4.15 候选发布记录

状态：候选构建准备中，尚未更新正式下载源。

基于Windows工作台提交5b04b09a建立独立分支codex/windows-release-1.4.15。版本和内置更新说明改为1.4.15，其余源代码保持工作台已验证状态。

验证基线：95项Windows JVM测试、13项JUnit报告反例、Linux分页/恢复界面2项及原生CLI整链1项通过。Windows source4d16d61f已构建且独立配置--smoke通过。候选必须重新通过Windows CI的Workbench core regression、安装后启动、浏览器与合成凭据验证，才可上传；下载到产物不等于可发布。

发布脚本必须传本候选实际commit SHA并核对同一Windows job的必需检查。产物校验、现有下载源备份和最后切换feed仍待进行。已发布1.4.14包不得覆盖。

本次是阶段更新：Claude本地会话控制/恢复、模型和强度保存、历史分页、本地通用MCP共享登记、已连接本地Claude/ACP定时目标。其余PRD继续，包括ACP官方端点验证、云连接器/远端统一共享、全部运行器历史与附件、后台常驻和Windows完整交互。
