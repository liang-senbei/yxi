package app.yxi

import app.yxi.agent.Model
import app.yxi.agent.Prompt
import org.junit.Assert.*
import org.junit.Test

/**
 * `/model` 选单**绝不能**被当成普通「等你选」卡片。
 *
 * ⚠️ 点一下那种卡片是**送数字**，而在模型选单上这等于
 * 「saved as your default for new sessions」—— 用户顺手一点就把**账号默认模型**改了，
 * 不报错、事后也想不起来为什么。真机上真的发生过。
 */
class ModelGuardTest {

    /** 真机 46 列（手机宽度）抓下来的屏幕，**一字未改**。注意脚注被折成了两行。 */
    private val narrow = """
   Select model
   Switch between Claude models. Your pick
   becomes the default for new sessions.
   For other/previous model names, specify
   with --model.

   ❯ 1. Default (recommended) ✔  Opus 5
     2. Sonnet                 Sonnet 5 ·
                               Efficient for
     3. Haiku                  Haiku 4.5 ·
     4. Opus 4.6               Best for

   ◈ Max effort ←/→ to adjust

   Enter to set as default · s to use this
   session only · Esc to cancel
    """.trimIndent()

    /**
     * ⚠️ **这条是整个护栏存在的理由。**
     * 窄屏把 `to use this session only` 折成了两行，
     * 老写法（直接子串匹配）必然漏 —— 压平之后才认得出来。
     */
    @Test fun 窄屏折行也要认出模型选单() {
        assertNotNull("窄屏下认不出模型选单 —— 护栏在手机上等于没有", Model.parse(narrow))
    }

    /** 认出来之后，通用解析必须**闭嘴** */
    @Test fun 认出来之后不画成等你选卡片() {
        assertNull("模型选单被当成待答卡片了 —— 点一下会改掉账号默认模型", Prompt.parse(narrow))
    }

    /** 宽屏当然也要认 */
    @Test fun 宽屏一样认得出() {
        val wide = "   Select model\n   Switch between Claude models. Your pick becomes the default for new sessions.\n" +
            "   ❯ 1. Default (recommended) ✔  Opus 5 with 1M context\n" +
            "     2. Sonnet                   Sonnet 5 · Efficient\n" +
            "   Enter to set as default · s to use this session only · Esc to cancel"
        assertNotNull(Model.parse(wide))
        assertNull(Prompt.parse(wide))
    }

    /** ⚠️ 只提一句不算 —— 否则正文里聊到这几个词，真正的待答卡片会凭空消失 */
    @Test fun 正文里提到不算选单() {
        assertNull(Model.parse("我刚在 Select model 那里选了 Opus，你继续"))
    }
}
