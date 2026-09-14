package com.workouttracker.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FormatTest {

    private val today = LocalDate.of(2026, 9, 13)

    @Test
    fun `today and yesterday are named`() {
        assertEquals("Today", formatDay(today.toEpochDay(), today))
        assertEquals("Yesterday", formatDay(today.minusDays(1).toEpochDay(), today))
    }

    @Test
    fun `older dates in this year omit the year`() {
        val formatted = formatDay(LocalDate.of(2026, 3, 2).toEpochDay(), today)
        assertEquals(false, formatted.contains("2026"))
    }

    @Test
    fun `dates in another year include it`() {
        val formatted = formatDay(LocalDate.of(2025, 3, 2).toEpochDay(), today)
        assertEquals(true, formatted.contains("2025"))
    }

    @Test
    fun `whole weights lose the decimal point`() {
        assertEquals("60", formatWeight(60.0))
        assertEquals("62.5", formatWeight(62.5))
    }

    @Test
    fun `volume switches to tonnes once it gets large`() {
        assertEquals("900 kg", formatVolume(900.0))
        assertEquals("1.5t", formatVolume(1500.0))
    }

    @Test
    fun `relative time covers the usual ranges`() {
        val now = 1_000_000_000L
        assertEquals("Never", formatRelativeTime(0, now))
        assertEquals("Just now", formatRelativeTime(now - 5_000, now))
        assertEquals("5 min ago", formatRelativeTime(now - 300_000, now))
        assertEquals("2 h ago", formatRelativeTime(now - 7_200_000, now))
    }

    @Test
    fun `the countdown always pads its seconds`() {
        assertEquals("1:30", formatCountdown(90))
        assertEquals("0:05", formatCountdown(5))
        assertEquals("3:00", formatCountdown(180))
        assertEquals("0:00", formatCountdown(0))
    }

    @Test
    fun `a countdown past zero reads zero rather than going negative`() {
        assertEquals("0:00", formatCountdown(-3))
    }
}
