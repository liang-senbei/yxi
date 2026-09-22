# 附件引用边界修复

合入 hk13 agent 的独立提交 `b9291de`（本地 cherry-pick `2ac492e`），并以 `fd0753b` 补齐标点后直接接英文的案例。

- 删除附件后，ASCII 逗号、冒号、括号等旁边的引用会删除或重新编号；句点紧跟文件扩展名仍保留为普通文本。
- 候选列表中的 Shift+Enter 交还输入框换行；普通 Enter 和小键盘 Enter 仍确认候选。
- 保持方向键环绕、Esc 关闭、IME 组字期间不拦截，以及未知附件编号原样保留。

隔离编译 `run.cfdoxs` 成功；`run.OdTbhO` 的 AttachmentMentionsTest 共 10 tests、0 failures/errors/skipped。属于逻辑与回调验证，未声称完成 Windows 实际按键/UI 验收。

尚未发布。线上保持 1.4.12；回退编辑中删除图片后的文本引用同步等其他问题继续处理。
