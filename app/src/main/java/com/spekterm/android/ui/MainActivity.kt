package com.spekterm.android.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.spekterm.android.app
import com.spekterm.android.desktop.Desktop
import com.spekterm.android.log.ConnectionLog
import com.spekterm.android.service.ConnectionService
import com.spekterm.android.service.Notifications
import com.spekterm.android.store.Announcements
import com.spekterm.android.store.Pairing
import com.spekterm.android.store.StoreUnavailableException
import com.spekterm.android.ui.theme.SpektermTheme
import com.spekterm.android.store.ThemeChoice
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Where an intent asks the paired app to go (`session-conversation` design D8). */
private sealed interface Nav {
    data class Conversation(val sessionId: String) : Nav

    data object Sessions : Nav

    data object Status : Nav
}

@Serializable private data object SessionsRoute

@Serializable private data class ConversationRoute(val sessionId: String)

@Serializable private data object StatusRoute

/**
 * Pairing when nothing is paired (outside the navigation graph); otherwise the session list, a session's
 * conversation, and the connection status (`session-conversation` design D8).
 */
class MainActivity : ComponentActivity() {
    private var pairing by mutableStateOf<Pairing?>(null)
    private var announcements by mutableStateOf(Announcements())
    /** Pairing again while paired: the current pairing stays until the new one succeeds (`device-pairing`). */
    private var repairing by mutableStateOf(false)
    /** Set by an intent (a notification tap), consumed by the navigation graph. */
    private var pendingNav by mutableStateOf<Nav?>(null)
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh launch acts on its intent: a task recreated from recents redelivers the original one.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) handle(intent)
        // Reload whenever the service changes the pairing or the announcements (revoked, identity changed…).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { app.changes.collect { reload() } }
        }
        setContent {
            val choice by app.theme.collectAsStateWithLifecycle()
            val dark = when (choice) {
                ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                ThemeChoice.LIGHT -> false
                ThemeChoice.DARK -> true
            }
            // The activity is not recreated on a light/dark switch (configChanges), so the bars follow from here (D2).
            LaunchedEffect(dark) {
                val transparent = Color.Transparent.toArgb()
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(transparent, transparent) { dark },
                    navigationBarStyle = SystemBarStyle.auto(transparent, transparent) { dark },
                )
            }
            SpektermTheme(dark) {
                // Edge to edge: each screen's Scaffold keeps its content clear of the bars and the keyboard (D2).
                Surface(Modifier.fillMaxSize()) {
                    val current = pairing
                    if (current == null || repairing) {
                        PairScreen(
                            pairAgain = current == null && announcements.pairAgain,
                            onPaired = { paired -> onPaired(paired) },
                            onCancel = if (current != null) ({ repairing = false }) else null,
                        )
                    } else {
                        Paired(current)
                    }
                }
            }
        }
    }

    @Composable
    private fun Paired(pairing: Pairing) {
        val nav = rememberNavController()
        val target = pendingNav
        LaunchedEffect(target) {
            when (target) {
                null -> return@LaunchedEffect
                // Over the session list, replacing any conversation already open.
                is Nav.Conversation -> nav.navigate(ConversationRoute(target.sessionId)) { popUpTo<SessionsRoute>() }
                Nav.Sessions -> nav.popBackStack<SessionsRoute>(inclusive = false)
                Nav.Status -> nav.navigate(StatusRoute) {
                    popUpTo<SessionsRoute>()
                    launchSingleTop = true
                }
            }
            pendingNav = null
        }
        val desktopName = pairing.desktopName.ifEmpty { "spekterm" }
        NavHost(nav, startDestination = SessionsRoute) {
            composable<SessionsRoute> {
                SessionsScreen(
                    desktopName = desktopName,
                    deviceId = pairing.deviceId,
                    announcements = announcements,
                    onOpen = { nav.navigate(ConversationRoute(it)) },
                    onStatus = { nav.navigate(StatusRoute) { launchSingleTop = true } },
                    onResume = { resume() },
                )
            }
            composable<ConversationRoute> { entry ->
                ConversationScreen(
                    sessionId = entry.toRoute<ConversationRoute>().sessionId,
                    owner = entry.id,
                    announcements = announcements,
                    onBack = { nav.popBackStack() },
                    onStatus = { nav.navigate(StatusRoute) { launchSingleTop = true } },
                    onResume = { resume() },
                )
            }
            composable<StatusRoute> {
                StatusScreen(
                    pairing = pairing,
                    announcements = announcements,
                    onAnnouncements = { change -> announcements = app.announcements.update(change) },
                    onPause = { pause() },
                    onResume = { resume() },
                    onUnpair = { unpair() },
                    onPairAgain = { repairing = true },
                    onBack = { nav.popBackStack() },
                )
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
        when (intent?.action) {
            ACTION_RESUME -> {
                app.announcements.update { it.copy(paused = false, connectionStopped = false) }
                pendingNav = Nav.Sessions
            }
            ACTION_OPEN_SESSION -> intent.data?.takeIf { it.scheme == SESSION_SCHEME }?.schemeSpecificPart?.let { pendingNav = Nav.Conversation(it) }
            ACTION_OPEN_SESSIONS -> pendingNav = Nav.Sessions
            ACTION_OPEN_STATUS -> pendingNav = Nav.Status
            else -> return
        }
        // Handled once: returning from recents must not navigate again.
        intent.action = null
    }

    private fun reload() {
        // A store that is momentarily unreadable keeps what is shown rather than showing "not paired".
        pairing = try {
            app.credentials.load()
        } catch (_: StoreUnavailableException) {
            pairing
        }
        announcements = app.announcements.load()
    }

    private fun onPaired(paired: Pairing) {
        // Only now is the old connection replaced: a failed re-pairing leaves it running (design D5).
        ConnectionService.stop(this)
        app.credentials.save(paired)
        app.positions.delete()
        app.collapsed.delete()
        Desktop.clear()
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
        app.collapsed.delete()
        Desktop.clear()
        announcements = app.announcements.update { Announcements() }
        app.log.write(ConnectionLog.Kind.UNPAIRED, "by" to "user")
        pairing = null
    }

    companion object {
        const val ACTION_RESUME = "com.spekterm.android.RESUME"
        const val ACTION_OPEN_SESSION = "com.spekterm.android.OPEN_SESSION"
        const val ACTION_OPEN_SESSIONS = "com.spekterm.android.OPEN_SESSIONS"
        const val ACTION_OPEN_STATUS = "com.spekterm.android.OPEN_STATUS"
        const val SESSION_SCHEME = "spekterm-session"
    }
}
