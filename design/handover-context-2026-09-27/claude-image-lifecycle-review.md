# Claude 图片快照生命周期复核

只读当前 LocalClaudeImages / ClaudeConversationPane / State / InstructionQueue / ExitProtection；未删除数据、未改代码。

## 必须先修的真实问题

1. **孤儿图片无限累积**：LocalClaudeImages.capture先写磁盘；页面241行移除只删内存引用。批量capture中第二张失败、总大小超过12MiB、withContext返回前页面取消，先写入的图片均不会进入草稿，也无回收入口。这是可复现磁盘增长，不是假想风险。
2. **清理若只扫描当前队列会删已发送图**：InstructionQueue.pruneCompleted(153-175)会清空terminal条目attachments并旋转备份，但ClaudeTaskController.messages(166)仍持有图片引用；ClaudeSentImages随后依赖store.preview读取快照。terminal!=无引用，不能以“已完成”作为图片可删除条件。
3. **capture去重写入不是全局互斥**：每次capture创建独立DurableFile；其@Synchronized只保护该实例，两个任务同digest可同时通过exists再各写入。内容相同通常不会损坏，但会多余产生.bak并与未来GC竞争。没有锁/租约前不能上线扫描删除。

当前不是bug：同一页面capturingImages阻止发送；enqueue成功后才clear草稿，队列磁盘提交先于草稿移除。页面离开保留已加入state.claudeImageDrafts；捕获协程跟页面scope取消，finally清除ExitProtection操作计数。退出确认已有图片草稿计数。不要为修漏盘破坏这些正确顺序。

## 可实现清理规则

### 第一版（不删任何曾发送图片）

建立独立持久化图片引用账本，按digest记录：draft owners、capture leases、queue refs、everDelivered。capture与账本提交/GC使用同一store级互斥；跨进程若共用目录则文件锁。

- capture开始先创建operation lease；临时文件或新snapshot归该lease；成功后先提交草稿引用，再释放lease。失败/取消只释放lease，不能直接unlink快照（另一任务可能同digest）。
- 页面离开不等于丢弃；不清除已有草稿owner。用户点移除、丢弃并退出才释放草稿owner。若草稿不持久化，启动时清理上次草稿引用前明确采用“上次草稿已丢弃”产品规则；更好的方案为草稿引用持久化，避免意外崩溃丢草稿。
- enqueue保持现在先durable queue后移除draft的顺序；队列提交失败保留draft。beginDelivery之前持久标记everDelivered（保守一点允许误留，不能误删），之后即使Failed/Interrupted/Resolved或内容prune也保留图片。
- GC只选择：未everDelivered、所有队列状态均无引用、所有任务草稿均无引用、无capture/load租约、超过短暂保护期的已知digest。必须扫描所有任务而非当前任务。
- 加载预览/发送读图片持read lease直到读完。GC在锁内重新核对引用，然后移动至同目录隔离区；确认账本提交后再删除。只处理验证过的本store普通文件，未知.bak/.damaged/.tmp先保留并诊断。
- 队列/账本读失败或backup恢复需要核对时暂停GC，不将“读不到引用”视为零引用。

### 后续历史保留策略

用户明确清理历史时才解除everDelivered；需先确认所有实时messages/历史界面不再引用并说明将失去图片预览。不能假设Claude原生JSONL一定已保存完整图片，也不能因为HTTP送出成功就删除快照。要自动回收已发送图，先实现持久化history image refs或证明原生历史可完整恢复图像，随后再按用户保留期回收。现阶段建议everDelivered不自动删除。

## 最小验证

- 两图批次第二张非法、批次总量超限、capture中离开页面：最终无草稿且孤儿可回收；操作计数归零。
- 两任务同图：移除一方不删；capture与GC并发不会返回已删除引用。
- enqueue磁盘失败保留草稿；发送中/投递不明不可删；完成后prune队列仍能查看已发送图。
- 模拟进程崩溃后重开：读取不完整账本不删；真正未引用capture快照可在明确恢复规则后清理。

建议先交付“孤儿capture回收+保留已发送图”闭环；不要先写遍历目录按mtime删除脚本。
