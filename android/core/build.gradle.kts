// 手机（Android）和桌面（Windows）共用的纯 Kotlin：不许 import android.*
plugins { alias(libs.plugins.kotlin.jvm) }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(libs.org.json)
    api(libs.kotlinx.coroutines.core)
    api(libs.jsch)
    api(libs.bouncycastle)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
