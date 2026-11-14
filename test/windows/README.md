NUI GPS 模拟测试包（Windows / 32 位模拟器 / 兼容低版本 Android）
====================================================================

文件说明
--------
gpsmock-debug.apk     GPS 模拟 App（纯 Java/Kotlin，无 native so，32/64 位通用）
gps_sim.bat           模拟定位控制脚本
cruise_light_test.bat 巡航红绿灯往返测试脚本
diag.bat              一键诊断（地图不动时先跑这个）
README.txt            本文件

与旧版 am 的兼容性
------------------
低版本 Android 的 am 不支持：
  - am start-foreground-service   -> 脚本改用 am startservice
  - --ed（Double extra）          -> 脚本改用 --es 传字符串，APK 侧已支持解析
保留可用的：
  - --ef（Float extra）用于 heading / speed
APK 已改为按 extra 实际类型解析（String / Float / Double 都认）。
lat/lon 同时用 --es（字符串）和 --ef latf/lonf（Float 兜底）两条通道传，
旧版 am 无论哪条能用，坐标都能生效。
本包 APK 为 versionCode=2 / versionName=1.1，install 后会打印实际版本，
如果不是 versionCode=2，说明设备上还是旧包。

快速开始
--------
1) 安装 + 授权（只需一次）
     gps_sim.bat install

2) 让车动起来（珠海示例，40km/h 向东）
     gps_sim.bat move 22.278211 113.542516 90 40

3) 确认注入生效
     gps_sim.bat status

4) 停止
     gps_sim.bat stop

5) 红绿灯往返测试（默认每段 20 秒、10km/h）
     cruise_light_test.bat
     cruise_light_test.bat 90 63    （先跑这个让高德进入巡航态）

模拟位置授权（脚本自动判断）
---------------------------
SDK >= 23：appops set com.nui.gpsmock android:mock_location allow
           settings put secure mock_location com.nui.gpsmock
SDK <  23：settings put secure mock_location 1
（旧系统还需要在开发者选项里打开"允许模拟位置"）

地图不动怎么办
--------------
运行 diag.bat，生成同目录的 diag.txt，重点看：
  - 第 4 段：授权值是否正确
  - 第 5 段：有没有 gpsmock 进程
  - 第 7 段：NUI.GpsMock 日志（SET/MOVE 是否收到、inject 是否失败）
  - 第 8 段：崩溃信息

多设备
------
脚本自动取第一台设备。多设备时打开 gps_sim.bat，把
    set "SERIAL="
改成
    set "SERIAL=emulator-5554"

原理
----
gpsmock 用 LocationManager.addTestProvider / setTestProviderLocation 注入系统 GPS 位置，
高德认为车辆在移动，自动进入巡航或沿路线导航，并发出真实的
AUTONAVI_STANDARD_BROADCAST_SEND 广播，NUI 据此更新巡航卡、红绿灯和语音提醒。
全程是真实广播链路，不伪造 NUI 侧数据。
