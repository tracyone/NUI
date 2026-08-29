package com.nui.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * NUI 自定义 Toast：右上角显示，深色半透明背景，避免被高德浮窗遮挡。
 * 替换所有 Toast.makeText().show() 调用。
 */
object NuiToast {
    private val handler = Handler(Looper.getMainLooper())

    fun show(ctx: Context, text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        // 系统 Toast 用 setGravity 改位置（简单有效，不用自定义 window）
        val t = Toast.makeText(ctx, text, duration)
        val dm = ctx.resources.displayMetrics
        // 右上角：gravity=TOP|END + xOffset 负的缩进 + yOffset 下移避开状态栏
        val statusBarHeight = run {
            val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) ctx.resources.getDimensionPixelSize(id) else (24 * dm.density).toInt()
        }
        t.setGravity(Gravity.TOP or Gravity.END, -(16 * dm.density).toInt(), statusBarHeight + (16 * dm.density).toInt())
        // 自定义 view：圆角深色背景
        val tv = t.view?.findViewById<TextView>(android.R.id.message)
        if (tv != null) {
            val pad = (16 * dm.density).toInt()
            tv.setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
            tv.setTextColor(Color.WHITE)
            tv.textSize = 14f
            (tv.parent as? ViewGroup)?.setBackground(
                GradientDrawable().apply {
                    cornerRadius = (12 * dm.density).toFloat()
                    setColor(0xCC000000.toInt()) // 80% 透明黑
                }
            )
        }
        t.show()
    }
}
