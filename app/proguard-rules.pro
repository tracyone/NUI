# Keep generic
-keep class com.nui.launcher.** { *; }


# OSMDroid 悬浮地图（反射加载 tile provider / 网络 provider，混淆会挂）
-keep class org.osmdroid.** { *; }
-keep class org.slf4j.** { *; }
-dontwarn org.osmdroid.**
-dontwarn org.slf4j.**

# 高德地图相关（接入后启用）
# -keep class com.amap.api.** { *; }
# -keep class com.autonavi.** { *; }
