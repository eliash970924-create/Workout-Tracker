package com.workouttracker.data

import java.time.LocalDate

/**
 * The log as a CSV file.
 *
 * The Drive backup lives in Drive's `appDataFolder`, which is private to the
 * app and cannot be browsed or opened by the person whose data it is. This is
 * the way out: one row per set, in a shape a spreadsheet understands.
 *
 * Numbers are written raw rather than formatted -- 80 and 900, not "80 kg" and
 * "15:00" -- because the point of a spreadsheet is to sum and chart them. The
 * metric column says which of the four number columns a row actually means.
 */
private val COLUMNS = listOf(
    "date",
    "session",
    "exercise",
    "muscle_group",
    "metric",
    "set",
    "reps",
    "weight_kg",
    "seconds",
    "meters",
    "completed",
    "session_notes",
)

/**
 * Excel reads a CSV as the system code page unless the file says otherwise, so
 * an exercise typed with an accent in it comes out as mojibake without this.
 * Sheets, LibreOffice and every CSV library strip it.
 */
private const val BOM = "﻿"

/** RFC 4180 says CRLF, which is also what keeps older Excel happy. */
private const val EOL = "\r\n"

fun buildCsv(rows: List<ExportRow>): String {
    val out = StringBuilder(BOM)
    out.append(COLUMNS.joinToString(",")).append(EOL)

    // Set numbers run per exercise within a session -- "3" meaning the third
    // set of bench press that day, which is how the app shows them and how you
    // would refer to one out loud.
    val counts = mutableMapOf<Pair<String, String>, Int>()
    for (row in rows) {
        val key = row.workoutId to row.exercise
        val setNumber = (counts[key] ?: 0) + 1
        counts[key] = setNumber
        out.append(
            listOf(
                LocalDate.ofEpochDay(row.date).toString(),
                escape(row.workoutName),
                escape(row.exercise),
                MuscleGroup.of(row.muscleGroup).displayName,
                ExerciseMetric.of(row.metric).displayName,
                setNumber.toString(),
                row.reps.toString(),
                number(row.weightKg),
                row.seconds.toString(),
                number(row.meters),
                if (row.completed) "yes" else "no",
                escape(row.workoutNotes),
            ).joinToString(",")
        ).append(EOL)
    }
    return out.toString()
}

/** 80.0 -> "80", 62.5 -> "62.5". Locale-independent, unlike String.format. */
private fun number(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/** Quoted only when it has to be, so the file stays readable by eye. */
private fun escape(field: String): String =
    if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + field.replace("\"", "\"\"") + "\""
    } else {
        field
    }
