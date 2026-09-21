package com.workouttracker.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {

    @Query(
        """
        SELECT w.id AS id, w.date AS date, w.name AS name,
               COUNT(s.id) AS setCount,
               COALESCE(SUM(s.reps * s.weightKg), 0.0) AS volume,
               COALESCE(SUM(s.seconds), 0) AS totalSeconds,
               COALESCE(SUM(s.meters), 0.0) AS totalMeters
        FROM workouts w
        LEFT JOIN exercise_sets s ON s.workoutId = w.id AND s.deleted = 0
        WHERE w.deleted = 0
        GROUP BY w.id
        ORDER BY w.date DESC, w.updatedAt DESC
        """
    )
    fun observeSummaries(): Flow<List<WorkoutSummary>>

    @Query("SELECT * FROM workouts WHERE id = :id AND deleted = 0")
    fun observeWorkout(id: String): Flow<Workout?>

    @Query("SELECT * FROM exercise_sets WHERE workoutId = :workoutId AND deleted = 0 ORDER BY position ASC")
    fun observeSets(workoutId: String): Flow<List<SetEntry>>

    /** Previously used exercise names, most recently used first, for suggestions. */
    @Query(
        """
        SELECT exercise FROM exercise_sets
        WHERE deleted = 0
        GROUP BY exercise
        ORDER BY MAX(updatedAt) DESC
        LIMIT 30
        """
    )
    fun observeExerciseNames(): Flow<List<String>>

    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun findWorkout(id: String): Workout?

    @Query("SELECT * FROM exercise_sets WHERE id = :id")
    suspend fun findSet(id: String): SetEntry?

    @Query("SELECT COALESCE(MAX(position), -1) FROM exercise_sets WHERE workoutId = :workoutId")
    suspend fun maxPosition(workoutId: String): Int

    @Query("SELECT * FROM exercise_sets WHERE workoutId = :workoutId AND exercise = :exercise AND deleted = 0 ORDER BY position DESC LIMIT 1")
    suspend fun lastSetOf(workoutId: String, exercise: String): SetEntry?

    /** Every live set of one workout, in session order. */
    @Query("SELECT * FROM exercise_sets WHERE workoutId = :workoutId AND deleted = 0 ORDER BY position ASC")
    suspend fun setsOf(workoutId: String): List<SetEntry>

    @Query(
        """
        SELECT * FROM exercise_sets
        WHERE workoutId = :workoutId AND exercise = :exercise AND deleted = 0
        ORDER BY position ASC
        """
    )
    suspend fun setsOfExerciseIn(workoutId: String, exercise: String): List<SetEntry>

    /** The workout this exercise was last done in, excluding the one in hand. */
    @Query(
        """
        SELECT s.workoutId FROM exercise_sets s
        JOIN workouts w ON w.id = s.workoutId
        WHERE s.exercise = :exercise AND s.deleted = 0 AND w.deleted = 0
          AND s.workoutId <> :excludeWorkoutId
        ORDER BY w.date DESC, w.updatedAt DESC
        LIMIT 1
        """
    )
    suspend fun lastWorkoutIdFor(exercise: String, excludeWorkoutId: String): String?

    /** Every earlier set of this exercise, newest session first, for the
     * "last time" summary shown while logging. */
    @Query(
        """
        SELECT s.id AS id, s.exercise AS exercise, s.reps AS reps,
               s.weightKg AS weightKg, s.position AS position,
               s.metric AS metric, s.seconds AS seconds, s.meters AS meters,
               s.completed AS completed,
               w.name AS workoutName, w.date AS workoutDate
        FROM exercise_sets s
        JOIN workouts w ON w.id = s.workoutId
        WHERE s.exercise = :exercise AND s.deleted = 0 AND w.deleted = 0
          AND s.workoutId <> :excludeWorkoutId
        ORDER BY w.date DESC, s.position ASC
        """
    )
    fun observePreviousSets(exercise: String, excludeWorkoutId: String): Flow<List<SetWithSession>>

    /**
     * Recent sessions that actually have something in them, newest first, for
     * offering as a starting point. The join drops empty sessions, which there
     * would be nothing to copy from.
     */
    @Query(
        """
        SELECT w.id FROM workouts w
        JOIN exercise_sets s ON s.workoutId = w.id AND s.deleted = 0
        WHERE w.deleted = 0 AND w.id <> :excludeWorkoutId
        GROUP BY w.id
        ORDER BY w.date DESC, w.updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun recentWorkoutIds(excludeWorkoutId: String, limit: Int): List<String>

    @Query("SELECT * FROM workouts WHERE id IN (:ids)")
    suspend fun workoutsByIds(ids: List<String>): List<Workout>

    @Query(
        """
        SELECT * FROM exercise_sets
        WHERE workoutId IN (:ids) AND deleted = 0
        ORDER BY position ASC
        """
    )
    suspend fun setsOfWorkouts(ids: List<String>): List<SetEntry>

    @Upsert
    suspend fun upsertWorkout(workout: Workout)

    @Upsert
    suspend fun upsertSet(set: SetEntry)

    @Query("UPDATE exercise_sets SET deleted = 1, updatedAt = :now WHERE workoutId = :workoutId AND deleted = 0")
    suspend fun tombstoneSetsOf(workoutId: String, now: Long)

    // --- sync: full state, tombstones included ---

    @Query("SELECT * FROM workouts")
    suspend fun allWorkouts(): List<Workout>

    @Query("SELECT * FROM exercise_sets")
    suspend fun allSets(): List<SetEntry>

    // --- history ---

    /** Every exercise ever logged, most recently performed first. */
    @Query(
        """
        SELECT s.exercise AS exercise,
               s.muscleGroup AS muscleGroup,
               COUNT(s.id) AS setCount,
               MAX(w.date) AS lastPerformed,
               MAX(s.weightKg) AS bestWeight,
               MAX(s.reps) AS bestReps,
               MAX(s.seconds) AS bestSeconds,
               MAX(s.meters) AS bestMeters,
               (SELECT s2.metric FROM exercise_sets s2
                JOIN workouts w2 ON w2.id = s2.workoutId
                WHERE s2.exercise = s.exercise AND s2.deleted = 0 AND w2.deleted = 0
                ORDER BY w2.date DESC, s2.updatedAt DESC LIMIT 1) AS metric
        FROM exercise_sets s
        JOIN workouts w ON w.id = s.workoutId
        WHERE s.deleted = 0 AND w.deleted = 0
        GROUP BY s.exercise
        ORDER BY lastPerformed DESC, s.exercise ASC
        """
    )
    fun observeExerciseHistory(): Flow<List<ExerciseHistoryEntry>>

    /** Every set logged for one exercise, newest session first. */
    @Query(
        """
        SELECT s.id AS id, s.exercise AS exercise, s.reps AS reps,
               s.weightKg AS weightKg, s.position AS position,
               s.metric AS metric, s.seconds AS seconds, s.meters AS meters,
               s.completed AS completed,
               w.name AS workoutName, w.date AS workoutDate
        FROM exercise_sets s
        JOIN workouts w ON w.id = s.workoutId
        WHERE s.deleted = 0 AND w.deleted = 0 AND s.exercise = :exercise
        ORDER BY w.date DESC, s.position ASC
        """
    )
    fun observeSetsForExercise(exercise: String): Flow<List<SetWithSession>>

    // --- export ---

    /**
     * Every live set with its session, oldest first, for the CSV export.
     *
     * Ordered by workout as well as by date so two sessions on the same day
     * come out one after the other rather than interleaved.
     */
    @Query(
        """
        SELECT s.workoutId AS workoutId, w.date AS date, w.name AS workoutName,
               w.notes AS workoutNotes, s.exercise AS exercise,
               s.muscleGroup AS muscleGroup, s.metric AS metric, s.reps AS reps,
               s.weightKg AS weightKg, s.seconds AS seconds, s.meters AS meters,
               s.completed AS completed
        FROM exercise_sets s
        JOIN workouts w ON w.id = s.workoutId
        WHERE s.deleted = 0 AND w.deleted = 0
        ORDER BY w.date ASC, s.workoutId ASC, s.position ASC
        """
    )
    suspend fun exportRows(): List<ExportRow>

    // --- custom exercises ---

    @Query("SELECT * FROM custom_exercises WHERE deleted = 0 ORDER BY name ASC")
    fun observeCustomExercises(): Flow<List<CustomExercise>>

    @Query("SELECT * FROM custom_exercises WHERE id = :id")
    suspend fun findCustomExercise(id: String): CustomExercise?

    @Query("SELECT * FROM custom_exercises")
    suspend fun allCustomExercises(): List<CustomExercise>

    @Upsert
    suspend fun upsertCustomExercise(exercise: CustomExercise)

    // --- per-exercise settings ---

    @Query("SELECT * FROM exercise_settings WHERE exercise = :exercise AND deleted = 0")
    fun observeExerciseSettings(exercise: String): Flow<ExerciseSettings?>

    @Query("SELECT * FROM exercise_settings WHERE exercise = :exercise AND deleted = 0")
    suspend fun findExerciseSettings(exercise: String): ExerciseSettings?

    @Query("SELECT * FROM exercise_settings WHERE exercise = :exercise")
    suspend fun findExerciseSettingsRow(exercise: String): ExerciseSettings?

    @Query("SELECT * FROM exercise_settings WHERE deleted = 0")
    fun observeAllExerciseSettings(): Flow<List<ExerciseSettings>>

    @Query("SELECT * FROM exercise_settings")
    suspend fun allExerciseSettings(): List<ExerciseSettings>

    @Upsert
    suspend fun upsertExerciseSettings(settings: ExerciseSettings)
}
