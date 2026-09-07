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
        versionCode = 13
        versionName = "0.0.13"
        // ABI 由 productFlavors 拆分（arm32 / arm64），见下
    }

    // 按架构拆包：
    //   arm32 : armeabi-v7a（ARM 32 位车机）+ x86（32 位 x86 模拟器/BlueStacks）——debug 用
    //   arm64 : arm64-v8a（64 位车机/模拟器）+ x86_64（64 位 x86 模拟器）
    // release 变体通过 buildTypes.release 的 packaging 排除模拟器 x86 库，只保留真机 ARM 库以减小体积
    flavorDimensions += "arch"
    productFlavors {
        create("arm32") {
            dimension = "arch"
            ndk { abiFilters += listOf("armeabi-v7a", "x86") }
        }
        create("arm64") {
            dimension = "arch"
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
    }

    buildTypes {
        release {
            // 压缩：代码混淆 + 资源收缩 + 原生库压缩存储
            isMinifyEnabled = true
            isShrinkResources = true
            // 临时用 debug 签名，保证 release 包可直接安装测试；正式发布时换成正式签名即可
            signingConfig = signingConfigs.getByName("debug")
            // release 只留真机 ARM 库，去掉模拟器 x86/x86_64 库（debug 包保留，供模拟器测试）
            packaging {
                jniLibs {
                    excludes += listOf("lib/x86/**", "lib/x86_64/**")
                }
            }
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
