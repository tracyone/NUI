# AGENTS.md — NUI 车机桌面 · AI 开发指南

> 本文件供 AI 编码助手（Claude Code / Cursor / Copilot 等）在本仓库工作时读取。
> 目标：让 AI 一上手就知道技术栈硬约束、怎么构建部署、项目里的核心约定在哪里，避免反复试错。
> 人读的特性说明看 `README.md`；本文件只写"开发时必须知道的硬信息"。
> 本文件描述以**当前代码为准**；当它与 `docs/` 下文档冲突时，先信代码并提醒维护者更新文档。

---

## 1. 项目是什么

**NUI · 车机桌面** —— 一个 Android 横屏 Launcher（桌面启动器），为车机场景设计。
核心能力：高德车机版深度联动（悬浮地图 + 导航/巡航 HUD）、音乐控制、天气动画、系统 TTS 语音、CarPlay 风格 Dock 布局。

- **语言**：Kotlin（100%），`viewBinding` 已开启
- **包名**：`com.nui.launcher`（debug 包名自动加 `.debug` 后缀 → `com.nui.launcher.debug`）
- **入口 Activity**：`.MainActivity`（声明了 `HOME` category，是桌面）
- 主模块共 33 个 Kotlin 文件，全部在 `app/src/main/java/com/nui/launcher/` 下

---

## 2. 技术栈硬约束（不要随意改）

| 项 | 值 | 说明 |
|---|---|---|
| JDK | **17** | 构建前置要求（`compileOptions` 也是 17） |
| AGP | **9.2.1** | AGP 9.0+ 已内置 Kotlin 支持，**不要**再 `apply org.jetbrains.kotlin.android` |
| compileSdk | 37 | buildTools 36.0.0 |
| minSdk | **21**（Android 5.0） | 车机老设备多，新 API 必须做版本判断 |
| targetSdk | 34 | |
| 构建脚本 | Kotlin DSL（`.kts`） | 不要写 Groovy |
| 网络仓库 | 阿里云镜像 | 由全局 `~/.gradle/init.d/mirrors.gradle` 注入，项目里**不要**自己加 google/mavenCentral |

### ABI 分包规则（重要）

- 通过 `productFlavors` 按架构拆分：`arm32`（armeabi-v7a）、`arm64`（arm64-v8a）
- 真机原生库放 flavor 源集；**x86/x86_64 模拟器库只在 debug 构建里**，release 天然只含真机库
- 构建产物路径：`app/build/outputs/apk/<arm32|arm64>/<debug|release>/app-<arch>-<type>.apk`
- 不要用 `abiFilters` / `packaging excludes` 控原生库（AGP 9 下行为已变，项目改用源集控制）

### 签名

- release 当前**临时用 debug 签名**（保证能直接装来测试）。正式发布再换正式签名，不要改 `app/build.gradle.kts` 里这行之外的签名逻辑。

---

## 3. 目录结构速查

