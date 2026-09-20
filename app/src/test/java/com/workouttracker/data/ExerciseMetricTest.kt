package com.workouttracker.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Per-exercise metrics.
 *
 * The invariant worth defending is that a set's numbers are only ever read
 * back through the metric they were typed under. Everything else here follows
 * from that: sets carry their own metric, a change applies to the session in
 * hand, and history stays as it was recorded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExerciseMetricTest {

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

    private suspend fun session() = repository.createWorkout("Test")

    @Test
    fun `an exercise is measured the way the catalogue says`() = runTest {
        assertEquals(ExerciseMetric.WEIGHT_REPS, repository.resolveMetric("Barbell Bench Press"))
        assertEquals(ExerciseMetric.REPS, repository.resolveMetric("Pull-Up"))
        assertEquals(ExerciseMetric.TIME, repository.resolveMetric("Plank"))
        assertEquals(ExerciseMetric.DISTANCE_TIME, repository.resolveMetric("Stationary Bike"))
        // Something the user typed in, with nothing said about it.
        assertEquals(ExerciseMetric.WEIGHT_REPS, repository.resolveMetric("Elias Special"))
    }

    @Test
    fun `a new set only carries the numbers its metric uses`() = runTest {
        val id = session()
        repository.addSet(id, "Stationary Bike")
        repository.addSet(id, "Plank")
        repository.addSet(id, "Barbell Bench Press")

        val sets = db.workoutDao().setsOf(id).associateBy { it.exercise }

        val bike = sets.getValue("Stationary Bike")
        assertEquals(ExerciseMetric.DISTANCE_TIME.name, bike.metric)
        assertEquals(0, bike.reps)
        assertEquals(0.0, bike.weightKg, 0.001)

        // A plank starts at a minute, which is a real guess; a bike ride has
        // no useful one and starts empty.
        assertEquals(60, sets.getValue("Plank").seconds)
        assertEquals(0, bike.seconds)

        val bench = sets.getValue("Barbell Bench Press")
        assertEquals(8, bench.reps)
        assertEquals(0, bench.seconds)
        assertEquals(0.0, bench.meters, 0.001)
    }

    @Test
    fun `another set repeats the last one, in its own units`() = runTest {
        val id = session()
        repository.addSet(id, "Running", meters = 5000.0, seconds = 1500)
        repository.addSet(id, "Running")

        val sets = db.workoutDao().setsOfExerciseIn(id, "Running")

        assertEquals(2, sets.size)
        assertEquals(5000.0, sets.last().meters, 0.001)
        assertEquals(1500, sets.last().seconds)
    }

    @Test
    fun `an override beats the catalogue, and clearing it hands back`() = runTest {
        repository.setMetric("Plank", ExerciseMetric.REPS)
        assertEquals(ExerciseMetric.REPS, repository.resolveMetric("Plank"))
        // However it was typed: the key is the lower-cased name.
        assertEquals(ExerciseMetric.REPS, repository.resolveMetric("  PLANK "))

        clock += 100
        repository.setMetric("Plank", null)

        assertEquals(ExerciseMetric.TIME, repository.resolveMetric("Plank"))
    }

    @Test
    fun `rest and metric are independent overrides on one row`() = runTest {
        repository.setRestSeconds("Deadlift", 210)
        clock += 100
        repository.setMetric("Deadlift", ExerciseMetric.REPS)

        assertEquals(1, db.workoutDao().allExerciseSettings().size)
        assertEquals(210, repository.restSecondsFor("Deadlift"))
        assertEquals(ExerciseMetric.REPS, repository.resolveMetric("Deadlift"))

        // Clearing one must not take the other with it.
        clock += 100
        repository.setMetric("Deadlift", null)

        assertEquals(210, repository.restSecondsFor("Deadlift"))
        assertFalse(db.workoutDao().allExerciseSettings().single().deleted)

        // Once nothing is left the row is tombstoned rather than left empty.
        clock += 100
        repository.setRestSeconds("Deadlift", null)

        assertNull(repository.restSecondsFor("Deadlift"))
        assertTrue(db.workoutDao().allExerciseSettings().single().deleted)
    }

    @Test
    fun `changing the metric re-measures this session and no other`() = runTest {
        val old = repository.createWorkout("Last week")
        repository.addSet(old, "Elias Special", reps = 5, weightKg = 100.0)
        val today = repository.createWorkout("Today")
        repository.addSet(today, "Elias Special", reps = 5, weightKg = 100.0)

        clock += 100
        repository.setMetric("Elias Special", ExerciseMetric.TIME, retagWorkoutId = today)

        val now = db.workoutDao().setsOfExerciseIn(today, "Elias Special").single()
        assertEquals(ExerciseMetric.TIME.name, now.metric)
        // The numbers the new metric cannot read are dropped, not left lying
        // around to reappear.
        assertEquals(0, now.reps)
        assertEquals(0.0, now.weightKg, 0.001)

        // What was logged last week was really five reps at a hundred kilos,
        // and relabelling it would make the log say something untrue.
        val before = db.workoutDao().setsOfExerciseIn(old, "Elias Special").single()
        assertEquals(ExerciseMetric.WEIGHT_REPS.name, before.metric)
        assertEquals(5, before.reps)
        assertEquals(100.0, before.weightKg, 0.001)
    }

    @Test
    fun `copying last session copies how it was measured too`() = runTest {
        val old = repository.createWorkout("Last week")
        repository.addSet(old, "Running", meters = 5000.0, seconds = 1500)
        val today = repository.createWorkout("Today")
        repository.addSet(today, "Running")

        repository.copyLastSession(today, "Running")

        val copied = db.workoutDao().setsOfExerciseIn(today, "Running").single()
        assertEquals(ExerciseMetric.DISTANCE_TIME.name, copied.metric)
        assertEquals(5000.0, copied.meters, 0.001)
        assertEquals(1500, copied.seconds)
    }

    @Test
    fun `volume counts the weighted sets and ignores the rest`() = runTest {
        val id = session()
        repository.addSet(id, "Barbell Bench Press", reps = 5, weightKg = 80.0)
        repository.addSet(id, "Running", meters = 5000.0, seconds = 1500)

        // Cardio contributes nothing to volume and everything to the totals
        // beside it, which is what makes one card able to describe both.
        val row = db.workoutDao().observeSummaries().first().single()
        assertEquals(400.0, row.volume, 0.001)
        assertEquals(5000.0, row.totalMeters, 0.001)
        assertEquals(1500, row.totalSeconds)
    }

    @Test
    fun `metrics travel in the snapshot and merge by time like everything else`() = runTest {
        val id = session()
        repository.addSet(id, "Running", meters = 5000.0, seconds = 1500)
        repository.setMetric("Elias Special", ExerciseMetric.TIME)

        val snapshot = repository.snapshot()

        assertEquals(Snapshot.CURRENT_VERSION, snapshot.version)
        val set = snapshot.sets.single()
        assertEquals(ExerciseMetric.DISTANCE_TIME.name, set.metric)
        assertEquals(5000.0, set.meters, 0.001)
        assertEquals(
            ExerciseMetric.TIME.name,
            snapshot.exerciseSettings.single { it.exercise == "elias special" }.metric,
        )

        val applied = repository.merge(
            Snapshot(
                exportedAt = clock,
                workouts = emptyList(),
                sets = emptyList(),
                exerciseSettings = listOf(
                    ExerciseSettings(
                        "elias special",
                        metric = ExerciseMetric.REPS.name,
                        updatedAt = clock + 500,
                    )
                ),
            )
        )

        assertEquals(1, applied)
        assertEquals(ExerciseMetric.REPS, repository.resolveMetric("Elias Special"))
    }

    @Test
    fun `a snapshot from before metrics reads as weight and reps`() = runTest {
        val id = session()
        repository.merge(
            Snapshot(
                version = 4,
                exportedAt = clock,
                workouts = listOf(Workout(id, 20_000, "Old", updatedAt = clock + 500)),
                sets = listOf(
                    SetEntry("s1", id, "Plank", 1, 0.0, 0, updatedAt = clock + 500)
                ),
            )
        )

        // Plank is a timed exercise now, but this row was logged as a rep and
        // has no duration to show; reading it any other way would invent one.
        val set = db.workoutDao().findSet("s1")!!
        assertEquals(ExerciseMetric.WEIGHT_REPS.name, set.metric)
        assertEquals(1, set.reps)
    }
}
