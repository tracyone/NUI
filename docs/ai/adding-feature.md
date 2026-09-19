# Skill：新增功能 / 页面的标准流程

> 触发场景：要加一个新设置项、新桌面卡片、新语音场景、新音乐 App 支持等。

---

## 0. 先判断改动落在哪一层

NUI 的业务都在 `:app` 模块 `com.nui.launcher` 下，按业务分包：

```
map/       悬浮地图          nav/      导航/巡航广播、状态机、HUD
music/     音乐控制          weather/  天气
voice/     语音（统一 TTS）   settings/ 设置
（根包）   桌面主体、Dock、主题、应用列表
```

> nav 子包内部分工：`NavInfoHost`（收广播/状态机/HUD/超速/疲劳/静音）、`NavHost`（回家/公司按钮+URL Scheme）、`TrafficLightMonitor`（红绿灯）。

新功能优先放进对应子包；跨多个子包的，在根包加一个协调类，别把逻辑堆进 `MainActivity`。

---

## 1. 标准步骤

### Step 1：定位现有同类实现
- 先在对应子包里找一个已有的相似功能，照着它的写法来（命名、生命周期、与 Host 的通信方式）。
- 不要新发明一套模式——比如语音播报一定走 `voice/NuiTts.kt`，不要直接 new `TextToSpeech`。

### Step 2：写代码
- Kotlin，开了 viewBinding：新布局在 `res/layout/`，绑定类自动生成。
- minSdk 21：用到高版本 API 时加 `Build.VERSION.SDK_INT` 判断。
- 横屏车机：点击区要大、字号要大，别照搬手机竖屏布局。

### Step 3：注册（如果需要）
- 新 Activity：在 `app/src/main/AndroidManifest.xml` 注册。
- 新高德广播字段：在 `nav/NavInfoHost.kt` 的接收器里解析（`TrafficLightMonitor` 有独立接收器，红绿灯相关才动它）；**不要加到 `NavHost`**。
- 新音乐 App 支持：① Manifest `<queries>` 加包名；② `music/MusicHost.kt` 里——要被模糊识别就给 `isMusicLike()` 的关键词列表加关键字，要进 Dock 绑定候选就加 `MUSIC_PACKAGES`。
- 新权限：在 Manifest 加，并在设置页引导用户授权（悬浮窗、通知访问等）。

### Step 4：设置项（如果是可配置项）
- 改 `settings/SettingsActivity.kt` + `SettingsDialog.kt`。
- SharedPreferences 键命名沿用现有风格，别和已有键冲突。

### Step 5：验证
```bash
./build.sh arm64 debug -o ~/Desktop   # 出包
# 装机后手动过一遍；涉及导航/巡航的优先跑 test/cruise_start.sh（一键复现巡航并截图）
```

### Step 6：提交
```
feat(nav): 新增高速服务区距离展示
fix(theme): 修复多实例外观不跟随
```
类型：`feat` `fix` `build` `docs` `chore` `refactor`；括号写模块名。

---

## 2. 几个高频场景的快捷入口

| 我想… | 从哪开始 |
|---|---|
| 加一个设置开关 | `settings/SettingsActivity.kt` 找最像的一个项照着抄 |
| 加一段语音播报 | 调 `NuiTts` 的播报接口，别新建 TTS |
| 在 HUD 加一个导航数据 | 读 `amap-broadcast.md` skill → 在 `NavInfoHost` 接收解析并渲染（不是 NavHost） |
| 支持新音乐 App | Manifest `<queries>` 加包名 + `MusicHost.isMusicLike()` 关键词（或 `MUSIC_PACKAGES` 候选） |
| 改桌面布局/Dock | `MainActivity.kt` + `res/layout`，注意按 DPI 算每页容量 |
| 换启动图标 | `python3 scripts/gen_launcher_icon.py` |

---

## 3. 不要做的事

- 不要把新逻辑直接堆进 `MainActivity.kt`（已经 ~1300 行，是总装类不是杂物间）。
- 不要绕过 `NuiTts` 直接 new `TextToSpeech`。
- 不要重新引入 sherpa-onnx / 离线语音（`com.k2fsa...` 目录已整体删除），语音只用系统 TTS。
- 不要把状态机/超速/疲劳/巡航静音逻辑放进 `NavHost`——那些归 `NavInfoHost`。
- 不要把 `weather-screen/` 当 Gradle 模块 include。
- 不要在没有真机验证时声称车机场景已通过——导航/巡航逻辑至少在模拟器跑过 `test/cruise_start.sh`。
