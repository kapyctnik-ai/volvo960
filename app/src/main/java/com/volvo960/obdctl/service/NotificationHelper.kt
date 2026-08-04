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
import com.volvo960.obdctl.ui.main.MainActivity

/**
 * Builds the persistent foreground notification (one line per active hold,
 * per-actuator stop actions) and the one-shot watchdog alert fired when a
 * hold auto-stops for lack of response.
 */
class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_ALERTS = "alerts"
        const val FOREGROUND_NOTIFICATION_ID = 1
        private const val WATCHDOG_NOTIFICATION_ID_BASE = 10_000
        const val ACTION_STOP_ONE = "com.volvo960.obdctl.action.STOP_ONE"
        const val ACTION_STOP_ALL = "com.volvo960.obdctl.action.STOP_ALL"
        const val EXTRA_ACTUATOR_ID = "actuator_id"
        private const val MAX_QUICK_ACTIONS = 3
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
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = context.getString(R.string.notif_channel_status_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.notif_channel_alerts_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.notif_channel_alerts_desc) }
        )
    }

    fun buildForegroundNotification(connectionLabel: String, holds: List<HoldStatus>): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)

        if (holds.isEmpty()) {
            builder.setContentTitle(context.getString(R.string.notif_title_connected))
            builder.setContentText(connectionLabel)
        } else {
            builder.setContentTitle(context.getString(R.string.notif_title_holding, holds.size))
            builder.setContentText(holds.joinToString(", ") { it.actuatorName })

            val inbox = NotificationCompat.InboxStyle()
            holds.forEach { inbox.addLine(it.actuatorName) }
            builder.setStyle(inbox)

            holds.take(MAX_QUICK_ACTIONS).forEach { hold ->
                builder.addAction(
                    0,
                    context.getString(R.string.notif_action_stop_one, hold.actuatorName),
                    stopOnePendingIntent(hold.actuatorId)
                )
            }
            builder.addAction(0, context.getString(R.string.notif_action_stop_all), stopAllPendingIntent())
        }
        return builder.build()
    }

    private fun stopOnePendingIntent(actuatorId: Long): PendingIntent {
        val intent = Intent(context, StopActionReceiver::class.java).apply {
            action = ACTION_STOP_ONE
            putExtra(EXTRA_ACTUATOR_ID, actuatorId)
        }
        return PendingIntent.getBroadcast(
            context, actuatorId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopAllPendingIntent(): PendingIntent {
        val intent = Intent(context, StopActionReceiver::class.java).apply { action = ACTION_STOP_ALL }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun postWatchdogAlert(actuatorId: Long, actuatorName: String, reason: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_watchdog_title))
            .setContentText(context.getString(R.string.notif_watchdog_body, actuatorName, reason))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(WATCHDOG_NOTIFICATION_ID_BASE + actuatorId.toInt(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; the in-app state is still authoritative.
        }
    }
}
