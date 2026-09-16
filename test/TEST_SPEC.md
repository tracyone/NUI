# NUI Launcher Test Specification

Test spec for all NUI features: steps + expected results. Run on **car head unit** (Android 9, 32/64-bit)
or **BlueStacks emulator** (`adb -s 127.0.0.1:5555`). Mark each item `[PASS] / [FAIL]`.

## 0. Environment & Prerequisites

| Item | Value |
|---|---|
| Device | Car head unit (Android 9, armeabi-v7a / arm64-v8a) or BlueStacks (arm64) |
| Build | `./gradlew :app:assembleArm32Release :app:assembleArm64Release` |
| APKs | `app/build/outputs/apk/{arm32,arm64}/release/app-*-release.apk` (~2.3M) |
| Install | `adb install -r <apk>` (fresh install: `adb uninstall com.nui.launcher` first, or use `nui_deploy.bat` which always clears data) |
| AmapAuto | `com.autonavi.amapauto` must be installed (cruise/navi features) |
| GPS mock | `./test/gps_sim.sh install` once on emulator (not needed on real car) |
| Logs | `adb logcat -s NUI.NavInfo:* NUI.MusicHost:* NUI.Weather:*` |

> 全新安装语义：使用 `nui_deploy.bat`（每次安装前 `pm clear`），保证悬浮区域/壁纸等回到默认配置。

> 通用操作提示（模拟器实测）：
> - **BACK 键（keyevent 4）在 NUI 桌面会退出 NUI**（NUI 是普通 Activity 非真 Launcher），退出后需 `am start` 重启；在设置 Dialog 打开时 BACK 关闭 Dialog。
> - 设置面板输入框（如地图延迟秒数）点击后**软键盘不弹出**（BlueStacks 限制），直接用 `input text` 注入即可。
> - 高德/酷我/字节车服会抢前台：操作 NUI 前先 `am force-stop com.autonavi.amapauto` 再 `am start -n com.nui.launcher.debug/com.nui.launcher.MainActivity`（巡航卡验证除外，见 T40）。
> - 桌面设置须从 page1 应用列表的"桌面设置"图标进入；`am start …SettingsActivity` 会报错。

---

## 1. Deploy & Fresh Install

**T01 - Fresh install resets to defaults**
- Steps:
  1. Run `nui_deploy.bat` on Windows (or `adb uninstall com.nui.launcher && adb install -r <apk>`) to install a *clean* version.
  2. Start NUI.
- Expected:
  - Suspended map area at **default size/position** (not remembered from previous install).
  - Music bar visible at its default spot; dock icons default size.
  - No leftover wallpaper / voice / map settings.

**T02 - First boot auto-launch AmapAuto and return to desktop**
- Steps:
  1. Fresh install, boot NUI.
  2. Watch timing.
- Expected:
  - NUI starts; **at ~5 s** AmapAuto launches automatically (config `地图` → 启动第 5 秒).
  - AmapAuto stays foreground ~5 s, then **returns to desktop at ~10 s** (config 返回第 10 秒; 10 s is absolute time, not "5 s after launch").
  - After return, NUI desktop + map float window are visible again.
- **Emulator note (2026-09-16 verified)**: on BlueStacks the AUTO_BACK mechanism works (logcat: `EXTRA_AUTO_BACK, keep page 0` → AmapAuto returns, NUI stays on page 0), but AmapAuto's Splash 加载慢 shifts the actual return time by ~5-12 s (observed return at +15-22 s instead of +10 s). This is emulator AmapAuto loading latency, not a logic failure; expect accurate timing on the car (AmapAuto 常驻/快).

---

## 2. Weather

**T03 - Full-screen weather on first boot**
- Steps: fresh install → boot NUI → wait for weather to load.
- Expected:
  - Full-screen weather animation shows once on first boot (rain / sun effect, transparent overlay, dock bar still visible).
  - Weather page (detailed info) opens when user taps weather area.
  - Weather animation auto-hides after a short time; it does NOT reappear when switching apps back to NUI.

**T04 - First-boot weather voice broadcast (30 s)**
- Steps:
  1. Fresh install, boot NUI, wait ~35 s (AmapAuto auto-returns at ~10 s; broadcast starts at 30 s).
  2. Listen.
