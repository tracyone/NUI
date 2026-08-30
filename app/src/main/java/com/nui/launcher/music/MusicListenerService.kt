package com.nui.launcher.music

import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi
import androidx.localbroadcastmanager.content.LocalBroadcastManager

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
        val pic = runCatching { notify.extras?.getParcelableCompat<Bitmap>(Notification.EXTRA_PICTURE) }
            .getOrNull()
        val largeIcon = runCatching {
            notify.extras?.getParcelableCompat<Bitmap>(Notification.EXTRA_LARGE_ICON)
        }.getOrNull()
        val cover = pic ?: largeIcon

        // 歌词：优先从 EXTRA_BIG_TEXT（通知大视图已显示的整段文本）里扒 LRC
        val candidate = when {
            !bigText.isNullOrBlank() && LRC_LIKE.containsMatchIn(bigText) -> bigText
            else -> null
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

    companion object {
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
        )
        private inline fun <reified T> Bundle.getParcelableCompat(key: String): T? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                getParcelable(key, T::class.java)
            else
                @Suppress("DEPRECATION") get(key) as? T
    }
}
