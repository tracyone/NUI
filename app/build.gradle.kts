plugins {
    id("com.android.application")
    // AGP 9.0+ 内置 Kotlin 支持，无需 apply org.jetbrains.kotlin.android
}

android {
    namespace = "com.nui.launcher"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.nui.launcher"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // 1. 64位优先：当前只打 arm64-v8a
        // 2. 后期支持32位：在 abiFilters 中追加 "armeabi-v7a" 即可
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9.0+ 会按 compileOptions 自动对齐 Kotlin JVM target，无需 kotlinOptions

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // 悬浮地图（内置）：OSMDroid，纯 Java、无 native 库、无需 API Key
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // 悬浮地图：后续接入高德地图时取消注释（需在 AndroidManifest 配置 API Key）
    // implementation("com.amap.api:3dmap:9.8.2")
}
