package com.nui.launcher.music

import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.nui.launcher.BuildConfig

/**
 * 通知监听器：两个作用
 * 1. 满足 MediaSessionManager.getActiveSessions(componentName) 的权限要求（必须启用 NUI 通知监听）
 * 2. 解析酷我车机版等不通过 MediaSession 发送歌词/封面的 App，把通知里的 extras
 *    （android.title / android.text / android.bigPicture / android.mediaSession）
 *    通过本地广播回传给 MusicHost，作为 MediaSession 空数据时的兜底。
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class MusicListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        val notify = sbn.notification ?: return

        // 只处理已知音乐包名或带有 MediaSession token 的通知（即系统媒体 Style）
        val notMediaStyle = notify.extras?.run {
            getParcelableCompat<Any>("android.mediaSession") == null &&
            !containsKey("android.template") &&
            notify.actions == null
        } != false
        if (pkg !in KNOWN_MUSIC_PKGS && notMediaStyle) return

        val extras: Bundle = notify.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE)?.toString()?.trim()
            ?: extras.getCharSequence("android.title")?.toString()?.trim()
        val artist = extras.getString(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence("android.text")?.toString()?.trim()
        val bigText = extras.getCharSequence("android.bigText")?.toString()
        // 封面：API 23+ 通知 largeIcon 存的是 Icon 对象，需 loadDrawable 转 Bitmap；
        // 旧版或 fallback 从 extras 取 Bitmap，最后再试 bigPicture
        val cover = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // 通知 largeIcon 存的是 Icon 对象（getLargeIcon() 在某些情况下返回 null，直接从 extras 取更可靠）
                val icon = notify.extras?.get(Notification.EXTRA_LARGE_ICON) as? Icon
                icon?.loadDrawable(this)?.toBitmap()
            } else null
        }.getOrNull() ?: runCatching {
            notify.extras?.getParcelableCompat<Bitmap>(Notification.EXTRA_LARGE_ICON)
        }.getOrNull() ?: runCatching {
            notify.extras?.getParcelableCompat<Bitmap>(Notification.EXTRA_PICTURE)
        }.getOrNull()

        // 歌词来源优先级：
        // 1) 通知 extras 里任何一段带时间戳的文本（不少车机版把歌词放自定义 key / bigText）
        // 2) 通知 RemoteViews 的歌词控件文本（酷我车机版桌面歌词就在里面，每次更新只给当前行）
        val candidate = findLrcInExtras(extras)
            ?: bigText?.takeIf { LRC_LIKE.containsMatchIn(it) }
            ?: remoteLyric(notify)
        if (candidate != null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "通知取到歌词 ${candidate.length} 字（$pkg）")
        } else if (dumpedPkgs.add(pkg)) {
            // 每个包只打一次：拿到通知却找不到歌词时，把 extras 的 key 列出来，
            // 便于确认真实设备上 App 到底把歌词放在哪个字段（不刷屏）
            Log.d(TAG, "通知无歌词 pkg=$pkg keys=${extras.keySet().joinToString()}")
        }

        val i = Intent(ACTION_NOTIFY).apply {
            setPackage(packageName)
            putExtra(EXTRA_PKG, pkg)
            title?.let { putExtra(EXTRA_TITLE, it) }
            artist?.let { putExtra(EXTRA_ARTIST, it) }
            cover?.let { putExtra(EXTRA_COVER, it) }
            candidate?.let { putExtra(EXTRA_LYRIC, it) }
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) { }

    /** 在 extras 里扫一遍，找带 LRC 时间戳的文本（App 可能放在自定义 key 里） */
    private fun findLrcInExtras(extras: Bundle): String? {
        for (key in extras.keySet()) {
            val v = extras.get(key) ?: continue
            val text = when (v) {
                is CharSequence -> v.toString()
                is Array<*> -> v.joinToString("\n") { it?.toString() ?: "" }
                else -> null
            } ?: continue
            if (text.length >= 40 && LRC_LIKE.containsMatchIn(text)) return text
        }
        return null
    }

    /** 解析通知的 RemoteViews（contentView / bigContentView），把里面 TextView 的文本收出来。
     *  同包 2 秒内只解析一次：通知可能每秒刷新，反复 inflate 会白烧 CPU。 */
    private fun remoteLyric(notify: Notification): String? {
        val pkg = notify.toString()
        val now = SystemClock.elapsedRealtime()
        if (now - lastRemoteParseAt < REMOTE_PARSE_INTERVAL_MS) return null
        lastRemoteParseAt = now
        val rvs = mutableListOf<RemoteViews>()
        notify.contentView?.let { rvs.add(it) }
        runCatching {
            // bigContentView 是 @hide 字段，公开 API 拿不到
            Notification::class.java.getField("bigContentView").get(notify)?.let {
                (it as? RemoteViews)?.let { rv -> rvs.add(rv) }
            }
        }
        val texts = mutableListOf<String>()
        for (rv in rvs) {
            runCatching {
                val root = rv.apply(this, FrameLayout(this))
                collectTexts(root, texts)
            }
        }
        val joined = texts.joinToString("\n").trim()
        return if (joined.length >= 20 && LRC_LIKE.containsMatchIn(joined)) joined else null
    }

    private fun collectTexts(v: View, out: MutableList<String>) {
        if (v is TextView) {
            v.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) collectTexts(v.getChildAt(i), out)
    }

    companion object {
        private const val TAG = "NUI.MusicListener"
        private const val REMOTE_PARSE_INTERVAL_MS = 2000L

        @Volatile
        private var lastRemoteParseAt = 0L

        /** 已经打过"通知无歌词"诊断日志的包名（每包只打一次） */
        private val dumpedPkgs = mutableSetOf<String>()

        const val ACTION_NOTIFY = "nui.music.NOTIFICATION_UPDATE"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_COVER = "cover"
        const val EXTRA_LYRIC = "lyric"
        private val LRC_LIKE = Regex("""\[\d{1,2}:\d{2}""")
        // 已知会塞歌词到通知 bigText / 不用 MediaSession 发歌词的 App
        val KNOWN_MUSIC_PKGS = setOf(
            "cn.kuwo.kwmusiccar", "cn.kuwo.player",
            "com.kugou.android", "com.kugou.android.car",
            "com.netease.cloudmusic", "com.netease.cloudmusic.car",
            "com.tencent.qqmusic", "com.tencent.qqmusiccar",
            "com.android.mediacenter",
            "com.luna.music", "com.luna.music.car",
        )
        private inline fun <reified T> Bundle.getParcelableCompat(key: String): T? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                getParcelable(key, T::class.java)
            else
                @Suppress("DEPRECATION") get(key) as? T
    }
}

/** Drawable 转 Bitmap（用于通知 Icon.loadDrawable 后的封面提取） */
private fun Drawable.toBitmap(): Bitmap {
    if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }
    val bmp = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp
}
