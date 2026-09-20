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

    /** 负一屏壁纸模式：FOLLOW=跟随桌面（默认）/ IMAGE=独立静态图 / VIDEO=独立动态视频 */
    enum class MinusMode { FOLLOW, IMAGE, VIDEO }

    companion object {
        private const val TAG = "WallpaperController"
        const val REQ_PICK = 0x1011
        const val REQ_PICK_MINUS_IMAGE = 0x1012
        const val REQ_PICK_MINUS_VIDEO = 0x1013
        private const val PREFS = "nui_wallpaper"
        private const val KEY_MINUS_MODE = "minus_mode"
        private const val MINUS_IMAGE_FILE = "minus_image.jpg"
        private const val MINUS_VIDEO_FILE = "minus_video.mp4"
    }

    /** 负一屏壁纸变更回调（选完图片/视频后通知 MainActivity 重新应用） */
    var onMinusWallpaperChanged: (() -> Unit)? = null

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

    // ==================== 负一屏壁纸 ====================

    private fun minusPrefs() =
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前负一屏壁纸模式（默认跟随桌面）。 */
    fun minusWallpaperMode(): MinusMode = try {
        MinusMode.valueOf(minusPrefs().getString(KEY_MINUS_MODE, MinusMode.FOLLOW.name)!!)
    } catch (e: Exception) { MinusMode.FOLLOW }

    /** 负一屏视频壁纸绝对路径（VIDEO 模式且文件存在时）。 */
    fun minusWallpaperPath(): String? {
        if (minusWallpaperMode() != MinusMode.VIDEO) return null
        val f = File(activity.filesDir, MINUS_VIDEO_FILE)
        return if (f.exists()) f.absolutePath else null
    }

    /** 负一屏静态壁纸 Bitmap（IMAGE 模式且文件存在时，居中裁剪到屏幕尺寸）。 */
    fun minusWallpaperBitmap(): Bitmap? {
        if (minusWallpaperMode() != MinusMode.IMAGE) return null
        val file = File(activity.filesDir, MINUS_IMAGE_FILE)
        if (!file.exists()) return null
        return try {
            val sw = activity.resources.displayMetrics.widthPixels
            val sh = activity.resources.displayMetrics.heightPixels
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            val sample = calcSample(opts.outWidth, opts.outHeight, sw, sh)
            val decode = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, decode) ?: return null
            centerCrop(bmp, sw, sh)
        } catch (e: Exception) {
            Log.e(TAG, "minus bitmap failed", e); null
        }
    }

    /** 弹负一屏壁纸菜单：跟随桌面 / 选择图片 / 选择视频 / （有图或视频时）恢复跟随。 */
    fun showMinusMenu() {
        val mode = minusWallpaperMode()
        val items = mutableListOf("跟随桌面壁纸", "选择静态图片", "选择动态视频")
        onHideFloat?.invoke()
        followUpPending = false
        val d = AlertDialog.Builder(activity)
            .setTitle("负一屏壁纸（当前：${minusModeLabel(mode)}）")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> setMinusMode(MinusMode.FOLLOW)
                    1 -> { followUpPending = true; pickMinusImage() }
                    2 -> { followUpPending = true; pickMinusVideo() }
                }
            }.create()
        d.setOnDismissListener { if (!followUpPending) onShowFloat?.invoke() }
        d.show()
    }

    private fun minusModeLabel(m: MinusMode) = when (m) {
        MinusMode.FOLLOW -> "跟随桌面"
        MinusMode.IMAGE -> "静态图片"
        MinusMode.VIDEO -> "动态视频"
    }

    private fun setMinusMode(m: MinusMode) {
        minusPrefs().edit().putString(KEY_MINUS_MODE, m.name).apply()
        NuiToast.show(activity, "负一屏壁纸：${minusModeLabel(m)}", Toast.LENGTH_SHORT)
        onMinusWallpaperChanged?.invoke()
        onShowFloat?.invoke()
    }

    private fun pickMinusImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        runCatching {
            activity.startActivityForResult(Intent.createChooser(intent, "选择负一屏壁纸"), REQ_PICK_MINUS_IMAGE)
        }.onFailure { Log.e(TAG, "pick minus image failed", it) }
    }

    private fun pickMinusVideo() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        runCatching {
            activity.startActivityForResult(Intent.createChooser(intent, "选择负一屏动态壁纸"), REQ_PICK_MINUS_VIDEO)
        }.onFailure { Log.e(TAG, "pick minus video failed", it) }
    }

    /** 处理负一屏选图/选视频结果（MainActivity.onActivityResult 转发）。 */
    fun onMinusActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK_MINUS_IMAGE && requestCode != REQ_PICK_MINUS_VIDEO) return
        onShowFloat?.invoke()
        if (resultCode != Activity.RESULT_OK || data == null) return
        val uri = data.data ?: return
        val targetName = if (requestCode == REQ_PICK_MINUS_IMAGE) MINUS_IMAGE_FILE else MINUS_VIDEO_FILE
        val target = File(activity.filesDir, targetName)
        try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return
            // 切换模式并通知重绘
            val newMode = if (requestCode == REQ_PICK_MINUS_IMAGE) MinusMode.IMAGE else MinusMode.VIDEO
            minusPrefs().edit().putString(KEY_MINUS_MODE, newMode.name).apply()
            NuiToast.show(activity, "负一屏壁纸：${minusModeLabel(newMode)}", Toast.LENGTH_SHORT)
            // 视频分辨率明显超过屏幕（像素总量 > 屏幕 2 倍）时，软解设备可能卡顿，温和提示但不阻止
            if (newMode == MinusMode.VIDEO) warnIfVideoOversized(target)
            onMinusWallpaperChanged?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "copy minus failed", e)
            NuiToast.show(activity, "负一屏壁纸设置失败", Toast.LENGTH_SHORT)
        }
    }
    /** 探测视频分辨率，仅当视频明显超出屏幕（单边 2 倍以上，如 4K 在 1080p 屏）才提示软解可能卡顿。 */
    private fun warnIfVideoOversized(file: File) {
        runCatching {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val vw = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val vh = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            mmr.release()
            val dm = activity.resources.displayMetrics
            // 视频单边超过屏幕对应边 2 倍才可能在软解设备上卡顿；1080p 视频在 720p/1080p 屏均不提示
            if (vw > 0 && vh > 0 && (vw > dm.widthPixels * 2 || vh > dm.heightPixels * 2)) {
                NuiToast.show(
                    activity,
                    "视频分辨率 ${vw}×${vh} 较高，若播放卡顿建议使用不超过 1080p 的视频",
                    Toast.LENGTH_LONG,
                )
            }
        }
    }

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
}
