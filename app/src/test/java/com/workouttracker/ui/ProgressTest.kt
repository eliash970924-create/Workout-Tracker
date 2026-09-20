package com.workouttracker.ui

import com.workouttracker.data.ExerciseMetric
import com.workouttracker.data.SetWithSession
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressTest {

    private fun set(day: Long, reps: Int, weightKg: Double) = SetWithSession(
        id = "$day-$reps-$weightKg",
        exercise = "Bench press",
        reps = reps,
        weightKg = weightKg,
        position = 0,
        workoutName = "Push",
        workoutDate = day,
    )

    private fun cardio(day: Long, meters: Double, seconds: Int) = SetWithSession(
        id = "$day-$meters-$seconds",
        exercise = "Running",
        reps = 0,
        weightKg = 0.0,
        position = 0,
        metric = ExerciseMetric.DISTANCE_TIME.name,
        seconds = seconds,
        meters = meters,
        workoutName = "Cardio",
        workoutDate = day,
    )

    @Test
    fun `a session becomes one point, taking its heaviest set`() {
        val points = progressPoints(
            listOf(set(10, 5, 60.0), set(10, 5, 70.0), set(10, 3, 65.0)),
            ProgressMetric.TOP_SET,
        )

        assertEquals(1, points.size)
        assertEquals(70.0, points.single().value, 0.001)
    }

    @Test
    fun `volume sums reps times weight across the session`() {
        val points = progressPoints(
            listOf(set(10, 5, 60.0), set(10, 5, 70.0)),
            ProgressMetric.VOLUME,
        )

        assertEquals(650.0, points.single().value, 0.001)
    }

    @Test
    fun `points come back oldest first, whatever order the query gave them`() {
        // observeSetsForExercise returns newest session first, so the chart has
        // to reverse it or the line would run backwards.
        val points = progressPoints(
            listOf(set(30, 5, 80.0), set(10, 5, 60.0), set(20, 5, 70.0)),
            ProgressMetric.TOP_SET,
        )

        assertEquals(listOf(10L, 20L, 30L), points.map { it.epochDay })
        assertEquals(listOf(60.0, 70.0, 80.0), points.map { it.value })
    }

    @Test
    fun `no sets means no points rather than an empty chart`() {
        assertEquals(emptyList<ProgressPoint>(), progressPoints(emptyList(), ProgressMetric.TOP_SET))
    }

    @Test
    fun `each metric labels its own units`() {
        assertEquals("70 kg", ProgressMetric.TOP_SET.format(70.0))
        assertEquals("650 kg", ProgressMetric.VOLUME.format(650.0))
        assertEquals("1.2t", ProgressMetric.VOLUME.format(1200.0))
        assertEquals("12 reps", ProgressMetric.BEST_REPS.format(12.0))
        assertEquals("2:00", ProgressMetric.LONGEST.format(120.0))
        assertEquals("5.2 km", ProgressMetric.TOTAL_DISTANCE.format(5200.0))
    }

    @Test
    fun `distance and time sum and peak across the session`() {
        val session = listOf(cardio(10, 2000.0, 600), cardio(10, 3000.0, 840))

        assertEquals(5000.0, progressPoints(session, ProgressMetric.TOTAL_DISTANCE).single().value, 0.001)
        assertEquals(3000.0, progressPoints(session, ProgressMetric.FURTHEST).single().value, 0.001)
        assertEquals(1440.0, progressPoints(session, ProgressMetric.TOTAL_TIME).single().value, 0.001)
    }

    @Test
    fun `an exercise is only offered the charts its numbers support`() {
        // Volume is reps times weight, which a bike ride has neither of.
        assertEquals(
            listOf(ProgressMetric.TOP_SET, ProgressMetric.VOLUME),
            ProgressMetric.optionsFor(ExerciseMetric.WEIGHT_REPS),
        )
        assertEquals(
            listOf(ProgressMetric.LONGEST, ProgressMetric.TOTAL_TIME),
            ProgressMetric.optionsFor(ExerciseMetric.TIME),
        )
        // Whatever the exercise, there is always something to plot.
        ExerciseMetric.entries.forEach {
            assertEquals(true, ProgressMetric.optionsFor(it).isNotEmpty())
        }
    }
}
