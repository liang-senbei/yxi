package app.yxi.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashTest {
    @Test fun 只打一个斜杠给全部候选() {
        assertEquals(Slash.ALL.size, Slash.suggest("/").size)
        assertEquals("compact", Slash.suggest("/").first().name)   // 手机上最常按的排头一个
    }

    @Test fun 按前缀过滤() {
        val names = Slash.suggest("/co").map { it.name }
        assertTrue(names.containsAll(listOf("compact", "context", "cost", "config")))
        assertTrue("model" !in names)
    }

    @Test fun 不以斜杠开头就不提示() {
        assertEquals(emptyList<Slash.Cmd>(), Slash.suggest(""))
        assertEquals(emptyList<Slash.Cmd>(), Slash.suggest("看一下 /usr/bin 里有什么"))
        // ⚠️ 这条是重点：正文里出现路径不能把提示条弹出来挡住输入框
        assertEquals(emptyList<Slash.Cmd>(), Slash.suggest("cd /compact"))
    }

    @Test fun 打了空格就收起来() {
        // 到了填参数的时候（`/model opus`），提示条只会挡路
        assertEquals(emptyList<Slash.Cmd>(), Slash.suggest("/model "))
        assertEquals(emptyList<Slash.Cmd>(), Slash.suggest("/model opus"))
    }

    @Test fun 多行不提示() {
        // 粘贴进来的长文本第一行可能就是个路径
        assertEquals(emptyList<Slash.Cmd>(), Slash.suggest("/tmp/x\n第二行"))
    }

    @Test fun 没匹配上就不弹() {
        assertEquals(emptyList<Slash.Cmd>(), Slash.suggest("/zzz"))
    }

    @Test fun 大小写不敏感() {
        assertEquals("compact", Slash.suggest("/COM").single().name)
    }

    @Test fun 打全了就收起来() {
        // 点一下候选就是这个情形：草稿变成 `/usage`，没什么好补的了，
        // 提示条还挂着只会挡住输入框
        assertEquals(emptyList<Slash.Cmd>(), Slash.suggest("/usage"))
        assertEquals(emptyList<Slash.Cmd>(), Slash.suggest("/USAGE"))
    }

    @Test fun 没有命令名是另一个的前缀() {
        // 「打全了就收起来」全靠这一条：真出现 /log 和 /login 这种一对，
        // 打到 /log 就再也补不出 /login 了
        // ⚠️ 比 name 不比引用：`ALL` 现在是 `get()`（为了换语言时提示跟着变），
        // 每次读都是**新对象**，`a !== b` 连「同一条命令」都拦不住，
        // 于是 compact 跟自己比，一测就红。
        val all = Slash.ALL
        for (a in all) for (b in all) {
            if (a.name != b.name) assertTrue(
                "${'$'}{b.name} 是 ${'$'}{a.name} 的前缀 —— suggest() 的收起规则会吃掉它",
                !b.name.startsWith(a.name),
            )
        }
    }
}
