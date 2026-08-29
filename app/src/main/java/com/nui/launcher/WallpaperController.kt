package com.nui.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.edit
import java.io.File
import java.io.InputStream

/**
 * 桌面壁纸控制器。
 *
 * 选图方式：ACTION_PICK（系统图库选择器，直接返回选中图片 URI）
 *  - 兼容性好：所有 Android 版本的图库都支持
 *  - 无需 READ_EXTERNAL_STORAGE 权限（ACTION_PICK 由系统授权临时读权限）
 *
 * 持久化：把选中图片复制到 app 内部存储（filesDir/wallpaper.jpg）
 *  - 避免 URI 权限丢失（ACTION_PICK 的 URI 不持久）
 *  - 重启后从内部文件加载，稳定可靠
 *
 * 渲染：居中裁剪到屏幕尺寸，设为根布局背景。
 * 默认壁纸：bg_launch 深色渐变。
 *
 * 触发：长按右侧面板时钟区 → "设置壁纸 / 恢复默认"。
 */
class WallpaperController(
    private val activity: Activity,
    private val root: View,
) {
    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 弹菜单/选图前隐藏悬浮地图，关闭后恢复（由外部注入，同 NavHost/MusicHost 模式） */
    var onHideFloat: (() -> Unit)? = null
    var onShowFloat: (() -> Unit)? = null
    // 选图会打开系统选择器：菜单关闭时若选择器将接管，则不恢复浮窗
    private var followUpPending = false

    /** 启动系统图库选择器。需在 MainActivity.onActivityResult 接收回调。 */
    fun pick() {
        // ACTION_GET_CONTENT + OPENABLE：弹出系统选择器，直接返回选中图片 URI
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        runCatching {
            activity.startActivityForResult(Intent.createChooser(intent, "选择壁纸"), REQ_PICK)
        }.onFailure {
            onShowFloat?.invoke() // 选择器未打开，恢复悬浮地图
            Toast.makeText(activity, "无法打开图库选择器", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "pick failed", it)
        }
    }

    /** 在 onActivityResult 中调用，处理选图结果。 */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK) return
        onShowFloat?.invoke() // 系统选择器已关闭，恢复悬浮地图
        if (resultCode != Activity.RESULT_OK || data == null) return
        val uri = data.data ?: return
        // 复制到内部存储持久化
        if (copyToInternal(uri)) {
            Toast.makeText(activity, "壁纸已设置", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(activity, "壁纸加载失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 把选中图片复制到内部存储，然后应用。 */
    private fun copyToInternal(uri: Uri): Boolean {
        return try {
            val target = File(activity.filesDir, WALLPAPER_FILE)
            activity.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return false
            applyFromFile(target)
        } catch (e: Exception) {
            Log.e(TAG, "copyToInternal failed", e)
            false
        }
    }

    /** 应用持久化的壁纸（启动时调用）。 */
    fun applyOnStart() {
        val file = File(activity.filesDir, WALLPAPER_FILE)
        if (!file.exists()) { applyDefault(); return }
        if (!applyFromFile(file)) applyDefault()
    }

    /** 从内部文件解码 + 居中裁剪到屏幕尺寸，设为根背景。 */
    private fun applyFromFile(file: File): Boolean {
        val sw = activity.resources.displayMetrics.widthPixels
        val sh = activity.resources.displayMetrics.heightPixels
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            val sample = calcSample(opts.outWidth, opts.outHeight, sw, sh)
            val decode = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, decode) ?: return false
            val fitted = centerCrop(bmp, sw, sh)
            root.background = BitmapDrawable(activity.resources, fitted)
            prefs.edit { putBoolean(KEY_SET, true) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "applyFromFile failed", e)
            false
        }
    }

    private fun applyDefault() {
        root.background = activity.getDrawable(R.drawable.bg_launch)
            ?: ColorDrawable(Color.parseColor("#0B0D11"))
    }

    /** 恢复默认壁纸。 */
    fun reset() {
        val file = File(activity.filesDir, WALLPAPER_FILE)
        file.delete()
        prefs.edit { putBoolean(KEY_SET, false) }
        applyDefault()
        Toast.makeText(activity, "已恢复默认壁纸", Toast.LENGTH_SHORT).show()
    }

    /** 弹菜单：设置壁纸 / 恢复默认。 */
    fun showMenu() {
        val hasCustom = File(activity.filesDir, WALLPAPER_FILE).exists()
        val items = if (hasCustom) arrayOf("选择新壁纸", "恢复默认壁纸")
        else arrayOf("选择壁纸")
        onHideFloat?.invoke()
        followUpPending = false
        val d = AlertDialog.Builder(activity)
            .setTitle("桌面壁纸")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { followUpPending = true; pick() } // 选择器将接管，浮窗保持隐藏
                    1 -> reset()
                }
            }.create()
        d.setOnDismissListener { if (!followUpPending) onShowFloat?.invoke() }
        d.show()
    }

    private fun calcSample(ow: Int, oh: Int, tw: Int, th: Int): Int {
        var s = 1
        if (ow <= 0 || oh <= 0) return s
        while (ow / s > tw * 2 || oh / s > th * 2) s *= 2
        return s
    }

    /** 居中裁剪到目标比例（不拉伸变形）。 */
    private fun centerCrop(src: Bitmap, tw: Int, th: Int): Bitmap {
        val sw = src.width
        val sh = src.height
        val scale = maxOf(tw.toFloat() / sw, th.toFloat() / sh)
        val scaledW = sw * scale
        val scaledH = sh * scale
        val matrix = Matrix().apply { postScale(scale, scale) }
        val scaled = Bitmap.createBitmap(src, 0, 0, sw, sh, matrix, true)
        val x = ((scaledW - tw) / 2f).toInt().coerceAtLeast(0)
        val y = ((scaledH - th) / 2f).toInt().coerceAtLeast(0)
        val cw = tw.coerceAtMost(scaled.width - x)
        val ch = th.coerceAtMost(scaled.height - y)
        return Bitmap.createBitmap(scaled, x, y, cw, ch)
    }

    companion object {
        private const val TAG = "WallpaperController"
        private const val PREFS = "nui_wallpaper"
        private const val KEY_SET = "wallpaper_set"
        private const val WALLPAPER_FILE = "wallpaper.jpg"
        const val REQ_PICK = 0x1011
    }
}
