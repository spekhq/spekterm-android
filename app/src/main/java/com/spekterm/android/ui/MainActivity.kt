package com.spekterm.android.ui

import com.spekterm.android.R
import android.widget.Toast
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.spekterm.android.desktop.IntakeTap
import kotlinx.serialization.Serializable

/** Where an intent asks the paired app to go (`session-conversation` design D8). */
private sealed interface Nav {
    data class Conversation(val sessionId: String) : Nav

    /** An inbox item's notification: where it opens is decided against the held inbox (`inbox` design D9). */
    data class Intake(val key: String) : Nav

    data object Sessions : Nav

    data object Status : Nav
}

@Serializable private data object SessionsRoute

@Serializable private data class ConversationRoute(val sessionId: String)

@Serializable private data object StatusRoute

@Serializable private data class HandoffRoute(val sessionId: String)

/** The inbox, scrolled to [focus] (an item key) when given (`inbox` design D10). */
@Serializable internal data class InboxRoute(val focus: String? = null)

@Serializable private data object OpenSpecFoldersRoute

@Serializable private data class OpenSpecFolderRoute(val folderId: String)

@Serializable private data class OpenSpecChangeRoute(val folderId: String, val slug: String)

@Serializable private data class OpenSpecSpecRoute(val folderId: String, val topic: String)

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
    /** A pairing link from another app, asked about first (`device-pairing`, `scan-connect-zh` D4). Saved. */
    private var offeredLink by mutableStateOf<String?>(null)
    /** A link confirmed over the paired screens, for the pairing screen to pair with at once. Saved. */
    private var startLink by mutableStateOf<String?>(null)
    /** Whether the pairing screen is pairing: a link arriving meanwhile is dropped (D4). */
    private var pairingBusy = false
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            repairing = it.getBoolean(STATE_REPAIRING)
            offeredLink = it.getString(STATE_OFFERED)
            startLink = it.getString(STATE_START)
        }
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
                            current = current,
                            offered = offeredLink,
                            onOfferDone = { offeredLink = null },
                            start = startLink,
                            onStarted = { startLink = null },
                            onBusy = { pairingBusy = it },
                        )
                    } else {
                        Paired(current)
                        // Over whatever screen is showing, so Cancel returns to it as it was (D4).
                        offeredLink?.let { raw ->
                            PairOfferDialog(
                                raw,
                                current,
                                onPair = {
                                    offeredLink = null
                                    startLink = raw
                                    repairing = true
                                },
                                onCancel = { offeredLink = null },
                            )
                        }
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
                is Nav.Intake -> {
                    // A fresh process holds no inbox yet: wait a little for the desktop's (design D9).
                    withTimeoutOrNull(INTAKE_WAIT_MS) { Desktop.inbox.first { it.current } }
                    when (val route = IntakeTap.route(target.key, Desktop.inbox.value, Desktop.sessions.value)) {
                        is IntakeTap.Route.Inbox -> nav.navigate(InboxRoute(route.focus)) { popUpTo<SessionsRoute>() }
                        is IntakeTap.Route.Conversation -> nav.navigate(ConversationRoute(route.sessionId)) { popUpTo<SessionsRoute>() }
                        IntakeTap.Route.Sessions -> nav.popBackStack<SessionsRoute>(inclusive = false)
                    }
                }
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
                    onOpenSpecFolders = { nav.navigate(OpenSpecFoldersRoute) { launchSingleTop = true } },
                    onOpenSpecFolder = { nav.navigate(OpenSpecFolderRoute(it)) },
                    onHandoff = { openHandoff(nav, it) },
                    onInbox = { nav.openInbox(null) },
                )
            }
            composable<InboxRoute> { entry ->
                val focus by entry.savedStateHandle.getStateFlow("focus", entry.toRoute<InboxRoute>().focus).collectAsStateWithLifecycle()
                InboxScreen(
                    focus = focus,
                    onFocused = { entry.savedStateHandle["focus"] = null },
                    announcements = announcements,
                    onBack = { nav.popBackStack() },
                    onStatus = { nav.navigate(StatusRoute) { launchSingleTop = true } },
                    onResume = { resume() },
                    onOpen = { nav.navigate(ConversationRoute(it)) },
                )
            }
            // A folder's OpenSpec, pushed over the session list (`openspec-browse` design D6).
            composable<OpenSpecFoldersRoute> {
                OpenSpecFoldersScreen(
                    announcements = announcements,
                    onBack = { nav.popBackStack() },
                    onOpen = { nav.navigate(OpenSpecFolderRoute(it)) },
                )
            }
            composable<OpenSpecFolderRoute> { entry ->
                val route = entry.toRoute<OpenSpecFolderRoute>()
                OpenSpecFolderScreen(
                    folderId = route.folderId,
                    owner = entry.id,
                    announcements = announcements,
                    onBack = { nav.popBackStack() },
                    onOpenChange = { nav.navigate(OpenSpecChangeRoute(route.folderId, it)) },
                    onOpenSpec = { nav.navigate(OpenSpecSpecRoute(route.folderId, it)) },
                )
            }
            composable<OpenSpecChangeRoute> { entry ->
                val route = entry.toRoute<OpenSpecChangeRoute>()
                OpenSpecChangeScreen(
                    folderId = route.folderId,
                    slug = route.slug,
                    owner = entry.id,
                    announcements = announcements,
                    onBack = { nav.popBackStack() },
                    // The archived copy replaces the vanished change (design D5).
                    onOpenArchived = { slug ->
                        nav.navigate(OpenSpecChangeRoute(route.folderId, slug)) { popUpTo(entry.destination.id) { inclusive = true } }
                    },
                )
            }
            composable<OpenSpecSpecRoute> { entry ->
                val route = entry.toRoute<OpenSpecSpecRoute>()
                OpenSpecSpecScreen(
                    folderId = route.folderId,
                    topic = route.topic,
                    owner = entry.id,
                    announcements = announcements,
                    onBack = { nav.popBackStack() },
                    onOpenChange = { nav.navigate(OpenSpecChangeRoute(route.folderId, it)) },
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
                    onHandoff = { openHandoff(nav, it) },
                    onOpenSpecFolder = { nav.navigate(OpenSpecFolderRoute(it)) },
                    onOpenInbox = { nav.openInbox(it) },
                )
            }
            // A session's handoff, pushed over where it was opened from (`session-relations` design D4).
            composable<HandoffRoute> { entry ->
                HandoffScreen(
                    sessionId = entry.toRoute<HandoffRoute>().sessionId,
                    announcements = announcements,
                    onBack = { nav.popBackStack() },
                    onStatus = { nav.navigate(StatusRoute) { launchSingleTop = true } },
                    onResume = { resume() },
                    onOpen = { nav.navigate(ConversationRoute(it)) },
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

    /** A session's handoff screen; one already on the back stack is returned to, not stacked again (D4). */
    private fun openHandoff(nav: NavHostController, sessionId: String) {
        if (!nav.popBackStack(HandoffRoute(sessionId), inclusive = false)) nav.navigate(HandoffRoute(sessionId))
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_REPAIRING, repairing)
        outState.putString(STATE_OFFERED, offeredLink)
        outState.putString(STATE_START, startLink)
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data
                if (data?.scheme == PAIR_SCHEME && data.host == PAIR_HOST) offer(intent.dataString.orEmpty()) else return
            }
            ACTION_RESUME -> {
                app.announcements.update { it.copy(paused = false, connectionStopped = false) }
                pendingNav = Nav.Sessions
            }
            ACTION_OPEN_SESSION -> intent.data?.takeIf { it.scheme == SESSION_SCHEME }?.schemeSpecificPart?.let { pendingNav = Nav.Conversation(it) }
            ACTION_OPEN_INTAKE -> intent.data?.takeIf { it.scheme == INTAKE_SCHEME }?.schemeSpecificPart?.let { pendingNav = Nav.Intake(it) }
            ACTION_OPEN_SESSIONS -> pendingNav = Nav.Sessions
            ACTION_OPEN_STATUS -> pendingNav = Nav.Status
            else -> return
        }
        // Handled once: returning from recents must not navigate again.
        intent.action = null
    }

    /** A pairing link from another app: asked about, unless a pairing is already under way (D4). */
    private fun offer(raw: String) {
        if (pairingBusy || startLink != null) {
            Toast.makeText(this, R.string.pair_offer_in_progress, Toast.LENGTH_LONG).show()
            return
        }
        offeredLink = raw
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
        const val ACTION_OPEN_INTAKE = "com.spekterm.android.OPEN_INTAKE"
        const val INTAKE_SCHEME = "spekterm-intake"
        /** How long a notification's tap waits for the desktop's inbox (`inbox` design D9). */
        private const val INTAKE_WAIT_MS = 5_000L
        private const val PAIR_SCHEME = "spekterm"
        private const val PAIR_HOST = "pair"
        private const val STATE_REPAIRING = "repairing"
        private const val STATE_OFFERED = "offeredLink"
        private const val STATE_START = "startLink"
    }
}

/**
 * The inbox at [focus]; one already on the back stack is returned to and handed the item through its saved state, since a
 * typed route's single-top would not deliver a new argument (`inbox` design D10).
 */
internal fun NavHostController.openInbox(focus: String?) {
    if (popBackStack<InboxRoute>(inclusive = false)) {
        if (focus != null) currentBackStackEntry?.savedStateHandle?.set("focus", focus)
    } else {
        navigate(InboxRoute(focus))
    }
}
