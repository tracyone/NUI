# NUI 巡航模式测试脚本（cruise_test.sh）

用 GPS mock 让高德车机版进入**巡航模式**，沿预设路线行驶，分段标注
**红绿灯 / 限速摄像头 / 普通路段**，同时监控高德广播（摄像头距离/限速/巡航状态），
每段自动截图，用于验证 NUI 巡航卡（速度、限速、摄像头、红绿灯）与 Navi-Link 悬浮框。

## 环境

- 模拟器：`adb -s 127.0.0.1:5555`（BlueStacks）。**车机使用**：把脚本里
  `ADB="adb -s 127.0.0.1:5555"` 改成车机 IP（如 `adb -s 192.168.x.x:5555`）即可，其余不变。
- 依赖：`gps_sim.sh`（GPS mock 注入）、gpsmock 已安装授权（`./gps_sim.sh install`）。
- 高德：`com.autonavi.amapauto`（脚本自动 force-stop 重启进入巡航）。
- 广播日志：优先用 Navi-Link（`com.navi.link`，自动启动，打印所有广播字段）；
  没有 Navi-Link 时可用 NUI 自己的日志。

## 用法

```bash
./cruise_test.sh run [route1]     # 跑完整测试路线（巡航 + 分段移动 + 广播监控 + 每段截图）
./cruise_test.sh scan <lat> <lon> <heading> <speed> <sec>   # 扫摄像头：向某点开 N 秒，打印摄像头字段
./cruise_test.sh shot [file]      # 手动截图（默认 test/shots/cruise_shot.png）
./cruise_test.sh route            # 打印路线分段
./cruise_test.sh status           # 当前 GPS 位置/速度
./cruise_test.sh help
```

## 默认路线 route1（中山·孙文东路向东）

| 段 | 目标点 | 朝向/速度 | 时长 | 标签 |
|---|---|---|---|---|
| 1 | 22.5155,113.3935 | 90° / 40 | 12s | plain |
| 2 | 22.5163,113.3970 | 90° / 35 | 12s | plain |
| 3 | 22.5170,113.4005 | 90° / 35 | 15s | **redlight**（孙文东路/东文路路口一带） |
| 4 | 22.5177,113.4040 | 90° / 40 | 15s | **redlight**（孙文东路/中山市政府路口一带） |
| 5 | 22.5182,113.4070 | 90° / 40 | 12s | plain |

脚本每段打印 `>>> [标签] move -> ...`，段内每 3 秒输出摄像头字段
（`CAMERA_DIST / CAMERA_SPEED / CAMERA_TYPE`，-1 表示无），段末自动截图到 `test/shots/`。

## 输出

- 控制台：每段标签 + 摄像头字段变化 + 最终广播快照
  （CUR_SPEED / LIMITED_SPEED / CAMERA_DIST / CAMERA_SPEED / CAMERA_TYPE /
  ICON / TYPE / CUR_ROAD_NAME / TRAFFIC_LIGHT_NUM）
- 截图：`test/shots/seg_<tag>_<time>.png`

## 已知限制（重要）

1. **BlueStacks 模拟器上高德对 mock 定位信任度低**：界面常显示"卫星定位中..."，
   因此**摄像头（CAMERA_DIST>0）和红绿灯（60073）数据几乎不推送**——不是脚本问题，
   是模拟器高德数据源问题。模拟器能稳定验证的是：**巡航进入（TYPE=2/ICON=0）、
   速度（CUR_SPEED）、路名（CUR_ROAD_NAME，部分路段）**。
2. **真实车机上**高德用真实 GPS，会正常推送电子眼/红绿灯数据——
   同一脚本（改 adb IP）跑通即可验证 NUI/Navi-Link 的摄像头条与红绿灯。
3. 路线里的 `redlight` 段是**地图上真实路口**，模拟器跑时高德不一定响应；
   车机跑时若仍无数据，可把 `scan` 扫到的实际摄像头路段加进 `ROUTE1` 数组。

## 典型验收点（车机）

- [ ] 巡航进入：广播 `TYPE=2`、`ICON=0`
- [ ] 速度：`CUR_SPEED` 随 move 速度变化，NUI 巡航卡显示
- [ ] 限速摄像头：接近 `CAMERA_DIST` 递减、`CAMERA_SPEED` 为限速值（如 80），NUI 显示摄像头条
- [ ] 红绿灯：接近路口收到 `60073`（lightsData），NUI/Navi-Link 显示箭头+倒计时
- [ ] 每段截图与广播快照时间戳对齐，可回放验证
