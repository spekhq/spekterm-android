package com.spekterm.android.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.spekterm.android.app
import com.spekterm.android.log.ConnectionLog
import com.spekterm.android.service.ConnectionService
import com.spekterm.android.service.Notifications
import com.spekterm.android.store.Announcements
import com.spekterm.android.store.Pairing

/** Two screens: pairing when nothing is paired, status otherwise (design D8). */
class MainActivity : ComponentActivity() {
    private var pairing by mutableStateOf<Pairing?>(null)
    private var announcements by mutableStateOf(Announcements())
    /** Pairing again while paired: the current pairing stays until the new one succeeds (`device-pairing`). */
    private var repairing by mutableStateOf(false)
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    val current = pairing
                    if (current == null || repairing) {
                        PairScreen(
                            pairAgain = current == null && announcements.pairAgain,
                            onPaired = { paired -> onPaired(paired) },
                            onCancel = if (current != null) ({ repairing = false }) else null,
                        )
                    } else {
                        StatusScreen(
                            pairing = current,
                            announcements = announcements,
                            onAnnouncements = { change -> announcements = app.announcements.update(change) },
                            onPause = { pause() },
                            onResume = { resume() },
                            onUnpair = { unpair() },
                            onPairAgain = { repairing = true },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        reload()
        // The app opening while paired starts the connection (`desktop-connection`), unless paused.
        if (pairing != null && !announcements.paused) ConnectionService.start(this)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_RESUME) {
            app.announcements.update { it.copy(paused = false, connectionStopped = false) }
            intent.action = null
        }
    }

    private fun reload() {
        pairing = app.credentials.load()
        announcements = app.announcements.load()
    }

    private fun onPaired(paired: Pairing) {
        // Only now is the old connection replaced: a failed re-pairing leaves it running (design D5).
        ConnectionService.stop(this)
        app.credentials.save(paired)
        app.positions.delete()
        announcements = app.announcements.update { Announcements() }
        for (id in listOf(Notifications.ID_PAIR_AGAIN, Notifications.ID_IDENTITY, Notifications.ID_STOPPED)) {
            Notifications.cancel(this, Notifications.TAG_STATUS, id)
        }
        pairing = paired
        repairing = false
        ConnectionService.start(this)
        // Asked when pairing succeeds (`attention-notifications`); denied, the status screen says so.
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun pause() {
        announcements = app.announcements.update { it.copy(paused = true) }
        app.log.write(ConnectionLog.Kind.PAUSED)
        ConnectionService.stop(this)
    }

    private fun resume() {
        announcements = app.announcements.update { it.copy(paused = false, connectionStopped = false) }
        app.log.write(ConnectionLog.Kind.RESUMED)
        ConnectionService.start(this)
    }

    private fun unpair() {
        ConnectionService.stop(this)
        app.credentials.delete()
        app.positions.delete()
        announcements = app.announcements.update { Announcements() }
        app.log.write(ConnectionLog.Kind.UNPAIRED, "by" to "user")
        pairing = null
    }

    companion object {
        const val ACTION_RESUME = "com.spekterm.android.RESUME"
    }
}
