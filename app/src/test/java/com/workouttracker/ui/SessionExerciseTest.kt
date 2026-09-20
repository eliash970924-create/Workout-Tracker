package com.workouttracker.ui

import com.workouttracker.data.ExerciseMetric
import com.workouttracker.data.SetEntry
import com.workouttracker.data.SetWithSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The "what's next" prompt is only as good as this ordering. */
class SessionExerciseTest {

    private fun set(id: String, exercise: String, position: Int) = SetEntry(
        id = id,
        workoutId = "w1",
        exercise = exercise,
        reps = 5,
        weightKg = 60.0,
        position = position,
        updatedAt = 1,
    )

    private val session = listOf(
        set("s1", "Barbell Bench Press", 0),
        set("s2", "Barbell Bench Press", 1),
        set("s3", "Barbell Row", 2),
        set("s4", "Barbell Bench Press", 3),
        set("s5", "Plank", 4),
    )

    @Test
    fun `exercises are listed once, in the order they first appear`() {
        // Bench press comes back once despite a later set, and keeps its
        // original place rather than jumping to the end.
        assertEquals(
            listOf("Barbell Bench Press", "Barbell Row", "Plank"),
            exerciseOrder(session),
        )
    }

    @Test
    fun `the next exercise is the one after this in the session`() {
        assertEquals("Barbell Row", nextExercise(session, "Barbell Bench Press"))
        assertEquals("Plank", nextExercise(session, "Barbell Row"))
    }

    @Test
    fun `the last exercise has nothing after it`() {
        assertNull(nextExercise(session, "Plank"))
    }

    @Test
    fun `an exercise not in the session has no next`() {
        assertNull(nextExercise(session, "Deadlift"))
    }

    @Test
    fun `an empty session has no order and no next`() {
        assertEquals(emptyList<String>(), exerciseOrder(emptyList()))
        assertNull(nextExercise(emptyList(), "Barbell Bench Press"))
    }

    private fun past(day: Long, reps: Int, weightKg: Double) = SetWithSession(
        id = "$day-$reps-$weightKg",
        exercise = "Deadlift",
        reps = reps,
        weightKg = weightKg,
        position = 0,
        workoutName = "Pull",
        workoutDate = day,
    )

    @Test
    fun `the previous session is the newest one, whole`() {
        // The query returns newest first; only that session belongs on the card.
        val previous = previousSession(
            listOf(
                past(30, 5, 100.0),
                past(30, 5, 110.0),
                past(20, 5, 90.0),
            )
        )

        assertEquals(30L, previous?.date)
        assertEquals(listOf(100.0, 110.0), previous?.sets?.map { it.weightKg })
    }

    @Test
    fun `an exercise never trained before has no previous session`() {
        assertNull(previousSession(emptyList()))
    }

    @Test
    fun `sets are described as reps by weight`() {
        assertEquals(
            "5 × 100 kg, 3 × 110 kg",
            describeSets(listOf(past(30, 5, 100.0), past(30, 3, 110.0))),
        )
    }

    @Test
    fun `a set is described by the numbers it was logged with`() {
        fun timed(seconds: Int) = past(30, 0, 0.0)
            .copy(metric = ExerciseMetric.TIME.name, seconds = seconds)

        assertEquals("1:30", describeSet(timed(90)))
        assertEquals(
            "12 reps",
            describeSet(past(30, 12, 0.0).copy(metric = ExerciseMetric.REPS.name)),
        )
        assertEquals(
            "5.2 km in 25:00",
            describeSet(
                past(30, 0, 0.0).copy(
                    metric = ExerciseMetric.DISTANCE_TIME.name,
                    seconds = 1500,
                    meters = 5200.0,
                )
            ),
        )
    }

    @Test
    fun `a long session trails off rather than filling the card`() {
        val sets = (1..6).map { past(30, 5, 100.0) }

        assertEquals(
            "5 × 100 kg, 5 × 100 kg, 5 × 100 kg, 5 × 100 kg, …",
            describeSets(sets),
        )
    }
}
