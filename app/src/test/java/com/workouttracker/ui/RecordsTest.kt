package com.workouttracker.ui

import com.workouttracker.data.ExerciseMetric
import com.workouttracker.data.SetWithSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Personal bests.
 *
 * A badge that appears when it should not is worse than no badge at all, so
 * what is pinned down here is mostly the cases that should *not* count: the
 * empty set, the repeat of a best, the untimed run, the planned set nobody did.
 */
class RecordsTest {

    private var next = 0

    /** Ids and positions run in creation order unless a test says otherwise. */
    private fun set(
        metric: ExerciseMetric = ExerciseMetric.WEIGHT_REPS,
        reps: Int = 0,
        weightKg: Double = 0.0,
        seconds: Int = 0,
        meters: Double = 0.0,
        day: Long = 100,
        position: Int? = null,
        completed: Boolean = true,
    ): SetWithSession {
        val index = next++
        return SetWithSession(
            id = "s$index",
            exercise = "Test",
            reps = reps,
            weightKg = weightKg,
            position = position ?: index,
            metric = metric.name,
            seconds = seconds,
            meters = meters,
            completed = completed,
            workoutName = "Session",
            workoutDate = day,
        )
    }

    private fun records(vararg sets: SetWithSession): Set<String> =
        recordIds(historyInOrder(sets.toList()).map { it.recordCandidate() })

    @Test
    fun `a heavier set is a record, a lighter one is not`() {
        val first = set(reps = 5, weightKg = 80.0)
        val heavier = set(reps = 5, weightKg = 85.0)
        val lighter = set(reps = 5, weightKg = 82.5)

        assertEquals(setOf(first.id, heavier.id), records(first, heavier, lighter))
    }

    @Test
    fun `at the same weight, more reps is a better set`() {
        val three = set(reps = 3, weightKg = 100.0)
        val five = set(reps = 5, weightKg = 100.0)
        val four = set(reps = 4, weightKg = 100.0)

        assertEquals(setOf(three.id, five.id), records(three, five, four))
    }

    @Test
    fun `repeating your best is not a new record`() {
        val first = set(reps = 5, weightKg = 100.0)
        val same = set(reps = 5, weightKg = 100.0)

        assertEquals(setOf(first.id), records(first, same))
    }

    @Test
    fun `bodyweight sets are counted in reps and holds in seconds`() {
        val eight = set(metric = ExerciseMetric.REPS, reps = 8)
        val twelve = set(metric = ExerciseMetric.REPS, reps = 12)
        val short = set(metric = ExerciseMetric.TIME, seconds = 60)
        val long = set(metric = ExerciseMetric.TIME, seconds = 90)

        val found = records(eight, twelve, short, long)

        // Each metric keeps its own best, so a 90 second plank does not have
        // to beat a set of twelve pull-ups to count.
        assertEquals(setOf(eight.id, twelve.id, short.id, long.id), found)
    }

    @Test
    fun `further is better, and at the same distance faster is`() {
        val threeK = set(metric = ExerciseMetric.DISTANCE_TIME, meters = 3000.0, seconds = 900)
        val fiveKSlow = set(metric = ExerciseMetric.DISTANCE_TIME, meters = 5000.0, seconds = 1560)
        val fiveKFast = set(metric = ExerciseMetric.DISTANCE_TIME, meters = 5000.0, seconds = 1440)
        val fiveKSlowAgain = set(metric = ExerciseMetric.DISTANCE_TIME, meters = 5000.0, seconds = 1500)

        assertEquals(
            setOf(threeK.id, fiveKSlow.id, fiveKFast.id),
            records(threeK, fiveKSlow, fiveKFast, fiveKSlowAgain),
        )
    }

    @Test
    fun `an untimed run does not beat a timed one over the same distance`() {
        val timed = set(metric = ExerciseMetric.DISTANCE_TIME, meters = 5000.0, seconds = 1440)
        val untimed = set(metric = ExerciseMetric.DISTANCE_TIME, meters = 5000.0, seconds = 0)

        // It is not a faster 5k, it is a 5k nobody timed.
        assertEquals(setOf(timed.id), records(timed, untimed))
    }

    @Test
    fun `a set with nothing in it is never a record`() {
        val empty = set(reps = 5, weightKg = 0.0)
        val noReps = set(metric = ExerciseMetric.REPS, reps = 0)
        val noTime = set(metric = ExerciseMetric.TIME, seconds = 0)
        val noDistance = set(metric = ExerciseMetric.DISTANCE_TIME, meters = 0.0, seconds = 600)

        assertTrue(records(empty, noReps, noTime, noDistance).isEmpty())
    }

    @Test
    fun `a set you planned but never ticked off cannot claim a record`() {
        val done = set(reps = 5, weightKg = 100.0)
        val planned = set(reps = 5, weightKg = 200.0, completed = false)

        assertEquals(setOf(done.id), records(done, planned))
    }

    @Test
    fun `an exercise that changed metric keeps a best of each`() {
        // Planks logged as reps before the metric existed, and as time since.
        val oldPlank = set(metric = ExerciseMetric.WEIGHT_REPS, reps = 1, weightKg = 20.0)
        val newPlank = set(metric = ExerciseMetric.TIME, seconds = 90)

        // Neither can beat the other, so both stand.
        assertEquals(setOf(oldPlank.id, newPlank.id), records(oldPlank, newPlank))
    }

    @Test
    fun `records are judged in the order the sets happened, not the order queried`() {
        // observeSetsForExercise hands back the newest session first.
        val older = set(reps = 5, weightKg = 100.0, day = 10, position = 0)
        val newer = set(reps = 5, weightKg = 90.0, day = 20, position = 0)

        // The 100 came first, so the later 90 is not a record. Sorting the
        // other way round would badge the 90 and miss the 100.
        assertEquals(setOf(older.id), records(newer, older))
    }

    @Test
    fun `within one session the earlier set is judged first`() {
        val first = set(reps = 5, weightKg = 100.0, day = 10, position = 0)
        val second = set(reps = 5, weightKg = 110.0, day = 10, position = 1)

        assertEquals(
            listOf(first.id, second.id),
            historyInOrder(listOf(second, first)).map { it.id },
        )
    }

    @Test
    fun `the first set of a new exercise is a record, because it is`() {
        val only = set(reps = 5, weightKg = 60.0)

        assertEquals(setOf(only.id), records(only))
    }
}
