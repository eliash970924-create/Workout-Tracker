package com.workouttracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseCatalogTest {

    @Test
    fun `every muscle group except Other offers exercises`() {
        val covered = ExerciseCatalog.all.map { it.muscleGroup }.toSet()
        val expected = MuscleGroup.entries.filter { it != MuscleGroup.OTHER }.toSet()

        assertEquals(expected, covered)
    }

    @Test
    fun `names are unique, since the name is the history key`() {
        val duplicates = ExerciseCatalog.all
            .groupBy { it.name.lowercase() }
            .filterValues { it.size > 1 }
            .keys

        assertTrue("duplicated exercise names: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `lookup by name is case and whitespace insensitive`() {
        assertEquals(MuscleGroup.CHEST, ExerciseCatalog.muscleGroupFor("Barbell Bench Press"))
        assertEquals(MuscleGroup.CHEST, ExerciseCatalog.muscleGroupFor("  barbell bench press "))
        assertEquals(MuscleGroup.BACK, ExerciseCatalog.muscleGroupFor("DEADLIFT"))
    }

    @Test
    fun `an unknown exercise has no catalogue group`() {
        assertNull(ExerciseCatalog.muscleGroupFor("Elias Special"))
    }

    @Test
    fun `stored muscle group names survive an unknown value`() {
        assertEquals(MuscleGroup.QUADS, MuscleGroup.of("QUADS"))
        assertEquals(MuscleGroup.OTHER, MuscleGroup.of("NOT_A_GROUP"))
        assertEquals(MuscleGroup.OTHER, MuscleGroup.of(null))
    }

    @Test
    fun `group names are stable identifiers, not display text`() {
        // Storage uses name(); display uses displayName. Conflating them would
        // silently recategorise every logged set on a relabel.
        assertEquals("FULL_BODY", MuscleGroup.FULL_BODY.name)
        assertEquals("Full body", MuscleGroup.FULL_BODY.displayName)
    }
}
