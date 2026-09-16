package com.workouttracker.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backup written by an older install is already sitting in Drive, so version 1
 * snapshots have to keep decoding. Losing this would not fail loudly — it would
 * quietly refuse to restore a real backup.
 */
class SnapshotCompatibilityTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val version1Json = """
        {
          "version": 1,
          "exportedAt": 1700000000000,
          "workouts": [
            {"id":"w1","date":20000,"name":"Leg day","notes":"","updatedAt":5,"deleted":false}
          ],
          "sets": [
            {"id":"s1","workoutId":"w1","exercise":"Back Squat","reps":5,
             "weightKg":100.0,"position":0,"updatedAt":5,"deleted":false}
          ]
        }
    """.trimIndent()

    @Test
    fun `a version 1 snapshot decodes, with defaults for the new fields`() {
        val snapshot = json.decodeFromString(Snapshot.serializer(), version1Json)

        assertEquals(1, snapshot.version)
        assertEquals("Back Squat", snapshot.sets.single().exercise)
        // No muscle group was recorded back then.
        assertEquals(MuscleGroup.OTHER.name, snapshot.sets.single().muscleGroup)
        assertTrue(snapshot.customExercises.isEmpty())
        assertTrue(snapshot.exerciseSettings.isEmpty())
        // The field default is false; merge is what decides an old snapshot's
        // sets were done, because only merge knows the snapshot's version.
        assertFalse(snapshot.sets.single().completed)
    }

    @Test
    fun `a snapshot of the current version round-trips`() {
        val original = Snapshot(
            exportedAt = 1,
            workouts = listOf(Workout("w1", 20000, "Push", updatedAt = 1)),
            sets = listOf(
                SetEntry(
                    "s1", "w1", "Barbell Bench Press", 5, 80.0, 0,
                    muscleGroup = MuscleGroup.CHEST.name, updatedAt = 1,
                )
            ),
            customExercises = listOf(
                CustomExercise("c1", "Elias Special", MuscleGroup.CORE.name, updatedAt = 1)
            ),
            exerciseSettings = listOf(
                ExerciseSettings("deadlift", restSeconds = 210, updatedAt = 1)
            ),
        )

        val decoded = json.decodeFromString(
            Snapshot.serializer(),
            json.encodeToString(Snapshot.serializer(), original),
        )

        assertEquals(original, decoded)
        assertEquals(Snapshot.CURRENT_VERSION, decoded.version)
    }

    @Test
    fun `unknown fields from a future version are ignored rather than fatal`() {
        val withExtras = version1Json.replaceFirst(
            "\"version\": 1,",
            "\"version\": 1, \"somethingNew\": {\"a\": 1},",
        )

        val snapshot = json.decodeFromString(Snapshot.serializer(), withExtras)

        assertEquals(1, snapshot.workouts.size)
    }
}
