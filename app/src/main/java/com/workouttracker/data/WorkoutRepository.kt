package com.workouttracker.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID

/** Notified after every local write so a background sync can be scheduled. */
fun interface SyncTrigger {
    fun onLocalChange()
}

/**
 * The app's single entry point to stored data.
 *
 * Every mutation stamps [updatedAt] and pokes [syncTrigger]; deletes are
 * tombstones. Those two rules are what make the Drive sync converge, so they
 * live here rather than being repeated in each screen.
 */
class WorkoutRepository(
    private val db: AppDatabase,
    private val syncTrigger: SyncTrigger,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.workoutDao()

    fun observeSummaries(): Flow<List<WorkoutSummary>> = dao.observeSummaries()

    fun observeWorkout(id: String): Flow<Workout?> = dao.observeWorkout(id)

    fun observeSets(workoutId: String): Flow<List<SetEntry>> = dao.observeSets(workoutId)

    fun observeExerciseNames(): Flow<List<String>> = dao.observeExerciseNames()

    fun observeExerciseHistory(): Flow<List<ExerciseHistoryEntry>> = dao.observeExerciseHistory()

    fun observeSetsForExercise(exercise: String): Flow<List<SetWithSession>> =
        dao.observeSetsForExercise(exercise)

    fun observeCustomExercises(): Flow<List<CustomExercise>> = dao.observeCustomExercises()

    /** Earlier sets of [exercise], newest session first, ignoring the one in hand. */
    fun observePreviousSets(exercise: String, excludeWorkoutId: String): Flow<List<SetWithSession>> =
        dao.observePreviousSets(exercise, excludeWorkoutId)

    /** Adds a user-defined exercise. Returns the name as stored, trimmed. */
    suspend fun addCustomExercise(name: String, muscleGroup: MuscleGroup): String {
        val trimmed = name.trim()
        dao.upsertCustomExercise(
            CustomExercise(
                id = UUID.randomUUID().toString(),
                name = trimmed,
                muscleGroup = muscleGroup.name,
                updatedAt = now(),
            )
        )
        syncTrigger.onLocalChange()
        return trimmed
    }

    suspend fun deleteCustomExercise(id: String) {
        val existing = dao.findCustomExercise(id) ?: return
        // Tombstoned, not removed: sets already logged against it keep their
        // own denormalised muscle group, so history is unaffected.
        dao.upsertCustomExercise(existing.copy(deleted = true, updatedAt = now()))
        syncTrigger.onLocalChange()
    }

    suspend fun createWorkout(name: String, date: LocalDate = LocalDate.now()): String {
        val workout = Workout(
            id = UUID.randomUUID().toString(),
            date = date.toEpochDay(),
            name = name,
            updatedAt = now(),
        )
        dao.upsertWorkout(workout)
        syncTrigger.onLocalChange()
        return workout.id
    }

    suspend fun updateWorkout(workout: Workout) {
        dao.upsertWorkout(workout.copy(updatedAt = now()))
        syncTrigger.onLocalChange()
    }

    suspend fun deleteWorkout(id: String) {
        val workout = dao.findWorkout(id) ?: return
        val stamp = now()
        db.withTransaction {
            // Tombstone the sets too, so the delete survives a merge with a
            // device that still has them.
            dao.tombstoneSetsOf(id, stamp)
            dao.upsertWorkout(workout.copy(deleted = true, updatedAt = stamp))
        }
        syncTrigger.onLocalChange()
    }

    /**
     * Appends a set for [exercise], repeating the last set logged for it in
     * this workout. Whichever numbers the exercise's metric does not use stay
     * at zero, so a bike ride never carries a stray weight around.
     */
    suspend fun addSet(
        workoutId: String,
        exercise: String,
        muscleGroup: MuscleGroup? = null,
        reps: Int? = null,
        weightKg: Double? = null,
        seconds: Int? = null,
        meters: Double? = null,
    ) {
        val group = muscleGroup ?: resolveMuscleGroup(exercise)
        val metric = resolveMetric(exercise)
        // Only repeat a set that was measured the same way; one logged before
        // the exercise was recategorised has its numbers in other fields.
        val previous = dao.lastSetOf(workoutId, exercise)?.takeIf { it.metric == metric.name }
        val set = SetEntry(
            id = UUID.randomUUID().toString(),
            workoutId = workoutId,
            exercise = exercise,
            reps = if (metric.usesReps) reps ?: previous?.reps ?: 8 else 0,
            weightKg = if (metric.usesWeight) weightKg ?: previous?.weightKg ?: 0.0 else 0.0,
            position = dao.maxPosition(workoutId) + 1,
            muscleGroup = group.name,
            metric = metric.name,
            // A minute is a plank; a cardio effort has no useful guess, and an
            // empty field is a clearer prompt than a made-up number.
            seconds = when {
                !metric.usesSeconds -> 0
                metric == ExerciseMetric.TIME -> seconds ?: previous?.seconds ?: 60
                else -> seconds ?: previous?.seconds ?: 0
            },
            meters = if (metric.usesDistance) meters ?: previous?.meters ?: 0.0 else 0.0,
            updatedAt = now(),
        )
        dao.upsertSet(set)
        syncTrigger.onLocalChange()
    }

    /**
     * Best guess at an exercise's muscle group from its name: the built-in
     * catalogue first, then the user's own exercises. Used when a caller has a
     * name but no group, such as adding another set to an existing exercise.
     */
    private suspend fun resolveMuscleGroup(exercise: String): MuscleGroup {
        ExerciseCatalog.muscleGroupFor(exercise)?.let { return it }
        val custom = dao.allCustomExercises()
            .firstOrNull { !it.deleted && it.name.equals(exercise.trim(), ignoreCase = true) }
        return MuscleGroup.of(custom?.muscleGroup)
    }

    /**
     * How [exercise] is measured: the user's own choice if they made one, then
     * the catalogue, then weight and reps. A custom exercise records its metric
     * as an override too, so both come from the same place.
     */
    suspend fun resolveMetric(exercise: String): ExerciseMetric {
        dao.findExerciseSettings(exercise.key())?.metric?.let { return ExerciseMetric.of(it) }
        return ExerciseCatalog.metricFor(exercise) ?: ExerciseMetric.DEFAULT
    }

    suspend fun updateSet(set: SetEntry) {
        dao.upsertSet(set.copy(updatedAt = now()))
        syncTrigger.onLocalChange()
    }

    /** Ticks a set off, or un-ticks it. No write when nothing changes. */
    suspend fun setCompleted(id: String, completed: Boolean) {
        val set = dao.findSet(id) ?: return
        if (set.completed == completed) return
        dao.upsertSet(set.copy(completed = completed, updatedAt = now()))
        syncTrigger.onLocalChange()
    }

    suspend fun deleteSet(id: String) {
        val set = dao.findSet(id) ?: return
        dao.upsertSet(set.copy(deleted = true, updatedAt = now()))
        syncTrigger.onLocalChange()
    }

    /** Drops an exercise from a session, tombstoning all of its sets at once. */
    suspend fun deleteExercise(workoutId: String, exercise: String) {
        val sets = dao.setsOfExerciseIn(workoutId, exercise)
        if (sets.isEmpty()) return
        db.withTransaction {
            for (set in sets) dao.upsertSet(set.copy(deleted = true, updatedAt = now()))
        }
        syncTrigger.onLocalChange()
    }

    /**
     * Moves a set [delta] places within its own exercise by swapping positions
     * with the neighbour it passes. Other exercises are untouched, so a set
     * that is positionally interleaved with another exercise stays that way.
     */
    suspend fun moveSet(setId: String, delta: Int) {
        val set = dao.findSet(setId) ?: return
        val siblings = dao.setsOfExerciseIn(set.workoutId, set.exercise)
        val index = siblings.indexOfFirst { it.id == setId }
        val target = index + delta
        if (index < 0 || target !in siblings.indices) return
        val other = siblings[target]
        db.withTransaction {
            dao.upsertSet(set.copy(position = other.position, updatedAt = now()))
            dao.upsertSet(other.copy(position = set.position, updatedAt = now()))
        }
        syncTrigger.onLocalChange()
    }

    /**
     * Moves a whole exercise [delta] places in the session.
     *
     * Exercise order is "lowest position first", so this renumbers rather than
     * swaps: each exercise's sets end up contiguous and in the new order. A
     * session that had two exercises interleaved is tidied up as a side effect,
     * which matches how the session screen already presents them.
     */
    suspend fun moveExercise(workoutId: String, exercise: String, delta: Int) {
        val order = dao.setsOf(workoutId).map { it.exercise }.distinct().toMutableList()
        val index = order.indexOf(exercise)
        val target = index + delta
        if (index < 0 || target !in order.indices) return
        order.add(target, order.removeAt(index))
        db.withTransaction { renumber(workoutId, order) }
        syncTrigger.onLocalChange()
    }

    /**
     * Puts the session's exercises in [order]. Names not in the session are
     * ignored, and any the caller left out keep their places after the rest.
     *
     * Takes the whole order rather than a direction, because a drag knows where
     * something ended up and not how many places it passed on the way.
     */
    suspend fun reorderExercises(workoutId: String, order: List<String>) {
        db.withTransaction { renumber(workoutId, order) }
        syncTrigger.onLocalChange()
    }

    /**
     * Puts one exercise's sets in [orderedIds].
     *
     * The exercise keeps whatever position slots it already occupies and the
     * sets are dealt back into them, so a session where two exercises are
     * interleaved stays interleaved rather than being silently tidied.
     */
    suspend fun reorderSets(workoutId: String, exercise: String, orderedIds: List<String>) {
        val sets = dao.setsOfExerciseIn(workoutId, exercise)
        if (sets.size < 2) return
        val slots = sets.map { it.position }.sorted()
        val byId = sets.associateBy { it.id }
        db.withTransaction {
            orderedIds.forEachIndexed { index, id ->
                val set = byId[id] ?: return@forEachIndexed
                val slot = slots.getOrNull(index) ?: return@forEachIndexed
                if (set.position != slot) {
                    dao.upsertSet(set.copy(position = slot, updatedAt = now()))
                }
            }
        }
        syncTrigger.onLocalChange()
    }

    /**
     * Replaces this exercise's un-ticked sets with the ones from the last
     * session it was trained in. Ticked sets are what you actually did, so they
     * stay; only the plan is overwritten. Returns how many sets were copied.
     */
    suspend fun copyLastSession(workoutId: String, exercise: String): Int {
        val copied = db.withTransaction {
            val source = dao.lastWorkoutIdFor(exercise, workoutId)
                ?: return@withTransaction 0
            val template = dao.setsOfExerciseIn(source, exercise)
            if (template.isEmpty()) return@withTransaction 0

            // Captured before the deletes, so the exercise keeps its place in
            // the session instead of being renumbered to the end.
            val order = dao.setsOf(workoutId).map { it.exercise }.distinct()

            for (set in dao.setsOfExerciseIn(workoutId, exercise).filter { !it.completed }) {
                dao.upsertSet(set.copy(deleted = true, updatedAt = now()))
            }
            var position = dao.maxPosition(workoutId)
            for (set in template) {
                dao.upsertSet(
                    SetEntry(
                        id = UUID.randomUUID().toString(),
                        workoutId = workoutId,
                        exercise = exercise,
                        reps = set.reps,
                        weightKg = set.weightKg,
                        position = ++position,
                        muscleGroup = set.muscleGroup,
                        metric = set.metric,
                        seconds = set.seconds,
                        meters = set.meters,
                        updatedAt = now(),
                    )
                )
            }
            renumber(workoutId, order)
            template.size
        }
        if (copied > 0) syncTrigger.onLocalChange()
        return copied
    }

    /**
     * Renumbers a session so its exercises run in [order] and each one's sets
     * are contiguous. Only rows whose position actually moves are written, to
     * keep the sync delta to what genuinely changed.
     */
    private suspend fun renumber(workoutId: String, order: List<String>) {
        val byExercise = dao.setsOf(workoutId).groupBy { it.exercise }
        var position = 0
        // Anything absent from order -- an exercise added since it was taken --
        // keeps its sets, after the ones that were named.
        val names = order + byExercise.keys.filterNot { it in order }
        for (name in names.distinct()) {
            for (set in byExercise[name].orEmpty()) {
                if (set.position != position) {
                    dao.upsertSet(set.copy(position = position, updatedAt = now()))
                }
                position++
            }
        }
    }

    // --- per-exercise settings ---

    /** Rest length for [exercise], or null when it just uses the default. */
    fun observeRestSeconds(exercise: String): Flow<Int?> =
        dao.observeExerciseSettings(exercise.key()).map { it?.restSeconds }

    suspend fun restSecondsFor(exercise: String): Int? =
        dao.findExerciseSettings(exercise.key())?.restSeconds

    /** The metric [exercise] has been overridden to, or null for the default. */
    fun observeMetricOverride(exercise: String): Flow<ExerciseMetric?> =
        dao.observeExerciseSettings(exercise.key()).map { row ->
            row?.metric?.let(ExerciseMetric::of)
        }

    /**
     * Every exercise the user has overridden the metric of, keyed the same way
     * the rows are. The picker needs the whole map at once to label its list.
     */
    fun observeMetricOverrides(): Flow<Map<String, ExerciseMetric>> =
        dao.observeAllExerciseSettings().map { rows ->
            rows.mapNotNull { row -> row.metric?.let { row.exercise to ExerciseMetric.of(it) } }
                .toMap()
        }

    /**
     * Sets this exercise's own rest length, or clears it with null so it falls
     * back to the default.
     */
    suspend fun setRestSeconds(exercise: String, seconds: Int?) =
        updateSettings(exercise) { it.copy(restSeconds = seconds) }

    /**
     * Sets how [exercise] is measured, or clears it with null to go back to
     * the catalogue's choice.
     *
     * [retagWorkoutId], when given, re-measures that session's sets of this
     * exercise so the change is visible where it was made. Only that session:
     * sets logged in the past recorded real numbers under the old metric, and
     * relabelling them would turn 5 x 100 kg into a 5 metre bike ride.
     */
    suspend fun setMetric(
        exercise: String,
        metric: ExerciseMetric?,
        retagWorkoutId: String? = null,
    ) {
        updateSettings(exercise) { it.copy(metric = metric?.name) }
        if (retagWorkoutId == null) return
        val resolved = resolveMetric(exercise)
        db.withTransaction {
            for (set in dao.setsOfExerciseIn(retagWorkoutId, exercise)) {
                if (set.metric == resolved.name) continue
                dao.upsertSet(
                    set.copy(
                        metric = resolved.name,
                        // Numbers the new metric does not use are dropped
                        // rather than left to reappear if it is changed back.
                        reps = if (resolved.usesReps) set.reps else 0,
                        weightKg = if (resolved.usesWeight) set.weightKg else 0.0,
                        seconds = if (resolved.usesSeconds) set.seconds else 0,
                        meters = if (resolved.usesDistance) set.meters else 0.0,
                        updatedAt = now(),
                    )
                )
            }
        }
        syncTrigger.onLocalChange()
    }

    /**
     * Edits one exercise's override row, creating it on demand. A row left
     * carrying no overrides is tombstoned rather than deleted, so another
     * device does not re-create it on the next sync.
     */
    private suspend fun updateSettings(
        exercise: String,
        transform: (ExerciseSettings) -> ExerciseSettings,
    ) {
        val key = exercise.key()
        val existing = dao.findExerciseSettingsRow(key)
        val updated = transform(
            existing?.copy(deleted = false) ?: ExerciseSettings(exercise = key, updatedAt = 0)
        )
        val empty = updated.restSeconds == null && updated.metric == null
        // A tombstone for a row that never existed, or is already one, would be
        // pure sync noise.
        if (empty && (existing == null || existing.deleted)) return
        dao.upsertExerciseSettings(updated.copy(deleted = empty, updatedAt = now()))
        syncTrigger.onLocalChange()
    }

    /** Exercise names are user-typed; the key is what makes them one row. */
    private fun String.key(): String = trim().lowercase()

    // --- sync support ---

    suspend fun snapshot(): Snapshot = db.withTransaction {
        Snapshot(
            exportedAt = now(),
            workouts = dao.allWorkouts(),
            sets = dao.allSets(),
            customExercises = dao.allCustomExercises(),
            exerciseSettings = dao.allExerciseSettings(),
        )
    }

    /**
     * Merges a snapshot pulled from Drive into the local database, keeping
     * whichever copy of each row carries the newer [Workout.updatedAt]. Ties go
     * to the local copy. Returns how many rows the remote won.
     */
    suspend fun merge(snapshot: Snapshot): Int = db.withTransaction {
        var applied = 0
        for (remote in snapshot.workouts) {
            val local = dao.findWorkout(remote.id)
            if (local == null || local.updatedAt < remote.updatedAt) {
                dao.upsertWorkout(remote)
                applied++
            }
        }
        // A snapshot written before sets could be ticked off carries no flag,
        // and everything in it was logged after being performed. Taking the
        // field default instead would silently un-tick those sets here.
        val remoteSets =
            if (snapshot.version < 3) snapshot.sets.map { it.copy(completed = true) }
            else snapshot.sets
        for (remote in remoteSets) {
            // Skip orphans: a set whose workout exists neither locally nor in
            // the snapshot would violate the foreign key.
            if (dao.findWorkout(remote.workoutId) == null) continue
            val local = dao.findSet(remote.id)
            if (local == null || local.updatedAt < remote.updatedAt) {
                dao.upsertSet(remote)
                applied++
            }
        }
        for (remote in snapshot.customExercises) {
            val local = dao.findCustomExercise(remote.id)
            if (local == null || local.updatedAt < remote.updatedAt) {
                dao.upsertCustomExercise(remote)
                applied++
            }
        }
        for (remote in snapshot.exerciseSettings) {
            val local = dao.findExerciseSettingsRow(remote.exercise)
            if (local == null || local.updatedAt < remote.updatedAt) {
                dao.upsertExerciseSettings(remote)
                applied++
            }
        }
        applied
    }
}
