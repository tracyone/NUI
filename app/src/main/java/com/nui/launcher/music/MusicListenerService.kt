package com.nui.launcher.music

import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi

/**
 * 空实现的 NotificationListenerService。
 *
 * 仅用于满足 MediaSessionManager.getActiveSessions(componentName) 的权限要求：
 * 调用方必须是已启用的通知监听器。本服务不处理通知，仅作为权限载体。
 *
 * 用户需在系统"通知监听权限"页启用 NUI 后，MusicHost 才能读取其它 App 的媒体会话。
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class MusicListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) { }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { }
}
