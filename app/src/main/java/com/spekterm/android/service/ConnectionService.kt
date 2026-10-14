package com.spekterm.android.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.spekterm.android.app
import com.spekterm.android.attention.AttentionNotifier
import com.spekterm.android.attention.AttentionPolicy
import com.spekterm.android.log.ConnectionLog.Kind
import com.spekterm.android.net.Attention
import com.spekterm.android.net.CloseInfo
import com.spekterm.android.net.ConnectionClosedException
import com.spekterm.android.net.Frames
import com.spekterm.android.net.RemoteClient
import com.spekterm.android.net.RemoteConnection
import com.spekterm.android.net.RemoteError
import com.spekterm.android.net.WatchAnswer
import com.spekterm.android.store.Pairing
import com.spekterm.android.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Holds the connection to the paired desktop (`desktop-connection`, design D5): a `specialUse` foreground
 * service that owns the only connection, reconnects with backoff, and turns attention events into notifications.
 */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var supervisor: Job? = null
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val backoff = Backoff()
    private val identity = IdentityTracker()
    @Volatile private var current: RemoteConnection? = null
    @Volatile private var connectedNetwork: Network? = null
    @Volatile private var defaultNetwork: Network? = null
    private var desktopName = "spekterm"
    private var registered = false

    private val log get() = app.log

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            pause()
            return START_NOT_STICKY
        }
        val pairing = app.credentials.load()
        if (pairing == null || app.announcements.load().paused) {
            stopSelf()
            return START_NOT_STICKY
        }
        desktopName = pairing.desktopName.ifEmpty { "spekterm" }
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_FOREGROUND,
                Notifications.ongoing(this, desktopName, StatusBoard.status.value),
                // The `specialUse` type exists from Android 14; before it the manifest's type is not enforced.
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: Exception) {
            // A sticky restart after the process was killed has no background-start exemption (design D10).
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                log.write(Kind.RESTART_REFUSED, "error" to e.javaClass.simpleName)
                app.announcements.update { it.copy(connectionStopped = true) }
                Notifications.status(
                    this,
                    Notifications.ID_STOPPED,
                    "Connection to $desktopName stopped",
                    "Android did not let the app reconnect in the background. Tap to resume.",
                    MainActivity.ACTION_RESUME,
                )
                stopSelf()
                return START_NOT_STICKY
            }
            throw e
        }
        if (supervisor?.isActive != true) {
            log.write(Kind.SERVICE_START, "sticky" to (intent == null))
            app.announcements.update { it.copy(connectionStopped = false) }
            Notifications.cancel(this, Notifications.TAG_STATUS, Notifications.ID_STOPPED)
            register()
            supervisor = scope.launch { supervise() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        log.write(Kind.SERVICE_STOP)
        unregister()
        current?.close()
        scope.cancel()
        StatusBoard.update { it.copy(state = Status.State.STOPPED, address = null, since = null, retryAt = null) }
        super.onDestroy()
    }

    private fun pause() {
        app.announcements.update { it.copy(paused = true) }
        log.write(Kind.PAUSED)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- The supervisor ----

    private suspend fun supervise() {
        while (scope.isActive) {
            val pairing = app.credentials.load() ?: return stopSelf()
            var closeInfo: CloseInfo? = null
            var helloError: RemoteError? = null
            var connected = false
            for (address in pairing.addressOrder()) {
                setStatus(Status.State.CONNECTING, address)
                log.write(Kind.ATTEMPT, "address" to address)
                connectedNetwork = defaultNetwork
                when (val result = RemoteClient.open(address, pairing.port, pairing.fingerprint)) {
                    is RemoteClient.OpenResult.PinMismatch -> {
                        identity.record(IdentityTracker.Outcome.MISMATCH)
                        log.write(Kind.PIN_MISMATCH, "address" to address, "check" to result.refusedBy)
                    }
                    is RemoteClient.OpenResult.Unreachable -> {
                        identity.record(IdentityTracker.Outcome.UNREACHABLE)
                        log.write(Kind.UNREACHABLE, "address" to address, "error" to result.cause?.javaClass?.simpleName)
                    }
                    is RemoteClient.OpenResult.Open -> {
                        identity.record(IdentityTracker.Outcome.MATCH)
                        connected = true
                        val outcome = session(result.connection, pairing, address)
                        closeInfo = outcome.first
                        helloError = outcome.second
                        break
                    }
                }
            }
            announceIdentity(identity.endRound())

            if (!connected) {
                waitToRetry(throttled = false)
                continue
            }
            val info = closeInfo
            log.write(
                Kind.DISCONNECTED,
                "code" to info?.code,
                "failure" to info?.failure?.javaClass?.simpleName,
                "error" to (helloError ?: info?.error)?.code,
            )
            StatusBoard.update { it.copy(lastClose = describe(info, helloError ?: info?.error)) }
            when (val decision = ConnectionPolicy.decide(info?.code, helloError ?: info?.error)) {
                ConnectionPolicy.Decision.Unpair -> return unpair()
                is ConnectionPolicy.Decision.Unsupported -> {
                    app.announcements.update { it.copy(unsupported = decision.hint.name) }
                    return stopSelf()
                }
                is ConnectionPolicy.Decision.Retry -> {
                    if (decision.remoteOff) setStatus(Status.State.REMOTE_OFF, null)
                    waitToRetry(decision.throttled)
                }
            }
        }
    }

    /** One authenticated connection, from `hello` to its end. Returns how it ended and `hello`'s error, if any. */
    private suspend fun session(connection: RemoteConnection, pairing: Pairing, address: String): Pair<CloseInfo, RemoteError?> {
        current = connection
        try {
            val hello = connection.call("hello", Frames.hello(pairing.deviceId, pairing.deviceSecret))
            if (!hello.ok) {
                // The desktop closes a refused connection one second after the answer.
                val info = withTimeoutOrNull(3_000) { connection.closed.await() } ?: run {
                    connection.cancel()
                    connection.closed.await()
                }
                return info to hello.error
            }
            log.write(Kind.AUTHENTICATED, "address" to address)
            if (pairing.lastAddress != address) app.credentials.save(pairing.copy(lastAddress = address))

            val watch = connection.call("attention.watch", Frames.attentionWatch(app.positions.load()))
            val notifier = AttentionNotifier(this, app.positions, log)
            if (watch.ok) {
                val answer = watch.result?.let { Frames.decodeOrNull<WatchAnswer>(it) } ?: WatchAnswer(emptyList(), true)
                notifier.apply(AttentionPolicy.onAnswer(answer), answer.events, "answer")
                log.write(Kind.WATCHING, "events" to answer.events.size, "complete" to answer.complete)
            }
            backoff.reset()
            StatusBoard.update {
                it.copy(state = Status.State.CONNECTED, address = address, since = System.currentTimeMillis(), retryAt = null, reconnects = it.reconnects + 1)
            }
            refreshOngoing()
            for (event in connection.events) {
                if (event.kind != "attention") continue
                val attention = Frames.decodeOrNull<Attention>(event.data) ?: continue
                notifier.apply(AttentionPolicy.onLive(attention), listOf(attention), "live")
            }
            return connection.closed.await() to null
        } catch (_: ConnectionClosedException) {
            return connection.closed.await() to null
        } finally {
            current = null
        }
    }

    private suspend fun waitToRetry(throttled: Boolean) {
        val delayMs = backoff.nextDelayMs(throttled)
        StatusBoard.update {
            it.copy(
                state = if (it.state == Status.State.REMOTE_OFF) Status.State.REMOTE_OFF else Status.State.WAITING,
                retryAt = System.currentTimeMillis() + delayMs,
                since = null,
            )
        }
        refreshOngoing()
        log.write(Kind.BACKOFF, "delayMs" to delayMs, "throttled" to throttled)
        // Drain a stale kick, so only a network change from now on cuts the wait short.
        kick.tryReceive()
        if (throttled) delay(delayMs) else withTimeoutOrNull(delayMs) { kick.receive() }
    }

    private fun unpair() {
        log.write(Kind.UNPAIRED)
        app.credentials.delete()
        app.positions.delete()
        app.announcements.update { it.copy(pairAgain = true, identityChanged = false) }
        Notifications.status(this, Notifications.ID_PAIR_AGAIN, "Pair again with $desktopName", "The desktop no longer accepts this phone. Scan a new pairing code.")
        stopSelf()
    }

    private fun announceIdentity(changed: Boolean) {
        val before = app.announcements.load().identityChanged
        if (changed == before) return
        app.announcements.update { it.copy(identityChanged = changed) }
        if (changed) {
            Notifications.status(
                this,
                Notifications.ID_IDENTITY,
                "$desktopName's identity changed",
                "The desktop presents a different certificate than when you paired. If you reset remote access, pair again.",
            )
        } else {
            Notifications.cancel(this, Notifications.TAG_STATUS, Notifications.ID_IDENTITY)
        }
    }

    private fun setStatus(state: Status.State, address: String?) {
        StatusBoard.update { it.copy(state = state, address = address, since = null) }
        refreshOngoing()
    }

    private fun refreshOngoing() = Notifications.post(this, null, Notifications.ID_FOREGROUND, Notifications.ongoing(this, desktopName, StatusBoard.status.value))

    private fun describe(info: CloseInfo?, error: RemoteError?): String =
        listOfNotNull(info?.code?.let { "close $it" }, error?.code, info?.failure?.javaClass?.simpleName).joinToString(", ").ifEmpty { "closed" }

    // ---- Network and power ----

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = defaultNetwork
            defaultNetwork = network
            log.write(Kind.NETWORK, "event" to "available", "changed" to (previous != network))
            val connection = current
            // A connection made on another network dies with it; reconnect now rather than wait for a ping.
            if (connection != null && connectedNetwork != null && connectedNetwork != network) connection.cancel()
            kick.trySend(Unit)
        }

        override fun onLost(network: Network) {
            if (defaultNetwork == network) defaultNetwork = null
            log.write(Kind.NETWORK, "event" to "lost")
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> log.write(Kind.IDLE_MODE)
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> log.write(Kind.POWER_SAVE)
            }
        }
    }

    private fun register() {
        if (registered) return
        registered = true
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        ContextCompat.registerReceiver(
            this,
            powerReceiver,
            IntentFilter().apply {
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unregister() {
        if (!registered) return
        registered = false
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        runCatching { unregisterReceiver(powerReceiver) }
    }

    companion object {
        const val ACTION_PAUSE = "com.spekterm.android.PAUSE"

        /** Starts the service from the foreground (the app, a notification tap) or an exempt broadcast. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
