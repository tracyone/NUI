# NUI 桌面 - 高德地图车机版接口文档

本文档整理 NUI 桌面与高德地图车机版（com.autonavi.amapauto）交互时使用的广播协议和 URL Scheme，包括已实现、已验证和待实现的接口。

---

## 一、广播协议

高德地图车机版通过系统广播与第三方应用通信，分为两类：

- **高德发送的广播**：Action = `AUTONAVI_STANDARD_BROADCAST_SEND`
- **发送给高德的广播**：Action = `AUTONAVI_STANDARD_BROADCAST_RECV`

### 1.1 高德发送的广播（AUTONAVI_STANDARD_BROADCAST_SEND）

第三方应用注册 BroadcastReceiver 监听此 Action，通过 `KEY_TYPE` 区分不同数据类型。

| KEY_TYPE | 含义 | 关键字段 | 状态 |
|----------|------|----------|------|
| 10001 | 导航/巡航信息（核心） | `CUR_SPEED`：当前速度<br>`LIMITED_SPEED`：限速<br>`ICON`/`NEW_ICON`：转向图标<br>`SEG_REMAIN_DIS_AUTO`：段剩余距离<br>`ROUTE_REMAIN_DIS`/`_AUTO`：全程剩余距离<br>`ROUTE_REMAIN_TIME_AUTO`：剩余时间<br>`ETA_TEXT`：预计到达<br>`NEXT_ROAD_NAME`/`CUR_ROAD_NAME`：下一条/当前道路名<br>`ROUTE_ALL_DIS`：全程总距离<br>`CAMERA_DIST`/`CAMERA_SPEED`：电子眼距离/限速<br>`endPOIName`：终点名称<br>`TRAFFIC_LIGHT_NUM`：红绿灯总数<br>`EXIT_NAME_INFO`/`EXIT_DIRECTION_INFO`：出口信息<br>`CAR_DIRECTION`：车头方向 | 🔍 已检索，待实现 |
| 10019 | 昼夜模式变化 | `EXTRA_STATE`：昼夜模式值 | ✅ 已实现（跟随地图外观） |
| 60073 | 红绿灯数据 | `trafficLightStatus`：灯状态（红/黄/绿）<br>`dir`：方向<br>`redLightCountDownSeconds`：倒计时秒数<br>`lightsData`：巡航模式 JSON 数组（多方向红绿灯） | 🔍 已检索，待实现 |
| 13011 | TMC 路况 | `EXTRA_TMC_SEGMENT`：JSON 路况分段数据 | 🔍 已检索，待实现 |
| 13012 | 车道线 | `EXTRA_DRIVE_WAY`：车道线 JSON 数据 | 🔍 已检索，待实现 |

#### 10019 昼夜模式 - 详细说明

- **字段**：`EXTRA_STATE`（int）
- **文档值**：37 = 白天，38 = 夜晚
- **实测值**：部分版本为 38 = 白天，40 = 夜晚
- **兼容判断**：**偶数 = 夜晚，奇数 = 白天**（覆盖 37/38/39/40 等所有已知值）
- **NUI 实现**：`MainActivity.amapDayNightReceiver`，收到后保存状态并调用 `refreshForThemeChange()`

### 1.2 发送给高德的广播（AUTONAVI_STANDARD_BROADCAST_RECV）

第三方应用通过 `sendBroadcast()` 发送，需指定 Component 为 `com.autonavi.amapauto.adapter.internal.AmapAutoBroadcastReceiver`。

| KEY_TYPE | 含义 | 关键字段 | 状态 |
|----------|------|----------|------|
| 10007 | 直接导航（坐标导航） | `EXTRA_DNAME`：终点名称<br>`EXTRA_DLAT`：终点纬度（double）<br>`EXTRA_DLON`：终点经度（double）<br>`ENTRY_LAT`：到达点纬度<br>`ENTRY_LON`：到达点经度<br>`EXTRA_DEV`：0 = gcj02 已加密<br>`EXTRA_M`：-1 = 默认规划策略<br>`SOURCE_APP`：来源应用名 | ✅ 已实现（真机验证成功） |
| 10048 | 设置昼夜模式 | `EXTRA_STATE`：0 = 自动，1 = 白天，2 = 夜晚 | ✅ 已验证（用于测试跟随地图功能） |

#### 10007 直接导航 - 详细说明

- **效果**：进入路线规划 + 5 秒倒计时自动开始导航
- **前提**：高德需在前台，建议先启动高德预热 3 秒再发送广播
- **NUI 实现**：`NavHost.sendNaviBroadcast()`

### 1.3 悬浮地图显示/关闭广播

| Action | 含义 | 状态 |
|--------|------|------|
| `com.autonavi.plus.showmap` | 显示高德悬浮地图 | ✅ 已实现 |
| `com.autonavi.plus.closemap` | 关闭高德悬浮地图 | ✅ 已实现 |

- **NUI 实现**：`MapSources.kt` 中定义，`MapHost` 调用
- **注意**：需先授予悬浮窗权限（`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`）

---

## 二、URL Scheme

通过 `Intent.ACTION_VIEW` + `Uri.parse()` 调用，需 `setPackage("com.autonavi.amapauto")`。

