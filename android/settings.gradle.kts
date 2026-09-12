pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // JediTerm（桌面版真终端的仿真器+控件，IntelliJ 终端同源）只在 JetBrains 的依赖仓发布，Central 没有
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") { content { includeGroup("org.jetbrains.jediterm") } }
    }
}
rootProject.name = "Yxi"
include(":app", ":core", ":desktop")   // core = 手机 / 桌面共用的纯 Kotlin；desktop = Windows 桌面版（Compose Multiplatform）
