package com.workouttracker.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Per-exercise rest lengths.
 *
 * Keyed by name rather than id, which is the whole point: the built-in
 * exercises are not rows anywhere, and two devices must agree on the key
 * without having agreed on a UUID first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExerciseRestTest {

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
    fun `an exercise with no setting of its own has none`() = runTest {
        assertNull(repository.restSecondsFor("Deadlift"))
        assertNull(repository.observeRestSeconds("Deadlift").first())
    }

    @Test
    fun `a rest length is remembered for that exercise`() = runTest {
        repository.setRestSeconds("Deadlift", 210)

        assertEquals(210, repository.restSecondsFor("Deadlift"))
        assertEquals(210, repository.observeRestSeconds("Deadlift").first())
        // And only for that exercise.
        assertNull(repository.restSecondsFor("Bicep Curl"))
    }

    @Test
    fun `the exercise name is matched however it was typed`() = runTest {
        repository.setRestSeconds("Deadlift", 210)

        assertEquals(210, repository.restSecondsFor("deadlift"))
        assertEquals(210, repository.restSecondsFor("  DEADLIFT "))
        // One row, not three, or two devices would never converge.
        assertEquals(1, db.workoutDao().allExerciseSettings().size)
    }

    @Test
    fun `setting it again replaces rather than adds`() = runTest {
        repository.setRestSeconds("Deadlift", 210)
        clock += 100
        repository.setRestSeconds("Deadlift", 180)

        assertEquals(180, repository.restSecondsFor("Deadlift"))
        assertEquals(1, db.workoutDao().allExerciseSettings().size)
    }

    @Test
    fun `clearing it falls back to the default`() = runTest {
        repository.setRestSeconds("Deadlift", 210)
        clock += 100
        repository.setRestSeconds("Deadlift", null)

        assertNull(repository.restSecondsFor("Deadlift"))
        // Tombstoned, not dropped, or the next sync would bring it back.
        assertEquals(1, db.workoutDao().allExerciseSettings().size)
        assertTrue(db.workoutDao().allExerciseSettings().single().deleted)
    }

    @Test
    fun `clearing an exercise that never had one writes nothing`() = runTest {
        repository.setRestSeconds("Deadlift", null)

        assertTrue(db.workoutDao().allExerciseSettings().isEmpty())
    }

    @Test
    fun `rest lengths travel in the snapshot`() = runTest {
        repository.setRestSeconds("Deadlift", 210)

        val snapshot = repository.snapshot()

        assertEquals(Snapshot.CURRENT_VERSION, snapshot.version)
        assertEquals("deadlift", snapshot.exerciseSettings.single().exercise)
        assertEquals(210, snapshot.exerciseSettings.single().restSeconds)
    }

    @Test
    fun `a newer remote rest length wins, an older one does not`() = runTest {
        repository.setRestSeconds("Deadlift", 210)

        val applied = repository.merge(
            Snapshot(
                exportedAt = clock,
                workouts = emptyList(),
                sets = emptyList(),
                exerciseSettings = listOf(
                    ExerciseSettings("deadlift", restSeconds = 240, updatedAt = clock + 500)
                ),
            )
        )

        assertEquals(1, applied)
        assertEquals(240, repository.restSecondsFor("Deadlift"))

        val ignored = repository.merge(
            Snapshot(
                exportedAt = clock,
                workouts = emptyList(),
                sets = emptyList(),
                exerciseSettings = listOf(
                    ExerciseSettings("deadlift", restSeconds = 60, updatedAt = clock - 500)
                ),
            )
        )

        assertEquals(0, ignored)
        assertEquals(240, repository.restSecondsFor("Deadlift"))
    }

    @Test
    fun `a remote tombstone clears a rest length set locally`() = runTest {
        repository.setRestSeconds("Deadlift", 210)

        repository.merge(
            Snapshot(
                exportedAt = clock,
                workouts = emptyList(),
                sets = emptyList(),
                exerciseSettings = listOf(
                    ExerciseSettings(
                        "deadlift",
                        restSeconds = 210,
                        updatedAt = clock + 500,
                        deleted = true,
                    )
                ),
            )
        )

        assertNull(repository.restSecondsFor("Deadlift"))
    }
}
