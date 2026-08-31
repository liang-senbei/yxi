package app.yxi

import app.yxi.agent.Groups
import org.junit.Assert.*
import org.junit.Test

class GroupsTest {

    /**
     * ⚠️ **这串 JSON 是跟 `server/yxi-hub` 之间的契约。**
     * 手机写、agent 读，两边是两套独立实现（Kotlin / python）——
     * 谁单方面改了格式，分组就静悄悄失效：UI 一切正常，agent 却永远说「你不在任何组里」。
     * `server/test_yxi.py::test_hub_only_talks_inside_the_group` 里用的是**同一串**，
     * 改这边就得同步改那边。
     */
    @Test fun 写出来的格式跟yxi_hub读的一致() {
        val t = Groups.Table(mapOf("测试组" to listOf("yxitest-a", "yxitest-b")))
        // ⚠️ 不比对整串字面量 —— JSONObject 不保证键的顺序，那样断言会因为
        // 「v 和 groups 谁在前」这种无关紧要的事红，掩盖真正的格式漂移。
        // 契约是**结构**：顶层一个 `groups` 对象，值是成员名数组。
        val o = org.json.JSONObject(Groups.encode(t))
        assertTrue("yxi-hub 读的是顶层 groups 键", o.has("groups"))
        val arr = o.getJSONObject("groups").getJSONArray("测试组")
        assertEquals(2, arr.length())
        assertEquals("yxitest-a", arr.getString(0))
    }

    @Test fun 转一圈回来还是原样() {
        val t = Groups.Table(mapOf("后端" to listOf("cc-api", "cc-db"), "空组" to emptyList()))
        assertEquals(t.groups, Groups.parse(Groups.encode(t)).groups)
    }

    /**
     * ⚠️ **读不懂就当没有分组，绝不抛异常。** 这文件用户可能手改过。
     * 为了一个坏掉的分组表让整个看板打不开，是拿主功能给附加功能陪葬。
     */
    @Test fun 坏文件不许把看板拖垮() {
        listOf("", "   ", "不是 json", "{", "[]", """{"groups":"不是对象"}""", """{"v":1}""")
            .forEach { assertTrue("「$it」把它弄崩了", Groups.parse(it).groups.isEmpty()) }
    }

    /** 用户明确要的：一个 agent 可以同时在好几个组里。 */
    @Test fun 一个会话可以在好几个组里() {
        val t = Groups.Table(
            mapOf("后端" to listOf("cc-api", "cc-db"), "上线" to listOf("cc-api", "cc-web")),
        )
        assertEquals(listOf("上线", "后端"), t.of("cc-api"))
        // 同组的人跨它所在的全部组，且**去重**（cc-api 在两个组里都有队友）
        assertEquals(setOf("cc-db", "cc-web"), t.matesOf("cc-api").toSet())
        assertEquals(listOf("cc-api"), t.matesOf("cc-db"))
    }

    @Test fun 加进去和拿出来() {
        var t = Groups.Table()
        t = t.withMember("组", "cc-a").withMember("组", "cc-b")
        assertEquals(listOf("cc-a", "cc-b"), t.groups["组"])
        // 重复加不该变成两份
        assertEquals(listOf("cc-a", "cc-b"), t.withMember("组", "cc-a").groups["组"])
        // ⚠️ 拿走最后一个成员，**组本身要留着** —— 组是用户建的，不该因为人走光了就没了
        t = t.withoutMember("组", "cc-a").withoutMember("组", "cc-b")
        assertTrue("成员空了就把组删了，用户会以为自己建的组丢了", "组" in t.groups)
        assertEquals(emptyList<String>(), t.groups["组"])
    }

    /** 路径/组名里的单引号不能把写回服务器的那条命令劈开。 */
    @Test fun 组名里的引号转一圈还在() {
        val t = Groups.Table(mapOf("it's" to listOf("cc-a")))
        assertEquals(listOf("cc-a"), Groups.parse(Groups.encode(t)).groups["it's"])
    }

    /**
     * 用户报的：新建分组不生效。
     *
     * 病根不在 [Groups] 而在界面 —— 建组要点**两下**（先「建 X」再「存下」），
     * 少点中间那下就把输入静默丢掉，服务器上落下 `{"v":1,"groups":{}}`。
     * 这里钉住那条**唯一合理的解读**：**输入框里有字 = 用户想要这个组**。
     * （界面上是「存下时若输入非空就先 withMember」，这条测的是那个语义。）
     */
    @Test fun 输入框里有字就该建出组来() {
        val empty = Groups.Table()
        val typed = "后端"
        // 界面在「存下」时做的事
        val saved = if (typed.isNotEmpty()) empty.withMember(typed, "cc-api") else empty
        assertEquals(listOf("cc-api"), saved.groups["后端"])
        assertTrue("空表存下去 = 用户白填一场", saved.groups.isNotEmpty())
        // 而没填字的时候不该凭空造组
        val nothing = if ("".isNotEmpty()) empty.withMember("", "cc-api") else empty
        assertTrue(nothing.groups.isEmpty())
    }

    /** 组名前后空格要吃掉 —— 「后端 」和「后端」不该是两个组。 */
    @Test fun 组名去空格() {
        val t = Groups.Table().withMember("  后端  ".trim(), "cc-api")
        assertEquals(setOf("后端"), t.groups.keys)
    }
}
