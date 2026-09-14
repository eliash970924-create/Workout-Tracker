package com.workouttracker.rest

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Rest lengths offered in settings, in seconds. */
val REST_PRESETS = listOf(60, 90, 120, 180)

data class RestSettings(
    val enabled: Boolean = true,
    val seconds: Int = 90,
)

/** SharedPreferences-backed store for the rest timer, shaped like the sync one. */
class RestPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("rest", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        RestSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            seconds = prefs.getInt(KEY_SECONDS, 90),
        )
    )
    val state: StateFlow<RestSettings> = _state.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.value = _state.value.copy(enabled = enabled)
    }

    fun setSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_SECONDS, seconds).apply()
        _state.value = _state.value.copy(seconds = seconds)
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_SECONDS = "seconds"
    }
}
