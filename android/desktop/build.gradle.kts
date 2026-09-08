// Yxi 桌面版（Windows；老板 2026-09-08：「像 Claude Desktop / ChatGPT Windows 版那样」）。
// Compose Multiplatform（JVM），SSH 还是 jsch；跟手机端共用 :core。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
kotlin { jvmToolchain(17) }

// 在 Linux 服务器上给别的平台打 uber jar（差别只是 Skia 的原生库）：-Pyxi.os=mac / win，不传 = 本机。
// jpackage（Msi/Exe）跨不了平台，那个只能在 Windows 上打（.github/workflows/desktop.yml）。
val yxiOs = project.findProperty("yxi.os") as String?
dependencies {
    implementation(project(":core"))
    implementation(when (yxiOs) { "mac" -> compose.desktop.macos_arm64; "win" -> compose.desktop.windows_x64; else -> compose.desktop.currentOs })
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.jsch)
    implementation(libs.org.json)
    implementation(libs.kotlinx.coroutines.swing)
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
            packageName = "Yxi"
            packageVersion = "1.0.0"
            vendor = "Yxi"
            windows { menu = true; shortcut = true; upgradeUuid = "3f6a9d2c-7b1e-4c0a-9a3d-8e2f5b1c4d7a" }
        }
    }
}
