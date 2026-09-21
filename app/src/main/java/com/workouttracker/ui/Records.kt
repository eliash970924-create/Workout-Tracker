package com.workouttracker.ui

import com.workouttracker.data.ExerciseMetric
import com.workouttracker.data.SetEntry
import com.workouttracker.data.SetWithSession

/**
 * A set reduced to what decides a personal best.
 *
 * Two numbers rather than one, because "best" almost always has a tiebreak:
 * five reps at 100 kg is a better set than three at 100 kg, and 5 km in 24
 * minutes is a better run than 5 km in 26. Both are ordered so that bigger
 * wins, which is what lets one comparison serve all four metrics.
 */
data class RecordCandidate(
    val id: String,
    val metric: ExerciseMetric,
    val primary: Double,
    /** Only consulted when [primary] ties. */
    val tiebreak: Double,
)

/**
 * What a set is worth as a record, or null when it is not worth anything.
 *
 * A set with nothing in the number its metric cares about — a bench press at
 * no weight, a run of no distance — is never a record. "Your best 0 kg" is not
 * a thing, and without this the first empty row of a new exercise would claim
 * one.
 */
private fun candidate(
    id: String,
    metric: ExerciseMetric,
    reps: Int,
    weightKg: Double,
    seconds: Int,
    meters: Double,
): RecordCandidate? = when (metric) {
    ExerciseMetric.WEIGHT_REPS ->
        if (weightKg > 0) RecordCandidate(id, metric, weightKg, reps.toDouble()) else null
    ExerciseMetric.REPS ->
        if (reps > 0) RecordCandidate(id, metric, reps.toDouble(), 0.0) else null
    ExerciseMetric.TIME ->
        if (seconds > 0) RecordCandidate(id, metric, seconds.toDouble(), 0.0) else null
    ExerciseMetric.DISTANCE_TIME ->
        if (meters > 0) {
            RecordCandidate(
                id = id,
                metric = metric,
                primary = meters,
                // Less time is better, so negate it. A distance logged without
                // a time never wins a tie -- it is not a faster run, it is an
                // untimed one.
                tiebreak = if (seconds > 0) -seconds.toDouble() else Double.NEGATIVE_INFINITY,
            )
        } else {
            null
        }
}

fun SetEntry.recordCandidate(): RecordCandidate? =
    candidate(id, ExerciseMetric.of(metric), reps, weightKg, seconds, meters)

fun SetWithSession.recordCandidate(): RecordCandidate? =
    candidate(id, ExerciseMetric.of(metric), reps, weightKg, seconds, meters)

/**
 * The id of the best set for each metric, from [candidates] in the order they
 * happened, oldest first.
 *
 * The best, not every set that was ever the best. Marking the latter looks
 * reasonable written down and awful in a session: log 8 x 20 kg and then
 * 8 x 25 kg and both are "a personal best at the time", so both get the badge
 * even though only one of them is your best. A badge that says "personal best"
 * has to mean the set it is on is the best one, or it means nothing.
 *
 * Ties go to whoever got there first, so repeating your best does not move the
 * badge off the set that earned it.
 *
 * Bests are kept per metric. An exercise that was logged in kilos and is now
 * logged in minutes has a best of each, because comparing them would be
 * comparing nothing.
 */
fun bestSetIds(candidates: List<RecordCandidate?>): Set<String> {
    val best = mutableMapOf<ExerciseMetric, RecordCandidate>()
    for (candidate in candidates) {
        if (candidate == null) continue
        val standing = best[candidate.metric]
        if (standing == null || candidate.beats(standing)) best[candidate.metric] = candidate
    }
    return best.values.mapTo(mutableSetOf()) { it.id }
}

/** Strictly better, so repeating your best is not a new record. */
private fun RecordCandidate.beats(other: RecordCandidate): Boolean =
    primary > other.primary || (primary == other.primary && tiebreak > other.tiebreak)

/** Earlier sets of one exercise in the order they happened, oldest first. */
fun historyInOrder(sets: List<SetWithSession>): List<SetWithSession> =
    sets.filter { it.completed }
        .sortedWith(compareBy({ it.workoutDate }, { it.position }))
