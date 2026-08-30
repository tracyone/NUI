package com.nui.launcher

import android.content.Intent
import android.graphics.drawable.Drawable

/**
 * 已安装应用的可启动条目模型。
 *
 * @param label     应用显示名称
 * @param packageName 包名
 * @param icon      应用图标
 * @param launchIntent 用于启动该应用的 Intent（已带 FLAG_ACTIVITY_NEW_TASK）
 */
data class AppModel(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val launchIntent: Intent,
    val onClick: (() -> Unit)? = null,
)
