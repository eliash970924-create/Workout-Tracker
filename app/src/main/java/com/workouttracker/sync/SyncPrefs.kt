package com.workouttracker.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How often the background backup runs. */
enum class SyncInterval(val hours: Long, val label: String) {
    HOURLY(1, "Every hour"),
    SIX_HOURLY(6, "Every 6 hours"),
    DAILY(24, "Once a day");

    companion object {
        fun fromHours(hours: Long): SyncInterval =
            entries.firstOrNull { it.hours == hours } ?: SIX_HOURLY
    }
}

data class SyncState(
    val autoSyncEnabled: Boolean = true,
    val interval: SyncInterval = SyncInterval.SIX_HOURLY,
    /** True once the user has granted Drive access at least once. */
    val connected: Boolean = false,
    val lastSyncAt: Long = 0L,
    val lastError: SyncError? = null,
    val syncing: Boolean = false,
)

/**
 * Small SharedPreferences-backed store for sync settings and status. Exposed as
 * a [StateFlow] so both the UI and the worker observe the same values.
 */
class SyncPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        SyncState(
            autoSyncEnabled = prefs.getBoolean(KEY_AUTO, true),
            interval = SyncInterval.fromHours(prefs.getLong(KEY_INTERVAL, 6)),
            connected = prefs.getBoolean(KEY_CONNECTED, false),
            lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0L),
            lastError = prefs.getString(KEY_LAST_ERROR, null)
                ?.let { SyncError(it, prefs.getString(KEY_LAST_ERROR_HINT, null)) },
        )
    )
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Drive file id of the backup, cached so we skip a lookup on every sync. */
    var backupFileId: String?
        get() = prefs.getString(KEY_FILE_ID, null)
        set(value) = prefs.edit().putString(KEY_FILE_ID, value).apply()

    fun setAutoSync(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO, enabled).apply()
        _state.value = _state.value.copy(autoSyncEnabled = enabled)
    }

    fun setInterval(interval: SyncInterval) {
        prefs.edit().putLong(KEY_INTERVAL, interval.hours).apply()
        _state.value = _state.value.copy(interval = interval)
    }

    fun setConnected(connected: Boolean) {
        prefs.edit().putBoolean(KEY_CONNECTED, connected).apply()
        _state.value = _state.value.copy(connected = connected)
    }

    fun setSyncing(syncing: Boolean) {
        _state.value = _state.value.copy(syncing = syncing)
    }

    fun recordSuccess(at: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC, at)
            .remove(KEY_LAST_ERROR).remove(KEY_LAST_ERROR_HINT).apply()
        _state.value = _state.value.copy(lastSyncAt = at, lastError = null, syncing = false)
    }

    fun recordError(error: SyncError) {
        prefs.edit()
            .putString(KEY_LAST_ERROR, error.message)
            .putString(KEY_LAST_ERROR_HINT, error.hint)
            .apply()
        _state.value = _state.value.copy(lastError = error, syncing = false)
    }

    fun clearConnection() {
        prefs.edit().remove(KEY_FILE_ID).putBoolean(KEY_CONNECTED, false).apply()
        _state.value = _state.value.copy(connected = false)
    }

    private companion object {
        const val KEY_AUTO = "auto_sync"
        const val KEY_INTERVAL = "interval_hours"
        const val KEY_CONNECTED = "connected"
        const val KEY_LAST_SYNC = "last_sync_at"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_LAST_ERROR_HINT = "last_error_hint"
        const val KEY_FILE_ID = "backup_file_id"
    }
}
