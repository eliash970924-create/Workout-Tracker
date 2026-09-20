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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The database on a phone is the only copy of the user's log until it reaches
 * Drive, so the migrations have to preserve it rather than start over.
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

    /** The same database four versions on, as MIGRATION_1_2 .. 3_4 leave it. */
    private val version4Schema = listOf(
        "CREATE TABLE IF NOT EXISTS `workouts` (" +
            "`id` TEXT NOT NULL, `date` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
            "`notes` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `exercise_sets` (" +
            "`id` TEXT NOT NULL, `workoutId` TEXT NOT NULL, `exercise` TEXT NOT NULL, " +
            "`reps` INTEGER NOT NULL, `weightKg` REAL NOT NULL, `position` INTEGER NOT NULL, " +
            "`muscleGroup` TEXT NOT NULL DEFAULT 'OTHER', " +
            "`completed` INTEGER NOT NULL DEFAULT 0, " +
            "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`workoutId`) REFERENCES `workouts`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_exercise_sets_workoutId` " +
            "ON `exercise_sets` (`workoutId`)",
        "CREATE TABLE IF NOT EXISTS `custom_exercises` (" +
            "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `muscleGroup` TEXT NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `exercise_settings` (" +
            "`exercise` TEXT NOT NULL, `restSeconds` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
            "PRIMARY KEY(`exercise`))",
    )

    @Test
    fun `sets logged before muscle groups survive, are classified, and read as done`() = runTest {
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

        // Opening with Room runs both migrations in turn, then validates the
        // schema against the entities.
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
            // Sets logged before the tick existed were written down after being
            // performed, so they read as done rather than as an unfinished plan.
            assertTrue(sets.getValue("s1").completed)
            assertTrue(sets.getValue("s2").completed)
            // Metrics are deliberately not backfilled from the catalogue: an
            // old row's numbers were typed as reps and kilos, and relabelling
            // it would read them as something they are not.
            assertEquals(ExerciseMetric.WEIGHT_REPS.name, sets.getValue("s1").metric)
            assertEquals(0, sets.getValue("s1").seconds)
            assertEquals(0.0, sets.getValue("s1").meters, 0.001)

            val summaries = db.workoutDao().observeSummaries().first()
            assertEquals(1, summaries.size)
            assertEquals("Chest day", summaries.single().name)
            assertEquals(2, summaries.single().setCount)

            // The new tables exist and are usable.
            assertEquals(emptyList<CustomExercise>(), db.workoutDao().allCustomExercises())
            assertEquals(emptyList<ExerciseSettings>(), db.workoutDao().allExerciseSettings())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * Rest lengths outlive the table being rebuilt.
     *
     * MIGRATION_4_5 cannot use ALTER TABLE, because `restSeconds` has to stop
     * being NOT NULL, so it copies the rows into a new table and swaps them
     * over. Losing a row there would quietly reset every per-exercise rest.
     */
    @Test
    fun `rest lengths survive the settings table being rebuilt`() = runTest {
        val name = "migration-4-5-test.db"
        context.deleteDatabase(name)
        createDatabase(name, version = 4, schema = version4Schema) { db ->
            db.execSQL(
                "INSERT INTO exercise_settings (exercise, restSeconds, updatedAt, deleted) " +
                    "VALUES ('deadlift', 210, 1000, 0)"
            )
            db.execSQL(
                "INSERT INTO exercise_settings (exercise, restSeconds, updatedAt, deleted) " +
                    "VALUES ('plank', 60, 1000, 1)"
            )
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_4_5)
            .build()
        try {
            val settings = db.workoutDao().allExerciseSettings().associateBy { it.exercise }

            assertEquals(2, settings.size)
            assertEquals(210, settings.getValue("deadlift").restSeconds)
            // No metric was chosen before this version, so none is claimed now.
            assertNull(settings.getValue("deadlift").metric)
            // Tombstones are rows too; dropping them would resurrect the
            // override on the next sync.
            assertTrue(settings.getValue("plank").deleted)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun createVersion1Database(name: String, populate: (SupportSQLiteDatabase) -> Unit) =
        createDatabase(name, version = 1, schema = version1Schema, populate = populate)

    private fun createDatabase(
        name: String,
        version: Int,
        schema: List<String>,
        populate: (SupportSQLiteDatabase) -> Unit,
    ) {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema.forEach(db::execSQL)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
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
