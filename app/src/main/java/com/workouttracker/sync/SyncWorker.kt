package com.workouttracker.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.workouttracker.WorkoutApp
import java.util.concurrent.TimeUnit

/** Runs one sync round in the background. */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as WorkoutApp
        if (!app.syncPrefs.state.value.autoSyncEnabled) return Result.success()

        return when (val outcome = app.syncManager.sync()) {
            is SyncManager.Outcome.Success -> Result.success()
            // Nothing to retry until the user grants access from Settings.
            SyncManager.Outcome.NotConnected -> Result.success()
            is SyncManager.Outcome.Failed ->
                if (outcome.retryable) Result.retry() else Result.failure()
        }
    }
}

/**
 * Auto-sync scheduling. Two triggers keep Drive close to the device without
 * draining the battery:
 *
 *  - a periodic job at the user's chosen interval, and
 *  - a debounced one-shot after any edit, so a finished workout reaches Drive
 *    in minutes rather than hours.
 *
 * Both require a network connection -- Wi-Fi, if the user asked for that --
 * and WorkManager holds them until there is one. "Sync now" in Settings is not
 * scheduled work and runs on whatever connection there is: an explicit tap is
 * the user choosing to spend the data.
 */
object SyncScheduler {

    private const val PERIODIC_WORK = "workout-sync-periodic"
    private const val ON_CHANGE_WORK = "workout-sync-on-change"

    /** Edits are batched for this long so a whole session is one upload. */
    private const val DEBOUNCE_MINUTES = 5L

    private fun constraints(state: SyncState) = Constraints.Builder()
        .setRequiredNetworkType(
            // UNMETERED rather than "Wi-Fi": what matters is not paying for the
            // data, and an unlimited connection is fine whatever carries it.
            if (state.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        )
        .build()

    /** Applies the current settings; call at startup and whenever they change. */
    fun applySettings(context: Context, state: SyncState) {
        val workManager = WorkManager.getInstance(context)
        if (!state.autoSyncEnabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK)
            workManager.cancelUniqueWork(ON_CHANGE_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(state.interval.hours, TimeUnit.HOURS)
            .setConstraints(constraints(state))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            // UPDATE keeps the existing schedule when nothing changed, so
            // toggling settings doesn't reset the interval countdown -- and it
            // does carry a changed network constraint over to the job.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Called after every local edit; REPLACE collapses a burst into one run. */
    fun requestSync(context: Context, state: SyncState) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(state))
            .setInitialDelay(DEBOUNCE_MINUTES, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ON_CHANGE_WORK, ExistingWorkPolicy.REPLACE, request)
    }
}
