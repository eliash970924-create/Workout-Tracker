package com.workouttracker.ui

import com.workouttracker.data.WorkoutSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The week card.
 *
 * Its whole job is one honest number, so the week boundary has to be exact:
 * a session counted into the wrong week makes both weeks wrong at once.
 */
class WeekSummaryTest {

    // A Thursday, so there is room either side of "today" inside the week.
    private val today = LocalDate.of(2026, 9, 17)
    private val monday = LocalDate.of(2026, 9, 14)

    private var next = 0

    private fun session(
        date: LocalDate,
        setCount: Int = 3,
        volume: Double = 1000.0,
        seconds: Int = 0,
        meters: Double = 0.0,
    ) = WorkoutSummary(
        id = "w${next++}",
        date = date.toEpochDay(),
        name = "Session",
        setCount = setCount,
        volume = volume,
        totalSeconds = seconds,
        totalMeters = meters,
    )

    @Test
    fun `the week runs Monday to Sunday inclusive`() {
        val review = weekReview(
            listOf(
                session(monday),
                session(today),
                session(monday.plusDays(6)), // Sunday
            ),
            today,
        )

        assertEquals(3, review.thisWeek.sessions)
    }

    @Test
    fun `the Sunday before and the Monday after belong to other weeks`() {
        val review = weekReview(
            listOf(
                session(monday.minusDays(1)), // last Sunday
                session(today),
                session(monday.plusDays(7)), // next Monday
            ),
            today,
        )

        assertEquals(1, review.thisWeek.sessions)
        assertEquals(1, review.lastWeek.sessions)
    }

    @Test
    fun `a Monday counts as the start of its own week, not the end of the last`() {
        // The off-by-one that would put every Monday session in the wrong week.
        val review = weekReview(listOf(session(monday)), today = monday)

        assertEquals(1, review.thisWeek.sessions)
        assertTrue(review.lastWeek.isEmpty)
    }

    @Test
    fun `a Sunday session still counts on the Sunday`() {
        val sunday = monday.plusDays(6)
        val review = weekReview(listOf(session(sunday)), today = sunday)

        assertEquals(1, review.thisWeek.sessions)
    }

    @Test
    fun `last week is the seven days before, and nothing older`() {
        val review = weekReview(
            listOf(
                session(monday.minusDays(7)), // last Monday
                session(monday.minusDays(1)), // last Sunday
                session(monday.minusDays(8)), // the Sunday before that
            ),
            today,
        )

        assertEquals(2, review.lastWeek.sessions)
        assertTrue(review.thisWeek.isEmpty)
    }

    @Test
    fun `a session you started and never logged anything in does not count`() {
        val review = weekReview(
            listOf(session(today, setCount = 0), session(today, setCount = 3)),
            today,
        )

        // Tapping Log workout and getting distracted is not training.
        assertEquals(1, review.thisWeek.sessions)
        assertEquals(3, review.thisWeek.sets)
    }

    @Test
    fun `totals add up across the week, in each unit`() {
        val review = weekReview(
            listOf(
                session(monday, setCount = 4, volume = 2500.0),
                session(today, setCount = 1, volume = 0.0, seconds = 1500, meters = 5000.0),
            ),
            today,
        )

        assertEquals(2, review.thisWeek.sessions)
        assertEquals(5, review.thisWeek.sets)
        assertEquals(2500.0, review.thisWeek.volume, 0.001)
        assertEquals(1500, review.thisWeek.seconds)
        assertEquals(5000.0, review.thisWeek.meters, 0.001)
    }

    @Test
    fun `a week with nothing in it is empty rather than a row of zeroes`() {
        val review = weekReview(emptyList(), today)

        assertTrue(review.thisWeek.isEmpty)
        assertTrue(review.lastWeek.isEmpty)
        assertFalse(weekReview(listOf(session(today)), today).thisWeek.isEmpty)
    }

    @Test
    fun `the totals line leaves out what the week did not include`() {
        assertEquals("2.5t", describeTotals(2500.0, 0.0, 0))
        assertEquals("5 km · 25:00", describeTotals(0.0, 5000.0, 1500))
        assertEquals("900 kg · 5 km · 25:00", describeTotals(900.0, 5000.0, 1500))
        // A week of nothing says nothing rather than "0 kg".
        assertEquals("", describeTotals(0.0, 0.0, 0))
    }

    @Test
    fun `one session is not plural`() {
        assertEquals("1 session", describeSessions(1))
        assertEquals("3 sessions", describeSessions(3))
        assertEquals("0 sessions", describeSessions(0))
    }
}
