package com.spekterm.android

import android.app.Application
import com.spekterm.android.log.ConnectionLog
import com.spekterm.android.service.Notifications
import com.spekterm.android.store.AnnouncementStore
import com.spekterm.android.store.CredentialStore
import com.spekterm.android.store.PositionStore

class App : Application() {
    lateinit var credentials: CredentialStore
        private set
    lateinit var positions: PositionStore
        private set
    lateinit var announcements: AnnouncementStore
        private set
    lateinit var log: ConnectionLog
        private set

    override fun onCreate() {
        super.onCreate()
        credentials = CredentialStore(this)
        positions = PositionStore(this)
        announcements = AnnouncementStore(this)
        log = ConnectionLog.forApp(this)
        Notifications.createChannels(this)
    }
}

val android.content.Context.app: App get() = applicationContext as App
