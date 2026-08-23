package app.yxi.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ 这些都是**真实抓下来的屏幕**，不是手写的样例。
 * 手写样例只会证明「解析器认得我以为的格式」—— 而这一整块的价值
 * 恰恰在于格式是实测出来的（状态行顶格、输入框由两条横线夹着、
 * 排队的输入和已处理的输入在屏幕上长得一模一样）。
 */
class LiveTest {

    @Test fun 忙的时候认出状态词() {
        val live = Live.parse(BUSY_QUEUED)
        assertTrue("脚注里有 esc to interrupt 就是在忙", live.busy)
        assertTrue("状态词=" + live.status, live.status.orEmpty().startsWith("Scampering…"))
    }

    @Test fun 空闲时不报状态() {
        val live = Live.parse(IDLE)
        assertFalse(live.busy)
        // ✻ Baked for 1m 44s 是**收尾**的形态，不是「正在忙」
        assertNull(live.status)
    }

    @Test fun 屏幕上只是提到状态词不算数() {
        // ⚠️ 这条测试**必须把真状态行去掉才有意义**。
        // 真状态行永远渲染在输出区最底下，而解析是倒着找第一条 ——
        // 留着它的话，这条测试会「绿得没有理由」：它根本没走到缩进那条规则。
        // 去掉之后，屏幕上唯一像状态行的就只剩我引用给用户看的那行（带缩进），
        // 于是缩进规则成了唯一挡住它的东西。
        val 状态行滚掉了 = BUSY_QUEUED.split("\n")
            .filterNot { it.startsWith("✢ ") || it.startsWith("✽ ") || it.startsWith("· ") }
            .joinToString("\n")
        val live = Live.parse(状态行滚掉了)
        assertTrue("这份样本里应该仍然是「在忙」", live.busy)
        assertNull("引用里那行带缩进，不能当成状态：" + live.status, live.status)
    }

    @Test fun 顶格的中文不会被当成状态词() {
        // ⚠️ **这一屏是拼的，我如实说明**：真机上「顶格渲染的中文消息」和
        // 「排在状态行下面」这两件事我都单独抓到过（用户消息就渲染在顶格），
        // 但**没抓到两者同时出现的那一帧**。所以这里手工拼了一屏。
        //
        // 正则本身错了是**实测证明**的（JDK 17 跑同一条正则）：
        //   "❯ 排队丙：这条带省略号…后面还有字".matches("^(\\S) (\\S*….*)$")  →  true
        // 中文不写空格，所以 `\S*` 能一路吃到 `…`，整条命中。
        // ⚠️ **但这个错够不着，别当成已复现的线上 bug**：忙的时候 TUI 总把状态行
        //    渲染在输出区最底下，而解析是倒着取第一条 —— 中文行即使命中也抢不到。
        //    三份真实整屏逐条验过、iOS 侧三轮约 60 次抓屏也造不出来。
        //    所以这是**纵深防御**：不该靠「渲染顺序恰好如此」这种没人写下来的巧合。
        //
        // ⚠️ 也正因为真状态行永远在最下面，**任何未经改动的真实整屏都验不到这条规则** ——
        //    想验它就必须动样本（这里是拼一屏，iOS 侧是删掉真状态行的真机整屏）。
        //
        // 真状态词永远是拉丁词（Scampering… / Sautéing… / Crafting…），拿这个挡。
        val 拼的 = buildString {
            appendLine("● 上一条回复")
            appendLine("❯ 排队丙：这条带省略号…后面还有字")
            appendLine("─".repeat(40))
            appendLine("❯ ")
            appendLine("─".repeat(40))
            appendLine("  ⏵⏵ bypass permissions on · esc to interrupt · ← for agents")
        }
        val live = Live.parse(拼的)
        assertTrue("这一屏应该判定为在忙", live.busy)
        assertNull("中文被当成状态词了：" + live.status, live.status)
    }

    @Test fun 带重音的拉丁状态词要认得() {
        // Sautéing… 里的 é 是 U+00E9 —— 挡中文的范围别把它一起挡了
        val 拼的 = buildString {
            appendLine("✽ Sautéing… (12s · ↓ 3.4k tokens)")
            appendLine("─".repeat(40))
            appendLine("❯ ")
            appendLine("─".repeat(40))
            appendLine("  ⏵⏵ bypass permissions on · esc to interrupt")
        }
        assertTrue("é 被挡掉了：" + Live.parse(拼的).status, Live.parse(拼的).status.orEmpty().startsWith("Sautéing…"))
    }

