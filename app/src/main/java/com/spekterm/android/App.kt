package com.spekterm.android

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import com.spekterm.android.desktop.Desktop
import com.spekterm.android.log.ConnectionLog
import com.spekterm.android.service.Notifications
import com.spekterm.android.store.AnnouncementStore
import com.spekterm.android.store.CollapsedStore
import com.spekterm.android.store.CredentialStore
import com.spekterm.android.store.PositionStore

class App : Application() {
    lateinit var credentials: CredentialStore
        private set
    lateinit var positions: PositionStore
        private set
    lateinit var announcements: AnnouncementStore
        private set
    lateinit var collapsed: CollapsedStore
        private set
    lateinit var log: ConnectionLog
        private set

    /** Bumped whenever the pairing or the announcements change, so screens reload what the service changed. */
    val changes = MutableStateFlow(0L)

    override fun onCreate() {
        super.onCreate()
        credentials = CredentialStore(this) { changes.value++ }
        positions = PositionStore(this)
        announcements = AnnouncementStore(this) { changes.value++ }
        collapsed = CollapsedStore(this)
        log = ConnectionLog.forApp(this)
        Desktop.log = { kind, fields -> log.write(kind, *fields) }
        Notifications.createChannels(this)
    }
}

val android.content.Context.app: App get() = applicationContext as App
