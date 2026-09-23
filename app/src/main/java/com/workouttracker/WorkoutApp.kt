package com.workouttracker

import android.app.Application
import com.workouttracker.data.AppDatabase
import com.workouttracker.data.SyncTrigger
import com.workouttracker.data.WorkoutRepository
import com.workouttracker.rest.RestPrefs
import com.workouttracker.rest.RestTimer
import com.workouttracker.sync.SyncManager
import com.workouttracker.sync.SyncPrefs
import com.workouttracker.sync.SyncScheduler

/**
 * Holds the app's few singletons. At this size a hand-rolled container is
 * clearer than a DI framework, and everything below is constructor-injected so
 * it stays testable.
 */
class WorkoutApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.build(this) }

    val syncPrefs: SyncPrefs by lazy { SyncPrefs(this) }

    val restPrefs: RestPrefs by lazy { RestPrefs(this) }

    val restTimer: RestTimer by lazy { RestTimer(this, restPrefs) }

    val repository: WorkoutRepository by lazy {
        WorkoutRepository(
            db = database,
            syncTrigger = SyncTrigger {
                val sync = syncPrefs.state.value
                if (sync.autoSyncEnabled) SyncScheduler.requestSync(this, sync)
            },
        )
    }

    val syncManager: SyncManager by lazy { SyncManager(this, repository, syncPrefs) }

    override fun onCreate() {
        super.onCreate()
        SyncScheduler.applySettings(this, syncPrefs.state.value)
    }
}
