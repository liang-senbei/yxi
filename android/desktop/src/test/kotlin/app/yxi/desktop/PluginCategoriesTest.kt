package app.yxi.desktop

import kotlin.test.*

class PluginCategoriesTest {
    @Test fun `publisher category aliases share one stable user facing category`() {
        assertEquals("开发工具", PluginCategories.label(" Engineering "))
        assertEquals("业务与运营", PluginCategories.label("Business   & Operations"))
        assertEquals("数据与分析", PluginCategories.label("data and analytics"))
        assertEquals("医疗健康", PluginCategories.label("Healthcare"))
        assertEquals("创意", PluginCategories.label("创意"))
    }
    @Test fun `missing and unknown categories stay in other instead of creating arbitrary sections`() {
        assertEquals("其他", PluginCategories.label(""))
        assertEquals("其他", PluginCategories.label("unrecognized publisher label"))
        assertEquals("其他", PluginCategories.order.last())
    }
}
