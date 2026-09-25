package com.workouttracker.data

import kotlinx.serialization.Serializable

/** The whole database as one JSON document — what gets stored on Drive. */
@Serializable
data class Snapshot(
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long,
    val workouts: List<Workout>,
    val sets: List<SetEntry>,
    /**
     * User-created exercises. Defaulted so a version 1 snapshot, written before
     * custom exercises existed, still decodes.
     */
    val customExercises: List<CustomExercise> = emptyList(),
    /** Per-exercise overrides. Defaulted so older snapshots still decode. */
    val exerciseSettings: List<ExerciseSettings> = emptyList(),
) {
    companion object {
        /**
         * 1: workouts and sets.
         * 2: sets carry a muscle group, and custom exercises are included.
         * 3: sets can be ticked off as completed.
         * 4: exercises can carry their own rest length.
         * 5: sets say how they were measured, and carry a duration and a
         *    distance alongside reps and weight.
         * 6: sets can be grouped into supersets.
         */
        const val CURRENT_VERSION = 6
    }
}
