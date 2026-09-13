package com.workouttracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A single training session.
 *
 * Ids are UUIDs rather than auto-increment integers so that rows created on two
 * different devices can never collide when they are merged during a sync.
 * [deleted] is a tombstone: rows are never physically removed, otherwise a
 * delete on one device would simply be re-created by the next sync.
 */
@Serializable
@Entity(tableName = "workouts")
data class Workout(
    @PrimaryKey val id: String,
    /** Calendar day of the session, as [java.time.LocalDate.toEpochDay]. */
    val date: Long,
    val name: String,
    val notes: String = "",
    /** Wall-clock millis of the last edit; drives last-write-wins merging. */
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** One logged set. Sets belonging to the same [exercise] are grouped in the UI. */
@Serializable
@Entity(
    tableName = "exercise_sets",
    foreignKeys = [
        ForeignKey(
            entity = Workout::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("workoutId")],
)
data class SetEntry(
    @PrimaryKey val id: String,
    val workoutId: String,
    val exercise: String,
    val reps: Int,
    val weightKg: Double,
    /** Ordering within the workout. */
    val position: Int,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** List-screen projection: a workout plus its aggregates, computed in SQL. */
data class WorkoutSummary(
    val id: String,
    val date: Long,
    val name: String,
    @ColumnInfo(name = "setCount") val setCount: Int,
    @ColumnInfo(name = "volume") val volume: Double,
)