| URL Scheme | 功能 | 参数 | 状态 |
|------------|------|------|------|
| `androidauto://navi2SpecialDest` | 一键回家/公司 | `sourceApplication`：来源应用名<br>`dest`：`home` = 回家，`crop` = 去公司 | ✅ 已实现（真机验证成功） |
| `androidauto://openFavorite` | 打开收藏夹 | `sourceApplication`：来源应用名 | ✅ 已实现（真机验证成功） |
| `androidauto://rootmap` | 打开高德主地图 | `sourceApplication`：来源应用名 | ✅ 已实现（兜底用） |
| `androidauto://navi` | 坐标导航 | `sourceApplication`、`lat`、`lon`、`poiname`、`dev` | ⚠️ 车机版实测无效，仅作兜底 |
| `androidauto://route` | 路线规划 | `sourceApplication`、`dlat`、`dlon`、`dname`、`dev`、`m` | ⚠️ 兜底用 |

### 2.1 navi2SpecialDest - 详细说明

- **正确参数**：`dest=home`（回家）/ `dest=crop`（去公司）
- **注意**：旧版 `destType` 参数（0~10）已弃用，全指向同一终点
- **必须加**：`addCategory(Intent.CATEGORY_DEFAULT)`，否则高德不响应
- **NUI 实现**：`NavHost.naviSpecial()`，发起导航后延迟 4 秒自动返回 NUI

### 2.2 openFavorite - 详细说明

- **发现方式**：反编译高德 APK 的 AndroidManifest.xml，在 `androidauto://` scheme 的 host 列表中找到 `openFavorite`
- **NUI 实现**：`MainActivity` 右侧面板收藏夹按钮点击事件

### 2.3 百度地图 URL Scheme（备选）

| URL Scheme | 功能 |
|------------|------|
| `baidumap://map/direction` | 坐标导航 |

- **参数**：`destination`（lat,lon）、`destination_name`、`coord_type=gcj02`、`mode=driving`、`src`
- **NUI 实现**：`NavHost.startNavigation()` 中百度地图分支

---

## 三、其他关键信息

### 3.1 高德启动入口

- **推荐入口**：`com.autonavi.auto.remote.fill.UsbFillActivity`（官方推荐，第三方调用更可靠）
- **包名**：`com.autonavi.amapauto`
- **NUI 实现**：`NavHost.launchApp()` 中优先使用 UsbFillActivity，失败后降级为默认启动入口

### 3.2 已实现功能清单

| 功能 | 实现位置 | 接口类型 |
|------|----------|----------|
| 跟随地图外观自动切换深浅 | `MainActivity.amapDayNightReceiver` | 广播（10019） |
| 一键回家/公司 | `NavHost.naviSpecial()` | URL Scheme（navi2SpecialDest） |
| 坐标导航 | `NavHost.sendNaviBroadcast()` | 广播（10007） |
| 打开收藏夹 | `MainActivity` 右侧面板 | URL Scheme（openFavorite） |
| 悬浮地图显示/关闭 | `MapHost` | 广播（showmap/closemap） |
| 设置高德昼夜模式（测试用） | adb 命令 | 广播（10048） |

### 3.3 待实现/可扩展功能

| 功能 | 接口 | 说明 |
|------|------|------|
| 导航信息面板（速度/限速/转向/剩余距离/预计到达等） | 广播（10001） | 导航/巡航核心信息，字段最丰富 |
| 红绿灯倒计时显示 | 广播（60073） | 导航模式单路口 + 巡航模式多方向 |
| TMC 路况显示 | 广播（13011） | JSON 路况分段数据 |
| 车道线显示 | 广播（13012） | 车道线 JSON 数据 |

---

## 四、测试命令参考

### 4.1 发送广播给高德

```bash
# 设置昼夜模式（10048）
adb shell am broadcast -a AUTONAVI_STANDARD_BROADCAST_RECV --ei KEY_TYPE 10048 --ei EXTRA_STATE 1
# EXTRA_STATE: 0=自动, 1=白天, 2=夜晚

# 直接导航（10007）
adb shell am broadcast -a AUTONAVI_STANDARD_BROADCAST_RECV \
  --ei KEY_TYPE 10007 \
  --es EXTRA_DNAME "目的地" \
  --es EXTRA_DLAT "39.9087" \
  --es EXTRA_DLON "116.3975" \
  --es ENTRY_LAT "39.9087" \
  --es ENTRY_LON "116.3975" \
  --ei EXTRA_DEV 0 \
  --ei EXTRA_M -1 \
  --es SOURCE_APP "NUI"
```

### 4.2 调用 URL Scheme

```bash
# 一键回家
adb shell am start -a VIEW -d "androidauto://navi2SpecialDest?sourceApplication=NUI&dest=home" -p com.autonavi.amapauto

# 打开收藏夹
adb shell am start -a VIEW -d "androidauto://openFavorite?sourceApplication=nui" -p com.autonavi.amapauto

# 打开主地图
adb shell am start -a VIEW -d "androidauto://rootmap?sourceApplication=NUI" -p com.autonavi.amapauto
```

### 4.3 监听高德发送的广播

```bash
# 监听所有高德广播
adb logcat | grep AUTONAVI_STANDARD_BROADCAST_SEND

# 监听昼夜模式变化（10019）
adb logcat | grep -E "KEY_TYPE=10019|EXTRA_STATE"
```

---

*文档版本：v1.0*
*最后更新：2026-09-06*
