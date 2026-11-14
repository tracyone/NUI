plugins {
    id("com.android.application")
}

android {
    namespace = "com.nui.gpsmock"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nui.gpsmock"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
