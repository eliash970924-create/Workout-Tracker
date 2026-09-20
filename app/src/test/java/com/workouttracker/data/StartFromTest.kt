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
 * Starting a session from an earlier one.
 *
 * The point is to save rebuilding a routine exercise by exercise, so what
 * matters is that the copy is complete -- order, metrics, numbers -- and that
 * it is a plan rather than a claim about what has been done.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StartFromTest {

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

    /** A push day with three exercises, one of them cardio, to copy from. */
    private suspend fun pushDay(day: LocalDate = LocalDate.of(2026, 9, 1)): String {
        val id = repository.createWorkout("Push day", day)
        repository.addSet(id, "Barbell Bench Press", reps = 5, weightKg = 80.0)
        repository.addSet(id, "Barbell Bench Press", reps = 5, weightKg = 85.0)
        repository.addSet(id, "Overhead Press", reps = 8, weightKg = 40.0)
        repository.addSet(id, "Running", meters = 3000.0, seconds = 900)
        return id
    }

    @Test
    fun `a copied session brings its exercises, order and numbers`() = runTest {
        val source = pushDay()
        val target = repository.createWorkout("Workout", LocalDate.of(2026, 9, 8))

        assertEquals(4, repository.copySession(target, source))

        val copied = db.workoutDao().setsOf(target)
        assertEquals(4, copied.size)
        assertEquals(
            listOf("Barbell Bench Press", "Barbell Bench Press", "Overhead Press", "Running"),
            copied.map { it.exercise },
        )
        assertEquals(listOf(80.0, 85.0, 40.0, 0.0), copied.map { it.weightKg })
        // Cardio comes across in its own units, not as reps and kilos.
        val run = copied.single { it.exercise == "Running" }
        assertEquals(ExerciseMetric.DISTANCE_TIME.name, run.metric)
        assertEquals(3000.0, run.meters, 0.001)
        assertEquals(900, run.seconds)
    }

    @Test
    fun `a copied session is a plan, not a record of having done it`() = runTest {
        val source = pushDay()
        for (set in db.workoutDao().setsOf(source)) repository.setCompleted(set.id, true)
        val target = repository.createWorkout("Workout", LocalDate.of(2026, 9, 8))

        repository.copySession(target, source)

        assertTrue(db.workoutDao().setsOf(target).none { it.completed })
        // And the session it came from is untouched.
        assertTrue(db.workoutDao().setsOf(source).all { it.completed })
        assertEquals(4, db.workoutDao().setsOf(source).size)
    }

    @Test
    fun `copying appends rather than replacing what is already there`() = runTest {
        val source = pushDay()
        val target = repository.createWorkout("Workout", LocalDate.of(2026, 9, 8))
        repository.addSet(target, "Deadlift", reps = 5, weightKg = 140.0)

        repository.copySession(target, source)

        val sets = db.workoutDao().setsOf(target)
        assertEquals(5, sets.size)
        assertEquals("Deadlift", sets.first().exercise)
        // Positions stay unique, which is what the ordering and the
        // drag-to-reorder both rest on.
        assertEquals(sets.size, sets.map { it.position }.distinct().size)
    }

    @Test
    fun `copying a session onto itself does nothing`() = runTest {
        val source = pushDay()

        assertEquals(0, repository.copySession(source, source))
        assertEquals(4, db.workoutDao().setsOf(source).size)
    }

    @Test
    fun `the sessions on offer are recent, non-empty, and not this one`() = runTest {
        val old = pushDay(LocalDate.of(2026, 9, 1))
        clock += 100
        val recent = repository.createWorkout("Leg day", LocalDate.of(2026, 9, 5))
        repository.addSet(recent, "Back Squat", reps = 5, weightKg = 100.0)
        // An empty session has nothing to copy, so it is not offered.
        repository.createWorkout("Never started", LocalDate.of(2026, 9, 6))
        val current = repository.createWorkout("Workout", LocalDate.of(2026, 9, 8))

        val offered = repository.recentSessions(excludeWorkoutId = current)

        assertEquals(listOf("Leg day", "Push day"), offered.map { it.name })
        assertEquals(listOf(recent, old), offered.map { it.id })
    }

    @Test
    fun `each offer says what is in it, once per exercise and in order`() = runTest {
        val source = pushDay()
        val current = repository.createWorkout("Workout", LocalDate.of(2026, 9, 8))

        val offer = repository.recentSessions(excludeWorkoutId = current).single()

        assertEquals(4, offer.setCount)
        // Two bench sets, one entry.
        assertEquals(
            listOf("Barbell Bench Press", "Overhead Press", "Running"),
            offer.exercises,
        )
    }

    @Test
    fun `a deleted session is not offered`() = runTest {
        val source = pushDay()
        val current = repository.createWorkout("Workout", LocalDate.of(2026, 9, 8))
        clock += 100
        repository.deleteWorkout(source)

        assertTrue(repository.recentSessions(excludeWorkoutId = current).isEmpty())
    }

    @Test
    fun `a name nobody chose can be replaced, a typed one cannot`() {
        assertTrue(isDefaultWorkoutName("Workout"))
        assertTrue(isDefaultWorkoutName("Weekend session"))
        assertTrue(isDefaultWorkoutName("  Workout "))
        assertTrue(isDefaultWorkoutName(""))
        assertFalse(isDefaultWorkoutName("Push day"))
        // The creator and the check have to agree, or a fresh session would
        // keep a name it was only ever given by default.
        assertTrue(isDefaultWorkoutName(defaultWorkoutName(LocalDate.of(2026, 9, 8))))
        assertTrue(isDefaultWorkoutName(defaultWorkoutName(LocalDate.of(2026, 9, 12))))
    }
}
