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
fun formatWeight(kg: Double): String =
    if (kg % 1.0 == 0.0) kg.toLong().toString() else kg.toString()

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