```
NUI/
├── app/                        # 主模块（:app），所有业务代码都在这
│   └── src/main/
│       ├── java/com/nui/launcher/
│       │   ├── MainActivity.kt          # 桌面主入口（~1300 行，总装；含昼夜广播接收器）
│       │   ├── AppListActivity.kt        # 分页应用列表
│       │   ├── DockConfig.kt / DockSlots.kt / DockPickerDialog.kt  # 左侧 Dock 配置与选择
│       │   ├── AppListAdapter.kt / AppModel.kt / RecentApps.kt / HiddenApps.kt
│       │   ├── WallpaperController.kt / UiTheme.kt   # 昼夜主题、壁纸
│       │   ├── IconUtils.kt / NuiToast.kt / StockHome.kt
│       │   ├── map/                     # 悬浮地图
│       │   │   ├── MapHost.kt           #   浮窗托管（~790 行）
│       │   │   ├── MapSource.kt / MapSources.kt  #   地图源 + showmap/closemap 广播定义
│       │   │   └── MapPickerDialog.kt
│       │   ├── nav/                     # 导航 / 巡航（核心，最复杂）
│       │   │   ├── NavInfoHost.kt       # ★收 10001/10019/60073/13011 广播 + 状态机
│       │   │   │                        #   + HUD 渲染 + 超速 + 疲劳提醒 + 巡航静音（~950 行）
│       │   │   ├── NavHost.kt           # 右侧"回家/公司"按钮 + URL Scheme + 10007 坐标导航
│       │   │   └── TrafficLightMonitor.kt  # 红绿灯倒计时语音（仅巡航）
│       │   ├── music/
│       │   │   ├── MusicHost.kt         # 播放器 UI + isMusicLike() 关键字识别 + MUSIC_PACKAGES 候选列表
│       │   │   ├── MusicListenerService.kt  # NotificationListenerService（需通知访问授权）
│       │   │   └── LyricFetcher.kt      # 网易云 LRC 歌词抓取
│       │   ├── weather/                 # WeatherActivity / WeatherFetcher / WeatherSurfaceView / WeatherVoice
│       │   ├── voice/NuiTts.kt          # ★系统 TTS 的唯一封装，全项目语音都走它
│       │   └── settings/                # SettingsActivity / SettingsDialog / KeyMapConfig / KeyMapExecutor
│       └── res/                         # 8 个 layout + drawable + mipmap
├── gpsmock/                    # :gpsmock 模块（MockGpsService，注入模拟 GPS，配合巡航测试）
├── icontest/                   # :icontest 模块（图标测试）
├── weather-screen/             # 实验/截图/模型资源目录（**不是 Gradle 模块**，勿 include）
├── docs/
│   ├── amap_auto_protocol.md           # ★ 高德车机版广播协议整理（处理导航/巡航先读）
│   ├── AmapAuto标准广播协议_20180813.pdf  # 高德官方协议原文
│   └── images/
├── test/
│   ├── TEST_SPEC.md / TEST_REPORT.md   # 测试规格与报告
│   ├── cruise_start.sh                  # ★ 一键模拟巡航（BlueStacks，注入数据并截图）
│   ├── cruise_test.sh                   # 巡航状态机测试
│   ├── gps_sim.sh                       # GPS 信号模拟（配 .md 说明）
│   └── 声音延时测试方案.md / 声音延时测试结果.md
├── scripts/gen_launcher_icon.py        # 生成多密度启动图标（需 Pillow）
├── build.sh / build.bat               # 一键编译（mac/Linux / Windows）
├── nui_deploy.bat                      # Windows 一键部署+抓日志（adb 默认连 127.0.0.1:5555）
└── settings.gradle.kts                 # 只 include :app :gpsmock :icontest
```

> 语音历史：项目早期引入过 sherpa-onnx 离线语音（`com.k2fsa.sherpa.onnx`），**该目录已在清理提交中整体删除**。当前语音只有系统 TTS（`voice/NuiTts.kt`），不要重新引入离线引擎或新建 TTS 封装。

---

## 4. 常用命令

### 构建（在仓库根目录）

```bash
./build.sh                          # 默认：全架构 × debug/release（4 个 APK）
./build.sh arm64                    # 仅 arm64（debug + release）
./build.sh arm32 release            # 仅 arm32 release
./build.sh arm64 debug -o ~/Desktop # 编译并把 APK 拷到指定目录

# 等价 Gradle：
./gradlew assembleAll               # 全架构
./gradlew :app:assembleArm64Debug   # 单个变体
```

Windows 用 `build.bat`，参数同理。

### 部署到车机 / 模拟器

```bat
:: Windows（在仓库根目录，adb 默认连 127.0.0.1:5555）
nui_deploy.bat                  :: arm32 + debug（默认，模拟器调试用）
nui_deploy.bat arm64            :: arm64 + debug
nui_deploy.bat release          :: arm32 + release（真机）
nui_deploy.bat arm64 release    :: arm64 + release
```

脚本流程（8 步）：pm clear → uninstall → install -r → **安装后再 pm clear 一次**（保证全新数据）→ 清 logcat → 启动 MainActivity → 抓 60 秒日志到设备文件。**只操作 `com.nui.launcher(.debug)`，不会动别的 App。**

macOS 手动安装：

```bash
adb install -r app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
```

### 测试（巡航/导航场景无需真车上路）

```bash
bash test/cruise_start.sh                                # BlueStacks 一键复现巡航：注入GPS→拉高德→等巡航→注测速数据→截图
bash test/cruise_start.sh <lat> <lon> <hdg> <speed>      # 自定义位置/速度
bash test/cruise_test.sh                                 # 巡航状态机测试
bash test/gps_sim.sh                                     # GPS 信号模拟
```

截图产物在 `test/shots/`。改完导航/巡航逻辑，对照 `test/TEST_REPORT.md` 检查回归。

### 确认车机 CPU 架构（选哪个 APK）

```bash
adb shell getprop ro.product.cpu.abi
# arm64-v8a → arm64 包；armeabi-v7a → arm32 包
```

---

## 5. 核心架构约定（改代码前必读）

### 5.1 高德广播是项目最复杂的部分

所有导航/巡航 HUD 数据来自**高德车机版（`com.autonavi.amapauto`）发送的系统广播**，NUI 自己不采 GPS/路况。

