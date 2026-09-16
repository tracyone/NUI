package com.nui.launcher

import android.content.Context
import android.content.Intent

/**
 * Dock 槽位组装：
 * - 前三个固定槽位：[FIXED_COUNT] = 3（绑定的地图 / 绑定的音乐 / 最近打开的程序）
 * - 后面的槽位：用户在 DockConfig 中配置的应用
 *
 * 固定槽位不可移除（长按无操作），用户配置槽位保持原有的添加/移除逻辑。
 * 渲染时用户配置的索引 = 渲染索引 - FIXED_COUNT。
 */
object DockSlots {
    const val FIXED_COUNT = 3

    /** 组装 dock 槽位列表，前三个固定，后面用户配置 */
    fun load(context: Context, mapPkg: String?, musicPkg: String?): List<AppModel?> {
        val result = mutableListOf<AppModel?>()
        // slot 0: 绑定的地图
        result.add(mapPkg?.let { pkgToModel(context, it) })
        // slot 1: 绑定的音乐
        result.add(musicPkg?.let { pkgToModel(context, it) })
        // slot 2: 最近打开的程序（排除地图、音乐及用户自定义槽位中已显示的应用，
        // 避免最近槽与固定/自定义槽重复显示同一个应用）
        val exclude = setOfNotNull(mapPkg, musicPkg) +
            DockConfig.loadApps(context).mapNotNull { it?.packageName }
        result.add(RecentApps.getRecent(context, exclude))
        // slot 3+: 用户配置
        result.addAll(DockConfig.loadApps(context))
        return result
    }

    private fun pkgToModel(context: Context, pkg: String): AppModel? {
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage(pkg) ?: return null
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        return AppModel(
            label = label,
            packageName = pkg,
            icon = IconUtils.getIconWithFallback(pm, pkg, pm.defaultActivityIcon),
            launchIntent = launch.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
        )
    }
}
