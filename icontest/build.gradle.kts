plugins {
    id("com.android.application")
}

android {
    namespace = "com.nui.icontest"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // 图标排序测试：三个对照 flavor
    //  noicon      = AAA无图标（完全不声明 icon，系统给默认图标）
    //  defaulticon = AAB无图标（显式引用 @android:drawable/sym_def_app_icon）
    //  withicon    = BBB有图标（自定义 mipmap 图标）
    flavorDimensions += "icon"
    productFlavors {
        create("noicon") {
            dimension = "icon"
            applicationId = "com.nui.icontest.noicon"
        }
        create("defaulticon") {
            dimension = "icon"
            applicationId = "com.nui.icontest.defaulticon"
        }
        create("withicon") {
            dimension = "icon"
            applicationId = "com.nui.icontest.withicon"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
