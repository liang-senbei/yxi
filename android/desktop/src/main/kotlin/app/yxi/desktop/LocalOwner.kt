package app.yxi.desktop

/**
 * 本机会话记录是不是「这台电脑、这个用户」的：记录里存的是建会话时的 user.name 和 os.name。系统只比家族（Windows / macOS / 其他照原样）——
 * Windows 10 升到 11、或者换了带的 JDK，os.name 会从「Windows 10」变成「Windows 11」，会话还是这台电脑的，不该整批看不见、接不回（发版前检查 10-15）。
 */
internal object LocalOwner {
    fun family(osName: String): String = when {
        osName.startsWith("Windows") -> "Windows"
        osName.startsWith("Mac") -> "Mac"
        else -> osName
    }

    fun sameSystem(recorded: String, current: String): Boolean = family(recorded) == family(current)

    fun owns(user: String, platform: String): Boolean =
        user == System.getProperty("user.name") && sameSystem(platform, System.getProperty("os.name"))
}
