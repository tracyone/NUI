package com.nui.launcher.settings

import android.content.Context
import com.nui.launcher.music.MusicHost
import com.nui.launcher.nav.NavHost

/** 全局按键分发：命中映射则执行对应操作 */
object KeyMapExecutor {
    fun handle(
        context: Context,
        keyCode: Int,
        music: MusicHost?,
        nav: NavHost?,
    ): Boolean {
        val rec = KeyMapConfig.load(context).firstOrNull { it.keyCode == keyCode } ?: return false
        when (rec.action) {
            KeyMapConfig.ACTION_MUSIC_NEXT -> music?.next()
            KeyMapConfig.ACTION_MUSIC_PREV -> music?.prev()
            KeyMapConfig.ACTION_NAV_HOME -> nav?.naviHome()
            KeyMapConfig.ACTION_NAV_COMPANY -> nav?.naviCompany()
        }
        return true
    }
}
