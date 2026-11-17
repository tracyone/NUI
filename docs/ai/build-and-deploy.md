# Skill：构建、部署与抓日志

> 触发场景：改完代码要出 APK、要装到车机/模拟器、要看日志定位问题。

---

## 1. 前置条件

- JDK 17
- Android SDK：`local.properties` 里配 `sdk.dir`，或设 `ANDROID_HOME`
- 仓库根目录执行命令

---

## 2. 出 APK

```bash
./build.sh                          # 全架构 × debug/release（4 个包）
./build.sh arm64                    # arm64（debug + release）
./build.sh arm32 release            # 只要 arm32 release
./build.sh arm64 debug -o ~/Desktop # 出包并复制到指定目录
./build.sh -h                       # 帮助
```

产物固定在：
```
app/build/outputs/apk/<arm32|arm64>/<debug|release>/app-<arch>-<type>.apk
```

> 只改了一点代码想快速验证，用 `./build.sh arm64 debug` 就够，别每次出全量。

---

## 3. 装到设备

### 选哪个架构的包

```bash
adb shell getprop ro.product.cpu.abi
# arm64-v8a  → arm64 包
# armeabi-v7a → arm32 包
```

### Windows 车机 / 模拟器（推荐一键脚本）

仓库根目录：
```bat
nui_deploy.bat                  :: arm32 + debug（默认，模拟器调试）
nui_deploy.bat arm64            :: arm64 + debug
nui_deploy.bat release         :: arm32 + release（真机）
nui_deploy.bat arm64 release
```
脚本 8 步：`pm clear` → `uninstall` → `install -r` → **安装后再 `pm clear` 一次**（双重保险保证全新数据）→ 清 logcat → 启动 MainActivity → 抓 **60 秒**日志。
adb 默认连 `127.0.0.1:5555`（模拟器）；连真机改脚本里的 `ADB_TARGET`。
日志先落在设备 `/mnt/sdcard/nui_launcher.log`，再 pull 到脚本目录的 `nui_launcher.log`。

### macOS / 手动

```bash
adb install -r app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
```

---

## 4. 抓日志

```bash
# 只看 NUI 进程
adb shell pidof com.nui.launcher.debug
adb logcat --pid=<上面的pid>

# 或按 tag 过滤（项目实际 TAG）
adb logcat -s "NUI.NavInfo:*" "NUI.TrafficLight:*" "NuiTts:*"

# nui_deploy.bat 会自动抓 60 秒并 pull 成本地 nui_launcher.log，Windows 下直接看它
```

定位高德广播 / 状态机 / 超速问题，重点看 `NUI.NavInfo`（NavInfoHost）和 `NUI.TrafficLight`（TrafficLightMonitor）；`NavHost` 管回家/公司，不打状态机日志。

---

## 5. 常见报错

| 现象 | 处理 |
|---|---|
| 编译报 Kotlin 插件相关 | 不要手动 apply `org.jetbrains.kotlin.android`，AGP 9 已内置 |
| 依赖下载慢/失败 | 仓库镜像由 `~/.gradle/init.d/mirrors.gradle` 统一管，别在项目里加仓库 |
| release 装不上 | 确认用的是 release 包（包名无 `.debug`）；当前 release 临时用 debug 签名 |
| 模拟器上跑不起来 | 用 debug 包（debug 才含 x86/x86_64 原生库） |
| 装完不是桌面 | 按 Home 键选 NUI 为默认桌面；确认 Manifest 有 HOME category |
| 悬浮地图不显示 | 先授予悬浮窗权限（`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`） |
| 音乐控制没反应 | 需要用户授权"通知访问"，且目标音乐 App 在 Manifest `<queries>` 里有包名 |

---

## 6. 提交前自检

