# Windows 1.4.15 候选发布记录

状态：已发布，2026-09-24。公网releases.win.json已返回1.4.15。

冻结源码：e744ba255fb0bbfadd38cb973efd91f0fdfbcf27。GitHub Desktop run35974085873完成成功；同一Windows job内95项工作台回归、打包、普通冒烟、真装真启动、浏览器原生渲染/退出、合成凭据迁移/重开均通过。

产物ID10797747549，ZIP大小925552780，SHA256 77b579a8063fccb3b5a9a1b9cafbda1df7b73d27d79e65cb948e46c8def7ddd0。服务器直连下载通过完整哈希，ZIP CRC、包内Yxi.cfg版本、nupkg大小/哈希、RELEASES SHA1引用均核对。

新包Yxi-1.4.15-full.nupkg大小328785341，SHA256 6093997da90f4fe096e3c64d9be140c7adefdc33f68f7095754997f09162ef18；Setup大小333307325，SHA256 706d0088e67eb582af747cb18c2767703583d83f2fb2fa234b447ca5df91b84c。

发布证据与回退备份：hk13 /root/src/workspace/yunxi/windows-release/1.4.15-run35974085873，deployment-result.json记录published/publicVerified=true。原1.4.14的Setup、RELEASES、JSON已复制校验备份，旧nupkg保留。持发布锁，先不可变新nupkg，再Setup、RELEASES，最后JSON feed，逐个标准公网URL完整GET核对哈希。首轮Python带查询参数校验403，未改线上索引；改标准地址curl后通过。RELEASES编码UTF-8 BOM已正确处理。

本机再次GET正式feed确认1.4.15。此发布没有自动替换或重启用户正在使用的应用；可在应用检查更新或下载安装器。

基于Windows工作台提交5b04b09a建立独立分支codex/windows-release-1.4.15。版本和内置更新说明改为1.4.15，其余源代码保持工作台已验证状态。

验证基线：95项Windows JVM测试、13项JUnit报告反例、Linux分页/恢复界面2项及原生CLI整链1项通过。Windows source4d16d61f已构建且独立配置--smoke通过。这些候选CI检查本轮均已通过；下载到产物本身不作为发布依据。

发布脚本必须传本候选实际commit SHA并核对同一Windows job的必需检查。产物校验、现有下载源备份和最后切换feed已完成。已发布1.4.14包不得覆盖。

本次是阶段更新：Claude本地会话控制/恢复、模型和强度保存、历史分页、本地通用MCP共享登记、已连接本地Claude/ACP定时目标。其余PRD继续，包括ACP官方端点验证、云连接器/远端统一共享、全部运行器历史与附件、后台常驻和Windows完整交互。
