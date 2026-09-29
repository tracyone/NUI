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

    /** 负一屏壁纸变更回调（选完图片/视频后通知 MainActivity 重新应用） */
    var onMinusWallpaperChanged: (() -> Unit)? = null

    companion object {
        private const val TAG = "WallpaperController"
        const val REQ_PICK = 0x1011
        /** 负一屏白天壁纸选图（图片/视频均可，按 MIME 自动存为 .jpg/.mp4） */
        const val REQ_PICK_MINUS_DAY = 0x1012
        /** 负一屏晚上壁纸选图（图片/视频均可） */
        const val REQ_PICK_MINUS_NIGHT = 0x1013
        private const val PREFS = "nui_wallpaper"
        private const val MINUS_DAY_BASE = "minus_day"
        private const val MINUS_NIGHT_BASE = "minus_night"
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

    // ==================== 负一屏壁纸 ====================
    // 设计：白天/晚上两个独立槽位，每槽位"跟随桌面"或"选择壁纸"（图片/视频自动识别）。
    // 文件命名：minus_day.jpg / minus_day.mp4、minus_night.jpg / minus_night.mp4
    // 应用时按当前外观深浅取对应槽位的文件；无文件则跟随桌面。

    private fun minusBase(day: Boolean) = if (day) MINUS_DAY_BASE else MINUS_NIGHT_BASE

    /** 查找指定槽位已存在的壁纸文件（先找 .jpg 再找 .mp4），不存在返回 null。 */
    private fun minusFile(day: Boolean): File? {
        val base = minusBase(day)
        val jpg = File(activity.filesDir, "$base.jpg")
        if (jpg.exists()) return jpg
        val mp4 = File(activity.filesDir, "$base.mp4")
        return if (mp4.exists()) mp4 else null
    }

    /** 当前外观应使用的负一屏壁纸文件（null=跟随桌面）。 */
    private fun currentMinusFile(): File? = minusFile(!UiTheme.isDark(activity))

    /** 当前负一屏壁纸是否为视频（文件存在且扩展名为 .mp4）。 */
    private fun isVideoFile(f: File?): Boolean =
        f != null && f.extension.equals("mp4", ignoreCase = true)

    /** 负一屏视频壁纸路径（当前槽位为视频时）。 */
    fun minusWallpaperPath(): String? = currentMinusFile()?.takeIf { isVideoFile(it) }?.absolutePath

    /** 负一屏静态壁纸 Bitmap（当前槽位为图片时，居中裁剪到屏幕尺寸）。 */
    fun minusWallpaperBitmap(): Bitmap? {
        val file = currentMinusFile() ?: return null
        if (isVideoFile(file)) return null
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

    /** 负一屏白天/晚上壁纸是否已自定义（设置页状态显示用）。 */
    fun hasMinusCustom(day: Boolean): Boolean = minusFile(day) != null

    /** 弹负一屏指定槽位的壁纸菜单：跟随桌面 / 选择壁纸。 */
    fun showMinusMenu(day: Boolean) {
        val label = if (day) "白天" else "晚上"
        val has = hasMinusCustom(day)
        val items = if (has) arrayOf("跟随桌面", "选择新壁纸") else arrayOf("跟随桌面", "选择壁纸")
        onHideFloat?.invoke()
        followUpPending = false
        val d = AlertDialog.Builder(activity)
            .setTitle("负一屏${label}壁纸")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> resetMinus(day)
                    1 -> { followUpPending = true; pickMinus(day) }
                }
            }.create()
        d.setOnDismissListener { if (!followUpPending) onShowFloat?.invoke() }
        d.show()
    }

    private fun pickMinus(day: Boolean) {
        val label = if (day) "白天" else "晚上"
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"  // 图片/视频均可，按 MIME 自动识别
        }
        val req = if (day) REQ_PICK_MINUS_DAY else REQ_PICK_MINUS_NIGHT
        runCatching {
            activity.startActivityForResult(Intent.createChooser(intent, "选择负一屏${label}壁纸"), req)
        }.onFailure { Log.e(TAG, "pick minus failed", it) }
    }

    /** 处理负一屏选图结果（MainActivity.onActivityResult 转发）。按 MIME 存为 .jpg/.mp4。 */
    fun onMinusActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val isDay = requestCode == REQ_PICK_MINUS_DAY
        val isNight = requestCode == REQ_PICK_MINUS_NIGHT
        if (!isDay && !isNight) return
        onShowFloat?.invoke()
        if (resultCode != Activity.RESULT_OK || data == null) return
        val uri = data.data ?: return
        val day = isDay
        try {
            // 先清掉该槽位旧文件（无论 .jpg 还是 .mp4）
            File(activity.filesDir, "${minusBase(day)}.jpg").delete()
            File(activity.filesDir, "${minusBase(day)}.mp4").delete()
            val mime = activity.contentResolver.getType(uri) ?: ""
            val ext = if (mime.startsWith("video/")) "mp4" else "jpg"
            val target = File(activity.filesDir, "${minusBase(day)}.$ext")
            activity.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return
            NuiToast.show(activity, "负一屏${if (day) "白天" else "晚上"}壁纸已设置", Toast.LENGTH_SHORT)
            if (ext == "mp4") warnIfVideoOversized(target)
            onMinusWallpaperChanged?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "copy minus failed", e)
            NuiToast.show(activity, "负一屏壁纸设置失败", Toast.LENGTH_SHORT)
        }
    }

    /** 恢复负一屏指定槽位为跟随桌面（删除该槽位文件）。 */
    private fun resetMinus(day: Boolean) {
        File(activity.filesDir, "${minusBase(day)}.jpg").delete()
        File(activity.filesDir, "${minusBase(day)}.mp4").delete()
        NuiToast.show(activity, "负一屏${if (day) "白天" else "晚上"}壁纸：跟随桌面", Toast.LENGTH_SHORT)
        onMinusWallpaperChanged?.invoke()
        onShowFloat?.invoke()
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
        // 旧版负一屏单图/单视频 → 迁移到白天槽位（minus_day.jpg / minus_day.mp4）
        val oldMinusImg = File(activity.filesDir, "minus_image.jpg")
        val minusDayJpg = File(activity.filesDir, "$MINUS_DAY_BASE.jpg")
        if (oldMinusImg.exists() && !minusDayJpg.exists()) {
            runCatching { oldMinusImg.copyTo(minusDayJpg, overwrite = false) }
            oldMinusImg.delete()
        }
        val oldMinusVid = File(activity.filesDir, "minus_video.mp4")
        val minusDayMp4 = File(activity.filesDir, "$MINUS_DAY_BASE.mp4")
        if (oldMinusVid.exists() && !minusDayMp4.exists()) {
            runCatching { oldMinusVid.copyTo(minusDayMp4, overwrite = false) }
            oldMinusVid.delete()
        }
        // 上一版的 minus_image_day/night.jpg 迁移到 minus_day/night.jpg
        mapOf(
            "minus_image_day.jpg" to "$MINUS_DAY_BASE.jpg",
            "minus_image_night.jpg" to "$MINUS_NIGHT_BASE.jpg",
        ).forEach { (old, new) ->
            val o = File(activity.filesDir, old)
            val n = File(activity.filesDir, new)
            if (o.exists() && !n.exists()) {
                runCatching { o.copyTo(n, overwrite = false) }
                o.delete()
            }
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