- 协议字段整理：**先读 `docs/amap_auto_protocol.md`**，官方原文在同目录 PDF。
- 收高德广播 Action = `AUTONAVI_STANDARD_BROADCAST_SEND`；发给高德 Action = `AUTONAVI_STANDARD_BROADCAST_RECV`。

**三个类的职责（别搞混）：**

- `nav/NavInfoHost.kt`（~950 行，核心）：注册接收器收 10001/10019/60073/13011，维护 `Mode{NONE,NAVI,CRUISE}` 状态机，渲染导航卡/巡航卡，负责超速语音、疲劳提醒、巡航静音、昼夜主动查询。
- `nav/NavHost.kt`：右侧"回家/公司"按钮；URL Scheme 一键回家/公司、长按设置坐标后用 10007 广播坐标导航。**不收状态广播、不判状态机。**
- `nav/TrafficLightMonitor.kt`：仅巡航模式下的红绿灯变灯语音。

**状态机判定（以代码为准，`NavInfoHost` 与 `TrafficLightMonitor` 一致）：**

- **10019 的 `EXTRA_STATE` 是权威判据**：`8`=导航中、`9`=导航结束、`24`=巡航进入、`25`=巡航退出。
- 10001 的转向图标辅助：导航态 `NEW_ICON/ICON ≠ 0`，巡航态 `ICON = 0`。
- **46/47 巡航进出广播实车不可靠，已弃用**，不要用；`TYPE` 字段也常缺失，不要靠它。
- 被动广播可能漏发（如启动时高德没运行）：`NavInfoHost` 启动 2 秒后会主动发 **12404**（查导航状态）、**13030**（查昼夜），并每 60 秒轮询昼夜，高德通过 10019 回传。

> 详细字段和红线见 `docs/ai/amap-broadcast.md`。

### 5.2 语音通道是统一的

- 全项目**只有 `voice/NuiTts.kt` 一处**实例化 `TextToSpeech`；天气、导航、红绿灯、按键反馈、疲劳提醒都调它。**不要**新建 TTS 实例或换离线引擎。
- 基于系统 TTS，免下载模型，专为低性能车机设计。
- **巡航期间静音高德巡航播报**：`NavInfoHost.setAmapCruiseMute()` 发 **10047 `EXTRA_CASUAL_MUTE`**（进巡航静音、退巡航/进导航恢复，临时静音单次有效，不误伤导航语音）。改模式切换逻辑时务必保留这个差异。

### 5.3 音乐控制

- `music/MusicListenerService.kt` 是 `NotificationListenerService`（需用户在系统设置授权通知访问）+ MediaSession 双通道。
- 音乐 App 识别在 **`MusicHost.kt` 的 `isMusicLike(pm, ai)`**：用包名关键词 `pkgKeywords` + 应用名关键词 `labelKeywords` 模糊匹配（`pkg.contains(...)`），**不依赖枚举包名**，车厂定制版也能识别。
- 另有 `MusicHost.MUSIC_PACKAGES`（精确包名集合）用于**启动卡/Dock 绑定的候选列表**，和模糊识别是两套。
- **新增支持音乐 App**：① AndroidManifest `<queries>` 加包名（当前 12 个）；② 需要被模糊识别就加关键词、要进绑定候选列表就加 `MUSIC_PACKAGES`。
- 歌词：`music/LyricFetcher.kt` 统一走网易云公开接口（`music.163.com`），按"歌名+歌手"搜索抓 LRC；QQ/酷狗/酷我等也都用这个源。

### 5.4 主题 / 壁纸

- 昼夜外观由 `UiTheme.kt` + `WallpaperController.kt` 管理；`MainActivity.amapDayNightReceiver` 收 10019 后，仅在 `UiTheme.Mode.FOLLOW_MAP` 模式下同步。
- **昼夜值当前写死 37=白天、38=夜晚，其它值直接忽略**（`MainActivity` 的 `when(state)`）。注意：`docs/amap_auto_protocol.md` 提到部分高德版本实测为 38/40 并建议用"奇偶"判断，但**代码尚未实现奇偶兼容**——在那些版本上"跟随地图"昼夜可能不生效，改动前先确认。
- 白天/夜晚双壁纸槽位，缺省逐级回退。
- **已知坑**：多实例下外观去重用实例级 `appliedMapDark` 标志；后台 `invalidate` 会丢弃刷新，回前台要强制重排重绘。改主题先看 git log 里 `fix(theme):` 的提交。

### 5.5 车机专属

