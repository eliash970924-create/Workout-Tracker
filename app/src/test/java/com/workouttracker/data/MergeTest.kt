package com.workouttracker.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
                SetEntry("s1", "w1", "Deadlift", 5, 100.0, 0, clock),
                SetEntry("s2", "w1", "Deadlift", 5, 105.0, 1, clock),
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
}