- Expected:
  - Voice says greeting + weather once, e.g. "主人早上好，今天珠海晴，气温 28 度……" (格式：主人xx好；只播报一次).
  - If weather fails to load: still broadcasts the greeting part only (主人xx好 + 星期), no weather part.
  - No duplicate broadcast (was a bug: two broadcasts on first boot).

**T05 - Major weather change broadcast**
- Steps: wait for a major weather alert (or force refresh).
- Expected: new alert is broadcast once; not re-broadcast for the same alert.

---

## 3. Voice (TTS)

**T06 - Voice gender switch in settings**
- Steps: 桌面设置 → 语音（新建"语音"入口）→ switch male/female.
- Expected:
  - On switch, immediate voice: "你好，我是xxx" (the new voice speaks its own name).
  - Only one engine voice; no "正在切换…" then long gap (performance dependent — see T08).
  - No "均为离线内置" hint text (removed).
  - **No system TTS engine (e.g. emulator)**: female (小雅) checked by default, male (超文) **greyed out & unclickable** — this is expected (voice availability check), NOT a bug. Verify on car only after confirming a TTS engine exists.
- **Operation hint (2026-09-16 verified)**: settings panel category items start at x=248 (320dpi; OCR x≈143 is the text's left edge only). Clicking x≈166 lands OUTSIDE the dialog and closes it. Click category center instead: 声音=(488,580), 外观=(488,460), 地图=(488,700), 应用=(488,820), 关于=(488,940). Confirmed: tapping (488,580) opens the voice panel; tapping (166,535) dismisses the dialog.

**T07 - Voice generality**
- Steps: check voice is used by weather, navigation, cruise alerts, key-map announcements, music notices.
- Expected: voice is a generic `语音` module, not coupled to weather; all features use it.

**T08 - Voice latency (performance test)**
- Steps: see `test/声音延时测试方案.md` / `声音延时测试结果.md`.
- Expected: on emulator (Mac mini) switch latency is small; on car (low CPU) latency is documented for the record.

---

## 4. Appearance / Wallpaper / Follow-Map

**T09 - Dark/Light mode switch**
- Steps: 桌面设置 → 外观 → switch 深色/浅色.
- Expected:
  - All NUI UI recolors instantly (dock, app list, settings, music bar text).
  - Text readable in BOTH modes (e.g. "跟随地图" label visible in dark mode; "返回/保存" visible in light mode).

**T10 - Wallpaper day/night follows appearance**
- Steps: set 白天壁纸 + 晚上壁纸 in 外观 → switch appearance.
- Expected:
  - Dark appearance → night wallpaper; light → day wallpaper.
  - Fallback: night not set → use day; neither set → default wallpaper.

**T11 - Follow-map appearance (默认跟随地图)**
- Steps:
  1. 外观 mode = 跟随地图 (default).
  2. Start AmapAuto; toggle its day/night via 协议 10048（EXTRA_DAY_NIGHT_MODE: 0=自动 / 1=白天 / 2=黑夜）.
  3. 复杂场景：在巡航进入 / 巡航中 / 巡航退出、导航进入 / 导航中 / 导航退出各阶段重复切换 10048 白天/黑夜，确认 NUI 全程跟随.
- Expected:
  - NUI appearance follows AmapAuto's day/night state（10019 EXTRA_STATE=37 白天→浅色 / 38 夜晚→深色）.
  - 模式切换（巡航卡/导航卡）与外观切换互不干扰：切外观不丢失模式卡，切模式不打断外观跟随.
  - 判据：dock 背景 RGB 采样（深色 ~50、浅色 ~210）；巡航卡/导航卡存在且内容正常.
- 工具与命令：
  - 切外观: `adb shell am broadcast -a AUTONAVI_STANDARD_BROADCAST_RECV --ei KEY_TYPE 10048 --ei EXTRA_DAY_NIGHT_MODE 1`（2=黑夜, 0=自动）
  - 结束导航: `adb shell am broadcast -a AUTONAVI_STANDARD_BROADCAST_RECV --ei KEY_TYPE 10010`
  - GPS 模拟: `test/gps_sim.sh move <lat> <lon> <heading> <speed>` / `stop` / `navi`
  - 导航启动：高德 UI 点「回家/去公司」→ 蓝色按钮开始导航（route scheme 后需真实确认）
  - 注意：10048/10010 是高德官方控制接口（真实切换高德状态），非伪造广播数据。

---

## 5. Map (AmapAuto)

**T12 - Map float window bounds vs system dock**
- Steps:
  1. 设置 → 外观 → 显示系统 Dock: OFF.
  2. Long-press app-list button → drag suspended map area bottom edge down.
- Expected:
  - With system dock hidden, map area bottom can extend below the previous dock boundary (area adapts to new bottom).
  - With system dock shown, bottom is clamped above the dock (cannot overlap it).
  - Right/up drag works freely.

**T13 - Map bind / collection / home / company**
- Steps: 桌面设置 → 地图 → bind AmapAuto; use 收藏/回家/公司 buttons on right info area.
- **Operation hint (2026-09-16 verified)**: 收藏/回家/公司 buttons are part of the NUI overlay on **page 0** (right info area, 320dpi bounds ≈ 回家[747,52][812,190] / 公司[827,52][892,190] / 收藏[907,52][972,190]; centers ≈ (780,120)/(860,120)/(940,120)). On page 1+ (app grid) these buttons are NOT present — clicking that area hits an app icon instead. Always return to page 0 first (swipe left) before testing these buttons.
- Expected:
  - Bind saves as preferred map app.
  - 收藏 opens AmapAuto favorites (via URL/广播; falls back to launching map).
  - 回家/公司 start navigation (navi2SpecialDest or 10007 broadcast), AmapAuto returns to desktop after launch.

**T14 - Map app icon in dock**
- Steps: check dock top slot.
- Expected: dock slot #1 = bound map app icon; shown as soon as configured (not only after app launches).

---

## 6. Cruise Mode

Use `./test/cruise_start.sh` (emulator) or drive on car.

**T15 - Cruise entry detection**
- Steps: start AmapAuto, let it enter cruise (no route); observe NUI right card.
- Expected: NUI switches to cruise card (speed circle + limit circle + camera row). Entry via ICON=0 / STATE=24.

**T16 - Cruise card display**
- Steps: cruise with speed 43, limit 70 available.
- Expected:
  - Speed in **black circle blue ring** (large number), limit in **red ring** (if data present).
  - Cameras (any type) in second row: icon (white circle bg) + distance, large text.
  - Overspeed: speed & limit circles turn red.

**T17 - Speed camera voice alerts (TYPE=0)**
- Steps: drive toward a speed camera (限速摄像头) in cruise.
- Expected:
  - On first detection: "前方XXX米有限速摄像头，限速XX" (distance + limit, no "公里").
  - When <200 m: repeat the alert once.
  - If speed > limit×1.1 (10%): "您已超速，当前限速XX".
  - After passing: single "登" beep.
  - In navigation mode: NO NUI voice (AmapAuto speaks itself).

**T18 - Overspeed without camera**
- Steps: cruise, no speed camera tracked, speed > limit×1.2 (20%).
- Expected: "您已超速，当前限速XX" only when exceeding 20%; not at 10%.

**T19 - Traffic light countdown alert (cruise)**
- Steps: cruise approaching a red light with countdown ≤3 s.
- Expected:
  - Voice "X秒后变绿" / "绿灯即将亮起" (countdown 3→1).
  - Traffic light row shows per-direction light + arrow + countdown (Navi-Link style).
  - No speed condition needed.

**T20 - Cruise mute**
- Steps: enter cruise; check AmapAuto cruise-voice button.
- Expected: NUI sends mute broadcast on cruise enter (10047), restores on exit; navigation unaffected.

**T21 - Cruise card buttons**
- Steps: in cruise, check right card.
- Expected: 回家/公司/收藏 buttons still visible and usable.

---

## 7. Navigation Mode

**T22 - Nav card display (icons aligned with cruise)**
- Steps: start navigation (real route on car; on emulator use `gps_sim.sh move` + 10007 broadcast or `navi2SpecialDest`).
- Expected:
  - Card shows: destination name / ETA (arrive time · remaining distance) / **speed black-blue circle + limit red ring** (same style as cruise) / camera icon (white circle, full type mapping) / congestion line.
  - Camera icon size 38dp with white ring background (same as cruise).

**T23 - Arrival time & congestion**
- Steps: navigate with congestion ahead.
- Expected: ETA shows **arrive time** (not remaining duration). Congestion shows distance to next congested segment with color (yellow/red/dark red) that decreases in real time.

**T24 - Rest reminder (fatigue)**
- Steps: keep navigating >1.5 h (fast-forward not possible; set test on long route).
- Expected: after 90 min continuous navigation, voice reminds to rest 3 times, 2 min apart; then resets for the next 90-min cycle.

**T25 - Service area display (highway)**
- Steps: navigate on highway, pass a service area.
- Expected: distance to next service area + name shown large (number larger than text), "下一个服务区" small line below. Not shown in city navigation? (highway only).

---

## 8. App List

**T26 - Icon sorting (apps with icons first)**
- Steps: open app list (page1+).
- Expected:
  - Apps **with real icons** sorted before apps using default/system icon.
  - Apps without icons (default icon) go to later pages.
  - Test package: app named "A..." with no icon must sort AFTER an app named "Z..." with a real icon.

**T27 - Paged layout & lazy loading**
- Steps: swipe left/right between pages.
- Expected:
  - Page0 = suspended map page; Page1+ = app pages (one page full of icons, then next).
  - Icons load per-page on demand (page2 icons load when page2 opened) and are cached for fast revisit.
  - Horizontal swipe only; vertical scroll disabled; no scrollbar.
- **Swipe direction (verified)**: finger-swipe **right→left** (`input swipe 1600 540 300 540`) = NEXT page (page0→1→2); finger-swipe **left→right** (`input swipe 300 540 1600 540`) = PREVIOUS page (2→1→0). Start swipe **outside the dock (x>200) and outside the map float area** (map area intercepts the gesture). In emulator the map float may cover the whole page when AmapAuto is running — force-stop AmapAuto before page swiping if stuck.

**T28 - Hide / uninstall apps**
- Steps: long-press an app icon → menu → 隐藏 / 卸载 → confirm dialog.
- Expected:
  - Uninstall actually uninstalls the app.
  - Hidden apps move to 桌面设置 → 应用 → hidden-app manager, can be restored.

**T29 - App list shows system apps**
- Steps: scroll app list.
- Expected: system apps visible; user can hide the ones never used (see T28).

---

## 9. Music

**T30 - Bind QQ Music / Kuwo (car versions)**
- Steps: 长按音乐区 → choose music app (QQ音乐 / 酷我) → it launches.
- Expected:
  - Bind saves preferred app; dock slot #2 shows bound music icon once configured.
  - First bind launches the app (no silent failure).

**T31 - Playback controls & lyrics**
- Steps: play a song; tap play/pause, prev/next.
- Expected:
  - Buttons work (play/pause/prev/next) via MediaSession.
  - Lyrics show for ALL bound music apps via **NetEase cloud** source (including QQ music & Kuwo; non-standard QQ music also uses NetEase).
  - Lyrics scroll with playback progress; highlight current line.

**T32 - Kuwo auto-stop on background (car)**
- Steps (car, Kuwo 6.0): play in Kuwo → switch back to desktop (Kuwo goes background).
- Expected:
  - NUI auto-resumes playback ~0.8 s after detecting Kuwo paused in background (unless user paused via NUI button).
  - If user explicitly pauses via NUI button, it stays paused.

**T33 - Music card layout**
- Steps: play song with/without cover.
- Expected:
  - With cover: album disc + rotating animation + song/artist + lyrics.
  - Without cover (or compact): small card with song name + artist + play/prev/next only (no jumpy size changes on track switch).

---

## 10. Steering-wheel Key Mapping

**T34 - Add / edit / delete mapping**
- Steps: 桌面设置 → 方向盘 → 点击添加按键映射 → dialog with two rows (key code / action).
- Expected:
  - Click ripple appears on the row being clicked (keycode row vs action row do NOT cross-highlight — bug fixed).
  - "请按下方向盘按键…" button label now "监听按键"; listening captures real key code.
  - New action available: 切换系统Dock栏（显示/隐藏）.
  - Edit saves to the SAME entry (does not create a duplicate).

**T35 - Key mapping execution**
- Steps: press mapped steering-wheel key.
- Expected: mapped action executes (next/prev song, home nav, dock toggle, etc.).
- **IMPORTANT (verified)**: media keys (e.g. 88 = KEYCODE_MEDIA_PREVIOUS) are **globally intercepted by MediaSessionService** and routed to the music app (logcat evidence) — NUI's dispatchKeyEvent never sees them. **Verify execution with a non-media key** (e.g. 66 = ENTER). Verified: keyevent 66 toggles 显示系统Dock ON↔OFF both ways.

---

## 11. Settings

**T36 - Appearance panel**
- Steps: 桌面设置 → 外观.
- Expected:
  - 显示状态栏 and 显示系统 Dock are **independent** switches (separated by divider line).
  - With system dock hidden, all entries visible (including 应用 entry — no hidden entry).
  - Map delay input boxes readable in dark mode (text not invisible).

**T37 - About entry**
- Steps: 桌面设置 → 关于.
- Expected: shows author 作者 tracyone, version 0.0.30 (increments each commit), and 重启NUI桌面 (restart launcher) action works.

**T38 - DPI adaptation**
- Steps: change system DPI (e.g. `adb shell wm density 240`; restore with `wm density 320`).
- Expected: settings / key-map popup text readable and not oversized; car layout recalculates icon counts per page.
- **Verified on emulator (2026-09-16)**: at 240dpi page1 recalculates to 4 icons/row (was 6 at 320dpi) with no overflow; settings panel text readable; opening settings works by tapping the **item center** (240dpi 桌面设置 item ≈ [162,200][232,270], center ≈ (197,235)); the earlier "240dpi 桌面设置 click didn't work" was a wrong-coordinate tap (x≈144 is the dock edge + padding start, not the item), not a real input failure.

**T39 - System dock & status bar**
- Steps: 外观 → toggle 显示状态栏 / 显示系统 Dock.
- Expected:
  - Status bar and system dock appear/disappear independently.
  - NUI content (dock/music/map area) dynamically yields (padding) when system bars shown.
  - After launching an app and the app exits itself (not via HOME), system dock hides again (no stuck bar covering NUI bottom).
- **Limitation**: BlueStacks has NO system navigation bar, so dock appearance/yield can only be partially verified (status bar toggle works, shows inset padding). **Full dock/statusbar verification requires the real car.**

---

## 12. GPS Simulation (emulator only)

**T40 - gps_sim.sh**
- Steps: `./test/gps_sim.sh install` → `move 22.517 113.394 45 60` → watch.
- Expected: AmapAuto follows mock GPS, enters cruise, sends real broadcasts; NUI cruise card updates. See `test/gps_sim.md` & `test/cruise_test.md` for routes and known limitations (BlueStacks rarely sends camera/traffic-light data; car with real GPS does).
- **Verified on emulator**: `gps_sim.sh move` → AmapAuto follows mock location (中山市东区), enters cruise; NUI right card shows cruise speed 63 km/h. **Note**: do NOT force-stop AmapAuto before checking the cruise card (kills the broadcast source); just bring NUI to front. Camera/traffic-light data still needs real car.

---

## 13. App grid left-align regression (new, fixed 2026-09-16)

**T41 - page1 first column must stay flush against dock after settings round-trip**
- Steps (240dpi, the user-reported density):
  1. `adb shell wm density 240`, fresh install, open page1 (app grid).
  2. Record first-column item left edge (uiautomator dump).
  3. Open 桌面设置 panel, then BACK to page1.
  4. Record first-column left edge again.
- Expected:
  - First column starts at dock right edge + 12dp spacing (240dpi: dock bg right ≈144px → first item x≈162px; 320dpi: dock ≈192px → item x≈216px).
  - After the settings round-trip the first column does NOT shift right (regression was double left-padding stacking in `applyDockStyle` + container padding).
  - Also check default (no touch) view is flush, and dock shape switch (edge→float or reverse) keeps the same offset.
- **Verified on emulator (2026-09-16)**: 240dpi page1 first item "桌面设置" [162,…] before AND after settings round-trip (uiautomator: [250,186]→[358,222] area unchanged pre/post); 320dpi first item starts 216px. Fixed in `MainActivity.kt` (container padding 0 + single source `appGridLeftPadDp()` + immediate setPadding on bind).

---

## Regression Checklist (quick pass after each release)

- [ ] Fresh install → default layout (T01)
- [ ] First boot: map auto-launch/return (T02) + weather voice once at 30s (T04)
- [ ] Appearance follow-map no flip-flop (T11)
- [ ] Cruise: speed camera voice + ding (T17), overspeed 10/20% (T17/T18), traffic light (T19)
- [ ] Nav: card icons aligned (T22), arrive time (T23)
- [ ] App list icon-first sorting (T26)
- [ ] App grid left-align regression (T41)
- [ ] Music bind + NetEase lyrics + Kuwo background resume (T30/T31/T32)
- [ ] Key-map add/edit no duplicate (T34)
- [ ] System dock/status bar independent (T39)
