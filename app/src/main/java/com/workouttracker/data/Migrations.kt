package com.workouttracker.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds muscle groups and user-created exercises.
 *
 * Sets logged before this version have no muscle group, so the migration
 * backfills them from [ExerciseCatalog] by name. Anything unrecognised — a
 * free-text exercise from the old add-exercise dialog — stays OTHER, which is
 * also where custom exercises without a chosen group land.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `exercise_sets` ADD COLUMN `muscleGroup` TEXT NOT NULL DEFAULT 'OTHER'"
        )
        db.execSQL(
            // Must match the entity exactly, defaults included: Room validates
            // the migrated schema on open and rejects any divergence. `deleted`
            // carries no default because CustomExercise.deleted declares none.
            "CREATE TABLE IF NOT EXISTS `custom_exercises` (" +
                "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `muscleGroup` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))" 
        )
        // Case-insensitive so "bench press" typed by hand matches the catalogue.
        for (exercise in ExerciseCatalog.all) {
            db.execSQL(
                "UPDATE exercise_sets SET muscleGroup = ? WHERE LOWER(exercise) = ?",
                arrayOf(exercise.muscleGroup.name, exercise.name.lowercase()),
            )
        }
    }
}

/**
 * Adds the per-set completion tick.
 *
 * The column has to be declared exactly as the entity does -- `DEFAULT 0` --
 * or Room rejects the migrated schema on open. Existing rows are then filled
 * in separately: a set logged before this version was written down after being
 * performed, so it reads as done rather than as an unfinished plan.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `exercise_sets` ADD COLUMN `completed` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE exercise_sets SET completed = 1")
    }
}

/**
 * Adds per-exercise settings.
 *
 * Nothing to backfill: an exercise with no row here simply uses the rest
 * length from Settings, which is what every exercise did before.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Must match the entity exactly, defaults included, or Room rejects the
        // migrated schema on open. `deleted` carries no default because
        // ExerciseSettings.deleted declares none.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `exercise_settings` (" +
                "`exercise` TEXT NOT NULL, `restSeconds` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
                "PRIMARY KEY(`exercise`))"
        )
    }
}

/**
 * Adds per-exercise metrics.
 *
 * Sets gain a metric of their own plus the two numbers the new ones need. The
 * metric is deliberately not backfilled from the catalogue the way muscle
 * groups were in [MIGRATION_1_2]: a plank logged before this version has a rep
 * count and no duration, so re-labelling it as timed would replace real data
 * with a zero. Everything already logged stays weight x reps and reads back
 * exactly as it was entered; only new sets follow the catalogue.
 *
 * `exercise_settings` is rebuilt rather than altered, because `restSeconds`
 * has to become nullable: rest and metric are independent overrides, and
 * clearing one must not clear the other.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Defaults must match the entity exactly -- Room validates the migrated
        // schema on open and rejects any divergence.
        db.execSQL(
            "ALTER TABLE `exercise_sets` ADD COLUMN `metric` TEXT NOT NULL DEFAULT 'WEIGHT_REPS'"
        )
        db.execSQL("ALTER TABLE `exercise_sets` ADD COLUMN `seconds` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `exercise_sets` ADD COLUMN `meters` REAL NOT NULL DEFAULT 0")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `exercise_settings_new` (" +
                "`exercise` TEXT NOT NULL, `restSeconds` INTEGER, `metric` TEXT, " +
                "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
                "PRIMARY KEY(`exercise`))"
        )
        db.execSQL(
            "INSERT INTO `exercise_settings_new` " +
                "(`exercise`, `restSeconds`, `metric`, `updatedAt`, `deleted`) " +
                "SELECT `exercise`, `restSeconds`, NULL, `updatedAt`, `deleted` " +
                "FROM `exercise_settings`"
        )
        db.execSQL("DROP TABLE `exercise_settings`")
        db.execSQL("ALTER TABLE `exercise_settings_new` RENAME TO `exercise_settings`")
    }
}

/**
 * Adds supersets: a nullable group id on each set.
 *
 * Nothing to backfill. Every exercise logged before this was done on its own,
 * which is exactly what a null means.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Nullable and with no default, matching the entity, or Room rejects the
        // migrated schema on open.
        db.execSQL("ALTER TABLE `exercise_sets` ADD COLUMN `supersetId` TEXT")
    }
}

/**
 * Adds notes on an exercise within a session.
 *
 * A new table and nothing to backfill: the session's own notes stay where they
 * were, on the workout.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Must match the entity exactly, or Room rejects the migrated schema on
        // open. `deleted` carries no default because ExerciseNote.deleted
        // declares none.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `exercise_notes` (" +
                "`id` TEXT NOT NULL, `workoutId` TEXT NOT NULL, `exercise` TEXT NOT NULL, " +
                "`text` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
    }
}
