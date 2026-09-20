package com.workouttracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The CSV export.
 *
 * This is the only copy of the log a person can actually open, so it is worth
 * pinning down: the wrong quoting or a swallowed column turns a backup into a
 * file that looks fine and is not.
 */
class CsvTest {

    private fun row(
        workoutId: String = "w1",
        date: LocalDate = LocalDate.of(2026, 9, 20),
        workoutName: String = "Push day",
        workoutNotes: String = "",
        exercise: String = "Barbell Bench Press",
        muscleGroup: MuscleGroup = MuscleGroup.CHEST,
        metric: ExerciseMetric = ExerciseMetric.WEIGHT_REPS,
        reps: Int = 5,
        weightKg: Double = 80.0,
        seconds: Int = 0,
        meters: Double = 0.0,
        completed: Boolean = true,
    ) = ExportRow(
        workoutId = workoutId,
        date = date.toEpochDay(),
        workoutName = workoutName,
        workoutNotes = workoutNotes,
        exercise = exercise,
        muscleGroup = muscleGroup.name,
        metric = metric.name,
        reps = reps,
        weightKg = weightKg,
        seconds = seconds,
        meters = meters,
        completed = completed,
    )

    /** The file without its byte order mark, split into lines. */
    private fun lines(csv: String): List<String> =
        csv.removePrefix("﻿").trimEnd('\r', '\n').split("\r\n")

    @Test
    fun `a set becomes a row of raw numbers`() {
        val csv = buildCsv(listOf(row()))

        assertEquals(
            listOf(
                "date,session,exercise,muscle_group,metric,set,reps,weight_kg," +
                    "seconds,meters,completed,session_notes",
                "2026-09-20,Push day,Barbell Bench Press,Chest,Weight & reps,1,5,80,0,0,yes,",
            ),
            lines(csv),
        )
    }

    @Test
    fun `cardio and holds carry their own numbers`() {
        val csv = buildCsv(
            listOf(
                row(
                    exercise = "Running",
                    muscleGroup = MuscleGroup.CARDIO,
                    metric = ExerciseMetric.DISTANCE_TIME,
                    reps = 0,
                    weightKg = 0.0,
                    seconds = 1500,
                    meters = 5200.0,
                ),
            )
        )

        // Raw, not "5.2 km in 25:00": a spreadsheet wants to sum these.
        assertTrue(lines(csv)[1].contains(",1500,5200,"))
        assertTrue(lines(csv)[1].contains("Distance & time"))
    }

    @Test
    fun `set numbers count within an exercise, and restart in the next session`() {
        val csv = buildCsv(
            listOf(
                row(workoutId = "w1", exercise = "Barbell Bench Press"),
                row(workoutId = "w1", exercise = "Barbell Bench Press"),
                row(workoutId = "w1", exercise = "Overhead Press"),
                row(workoutId = "w1", exercise = "Barbell Bench Press"),
                row(workoutId = "w2", exercise = "Barbell Bench Press"),
            )
        )

        // Column 6 is the set number. Bench goes 1, 2, then 3 even with another
        // exercise in between, and starts over in the next session.
        assertEquals(
            listOf("1", "2", "1", "3", "1"),
            lines(csv).drop(1).map { it.split(",")[5] },
        )
    }

    @Test
    fun `commas and quotes in a name cannot break the file open`() {
        val csv = buildCsv(
            listOf(
                row(
                    workoutName = "Push, pull, legs",
                    exercise = "Farmer's Walk",
                    workoutNotes = "felt \"strong\" today",
                ),
            )
        )

        val line = lines(csv)[1]
        assertTrue(line, line.contains("\"Push, pull, legs\""))
        // An apostrophe needs nothing; a double quote is doubled.
        assertTrue(line, line.contains("Farmer's Walk,"))
        assertTrue(line, line.endsWith("\"felt \"\"strong\"\" today\""))
    }

    @Test
    fun `a newline in the notes stays inside its own field`() {
        val csv = buildCsv(listOf(row(workoutNotes = "line one\nline two")))

        // Quoted, so the embedded break does not read as a new record: the
        // whole file is two logical rows however many lines it prints as.
        assertTrue(csv.contains("\"line one\nline two\""))
    }

    @Test
    fun `fractional weights keep their decimal and whole ones lose it`() {
        val csv = buildCsv(listOf(row(weightKg = 62.5), row(weightKg = 60.0)))

        assertEquals(listOf("62.5", "60"), lines(csv).drop(1).map { it.split(",")[7] })
    }

    @Test
    fun `an unfinished set is exported as not done rather than dropped`() {
        val csv = buildCsv(listOf(row(completed = false)))

        assertTrue(lines(csv)[1].endsWith(",no,"))
    }

    @Test
    fun `an empty log is still a valid file with its header`() {
        val csv = buildCsv(emptyList())

        assertEquals(1, lines(csv).size)
        assertTrue(csv.startsWith("﻿"))
    }
}
