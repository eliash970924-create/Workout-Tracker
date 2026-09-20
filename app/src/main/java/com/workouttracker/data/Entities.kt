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
    /**
     * [MuscleGroup] name, denormalised at log time. Kept on the row rather than
     * looked up from the exercise so history stays correctly grouped even if a
     * custom exercise is later deleted or recategorised.
     */
    @ColumnInfo(defaultValue = "OTHER")
    val muscleGroup: String = MuscleGroup.OTHER.name,
    /**
     * [ExerciseMetric] name, denormalised for the same reason [muscleGroup]
     * is, and with a second one of its own: the numbers below only make sense
     * read together with the metric that was in force when they were entered.
     * Re-reading it from the exercise would turn a logged 5 x 100 kg into a
     * 5 metre bike ride the moment the exercise was recategorised.
     */
    @ColumnInfo(defaultValue = "WEIGHT_REPS")
    val metric: String = ExerciseMetric.DEFAULT.name,
    /** Duration, for a timed hold or a cardio effort. */
    @ColumnInfo(defaultValue = "0")
    val seconds: Int = 0,
    /** Distance covered, in metres. Entered in kilometres, stored in metres
     * so a 400 m interval is a whole number rather than 0.4. */
    @ColumnInfo(defaultValue = "0")
    val meters: Double = 0.0,
    /**
     * Ticked off during the session. Sets are planned first and completed as
     * they are done, so a set can exist without having been performed yet.
     */
    @ColumnInfo(defaultValue = "0")
    val completed: Boolean = false,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/**
 * An exercise the user added themselves. Built-in exercises live in
 * [ExerciseCatalog] and are not stored, so this table holds only what is
 * genuinely per-user — and therefore worth syncing.
 */
@Serializable
@Entity(tableName = "custom_exercises")
data class CustomExercise(
    @PrimaryKey val id: String,
    val name: String,
    val muscleGroup: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/**
 * Per-exercise overrides: rest length, and how the exercise is measured.
 *
 * Keyed by the lower-cased exercise name rather than a UUID, because the
 * built-in exercises are not database rows and so have no id to hang this off.
 * A name key also converges better: two devices that set a rest for "Deadlift"
 * independently end up editing one row instead of creating two.
 *
 * Both fields are nullable and independent: null means "whatever the default
 * for this exercise is", so setting one does not commit the user to the other.
 * A row with both null carries nothing and is tombstoned instead.
 */
@Serializable
@Entity(tableName = "exercise_settings")
data class ExerciseSettings(
    /** Lower-cased, so the same lift typed two ways is one exercise. */
    @PrimaryKey val exercise: String,
    /** Null uses the app-wide rest length from Settings. */
    val restSeconds: Int? = null,
    /** [ExerciseMetric] name. Null uses the catalogue's choice for this exercise. */
    val metric: String? = null,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** List-screen projection: a workout plus its aggregates, computed in SQL. */
data class WorkoutSummary(
    val id: String,
    val date: Long,
    val name: String,
    @ColumnInfo(name = "setCount") val setCount: Int,
    /** Reps times weight, which only the weighted sets contribute to. */
    @ColumnInfo(name = "volume") val volume: Double,
    @ColumnInfo(name = "totalSeconds") val totalSeconds: Int,
    @ColumnInfo(name = "totalMeters") val totalMeters: Double,
)

/** One row per exercise ever logged, for the History tab. */
data class ExerciseHistoryEntry(
    val exercise: String,
    val muscleGroup: String,
    /**
     * The metric of the most recent set, not of all of them. An exercise
     * recategorised part-way through has sets of both kinds, and the one it is
     * measured by now is the one worth summarising it with.
     */
    val metric: String,
    @ColumnInfo(name = "setCount") val setCount: Int,
    @ColumnInfo(name = "lastPerformed") val lastPerformed: Long,
    @ColumnInfo(name = "bestWeight") val bestWeight: Double,
    @ColumnInfo(name = "bestReps") val bestReps: Int,
    @ColumnInfo(name = "bestSeconds") val bestSeconds: Int,
    @ColumnInfo(name = "bestMeters") val bestMeters: Double,
)

/**
 * An earlier session offered as a starting point for a new one.
 *
 * Assembled in the repository rather than projected straight out of SQL: the
 * exercise list wants to be in session order and de-duplicated, which is a
 * GROUP_CONCAT over an ordered subquery in SQLite and a one-liner in Kotlin.
 */
data class SessionTemplate(
    val id: String,
    val date: Long,
    val name: String,
    val setCount: Int,
    /** Exercise names in the order the session does them. */
    val exercises: List<String>,
)

/** A set together with the session it belongs to, for per-exercise history. */
data class SetWithSession(
    val id: String,
    val exercise: String,
    val reps: Int,
    val weightKg: Double,
    val position: Int,
    val metric: String = ExerciseMetric.DEFAULT.name,
    val seconds: Int = 0,
    val meters: Double = 0.0,
    @ColumnInfo(name = "workoutName") val workoutName: String,
    @ColumnInfo(name = "workoutDate") val workoutDate: Long,
)
