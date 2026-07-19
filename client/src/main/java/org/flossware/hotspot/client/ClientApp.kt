package org.flossware.hotspot.client

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import org.flossware.hotspot.client.log.StructuredTree
import timber.log.Timber
import java.util.UUID

class ClientApp : Application() {

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
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "vpn_tunnel"
    }
}
