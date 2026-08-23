import Foundation

// 测试数据。**每一条都是从真机上原样抠下来的**，没有一条是手写的：
//   · 屏幕 = `tmux capture-pane -p -t cc-iosparse`（我自己起的测试会话，
//     里面只有泡面、hello.py 和 pip install，没有任何私人内容）
//   · 转录 = `~/.claude/projects/…-scratchpad-iosparse/<uuid>.jsonl`
//     和 `…-scratchpad-livetest/<uuid>.jsonl`
//   · Claude Code 版本 **2.1.241**
//
// ⚠️ **手写样例只能证明「解析器认得我以为的格式」。** 这一层的价值恰恰在于
// 格式是实测出来的：脚注有三种形态、`toolUseResult` 会退化成字符串、
// 出队的 operation 换了名字还丢了 content、中文里的省略号长得像状态词……
// 这些没有一条是想出来的。
//
// 换版本以后要重新抠：起一个 tmux 会话跑 claude，照上面两条路径重抓。
enum Fixture {

    /// 真机权限提示 A（`pip install requests`）**整屏**。⚠️ 脚注是 `Esc to cancel · Tab to amend · ctrl+e to explain`，
    /// **没有 `to navigate`** —— 只认 navigate 的规则会把最该认出来的一种整个漏掉（TROUBLESHOOTING #46）。
    static let permA = #"""


✻ Sautéed for 2s

❯ 运行 python3 -c "import time; [time.sleep(1) for _ in range(120)]" 然后回复 DONE。不要放后台。

  Ran 1 shell command

❯ 排队丁：省略号在这…后面还有字

● Background command "Sleep 90 seconds in background" completed (exit code 0)

● DONE

  另外两件：
  - 排队丁收到 —— 省略号在这…后面的字也在。
  - 后台那条 sleep 90 已完成，退出码 0。

✻ Cooked for 2m 7s

❯ 用 Bash 跑：pip install requests

● Installing requests via pip
  ⎿  $ pip install requests

───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 Bash command

   pip install requests
   Install requests via pip

 This command requires approval

 Do you want to proceed?
 ❯ 1. Yes
   2. Yes, and don’t ask again for: pip install *
   3. No

 Esc to cancel · Tab to amend · ctrl+e to explain
"""#

    /// 真机权限提示 B（`pip install flask`）**整屏**。
    /// ⚠️ 跟 A **标题和三个选项一字不差**（`1. Yes / 2. Yes, and don’t ask again for: pip install * / 3. No`），
    /// 只有上面那行命令不同 —— 这正是 #53 说的「只比几号 + 选项文案等于没比」。
    static let permB = #"""


❯ 排队丁：省略号在这…后面还有字

● Background command "Sleep 90 seconds in background" completed (exit code 0)

● DONE

  另外两件：
  - 排队丁收到 —— 省略号在这…后面的字也在。
  - 后台那条 sleep 90 已完成，退出码 0。

✻ Cooked for 2m 7s

❯ 用 Bash 跑：pip install requests

  Ran 1 shell command

✻ Crunched for 21s

❯ 用 Bash 跑：pip install flask

● Installing flask via pip
  ⎿  $ pip install flask

───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 Bash command

   pip install flask
   Install flask via pip

 This command requires approval

 Do you want to proceed?
 ❯ 1. Yes
   2. Yes, and don’t ask again for: pip install *
   3. No

 Esc to cancel · Tab to amend · ctrl+e to explain
"""#

    /// 真机 AskUserQuestion 单选。
    static let askSingle = #"""

❯ 用 Bash 跑一条：rm -rf /tmp/yxi-parse-scratch

  Listed 1 directory

✻ Worked for 21s

❯ 用 AskUserQuestion 工具问我一个单选问题：晚饭吃面还是吃饭。两个选项，直接调工具，不要说别的。
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 ☐ 晚饭

晚饭吃面还是吃饭？

❯ 1. 吃面
     来一碗面
  2. 吃饭
     来一碗饭
  3. Type something.
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  4. Chat about this

Enter to select · ↑/↓ to navigate · Esc to cancel
"""#

    /// 真机 AskUserQuestion 多选，第 3 项已经用 `tmux send-keys 3` 勾上了（`[✔]`）。
    static let askMulti = #"""

✻ Cooked for 2s

❯ 再用 AskUserQuestion 问我一个可多选的问题：配菜加哪些。给四个选项（黑椒牛柳/番茄炒蛋/清炒时蔬/紫菜蛋花汤），multiSelect 打开。直接调工具。
  ⎿  Invalid tool parameters
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
←  ☒ 配菜  ✔ Submit  →

配菜加哪些？

❯ 1. [ ] 黑椒牛柳
  有肉
  2. [ ] 番茄炒蛋
  家常
  3. [✔] 清炒时蔬
  解腻
  4. [ ] 紫菜蛋花汤
  配汤
  5. [ ] Type something
     Submit
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  6. Chat about this

Enter to select · ↑/↓ to navigate · Esc to cancel
"""#

    /// 真机 ExitPlanMode 批准框**整屏**。两个坑同时在这一屏上：
    /// ① 计划正文里就是 `1. 烧水 / 2. 下面 / 3. 出锅`，**紧贴在真选项上面**（#30）；
    /// ② 脚注是 `ctrl+g to edit in VS Code · ~/.claude/plans/….md`，
    ///    **`to cancel` 和 `to navigate` 一个都没有** —— 安卓那版认不出这一屏。
    static let planApproval = #"""
  2. 计时 — 大火烧开后转中小火：溏心 6 分钟 / 半熟 8 分钟 / 全熟 10 分钟。
  3. 过冷水 — 捞出立刻泡冷水 2 分钟，再剥壳。
▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔

  ───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
   Ready to code?

   Here is Claude's plan:
  ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
   煮鸡蛋

   Context

   用户要一份煮鸡蛋的步骤计划（替换掉之前的泡面计划）。

   步骤

   1. 下锅 — 鸡蛋放冷水锅里，水没过蛋一指，加半勺盐（防裂、好剥壳）。
   2. 计时 — 大火烧开后转中小火：溏心 6 分钟 / 半熟 8 分钟 / 全熟 10 分钟。
   3. 过冷水 — 捞出立刻泡冷水 2 分钟，再剥壳。

   验证

   剥壳不粘、切开蛋黄状态符合所选时间即可。
  ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌





  ───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
   Claude has written up a plan and is ready to execute. Would you like to proceed?

   ❯ 1. Yes, and use auto mode
     2. Yes, manually approve edits
     3. Tell Claude what to change
        shift+tab to approve with this feedback

   ctrl+g to edit in VS Code · ~/.claude/plans/lively-honking-widget.md
"""#

    /// 真机「正在忙 + 有排队输入」整屏。状态行 `✶ Embellishing… (…)` 顶格，
    /// 输入框由两条横线夹着，模式行里有 `esc to interrupt`。
    static let busyQueued = #"""

● Write(hello.py)
  ⎿  Wrote 1 line to hello.py
      1 print("hello")

● Update(hello.py)
  ⎿  Added 1 line, removed 1 line
      1 -print("hello")
      1 +print("world")

