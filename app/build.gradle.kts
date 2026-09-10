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
        versionCode = 21
        versionName = "0.0.21"
        // ABI 由 productFlavors 拆分（arm32 / arm64），见下
    }

    // 按架构拆包（AGP 9 下 abiFilters/packaging excludes 行为已变，改用源集控制原生库）：
    //   arm32/      : armeabi-v7a（ARM 32 位车机）
    //   arm64/      : arm64-v8a（64 位车机）
    //   arm32Debug/ : x86（32 位 x86 模拟器，仅 debug 打包）
    //   arm64Debug/ : x86_64（64 位 x86 模拟器，仅 debug 打包）
    // 真机 ARM 库放 flavor 源集、模拟器库放 buildType 源集，release 天然只含真机库。
    flavorDimensions += "arch"
    productFlavors {
        create("arm32") {
            dimension = "arch"
        }
        create("arm64") {
            dimension = "arch"
        }
    }

    buildTypes {
        release {
            // 压缩：代码混淆 + 资源收缩 + 原生库压缩存储
            isMinifyEnabled = true
            isShrinkResources = true
            // 临时用 debug 签名，保证 release 包可直接安装测试；正式发布时换成正式签名即可
            signingConfig = signingConfigs.getByName("debug")
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
        aidl = true
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
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
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
