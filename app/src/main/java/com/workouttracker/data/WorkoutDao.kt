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
               COALESCE(SUM(s.reps * s.weightKg), 0.0) AS volume
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
               MAX(s.weightKg) AS bestWeight
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
               w.name AS workoutName, w.date AS workoutDate
        FROM exercise_sets s
        JOIN workouts w ON w.id = s.workoutId
        WHERE s.deleted = 0 AND w.deleted = 0 AND s.exercise = :exercise
        ORDER BY w.date DESC, s.position ASC
        """
    )
    fun observeSetsForExercise(exercise: String): Flow<List<SetWithSession>>

    // --- custom exercises ---

    @Query("SELECT * FROM custom_exercises WHERE deleted = 0 ORDER BY name ASC")
    fun observeCustomExercises(): Flow<List<CustomExercise>>

    @Query("SELECT * FROM custom_exercises WHERE id = :id")
    suspend fun findCustomExercise(id: String): CustomExercise?

    @Query("SELECT * FROM custom_exercises")
    suspend fun allCustomExercises(): List<CustomExercise>

    @Upsert
    suspend fun upsertCustomExercise(exercise: CustomExercise)
}
