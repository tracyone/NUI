package com.nui.launcher.nav

import com.nui.launcher.NuiToast

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import com.nui.launcher.R
import com.nui.launcher.voice.NuiTts

/**
 * 右侧导航按钮：回家 / 公司。
 *
 * 双模式：
 *
 * - 默认（未设直达坐标）：点击 → 用 `androidauto://navi2SpecialDest?dest=home/crop` URI
 *   直接触发高德车机版一键回家/去公司（需 CATEGORY_DEFAULT，用户真机验证成功）。
 *   高德使用其已保存的家/公司地址自动规划路线并倒计时导航。
 * - 可选（长按设置过直达坐标）：点击 → 用 10007 广播传入坐标直接导航。
 *
 * 长按 → 设置/清除直达坐标、切换导航地图。
 *
 * 悬浮地图处理：弹任何对话框前隐藏高德浮窗，关闭后恢复（同 MusicHost 模式）。
 */
class NavHost(
    private val context: Context,
    private val homeContainer: View,
    private val companyContainer: View,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 弹出对话框前隐藏悬浮地图，关闭后恢复（由外部注入，与 MusicHost 同模式） */
    var onHideFloat: (() -> Unit)? = null
    var onShowFloat: (() -> Unit)? = null
    // 菜单→子对话框链：菜单关闭时若子对话框将接管，则不恢复浮窗
    private var followUpPending = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private data class NavBtn(
        val container: View,
        val icon: ImageView,
        val label: TextView,
        val key: String,
        val default: NavTarget,
        val iconRes: Int,
    )

    private lateinit var homeBtn: NavBtn
    private lateinit var companyBtn: NavBtn

    fun start() {
        homeBtn = NavBtn(
            homeContainer,
            homeContainer.findViewById(R.id.navIconHome),
            homeContainer.findViewById(R.id.navLabelHome),
            KEY_HOME, DEFAULT_HOME, R.drawable.ic_nav_home,
        )
        companyBtn = NavBtn(
            companyContainer,
            companyContainer.findViewById(R.id.navIconCompany),
            companyContainer.findViewById(R.id.navLabelCompany),
            KEY_COMPANY, DEFAULT_COMPANY, R.drawable.ic_nav_company,
        )
        bind(homeBtn)
        bind(companyBtn)
    }

    /** 一键回家（按钮/方向盘共用）：有坐标走坐标导航，无坐标走 naviSpecial */
    fun naviHome() {
        NuiTts(context).speak("正在发起回家的导航")
        loadTarget(KEY_HOME)?.let { startNavigation(it) } ?: naviSpecial(DEST_HOME)
    }

    /** 一键去公司（按钮/方向盘共用） */
    fun naviCompany() {
        NuiTts(context).speak("正在发起往公司的导航")
        loadTarget(KEY_COMPANY)?.let { startNavigation(it) } ?: naviSpecial(DEST_COMPANY)
    }

    /** 绑定按钮：有坐标→10007 广播导航；无坐标→navi2SpecialDest?dest=home/crop 一键导航。 */
    private fun bind(b: NavBtn) {
        b.icon.setImageResource(b.iconRes)
        val t = loadTarget(b.key)
        if (t == null) {
            // 未设坐标：用 navi2SpecialDest?dest=home/crop 直接触发高德已保存的家/公司地址
            b.label.text = b.default.label
        } else {
            // 有坐标：10007 广播直接导航
            b.label.text = t.label
        }
        // 点击统一走 naviHome/naviCompany（内部按是否设坐标选择导航方式），并播报语音
        b.container.setOnClickListener {
            if (b.key == KEY_HOME) naviHome() else naviCompany()
        }
        b.container.setOnLongClickListener { editTarget(b, t ?: b.default); true }
    }

    /** 校验并加载坐标：lat/lon 必须在有效范围内，否则视为无效（避免"路线获取失败"）。 */
    private fun loadTarget(key: String): NavTarget? {
        val raw = prefs.getString(key, null) ?: return null
        val p = raw.split('\n')
        if (p.size < 3) return null
        val label = p[0]
        val lat = p[1].toDoubleOrNull() ?: return null
        val lon = p[2].toDoubleOrNull() ?: return null
        // 有效范围：纬度 [-90, 90]，经度 [-180, 180]，且不能是 (0,0)（默认占位）
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (lat == 0.0 && lon == 0.0) return null
        return NavTarget(label, lat, lon)
    }

    /** 发送 10007 直接导航广播（高德车机版 AmapAuto 标准协议，真机验证成功）。
     *  参数名严格按官方文档：EXTRA_DLAT/EXTRA_DLON/EXTRA_DNAME/EXTRA_DEV/EXTRA_M/ENTRY_LAT/ENTRY_LON。
     *  返回 true 表示广播投递成功。 */
    private fun sendNaviBroadcast(t: NavTarget): Boolean {
        val intent = Intent(ACTION_RECV).apply {
            component = ComponentName(APP_AMAP, AMAP_AUTO_RECEIVER)
            setPackage(APP_AMAP)
            putExtra("KEY_TYPE", KEY_TYPE_NAVI)           // 10007
            putExtra("EXTRA_DNAME", t.label)             // 终点名称
            putExtra("EXTRA_DLAT", t.lat)                 // 终点纬度 (double)
            putExtra("EXTRA_DLON", t.lon)                 // 终点经度 (double)
            putExtra("ENTRY_LAT", t.lat)                  // 到达点纬度
            putExtra("ENTRY_LON", t.lon)                  // 到达点经度
            putExtra("EXTRA_DEV", 0)                       // 0=gcj02 已加密
            putExtra("EXTRA_M", -1)                       // -1=默认规划策略
            putExtra("SOURCE_APP", "NUI")
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        return runCatching { context.sendBroadcast(intent) }.isSuccess
    }

    /** 一键回家/公司：用 navi2SpecialDest?dest=home/crop URI（用户真机验证成功）。
     *  发起导航后延迟 4s 自动返回 NUI，高德浮窗恢复显示导航画面。
     *  需加 CATEGORY_DEFAULT，否则高德不响应。百度不支持，直接打开主界面。 */
    private fun naviSpecial(dest: String) {
        val app = preferredApp()
        if (!installedApp(app)) {
            NuiToast.show(context, "未安装 ${mapAppName(app)}", Toast.LENGTH_LONG)
            return
        }
        if (app == APP_BAIDU) { launchApp(APP_BAIDU); return }

        val label = if (dest == DEST_HOME) "回家" else "去公司"
        val uri = "androidauto://navi2SpecialDest?sourceApplication=NUI&dest=$dest"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(APP_AMAP)
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(i) }.isSuccess) {
            NuiToast.show(context, "已发起$label", Toast.LENGTH_SHORT)
            returnToNui(4000L)
        } else {
            // 兜底：启动高德后再试一次，成功后同样返回 NUI
            launchApp(APP_AMAP)
            handler.postDelayed({
                if (runCatching { context.startActivity(i) }.isSuccess) {
                    NuiToast.show(context, "已发起$label", Toast.LENGTH_SHORT)
                    returnToNui(4000L)
                } else {
                    val mapUri = "androidauto://rootmap?sourceApplication=NUI"
                    val mapI = Intent(Intent.ACTION_VIEW, Uri.parse(mapUri))
                        .setPackage(APP_AMAP)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(mapI) }
                    NuiToast.show(context, "${label}失败，请在高德中手动发起", Toast.LENGTH_LONG)
                }
            }, 3000L)
        }
    }

    /** 发起导航后延迟返回 NUI，并恢复高德浮窗（用户可在悬浮窗看到导航画面）。 */
    private fun returnToNui(delayMs: Long) {
        handler.postDelayed({
            val back = Intent().apply {
                setClassName(context, "com.nui.launcher.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            runCatching { context.startActivity(back) }
            // 回 NUI 后恢复浮窗，高德在后台继续导航
            handler.postDelayed({ onShowFloat?.invoke() }, 500L)
        }, delayMs)
    }

    /** 坐标导航：高德首选 10007 广播（真机验证成功），URI 方式在车机版上无效仅作兜底。百度用 URI。 */
    private fun startNavigation(t: NavTarget) {
        val app = preferredApp()
        if (!installedApp(app)) {
            NuiToast.show(context, "未安装 ${mapAppName(app)}，长按按钮可切换", Toast.LENGTH_LONG)
            return
        }
        when (app) {
            APP_AMAP -> startAmapNav(t)
            APP_BAIDU -> {
                val uri = "baidumap://map/direction?destination=${t.lat},${t.lon}" +
                    "&destination_name=${Uri.encode(t.label)}&coord_type=gcj02&mode=driving&src=com.nui.launcher"
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    .setPackage(APP_BAIDU)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(i) }
                    .onFailure { launchApp(app) }
            }
        }
    }

    /** 高德车机版坐标导航：10007 广播为主（真机验证成功→路线规划+5s倒计时自动导航），
     *  发起后延迟返回 NUI 恢复浮窗。URI 方式仅兜底。 */
    private fun startAmapNav(t: NavTarget) {
        // 首选：10007 直接导航广播（需高德在前台，先启动预热）
        launchApp(APP_AMAP)
        handler.postDelayed({
            if (sendNaviBroadcast(t)) {
                NuiToast.show(context, "导航至${t.label}", Toast.LENGTH_SHORT)
                returnToNui(5000L)
            } else {
                // 兜底①：androidauto://navi URI（车机版实测无效，保留兜底）
                val naviUri = "androidauto://navi?sourceApplication=NUI" +
                    "&lat=${t.lat}&lon=${t.lon}&poiname=${Uri.encode(t.label)}&dev=0"
                val naviIntent = Intent(Intent.ACTION_VIEW, Uri.parse(naviUri))
                    .setPackage(APP_AMAP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(naviIntent) }.isSuccess) return@postDelayed

                // 兜底②：androidauto://route URI
                val routeUri = "androidauto://route?sourceApplication=NUI" +
                    "&dlat=${t.lat}&dlon=${t.lon}&dname=${Uri.encode(t.label)}&dev=0&m=0"
                val routeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(routeUri))
                    .setPackage(APP_AMAP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(routeIntent) }.isSuccess) return@postDelayed

                // 兜底③：rootmap 打开主图
                val mapUri = "androidauto://rootmap?sourceApplication=NUI"
                val mapI = Intent(Intent.ACTION_VIEW, Uri.parse(mapUri))
                    .setPackage(APP_AMAP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(mapI) }
                NuiToast.show(context, "导航失败，请在高德中手动发起", Toast.LENGTH_LONG)
            }
        }, 3000L)
    }

    private fun launchApp(pkg: String) {
        if (pkg == APP_AMAP) {
            // 高德车机版：优先用 UsbFillActivity（官方推荐启动入口，第三方调用更可靠）
            val fillI = Intent().apply {
                component = ComponentName(pkg, "com.autonavi.auto.remote.fill.UsbFillActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(fillI) }.isSuccess) return
        }
        val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: run {
            NuiToast.show(context, "无法启动 ${mapAppName(pkg)}", Toast.LENGTH_SHORT)
            return
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
            .onFailure { NuiToast.show(context, "无法启动 ${mapAppName(pkg)}", Toast.LENGTH_SHORT) }
    }

    private fun installedApp(pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun preferredApp(): String {
        var app = prefs.getString(KEY_APP, null)
        if (app == null) {
            app = when {
                installedApp(APP_AMAP) -> APP_AMAP
                installedApp(APP_BAIDU) -> APP_BAIDU
                else -> APP_AMAP
            }
            prefs.edit { putString(KEY_APP, app) }
        }
        return app
    }

    /** 长按菜单：设置/清除直达坐标、切换地图。 */
    private fun editTarget(b: NavBtn, current: NavTarget) {
        val hasCoords = prefs.contains(b.key)
        val items = mutableListOf(
            "设置直达坐标（可选，一键导航）",
            "切换地图（当前: ${mapAppName(preferredApp())}）",
        )
        if (hasCoords) items.add("清除坐标")
        onHideFloat?.invoke()
        followUpPending = false
        val d = AlertDialog.Builder(context)
            .setTitle(b.default.label)
            .setItems(items.toTypedArray()) { _, which ->
                followUpPending = true
                when (which) {
                    0 -> showEditDialog(b, current)
                    1 -> showAppPicker()
                    2 -> {
                        followUpPending = false // 不弹子对话框，直接恢复浮窗
                        prefs.edit { remove(b.key) }
                        bind(b)
                        NuiToast.show(context, "已清除坐标", Toast.LENGTH_SHORT)
                    }
                }
            }.create()
        d.setOnDismissListener { if (!followUpPending) onShowFloat?.invoke() }
        d.show()
    }

    private fun showEditDialog(b: NavBtn, current: NavTarget = b.default) {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val etName = EditText(context).apply { hint = "名称（如：家）"; setText(current.label) }
        val etLat = EditText(context).apply {
            hint = "纬度（如 39.9042）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(if (current.lat == 0.0) "" else current.lat.toString())
        }
        val etLon = EditText(context).apply {
            hint = "经度（如 116.4074）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(if (current.lon == 0.0) "" else current.lon.toString())
        }
        wrap.addView(etName)
        wrap.addView(etLat)
        wrap.addView(etLon)
        onHideFloat?.invoke()
        val d = AlertDialog.Builder(context)
            .setTitle("设置直达坐标")
            .setMessage("设好后点击按钮将用坐标直达导航；留空清除则改用高德一键回家/去公司")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim().ifEmpty { current.label }
                val lat = etLat.text.toString().trim().toDoubleOrNull()
                val lon = etLon.text.toString().trim().toDoubleOrNull()
                if (lat == null || lon == null) {
                    NuiToast.show(context, "经纬度格式错误", Toast.LENGTH_SHORT)
                    return@setPositiveButton
                }
                prefs.edit { putString(b.key, "$name\n$lat\n$lon") }
                NuiToast.show(context, "已保存，点击将一键导航", Toast.LENGTH_SHORT)
                bind(b)
            }
            .setNegativeButton("取消", null)
            .create()
        d.setOnDismissListener { onShowFloat?.invoke() }
        d.show()
    }

    private fun showAppPicker() {
        val apps = mutableListOf<String>()
        if (installedApp(APP_AMAP)) apps.add(APP_AMAP)
        if (installedApp(APP_BAIDU)) apps.add(APP_BAIDU)
        if (apps.isEmpty()) {
            NuiToast.show(context, "未安装高德或百度地图", Toast.LENGTH_LONG)
            return
        }
        val labels = apps.map { mapAppName(it) }.toTypedArray()
        onHideFloat?.invoke()
        val d = AlertDialog.Builder(context)
            .setTitle("导航地图")
            .setItems(labels) { _, which ->
                prefs.edit { putString(KEY_APP, apps[which]) }
                NuiToast.show(context, "已切换为 ${labels[which]}", Toast.LENGTH_SHORT)
            }.create()
        d.setOnDismissListener { onShowFloat?.invoke() }
        d.show()
    }

    private fun mapAppName(pkg: String) = when (pkg) {
        APP_AMAP -> "高德地图"
        APP_BAIDU -> "百度地图"
        else -> pkg
    }

    private data class NavTarget(val label: String, val lat: Double, val lon: Double)

    companion object {
        private const val PREFS = "nui_nav"
        private const val KEY_HOME = "home"
        private const val KEY_COMPANY = "company"
        private const val KEY_APP = "nav_app"
        const val APP_AMAP = "com.autonavi.amapauto"
        const val APP_BAIDU = "com.baidu.BaiduMap"
        // 高德车机版标准广播协议
        private const val ACTION_RECV = "AUTONAVI_STANDARD_BROADCAST_RECV"
        private const val AMAP_AUTO_RECEIVER =
            "com.autonavi.amapauto.adapter.internal.AmapAutoBroadcastReceiver"
        // 10007 = 直接导航（传入终点坐标+名称，真机验证成功：进入路线规划+5s倒计时自动开始导航）
        private const val KEY_TYPE_NAVI = 10007
        // navi2SpecialDest 正确参数：dest=home（回家）/ dest=crop（去公司）
        // 真机验证成功：am start -a VIEW -d "androidauto://navi2SpecialDest?sourceApplication=NUI&dest=home" -p com.autonavi.amapauto
        private const val DEST_HOME = "home"
        private const val DEST_COMPANY = "crop"
        // 旧 destType 参数已弃用（0~10 全指向同一终点）
        // 仅作为未设置时的标签占位，不再作为导航默认目的地
        private val DEFAULT_HOME = NavTarget("回家", 0.0, 0.0)
        private val DEFAULT_COMPANY = NavTarget("公司", 0.0, 0.0)
    }
}
