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
