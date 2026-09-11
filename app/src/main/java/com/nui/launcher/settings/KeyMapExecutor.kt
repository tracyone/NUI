package com.nui.launcher.settings

import android.content.Context
import com.nui.launcher.UiTheme
import com.nui.launcher.music.MusicHost
import com.nui.launcher.nav.NavHost

/** 全局按键分发：命中映射则执行对应操作 */
object KeyMapExecutor {
    /** @param onSystemDockToggle 切换系统 Dock 显隐后回调（MainActivity 用来重新应用窗口标志/insets） */
    fun handle(
        context: Context,
        keyCode: Int,
        music: MusicHost?,
        nav: NavHost?,
        onSystemDockToggle: (() -> Unit)? = null,
    ): Boolean {
        val rec = KeyMapConfig.load(context).firstOrNull { it.keyCode == keyCode } ?: return false
        when (rec.action) {
            KeyMapConfig.ACTION_MUSIC_NEXT -> music?.next()
            KeyMapConfig.ACTION_MUSIC_PREV -> music?.prev()
            KeyMapConfig.ACTION_NAV_HOME -> nav?.naviHome()
            KeyMapConfig.ACTION_NAV_COMPANY -> nav?.naviCompany()
            KeyMapConfig.ACTION_TOGGLE_DOCK -> {
                UiTheme.setShowSystemDock(context, !UiTheme.showSystemDock(context))
                onSystemDockToggle?.invoke()
            }
        }
        return true
    }
}
