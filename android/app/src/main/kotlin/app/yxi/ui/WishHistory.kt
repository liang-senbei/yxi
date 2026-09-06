package app.yxi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yxi.agent.Wish
import app.yxi.ui.theme.Copper
import app.yxi.ui.theme.Muted
import kotlinx.coroutines.launch

/**
 * 祈愿记录 —— 每一次祈愿抽到了什么，新的在前。
 *
 * 接口 `GET /api/wish/history?limit=30&before=<id>` → `{items, nextCursor}`
 * （契约 `logto_yxi/design/wish-checkin.md` §4），取数与解析在 [Wish.history]。
 *
 * ⚠️ 游标是 **before**（取比它更旧的），不是 after —— 记录从顶上插新的，
 * 按页码翻会重复或漏。往下翻传**上一页最后一条的 drawId**。
 *
 * ⚠️ **「拿不到」和「没有记录」要分开说**（STYLE.md §0：不骗人）。网络不通时写
 * 「还没有记录」，读者收到的意思是「我抽到的东西没了」—— 收藏是抽卡的全部意义，
 * 这句话骗不起。
 *
 * ⚠️ 稀有度配色直接用 `WishScreen.kt` 的 [rarityColor] / [rarityLabel]，**不另抄一份**：
 * 抽卡当场是那个金，翻记录变成另一个金，人只会觉得自己记错了。
 */
@Composable
fun WishHistory(modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<Wish.Record>() }
    var cursor by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loading = true
        val r = Wish.history(ctx)
        if (r == null) failed = true else { items.addAll(r.first); cursor = r.second }
        loading = false
    }

    Column(modifier.fillMaxSize()) {
        Text(
            t("祈愿记录"), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(18.dp, 14.dp, 18.dp, 8.dp),
        )
        when {
            loading && items.isEmpty() -> Note(t("正在取…"))
            // 拿不到 ≠ 没有。这两句永远不能换位置。
            failed && items.isEmpty() -> Note(t("取不到 —— 网络不通，或者登录过期了。记录在服务端，没丢。"))
            items.isEmpty() -> Note(t("还没有记录。去祈愿页抽一次，抽到什么都会记在这里。"))
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp, 0.dp, 14.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.drawId }) { r -> DrawCard(r) }
                // 还有更旧的：传上一页最后一条的 id
                cursor?.let { cur ->
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable {
                                scope.launch {
                                    val r = Wish.history(ctx, before = cur)
                                    if (r != null) { items.addAll(r.first); cursor = r.second }
                                }
                            },
                        ) {
                            Text(
                                t("看更早的"), Modifier.fillMaxWidth().padding(16.dp),
                                style = MaterialTheme.typography.labelLarge, color = Muted,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一次祈愿一张卡：卡头说清「哪一抽、什么时候」，卡里逐条列这一抽出的东西。 */
@Composable
private fun DrawCard(r: Wish.Record) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp, 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (r.count >= 10) t("十连") else t("单抽"),
                    style = MaterialTheme.typography.labelSmall, color = Copper,
                )
                Text(
                    // 服务端给的是 ISO 串，截到分钟就够 —— 记录页看的是「哪天那会儿」，不是秒
                    app.yxi.agent.Tz.dateTime(r.at),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = Muted,
                    textAlign = TextAlign.End,
                )
            }
            r.results.forEach { Line(it) }
        }
    }
}

@Composable
private fun Line(g: Wish.Got) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 稀有度先用一颗点说，再用字说 —— 只靠颜色的话色弱看不出档次
        Box(Modifier.size(7.dp).clip(CircleShape).background(rarityColor(g.rarity)))
        Text(
            g.name, Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        note(g)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Muted) }
        Text(
            rarityLabel(g.rarity),
            style = MaterialTheme.typography.labelSmall, color = rarityColor(g.rarity),
        )
    }
}

/**
 * 名字右边那句补充。**只在有话可说时才出现**：
 * 重复折算是钱的事（不写就成了「白抽一发」），首次到手是抽卡唯一的高光。
 * 其余情况留空，别把每行都塞满。
 */
private fun note(g: Wish.Got): String? = when {
    // ⚠️ 整句当 key、数字走 format 占位符。把句子拆成两半再跟数字拼起来，
    //    英文语序一变就拼不回来；而且拆出来的半句会被 dev/i18n-check.sh 当成待翻条目。
    // ⚠️ **必须按 kind 分开写**。服务端历史返回的是**当时存的原值、没做迁移**
    //    （cc-logto_yxi 2026-09-07 确认：库里 tickets 70 条、micro 18 条并存），
    //    一律写成「折曦光 ×N」的话，5 微曦会显示成「折曦光 ×5」——**价值差 10 倍**。
    //    不迁移是对的：旧那一发真的给了 1 曦光，曦光流水里也真的进过账；
    //    历史该记录发生过什么，不是记录"按今天的规则本该是什么"。
    g.dupConvertedTo != null -> when (g.dupConvertedTo.first) {
        "micro" -> t("重复 · 折微曦 ×%d").format(g.dupConvertedTo.second)
        else -> t("重复 · 折曦光 ×%d").format(g.dupConvertedTo.second)
    }
    // 六命前的重复角色：不返还，价值是命座本身。没这一条的话历史里它**什么标都没有**，
    // 看起来像白抽（结算页已经说了「命座 +1」，历史得对得上）。
    !g.isNew && g.kind == "character" -> t("重复 · 命座 +1")
    g.isNew && g.kind in setOf("character", "cosmetic") -> t("NEW")
    g.kind == "membership_days" && g.amount > 0 -> t("会员 %d 天").format(g.amount)
    g.kind == "tickets" && g.amount > 0 -> "×%d".format(g.amount)
    else -> null
}

@Composable
private fun Note(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        Text(text, Modifier.padding(18.dp, 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}