- 方向盘按键映射：`settings/KeyMapConfig.kt` + `KeyMapExecutor.kt`。
- 地图自启动时序（桌面启动后第几秒拉起高德、第几秒返回）可配，在设置里。
- **疲劳驾驶提醒**：在 `NavInfoHost.kt`（常量 `FATIGUE_FIRST_MS=90分钟`、`FATIGUE_INTERVAL_MS=2分钟`、`FATIGUE_REMIND_TIMES=3`）。进入导航**或巡航**（驾驶态）开始计时，90 分钟首次提醒、每隔 2 分钟一次共 3 次，之后重置进入下一轮 90 分钟；退出驾驶态停止/重置。
- 红绿灯变灯语音：`TrafficLightMonitor.kt`，仅巡航、车速 `≤ 25 km/h`（常量 `CRUISE_SPEED_THRESHOLD`）、红灯倒计时 `≤ 3 秒`时提醒。
- 一键重启桌面、关于页在 `settings/`。

---

## 6. 常见任务怎么做

| 任务 | 入口 |
|---|---|
| 新增一个桌面设置项 | `settings/SettingsActivity.kt` + `SettingsDialog.kt`，注意 SharedPreferences 键命名 |
| 新增导航/巡航字段展示 | 读 `docs/amap_auto_protocol.md` → 在 `NavInfoHost.kt` 接收解析 + 渲染 |
| 改状态机判定 | `NavInfoHost.kt` 的 10019/10001 处理与 `Mode` 枚举；`TrafficLightMonitor.kt` 有独立模式标志需同步 |
| 改超速 / 疲劳 / 巡航静音阈值 | 都在 `NavInfoHost.kt`（`checkOverspeed()` / `FATIGUE_*` / `setAmapCruiseMute()`） |
| 改红绿灯提醒 | `TrafficLightMonitor.kt` |
| 改回家/公司/收藏直达 | `NavHost.kt`（URL Scheme 与 10007 广播） |
| 新增支持一个音乐 App | Manifest `<queries>` 加包名 + `MusicHost.isMusicLike()` 关键词 / `MUSIC_PACKAGES` 候选 |
| 新增语音播报场景 | 调 `NuiTts` 的统一播报接口，不要新建 TTS 实例 |
| 改桌面布局/Dock | `MainActivity.kt` + res/layout，注意按 DPI/分辨率算每页容量 |
| 生成新启动图标 | `python3 scripts/gen_launcher_icon.py`（需 Pillow） |

---

## 7. 提交信息规范

沿用仓库现有习惯（Conventional Commits 中文范围）：

```
feat(nav): 新增服务区距离展示
fix(theme): 修复多实例外观不跟随
build: 规范化一键编译脚本
docs: 合并 doc/ 到 docs/
chore: 删除无引用死代码
```

类型：`feat` `fix` `build` `docs` `chore` `refactor`；括号里写模块（nav / theme / music / map …）。

---

## 8. 踩坑清单（AI 别再踩）

1. **不要**给 AGP 9 项目手动 apply Kotlin 插件。
2. **不要**在项目里加 google()/mavenCentral()——镜像由全局 init 脚本管。
3. **不要**用 `abiFilters` 控 ABI，项目走 flavor 源集。
4. **不要**重新引入 sherpa-onnx / 离线语音（已整体删除），语音只用系统 TTS 的 `NuiTts`。
5. **不要**用 46/47 广播或 `TYPE` 字段判巡航/导航——以 10019 的 STATE（8/9/24/25）为权威，ICON 辅助。
6. **不要**把状态机/超速/疲劳/巡航静音逻辑加到 `NavHost`——那些都在 `NavInfoHost`；`NavHost` 只管回家/公司按钮。
7. minSdk 21：用任何高版本 API 前先确认是否需要 `Build.VERSION` 判断。
8. 车机是**横屏**、性能弱——UI 按大字号/大点击区设计，别照搬手机竖屏布局。
9. 改高德广播处理前，必须对照 `docs/amap_auto_protocol.md` 与代码注释（文档个别处滞后于代码，以代码为准）。
10. `weather-screen/` 不是 Gradle 模块，别 `include` 进 settings.gradle。
11. 新增音乐 App 只改关键词不够，Manifest `<queries>` 也要加（Android 11+ 包可见性限制）。

---

## 9. 详细 Skill 索引

高频、复杂场景的展开文档放在 `docs/ai/`：

- `docs/ai/amap-broadcast.md` —— 处理高德导航/巡航广播的完整工作流
- `docs/ai/build-and-deploy.md` —— 构建、部署、抓日志的速查与排错
- `docs/ai/adding-feature.md` —— 新增功能/页面的标准流程

> 当任务命中上述场景时，先读对应 skill 再动手。
