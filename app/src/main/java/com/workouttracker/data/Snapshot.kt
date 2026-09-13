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
) {
    companion object {
        /**
         * 1: workouts and sets.
         * 2: sets carry a muscle group, and custom exercises are included.
         */
        const val CURRENT_VERSION = 2
    }
}
