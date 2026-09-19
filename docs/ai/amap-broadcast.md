# Skill：处理高德车机版广播（导航 / 巡航 / HUD）

> 触发场景：要新增导航/巡航数据展示、改状态机、加昼夜跟随、调超速/拥堵/疲劳阈值、调红绿灯提醒。
> 这是 NUI 最复杂的子系统，**动手前先读完本文件 + `docs/amap_auto_protocol.md`，并以代码为准**（文档个别处滞后）。

---

## 0. 心智模型

NUI 不自己采 GPS/路况，所有 HUD 数据来自**高德车机版（`com.autonavi.amapauto`）发出的系统广播**。
NUI 只做三件事：注册 `BroadcastReceiver` → 按协议解析字段 → 在自己的窗口里渲染 + 必要时语音提醒。

- 收高德广播：Action = `AUTONAVI_STANDARD_BROADCAST_SEND`，靠 `KEY_TYPE` 区分数据类型
- 发给高德：Action = `AUTONAVI_STANDARD_BROADCAST_RECV`，需指定 Component
- 悬浮地图显隐：`com.autonavi.plus.showmap` / `closemap`（定义在 `MapSources.kt`）

---

## 1. 关键文件对照表（先认清三个 nav 类的分工）

| 你要改什么 | 去哪个文件 |
|---|---|
| **收 10001/10019/60073/13011 广播、判巡航/导航状态机、渲染导航卡/巡航卡** | **`nav/NavInfoHost.kt`（核心，~950 行）** |
| 超速语音、疲劳驾驶提醒、巡航静音高德、昼夜主动查询 | `nav/NavInfoHost.kt` |
| 红绿灯发现、变灯语音提醒 | `nav/TrafficLightMonitor.kt`（独立接收器，有自己的模式标志） |
| 右侧"回家/公司"按钮、URL Scheme 直达、10007 坐标导航 | `nav/NavHost.kt`（**不收状态广播、不判状态机**） |
| 昼夜外观跟随 | `MainActivity.amapDayNightReceiver` → `UiTheme.setMapDark()` → `refreshForThemeChange()` |
| 悬浮地图显隐 | `MapSources.kt` 定义 action，`MapHost.kt` 调用 |

> `NavHost` 与 `NavInfoHost` 名字相近、职责完全不同：**带 Info 的才是收广播/状态机/HUD**，别改错文件。

---

## 2. 状态机判定（严格遵守，别自己发明）

状态机枚举在 `NavInfoHost`：`Mode { NONE, NAVI, CRUISE }`。

**第一判据是 10019 的 `EXTRA_STATE`（权威），不是 ICON、更不是 TYPE：**

| EXTRA_STATE | 含义 |
|---|---|
| `8`  | 进入导航（NAVI） |
| `9`  | 导航结束（回桌面，等巡航数据重新进入） |
| `24` | **巡航进入**（CRUISE） |
| `25` | 巡航退出（恢复按钮区） |

**10001 的转向图标作辅助：**
- 导航态 `NEW_ICON`/`ICON ≠ 0`（NEW_ICON 优先、ICON 兜底）；巡航态 `ICON = 0`。
- 导航模式下收到的巡航数据要忽略，不能打断导航。

**已弃用、不要用：**
- `46/47` 巡航进出广播——实车不可靠。
- 10001 的 `TYPE` 字段（0/1/2）——经常缺失。
- （`TrafficLightMonitor` 里有一份独立的模式布尔标志，改状态机时两处要保持一致。）

**被动广播会漏发，所以有主动查询（在 `NavInfoHost`）：**
- 启动约 2 秒后主动发 **12404**（`EXTRA_REQUEST_AUTO_STATE=1`，查导航状态）和 **13030**（查昼夜），高德通过 10019 回传。
- 昼夜每 **60 秒**轮询一次（`DAY_NIGHT_POLL_MS`）。
- 还有"数据断流看门狗"：导航/巡航态下超时收不到 10001/10019 就恢复按钮区。

### 昼夜模式（注意代码与文档的差异）

- `MainActivity.amapDayNightReceiver` 当前**只认 `37=白天`、`38=夜晚`，其它值 `else -> return` 直接忽略**。
- `docs/amap_auto_protocol.md` 提到部分高德版本实测为 38/40、建议用"奇数=白天/偶数=夜晚"兼容，但**代码尚未实现奇偶判断**。
- 因此：不要在文档里假设奇偶兼容已生效；若要修这个兼容缺口，改 `MainActivity` 的 `when(state)`，并同步主动查询路径。

---

## 3. 常用广播字段速查

### 10001 导航/巡航信息

| 字段 | 含义 |
|---|---|
| `CUR_SPEED` | 当前车速（km/h） |
| `LIMITED_SPEED` | 道路限速 |
| `NEW_ICON` / `ICON` | 转向图标编号（2~20，映射 `sou{N}_night_a530` 资源；辅助判模式） |
| `SEG_REMAIN_DIS_AUTO` | 段剩余距离 |
| `ROUTE_REMAIN_DIS_AUTO` / `ROUTE_REMAIN_TIME_AUTO` | 全程剩余距离 / 时间 |
| `ETA_TEXT` | 预计到达文本 |
| `NEXT_ROAD_NAME` / `CUR_ROAD_NAME` | 下一条 / 当前道路名 |
| `CAMERA_DIST` / `CAMERA_SPEED` | 电子眼距离 / 测速限速 |
| `endPOIName` | 终点名称 |
| `EXIT_NAME_INFO` / `EXIT_DIRECTION_INFO` | 高速出口 |
| `ROAD_TYPE` / `SAPA_DIST_AUTO` / `SAPA_NAME` / `SAPA_NUM` | 服务区（仅高速） |

