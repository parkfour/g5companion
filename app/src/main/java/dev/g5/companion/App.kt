package dev.g5.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(GlassesService.CHANNEL, "Glasses connection", NotificationManager.IMPORTANCE_LOW),
        )
        Glasses.init(this)
        Assistant.init(this)
    }
}
