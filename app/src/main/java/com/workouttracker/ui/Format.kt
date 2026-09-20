package com.workouttracker.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
private val dayWithYearFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())

/** "Today" / "Yesterday" / "Mon 8 Sep", with the year once it stops being obvious. */
fun formatDay(epochDay: Long, today: LocalDate = LocalDate.now()): String {
    val date = LocalDate.ofEpochDay(epochDay)
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(if (date.year == today.year) dayFormat else dayWithYearFormat)
    }
}

/** Drops the decimal point for whole numbers: 60.0 -> "60", 62.5 -> "62.5". */
fun formatWeight(kg: Double): String = plain(kg)

private fun plain(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/**
 * "45 s", "1:30", "1:05:00" -- as long as it needs to be and no longer, so a
 * plank does not read as a marathon.
 */
fun formatDuration(seconds: Int): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val rest = total % 60
    return when {
        hours > 0 -> String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rest)
        minutes > 0 -> String.format(Locale.US, "%d:%02d", minutes, rest)
        else -> "$rest s"
    }
}

/**
 * Metres up to a kilometre, kilometres above it, to one decimal: 400 -> "400 m",
 * 5200 -> "5.2 km". Distances are stored in metres but read in kilometres.
 */
fun formatDistance(meters: Double): String = when {
    meters >= 1000 -> "${plain(Math.round(meters / 100.0) / 10.0)} km"
    else -> "${plain(meters)} m"
}

/** Metres as the kilometres the user typed, for putting back in the field. */
fun formatKilometres(meters: Double): String = plain(Math.round(meters) / 1000.0)

fun formatVolume(kg: Double): String = when {
    kg >= 1000 -> String.format(Locale.US, "%.1ft", kg / 1000)
    else -> "${formatWeight(kg)} kg"
}

/** Coarse "5 min ago" style text for the last successful sync. */
fun formatRelativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    if (millis <= 0L) return "Never"
    val seconds = (now - millis) / 1000
    return when {
        abs(seconds) < 60 -> "Just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} h ago"
        seconds < 604_800 -> "${seconds / 86_400} d ago"
        else -> Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(dayFormat)
    }
}

/** "1:30" for the rest countdown. */
fun formatCountdown(seconds: Int): String {
    val left = seconds.coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", left / 60, left % 60)
}
