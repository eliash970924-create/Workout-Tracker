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
    fun `cardio is offered, and asks for distance or time rather than kilos`() {
        val cardio = ExerciseCatalog.all.filter { it.muscleGroup == MuscleGroup.CARDIO }

        assertTrue("no cardio in the catalogue", cardio.isNotEmpty())
        val weighted = cardio.filter { it.metric == ExerciseMetric.WEIGHT_REPS }
        assertTrue("cardio measured in kilos: ${weighted.map { it.name }}", weighted.isEmpty())
        assertEquals(ExerciseMetric.DISTANCE_TIME, ExerciseCatalog.metricFor("Stationary Bike"))
        assertEquals(ExerciseMetric.TIME, ExerciseCatalog.metricFor("Jump Rope"))
    }

    @Test
    fun `holds are timed and bodyweight sets are counted`() {
        assertEquals(ExerciseMetric.TIME, ExerciseCatalog.metricFor("Plank"))
        assertEquals(ExerciseMetric.TIME, ExerciseCatalog.metricFor("Wall Sit"))
        assertEquals(ExerciseMetric.REPS, ExerciseCatalog.metricFor("Pull-Up"))
        // A lift is still a lift: the default has to stay the common case.
        assertEquals(ExerciseMetric.WEIGHT_REPS, ExerciseCatalog.metricFor("Deadlift"))
        assertNull(ExerciseCatalog.metricFor("Elias Special"))
    }

    @Test
    fun `stored metric names survive an unknown value`() {
        assertEquals(ExerciseMetric.TIME, ExerciseMetric.of("TIME"))
        assertEquals(ExerciseMetric.DEFAULT, ExerciseMetric.of("NOT_A_METRIC"))
        assertEquals(ExerciseMetric.DEFAULT, ExerciseMetric.of(null))
    }

    @Test
    fun `group names are stable identifiers, not display text`() {
        // Storage uses name(); display uses displayName. Conflating them would
        // silently recategorise every logged set on a relabel.
        assertEquals("FULL_BODY", MuscleGroup.FULL_BODY.name)
        assertEquals("Full body", MuscleGroup.FULL_BODY.displayName)
    }
}
