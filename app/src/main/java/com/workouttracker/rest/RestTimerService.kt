package com.workouttracker.rest

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.workouttracker.WorkoutApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the rest countdown alive and on screen.
 *
 * The countdown itself lives in [RestTimer]; this only exists so Android does
 * not reclaim the process mid-rest, and so the notification has somewhere to be
 * posted from. It watches the timer's state and stops itself the moment the
 * rest ends, which means the timer stays the single source of truth and the
 * service has no lifecycle of its own to get out of step.
 *
 * The type is `specialUse` because a rest can be set to half an hour and
 * `shortService` is capped at three minutes. Nothing else on the list honestly
 * describes a rest timer.
 */
class RestTimerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val timer = (applicationContext as? WorkoutApp)?.restTimer
        val state = timer?.state?.value
        if (timer == null || state == null) {
            // The rest ended between the start request and getting here.
            stop()
            return START_NOT_STICKY
        }

        if (!started) {
            started = true
            // startForegroundService gives us a few seconds to become
            // foreground or the system kills us with an exception.
            promote(state)
            scope.launch {
                timer.state.collect { current ->
                    if (current == null) stop() else update(current)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun promote(state: RestState) {
        try {
            ServiceCompat.startForeground(
                this,
                RestNotifications.RUNNING_ID,
                RestNotifications.running(this, state),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            // Notifications refused, or the system declined the foreground
            // start. The in-app countdown still works, so carry on quietly.
            Log.w(TAG, "Could not show the rest countdown", e)
            stop()
        }
    }

    private fun update(state: RestState) {
        try {
            NotificationManagerCompat.from(this)
                .notify(RestNotifications.RUNNING_ID, RestNotifications.running(this, state))
        } catch (_: SecurityException) {
            // Notification permission was refused; the bar on screen remains.
        }
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "RestTimerService"

        /**
         * Starting a foreground service is only allowed from the foreground, so
         * a refusal is expected rather than exceptional -- the countdown on
         * screen does not depend on this succeeding.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RestTimerService::class.java),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the rest countdown service", e)
            }
        }
    }
}
