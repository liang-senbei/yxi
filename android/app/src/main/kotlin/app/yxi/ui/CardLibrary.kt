package app.yxi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.yxi.ui.theme.Muted

/**
 * 卡牌库 ——《神之冠冕》（老板 2026-09-04 给的设定 + 立绘）。首期 UP 是**浮云之冠 · 云曦**。
 *
 * 抽到了显示全貌（配色 + 冠 + 台词），没抽到只显示**剪影**。
 * ⚠️ 没获得的卡**不能画成获得的样子** —— 收集类界面里最要紧的就是「我到底有没有」一眼可辨。
 *
 * ⚠️ 「有没有」的真相源在服务端（祈愿发的 `kind: "character"`）。接口没上线之前**全部按未获得显示**，
 * 并且明说「还没开通」——不假装你已经有了一张。
 */
@Composable
fun CardLibrary(owned: Set<String> = emptySet(), live: Boolean = false, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf<Crown?>(null) }
    open?.let { c -> CrownDetail(c, owned.contains(c.id)) { open = null } }

    // 两栏：角色（这一页）和装扮（[SkinPicker]）。
    // ⚠️ 「我有什么」和「我用哪个」摆在同一处最省解释 —— 装扮不像角色只是收藏，它是**正在用的东西**。
    var tab by remember { mutableStateOf(0) }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(18.dp, 14.dp, 18.dp, 6.dp)) {
            Text(t("卡牌库"), style = MaterialTheme.typography.headlineMedium)
            Text(
                // ⚠️ `owned` 是**服务端给的全部藏品 id**（角色 + 装扮 + 快捷语包…），
                //    分母却只有角色数 —— 直接用 owned.size 会印出「已收集 27 / 9」。实测踩过。
                t("《神之冠冕》· 已收集 %d / %d").format(CROWNS.count { owned.contains(it.id) }, CROWNS.size),
                style = MaterialTheme.typography.labelMedium, color = Muted,
            )
            if (!live) {
                Spacer(Modifier.height(6.dp))
                Text(
                    t("祈愿还没开通 —— 下面是全部的样子，都还没获得。"),
                    style = MaterialTheme.typography.labelSmall, color = Muted,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(14.dp, 4.dp, 14.dp, 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(t("角色"), t("装扮")).forEachIndexed { i, label ->
                Surface(
                    color = if (tab == i) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(100.dp),
                    modifier = Modifier.clip(RoundedCornerShape(100.dp)).clickable { tab = i },
                ) {
                    Text(
                        label, Modifier.padding(18.dp, 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (tab == i) MaterialTheme.colorScheme.onSecondaryContainer else Muted,
                    )
                }
            }
        }
        if (tab == 1) {
            SkinPicker(live = live, header = false)
            return@Column
        }
        // ⚠️ **一列横版，不是两列竖版**（老板 2026-09-04：「我给你上传的图片，你不要截图、
        //    不要截一部分出来，要保证它是完整的」）。原图实测全是 **16:9 横构图**（2752×1536 一类，
        //    比例 1.7917），所以卡面也必须是 16:9 —— 竖版卡放横图，要么裁要么留一大片黑边。
        LazyVerticalGrid(
            GridCells.Fixed(1),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp, 4.dp, 14.dp, 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(CROWNS, key = { it.id }) { c ->
                val has = owned.contains(c.id)
                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp))
                        .clickable { open = c },
                ) {
                    Box(Modifier.background(cardBrush(c, !has))) {
                        // 有立绘就用立绘当卡面；没有的（幻蝶）退回几何纹章
                        // ⚠️ 未获得的**压暗 + 去饱和**，一眼分得出「我还没有这张」——
                        //    但保留轮廓，收集类界面要让人看见「还差什么」。
                        CardFace(c, has)
                        Column(
                            Modifier.fillMaxSize().padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                c.crown,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xE6FFFFFF),
                                modifier = Modifier.align(Alignment.Start),
                            )
                            Spacer(Modifier.height(1.dp))
                            Column(
                                Modifier.align(Alignment.Start),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text(
                                    if (has) c.name else "？？",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                )
                                Text(
                                    if (has) c.trait.ifBlank { t("设定待补") } else t("未获得"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xB3FFFFFF),
                                )
                            }
                        }
                        // 首期 UP 角标
                        if (c.id == "yunxi") Surface(
                            color = Color(0xE6FF6B6B), shape = RoundedCornerShape(0.dp, 20.dp, 0.dp, 12.dp),
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Text(
                                "UP", Modifier.padding(9.dp, 3.dp),
                                style = MaterialTheme.typography.labelSmall, color = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 卡面本身：有立绘用立绘，没有的画几何纹章。
 * 未获得 → 去饱和 + 压暗（看得见轮廓，但一眼是「没有」）。
 * 底部一层黑到透明的渐变，保证压在上面的字读得清。
 */
@Composable
private fun CardFace(c: Crown, has: Boolean) {
    Box(Modifier.fillMaxSize().background(cardBrush(c, !has))) {
        if (c.art != 0) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(c.art),
                contentDescription = null,
                // ⚠️ **Fit 不是 Crop**：容器就是 16:9、图也是 16:9，两者本该正好对上；
                //    用 Fit 是为了**万一比例有零点几的出入也绝不裁**（老板要的是「完整」）。
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                colorFilter = if (has) null else androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                    androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) },
                ),
                alpha = if (has) 1f else 0.34f,
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CrownArt(c, 96.dp, locked = !has)
            }
        }
        // 上下各压一层，让顶上的冠名和底下的角色名都读得清
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color(0x99000000), 0.35f to Color(0x00000000),
                    0.6f to Color(0x00000000), 1f to Color(0xCC000000),
                ),
            ),
        )
    }
}

/** 点开一张：整卡放大，配上故事和台词。没获得的只给设定，不给「你已拥有」的错觉。 */
@Composable
private fun CrownDetail(c: Crown, has: Boolean, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color(0xCC0E1116)).clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier.fillMaxWidth(0.86f).clip(RoundedCornerShape(26.dp)),
            ) {
                Column(
                    Modifier.background(cardBrush(c, !has)).padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        c.crown, style = MaterialTheme.typography.labelLarge,
                        color = Color(0xE6FFFFFF),
                    )
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(18.dp)),
                    ) { CardFace(c, has) }
                    Text(
                        if (has) c.name else t("未获得"),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                    )
                    // ⚠️ 设定是老板给的 IP，**没给的就空着说「设定待补」，不替他编**
                    Text(
                        c.trait.ifBlank { t("设定待补") }, style = MaterialTheme.typography.labelMedium,
                        color = Color(0xB3FFFFFF),
                    )
                    if (c.story.isNotBlank()) Text(
                        c.story, style = MaterialTheme.typography.bodySmall,
                        color = Color(0xCCFFFFFF), textAlign = TextAlign.Center,
                    )
                    if (c.quote.isNotBlank()) Text(
                        "「" + c.quote + "」",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