### 其它 KEY_TYPE

- **10019**：导航/巡航状态 + 昼夜（见第 2 节）。
- **60073**：红绿灯。导航态用 `redLightCountDownSeconds`（单一路口）；巡航态用 `lightsData`（JSON 数组，多方向，取第一个=最近的）。数据可能是 JSON 文本 / Bundle / 数组，解析要兼容。
- **13011 TMC 路况**：JSON 分段，`tmc_info[]` 每段含 `tmc_status`（-1无数据/0未知蓝/1畅通绿/2缓行黄/3拥堵红/4严重拥堵深红/10已驶过灰）、段距离、占比。
  - 用 `finish_distance` 定位当前位置所在段，往后找第一段拥堵算实时距离；status=10 已驶过不参与判断。
  - 约 6 秒一次推送。代码兼容两种坐标系（段和≈residual 按剩余路程，否则按全程），改时别破坏。

### 发给高德（NavHost / MapSources）

- **10007**：坐标直接导航（`EXTRA_DLAT/DLON/DNAME`，`EXTRA_DEV=0` gcj02），需高德在前台，建议先预热 3 秒；进入路线规划后 5 秒倒计时自动导航。
- **10047 `EXTRA_CASUAL_MUTE`**：巡航播报临时静音（1 静音 / 0 恢复）。
- **10048 `EXTRA_STATE`**：设置昼夜（0 自动 / 1 白天 / 2 夜晚），常用于测试。
- URL Scheme：`androidauto://navi2SpecialDest?dest=home|crop`（回家/公司，必须加 `CATEGORY_DEFAULT`）、`androidauto://openFavorite`（收藏夹）、`androidauto://rootmap`（兜底）。

---

## 4. 改阈值/行为的常见位置（都在 NavInfoHost，除非注明）

- **模式切换 / 巡航静音**：`setAmapCruiseMute(mute)`——进 CRUISE 静音、退巡航或进 NAVI 恢复（10047 显式 + 隐式 action 双发，幂等）。**导航语音不能被静音。**
- **超速语音**：`checkOverspeed()`，**仅 CRUISE 模式**；限速只认测速点 `CAMERA_SPEED`（cruiseLimit）。
  - 正在跟踪摄像头（`camTracked`）→ 超 **10%**（`limit*11/10`）播报；没跟踪摄像头 → 超 **20%**（`limit*12/10`）。
- **摄像头语音**：`checkCameraVoice()`——首次发现播报一次，`CAMERA_DIST < 200` 再报一次（`camAnnouncedNear`），通过后"登"一声提示音。
- **疲劳驾驶**：常量 `FATIGUE_FIRST_MS=90分钟`、`FATIGUE_INTERVAL_MS=2分钟`、`FATIGUE_REMIND_TIMES=3`；NAVI/CRUISE 都算驾驶态，3 次后重置进入下一轮 90 分钟。
- **红绿灯变灯**：在 `TrafficLightMonitor.kt`，仅巡航、车速 `≤ 25 km/h`（`CRUISE_SPEED_THRESHOLD=25.0`）、红灯倒计时 `≤ 3 秒`时提醒。
  - 注意：`NavInfoHost.kt` 顶部有句注释写"≤20km/h"是**过时注释**，以 TrafficLightMonitor 的常量 25 为准。
- **超速变色 / 拥堵着色 / 摄像头距离显示**：`NavInfoHost.kt` 的渲染段。

---

## 5. 验证方式（无需真车上路）

1. **首选 `test/cruise_start.sh`**（BlueStacks 一键）：重启 GPS 注入 → 拉高德 → 轮询等进入巡航 → 注入限速/摄像头数据 → 截图到 `test/shots/`。
   - `./cruise_start.sh [lat] [lon] [hdg] [speed]`
2. `test/cruise_test.sh` 做巡航状态机测试；`test/gps_sim.sh` 模拟 GPS。
3. 昼夜跟随测试：发 10048 `EXTRA_STATE=1/2` 切白天/夜晚（注意代码只认 10019 回传的 37/38）。
4. 改完对照 `test/TEST_REPORT.md` 检查回归。

---

## 6. 红线

- 判模式以 **10019 的 STATE（8/9/24/25）为权威**，ICON 辅助；不要用 46/47 或 `TYPE`。
- 状态机/超速/疲劳/巡航静音都在 **NavInfoHost**；不要把这些逻辑塞进只管回家/公司的 `NavHost`。
- 巡航静音只能静音巡航播报，**导航播报必须保留**。
- 不要假设昼夜已做奇偶兼容——当前代码只认 37/38。
- 不要在导航模式下把巡航数据当成新导航打断。
- 不要新建第二条语音通道；播报统一走 `voice/NuiTts.kt`。
- 字段含义不确定时，查 `docs/AmapAuto标准广播协议_20180813.pdf` 原文与代码注释，不要猜。
