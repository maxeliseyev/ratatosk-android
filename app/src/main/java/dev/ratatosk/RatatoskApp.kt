package dev.ratatosk

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.ratatosk.vpn.TunnelController

class RatatoskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                TunnelController.CHANNEL_ID,
                "Tunnel",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        TunnelController.init(this)
    }
}
