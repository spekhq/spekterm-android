package com.spekterm.android.service

import com.spekterm.android.ui.Reasons
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.spekterm.android.R
import com.spekterm.android.ui.MainActivity

/** Channels and the notifications that are not attention events. */
object Notifications {
    const val CHANNEL_CONNECTION = "connection"
    const val CHANNEL_ATTENTION = "attention"
    const val CHANNEL_STATUS = "status"

    const val ID_FOREGROUND = 1
    const val TAG_STATUS = "status"
    const val ID_PAIR_AGAIN = 10
    const val ID_IDENTITY = 11
    const val ID_STOPPED = 12

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_CONNECTION, context.getString(R.string.notification_channel_connection), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.notification_channel_connection_description)
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_ATTENTION, context.getString(R.string.notification_channel_attention), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.notification_channel_attention_description)
                },
                NotificationChannel(CHANNEL_STATUS, context.getString(R.string.notification_channel_status), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.notification_channel_status_description)
                },
            ),
        )
    }

    fun openApp(context: Context, action: String? = null): PendingIntent = PendingIntent.getActivity(
        context,
        action.hashCode(),
        Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Opens [sessionId]'s conversation. The session id is the intent's data, so each session's `PendingIntent` is
     * distinct and `FLAG_UPDATE_CURRENT` never overwrites another session's (design D8).
     */
    fun openSession(context: Context, sessionId: String): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_SESSION)
            .setData(Uri.fromParts(MainActivity.SESSION_SCHEME, sessionId, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun ongoing(context: Context, desktop: String, status: Status): Notification {
        val text = when {
            status.state == Status.State.CONNECTED ->
                status.address?.let { context.getString(R.string.status_connected_via, it) } ?: context.getString(R.string.status_connected)
            status.remoteOff -> context.getString(R.string.status_remote_off_long)
            status.state == Status.State.CONNECTING ->
                status.address?.let { context.getString(R.string.status_connecting_to, it) } ?: context.getString(R.string.status_connecting)
            status.state == Status.State.WAITING ->
                status.report?.takeIf { !status.throttled }?.let { Reasons.short(it).resolve(context) } ?: context.getString(R.string.status_waiting)
            else -> context.getString(R.string.notification_stopped)
        }
        val pause = PendingIntent.getService(
            context,
            0,
            Intent(context, ConnectionService::class.java).setAction(ConnectionService.ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(desktop)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.status_pause), pause)
            .build()
    }

    fun canPost(context: Context): Boolean {
        val permitted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Posts unless the permission is missing; the status screen carries the same state either way. */
    fun post(context: Context, tag: String?, id: Int, notification: Notification) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(context: Context, tag: String?, id: Int) = NotificationManagerCompat.from(context).cancel(tag, id)

    fun status(context: Context, id: Int, title: String, text: String, action: String? = null) = post(
        context,
        TAG_STATUS,
        id,
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            // Status notifications open the connection status, unless they carry their own action (resume).
            .setContentIntent(openApp(context, action ?: MainActivity.ACTION_OPEN_STATUS))
            .build(),
    )
}
