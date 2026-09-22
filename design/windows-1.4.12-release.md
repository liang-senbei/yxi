# Windows 1.4.12 发布记录

状态：已发布（2026-09-22 05:25 UTC）。用户明确要求先上线可用权限配置版本，完整 PRD 继续推进。

最终 CI `35689962187` 成功，安装后启动、浏览器原生预览及凭据验证全部通过。产物 `10677733493`，906,801,148 字节，ZIP CRC 与包摘要校验通过。发布目录 `/root/src/workspace/yunxi/windows-release/1.4.12-run35689962187` 的 deployment-result.json 记录部署。

公开 feed、更新日志均返回 1.4.12，Setup HTTP 200、327,057,393 字节；Windows 本地 Java HttpClient 实测 feed HTTP 200 和版本 1.4.12。Python urllib 的首次公网探测遭 403，随后 curl 与实际更新器同类 Java 客户端通过，不将该失败隐去或当作客户端成功证据。

更新包 SHA-256：`237ce8e205b24956ed20ba2fd1edbb3d1e1adf8f8e926f338ae0662324298d4c`。
Setup SHA-256：`b8077643ffca1996f3687fbabfce393b3e737705ea1e8bf0bb0789f1cabc4cd1`。
下载：https://yxi.keuury.com/desktop/Yxi-win-Setup.exe?v=1.4.12 。

当前冻结提交：`d724032e8433ba8f8ac9e2f7b1da6b4e63f5e306`，分支 `codex/windows-release-1.4.12`。
Windows CI：`35689962187`，https://github.com/liang-senbei/yxi/actions/runs/35689962187 。沿用该任务观察。

前候选 `996a6a4` 的 `35688684779` CI 虽通过，但只读审查发现带启动提示词会话无法一键配置，故未发布。其暂存目录已标记 superseded；不能使用该产物。`d724032` 接受并丢弃已进入历史的初始位置提示词（绝不重发），使用绝对 Python 路径且等待敏感上下文文件被消费、超时清理，并兼容确认页已选中 Yes 的情况。

`run.Q5QWh9` 对 `d724032` 的真实 CLI 整链测试通过（1 test、0 failures/errors/skipped），新增带启动提示词会话的一键配置、请求计数不增加、敏感重启文件无残留断言。1.4.11 回滚备份 `/var/www/yxi/desktop-rollback-1.4.11-before-1.4.12` 已完整校验并保留。

## 本批交付

- 会话权限菜单，实际原生模式核验。
- 未开放完全访问时解释原因，提供当前空闲会话的一键配置重启；保留原 sessionId、cwd、受支持的 argv 和环境，临时传递文件权限 600，运行器启动前删除。
- 新建会话选择启动权限；明确选中 bypass 后识别并处理原生首次确认。
- 审批选项翻译区分单次、持久规则和自动模式；重建会话不沿用旧状态。

## 证据与限制

`ddcb143` 的隔离真实 CLI + 假接口整链 `run.xmP9XV` 成功，覆盖原会话一键重启到 bypass、原消息仍在、没有新增请求，以及新建 bypass 启动确认。冻结提交追加版本与日志、关闭首轮自动回退的默认入口；首轮失败恢复尚未验收，原生回退入口保留，不能据正常路径测试宣称完成。

权限偏好持久化、Codex 权限适配、Windows 完整鼠标交互及其他 PRD 项仍待完成。发布前必须确认 Windows CI 成功、安装启动步骤通过、候选产物校验、备份当前下载源并最后更新 feed；不能用可下载 artifact 代替安装检查。
