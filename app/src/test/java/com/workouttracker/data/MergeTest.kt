package com.workouttracker.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Covers the last-write-wins rules the Drive sync depends on. */
@RunWith(RobolectricTestRunner::class)
// Robolectric would otherwise instantiate the real WorkoutApp, whose onCreate
// schedules WorkManager work. WorkManager's androidx.startup initializer does
// not run here, so getInstance() throws. These tests only need a Context for
// the in-memory database.
@Config(application = Application::class)
class MergeTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository
    private var clock = 1_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = WorkoutRepository(db, syncTrigger = {}, now = { clock })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `remote edit newer than local wins`() = runTest {
        val id = repository.createWorkout("Legs", LocalDate.of(2026, 1, 5))

        val applied = repository.merge(
            snapshotOf(
                Workout(id = id, date = 20_000, name = "Legs (heavy)", updatedAt = clock + 500)
            )
        )

        assertEquals(1, applied)
        assertEquals("Legs (heavy)", db.workoutDao().findWorkout(id)?.name)
    }

    @Test
    fun `local edit newer than remote is kept`() = runTest {
        clock = 5_000
        val id = repository.createWorkout("Push", LocalDate.of(2026, 1, 5))

        val applied = repository.merge(
            snapshotOf(Workout(id = id, date = 20_000, name = "Stale", updatedAt = 1_000))
        )

        assertEquals(0, applied)
        assertEquals("Push", db.workoutDao().findWorkout(id)?.name)
    }

    @Test
    fun `equal timestamps keep the local copy`() = runTest {
        val id = repository.createWorkout("Pull", LocalDate.of(2026, 1, 5))

        repository.merge(
            snapshotOf(Workout(id = id, date = 20_000, name = "Remote", updatedAt = clock))
        )

        assertEquals("Pull", db.workoutDao().findWorkout(id)?.name)
    }

    @Test
    fun `a remote tombstone deletes a workout that still exists locally`() = runTest {
        val id = repository.createWorkout("Legs", LocalDate.of(2026, 1, 5))
        repository.addSet(id, "Squat")

        repository.merge(
            snapshotOf(
                Workout(id = id, date = 20_000, name = "Legs", updatedAt = clock + 1, deleted = true)
            )
        )

        assertTrue(db.workoutDao().findWorkout(id)!!.deleted)
        // Soft-deleted rows disappear from the list without losing the tombstone.
        assertTrue(summaries().none { it.id == id })
    }

    @Test
    fun `a local delete is not resurrected by an older remote copy`() = runTest {
        val id = repository.createWorkout("Legs", LocalDate.of(2026, 1, 5))
        val beforeDelete = db.workoutDao().findWorkout(id)!!
        clock += 1_000
        repository.deleteWorkout(id)

        repository.merge(snapshotOf(beforeDelete))

        assertTrue(db.workoutDao().findWorkout(id)!!.deleted)
    }

    @Test
    fun `deleting a workout tombstones its sets too`() = runTest {
        val id = repository.createWorkout("Legs", LocalDate.of(2026, 1, 5))
        repository.addSet(id, "Squat")
        clock += 10
        repository.deleteWorkout(id)

        val sets = db.workoutDao().allSets()
        assertEquals(1, sets.size)
        assertTrue(sets.single().deleted)
        assertEquals(clock, sets.single().updatedAt)
    }

    @Test
    fun `a set whose workout is unknown is skipped rather than crashing`() = runTest {
        val applied = repository.merge(
            Snapshot(
                exportedAt = clock,
                workouts = emptyList(),
                sets = listOf(
                    SetEntry(
                        id = "orphan",
                        workoutId = "missing-workout",
                        exercise = "Bench",
                        reps = 5,
                        weightKg = 60.0,
                        position = 0,
                        updatedAt = clock,
                    )
                ),
            )
        )

        assertEquals(0, applied)
        assertNull(db.workoutDao().findSet("orphan"))
    }

    @Test
    fun `a remote workout arriving with its sets is inserted whole`() = runTest {
        val snapshot = Snapshot(
            exportedAt = clock,
            workouts = listOf(Workout(id = "w1", date = 20_000, name = "Remote day", updatedAt = clock)),
            sets = listOf(
                SetEntry("s1", "w1", "Deadlift", 5, 100.0, 0, updatedAt = clock),
                SetEntry("s2", "w1", "Deadlift", 5, 105.0, 1, updatedAt = clock),
            ),
        )

        assertEquals(3, repository.merge(snapshot))
        assertNotNull(db.workoutDao().findWorkout("w1"))
        val summary = summaries().single { it.id == "w1" }
        assertEquals(2, summary.setCount)
        assertEquals(1025.0, summary.volume, 0.001)
    }

    @Test
    fun `syncing twice in a row changes nothing the second time`() = runTest {
        val id = repository.createWorkout("Legs", LocalDate.of(2026, 1, 5))
        repository.addSet(id, "Squat")
        val exported = repository.snapshot()

        assertEquals(0, repository.merge(exported))
        assertEquals(1, summaries().size)
    }

    private suspend fun summaries() = db.workoutDao().observeSummaries().first()

    private fun snapshotOf(vararg workouts: Workout) =
        Snapshot(exportedAt = clock, workouts = workouts.toList(), sets = emptyList())

    @Test
    fun `custom exercises merge by last write, like everything else`() = runTest {
        clock = 100
        repository.addCustomExercise("Elias Special", MuscleGroup.CORE)
        val local = db.workoutDao().allCustomExercises().single()

        val applied = repository.merge(
            Snapshot(
                exportedAt = clock,
                workouts = emptyList(),
                sets = emptyList(),
                customExercises = listOf(
                    local.copy(name = "Renamed remotely", updatedAt = clock + 50),
                    CustomExercise("c2", "From another phone", MuscleGroup.CALVES.name, clock),
                ),
            )
        )

        assertEquals(2, applied)
        val stored = db.workoutDao().allCustomExercises().associateBy { it.id }
        assertEquals("Renamed remotely", stored.getValue(local.id).name)
        assertEquals("From another phone", stored.getValue("c2").name)
    }

    @Test
    fun `sets from a pre-completion snapshot arrive ticked off`() = runTest {
        val id = repository.createWorkout("Push", LocalDate.of(2026, 1, 5))

        repository.merge(
            Snapshot(
                version = 2,
                exportedAt = clock,
                workouts = emptyList(),
                sets = listOf(
                    SetEntry(
                        id = "s1",
                        workoutId = id,
                        exercise = "Barbell Bench Press",
                        reps = 5,
                        weightKg = 80.0,
                        position = 0,
                        updatedAt = clock + 500,
                    )
                ),
            )
        )

        // Version 2 had no completion flag, and everything in it was logged
        // after being performed. Taking the field default would un-tick it.
        assertTrue(db.workoutDao().allSets().single().completed)
    }

    @Test
    fun `a current snapshot's completion flag is taken as written`() = runTest {
        val id = repository.createWorkout("Push", LocalDate.of(2026, 1, 5))

        repository.merge(
            Snapshot(
                exportedAt = clock,
                workouts = emptyList(),
                sets = listOf(
                    SetEntry(
                        id = "s1",
                        workoutId = id,
                        exercise = "Barbell Bench Press",
                        reps = 5,
                        weightKg = 80.0,
                        position = 0,
                        completed = false,
                        updatedAt = clock + 500,
                    )
                ),
            )
        )

        assertFalse(db.workoutDao().allSets().single().completed)
    }

    @Test
    fun `ticking a set off is a change the sync will carry`() = runTest {
        val id = repository.createWorkout("Push", LocalDate.of(2026, 1, 5))
        repository.addSet(id, "Barbell Bench Press")
        val set = db.workoutDao().allSets().single()
        assertFalse(set.completed)

        clock += 100
        repository.setCompleted(set.id, true)

        val updated = db.workoutDao().allSets().single()
        assertTrue(updated.completed)
        // A newer updatedAt is what makes last-write-wins carry it to Drive.
        assertTrue(updated.updatedAt > set.updatedAt)
    }

    @Test
    fun `a set records the muscle group of its exercise`() = runTest {
        val id = repository.createWorkout("Push", LocalDate.of(2026, 1, 5))

        repository.addSet(id, "Barbell Bench Press")

        assertEquals(MuscleGroup.CHEST.name, db.workoutDao().allSets().single().muscleGroup)
    }

    @Test
    fun `an unknown exercise falls back to Other rather than guessing`() = runTest {
        val id = repository.createWorkout("Push", LocalDate.of(2026, 1, 5))

        repository.addSet(id, "Elias Special")

        assertEquals(MuscleGroup.OTHER.name, db.workoutDao().allSets().single().muscleGroup)
    }

    @Test
    fun `adding another set reuses the muscle group of the user's own exercise`() = runTest {
        val id = repository.createWorkout("Core", LocalDate.of(2026, 1, 5))
        repository.addCustomExercise("Elias Special", MuscleGroup.CORE)

        // No group passed: it has to be resolved from the custom exercise list.
        repository.addSet(id, "Elias Special")

        assertEquals(MuscleGroup.CORE.name, db.workoutDao().allSets().single().muscleGroup)
    }
}
