// Top-level build file
// AGP 9.0+ 已内置 Kotlin 支持，无需单独 apply org.jetbrains.kotlin.android
plugins {
    id("com.android.application") version "9.2.1" apply false
}

/** 一键编译所有架构 APK（arm32/arm64 × debug/release）：
 *  ./gradlew assembleAll
 */
tasks.register("assembleAll") {
    group = "build"
    description = "编译所有架构 APK（arm32/arm64 × debug/release）"
    dependsOn(
        ":app:assembleArm32Debug",
        ":app:assembleArm32Release",
        ":app:assembleArm64Debug",
        ":app:assembleArm64Release",
    )
}
