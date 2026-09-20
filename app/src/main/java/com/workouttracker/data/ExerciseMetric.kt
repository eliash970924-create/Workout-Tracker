package com.workouttracker.data

/**
 * How an exercise is measured.
 *
 * Reps and kilos describe a bench press well and a stationary bike not at all,
 * so each exercise says which numbers it wants and the set row shows only
 * those. Stored by [name] on every set, never by ordinal, for the same reason
 * [MuscleGroup] is: a reordered enum must not silently rewrite the log.
 */
enum class ExerciseMetric(val displayName: String, val description: String) {
    WEIGHT_REPS("Weight & reps", "Reps at a weight, the usual for a lift."),
    REPS("Reps", "Just the count, for bodyweight work."),
    TIME("Time", "How long it was held or kept up."),
    DISTANCE_TIME("Distance & time", "How far, and how long it took.");

    val usesReps: Boolean get() = this == WEIGHT_REPS || this == REPS
    val usesWeight: Boolean get() = this == WEIGHT_REPS
    val usesSeconds: Boolean get() = this == TIME || this == DISTANCE_TIME
    val usesDistance: Boolean get() = this == DISTANCE_TIME

    companion object {
        /**
         * What an exercise is measured by unless something says otherwise.
         * Every set logged before metrics existed is one of these.
         */
        val DEFAULT = WEIGHT_REPS

        /** Unknown names fall back to [DEFAULT] rather than throwing. */
        fun of(stored: String?): ExerciseMetric =
            entries.firstOrNull { it.name == stored } ?: DEFAULT
    }
}
