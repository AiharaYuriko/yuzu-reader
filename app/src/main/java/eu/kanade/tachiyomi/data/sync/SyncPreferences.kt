package eu.kanade.tachiyomi.data.sync

import dev.zacsweers.metro.Inject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
class SyncPreferences(store: PreferenceStore) {
    val credentials = store.getString(Preference.appStateKey("syncyomi_credentials"))
    val driveCredentials = store.getString(Preference.appStateKey("yuzu_drive_account"))
    private val useGoogleDrive = store.getBoolean(Preference.appStateKey("yuzu_use_google_drive"))
    val backend = store.getString(Preference.appStateKey("yuzu_sync_backend"))
    val oneDriveCredentials = store.getString(Preference.appStateKey("yuzu_onedrive_account"))
    val deviceId = store.getString(Preference.appStateKey("syncyomi_device_id"))
    val status = store.getString(Preference.appStateKey("syncyomi_status"))
    val onOpen = store.getBoolean("syncyomi_on_open", true)
    val onRead = store.getBoolean("syncyomi_on_read", true)

    fun account(): SyncAccount? {
        val (url, key) = credentials.get().split('\n', limit = 2).takeIf { it.size == 2 } ?: return null
        return SyncAccount.parse(url, key)
    }

    fun driveAccount(): DriveAccount? {
        val (email, id) = driveCredentials.get().split('\n', limit = 2).takeIf { it.size == 2 } ?: return null
        if (email.isBlank() || id.isBlank()) return null
        return DriveAccount(email, id)
    }

    fun oneDriveAccount(): OneDriveAccount? {
        val (clientId, id, name) = oneDriveCredentials.get().split('\n', limit = 3)
            .takeIf { it.size == 3 } ?: return null
        return OneDriveAccount(clientId, id, name)
    }

    fun selectedBackend(value: String = backend.get()): SyncBackend =
        SyncBackend.entries.find { it.key == value }
            ?: if (useGoogleDrive.get()) SyncBackend.GoogleDrive else SyncBackend.SyncYomi

    internal fun target(): SyncTarget? = when (selectedBackend()) {
        SyncBackend.SyncYomi -> account()
        SyncBackend.GoogleDrive -> driveAccount()
        SyncBackend.OneDrive -> oneDriveAccount()
    }
}

enum class SyncBackend(val key: String, val label: String) {
    SyncYomi("syncyomi", "SyncYomi"),
    GoogleDrive("drive", "Google Drive"),
    OneDrive("onedrive", "OneDrive"),
}

sealed interface SyncTarget

sealed interface CloudSyncTarget : SyncTarget

data class DriveAccount(val email: String, val id: String) : CloudSyncTarget

data class OneDriveAccount(val clientId: String, val id: String, val name: String) : CloudSyncTarget

data class SyncAccount(val url: HttpUrl, val key: String) : SyncTarget {
    companion object {
        fun parse(url: String, key: String): SyncAccount? {
            val host = url.trim().toHttpUrlOrNull() ?: return null
            val cleanKey = key.trim()
            if (host.username.isNotEmpty() || host.password.isNotEmpty() ||
                host.query != null || host.fragment != null ||
                cleanKey.isEmpty() || cleanKey.any { it.code !in 33..126 }
            ) {
                return null
            }
            return SyncAccount(host, cleanKey)
        }
    }
}
