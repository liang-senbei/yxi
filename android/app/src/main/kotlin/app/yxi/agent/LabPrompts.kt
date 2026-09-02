package app.yxi.agent

/**
 * 实验室里三个按钮发给会话的提示词（用户 2026-09-02 定的，D29）：
 *  · **查明并画出来**（architecture-verifier）：把真实项目反向还原成可验证架构图
 *  · **把结构画成图**（diagram-generator）：把已知的关系表达成图
 *  · **更新**：按项目现状把实验室里某一张重画一遍，原位替换
 *
 * ⚠️ App 只带提示词，不带任何技能、渲染器或内容 —— 画图在服务器上做、结果走 `yxi-lab` 回来，D26 / D27 不破。
 * ⚠️ 规矩跟 `yxi-lab spec` 一致：单文件离线、≤1MB、不引外部字体 / 脚本。提示词里不带 `$`（要经 tmux send-keys）。
 * ⚠️ 用户填了提示词就拼进去；没填就是默认执行 —— 这是用户要的「不输入就默认」。
 */
object LabPrompts {

    const val GROUP_ARCH = "架构图"
    const val GROUP_DIAGRAM = "图表"

    private const val RULES =
        "产出规矩（yxi-lab spec）：单文件、离线（不引外部字体 / 脚本 / CSS，图用 data URI 内联）、不超过 1MB、有 viewport meta。" +
            "先 yxi-lab check <文件>，有 ✗ 就改到过为止。服务器上有 archify 技能（node …/bin/archify.mjs）就用它出可验证的 HTML" +
            "（deliver 之后把 Google 字体的 <link> / @import 剥掉再 check），没有就自己写 SVG 或单文件 HTML。" +
            "做完回一句「实验室已更新，刷新看」。"

    private fun extra(s: String) = s.trim().let { if (it.isEmpty()) "" else "补充要求：$it\n" }

    /** 查明并画出来 */
    fun verify(extra: String): String =
        "把当前项目（你所在的目录）反向还原成可验证的架构图。读代码、配置、部署文件、运行态和文档；" +
            "梳理服务调用顺序、数据库、队列、缓存、外部 API、跨仓和跨服务器链路；" +
            "每个节点和连线标 confirmed / inferred / unknown / conflict，附证据清单（文件 + 行号）和验证回执。\n" +
            extra(extra) + RULES + "\n" +
            "推：yxi-lab add <文件> \"<项目名> 架构图\" \"<一句：查明了什么、哪些是推断>\" \"<你的名字>\" --aspect 16:10 --group $GROUP_ARCH"

    /** 把结构画成图 */
    fun draw(extra: String): String =
        "把下面描述的结构整理成图，按内容选类型：流程 / 泳道 / 时序 / 状态 / ER / 类 / 依赖 / C4 / Gantt / Journey。" +
            "Mermaid 源码留一份（放在说明里或同名 .mmd），展示用的成品出 SVG 或单文件 HTML。\n" +
            "描述：" + extra.trim().ifEmpty { "把这个项目的模块 / 服务关系画成依赖图。" } + "\n" +
            RULES + "\n" +
            "推：yxi-lab add <文件> \"<标题>\" \"<一句说明>\" \"<你的名字>\" --group $GROUP_DIAGRAM"

    /** 更新某一项：原位替换（id 不变，手机上置顶 / 采纳都还在） */
    fun update(item: LabRemote.Item, extra: String): String =
        "更新实验室里这一项：id=${item.id}《${item.title}》（${item.type}，文件 ~/.yxi/lab/${item.file}" +
            (if (item.group.isNotBlank()) "，组「${item.group}」" else "") +
            (if (item.aspect.isNotBlank()) "，比例 ${item.aspect}" else "") + "）。" +
            "按项目现状重画：先看上一版，再读代码 / 配置 / 部署文件找变化；变了的改、不确定的标 inferred / unknown，保持同样的格式与比例。\n" +
            extra(extra) + RULES + "\n" +
            "推回同一项：yxi-lab update ${item.id} <新文件> \"<一句：改了什么>\"（id 不变，手机上原位刷新）"
}