  Read 1 file, listed 1 directory, ran 2 shell commands

● Explore(Count files in dir)
  ⎿  Backgrounded agent (↓ to manage · ctrl+o to expand)

● 1–4 完成：

  - hello.py 写好 → 改成 print("world") → 读回确认。
  - python3 hello.py → world
  - ls /no/such/dir → 退出码 2，No such file or directory（如预期报错）

  5. Explore 子 agent 已在后台跑，数文件中，结果回来我再报。

✻ Waiting for 1 background agent to finish

❯ 排队甲：这条应该在手机上看得见

✶ Embellishing… (2s · ↓ 1.3k tokens · thinking)
  ⎿  Tip: Use git worktrees to run multiple Claude sessions in parallel.

  ❯ 排队乙：这条也是

───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
❯ Press up to edit queued messages
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  ⏵⏵ auto mode on (shift+tab to cycle) · esc to interrupt · ← for agents · ↓ to manage

  ● main
  ◯ Explore  Count files in dir                                                                                                           5s · ↓ 11.0k tokens
"""#

    /// 真机「正在忙」整屏，而且屏幕上**顶格**有一条中文排队输入 `❯ 排队丙：这条带省略号…后面还有字`。
    /// ⚠️ 它长得跟状态行一模一样（顶格 + 一个符号 + 一段不含空格且带 `…` 的文字）——
    /// 因为**中文不写空格**。见 `LiveTests.中文里的省略号不能当状态词`。
    static let busyCJKEllipsis = #"""


● Agent "Count files in dir" finished · 5s

● 子 agent 回来了：该目录下 1 个文件 —— hello.py。没有子目录、没有隐藏文件。

  五步全部走完。

✻ Brewed for 34s · 1 shell still running

❯ 用 run_in_background 跑 sleep 90，然后等它跑完再回我

  Ran 1 shell command

● 已在后台跑，90 秒。跑完通知我我再回你。

✻ Brewed for 13s · 1 shell still running

❯ 排队丙：这条带省略号…后面还有字

● 收到，排队丙 —— 省略号…后面的字也在。sleep 还在跑。

✻ Sautéed for 2s · 1 shell still running

❯ 运行 python3 -c "import time; [time.sleep(1) for _ in range(120)]" 然后回复 DONE。不要放后台。

  Sleeping 120 seconds in foreground · 26s
  ⎿  $ python3 -c "import time; [time.sleep(1) for _ in range(120)]" (23s)
     (ctrl+b ctrl+b (twice) to run in background)

· Gusting… (29s · ↓ 208 tokens)
  ⎿  Tip: Run /install-slack-app to use Claude in Slack

  ❯ 排队丁：省略号在这…后面还有字

───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
❯ Press up to edit queued messages
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  ⏵⏵ auto mode on · 1 shell · esc to interrupt · ← for agents · ↓ to manage
"""#

    /// 真机空闲整屏。最后一条状态是 `✻ Brewed for 34s`（**收尾形态，没有 `…`**），
    /// 模式行里没有 `esc to interrupt`。
    static let idleScreen = #"""


● Explore(Count files in dir)
  ⎿  Backgrounded agent (↓ to manage · ctrl+o to expand)

● 1–4 完成：

  - hello.py 写好 → 改成 print("world") → 读回确认。
  - python3 hello.py → world
  - ls /no/such/dir → 退出码 2，No such file or directory（如预期报错）

  5. Explore 子 agent 已在后台跑，数文件中，结果回来我再报。

✻ Waiting for 1 background agent to finish

❯ 排队甲：这条应该在手机上看得见

● 收到，排队甲。子 agent 还在跑，完了我一起报。

✻ Waiting for 1 background agent to finish

❯ 排队乙：这条也是

● 收到，排队乙。两条都在。子 agent 仍在跑。

✻ Waiting for 1 background agent to finish

● Agent "Count files in dir" finished · 5s

● 子 agent 回来了：该目录下 1 个文件 —— hello.py。没有子目录、没有隐藏文件。

  五步全部走完。

✻ Brewed for 34s

───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
❯ 清理掉 hello.py
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  ⏵⏵ auto mode on (shift+tab to cycle) · ← for agents
"""#


    /// 真机「正在忙」整屏，而且屏幕上**带缩进**地印着两行长得跟状态行一模一样的东西
    /// （`  · Scampering… (4m 48s · ↓ 10.2k tokens)` / `  ✽ Pondering… (12s)`）——
    /// 我让它把这两行原样回给我，于是它们作为**正文**出现在转录区里。
    /// 真状态行是顶格的 `✻ Reticulating… (…)`。
    static let busyIndentedStatus = #"""

● DONE

  另外两件：
  - 排队丁收到 —— 省略号在这…后面的字也在。
  - 后台那条 sleep 90 已完成，退出码 0。

✻ Cooked for 2m 7s

❯ 用 Bash 跑：pip install requests

  Ran 1 shell command

✻ Crunched for 21s

❯ 用 Bash 跑：pip install flask

  Ran 1 shell command

✻ Baked for 32s

❯ 照做：先原样回我三行（第一行写「样例如下」，第二行写 · Scampering… (4m 48s · ↓ 10.2k tokens)，第三行写 ✽ Pondering… (12s)），然后立刻用 Bash 前台跑 python3
  -c "import time;[time.sleep(1) for _ in range(120)]"，不要放后台。

● 样例如下
  · Scampering… (4m 48s · ↓ 10.2k tokens)
  ✽ Pondering… (12s)

● Sleeping 120 seconds in foreground · 26s
  ⎿  $ python3 -c "import time;[time.sleep(1) for _ in range(120)]" (25s)
     (ctrl+b ctrl+b (twice) to run in background)

✻ Reticulating… (29s · ↓ 177 tokens)
  ⎿  Tip: Use /permissions to pre-approve and pre-deny bash, edit, and MCP tools

───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
❯ 
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  ⏵⏵ auto mode on (shift+tab to cycle) · esc to interrupt · ← for agents
"""#

    /// ⚠️ 真机整屏：正文里就是 `1. 烧水 / 2. 下锅 / 3. 出锅`，紧接着一行
    /// 「计划已存到 ~/.claude/plans/lively-honking-widget.md」。
    /// 这一屏**根本没在等人选**（输入框和模式行都在），但那行路径长得跟计划批准框的脚注一样。
    /// 把它当脚注的话，那三个步骤会变成三个可点的「选项」。
    static let plansPathMention = #"""
● 煮鸡蛋：

