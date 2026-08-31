package com.nui.launcher

import com.nui.launcher.NuiToast

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
import android.util.Log
import android.view.View
import android.widget.Toast
import java.io.File

/**
 * 桌面壁纸控制器（白天 / 晚上双槽位）。
 *
 * - 白天壁纸用于浅色外观，晚上壁纸用于深色外观（跟随 [UiTheme.isDark]）。
 * - 回退规则：晚上未设置 → 用白天壁纸；白天未设置 → 用默认壁纸。
 * - 选图方式：ACTION_GET_CONTENT（系统图库选择器，免存储权限，临时授权）。
 * - 持久化：选中图片复制到 app 内部存储（filesDir/wallpaper_day.jpg 与 wallpaper_night.jpg），
 *   重启后从内部文件加载，稳定可靠。
 * - 渲染：居中裁剪到屏幕尺寸，设为根布局背景；默认壁纸为 default_wallpaper 深色渐变。
 *
 * 入口：桌面设置 → 外观 → 壁纸（原长按时钟区入口已移除）。
 */
class WallpaperController(
    private val activity: Activity,
    private val root: View,
) {
    enum class Slot(val fileName: String) {
        DAY("wallpaper_day.jpg"),
        NIGHT("wallpaper_night.jpg"),
    }

    /** 弹菜单/选图前隐藏悬浮地图，关闭后恢复（由外部注入，同 NavHost/MusicHost 模式） */
    var onHideFloat: (() -> Unit)? = null
    var onShowFloat: (() -> Unit)? = null

    /** 当前正在设置哪个槽位的壁纸（选图结果回填用） */
    private var currentSlot: Slot = Slot.DAY

    /** 选图会打开系统选择器：菜单关闭时若选择器将接管，则不恢复浮窗 */
    private var followUpPending = false

    /** 启动系统图库选择器。需在宿主 Activity.onActivityResult 接收回调。 */
    fun pick(slot: Slot) {
        currentSlot = slot
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        runCatching {
            activity.startActivityForResult(Intent.createChooser(intent, "选择壁纸"), REQ_PICK)
        }.onFailure {
            onShowFloat?.invoke()
            NuiToast.show(activity, "无法打开图库选择器", Toast.LENGTH_SHORT)
            Log.e(TAG, "pick failed", it)
        }
    }

    /** 在 onActivityResult 中调用，处理选图结果。 */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK) return
        onShowFloat?.invoke() // 系统选择器已关闭，恢复悬浮地图
        if (resultCode != Activity.RESULT_OK || data == null) return
        val uri = data.data ?: return
        if (copyToInternal(uri, currentSlot)) {
            NuiToast.show(activity, "${slotLabel(currentSlot)}已设置", Toast.LENGTH_SHORT)
        } else {
            NuiToast.show(activity, "壁纸加载失败", Toast.LENGTH_SHORT)
        }
    }

    /** 把选中图片复制到对应槽位内部文件，然后应用。 */
    private fun copyToInternal(uri: Uri, slot: Slot): Boolean {
        return try {
            val target = File(activity.filesDir, slot.fileName)
            activity.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return false
            applyForAppearance(UiTheme.isDark(activity))
            true
        } catch (e: Exception) {
            Log.e(TAG, "copyToInternal failed", e)
            false
        }
    }

    /** 启动时应用壁纸（自动迁移旧版单壁纸 → 白天壁纸）。 */
    fun applyOnStart() {
        migrateLegacy()
        applyForAppearance(UiTheme.isDark(activity))
    }

    /** 按当前外观深浅重新解析并应用壁纸（主题切换 / 系统深浅切换时调用）。 */
    fun applyForAppearance(dark: Boolean) {
        val file = resolveFile(dark)
        if (file == null || !applyFromFile(file)) applyDefault()
    }

    /** 当前槽位是否已自定义壁纸。 */
    fun hasCustom(slot: Slot): Boolean = File(activity.filesDir, slot.fileName).exists()

    /** 恢复默认壁纸（指定槽位；默认双槽位全清）。 */
    fun reset(slot: Slot? = null) {
        val slots = slot?.let { listOf(it) } ?: Slot.values().toList()
        for (s in slots) File(activity.filesDir, s.fileName).delete()
        applyForAppearance(UiTheme.isDark(activity))
        NuiToast.show(activity, "已恢复默认壁纸", Toast.LENGTH_SHORT)
    }

    /** 弹菜单：选择新壁纸 / 恢复默认（按槽位）。 */
    fun showMenu(slot: Slot) {
        val has = hasCustom(slot)
        val items = if (has) arrayOf("选择新壁纸", "恢复默认壁纸") else arrayOf("选择壁纸")
        onHideFloat?.invoke()
        followUpPending = false
        val d = AlertDialog.Builder(activity)
            .setTitle(slotLabel(slot))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { followUpPending = true; pick(slot) } // 选择器将接管，浮窗保持隐藏
                    1 -> reset(slot)
                }
            }.create()
        d.setOnDismissListener { if (!followUpPending) onShowFloat?.invoke() }
        d.show()
    }

    /** 旧版单壁纸 wallpaper.jpg → 白天壁纸（若白天槽位尚未设置） */
    private fun migrateLegacy() {
        val legacy = File(activity.filesDir, "wallpaper.jpg")
        val day = File(activity.filesDir, Slot.DAY.fileName)
        if (legacy.exists() && !day.exists()) {
            runCatching { legacy.copyTo(day, overwrite = false) }
            legacy.delete()
        }
    }

    /** 解析当前外观应使用的壁纸文件（null = 用默认）。 */
    private fun resolveFile(dark: Boolean): File? {
        val day = File(activity.filesDir, Slot.DAY.fileName)
        val night = File(activity.filesDir, Slot.NIGHT.fileName)
        return if (dark) {
            // 晚上：晚上壁纸 → 白天壁纸 → 默认
            night.takeIf { it.exists() } ?: day.takeIf { it.exists() }
        } else {
            // 白天：白天壁纸 → 默认
            day.takeIf { it.exists() }
        }
    }

    private fun slotLabel(slot: Slot) =
        if (slot == Slot.DAY) "白天壁纸" else "晚上壁纸"

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
            true
        } catch (e: Exception) {
            Log.e(TAG, "applyFromFile failed", e)
            false
        }
    }

    private fun applyDefault() {
        root.background = activity.getDrawable(R.drawable.default_wallpaper)
            ?: activity.getDrawable(R.drawable.bg_launch)
            ?: ColorDrawable(Color.parseColor("#0B0D11"))
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
        const val REQ_PICK = 0x1011
    }
}
