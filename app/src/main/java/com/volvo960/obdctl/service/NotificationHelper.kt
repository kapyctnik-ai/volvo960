package com.volvo960.obdctl.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.volvo960.obdctl.R
import com.volvo960.obdctl.ui.dash.DashActivity

/**
 * The persistent notification the connection service runs under, plus the
 * one-shot alert posted when the app gives up on the adapter and shuts down —
 * without it the app would simply vanish with no explanation.
 */
class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_ALERTS = "alerts"
        const val FOREGROUND_NOTIFICATION_ID = 1
        private const val SHUTDOWN_NOTIFICATION_ID = 2
    }

    init {
        createChannels()
    }

    private fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.notif_channel_status_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notif_channel_status_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.notif_channel_alerts_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.notif_channel_alerts_desc) }
        )
    }

    fun buildForegroundNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, DashActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, ObdService::class.java).apply { action = ObdService.ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    fun postShutdownAlert(reason: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_shutdown_title))
            .setContentText(context.getString(R.string.notif_shutdown_body, reason))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(SHUTDOWN_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; nothing else to do.
        }
    }
}
