package com.spekterm.android

import android.app.Application
import android.app.UiModeManager
import android.os.Build
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import com.spekterm.android.attach.Attach
import com.spekterm.android.desktop.Desktop
import com.spekterm.android.log.ConnectionLog
import com.spekterm.android.service.Notifications
import com.spekterm.android.store.AnnouncementStore
import com.spekterm.android.store.CollapsedStore
import com.spekterm.android.store.CredentialStore
import com.spekterm.android.store.PositionStore
import com.spekterm.android.store.ThemeChoice
import com.spekterm.android.store.ThemeStore
import com.spekterm.android.ui.SentFileCache

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
    private lateinit var themeStore: ThemeStore
    private val themeChoice = MutableStateFlow(ThemeChoice.SYSTEM)

    /** Light or dark as chosen in settings (`ui-polish` D13). */
    val theme: StateFlow<ThemeChoice> = themeChoice

    /** Bumped whenever the pairing or the announcements change, so screens reload what the service changed. */
    val changes = MutableStateFlow(0L)

    override fun onCreate() {
        super.onCreate()
        credentials = CredentialStore(this) { changes.value++ }
        positions = PositionStore(this)
        announcements = AnnouncementStore(this) { changes.value++ }
        collapsed = CollapsedStore(this)
        themeStore = ThemeStore(this)
        themeChoice.value = themeStore.load()
        // Every start, so the system's copy follows ours even if they parted (D13).
        applyNightMode(themeChoice.value)
        log = ConnectionLog.forApp(this)
        Desktop.log = { kind, fields -> log.write(kind, *fields) }
        Notifications.createChannels(this)
        Attach.clearCache(this)
        SentFileCache.clear(this)
    }

    /** The channels' names follow a change of language (`app-language`, `scan-connect-zh` D8). */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Notifications.createChannels(this)
    }

    fun chooseTheme(choice: ThemeChoice) {
        themeStore.save(choice)
        themeChoice.value = choice
        applyNightMode(choice)
    }

    /**
     * Tells the system, on Android 12+, so the starting window and `values-night` follow the choice too; it changes
     * the configuration in place (`configChanges` has `uiMode`). `AUTO` means no override: the phone's setting (D13).
     */
    private fun applyNightMode(choice: ThemeChoice) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
            when (choice) {
                ThemeChoice.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                ThemeChoice.LIGHT -> UiModeManager.MODE_NIGHT_NO
                ThemeChoice.DARK -> UiModeManager.MODE_NIGHT_YES
            },
        )
    }
}

val android.content.Context.app: App get() = applicationContext as App
