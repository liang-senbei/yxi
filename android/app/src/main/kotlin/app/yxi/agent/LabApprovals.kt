package app.yxi.agent

import app.yxi.ssh.SshSession

/**
 * 实验室的「审核勾选」—— 用户在候选上打勾，落到**连的那台服务器**的
 * `~/.yxi/lab-approvals.txt`（一行一个 id）。
 *
 * ⚠️ **为什么落服务器不落手机本地**：勾了要让**开发者（我）看得到**。
 * 手机本地的 SharedPreferences 我隔着真机读不到；写到服务器上，我 `cat` 一下就知道
 * 用户审核通过了哪几个。这跟事件流、答复走同一套「app 写、服务器留、我读」。
 *
 * ⚠️ 只用 grep/echo，**不依赖 python**（目标机不一定有）。id 只留字母数字和 `.-_`，
 * 防 shell 注入 —— 反正 id 是代码里写死的常量。
 */
object LabApprovals {
    private const val F = "\$HOME/.yxi/lab-approvals.txt"

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it in ".-_" }

    /** 读回已勾选的 id 集合。读不到（没连/没文件）就空集。 */
    suspend fun load(ssh: SshSession?): Set<String> =
        ssh?.exec("cat $F 2>/dev/null").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** 勾/取消勾一个 id。`on` = 勾上（幂等追加），否则从文件里删掉那一行。 */
    suspend fun set(ssh: SshSession?, id: String, on: Boolean) {
        ssh ?: return
        val s = safe(id)
        if (on) ssh.exec("mkdir -p \$HOME/.yxi 2>/dev/null; grep -qxF ${app.yxi.ssh.Shell.q(s)} $F 2>/dev/null || echo ${app.yxi.ssh.Shell.q(s)} >> $F")
        else ssh.exec("[ -f $F ] && grep -vxF ${app.yxi.ssh.Shell.q(s)} $F > $F.tmp 2>/dev/null && mv $F.tmp $F || true")
    }
}
