package com.volvo960.obdctl.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.volvo960.obdctl.VolvoApp

/** Handles the per-actuator "stop" and "stop all" actions on the foreground notification. */
class StopActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as VolvoApp
        when (intent.action) {
            NotificationHelper.ACTION_STOP_ONE -> {
                val id = intent.getLongExtra(NotificationHelper.EXTRA_ACTUATOR_ID, -1L)
                if (id != -1L) app.holdManager.stop(id)
            }
            NotificationHelper.ACTION_STOP_ALL -> app.holdManager.stopAll()
        }
    }
}
