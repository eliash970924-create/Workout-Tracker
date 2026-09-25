package com.workouttracker.ui

import com.workouttracker.data.ExerciseMetric
import com.workouttracker.data.SetEntry
import com.workouttracker.data.SetWithSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The "what's next" prompt is only as good as this ordering. */
class SessionExerciseTest {

    private fun set(id: String, exercise: String, position: Int, completed: Boolean = false) =
        SetEntry(
            id = id,
            workoutId = "w1",
            exercise = exercise,
            reps = 5,
            weightKg = 60.0,
            position = position,
            completed = completed,
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
    fun `what is left starts after this exercise, in session order`() {
        assertEquals(listOf("Barbell Row", "Plank"), remainingExercises(session, "Barbell Bench Press"))
    }

    @Test
    fun `what is left wraps round to an exercise that was skipped`() {
        // A busy machine: bench, skip the row, plank. When the plank is done,
        // the row is still waiting, and "nothing after this" would be wrong.
        assertEquals(
            listOf("Barbell Bench Press", "Barbell Row"),
            remainingExercises(session, "Plank"),
        )
    }

    @Test
    fun `skipping several and finishing the last one leads back to the first skipped`() {
        // A, B, C, D, E. A done; B and C skipped because the machines were
        // taken; D done; E, the last in the list, just finished.
        val sets = listOf(
            set("a", "A", 0, completed = true),
            set("b", "B", 1),
            set("c", "C", 2),
            set("d", "D", 3, completed = true),
            set("e", "E", 4, completed = true),
        )

        // Not "the session is over": B is next up, and C is offered alongside.
        assertEquals(listOf("B", "C"), remainingExercises(sets, "E"))
        // And the rest after E's last set says so, and opens B.
        assertEquals("Time for B.", restNext(sets, "E")?.message)
        assertEquals("B", restNext(sets, "E")?.exercise)
    }

    @Test
    fun `finished exercises are not suggested again`() {
        val sets = listOf(
            set("s1", "Barbell Bench Press", 0, completed = true),
            set("s2", "Barbell Row", 1, completed = true),
            set("s3", "Plank", 2),
        )

        // Did the row before the bench; the bench is done, and so is the row.
        assertEquals(listOf("Plank"), remainingExercises(sets, "Barbell Bench Press"))
    }

    @Test
    fun `when everything is done there is nothing left`() {
        val sets = listOf(
            set("s1", "Barbell Bench Press", 0, completed = true),
            set("s2", "Barbell Row", 1, completed = true),
        )

        assertEquals(emptyList<String>(), remainingExercises(sets, "Barbell Row"))
        assertEquals(emptyList<String>(), remainingExercises(emptyList(), "Barbell Row"))
    }

    @Test
    fun `mid-exercise, the rest is just the next set`() {
        val sets = listOf(
            set("s1", "Lateral Raise", 0, completed = true),
            set("s2", "Lateral Raise", 1),
            set("s3", "Barbell Row", 2),
        )

        // Null means the usual "time for your next set of lateral raises".
        assertNull(restNext(sets, "Lateral Raise"))
    }

    @Test
    fun `after the last set of an exercise, the rest leads to the next one`() {
        val sets = listOf(
            set("s1", "Lateral Raise", 0, completed = true),
            set("s2", "Lateral Raise", 1, completed = true),
            set("s3", "Barbell Row", 2),
        )

        val next = restNext(sets, "Lateral Raise")

        assertEquals("Time for Barbell Row.", next?.message)
        assertEquals("Barbell Row", next?.exercise)
    }

    @Test
    fun `after the last set of the session, the rest says so`() {
        val sets = listOf(
            set("s1", "Barbell Row", 0, completed = true),
            set("s2", "Lateral Raise", 1, completed = true),
        )

        val next = restNext(sets, "Lateral Raise")

        assertEquals("That was the last set of the session.", next?.message)
        // Tapping it opens the session rather than an exercise.
        assertNull(next?.exercise)
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
    fun `two sessions on one day are not one previous session`() {
        // Morning and evening: the card is the evening's alone.
        val previous = previousSession(
            listOf(
                past(30, 5, 100.0).copy(workoutId = "evening"),
                past(30, 5, 60.0).copy(workoutId = "morning"),
            )
        )

        assertEquals("evening", previous?.workoutId)
        assertEquals(listOf(100.0), previous?.sets?.map { it.weightKg })
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
