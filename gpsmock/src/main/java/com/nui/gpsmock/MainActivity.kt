package com.nui.gpsmock

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** 极简控制页：申请定位权限 + 显示控制命令说明 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            textSize = 14f
            setPadding(32, 64, 32, 0)
        }
        setContentView(tv)

        // 定位权限（mock 注入需要）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1
            )
        }

        tv.text = """
            GPS Mock v1.0

            控制命令（adb）：
            SET  : am startservice -n com.nui.gpsmock/.MockGpsService -a com.nui.gpsmock.SET --ef lat 22.517 --ef lon 113.394 --ef heading 45 --ef speed 60
            MOVE : am startservice -n com.nui.gpsmock/.MockGpsService -a com.nui.gpsmock.MOVE --ef lat 22.517 --ef lon 113.394 --ef heading 45 --ef speed 60
            STOP : am startservice -n com.nui.gpsmock/.MockGpsService -a com.nui.gpsmock.STOP

            首次授权：
            adb shell appops set $packageName android:mock_location allow
            也可在开发者选项-选择模拟位置信息应用 中选本应用
        """.trimIndent()
    }
}
