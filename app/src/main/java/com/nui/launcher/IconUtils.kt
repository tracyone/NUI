package com.nui.launcher

import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * 应用图标加载工具：
 * - getIconWithFallback: 返回应用图标，若系统未给则用默认占位图
 * - hasCustomIcon: 判断应用是否有自定义图标（区别于系统默认图标）
 * - loadBitmap: 按需加载图标 Bitmap（LruCache 缓存，翻页回来秒显；不同尺寸分别缓存）
 * - toBitmap: 将 Drawable 转为 Bitmap
 *
 * 内存考虑：车机内存有限，缓存上限 8MB，按"包名@尺寸"为 key，
 * 避免重复加载同一应用同一尺寸的图标。
 */
object IconUtils {

    private const val MAX_CACHE_BYTES = 8 * 1024 * 1024
    private val bitmapCache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun getIconWithFallback(pm: PackageManager, packageName: String, fallback: Drawable): Drawable {
        return try {
            pm.getApplicationIcon(packageName) ?: fallback
        } catch (e: PackageManager.NameNotFoundException) {
            fallback
        }
    }

    /**
     * 应用是否有自定义图标（区别于系统默认图标），两层判断：
     * 1. activity / application 都未声明 icon（iconRes==0 && appIconRes==0）→ 系统默认图标 → false；
     * 2. 有声明但实际显示的图标与系统默认应用图标（getDefaultActivityIcon）constantState 相同
     *    （如显式引用 @android:drawable/sym_def_app_icon）→ false；
     *    其余（声明了自己的图标资源）→ true。
     *
     * 不能只用 loadIcon 与 defaultActivityIcon 比较（第一版）：
     * Android 8+ 对"完全无 icon 声明"的应用，loadIcon 返回 adaptive 包装的系统默认图标，
     * constantState 与 getDefaultActivityIcon()（普通 drawable）不相等 → 全部误判"有图标"，
     * 排序退化为字母序（车机反馈的问题）。
     */
    fun hasCustomIcon(pm: PackageManager, ri: ResolveInfo): Boolean {
        val ai = ri.activityInfo ?: return false
        val iconRes = ai.icon
        val appIconRes = ai.applicationInfo?.icon ?: 0
        if (iconRes == 0 && appIconRes == 0) return false
        return try {
            ri.loadIcon(pm).constantState != pm.defaultActivityIcon.constantState
        } catch (e: Exception) {
            true
        }
    }

    /** 按需加载应用图标 Bitmap：先查缓存，未命中才从 PackageManager 加载并转 Bitmap 入缓存 */
    fun loadBitmap(
        pm: PackageManager,
        packageName: String,
        fallback: Drawable,
        sizePx: Int,
    ): Bitmap {
        val key = "$packageName@$sizePx"
        bitmapCache.get(key)?.let { return it }
        val drawable = getIconWithFallback(pm, packageName, fallback)
        val bmp = toBitmap(drawable, sizePx)
        bitmapCache.put(key, bmp)
        return bmp
    }

    /** 清除缓存（应用卸载/图标变化后调用，避免残留旧图标） */
    fun clearCache() = bitmapCache.evictAll()

    fun toBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bmp
    }
}
