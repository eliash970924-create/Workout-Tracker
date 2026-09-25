package com.workouttracker.ui

import com.workouttracker.data.SetEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a session's sets read as supersets, and when a superset rests. */
class SupersetsTest {

    private var next = 0

    private fun set(exercise: String, superset: String? = null, completed: Boolean = false) =
        SetEntry(
            id = "s${next}",
            workoutId = "w1",
            exercise = exercise,
            reps = 5,
            weightKg = 60.0,
            position = next++,
            completed = completed,
            supersetId = superset,
            updatedAt = 1,
        )

    @Test
    fun `consecutive exercises sharing an id are one block`() {
        val sets = listOf(
            set("Squat"),
            set("Bench", "a"),
            set("Bench", "a"),
            set("Row", "a"),
            set("Row", "a"),
            set("Plank"),
        )

        assertEquals(
            listOf(
                Block.Single("Squat"),
                Block.Superset("a", listOf("Bench", "Row")),
                Block.Single("Plank"),
            ),
            blocksOf(sets),
        )
        assertEquals(listOf("Squat", "Bench", "Row", "Plank"), exercisesOf(blocksOf(sets)))
    }

    @Test
    fun `a superset of one is drawn as the exercise it is`() {
        val sets = listOf(set("Bench", "a"), set("Squat"))

        assertEquals(listOf(Block.Single("Bench"), Block.Single("Squat")), blocksOf(sets))
    }

    @Test
    fun `two runs of one id get distinct keys`() {
        // Nothing should write this, but a sync race could, and two items with
        // one key crash the list.
        val sets = listOf(set("A", "x"), set("B", "x"), set("C"), set("D", "x"), set("E", "x"))

        val keys = blocksOf(sets).map { it.key }
        assertEquals(keys.distinct(), keys)
    }

    @Test
    fun `a superset is named by its members`() {
        val sets = listOf(set("Bench", "a"), set("Row", "a"))

        assertEquals("Bench + Row", blockOf(sets, "Row").label)
        assertEquals(Block.Single("Squat"), blockOf(sets, "Squat"))
    }

    @Test
    fun `a round is finished once every member has had its turn`() {
        val members = listOf("Bench", "Row")
        val benchDone = listOf(
            set("Bench", "a", completed = true),
            set("Bench", "a"),
            set("Row", "a"),
            set("Row", "a"),
        )
        // Bench done, row still to go: no rest.
        assertFalse(completesRound(benchDone, members, "Bench"))

        val bothDone = benchDone.map { if (it.id == "s2") it.copy(completed = true) else it }
        assertTrue(completesRound(bothDone, members, "Row"))
    }

    @Test
    fun `the order the members are done in does not matter`() {
        val members = listOf("Bench", "Row")
        val rowFirst = listOf(
            set("Bench", "a"),
            set("Row", "a", completed = true),
        )
        assertFalse(completesRound(rowFirst, members, "Row"))
    }

    @Test
    fun `a member with fewer sets stops holding up the rounds it is not in`() {
        val members = listOf("Bench", "Row")
        val sets = listOf(
            set("Bench", "a", completed = true),
            set("Bench", "a", completed = true),
            set("Row", "a", completed = true),
        )
        // Round two of bench: row has none left, so the round is over.
        assertTrue(completesRound(sets, members, "Bench"))
    }

    @Test
    fun `an exercise on its own always rests`() {
        assertTrue(completesRound(listOf(set("Bench", completed = true)), listOf("Bench"), "Bench"))
    }

    @Test
    fun `the rest between rounds names the round`() {
        val sets = listOf(
            set("Bench", "a", completed = true),
            set("Bench", "a"),
            set("Row", "a", completed = true),
            set("Row", "a"),
            set("Plank"),
        )

        val next = restNext(sets, "Row")
        assertEquals("Next round: Bench + Row.", next?.message)
        assertEquals("Bench", next?.exercise)
    }

    @Test
    fun `the rest after a superset names what follows it`() {
        val sets = listOf(
            set("Squat"),
            set("Bench", "a", completed = true),
            set("Row", "a", completed = true),
            set("Curl", "b"),
            set("Pushdown", "b"),
        )

        val next = restNext(sets, "Row")
        assertEquals("Time for Curl + Pushdown.", next?.message)
        assertEquals("Curl", next?.exercise)
    }

    @Test
    fun `a finished superset at the end of the session says so`() {
        val sets = listOf(
            set("Bench", "a", completed = true),
            set("Row", "a", completed = true),
        )

        val next = restNext(sets, "Bench")
        assertEquals("That was the last set of the session.", next?.message)
        assertNull(next?.exercise)
    }
}
