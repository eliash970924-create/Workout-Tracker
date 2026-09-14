package com.workouttracker.ui

import com.workouttracker.data.SetEntry
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
}
