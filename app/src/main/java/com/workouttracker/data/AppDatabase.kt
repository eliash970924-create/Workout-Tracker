package com.workouttracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Workout::class, SetEntry::class, CustomExercise::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "workout-tracker.db")
                // Never destructive: this database is the only copy of the
                // user's log until it reaches Drive.
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
