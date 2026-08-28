# Yxi 小巧思落地清单（2026-08-25）

调研四路 agent 汇总后，用户拍板：**大赌注先不做**（真·PreToolUse 阻塞审批、连接健康点+自动重连），
**「先别做」跳过**（Wear OS、存疑的钩子事件），**其余 19 项全做**。
纪律：**做一条勾一条**，每条落地并编译通过才算完。

状态图例：`[ ]` 待做 · `[~]` 进行中 · `[x]` 完成（附落地说明）

---

## 第一梯队 —— 直击「瞄一眼→批准/推一把」核心

- [x] **1. 通知栏决策按钮 + 直接回复** ✅ 数字按钮本已在，新增 RemoteInput「回一句」到 needs+done 通知 —— 把「1 Yes / 2 Yes别再问 / 3 No」渲染成通知按钮，点一下用 tmux 发数字；再加自由文本回复框。`watch/EventService.kt`、`watch/AnswerReceiver.kt`、`agent/SessionProbe.kt`
- [x] **2. 罐头回复 / 常用语 chips** ✅ 新 Snippets.kt + SendSheet + 聊天草稿框上方 chip —— 你自己的短语库，一点塞进回复。新 `ui/Snippets.kt` + `SendSheet`（SessionsScreen ~560）+ 聊天回复框
- [x] **3. 忙时一键「停」按钮** ✅ LiveStatus 加「■ 停」pill，发 Escape —— `Live.busy` 时聊天状态词旁给停止键，发 `Escape`（已在 SAFE_KEY 白名单）。`ui/ChatScreen.kt`、`agent/SessionProbe.kt`
- [x] **4. TodoWrite 待办清单卡 + 顶栏「正在做…」** ✅ ToolCards.TodoBody(☐▶☑)+ ChatScreen doingNow 行 —— TodoWrite 渲染成 ☐▶☑，顶栏回显 in_progress 那条。`ui/ToolCards.kt`、`agent/Transcript.kt`、`ui/ChatScreen.kt`
- [x] **5. 上下文吃紧变黄 + 一键 /compact** ✅ 上下文数 ≥15万染 Amber、点发 /compact —— token 过阈值把「上下文 N」染琥珀、点一下 /compact。`ui/ChatScreen.kt`、`agent/Slash.kt`

## 便宜小惊喜（S）

- [x] **6a. 会话卡相对活跃时间** ✅ `ago()`(新 TimeFmt.kt)+ SessionCard 顶行 —— `Session.lastActivity` 渲染成「5 分钟前」。`agent/SessionProbe.kt`、`SessionCard`
- [x] **6b. 完成耗时「刚跑完·13s」** ✅ Live 加 doneFor 字段，Switcher 卡显示 —— `Live.parse` 保留 finish 形态的耗时。`agent/Live.kt`
- [x] **6c. 悬浮卡实时状态词** ✅ Switcher 对原始 peek 跑 Live.parse，卡头显示 ✽状态词 —— 对已取回的 peek 文本跑 `Live.parse` 画状态词。`ui/Switcher.kt`
- [x] **7. 等待计时 + 升级重提醒** ✅ WaitCtx 记 since，escalate() 每分钟查、跨 2/5/10/20/40 分再响 +「已等你 Xm」 —— 卡片「已等你 6 分钟」；超时未答再响一次。`watch/EventService.kt`
- [x] **8. 触感词汇表** ✅ 拆 CH_NEEDS(急促两下)/CH_DONE(轻一下) 两频道，各自 vibrationPattern —— 等审批/报错/完成各自不同震动。`NotificationChannel.setVibrationPattern` + Compose 触感
- [x] **9. 快捷设置磁贴「N 个等你」** ✅ WaitingTile(QS)，读 prefs 已知态，点开 app —— QS 磁贴显示待办数、点进列表。新 `TileService`
- [x] **10. 每会话静音 / 免打扰** ✅ 新 Mute.kt + handle 门禁 + 通知「静音」动作 + 回复 sheet 开关 + 卡片🔕 —— 长按卡片消音某会话。仿 `ui/Pinned.kt` + `EventService` gate
- [x] **11. 每台主机固定强调色** ✅ hostColor(id) 派生稳定色，看板头部色点 —— 每 host 派生一个稳定强调色，看板/通知一致。`ui/theme` + host 色
- [x] **12. 语音回复** ✅ 随 #1 白送：RemoteInput 回复框自带输入法麦克风 —— 靠输入法麦克风（RemoteInput 自带，随 #1 落地）。⚠️ 别用独立识别 API

## 值得投入一点（M）

- [x] **13. 指纹守 risky 审批** ✅ Risky 正则 + 框架 BiometricPrompt(API28+)，聊天内审批命中危险词先验指纹 —— 审批文本命中 rm -rf/force-push/deploy 等要按指纹。`BiometricPrompt` + 正则
- [x] **14. 审批前看 git diff** ✅ PendingCard「看改动」→ fetchGitDiff + DiffSheet(+绿/-红/@@青) —— 等你的卡片一点跑 `git diff` 显红绿改动。新 diff 屏 + SSH
- [x] **15. 常驻「Claude 正在干嘛」实况通知** ✅ statusLoop 每30s数「在跑」，ongoing 显示等你/在跑；已有灵动胶囊 promote —— 一条 ongoing 通知显示当前工具+耗时+进度。`setOngoing` + ProgressStyle（安卓16胶囊降级）
- [x] **16. 分享到某个会话** ✅ ShareActivity：SEND 文字/图片/文件→选会话→送(借盯梢连接或现连) —— 从任意 app 分享报错/截图/文件进指定会话。`shortcuts.xml` share-target + 接收器
- [x] **17. 终端快捷键条** ✅ 已存在 KeyBar.kt：esc/tab/^C/^B(tmux前缀)/方向/… + DPad + 粘滞 Ctrl（比提议更全） —— 键盘上方 Esc/Ctrl(粘滞)/方向/tmux 前缀。`ui`（终端屏）
- [x] **18. 从手机拉起项目会话** ✅ 看板 ＋ 按钮 + NewSessionDialog(最近目录/手打路径)→ tmux new + claude → 进对话 —— 记住最近项目目录，一点 `tmux new + claude`。最近目录 + 拉起
- [x] **19. 桌面小组件** ✅ WaitingWidget(RemoteViews，无新依赖)，读 prefs，服务变更 refresh 推一版 —— 一格一会话按状态染色，点等你的直接批。`androidx.glance`

---

## 收尾
- [x] 全做完（发 code 55 / 0.9.11）：bump 版本、`aapt2` 核 versionCode、`install.sh --publish`、验证公网、更新 `handover.md` + `TROUBLESHOOTING.md`
