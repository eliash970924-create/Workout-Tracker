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
