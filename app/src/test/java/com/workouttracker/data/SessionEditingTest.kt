package com.workouttracker.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Reordering, removing an exercise, and copying a previous session.
 *
 * All three work by rewriting `position` or tombstoning rows, which the Drive
 * sync then carries by last-write-wins, so the ordering these assert on is the
 * ordering that reaches every device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SessionEditingTest {

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

    private suspend fun setsOf(workoutId: String) = db.workoutDao().setsOf(workoutId)

    private suspend fun exerciseOrderOf(workoutId: String) =
        setsOf(workoutId).map { it.exercise }.distinct()

    @Test
    fun `moving a set up swaps it with the one above`() = runTest {
        val id = repository.createWorkout("Push", LocalDate.of(2026, 1, 5))
        repository.addSet(id, "Barbell Bench Press", reps = 5, weightKg = 60.0)
        repository.addSet(id, "Barbell Bench Press", reps = 5, weightKg = 80.0)
        repository.addSet(id, "Barbell Bench Press", reps = 3, weightKg = 90.0)

        // The warm-up set added last, moved to the front where it belongs.
        val last = setsOf(id).last()
        repository.moveSet(last.id, -1)
        repository.moveSet(last.id, -1)

        assertEquals(listOf(90.0, 60.0, 80.0), setsOf(id).map { it.weightKg })
    }

    @Test
    fun `moving the first set up does nothing rather than falling off the end`() = runTest {
        val id = repository.createWorkout("Push", LocalDate.of(2026, 1, 5))
        repository.addSet(id, "Barbell Bench Press", reps = 5, weightKg = 60.0)
        repository.addSet(id, "Barbell Bench Press", reps = 5, weightKg = 80.0)

        repository.moveSet(setsOf(id).first().id, -1)

        assertEquals(listOf(60.0, 80.0), setsOf(id).map { it.weightKg })
    }

    @Test
    fun `a set only moves within its own exercise`() = runTest {
        val id = repository.createWorkout("Full body", LocalDate.of(2026, 1, 5))
        repository.addSet(id, "Deadlift", reps = 5, weightKg = 100.0)
        repository.addSet(id, "Barbell Bench Press", reps = 5, weightKg = 60.0)
        repository.addSet(id, "Deadlift", reps = 5, weightKg = 120.0)

        val heavier = setsOf(id).single { it.weightKg == 120.0 }
        repository.moveSet(heavier.id, -1)

        // Deadlift's two sets swapped; bench is untouched and the exercises
        // still appear in the order they were added.
        assertEquals(listOf("Deadlift", "Barbell Bench Press"), exerciseOrderOf(id))
        assertEquals(
            listOf(120.0, 100.0),
            setsOf(id).filter { it.exercise == "Deadlift" }.map { it.weightKg },
        )
    }

    @Test
    fun `moving an exercise down reorders the session`() = runTest {
        val id = repository.createWorkout("Full body", LocalDate.of(2026, 1, 5))
        repository.addSet(id, "Deadlift")
        repository.addSet(id, "Barbell Bench Press")
        repository.addSet(id, "Leg Press")

        repository.moveExercise(id, "Deadlift", 1)

        assertEquals(
            listOf("Barbell Bench Press", "Deadlift", "Leg Press"),
            exerciseOrderOf(id),
        )
    }

    @Test
    fun `moving an exercise past the end does nothing`() = runTest {
        val id = repository.createWorkout("Full body", LocalDate.of(2026, 1, 5))
        repository.addSet(id, "Deadlift")
        repository.addSet(id, "Leg Press")

        repository.moveExercise(id, "Leg Press", 1)

        assertEquals(listOf("Deadlift", "Leg Press"), exerciseOrderOf(id))
    }

    @Test
    fun `moving an exercise gathers its scattered sets together`() = runTest {
        val id = repository.createWorkout("Full body", LocalDate.of(2026, 1, 5))
        repository.addSet(id, "Deadlift", reps = 5, weightKg = 100.0)
        repository.addSet(id, "Leg Press", reps = 10, weightKg = 200.0)
        repository.addSet(id, "Deadlift", reps = 5, weightKg = 120.0)

        repository.moveExercise(id, "Leg Press", -1)

        // Renumbering is what makes the order well defined, so the two deadlift
        // sets end up adjacent rather than straddling the leg press.
        assertEquals(
            listOf("Leg Press", "Deadlift", "Deadlift"),
            setsOf(id).map { it.exercise },
        )
        assertEquals(listOf(0, 1, 2), setsOf(id).map { it.position })
    }

    @Test
    fun `removing an exercise tombstones every one of its sets`() = runTest {
        val id = repository.createWorkout("Full body", LocalDate.of(2026, 1, 5))
        repository.addSet(id, "Deadlift")
        repository.addSet(id, "Deadlift")
        repository.addSet(id, "Leg Press")

        repository.deleteExercise(id, "Deadlift")

        assertEquals(listOf("Leg Press"), exerciseOrderOf(id))
        // Tombstoned rather than dropped, or the next sync would bring it back.
        assertEquals(3, db.workoutDao().allSets().size)
        assertTrue(db.workoutDao().allSets().filter { it.exercise == "Deadlift" }
            .all { it.deleted })
    }

    @Test
    fun `copying last session brings over its reps and weights`() = runTest {
        val old = repository.createWorkout("Pull", LocalDate.of(2026, 1, 1))
        repository.addSet(old, "Deadlift", reps = 5, weightKg = 100.0)
        repository.addSet(old, "Deadlift", reps = 5, weightKg = 110.0)
        repository.addSet(old, "Deadlift", reps = 3, weightKg = 120.0)

        val today = repository.createWorkout("Pull", LocalDate.of(2026, 1, 8))
        repository.addSet(today, "Deadlift")

        val copied = repository.copyLastSession(today, "Deadlift")

        assertEquals(3, copied)
        val sets = setsOf(today)
        assertEquals(listOf(100.0, 110.0, 120.0), sets.map { it.weightKg })
        assertEquals(listOf(5, 5, 3), sets.map { it.reps })
        // Copied as a plan, not as work already done.
        assertTrue(sets.none { it.completed })
    }

    @Test
    fun `copying keeps sets already ticked off and replaces only the plan`() = runTest {
        val old = repository.createWorkout("Pull", LocalDate.of(2026, 1, 1))
        repository.addSet(old, "Deadlift", reps = 5, weightKg = 100.0)

        val today = repository.createWorkout("Pull", LocalDate.of(2026, 1, 8))
        repository.addSet(today, "Deadlift", reps = 8, weightKg = 60.0)
        repository.addSet(today, "Deadlift", reps = 8, weightKg = 70.0)
        // The first is done; the second is still just a plan.
        repository.setCompleted(setsOf(today).first().id, true)

        repository.copyLastSession(today, "Deadlift")

        val sets = setsOf(today)
        assertEquals(2, sets.size)
        assertEquals(60.0, sets.first().weightKg, 0.001)
        assertTrue(sets.first().completed)
        assertEquals(100.0, sets.last().weightKg, 0.001)
        assertFalse(sets.last().completed)
    }

    @Test
    fun `copying leaves the exercise where it was in the session`() = runTest {
        val old = repository.createWorkout("Full body", LocalDate.of(2026, 1, 1))
        repository.addSet(old, "Deadlift", reps = 5, weightKg = 100.0)

        val today = repository.createWorkout("Full body", LocalDate.of(2026, 1, 8))
        repository.addSet(today, "Deadlift")
        repository.addSet(today, "Leg Press")

        repository.copyLastSession(today, "Deadlift")

        // Replacing the sets must not shuffle deadlift to the bottom just
        // because its rows are newer.
        assertEquals(listOf("Deadlift", "Leg Press"), exerciseOrderOf(today))
    }

    @Test
    fun `copying an exercise never trained before does nothing`() = runTest {
        val id = repository.createWorkout("Pull", LocalDate.of(2026, 1, 8))
        repository.addSet(id, "Deadlift", reps = 8, weightKg = 60.0)

        val copied = repository.copyLastSession(id, "Deadlift")

        assertEquals(0, copied)
        assertEquals(1, setsOf(id).size)
        assertEquals(60.0, setsOf(id).single().weightKg, 0.001)
    }

    @Test
    fun `the session in hand is never its own last time`() = runTest {
        val id = repository.createWorkout("Pull", LocalDate.of(2026, 1, 8))
        repository.addSet(id, "Deadlift", reps = 8, weightKg = 60.0)
        repository.addSet(id, "Deadlift", reps = 8, weightKg = 65.0)

        assertEquals(0, repository.copyLastSession(id, "Deadlift"))
        assertEquals(listOf(60.0, 65.0), setsOf(id).map { it.weightKg })
    }
}
