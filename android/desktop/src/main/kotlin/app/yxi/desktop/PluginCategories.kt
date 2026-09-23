package app.yxi.desktop

internal object PluginCategories {
    val order = listOf("开发工具", "效率", "沟通协作", "创意", "数据与分析", "业务与运营", "教育与研究", "科学研究", "金融", "安全", "医疗健康", "旅行", "娱乐", "其他")
    fun label(raw: String): String {
        if (raw.trim() in order) return raw.trim()
        return when (raw.trim().lowercase().replace(Regex("\\s+"), " ")) {
            "developer tools", "development", "engineering", "developer", "开发" -> "开发工具"
            "productivity", "office" -> "效率"
            "communication", "communications", "collaboration" -> "沟通协作"
            "design", "creative", "creativity" -> "创意"
            "data & analytics", "data and analytics", "analytics", "data" -> "数据与分析"
            "business", "business & operations", "business and operations", "operations" -> "业务与运营"
            "education", "education & research", "education and research" -> "教育与研究"
            "research", "science", "scientific research" -> "科学研究"
            "finance", "financial" -> "金融"
            "security" -> "安全"
            "health", "health & fitness", "healthcare", "medical" -> "医疗健康"
            "travel" -> "旅行"
            "entertainment", "games" -> "娱乐"
            else -> "其他"
        }
    }
}
