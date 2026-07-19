package org.flossware.hotspot

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import org.flossware.hotspot.log.StructuredTree
import org.flossware.hotspot.service.HotspotService
import timber.log.Timber
import java.util.UUID

class HotspotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initTimber()
        createNotificationChannel()
    }

    private fun initTimber() {
        val sessionId = UUID.randomUUID().toString().take(8)
        Timber.plant(StructuredTree(sessionId))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            HotspotService.CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
