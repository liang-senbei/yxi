plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.yxi"
    // compileSdk 37：AndroidX 2026.08 起要求 37（36 会 checkDebugAarMetadata 失败）。
    // minSdk 26 保覆盖面 —— 运行时判版本，新机吃好体验，老机照常能用。见 PRD §2.7
    compileSdk = 37

    defaultConfig {
        applicationId = "app.yxi"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    sourceSets["main"].kotlin.directories.add("src/main/kotlin")
    sourceSets["androidTest"].kotlin.directories.add("src/androidTest/kotlin")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.jsch)
    implementation(libs.termlib)
    implementation(libs.markdown)
    implementation(libs.markdown.m3)
    implementation(libs.bouncycastle)
    implementation(libs.kotlinx.coroutines.android)

    // 仪器测试：KnownHosts 的分支表要在真机上跑，因为它依赖 android.util.Base64
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
