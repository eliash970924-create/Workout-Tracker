package com.workouttracker.data

import kotlinx.serialization.Serializable

/** The whole database as one JSON document — what gets stored on Drive. */
@Serializable
data class Snapshot(
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long,
    val workouts: List<Workout>,
    val sets: List<SetEntry>,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
