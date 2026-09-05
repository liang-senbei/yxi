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
        // ⚠️ versionCode 是**更新检查唯一比较的东西**，每次发包必须 +1。
        // versionName 只给人看。
        versionCode = 169
        versionName = "1.1.13"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ⚠️ **只留 arm64-v8a。** sherpa-onnx 的 AAR 带四套原生库，加起来 120MB；
        // 全带上 APK 会从 28MB 涨到 150MB 以上。2017 年以后的安卓手机全是 arm64，
        // 只留它 = 只多 30MB。
        // ⚠️ **代价：模拟器（x86_64）上没有语音**。那是开发用的，真机不受影响 ——
        // 但在模拟器上验语音功能会看到「这台设备不支持」，别以为是代码坏了。
        ndk { abiFilters += "arm64-v8a" }
    }

    // ⚠️ **签名是构建的一部分，不是仓库外一个脚本的事**（2026-09-04 安全审计后改）。
    //    密钥和口令都在仓库外：`~/.secrets/yxi-release.jks` + `~/.secrets/yxi-release-store.pass`（都是 600）。
    //    ⚠️ 拿不到密钥时**不配置签名**（而不是报错）：别人 clone 下来照样能 assembleDebug。
    signingConfigs {
        val ks = File(System.getProperty("user.home"), ".secrets/yxi-release.jks")
        val pwFile = File(System.getProperty("user.home"), ".secrets/yxi-release-store.pass")
        if (ks.exists() && pwFile.exists()) {
            create("release") {
                storeFile = ks
                storePassword = pwFile.readText().trim()
                keyAlias = "yxi"
                keyPassword = pwFile.readText().trim()
            }
        }
    }

    buildTypes {
        // ⚠️ **debug 补回 x86_64，不然模拟器上装不了了。**
        // release 只留 arm64（省 90MB），但模拟器是 x86_64 —— 只留 arm64 的话
        // `adb install` 直接拒绝，开发时连界面都看不到一眼。
        // buildType 的 abiFilters 跟 defaultConfig 的是**并集**，所以 debug = arm64 + x86_64。
        getByName("debug") { ndk { abiFilters += "x86_64" } }

        release {
            // 有密钥就直接签好（省掉「忘了签 / 签到旧包上」那类事故，见 #223）
            signingConfig = signingConfigs.findByName("release")
            // ⚠️ **开混淆和资源压缩**（2026-09-04 安全审计，老板点名要「反反编译」）。
            //    以前 1793 个全限定类名、方法名、.kt 源文件名原样出厂，jadx 能还原到接近源码。
            //    ⚠️ 规则见 proguard-rules.pro —— jsch / BouncyCastle / sherpa-onnx 三样靠名字找类，
            //       keep 漏一个就是「SSH 连不上 / 生成密钥失败 / 语音一按就崩」，而且编译一路绿灯。
            //       改完必须跑一遍 E2E，别信「编过了」。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }   // buildConfig：更新检查要读 VERSION_CODE
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

    // 手机上直接做语音识别（sherpa-onnx + SenseVoice）。
    // ⚠️ AAR **不进 git**（47MB），`dev/fetch-libs.sh` 下。见 android/app/libs/README。
    implementation(fileTree("libs") { include("*.aar") })

    // 仪器测试：KnownHosts 的分支表要在真机上跑，因为它依赖 android.util.Base64
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
