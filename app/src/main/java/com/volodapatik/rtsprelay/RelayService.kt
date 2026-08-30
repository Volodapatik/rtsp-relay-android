package com.volodapatik.rtsprelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class RelayService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("relay", "RTSP Relay", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        startForeground(1001, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rtsp = intent?.getStringExtra("rtsp_url") ?: ""
        val server = intent?.getStringExtra("server_url") ?: ""
        // Server relay implementation will be added after the Railway endpoint is selected.
        // Keeping this as a foreground service allows the relay engine to run independently of the UI.
        return START_STICKY
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "relay")
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("RTSP Relay")
            .setContentText("Сервіс трансляції запущено")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
