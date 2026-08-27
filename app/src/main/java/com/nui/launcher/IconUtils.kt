package com.nui.launcher

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable

/**
 * 应用图标加载工具：
 * - getIconWithFallback: 返回应用图标，若系统未给则用默认占位图
 * - toBitmap: 将 Drawable 转为 Bitmap（适配 RecyclerView 异步回收）
 */
object IconUtils {

    fun getIconWithFallback(pm: PackageManager, packageName: String, fallback: Drawable): Drawable {
        return try {
            pm.getApplicationIcon(packageName) ?: fallback
        } catch (e: PackageManager.NameNotFoundException) {
            fallback
        }
    }

    fun toBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bmp
    }
}
