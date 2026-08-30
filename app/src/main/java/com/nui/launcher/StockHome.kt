package com.nui.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat

/**
 * "回到原车桌面"快捷方式：把其它设置为 CATEGORY_HOME 的 Launcher 拉起来。
 *
 * 识别方式：虚拟包名 [PKG_STOCK_HOME]。DockConfig / DockPickerDialog 都认这个包，
 * 生成一个带 "原车桌面" 标签和 房子🏠图标的 AppModel，点击触发 [launch]。
 */
object StockHome {
    const val PKG_STOCK_HOME = "__nui_stock_home__"
    const val LABEL_STOCK_HOME = "原车桌面"

    /** 构造代表"原车桌面"的虚拟 AppModel，图标用系统级返回Home/房子 icon（tint 白色） */
    fun model(context: Context): AppModel {
        val pm = context.packageManager
        val baseIcon: Drawable = runCatching {
            // 用内置的 回家 图标，语义最贴近
            ContextCompat.getDrawable(context, R.drawable.ic_nav_home)!!.mutate()
        }.recoverCatching {
            // 兜底：取第一个其它 Launcher 的图标；再兜底用启动器前景
            firstOtherLauncherIntent(context, pm)?.component?.packageName
                ?.let { pm.getApplicationIcon(it).mutate() }
                ?: ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)!!
        }.getOrDefault(ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)!!)
        // 不固定 tint：由调用方（如 MainActivity.renderDock）按深浅模式设置，
        // 避免浅色背景下浅灰图标看不清
        val icon: Drawable = baseIcon
        val launchIntent = Intent().apply {
            // 不通过具体包，直接走我们的自定义 action；
            // 最终点击时在 MainActivity 侧按 PKG_STOCK_HOME 拦截 -> launch(context)
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            // 用 component 占位，避免 queryIntentActivities 不认识；点击前会被 override
            setPackage(PKG_STOCK_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return AppModel(
            label = LABEL_STOCK_HOME,
            packageName = PKG_STOCK_HOME,
            icon = icon,
            launchIntent = launchIntent,
        )
    }

    /** 点击虚拟条目时真正执行：拉起第一个非 NUI 的系统 Launcher/Home。 */
    fun launch(context: Context): Boolean {
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

        // 1) 优先：已经有"非NUI"的默认 Launcher（用户设置过的），直接用
        val def = pm.resolveActivity(homeIntent, 0)
        if (def != null && def.activityInfo != null) {
            val defPkg = def.activityInfo.packageName
            if (defPkg != context.packageName &&
                defPkg != "android" &&
                !defPkg.startsWith("com.android.") || true
            ) {
                // 如果 resolveActivity 返回的是 ResolverActivity（com.android.internal.app.ResolverActivity）
                // 或系统的默认选择器，这也可以接受，直接走 startActivity 会弹选择
            }
            if (defPkg != context.packageName) {
                val i = Intent(homeIntent).apply {
                    component = android.content.ComponentName(defPkg, def.activityInfo.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (runCatching { context.startActivity(i); true }.getOrDefault(false)) return true
            }
        }

        // 2) 兜底：列出所有 HOME 类应用，排除自己，取第一个启动
        val others = pm.queryIntentActivities(homeIntent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .sortedByDescending { it.priority }
        if (others.isEmpty()) return false
        for (ri in others) {
            val pkg = ri.activityInfo.packageName
            // 跳过 ResolverActivity 这种壳
            if (pkg == "android" || pkg == "com.android.internal.app") continue
            val i = Intent(homeIntent).apply {
                component = android.content.ComponentName(pkg, ri.activityInfo.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            if (runCatching { context.startActivity(i); true }.getOrDefault(false)) return true
        }
        // 3) 最后兜底：让系统弹 Launcher 选择器（会把 NUI 自己也列进去，但系统也会问）
        val chooser = Intent.createChooser(homeIntent, LABEL_STOCK_HOME).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(chooser); true }.getOrDefault(false)
    }

    private fun firstOtherLauncherIntent(context: Context, pm: PackageManager): Intent? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.queryIntentActivities(homeIntent, 0)
            .firstOrNull { it.activityInfo.packageName != context.packageName }
            ?.let { ri ->
                Intent().apply {
                    component = android.content.ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
                }
            }
    }
}
