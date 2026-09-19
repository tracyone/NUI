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
