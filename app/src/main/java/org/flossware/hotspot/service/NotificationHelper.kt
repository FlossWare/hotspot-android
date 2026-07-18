package org.flossware.hotspot.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.flossware.hotspot.MainActivity
import org.flossware.hotspot.R

/**
 * Builds and updates the foreground-service notification.
 *
 * Extracted from [HotspotService] so notification layout can be tested
 * and evolved independently.
 */
class NotificationHelper(private val context: Context) {

    /** Creates a notification showing the current [deviceCount]. */
    fun build(deviceCount: Int): Notification {
        val stopIntent = Intent(context, HotspotService::class.java).apply {
            action = HotspotService.ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            context, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = Intent(context, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, HotspotService.CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_text, deviceCount))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.notification_action_stop),
                stopPending,
            )
            .setOngoing(true)
            .build()
    }

    /** Posts an updated notification with the given [deviceCount]. */
    fun update(deviceCount: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(HotspotService.NOTIFICATION_ID, build(deviceCount))
    }
}
