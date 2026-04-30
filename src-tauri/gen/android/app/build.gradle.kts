import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("rust")
}

val tauriProperties = Properties().apply {
    val propFile = file("tauri.properties")
    if (propFile.exists()) {
        propFile.inputStream().use { load(it) }
    }
}

android {
    compileSdk = 36
    namespace = "app.claudio.desktop"
    defaultConfig {
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        applicationId = "app.claudio.desktop"
        minSdk = 24
        targetSdk = 36
        versionCode = tauriProperties.getProperty("tauri.android.versionCode", "1").toInt()
        versionName = tauriProperties.getProperty("tauri.android.versionName", "1.0")
    }
    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = false
            packaging {                jniLibs.keepDebugSymbols.add("*/arm64-v8a/*.so")
                jniLibs.keepDebugSymbols.add("*/armeabi-v7a/*.so")
                jniLibs.keepDebugSymbols.add("*/x86/*.so")
                jniLibs.keepDebugSymbols.add("*/x86_64/*.so")
            }
        }
        getByName("release") {
            // 个人侧载需要 APK 已签名 —— 直接复用 Android 自动生成的 debug keystore
            // （位于 ~/.android/debug.keystore）。这个 keystore 不应该用于 Play
            // Store 发布，但本地 / 自家设备装是足够的。等真要发布再换正式 keystore。
            signingConfig = signingConfigs.getByName("debug")
            // ProGuard 关掉：Tauri 暴露给 webview 的 Kotlin 类（Activity / Service /
            // RustWebView 等）数量不少，全部加 keep 规则比较啰嗦，先关掉换稳定性。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                *fileTree(".") { include("**/*.pro") }
                    .plus(getDefaultProguardFile("proguard-android-optimize.txt"))
                    .toList().toTypedArray()
            )
        }
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        buildConfig = true
    }
}

rust {
    rootDirRel = "../../../"
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    // androidx.media：MediaSessionCompat + NotificationCompat.MediaStyle，
    // 给系统 "Now Playing" 卡片（锁屏 / 通知抽屉）和耳机按键路由用。
    // 用 compat 版本而不是 androidx.media3，因为我们不需要 ExoPlayer / 播放器 UI ——
    // 只需要"会话状态广播 + 媒体样式通知"，这部分 API 在 media-compat 1.7.0 已稳定。
    implementation("androidx.media:media:1.7.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
}

apply(from = "tauri.build.gradle.kts")