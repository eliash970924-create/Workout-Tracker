package com.workouttracker.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The database on a phone is the only copy of the user's log until it reaches
 * Drive, so the 1 -> 2 migration has to preserve it rather than start over.
 *
 * A version 1 database is built here from the DDL Room generated for it, rather
 * than via MigrationTestHelper, because no version 1 schema JSON was ever
 * committed. Opening the result with Room is what validates the migration:
 * Room compares the migrated schema against the entities and throws if they
 * diverge, so a wrong column type or a stray DEFAULT fails this test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val version1Schema = listOf(
        "CREATE TABLE IF NOT EXISTS `workouts` (" +
            "`id` TEXT NOT NULL, `date` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
            "`notes` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `exercise_sets` (" +
            "`id` TEXT NOT NULL, `workoutId` TEXT NOT NULL, `exercise` TEXT NOT NULL, " +
            "`reps` INTEGER NOT NULL, `weightKg` REAL NOT NULL, `position` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`workoutId`) REFERENCES `workouts`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_exercise_sets_workoutId` " +
            "ON `exercise_sets` (`workoutId`)",
    )

    @Test
    fun `sets logged before muscle groups survive and are classified`() = runTest {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        createVersion1Database(name) { db ->
            db.execSQL(
                "INSERT INTO workouts (id, date, name, notes, updatedAt, deleted) " +
                    "VALUES ('w1', 20000, 'Chest day', 'felt strong', 1000, 0)"
            )
            // One exercise from the catalogue, one the user typed by hand back
            // when the add-exercise dialog was free text.
            db.execSQL(
                "INSERT INTO exercise_sets " +
                    "(id, workoutId, exercise, reps, weightKg, position, updatedAt, deleted) " +
                    "VALUES ('s1', 'w1', 'Barbell Bench Press', 5, 80.0, 0, 1000, 0)"
            )
            db.execSQL(
                "INSERT INTO exercise_sets " +
                    "(id, workoutId, exercise, reps, weightKg, position, updatedAt, deleted) " +
                    "VALUES ('s2', 'w1', 'Elias Special', 8, 20.0, 1, 1000, 0)"
            )
        }

        // Opening with Room runs MIGRATION_1_2 and then validates the schema.
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2)
            .build()
        try {
            val sets = db.workoutDao().allSets().associateBy { it.id }

            assertEquals(2, sets.size)
            // Backfilled from the catalogue by name.
            assertEquals(MuscleGroup.CHEST.name, sets.getValue("s1").muscleGroup)
            // An unrecognised name stays OTHER rather than being guessed at.
            assertEquals(MuscleGroup.OTHER.name, sets.getValue("s2").muscleGroup)
            // The training data itself is untouched.
            assertEquals(80.0, sets.getValue("s1").weightKg, 0.001)
            assertEquals(5, sets.getValue("s1").reps)

            val summaries = db.workoutDao().observeSummaries().first()
            assertEquals(1, summaries.size)
            assertEquals("Chest day", summaries.single().name)
            assertEquals(2, summaries.single().setCount)

            // The new table exists and is usable.
            assertEquals(emptyList<CustomExercise>(), db.workoutDao().allCustomExercises())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun createVersion1Database(name: String, populate: (SupportSQLiteDatabase) -> Unit) {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    version1Schema.forEach(db::execSQL)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        try {
            populate(helper.writableDatabase)
        } finally {
            helper.close()
        }
    }
}
