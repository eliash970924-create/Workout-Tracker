package com.workouttracker.ui

import com.workouttracker.data.WorkoutSummary
import java.time.LocalDate

/** What a week amounted to. */
data class WeekSummary(
    val sessions: Int,
    val sets: Int,
    val volume: Double,
    val seconds: Int,
    val meters: Double,
) {
    val isEmpty: Boolean get() = sessions == 0
}

/** This week and the one before it, which is what makes either mean anything. */
data class WeekReview(val thisWeek: WeekSummary, val lastWeek: WeekSummary)

/**
 * The last two weeks of training, summed from the session list the Workouts
 * tab already has. No query of its own: the numbers are all in
 * [WorkoutSummary] and a week is a handful of rows.
 *
 * Weeks run Monday to Sunday, matching the rest of the app -- the default
 * session name already treats Saturday and Sunday as the weekend, which only
 * makes sense if the week starts on Monday.
 *
 * A session counts once it has a set in it. Tapping Log workout and then
 * getting distracted is not training, and having that inflate the count would
 * make the one number here worth less than nothing.
 */
fun weekReview(summaries: List<WorkoutSummary>, today: LocalDate = LocalDate.now()): WeekReview {
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong()).toEpochDay()
    return WeekReview(
        thisWeek = summarise(summaries, monday, monday + 6),
        lastWeek = summarise(summaries, monday - 7, monday - 1),
    )
}

private fun summarise(summaries: List<WorkoutSummary>, from: Long, to: Long): WeekSummary {
    val week = summaries.filter { it.date in from..to && it.setCount > 0 }
    return WeekSummary(
        sessions = week.size,
        sets = week.sumOf { it.setCount },
        volume = week.sumOf { it.volume },
        seconds = week.sumOf { it.totalSeconds },
        meters = week.sumOf { it.totalMeters },
    )
}

/** "3 sessions", or "1 session". */
fun describeSessions(count: Int): String = if (count == 1) "1 session" else "$count sessions"
