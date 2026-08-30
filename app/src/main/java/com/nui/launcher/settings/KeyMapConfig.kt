package com.nui.launcher.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 方向盘按键映射：keycode -> 操作，持久化在 prefs */
data class KeyMapRecord(val keyCode: Int, val action: String)

object KeyMapConfig {
    private const val PREFS = "nui_keymap"
    private const val KEY = "records"

    const val ACTION_MUSIC_NEXT = "music_next"
    const val ACTION_MUSIC_PREV = "music_prev"
    const val ACTION_NAV_HOME = "nav_home"
    const val ACTION_NAV_COMPANY = "nav_company"

    val ACTIONS = linkedMapOf(
        ACTION_MUSIC_NEXT to "音乐·下一首",
        ACTION_MUSIC_PREV to "音乐·上一首",
        ACTION_NAV_HOME to "导航·回家",
        ACTION_NAV_COMPANY to "导航·去公司",
    )

    fun actionName(action: String): String = ACTIONS[action] ?: action

    fun load(ctx: Context): List<KeyMapRecord> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                KeyMapRecord(o.getInt("key"), o.getString("action"))
            }
        }.getOrDefault(emptyList())
    }

    private fun save(ctx: Context, records: List<KeyMapRecord>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(JSONObject().put("key", r.keyCode).put("action", r.action))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, rec: KeyMapRecord) {
        save(ctx, load(ctx).filter { it.keyCode != rec.keyCode } + rec)
    }

    fun update(ctx: Context, rec: KeyMapRecord) = add(ctx, rec)

    fun remove(ctx: Context, rec: KeyMapRecord) {
        save(ctx, load(ctx).filter { it != rec })
    }
}
