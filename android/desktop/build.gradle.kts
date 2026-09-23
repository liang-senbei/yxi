// Yxi 桌面版（Windows；老板 2026-09-08：「像 Claude Desktop / ChatGPT Windows 版那样」）。
// Compose Multiplatform（JVM），SSH 还是 jsch；跟手机端共用 :core。
// 发布包走 Velopack（createDistributable 的 app-image → `vpk pack` → 一键 Setup.exe + 自动更新，见 README / desktop.yml）；
// jpackage 的 Msi/Exe 目标留着只是给老地址应急用。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}
// Markdown JVM artifacts require Java 21. The packaged runtime and CI must use the same baseline.
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
kotlin { jvmToolchain(21) }

// 在 Linux 服务器上给别的平台打 uber jar（差别只是 Skia 的原生库）：-Pyxi.os=mac / win，不传 = 本机。
// jpackage（Msi/Exe）跨不了平台，那个只能在 Windows 上打（.github/workflows/desktop.yml）。
val yxiOs = project.findProperty("yxi.os") as String?
dependencies {
    implementation(project(":core"))
    implementation(when (yxiOs) { "mac" -> compose.desktop.macos_arm64; "win" -> compose.desktop.windows_x64; else -> compose.desktop.currentOs })
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.jsch)
    // 真终端（老板 09-12：终端模式要像 VSCode Remote，去掉输入框）—— JediTerm 是 IntelliJ 终端同源的仿真器+控件（Swing，SwingPanel 嵌入）。
    // 仓库坐标见 settings.gradle.kts（JetBrains intellij-dependencies，Central 没有）。
    implementation("org.jetbrains.jediterm:jediterm-core:3.3")   // ⚠️ ui 的 POM 没声明 core，必须两个都显式引
    implementation("org.jetbrains.jediterm:jediterm-ui:3.3")
    implementation("org.slf4j:slf4j-api:2.0.16")                 // ⚠️ jediterm 内部打日志用，POM 同样没声明（真机/运行时才炸）
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")                    // 终端库的日志静默掉
    implementation(libs.org.json)
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.44.0")
    implementation("me.friwi:jcefmaven:146.0.10")
    val browserPlatform = when (yxiOs) {
        "win" -> "windows-amd64"
        "mac" -> "macosx-arm64"
        else -> when {
            System.getProperty("os.name").startsWith("Windows") -> "windows-amd64"
            System.getProperty("os.name").startsWith("Mac") -> "macosx-" + if (System.getProperty("os.arch") == "aarch64") "arm64" else "amd64"
            else -> "linux-" + if (System.getProperty("os.arch") == "aarch64") "arm64" else "amd64"
        }
    }
    runtimeOnly("me.friwi:jcef-natives-$browserPlatform:jcef-d3de827+cef-146.0.10+g8219561+chromium-146.0.7680.179")
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(kotlin("test"))
}
tasks.test {
    useJUnitPlatform()
    // Fresh isolated fixtures must execute, even when compiled tests are unchanged.
    listOf("YXI_ROUTE_FIXTURE", "YXI_BROWSER_FIXTURE", "YXI_SERVICE_UI_FIXTURE",
        "YXI_MAIL_UI_FIXTURE", "YXI_SUPPORT_UI_FIXTURE", "YXI_PLUGIN_UI_OUT",
        "YXI_PLUGIN_TOGGLE_FIXTURE", "YXI_PLUGIN_INSTALL_FIXTURE", "YXI_HOST_RECOVERY_UI_OUT", "SKIKO_RENDER_API").forEach { key ->
        inputs.property("fixture.$key", System.getenv(key).orEmpty())
    }
    System.getenv("YXI_MAIL_UI_FIXTURE")?.let { systemProperty("user.home", "$it/profile") }
    System.getenv("YXI_SUPPORT_UI_FIXTURE")?.let { systemProperty("user.home", "$it/profile") }
    System.getenv("YXI_SERVICE_UI_FIXTURE")?.let {
        systemProperty("user.home", "$it/client-home")
        systemProperty("yxi.browser.localFixture", "true")
        systemProperty("yxi.browser.runtimeDir", System.getenv("YXI_BROWSER_RUNTIME_DIR") ?: "$it/browser-runtime")
    }
    System.getenv("YXI_BROWSER_FIXTURE")?.let { fixture ->
        systemProperty("user.home", "$fixture/client-home")
        systemProperty("yxi.browser.localFixture", "true")
        systemProperty("yxi.browser.runtimeDir", System.getenv("YXI_BROWSER_RUNTIME_DIR") ?: "$fixture/browser-runtime")
    }
}
// Local Python verification may create caches beside embedded scripts.
tasks.withType<org.gradle.language.jvm.tasks.ProcessResources>().configureEach {
    exclude("**/__pycache__/**", "**/*.pyc")
}
tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
    from(rootProject.projectDir.parentFile.resolve("server/yxi-asr")) { into("app/yxi/desktop/asr") }
    from(rootProject.projectDir.parentFile.resolve("server/yxi-hub")) { into("app/yxi/desktop/hub") }
    from(rootProject.projectDir.parentFile.resolve("server/configure-hub.py")) { into("app/yxi/desktop/hub") }
}
// 插件按本机 OS 起名（跨平台打出来也叫 linux-x64），文件名改成跟着目标平台走。
// ⚠️ 要设 archiveFileName 不能设 archiveAppendix：插件在 afterEvaluate 里才设 appendix，会盖掉这儿的；显式 fileName 不受约定影响。
tasks.withType<org.gradle.jvm.tasks.Jar>().matching { it.name == "packageUberJarForCurrentOS" }.configureEach {
    // ⚠️ BouncyCastle 的 jar 是签过名的，它的 META-INF/*.SF|RSA 摊进 uber jar 后 java -jar 直接死：
    //    `Invalid signature file digest for Manifest main attributes`（xvfb 冒烟撞见的）。OpenJDK 不要求 JCE provider 签名，丢掉即可。
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
    val tag = when (yxiOs) { "mac" -> "macos-arm64"; "win" -> "windows-x64"; else -> return@configureEach }
    archiveFileName.set("Yxi-$tag-${compose.desktop.application.nativeDistributions.packageVersion}.jar")
}
compose.desktop {
    application {
        mainClass = "app.yxi.desktop.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe)
            // ⚠️ 必须带全模块：1.1.0 的精简 runtime 缺 java.net.http（Updater 的 HttpClient 在用），
            //    真机一启动就是「Failed to launch JVM」（NoClassDefFoundError）——CI 和开发机的 smoke 都用
            //    完整 JDK 跑 uber jar，永远验不出来，只有精简后的 jpackage runtime 会炸（老板真机抓的）。
            includeAllModules = true
            packageName = "Yxi"
            packageVersion = "1.4.14"   // 也是 Velopack 的 packVersion（CI 从 app-image 的 Yxi.cfg 读）和运行时的 jpackage.app-version
            vendor = "Yxi"
            windows { menu = true; shortcut = true; iconFile.set(project.file("icon.ico")); upgradeUuid = "3f6a9d2c-7b1e-4c0a-9a3d-8e2f5b1c4d7a" }
        }
    }
}
