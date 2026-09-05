package app.yxi.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 深渊客户端只有两段逻辑值得守：**预估公式**（必须和 `logto_yxi/design/abyss.md`「规则」一节算出同样的数）和**解析**。
 * 结算本身在服务端，这里不测「谁赢」。
 */
class AbyssTest {
    private val rules = Abyss.Rules(
        base = 120, perDup = 30, maxDup = 6, weakMult = 1.5, turbMult = 1.5, teamSize = 2,
        passRatio = 1.0, bonusRatio = 1.3, rewardEvery = 3, rewardTickets = 2, maxStars = 36,
        fullReward = Abyss.FullReward("tickets", 5, ""),
    )
    private fun state(turbTraits: List<String> = emptyList()) = Abyss.State(
        season = Abyss.Season("2026-09-2", 1, "第 1 期", "", "",
            if (turbTraits.isEmpty()) null else Abyss.Turbulence("浮云紊流", "", turbTraits, 1.5)),
        floors = emptyList(), totalStars = 0, claimed = emptyList(), played = false,
        roster = listOf(
            Abyss.Roster("yunxi", "云曦", 2, listOf("空灵", "纯真"), 180),
            Abyss.Roster("jinxing", "烬星", 0, listOf("威严", "炽烈"), 120),
        ),
        travelerPower = 100, rules = rules,
    )

    @Test fun 星数规则() {
        assertEquals(0, Abyss.stars(0.9, 0.9, rules))
        assertEquals(1, Abyss.stars(0.9, 2.0, rules))       // 一半没过、另一半过 → 1 星（两半各算）
        assertEquals(1, Abyss.stars(1.0, 0.5, rules))
        assertEquals(2, Abyss.stars(1.0, 1.0, rules))       // 0 角色过第 1 层：两半各 1.0 → 2 星（契约）
        assertEquals(2, Abyss.stars(1.5, 1.2, rules))       // 一半余量不够，第三星不给
        assertEquals(3, Abyss.stars(1.3, 1.3, rules))
    }

    @Test fun 战力_旅人加角色_弱点与紊流各乘一次_四舍五入() {
        val st = state(turbTraits = listOf("空灵"))
        val half = Abyss.Half(listOf("纯真", "坚忍"), 200)
        // 旅人 100 + 云曦 180 ×1.5(弱点 纯真) ×1.5(紊流 空灵) = 505
        assertEquals(505, Abyss.power(listOf("yunxi"), half, st))
        // 烬星什么都不命中 → 220；没拥有的 id 忽略 → 100
        assertEquals(220, Abyss.power(listOf("jinxing"), half, st))
        assertEquals(100, Abyss.power(listOf("nobody"), half, st))
        // 四舍五入：旅人 + 烬星 ×1.5 = 280（整）；换个基础值 125 ×1.5 = 187.5 → 100 + 187.5 = 287.5 → 288
        val odd = st.copy(roster = listOf(Abyss.Roster("jinxing", "烬星", 0, listOf("炽烈"), 125)))
        assertEquals(288, Abyss.power(listOf("jinxing"), Abyss.Half(listOf("炽烈"), 100), odd))
    }

    @Test fun 预估一层() {
        val st = state()
        val f = Abyss.Floor(3, Abyss.Half(listOf("空灵"), 170), Abyss.Half(listOf("炽烈"), 170), 0, emptyList(), emptyList())
        // 上半 云曦 100+270=370 → 2.18；下半 烬星 100+180=280 → 1.65 → 3 星
        val (stars, ru, rd) = Abyss.estimate(f, listOf("yunxi"), listOf("jinxing"), st)
        assertEquals(3, stars)
        assertEquals(370.0 / 170, ru, 1e-9)
        assertEquals(280.0 / 170, rd, 1e-9)
    }

    @Test fun 解析状态_缺字段不抛_紊流可空_规则有默认() {
        val st = Abyss.parseState(JSONObject(
            """{"season":{"id":"2026-09-2","seq":1,"name":"第 1 期"},
                "floors":[{"n":1,"up":{"weak":["空灵"],"d":100},"down":{"d":100}}],
                "roster":[{"id":"yunxi","name":"云曦","dup":3,"traits":["空灵","纯真"],"power":210}],
                "played":true,"rules":{"teamSize":3,"fullReward":{"kind":"cosmetic","id":"bubble_cloud"}}}""",
        ))
        assertEquals(1, st.floors.size)
        assertEquals(listOf("空灵"), st.floors[0].up.weak)
        assertEquals(0, st.floors[0].down.weak.size)
        assertNull(st.season.turbulence)
        assertTrue(st.played)
        assertEquals(3, st.rules.teamSize)
        assertEquals(1.5, st.rules.weakMult, 1e-9)              // 没给就用默认
        assertEquals("cosmetic", st.rules.fullReward.kind)
        assertEquals(listOf("空灵", "纯真"), st.roster[0].traits)
    }

    @Test fun 解析结果_granted_档到物品() {
        val r = Abyss.parseResult(JSONObject(
            """{"floor":4,"stars":2,"best":3,"up":{"power":370,"ratio":2.176},"down":{"power":100,"ratio":0.588},
                "totalStars":9,"granted":[{"threshold":9,"items":[{"kind":"tickets","amount":2}]}]}""",
        ))
        assertEquals(4, r.floor); assertEquals(2, r.stars); assertEquals(3, r.best)
        assertEquals(1, r.granted.size); assertEquals(9, r.granted[0].threshold)
        assertEquals("tickets", r.granted[0].items[0].kind); assertEquals(2L, r.granted[0].items[0].amount)
    }
}
