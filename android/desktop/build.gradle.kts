// Yxi 桌面版（Windows；老板 2026-09-08：「像 Claude Desktop / ChatGPT Windows 版那样」）。
// Compose Multiplatform（JVM），SSH 还是 jsch；跟手机端共用 :core。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.jsch)
    implementation(libs.org.json)
    implementation(libs.kotlinx.coroutines.swing)
}
compose.desktop {
    application {
        mainClass = "app.yxi.desktop.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe)
            packageName = "Yxi"
            packageVersion = "1.0.0"
            vendor = "Yxi"
            windows { menu = true; shortcut = true; upgradeUuid = "3f6a9d2c-7b1e-4c0a-9a3d-yxi000000001".replace("yxi000000001", "8e2f5b1c4d7a") }
        }
    }
}
