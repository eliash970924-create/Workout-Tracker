package com.workouttracker.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Backup and restore.
 *
 * A backup is only worth having if it restores, so the test that matters is
 * the round trip: take one, wipe the database, put it back, and find the log
 * as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository
    private var clock = 1_000L

    @Before
    fun setUp() {
        db = open()
    }

    @After
    fun tearDown() = db.close()

    private fun open(): AppDatabase {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = WorkoutRepository(db, syncTrigger = {}, now = { clock })
        return db
    }

    private suspend fun aLoggedSession(): String {
        val id = repository.createWorkout("Push day", LocalDate.of(2026, 9, 1))
        repository.addSet(id, "Barbell Bench Press", reps = 5, weightKg = 80.0)
        repository.addSet(id, "Running", meters = 5000.0, seconds = 1500)
        repository.addCustomExercise("Elias Special", MuscleGroup.CORE)
        repository.setRestSeconds("Deadlift", 210)
        repository.setMetric("Elias Special", ExerciseMetric.TIME)
        return id
    }

    @Test
    fun `a backup restores into an empty database`() = runTest {
        aLoggedSession()
        val file = encodeBackup(repository.snapshot())

        // The phone was lost; this is a fresh install.
        db.close()
        open()
        assertTrue(db.workoutDao().allWorkouts().isEmpty())

        val applied = repository.merge(decodeBackup(file))

        assertTrue("nothing was restored", applied > 0)
        val workout = db.workoutDao().allWorkouts().single()
        assertEquals("Push day", workout.name)
        val sets = db.workoutDao().setsOf(workout.id).associateBy { it.exercise }
        assertEquals(2, sets.size)
        assertEquals(80.0, sets.getValue("Barbell Bench Press").weightKg, 0.001)
        // Cardio comes back in its own units rather than as reps and kilos.
        assertEquals(5000.0, sets.getValue("Running").meters, 0.001)
        assertEquals(1500, sets.getValue("Running").seconds)
        // And the things that are not sets: custom exercises and overrides.
        assertNotNull(db.workoutDao().allCustomExercises().singleOrNull { it.name == "Elias Special" })
        assertEquals(210, repository.restSecondsFor("Deadlift"))
        assertEquals(ExerciseMetric.TIME, repository.resolveMetric("Elias Special"))
    }

    @Test
    fun `restoring the same backup twice changes nothing the second time`() = runTest {
        aLoggedSession()
        val file = encodeBackup(repository.snapshot())

        repository.merge(decodeBackup(file))
        val second = repository.merge(decodeBackup(file))

        // Merging is by updatedAt, so a backup of the log you already have is
        // a no-op rather than a pile of duplicates.
        assertEquals(0, second)
        assertEquals(2, db.workoutDao().setsOf(db.workoutDao().allWorkouts().single().id).size)
    }

    @Test
    fun `a stale backup cannot undo work done since it was taken`() = runTest {
        val id = aLoggedSession()
        val stale = encodeBackup(repository.snapshot())

        clock += 5_000
        repository.addSet(id, "Deadlift", reps = 5, weightKg = 140.0)
        repository.updateWorkout(db.workoutDao().findWorkout(id)!!.copy(name = "Push day (heavy)"))

        repository.merge(decodeBackup(stale))

        // Restoring is a merge: the newer local edits win.
        assertEquals("Push day (heavy)", db.workoutDao().findWorkout(id)!!.name)
        assertEquals(3, db.workoutDao().setsOf(id).size)
    }

    @Test
    fun `a backup from a newer version of the app is refused, not half read`() {
        val future = encodeBackup(
            Snapshot(
                version = Snapshot.CURRENT_VERSION + 1,
                exportedAt = 1,
                workouts = emptyList(),
                sets = emptyList(),
            )
        )

        val thrown = assertThrows(BackupTooNewException::class.java) { decodeBackup(future) }

        assertEquals(Snapshot.CURRENT_VERSION + 1, thrown.version)
    }

    @Test
    fun `a backup that has picked up a byte order mark still reads`() = runTest {
        aLoggedSession()
        val file = "﻿" + encodeBackup(repository.snapshot()) + "\n"

        assertEquals(Snapshot.CURRENT_VERSION, decodeBackup(file).version)
    }

    @Test
    fun `a file that is not a backup fails rather than importing nonsense`() {
        assertThrows(Exception::class.java) { decodeBackup("date,session,exercise\n2026-09-01,Push") }
        assertThrows(Exception::class.java) { decodeBackup("") }
    }

    @Test
    fun `an old backup still restores, and its sets read as done`() = runTest {
        val id = repository.createWorkout("Placeholder", LocalDate.of(2026, 9, 1))
        val version2 = """
            {"version":2,"exportedAt":1,
             "workouts":[{"id":"$id","date":20000,"name":"Leg day","notes":"",
                          "updatedAt":${clock + 500},"deleted":false}],
             "sets":[{"id":"s1","workoutId":"$id","exercise":"Back Squat","reps":5,
                      "weightKg":100.0,"position":0,"muscleGroup":"QUADS",
                      "updatedAt":${clock + 500},"deleted":false}]}
        """.trimIndent()

        repository.merge(decodeBackup(version2))

        val set = db.workoutDao().findSet("s1")!!
        assertEquals("Back Squat", set.exercise)
        // Version 2 had no completion flag; those sets were logged after being
        // performed, so restoring must not silently un-tick them.
        assertTrue(set.completed)
        assertEquals(ExerciseMetric.WEIGHT_REPS.name, set.metric)
    }
}
