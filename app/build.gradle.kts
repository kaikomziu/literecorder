plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.literecorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.literecorder"
        // 内部音声の録音（AudioPlaybackCapture）と MediaStore の相対パス保存が使える Android 10 以上
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // 超軽量にするため、使っていないコードとリソースを削る
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 自分で配布する用途なので、デバッグ鍵で署名して apk をそのまま入れられるようにする
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += listOf("META-INF/*.version", "META-INF/*.kotlin_module", "**/*.kotlin_builtins", "DebugProbesKt.bin")
        }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // MP3 エンコーダ（LAME の純 Java 移植）。javax.sound に依存する高水準 API は使わず、低水準 API だけ使う
    implementation("de.sciss:jump3r:1.0.5")
}