- [ ] `./build.sh arm64 debug` 能编过
- [ ] 改了导航/巡航相关 → 跑 `test/cruise_start.sh`（一键复现巡航并截图）或 `test/cruise_test.sh` 不回归
- [ ] minSdk 21 兼容：新 API 有版本判断
- [ ] commit message 符合 `type(scope):` 格式

---

## 7. 实测注意：启动时序与翻页（AI 必看）

### 7.1 装完包不会自己回到前台

`adb install -r` 之后 NUI 不会自动起来，必须手动拉：

```bash
adb -s 127.0.0.1:5555 shell am start -n com.nui.launcher.debug/.MainActivity
# release 包：am start -n com.nui.launcher/.MainActivity
```

### 7.2 前 12 秒不要测 UI（预热高德）

NUI 启动后默认**第 5 秒自动拉起高德、第 10 秒回桌面**（回桌面后 2.5 秒还有一次兜底返回，防止慢设备上高德抢回前台）。
所以装包/重启后立刻截图，很可能拍到"高德在前台、地图全屏"——这不是布局坏了，也不是浮窗尺寸错了。
**等 ≥12 秒再开始截图和点按**。想关掉这条路只能改设置：地图 →「绑定地图应用」选「内置地图（OpenStreetMap）」（该类型不启动外部地图），或把「启动高德延迟」调大。

### 7.3 翻页：滑动起点必须避开地图浮窗

桌面页地图是 WindowManager 浮窗（1280×720 下约 x=116~880）。起点落进浮窗的 `input swipe` 会被它吞掉，页翻不动。可用手势：

```bash
# 桌面页 → 负一屏（向右滑），起点在浮窗左侧缝隙
adb shell input swipe 105 150 1260 150 200
# 反过来（负一屏 → 桌面页）
adb shell input swipe 1260 150 105 150 200
```

巡航"大屏"状态下浮窗接近全屏、缝隙也消失，这时先踩住时序：

```bash
adb shell am force-stop com.nui.launcher
adb shell am start -n com.nui.launcher/.MainActivity
sleep 8          # 等布局稳定但还没到高德预热窗口
adb shell input swipe 105 150 1260 150 200
```
（或在浮窗缩小后从右侧边缘向右滑，`MainActivity.dispatchTouchEvent` 有右边缘手势。）

### 7.4 点按钮前先 dump 真实 bounds，别按截图目测

```bash
adb shell uiautomator dump /sdcard/umi.xml
adb shell cat /sdcard/umi.xml | tr '<' '\n<' | grep -E 'btnMinusPlay|btnMinusPrev|btnMinusNext'
```

负一屏底栏播放键实测 `[1152,670][1196,714]`（中心 **1174,692**），按截图目测取 y=654 会点空。

### 7.5 注入巡航红绿灯（60073）

```bash
adb shell am broadcast -p com.nui.launcher -a AUTONAVI_STANDARD_BROADCAST_SEND \
  --ei KEY_TYPE 60073 --ei size 3 --ei index 0 --ei dir 2 --ei trafficLightStatus 0 --ei redLightCountDownSeconds 30
adb shell am broadcast -p com.nui.launcher -a AUTONAVI_STANDARD_BROADCAST_SEND \
  --ei KEY_TYPE 60073 --ei size 3 --ei index 1 --ei dir 1 --ei trafficLightStatus 1 --ei greenLightLastSecond 15
adb shell am broadcast -p com.nui.launcher -a AUTONAVI_STANDARD_BROADCAST_SEND \
  --ei KEY_TYPE 60073 --ei size 3 --ei index 2 --ei dir 3 --ei trafficLightStatus 0 --ei redLightCountDownSeconds 45
```

`dir`：1 左 / 2 直 / 3 右 / 4 掉头；`trafficLightStatus`：0 红 / 1 绿 / 2 黄。灯条约 8 秒无新帧会自动隐藏（`LIGHT_TIMEOUT_MS`），验证"隐藏不占位"就等 10 秒再截图。

调试截图统一落 `~/Desktop`（维护者习惯）。
