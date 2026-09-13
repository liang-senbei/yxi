package app.yxi.desktop

import app.yxi.agent.AccountApi

internal fun quotaSummary(me: AccountApi.Me): String = when {
    me.quotaUnlimited -> "不限"
    !me.quotaRemainingKnown || me.quotaRemaining == null -> "额度暂不可用，请刷新后查看"
    else -> (if (me.quotaRemaining == 0) "本期额度已用完" else "还能改 ${me.quotaRemaining} 次") +
        (if (me.quotaLimitKnown) "（共 ${me.quotaLimit} 次）" else "") +
        (me.nextRefreshAt?.takeIf { it.isNotBlank() }?.let { " · $it 恢复" } ?: "")
}
