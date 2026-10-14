package com.spekterm.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.spekterm.android.app

/** Starts the connection after a reboot or an app update, unless the user paused it or nothing is paired. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.app
        if (app.credentials.load() == null || app.announcements.load().paused) return
        ConnectionService.start(context)
    }
}