    private companion object {
        val BUSY_QUEUED = """

  Ran 2 shell commands

❯ 还有终端模式我怎么不能滑动屏幕然后就是看前面你的输入输出，再就是终端模式的键盘点击似乎只能输入英文不能打中文然后那些功能按键能不能支持打开，不然就一行我要滑
  动很久这样子不方便

● 确认了,你的判断完全正确。 同一时刻两边对照:

  画面（tmux）                          转录文件（手机读的）
  ──────────────────────────────       ────────────────────────────
  · Scampering… (4m 48s · ↓ 10.2k)     ← 这行根本不在文件里
  ❯ 还有终端模式我怎么不能滑动屏幕…      ← 你这条也不在
  ❯ Press up to edit queued messages    最后一条还是「我的对话模式里面的
    ⏵⏵ … esc to interrupt …              没有模型thinking…」

  排队中的输入只活在画面上,提交了但还没被处理就不进转录。 手机只读转录,所以你打的每一条在被处理之前都是隐形的。状态词(Scampering…)同理。

  你后面那条终端模式的问题我也收到了,先把对话模式这个修完再说。

  Ran 4 shell commands

● Now the live-state parser. 先把真实画面样本存下来当测试数据:

  Showing exact indentation of key pane lines
  ⎿  ${'$'} S=/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad
     tmux capture-pane -p -t cc-Yxi > ${'$'}S/pane_busy_queued.txt
     python3 - <<'PY'
     S='/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/pane_busy_queued.txt'
     for i,l in enumerate(ope…

✢ Scampering… (6m 16s · ↓ 15.7k tokens)
  ⎿  Tip: Use /btw to ask a quick side question without interrupting Claude's current work
                                                                                                                       ✔ Update installed · Restart to update
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
❯ 
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  ⏵⏵ bypass permissions on (shift+tab to cycle) · esc to interrupt · ← for agents
  ⧉  yxi-app-design"""

        val IDLE = """  关键内容：
  - 店铺：ID 274578091，粉丝 7859，保证金 ¥10.05 万，综合体验 4.5（落后 93%
    同行），发货地辽宁大连
  - 商品：Pro 代充 ¥178（已售 5000+）、Max 直登 ¥980/¥1980（已售 1 万+），共 6
    个 SKU 带库存
  - 图片 17 张全部落盘，同时 JSON 里保留了原始 CDN URL
  - 汇总里还有方法论：TSDK mtop 签名 + 扫码登录态，详情长图必须走 getdesc
    v7.0（v6.0 已废弃），以及 RGV587 风控/CDN
    420/登录态失效的三个坑；末尾还实测了 Apify
    路线——实物商品可用，虚拟充值类目因强制登录不可用

  ▎ 抓取脚本本身（~/Downloads/taobao-scrape/scrape.py +
  ▎ TSDK）不在这个包里，只有产物。要我一并从你电脑拉过来吗？

  ▎ 中文文件名过 scp 会编码坏，走的是「Windows 侧复制成 ASCII 临时名 → 拉过来 →
  ▎ Python 按 CP437→UTF-8 修正解压」，临时文件已清掉。

✻ Baked for 1m 44s
                                        ✔ Update installed · Restart to update
────────────────────────────────────────────────────────────────────────────────
❯ 
────────────────────────────────────────────────────────────────────────────────
  ⏵⏵ bypass permissions on (shift+tab to cycle) · ← for agents"""

        val PROBE = """│                /…/scratchpad/probe                 │
│                                                    │
╰────────────────────────────────────────────────────╯


❯ 在前台运行 sleep 45（不要放后台、不要
  run_in_background），等它结束再回复我 DONE-A

  Ran 1 shell command

● Blocked by the harness:

  Blocked: standalone sleep 45. ... Do not chain
  shorter sleeps to work around this block.

  Foreground sleep 45 isn't available in this session,
  and the block explicitly forbids the workarounds
  (chained sleeps, and by the same logic python3 -c
  'time.sleep(45)'). So no DONE-A — I didn't wait 45s.

  Options if you want the wait: run_in_background:
  true (you said no), or you run it yourself with !
  sleep 45.

✻ Baked for 13s

❯ 排队输入 B

❯ 排队输入 C

● 收到 B、C 两条排队输入。没有具体任务内容——需要我对
  B/C 做什么，直接说。

✻ Sautéed for 5s

──────────────────────────────────────────────────────
❯ 用 run_in_background 跑 sleep 45
──────────────────────────────────────────────────────
  ⏵⏵ bypass permissions on (shift+tab to cycle) · ←…"""
    }
}
