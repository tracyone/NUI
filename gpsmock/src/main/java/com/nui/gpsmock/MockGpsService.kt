package com.nui.gpsmock

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlin.math.cos
import kotlin.math.sin

/**
 * GPS 模拟服务：通过 mock location provider 向系统注入位置，
 * 支持单点注入与沿指定方向匀速移动（模拟车辆行驶）。
 *
 * 控制方式（adb）：
 *   am start-foreground-service -n com.nui.gpsmock/.MockGpsService -a com.nui.gpsmock.SET \
 *       --ef lat 22.517 --ef lon 113.394 --ef heading 45 --ef speed 60
 *   am start-foreground-service -n com.nui.gpsmock/.MockGpsService -a com.nui.gpsmock.MOVE \
 *       --ef lat 22.517 --ef lon 113.394 --ef heading 45 --ef speed 60
 *   am start-foreground-service -n com.nui.gpsmock/.MockGpsService -a com.nui.gpsmock.STOP
 *
 * 首次使用需授权模拟位置：
 *   adb shell appops set com.nui.gpsmock android:mock_location allow
 *   adb shell settings put secure mock_location com.nui.gpsmock
 */
@SuppressLint("MissingPermission")
class MockGpsService : Service() {

    private var lm: LocationManager? = null
    private var thread: Thread? = null
    private var running = false
    private var lat = 22.517
    private var lon = 113.394
    private var heading = 0f
    private var speedKph = 60f
    private var injected = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        ensureProvider()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET -> {
                lat = intent.getDoubleExtra(EXTRA_LAT, lat)
                lon = intent.getDoubleExtra(EXTRA_LON, lon)
                heading = intent.getFloatExtra(EXTRA_HEADING, heading)
                speedKph = intent.getFloatExtra(EXTRA_SPEED, speedKph)
                stopMoving()
                inject(lat, lon, heading, speedKph)
                Log.i(TAG, "SET lat=$lat lon=$lon heading=$heading speed=$speedKph injected=$injected")
            }
            ACTION_MOVE -> {
                lat = intent.getDoubleExtra(EXTRA_LAT, lat)
                lon = intent.getDoubleExtra(EXTRA_LON, lon)
                heading = intent.getFloatExtra(EXTRA_HEADING, heading)
                speedKph = intent.getFloatExtra(EXTRA_SPEED, speedKph)
                startMoving()
                Log.i(TAG, "MOVE start lat=$lat lon=$lon heading=$heading speed=$speedKph")
            }
            ACTION_STOP -> {
                stopMoving()
                Log.i(TAG, "STOP")
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "GPS Mock", NotificationManager.IMPORTANCE_MIN)
            nm.createNotificationChannel(ch)
        }
        val n = if (android.os.Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setContentTitle("GPS Mock").setContentText("模拟定位运行中").setSmallIcon(
            android.R.drawable.ic_menu_mylocation
        ).build()
        startForeground(1, n)
    }

    private fun ensureProvider() {
        if (lm == null) {
            lm = getSystemService(LOCATION_SERVICE) as LocationManager
            try {
                lm?.addTestProvider(
                    LocationManager.GPS_PROVIDER,
                    false, false, false, false,
                    true, true, true,
                    android.location.Criteria.POWER_LOW,
                    android.location.Criteria.ACCURACY_FINE
                )
                lm?.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                Log.i(TAG, "gps test provider added & enabled")
            } catch (e: Exception) {
                // "already exists" 正常；权限类异常记录
                Log.w(TAG, "ensureProvider: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun inject(lat: Double, lon: Double, bearing: Float, speedKph: Float) {
        try {
            ensureProvider()
            val loc = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = lat
                longitude = lon
                accuracy = 5f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                this.bearing = bearing
                speed = speedKph / 3.6f
            }
            lm?.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
            injected++
        } catch (e: Exception) {
            Log.e(TAG, "inject failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun startMoving() {
        stopMoving()
        running = true
        thread = Thread {
            while (running) {
                inject(lat, lon, heading, speedKph)
                // 每 0.5s 沿 heading 方向前进 speed 对应的距离（米）
                val d = speedKph / 3.6 / 2.0
                val rad = Math.toRadians(heading.toDouble())
                val dLat = d * cos(rad) / 111320.0
                val dLon = d * sin(rad) / (111320.0 * cos(Math.toRadians(lat)))
                lat += dLat
                lon += dLon
                Thread.sleep(500)
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun stopMoving() {
        running = false
        thread?.interrupt()
        thread = null
    }

    override fun onDestroy() {
        stopMoving()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NUI.GpsMock"
        private const val CHANNEL_ID = "gpsmock"
        const val ACTION_SET = "com.nui.gpsmock.SET"
        const val ACTION_MOVE = "com.nui.gpsmock.MOVE"
        const val ACTION_STOP = "com.nui.gpsmock.STOP"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_HEADING = "heading"
        const val EXTRA_SPEED = "speed"
    }
}