  1. 下锅 — 鸡蛋放冷水锅里，水没过蛋一指，加半勺盐（防裂、好剥壳）。
  2. 计时 — 大火烧开后转中小火：溏心 6 分钟 / 半熟 8 分钟 / 全熟 10 分钟。
  3. 过冷水 — 捞出立刻泡冷水 2 分钟，再剥壳。
  ⎿  User rejected Claude's plan:
     ╭──────────────────────────────────────────────────────────────────────────╮
     │ 煮鸡蛋                                                                   │
     │                                                                          │
     │ Context                                                                  │
     │                                                                          │
     │ 用户要一份煮鸡蛋的步骤计划（替换掉之前的泡面计划）。                     │
     │                                                                          │
     │ 步骤                                                                     │
     │                                                                          │
     │ 1. 下锅 — 鸡蛋放冷水锅里，水没过蛋一指，加半勺盐（防裂、好剥壳）。       │
     │ 2. 计时 — 大火烧开后转中小火：溏心 6 分钟 / 半熟 8 分钟 / 全熟 10 分钟。 │
     │ 3. 过冷水 — 捞出立刻泡冷水 2 分钟，再剥壳。                              │
     │                                                                          │
     │ 验证                                                                     │
     │                                                                          │
     │ 剥壳不粘、切开蛋黄状态符合所选时间即可。                                 │
     ╰──────────────────────────────────────────────────────────────────────────╯

✻ Brewed for 39s

❯ 原样回我四行，不要别的：第一行 1. 烧水  第二行 2. 下锅  第三行 3. 出锅  第四行 计划已存到 ~/.claude/plans/lively-honking-widget.md

● 1. 烧水
  2. 下锅
  3. 出锅
     计划已存到 ~/.claude/plans/lively-honking-widget.md

✻ Crunched for 1s

───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
❯ 再原样回我一行：✽ Pondering… (12s)
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  ⏵⏵ auto mode on (shift+tab to cycle) · ← for agents
"""#

    /// ⚠️ 真机整屏：`● 计划如下：` 下面**缩进**列着 `1. 烧水 / 2. 下锅 / 3. 出锅`，
    /// 再下面一行「计划已存到 ~/.claude/plans/lively-honking-widget.md」。
    ///
    /// 这一屏跟 [plansPathMention] 的区别是**那三行是缩进的**，所以它们跟真选项长得
    /// 一模一样（选项本来就缩进）。把那行路径当脚注的话，这三步就会变成三个可点的选项，
    /// 而这个会话**根本没在等人选**。挡住它的只有「弱锚点必须是最后一条非空行」。
    static let plansPathWithList = #"""
     │                                                                          │
     │ 用户要一份煮鸡蛋的步骤计划（替换掉之前的泡面计划）。                     │
     │                                                                          │
     │ 步骤                                                                     │
     │                                                                          │
     │ 1. 下锅 — 鸡蛋放冷水锅里，水没过蛋一指，加半勺盐（防裂、好剥壳）。       │
     │ 2. 计时 — 大火烧开后转中小火：溏心 6 分钟 / 半熟 8 分钟 / 全熟 10 分钟。 │
     │ 3. 过冷水 — 捞出立刻泡冷水 2 分钟，再剥壳。                              │
     │                                                                          │
     │ 验证                                                                     │
     │                                                                          │
     │ 剥壳不粘、切开蛋黄状态符合所选时间即可。                                 │
     ╰──────────────────────────────────────────────────────────────────────────╯

✻ Brewed for 39s

❯ 原样回我四行，不要别的：第一行 1. 烧水  第二行 2. 下锅  第三行 3. 出锅  第四行 计划已存到 ~/.claude/plans/lively-honking-widget.md

● 1. 烧水
  2. 下锅
  3. 出锅
     计划已存到 ~/.claude/plans/lively-honking-widget.md

✻ Crunched for 1s

❯ 原样回我，第一行写「计划如下：」，然后换行缩进列三步 1. 烧水 / 2. 下锅 / 3. 出锅，最后一行写 计划已存到 ~/.claude/plans/lively-honking-widget.md

● 计划如下：
  1. 烧水
  2. 下锅
  3. 出锅
     计划已存到 ~/.claude/plans/lively-honking-widget.md

✻ Cooked for 2s

───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
❯ 
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  ⏵⏵ auto mode on (shift+tab to cycle) · ← for agents
"""#

    /// ⚠️ 真机整屏，**空闲**（模式行里没有 `esc to interrupt`），
    /// 但顶格印着 `● Reticulating… 这是正文不是状态行` —— 一个拉丁词 + `…` + 顶格，
    /// 三条规则全中，可它只是正文。**只有「不忙就不报状态」挡得住它。**
    static let idleTopLevelEllipsis = #"""
     │ 2. 计时 — 大火烧开后转中小火：溏心 6 分钟 / 半熟 8 分钟 / 全熟 10 分钟。 │
     │ 3. 过冷水 — 捞出立刻泡冷水 2 分钟，再剥壳。                              │
     │                                                                          │
     │ 验证                                                                     │
     │                                                                          │
     │ 剥壳不粘、切开蛋黄状态符合所选时间即可。                                 │
     ╰──────────────────────────────────────────────────────────────────────────╯

✻ Brewed for 39s

❯ 原样回我四行，不要别的：第一行 1. 烧水  第二行 2. 下锅  第三行 3. 出锅  第四行 计划已存到 ~/.claude/plans/lively-honking-widget.md

● 1. 烧水
  2. 下锅
  3. 出锅
     计划已存到 ~/.claude/plans/lively-honking-widget.md

✻ Crunched for 1s

❯ 原样回我，第一行写「计划如下：」，然后换行缩进列三步 1. 烧水 / 2. 下锅 / 3. 出锅，最后一行写 计划已存到 ~/.claude/plans/lively-honking-widget.md

● 计划如下：
  1. 烧水
  2. 下锅
  3. 出锅
     计划已存到 ~/.claude/plans/lively-honking-widget.md

✻ Cooked for 2s

❯ 只回我一行，顶格，内容就是：Reticulating… 这是正文不是状态行

● Reticulating… 这是正文不是状态行

✻ Baked for 2s

───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
❯ 现在原样回我一行：✽ Pondering… (12s)
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  ⏵⏵ auto mode on (shift+tab to cycle) · ← for agents
"""#

    /// ⚠️ 真机整屏：用户在**输入框**里打了 `1. 先做这个`，还没发。
    /// 那一行渲染出来是 `❯ 1. 先做这个` —— 跟选择器的**光标行**一模一样
    /// （真机上 `❯` 后面还是个不换行空格 U+00A0，`\s` 照样吃）。
    /// 这个会话根本没在等人选。挡住它的是「选项块至少两行」：
    /// 输入框下面紧跟着就是那条横线，块到此为止。
    static let typedNumberedInInputBox = #"""

╭─ Claude Code ───────────────────────────────────────╮
│                                                     │
│               Welcome back tianliang!               │
│                                                     │
│                       ▐▛███▛█                       │
│                      ▝▜██████▀                      │
│                        ▝▝ ▝▝                        │
│                                                     │
│                 Opus 5 (1M context)                 │
│                     Claude Max                      │
│               /…/scratchpad/iosparse2               │
│                                                     │
╰─────────────────────────────────────────────────────╯











───────────────────────────────────────────────────────
❯ 1. 先做这个
───────────────────────────────────────────────────────
  ⏵⏵ auto mode on (shift+tab to cycle)
"""#

    // MARK: - 转录里的真实行（每行都是原样一行 JSONL）

    /// 排队进队。真机 2.1.241。
    static let enqueueA = #"{"type":"queue-operation","operation":"enqueue","timestamp":"2026-08-23T13:07:07.826Z","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226","content":"排队甲：这条应该在手机上看得见"}"#

    /// 第二条进队。
    static let enqueueB = #"{"type":"queue-operation","operation":"enqueue","timestamp":"2026-08-23T13:07:08.839Z","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226","content":"排队乙：这条也是"}"#

    /// ⚠️ **系统注入的 enqueue** —— 子 agent 跑完时 Claude Code 把一整块
    /// `<task-notification>` 也塞进了排队。照原样显示就是在用户脸上糊内部 XML，
    /// 而且是「你排队的输入」的口吻。
    static let enqueueTaskNotification = #"{"type":"queue-operation","operation":"enqueue","timestamp":"2026-08-23T13:07:12.700Z","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226","content":"<task-notification>\n<task-id>a7dd4f3b8ec5e486c</task-id>\n<tool-use-id>toolu_0197cEYYMvV1Mqvv8PZX89mr</tool-use-id>\n<output-file>/tmp/claude-0/-tmp-claude-0--root-src-workspace-Yxi-d0ccc7db-ab52-458f-807f-39247666d0c2-scratchpad-iosparse/eff0c425-ceb9-41de-b565-c14b6db2a226/tasks/a7dd4f3b8ec5e486c.output</output-file>\n<status>completed</status>\n<summary>Agent \"Count files in dir\" finished</summary>\n<note>A task-notification fires each time this agent stops with no live background children of its own. The user can send it another message and resume it, so the same task-id may notify more than once.</note>\n<result>**Total: 1 file**\n\n- `/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse/hello.py`\n\nNo subdirectories, no hidden files.</result>\n<usage><subagent_tokens>11259</subagent_tokens><tool_uses>1</tool_uses><duration_ms>5965</duration_ms></usage>\n</task-notification>"}"#

    /// ⚠️ **2.1.241 的出队是 `dequeue` 而且不带 `content`** —— 没有任何东西能拿来匹配。
    /// 只认「出队事件」的解析器在这个版本上等于没有出队。
    static let dequeueOp = #"{"type":"queue-operation","operation":"dequeue","timestamp":"2026-08-23T13:07:08.213Z","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226"}"#

    /// 更早的转录里出队是 `remove` + `content`（cc-livetest 会话实测）。两种都真实存在。
    static let removeOpOld = #"{"type":"queue-operation","operation":"remove","timestamp":"2026-08-23T08:27:18.558Z","sessionId":"12d150e1-4e72-4ef7-84ef-2836b5c816a9","content":"排队甲：这条应该在手机上看得见"}"#

    /// 排队甲后来**作为普通 `user` 消息**出现的那一行 —— 它就是 #76 说的「终态」。
    static let userSameTextA = #"{"parentUuid":"0fcca5e2-afbf-4718-8e3a-e77adf368063","isSidechain":false,"promptId":"40c1d204-8502-481a-aa40-89967af0b768","type":"user","message":{"role":"user","content":"排队甲：这条应该在手机上看得见"},"uuid":"7db75345-b1f5-4ea9-8708-b5537eb81fe5","timestamp":"2026-08-23T13:07:08.253Z","permissionMode":"auto","origin":{"kind":"human"},"promptSource":"queued","userType":"external","entrypoint":"cli","cwd":"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226","version":"2.1.241","gitBranch":"HEAD","slug":"lively-honking-widget"}"#

    /// 那条 `<task-notification>` 也是以普通 `user` 消息落的地。
    static let userTaskNotification = #"{"parentUuid":"6564a9d7-bc2d-4e41-8638-263de2ed1350","isSidechain":false,"promptId":"208329b2-f0b4-481d-a4d1-10197ce411ff","type":"user","message":{"role":"user","content":"<task-notification>\n<task-id>a7dd4f3b8ec5e486c</task-id>\n<tool-use-id>toolu_0197cEYYMvV1Mqvv8PZX89mr</tool-use-id>\n<output-file>/tmp/claude-0/-tmp-claude-0--root-src-workspace-Yxi-d0ccc7db-ab52-458f-807f-39247666d0c2-scratchpad-iosparse/eff0c425-ceb9-41de-b565-c14b6db2a226/tasks/a7dd4f3b8ec5e486c.output</output-file>\n<status>completed</status>\n<summary>Agent \"Count files in dir\" finished</summary>\n<note>A task-notification fires each time this agent stops with no live background children of its own. The user can send it another message and resume it, so the same task-id may notify more than once.</note>\n<result>**Total: 1 file**\n\n- `/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse/hello.py`\n\nNo subdirectories, no hidden files.</result>\n<usage><subagent_tokens>11259</subagent_tokens><tool_uses>1</tool_uses><duration_ms>5965</duration_ms></usage>\n</task-notification>"},"uuid":"480fac39-635e-4dec-8fe7-da305bc0e1d1","timestamp":"2026-08-23T13:07:14.936Z","permissionMode":"auto","origin":{"kind":"task-notification"},"promptSource":"system","userType":"external","entrypoint":"cli","cwd":"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226","version":"2.1.241","gitBranch":"HEAD","slug":"lively-honking-widget"}"#

    /// `attachment.queued_command`，`origin.kind == "human"` —— 人打的，要显示成用户消息。
    static let queuedCommandHuman = #"{"parentUuid":"3ea7e479-e65a-4dd8-9348-b6c06d02b8a4","isSidechain":false,"attachment":{"type":"queued_command","prompt":"排队丁：省略号在这…后面还有字","source_uuid":"ad7ecea9-4ff0-4a36-8142-6dade60ef6f2","commandMode":"prompt","origin":{"kind":"human"},"timestamp":"2026-08-23T13:21:42.161Z"},"type":"attachment","uuid":"d88895dc-41a7-44da-b9ba-0cd9d2e4b7d8","timestamp":"2026-08-23T13:21:42.161Z","session_id":"eff0c425-ceb9-41de-b565-c14b6db2a226","userType":"external","entrypoint":"cli","cwd":"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226","version":"2.1.241","gitBranch":"HEAD","slug":"lively-honking-widget"}"#

    /// 同样是 `queued_command`，但 `commandMode == "task-notification"` 且**没有 `origin`**。
    /// 它不能显示成用户消息，**但必须算作「已消化」**，否则那条排队气泡永远不消失。
    static let queuedCommandTaskNotification = #"{"parentUuid":"d88895dc-41a7-44da-b9ba-0cd9d2e4b7d8","isSidechain":false,"attachment":{"type":"queued_command","prompt":"<task-notification>\n<task-id>b05e3geqp</task-id>\n<tool-use-id>toolu_014FGvDUMfEDbUJNrx7LvyxA</tool-use-id>\n<output-file>/tmp/claude-0/-tmp-claude-0--root-src-workspace-Yxi-d0ccc7db-ab52-458f-807f-39247666d0c2-scratchpad-iosparse/eff0c425-ceb9-41de-b565-c14b6db2a226/tasks/b05e3geqp.output</output-file>\n<status>completed</status>\n<summary>Background command \"Sleep 90 seconds in background\" completed (exit code 0)</summary>\n</task-notification>","commandMode":"task-notification","timestamp":"2026-08-23T13:22:18.541Z"},"type":"attachment","uuid":"ba3d5512-1629-4b2b-83e3-d338714385af","timestamp":"2026-08-23T13:22:18.541Z","session_id":"eff0c425-ceb9-41de-b565-c14b6db2a226","userType":"external","entrypoint":"cli","cwd":"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226","version":"2.1.241","gitBranch":"HEAD","slug":"lively-honking-widget"}"#

    /// assistant 的 `tool_use`：Edit。
    static let assistantEdit = #"{"parentUuid":"8a60d9d6-2bba-4124-b5b2-b8ac8bcc169c","isSidechain":false,"message":{"model":"claude-opus-5","id":"msg_011CeKjLUNvUdZcn5qKfKjpe","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_016my7o5usRKr6S4rMuxUC3e","name":"Edit","input":{"replace_all":false,"file_path":"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse/hello.py","old_string":"print(\"hello\")","new_string":"print(\"world\")"},"caller":{"type":"direct"}}],"stop_reason":"tool_use","stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":618,"cache_read_input_tokens":47560,"output_tokens":166,"output_tokens_details":{"thinking_tokens":0},"server_tool_use":{"web_search_requests":0,"web_fetch_requests":0},"service_tier":"standard","cache_creation":{"ephemeral_1h_input_tokens":618,"ephemeral_5m_input_tokens":0},"inference_geo":"not_available","iterations":[{"input_tokens":2,"output_tokens":166,"cache_read_input_tokens":47560,"cache_creation_input_tokens":618,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":618},"type":"message"}],"speed":"standard"},"diagnostics":null},"requestId":"req_011CeKjLTaJYvwZ4irbp3uXw","type":"assistant","uuid":"5d690d65-dee6-4740-822d-2b282232a8e9","timestamp":"2026-08-23T13:06:51.800Z","effort":"high","session_id":"eff0c425-ceb9-41de-b565-c14b6db2a226","userType":"external","entrypoint":"cli","cwd":"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226","version":"2.1.241","gitBranch":"HEAD","slug":"lively-honking-widget"}"#

    /// 对应的 `tool_result` + 顶层 `toolUseResult`，里面有 `structuredPatch`（diff 视图靠它）。
    static let userEditResult = #"{"parentUuid":"5d690d65-dee6-4740-822d-2b282232a8e9","isSidechain":false,"promptId":"e1cecb37-01e4-41dd-9dcc-f3f56d180c37","type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_016my7o5usRKr6S4rMuxUC3e","type":"tool_result","content":"The file /tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse/hello.py has been updated successfully. (file state is current in your context — no need to Read it back)"}]},"uuid":"86a596ab-d354-4653-98c9-629f34236861","timestamp":"2026-08-23T13:06:51.862Z","toolUseResult":{"filePath":"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse/hello.py","oldString":"print(\"hello\")","newString":"print(\"world\")","originalFile":"print(\"hello\")\n","structuredPatch":[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":1,"lines":["-print(\"hello\")","+print(\"world\")"]}],"userModified":false,"replaceAll":false},"sourceToolAssistantUUID":"5d690d65-dee6-4740-822d-2b282232a8e9","session_id":"eff0c425-ceb9-41de-b565-c14b6db2a226","userType":"external","entrypoint":"cli","cwd":"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226","version":"2.1.241","gitBranch":"HEAD","slug":"lively-honking-widget"}"#

    /// assistant 的 `tool_use`：Bash（`python3 hello.py`）。
    static let assistantBashOk = #"{"parentUuid":"b6a4f80e-34c8-452c-89b1-b3e57b319162","isSidechain":false,"message":{"model":"claude-opus-5","id":"msg_011CeKjLnqS1YdrASJYdi3nH","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_01B3pZnUxpcCAWqrsBtFzXzW","name":"Bash","input":{"command":"python3 /tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse/hello.py","description":"Run hello.py"},"caller":{"type":"direct"}}],"stop_reason":"tool_use","stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":150,"cache_read_input_tokens":48489,"output_tokens":140,"output_tokens_details":{"thinking_tokens":0},"server_tool_use":{"web_search_requests":0,"web_fetch_requests":0},"service_tier":"standard","cache_creation":{"ephemeral_1h_input_tokens":150,"ephemeral_5m_input_tokens":0},"inference_geo":"not_available","iterations":[{"input_tokens":2,"output_tokens":140,"cache_read_input_tokens":48489,"cache_creation_input_tokens":150,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":150},"type":"message"}],"speed":"standard"},"diagnostics":null},"requestId":"req_011CeKjLmyqNor4KP7KF7V7m","type":"assistant","uuid":"8f1fa86f-256b-431e-bab7-f0cc6a75c826","timestamp":"2026-08-23T13:06:55.920Z","effort":"high","session_id":"eff0c425-ceb9-41de-b565-c14b6db2a226","userType":"external","entrypoint":"cli","cwd":"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226","version":"2.1.241","gitBranch":"HEAD","slug":"lively-honking-widget"}"#

    /// Bash 的 `toolUseResult`：**stdout / stderr 是分开的两个字段**。
    static let userBashOkResult = #"{"parentUuid":"8f1fa86f-256b-431e-bab7-f0cc6a75c826","isSidechain":false,"promptId":"e1cecb37-01e4-41dd-9dcc-f3f56d180c37","type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_01B3pZnUxpcCAWqrsBtFzXzW","type":"tool_result","content":"world","is_error":false}]},"uuid":"0500a39c-e3b2-47b2-b566-3f44a668f7a4","timestamp":"2026-08-23T13:06:58.092Z","toolUseResult":{"stdout":"world","stderr":"","interrupted":false,"isImage":false,"noOutputExpected":false},"sourceToolAssistantUUID":"8f1fa86f-256b-431e-bab7-f0cc6a75c826","session_id":"eff0c425-ceb9-41de-b565-c14b6db2a226","userType":"external","entrypoint":"cli","cwd":"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226","version":"2.1.241","gitBranch":"HEAD","slug":"lively-honking-widget"}"#

    /// assistant 的 `tool_use`：Bash（`ls /no/such/dir`）。
    static let assistantBashErr = #"{"parentUuid":"d0a4eb1f-f82c-431c-9ade-4ea206fb241d","isSidechain":false,"message":{"model":"claude-opus-5","id":"msg_011CeKjM5waK7nS95XqPBLa5","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_01MndiDCw9wjkxWUKBcyptXE","name":"Bash","input":{"command":"ls /no/such/dir","description":"List a nonexistent directory (expected to fail)"},"caller":{"type":"direct"}}],"stop_reason":"tool_use","stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":170,"cache_read_input_tokens":48639,"output_tokens":91,"output_tokens_details":{"thinking_tokens":0},"server_tool_use":{"web_search_requests":0,"web_fetch_requests":0},"service_tier":"standard","cache_creation":{"ephemeral_1h_input_tokens":170,"ephemeral_5m_input_tokens":0},"inference_geo":"not_available","iterations":[{"input_tokens":2,"output_tokens":91,"cache_read_input_tokens":48639,"cache_creation_input_tokens":170,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":170},"type":"message"}],"speed":"standard"},"diagnostics":null},"requestId":"req_011CeKjM4zmjMNQNXZaq48Y8","type":"assistant","uuid":"76796587-4731-4600-8aec-7a2cc8a2bb01","timestamp":"2026-08-23T13:06:59.718Z","effort":"high","session_id":"eff0c425-ceb9-41de-b565-c14b6db2a226","userType":"external","entrypoint":"cli","cwd":"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226","version":"2.1.241","gitBranch":"HEAD","slug":"lively-honking-widget"}"#

    /// ⚠️ 这条的 `toolUseResult` 是**一个字符串**不是对象。
    /// 当成对象取字段会静默取到空 —— 富渲染必须有降级。
    static let userBashErrResult = #"{"parentUuid":"76796587-4731-4600-8aec-7a2cc8a2bb01","isSidechain":false,"promptId":"e1cecb37-01e4-41dd-9dcc-f3f56d180c37","type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"Exit code 2\nls: cannot access '/no/such/dir': No such file or directory","is_error":true,"tool_use_id":"toolu_01MndiDCw9wjkxWUKBcyptXE"}]},"uuid":"95220a6b-ee2f-4eb6-b8bd-2d719eaba834","timestamp":"2026-08-23T13:07:01.166Z","toolUseResult":"Error: Exit code 2\nls: cannot access '/no/such/dir': No such file or directory","sourceToolAssistantUUID":"76796587-4731-4600-8aec-7a2cc8a2bb01","session_id":"eff0c425-ceb9-41de-b565-c14b6db2a226","userType":"external","entrypoint":"cli","cwd":"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226","version":"2.1.241","gitBranch":"HEAD","slug":"lively-honking-widget"}"#

    /// assistant 的 `tool_use`：AskUserQuestion。
    static let assistantAsk = #"{"parentUuid":"ef2a445d-6f58-48f9-b5b0-890327c01bde","isSidechain":false,"message":{"model":"claude-opus-5","id":"msg_011CeKiVnxFtVAe45oBv1MQL","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_01G5bP3oj983s7cWkxbNE1EA","name":"AskUserQuestion","input":{"questions":[{"question":"晚饭吃面还是吃饭？","header":"晚饭","multiSelect":false,"options":[{"label":"吃面","description":"来一碗面"},{"label":"吃饭","description":"来一碗饭"}]}]},"caller":{"type":"direct"}}],"stop_reason":"tool_use","stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":476,"cache_read_input_tokens":41216,"output_tokens":136,"output_tokens_details":{"thinking_tokens":0},"server_tool_use":{"web_search_requests":0,"web_fetch_requests":0},"service_tier":"standard","cache_creation":{"ephemeral_1h_input_tokens":476,"ephemeral_5m_input_tokens":0},"inference_geo":"not_available","iterations":[{"input_tokens":2,"output_tokens":136,"cache_read_input_tokens":41216,"cache_creation_input_tokens":476,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":476},"type":"message"}],"speed":"standard"},"diagnostics":null},"requestId":"req_011CeKiVnE6vevZoazz9QgRX","type":"assistant","uuid":"3708d9b2-300c-433e-a590-8afd41374506","timestamp":"2026-08-23T12:55:51.391Z","effort":"high","session_id":"eff0c425-ceb9-41de-b565-c14b6db2a226","userType":"external","entrypoint":"cli","cwd":"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226","version":"2.1.241","gitBranch":"HEAD"}"#

    /// AskUserQuestion 的 `toolUseResult`，里面有 `answers`（选项卡片渲染靠它）。
    static let userAskResult = #"{"parentUuid":"3708d9b2-300c-433e-a590-8afd41374506","isSidechain":false,"promptId":"c6d34750-9b83-4b1f-94be-a09b440f8e83","type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"Your questions have been answered: \"晚饭吃面还是吃饭？\"=\"吃面\". You can now continue with these answers in mind.","tool_use_id":"toolu_01G5bP3oj983s7cWkxbNE1EA"}]},"uuid":"ce4fd030-a311-4a2a-8b6d-bc854bbbe124","timestamp":"2026-08-23T12:56:23.381Z","toolUseResult":{"questions":[{"question":"晚饭吃面还是吃饭？","header":"晚饭","options":[{"label":"吃面","description":"来一碗面"},{"label":"吃饭","description":"来一碗饭"}],"multiSelect":false}],"answers":{"晚饭吃面还是吃饭？":"吃面"},"annotations":{}},"sourceToolAssistantUUID":"3708d9b2-300c-433e-a590-8afd41374506","session_id":"eff0c425-ceb9-41de-b565-c14b6db2a226","userType":"external","entrypoint":"cli","cwd":"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226","version":"2.1.241","gitBranch":"HEAD"}"#

    /// ⚠️ 真机 2.1.241 的 `thinking` 块：**`thinking` 字段是空串**，只剩加密的 `signature`。
    /// 本机全部转录里 4329 个 thinking 块，非空的 0 个。
    static let assistantThinkingEmpty = #"{"parentUuid":"b25eb4e0-ec5d-4bdd-bc12-fa8dbc6a8d9b","isSidechain":false,"message":{"model":"claude-opus-5","id":"msg_011CeKiTkgn3ZYix6NP867op","type":"message","role":"assistant","content":[{"type":"thinking","thinking":"","signature":"CAISlQUKpgEIERgCKkBOhwXn6w4Ornicj/Y+kqDM7iM88VBpShtK7PHcPf6jrFBUOQCLlSNAPtuNI/qeMPiJe/IJdStWgahw3890G0gXMg1jbGF1ZGUtb3B1cy01OAFCCHRoaW5raW5nWiRjZmYyZTA1Yy1hMzk1LTQ0NzYtYTM5MS0yOTkyOTBhZmQ3N2NyEMPSLs+AwXrMa0zgBRjGCQmIAQGoAbzbq9QGsAECEgweRRVlhhIoyM40diQaDFYwBpsNtz/iSmsQxiIwP/crp5C8o3zHYgs5bOOXX9ORBBrzP24FV7Q6ItKcyMoYu2ynJaRCJ04HlMBypmG1KpsDY29REyhd7D3SNNCN2xcRo6qg4NaXJW85kVfbUn0omR+os7o8x6GwH+liLfNkDcN7VHwZbQ0P2nMdhgatEgjcggWIEjcXLV74YPgyeqiGezhW7jbUILHtPNfXhJP11nHrNoLXJIZGM4YGJs0Zn9rWmBFqb2cF/uphsxYeHybEHLors07YuzcZUxA5IjFjAyT1z7zjB/DF0irJcZ+DNeY6Oj+LgifWvX7Vi3q/DNaQvIstenYclU4gGatdNMDqepjI2lgrP7YT+KMrdEEIDf53fB4iLe02qyC0UfVDqvura+O5fnkSWXFT0v0WdgAUZf4cKxmwDp/D6qaYusEdxFbSAH/XpwOjHmo/9hT9wEX92cWC1sh2tHnOjV9O+hkRyZf9gqWodMdMPiR3M9M4bNhUdvVdqKZvaBnU5IMbgKUEWbeTVVKLsTOFtlBYco6N7MT1G0L3jgBH0//yE4bAKC+v187KVNGpwu7jCQsIg3jiJdzJQIIyModRPSSPlcrp2v6qKRGpGaNyaxs6rnfncrw0sEV/IbRcVpofKywhGAE="}],"stop_reason":"tool_use","stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":307,"cache_read_input_tokens":40909,"output_tokens":236,"output_tokens_details":{"thinking_tokens":129},"server_tool_use":{"web_search_requests":0,"web_fetch_requests":0},"service_tier":"standard","cache_creation":{"ephemeral_1h_input_tokens":307,"ephemeral_5m_input_tokens":0},"inference_geo":"not_available","iterations":[{"input_tokens":2,"output_tokens":236,"cache_read_input_tokens":40909,"cache_creation_input_tokens":307,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":307},"type":"message"}],"speed":"standard"},"diagnostics":null},"requestId":"req_011CeKiTjxsDzVkAzLhjANfs","type":"assistant","uuid":"10b83c19-c5c9-48d0-a639-3b46275bdf44","timestamp":"2026-08-23T12:55:24.788Z","effort":"high","session_id":"eff0c425-ceb9-41de-b565-c14b6db2a226","userType":"external","entrypoint":"cli","cwd":"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226","version":"2.1.241","gitBranch":"HEAD"}"#

    /// assistant 的普通 `text` 块。
    static let assistantText = #"{"parentUuid":"814ded5d-c0a7-4554-bcdf-e95e06090c5a","isSidechain":false,"message":{"model":"claude-opus-5","id":"msg_011CeKiYJNt1MYU5ozeAKBhV","type":"message","role":"assistant","content":[{"type":"text","text":"吃面。"}],"stop_reason":"end_turn","stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":274,"cache_read_input_tokens":41692,"output_tokens":6,"output_tokens_details":{"thinking_tokens":0},"server_tool_use":{"web_search_requests":0,"web_fetch_requests":0},"service_tier":"standard","cache_creation":{"ephemeral_1h_input_tokens":274,"ephemeral_5m_input_tokens":0},"inference_geo":"not_available","iterations":[{"input_tokens":2,"output_tokens":6,"cache_read_input_tokens":41692,"cache_creation_input_tokens":274,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":274},"type":"message"}],"speed":"standard"},"diagnostics":null},"requestId":"req_011CeKiYHbVzhwfjShhGaJUt","type":"assistant","uuid":"45fc016f-32f9-4996-a8b9-aaed3ba1aae2","timestamp":"2026-08-23T12:56:24.274Z","effort":"high","session_id":"eff0c425-ceb9-41de-b565-c14b6db2a226","userType":"external","entrypoint":"cli","cwd":"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226","version":"2.1.241","gitBranch":"HEAD"}"#

    /// 非消息行（`mode`），没有 `message` 字段，要安静跳过。
    static let modeLine = #"{"type":"mode","mode":"normal","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226"}"#


    /// ⚠️ 真实的**侧链**行（子 agent 的内部独白），`isSidechain: true`。
    /// 2.1.241 起它们另存到 `<会话uuid>/subagents/*.jsonl`，主文件里已经没有了；
    /// 这条就是从那个子目录里抠的。**老转录里它们混在主文件里** ——
    /// 不过滤的话主线会被子 agent 的独白淹掉。
    static let sidechainLine = #"{"parentUuid":"178bdf9c-1905-4ba1-9dec-768fa3c2278d","isSidechain":true,"agentId":"a7dd4f3b8ec5e486c","message":{"model":"claude-opus-5","id":"msg_011CeKjMdNSh3CKs1UrvPUKk","type":"message","role":"assistant","content":[{"type":"text","text":"I'll count the files."}],"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":11041,"cache_read_input_tokens":0,"cache_creation":{"ephemeral_5m_input_tokens":11041,"ephemeral_1h_input_tokens":0},"output_tokens":1,"service_tier":"standard","inference_geo":"not_available"},"diagnostics":null},"requestId":"req_011CeKjMcc49h1BU9hucmvvJ","attributionAgent":"Explore","type":"assistant","uuid":"086ab7af-cc59-456c-a2db-8363d44d11c1","timestamp":"2026-08-23T13:07:07.012Z","effort":"high","userType":"external","entrypoint":"cli","cwd":"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse","sessionId":"eff0c425-ceb9-41de-b565-c14b6db2a226","version":"2.1.241","gitBranch":"HEAD","slug":"lively-honking-widget"}"#

    // MARK: - 一次往返的快照、用量、更新清单（都是真机上跑出来的原样输出）

    /// 本机真跑一遍 `SessionProbe.snapshotScript` 的原样输出。
    /// ⚠️ 里面有 `~/.cloud-status/` 下内容是 `{}` 的文件、有 tmux 早就没了的会话、
    /// 还有中文会话名 —— 三种都得安静处理。
    static let snapshot = #"""
__YXI_SNAPSHOT_V1__	tmux_begin
cc-Anthropic-Inspector|1|1787250544|0|/root/src/workspace/Anthropic-Inspector
cc-Yxi|1|1787489781|1|/root/src/workspace/Yxi
cc-claude_desktop|1|1787489234|0|/root/src/workspace/claude_desktop
cc-iosparse|1|1787489661|0|/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/iosparse
cc-livetest|1|1787480771|0|/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/livetest
cc-mail|1|1787480873|1|/root/src/workspace/mail
cc-nanobanana|1|1787456125|0|/root/src/workspace/nanobanana
cc-perm|1|1787464804|0|/tmp
cc-plantest|1|1787491226|0|/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/plantest
cc-probe|1|1787464826|0|/tmp
cc-remote-dev-station|1|1787427118|0|/root/src/workspace/remote-dev-station
cc-root|1|1787415593|0|/root
cc-stale|1|1787459698|0|/tmp
cc-taobao|1|1787431879|0|/root/src/workspace/taobao
cc-yxi-failclosed|1|1787490328|0|/tmp
cc-文件|1|1786706484|0|/opt/workspace/文件
cc-文件传|1|1786708250|0|/opt/workspace/文件传
cc-日常对话|1|1786723204|0|/opt/workspace/日常对话
cc-智商题|1|1786728540|0|/opt/workspace/智商题
cc-环境|1|1786720848|0|/opt/workspace/环境
cc-诗歌|1|1786686019|0|/opt/workspace/诗歌
cc-项目|1|1786863248|0|/opt/workspace/项目
yxi-probe|1|1787462217|0|/tmp
yxi-status|1|1787473376|0|/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/probe
__YXI_SNAPSHOT_V1__	tmux_end
__YXI_SNAPSHOT_V1__	status_begin
{}
{"state": "work", "event": "SubagentStop", "detail": "子 agent 完成,继续", "ts": 1787249626.5064092, "session": "cc-Anthropic-Inspector"}
{"state": "work", "event": "PreToolUse", "detail": "运行命令: S=/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-a", "ts": 1787491651.099762, "session": "cc-Yxi"}
{"state": "input", "event": "Notification", "detail": "Claude is waiting for your input", "ts": 1787145103.3937845, "session": "cc-claude_desktop"}
{"state": "input", "event": "Notification", "detail": "Claude is waiting for your input", "ts": 1787491462.1772363, "session": "cc-iosparse"}
{"state": "input", "event": "Notification", "detail": "Claude is waiting for your input", "ts": 1787473699.993524, "session": "cc-livetest"}
{"state": "input", "event": "Notification", "detail": "Claude is waiting for your input", "ts": 1787470332.6806324, "session": "cc-mail"}
{"state": "work", "event": "SubagentStop", "detail": "子 agent 完成,继续", "ts": 1787459514.3440313, "session": "cc-nanobanana"}
{"state": "idle", "event": "SessionStart", "detail": "会话已启动", "ts": 1787459525.199542, "session": "cc-perm"}
{"state": "idle", "event": "SessionStart", "detail": "会话已启动", "ts": 1787491242.1348534, "session": "cc-plantest"}
{"state": "idle", "event": "SessionStart", "detail": "会话已启动", "ts": 1787459541.199688, "session": "cc-probe"}
{"state": "idle", "event": "SessionStart", "detail": "会话已启动", "ts": 1786640529.33351, "session": "cc-remote-dev-station-remote"}
{"state": "work", "event": "SubagentStop", "detail": "子 agent 完成,继续", "ts": 1787428091.2952125, "session": "cc-remote-dev-station"}
{"state": "idle", "event": "SessionStart", "detail": "会话已启动", "ts": 1787062752.865583, "session": "cc-root"}
{"state": "input", "event": "Notification", "detail": "Claude needs your permission", "ts": 1787460235.6976924, "session": "cc-stale"}
{"state": "input", "event": "Notification", "detail": "Claude is waiting for your input", "ts": 1787417654.446621, "session": "cc-taobao"}
{"state": "input", "event": "Notification", "detail": "Claude is waiting for your input", "ts": 1787238638.1128569, "session": "cc-tmp-035858-6849"}
{"state": "work", "event": "PreToolUse", "detail": "运行命令: ssh -o ConnectTimeout=15 -o BatchMode=yes 192.220.", "ts": 1786608715.263262, "session": "cc-tmp-080855-27745"}
{"state": "input", "event": "Notification", "detail": "Claude is waiting for your input", "ts": 1786878059.1387148, "session": "cc-tmp-105338-30897"}
{"state": "input", "event": "Notification", "detail": "Claude is waiting for your input", "ts": 1786538761.3452072, "session": "cc-tmp-123808-27850"}
{"state": "work", "event": "SubagentStop", "detail": "子 agent 完成,继续", "ts": 1786640460.4350305, "session": "cc-tmp-164230-20590"}
{"state": "input", "event": "Notification", "detail": "Claude is waiting for your input", "ts": 1787250650.4550118, "session": "cc-tmp-180110-8869"}
{"state": "idle", "event": "SessionStart", "detail": "会话已启动", "ts": 1787490335.191796, "session": "cc-yxi-failclosed"}
{"state": "input", "event": "Notification", "detail": "Claude needs your permission", "ts": 1786706073.4020746, "session": "cc-文件"}
{"state": "work", "event": "SubagentStop", "detail": "子 agent 完成,继续", "ts": 1786708885.7464247, "session": "cc-文件传"}
{"state": "work", "event": "SubagentStop", "detail": "子 agent 完成,继续", "ts": 1786693275.3953142, "session": "cc-日常对话"}
{"state": "work", "event": "SubagentStop", "detail": "子 agent 完成,继续", "ts": 1786728688.3397293, "session": "cc-智商题"}
{"state": "work", "event": "SubagentStop", "detail": "子 agent 完成,继续", "ts": 1786721015.6617103, "session": "cc-环境"}
{"state": "idle", "event": "SessionStart", "detail": "会话已启动", "ts": 1786685729.6845877, "session": "cc-诗歌"}
{"state": "work", "event": "SubagentStop", "detail": "子 agent 完成,继续", "ts": 1786863425.134304, "session": "cc-项目"}
{"state": "idle", "event": "SessionStart", "detail": "会话已启动", "ts": 1787462219.6906273, "session": "yxi-probe"}
{"state": "idle", "event": "SessionStart", "detail": "会话已启动", "ts": 1787473378.181004, "session": "yxi-status"}
__YXI_SNAPSHOT_V1__	status_end
"""#

    /// 本机真实的 `ccusage blocks --active --json`（有 `projection`）。
    static let ccusageActive = #"""
{
  "blocks": [
    {
      "actualEndTime": "2026-08-23T13:27:31.022Z",
      "burnRate": {
        "costPerHour": 89.17584262704776,
        "tokensPerMinute": 1827822.0671129117,
        "tokensPerMinuteForIndicator": 9445.118460454465
      },
      "costUSD": 90.00022375000002,
      "endTime": "2026-08-23T17:00:00.000Z",
      "entries": 444,
      "id": "2026-08-23T12:00:00.000Z",
      "isActive": true,
      "isGap": false,
      "models": [
        "claude-opus-5"
      ],
      "projection": {
        "remainingMinutes": 212,
        "totalCost": 405.09,
        "totalTokens": 498181434
      },
      "startTime": "2026-08-23T12:00:00.000Z",
      "tokenCounts": {
        "cacheCreationInputTokens": 3160940,
        "cacheReadInputTokens": 106950270,
        "inputTokens": 888,
        "outputTokens": 571058
      },
      "totalTokens": 110683156
    }
  ]
}
"""#

    /// station 上真实的输出：当前 5 小时窗口空闲 —— `{"blocks": []}`。
    /// 不是错误，但**没有任何可显示的数字**，必须整块藏掉。
    static let ccusageEmpty = #"""
{
  "blocks": []
}
"""#

    /// station 上真实的历史块：`projection` 和 `burnRate` 都是 **null**（不是缺字段）。
    static let ccusageInactive = #"""
{
  "blocks": [
    {
      "actualEndTime": "2026-08-20T19:59:48.561Z",
      "burnRate": null,
      "costUSD": 1.7752280000000003,
      "endTime": "2026-08-20T20:00:00.000Z",
      "entries": 10,
      "id": "2026-08-20T15:00:00.000Z",
      "isActive": false,
      "isGap": false,
      "models": [
        "claude-fable-5"
      ],
      "projection": null,
      "startTime": "2026-08-20T15:00:00.000Z",
      "tokenCounts": {
        "cacheCreationInputTokens": 55966,
        "cacheReadInputTokens": 386308,
        "inputTokens": 20,
        "outputTokens": 5388
      },
      "totalTokens": 447682
    }
  ]
}
"""#

    /// 服务器上真实的 `~/.yxi/latest.json`。
    static let latestJson = #"""
{
 "versionCode": 15,
 "versionName": "0.5.6",
 "file": "Yxi.apk",
 "notes": "修：计划批准框在手机上认不出来（批不了计划）"
}
"""#

}
