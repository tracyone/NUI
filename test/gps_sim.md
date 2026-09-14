# GPS 模拟方案（GPS Simulation）

用于在模拟器（BlueStacks）上模拟车辆移动，触发高德车机版进入**巡航 / 导航**并发送**真实广播**，避免每次都在实车上测试。

## 原理

高德车机版（amapauto）**没有模拟导航**功能（已确认）。要让它在模拟器上"动起来"，唯一可靠途径是 **mock location（模拟定位注入）**：

```
gpsmock App (com.nui.gpsmock)
    └─ LocationManager.addTestProvider("gps") + setTestProviderLocation(...)
        └─ 系统 GPS Provider 位置 = 注入坐标（对所有 App 可见）
            └─ 高德车机版收到 GPS → 定位跟随 → 进入巡航（无路径）
                            └─ 移动持续注入 → 可配合路线规划进入导航
                                └─ 高德发 AUTONAVI_STANDARD_BROADCAST_SEND (10001 等)
                                    └─ NUI NavInfoHost 收到 → 巡航卡 / 导航卡更新
```

- `adb emu geo fix` 在 BlueStacks 上**无效**（无 emulator console），所以必须用 App 注入。
- 注入的 GPS 对系统所有应用可见，高德无需任何特殊配置。

## 文件

| 文件 | 说明 |
|---|---|
| `gpsmock/` | 独立 GPS 模拟 App 模块（不污染 NUI 主包），前台服务避免系统 idle 停止 |
| `test/gps_sim.sh` | 一键控制脚本（编译安装 / 授权 / 注入 / 移动 / 停止 / 导航 / 状态） |

## 快速开始

```bash
# 1. 编译安装 gpsmock 并授权（只需一次）
./test/gps_sim.sh install

# 2. 让车辆沿 45° 方向以 60km/h 匀速移动（高德自动进入巡航并发广播）
./test/gps_sim.sh move 22.517 113.394 45 60

# 3. 观察：
#    - adb shell dumpsys location 里 gps 坐标持续变化
#    - 高德地图定位点跟随移动，路线重算
#    - logcat 出现 NUI NavInfoHost / 10001 广播处理日志
#    - NUI 右侧巡航卡显示速度 / 红绿灯 / 摄像头距离

# 4. 停止移动
./test/gps_sim.sh stop
```

## 命令一览

| 命令 | 作用 |
|---|---|
| `install` | 编译 + 安装 gpsmock + 授权 mock location |
| `auth` | 重新授权（`appops android:mock_location allow` + `settings put secure mock_location`） |
| `set <lat> <lon> [heading] [speed]` | 注入单点（默认珠海 22.517,113.394） |
| `move <lat> <lon> <heading> <speed>` | 持续移动（heading 角度 0-360，speed km/h），每 0.5s 注入一次 |
| `stop` | 停止移动 |
| `navi <lat> <lon> [heading] [speed]` | 启动高德 + 用 `androidauto://route` 规划路线 + 持续移动 |
| `status` | 查看当前 GPS 注入位置 |

## 触发导航（导航卡测试）

1. `./test/gps_sim.sh navi 22.52 113.40 45 60` —— 高德会算路并进入路线页。
2. 高德界面（或 NUI 已自动返回）中，如需"开始导航"，在高德浮窗点一次"开始导航"按钮。
3. 移动持续注入后，高德真实导航 → NUI 导航卡显示（转向 / 车道 / 剩余距离 / 到达时间 / TMC 拥堵 / 服务区等）。

> 注：`navi` 脚本里 `androidauto://route` 只负责算路；高德车机版部分版本算路后不自动开始，需手动点一次"开始导航"。`move` 保持车辆移动，导航才能真实进行。

## 实测记录（2026-09-14，BlueStacks arm64）

| 步骤 | 结果 |
|---|---|
| `addTestProvider`（powerUsage=[1,3]，accuracy=[1,2] 参数修正后） | ✅ provider 添加成功 |
| `SET 22.517 113.394` | ✅ `dumpsys location` → `gps 22.517000,113.394000 hAcc=5.0 vel=16.6 bear=45.0` |
| `MOVE ... 45 60` | ✅ 6 秒后位置变 `22.517635,113.394688`（约移动 100 米） |
| 高德重启后 | ✅ 地图区域跟随 mock 位置变化；"去公司 45分钟→41分钟→38分钟"持续重算；回家从"已在附近"变"17分钟" |
| NUI 广播处理 | ✅ logcat 见 `NavInfoHost.updateCruise`（10001 巡航信息持续到达） |
| NUI page0 | ✅ 高德浮窗正常、右侧巡航卡显示速度 |

## 常见问题

- **注入后位置不变**：确认 gpsmock 是前台服务在跑（`adb shell dumpsys activity services com.nui.gpsmock`）；重跑 `./test/gps_sim.sh auth`；Android 12+ 必须在开发者选项"选择模拟位置信息应用"里选 GPS Mock（或已用 settings/appops 授权）。
- **高德定位不动**：高德需要冷启动一次（`am force-stop com.autonavi.amapauto` 后重启）才会重新订阅 GPS。
- **服务被系统停掉**：已改为前台服务（带通知），系统不再因 app idle 杀它；`am start-foreground-service` 启动。
- **想换城市**：`set/move` 的 lat/lon 传任意坐标即可（如广州 23.129,113.264；北京 39.904,116.407）。
