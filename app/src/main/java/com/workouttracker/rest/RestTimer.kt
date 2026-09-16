package com.workouttracker.rest

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.workouttracker.MainActivity
import com.workouttracker.R
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
 * The rest countdown between sets.
 *
 * It lives in the application container so it keeps running while you move
 * between screens, and it counts against [SystemClock.elapsedRealtime] rather
 * than tick counts, so a paused screen does not slow it down. It is not an
 * alarm, though: if Android kills the process mid-rest the countdown goes with
 * it. That is the trade for needing no exact-alarm permission, and a rest is
 * short enough that it rarely matters.
 */
class RestTimer(
    private val context: Context,
    private val prefs: RestPrefs,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {

    private val _remaining = MutableStateFlow<Int?>(null)

    /** Seconds left, or null when no rest is running. */
    val remaining: StateFlow<Int?> = _remaining.asStateFlow()

    private var countdown: Job? = null

    /**
     * Called after a set is ticked off. Does nothing unless the user turned the
     * timer on; [seconds] is the exercise's own rest length, or null to use the
     * default from Settings.
     */
    fun startIfEnabled(seconds: Int? = null) {
        val settings = prefs.state.value
        if (settings.enabled) start(seconds ?: settings.seconds)
    }

    fun start(seconds: Int) {
        countdown?.cancel()
        val total = seconds.coerceIn(MIN_REST_SECONDS, MAX_REST_SECONDS)
        _remaining.value = total
        countdown = scope.launch {
            val endsAt = SystemClock.elapsedRealtime() + total * 1000L
            while (true) {
                val millisLeft = endsAt - SystemClock.elapsedRealtime()
                if (millisLeft <= 0L) break
                // Round up, so a timer started at 90 reads "1:30" and not "1:29".
                _remaining.value = ceil(millisLeft / 1000.0).toInt()
                delay(TICK_MILLIS)
            }
            _remaining.value = null
            announce()
        }
    }

    /** Lengthens or shortens the running rest. No-op when nothing is running. */
    fun adjust(deltaSeconds: Int) {
        val left = _remaining.value ?: return
        start(left + deltaSeconds)
    }

    fun stop() {
        countdown?.cancel()
        countdown = null
        _remaining.value = null
    }

    private fun announce() {
        vibrate()
        postNotification()
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        } ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(400L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun postNotification() {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Rest timer")
                .setDescription("Tells you when a rest between sets is up.")
                .build()
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Rest over")
            .setContentText("Time for your next set.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notification permission was refused. The buzz, and the countdown
            // on screen, are still the signal -- this is not worth crashing for.
        }
    }

    private companion object {
        const val CHANNEL_ID = "rest_timer"
        const val NOTIFICATION_ID = 1
        const val TICK_MILLIS = 200L
    }
}
