package com.workouttracker.rest

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * What a rest leads up to, when it is not simply another set of the same
 * exercise: said in the notification that ends it, and opened by tapping that.
 */
data class RestNext(
    val message: String,
    /** The exercise to open, or null for the session as a whole. */
    val exercise: String?,
)

/** A rest in progress. Null state means nothing is running. */
data class RestState(
    val remainingSeconds: Int,
    val totalSeconds: Int,
    /** Wall-clock finish time, which is what the notification counts down to. */
    val endsAtMillis: Long,
    /** The exercise being rested from, for the notification. */
    val label: String? = null,
    /**
     * The session it was started in, so tapping the notification can go back
     * to the exercise rather than dropping you on the home screen.
     */
    val workoutId: String? = null,
    /** Null means another set of [label] is next, which is the usual case. */
    val next: RestNext? = null,
)

/**
 * The rest countdown between sets.
 *
 * It lives in the application container so it keeps running while you move
 * between screens, and it counts against [SystemClock.elapsedRealtime] rather
 * than tick counts, so a paused screen does not slow it down.
 *
 * While a rest is running, [RestTimerService] keeps the process alive and shows
 * the countdown in the notification shade. The timer stays the single source of
 * truth: the service only watches this state and stops itself when it clears.
 */
class RestTimer(
    private val context: Context,
    private val prefs: RestPrefs,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {

    private val _state = MutableStateFlow<RestState?>(null)

    /** The running rest, or null when there is none. */
    val state: StateFlow<RestState?> = _state.asStateFlow()

    private var countdown: Job? = null

    /**
     * Called after a set is ticked off. Does nothing unless the user turned the
     * timer on; [seconds] is the exercise's own rest length, or null to use the
     * default from Settings.
     */
    fun startIfEnabled(
        seconds: Int? = null,
        label: String? = null,
        workoutId: String? = null,
        next: RestNext? = null,
    ) {
        val settings = prefs.state.value
        if (settings.enabled) start(seconds ?: settings.seconds, label, workoutId, next)
    }

    /**
     * Starts a rest described entirely by its arguments. Nothing is carried
     * over from a rest already running: a null [next] means "another set of
     * the same", and inheriting the last rest's "time for bench press" would
     * turn that into a lie. Adjusting a running rest goes through [adjust],
     * which passes everything along explicitly.
     */
    fun start(
        seconds: Int,
        label: String? = null,
        workoutId: String? = null,
        next: RestNext? = null,
    ) {
        // A rest already running means the service is already up. Asking again
        // from a notification action would be a foreground start from the
        // background, which the system is entitled to refuse.
        val alreadyRunning = _state.value != null

        countdown?.cancel()
        val total = seconds.coerceIn(MIN_REST_SECONDS, MAX_REST_SECONDS)
        val endsAtMillis = System.currentTimeMillis() + total * 1000L
        _state.value = RestState(total, total, endsAtMillis, label, workoutId, next)

        if (!alreadyRunning) RestTimerService.start(context)

        countdown = scope.launch {
            val endsAt = SystemClock.elapsedRealtime() + total * 1000L
            while (true) {
                val millisLeft = endsAt - SystemClock.elapsedRealtime()
                if (millisLeft <= 0L) break
                // Round up, so a timer started at 90 reads "1:30" and not "1:29".
                _state.value = RestState(
                    remainingSeconds = ceil(millisLeft / 1000.0).toInt(),
                    totalSeconds = total,
                    endsAtMillis = endsAtMillis,
                    label = label,
                    workoutId = workoutId,
                    next = next,
                )
                delay(TICK_MILLIS)
            }
            _state.value = null
            announce(label, workoutId, next)
        }
    }

    /** Lengthens or shortens the running rest. No-op when nothing is running. */
    fun adjust(deltaSeconds: Int) {
        val current = _state.value ?: return
        // Still the same rest, from the same set, leading to the same thing.
        start(
            seconds = current.remainingSeconds + deltaSeconds,
            label = current.label,
            workoutId = current.workoutId,
            next = current.next,
        )
    }

    fun stop() {
        countdown?.cancel()
        countdown = null
        _state.value = null
    }

    private fun announce(label: String?, workoutId: String?, next: RestNext?) {
        vibrate()
        RestNotifications.postDone(context, label, workoutId, next)
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        } ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(400L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private companion object {
        const val TICK_MILLIS = 200L
    }
}
