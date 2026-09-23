package com.workouttracker.sync

import com.workouttracker.data.CustomExercise
import com.workouttracker.data.ExerciseMetric
import com.workouttracker.data.ExerciseSettings
import com.workouttracker.data.SetEntry
import com.workouttracker.data.Snapshot
import com.workouttracker.data.Workout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Drive backup is compressed. The only acceptable outcome is getting back
 * exactly what went in, so that is what is tested: whole snapshots through the
 * same path a sync takes, the file already on Drive that predates compression,
 * and what a damaged file does.
 */
class CompressionTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** A log with one of everything a snapshot carries, awkward text included. */
    private fun everything() = Snapshot(
        exportedAt = 1_790_000_000_000,
        workouts = listOf(
            Workout("w1", 20000, "Push, pull \"legs\"", notes = "Bænkpres 💪\nnew line", updatedAt = 5),
            Workout("w2", 20001, "Deleted day", updatedAt = 6, deleted = true),
        ),
        sets = listOf(
            SetEntry("s1", "w1", "Barbell Bench Press", 5, 82.5, 0, muscleGroup = "CHEST", completed = true, updatedAt = 5),
            SetEntry(
                "s2", "w1", "Running", 0, 0.0, 1,
                muscleGroup = "CARDIO", metric = ExerciseMetric.DISTANCE_TIME.name,
                seconds = 1500, meters = 5234.0, updatedAt = 5,
            ),
            SetEntry("s3", "w2", "Plank", 0, 0.0, 0, metric = ExerciseMetric.TIME.name, seconds = 90, updatedAt = 6, deleted = true),
        ),
        customExercises = listOf(CustomExercise("c1", "Elias Spëcial", "CORE", updatedAt = 3)),
        exerciseSettings = listOf(
            ExerciseSettings("deadlift", restSeconds = 210, updatedAt = 4),
            ExerciseSettings("plank", metric = ExerciseMetric.TIME.name, updatedAt = 4),
        ),
    )

    /** Exactly what a sync does: encode, compress and verify; then download and decode. */
    private fun throughSync(snapshot: Snapshot): Snapshot {
        val uploaded = compressVerified(json.encodeToString(Snapshot.serializer(), snapshot).toByteArray())
        return json.decodeFromString(Snapshot.serializer(), gunzipIfCompressed(uploaded).decodeToString())
    }

    @Test
    fun `a whole log comes back from Drive exactly as it went up`() {
        val original = everything()

        // Data classes compare every field, so this is every row, every value,
        // tombstones, accents, emoji and quotes included.
        assertEquals(original, throughSync(original))
    }

    @Test
    fun `the bytes come back exactly, not just something that parses the same`() {
        val bytes = json.encodeToString(Snapshot.serializer(), everything()).toByteArray()

        assertArrayEquals(bytes, gunzipIfCompressed(compressVerified(bytes)))
    }

    @Test
    fun `the backup already on Drive, written before compression, still reads`() {
        // Uncompressed JSON, as every backup was until this change and as the
        // one on Drive stays until the next upload replaces it.
        val legacy = json.encodeToString(Snapshot.serializer(), everything()).toByteArray()

        assertFalse(isGzip(legacy))
        assertEquals(
            everything(),
            json.decodeFromString(Snapshot.serializer(), gunzipIfCompressed(legacy).decodeToString()),
        )
    }

    @Test
    fun `a backup that picked up a byte order mark is not mistaken for gzip`() {
        val withBom = "\uFEFF{}".toByteArray()

        assertFalse(isGzip(withBom))
        assertArrayEquals(withBom, gunzipIfCompressed(withBom))
    }

    @Test
    fun `a damaged file fails loudly rather than reading as something else`() {
        val damaged = gzip("{\"version\":5}".toByteArray()).copyOf(12)

        // A sync turns this into "the backup on Drive is unreadable" and stops
        // before uploading, so the damaged file is not replaced by an empty log.
        assertTrue(isGzip(damaged))
        assertThrows(Exception::class.java) { gunzipIfCompressed(damaged) }
    }

    @Test
    fun `a year of training goes up several times smaller`() {
        val sets = (0 until 5200).map { i ->
            SetEntry("set-$i-${i * 7919}", "w${i / 25}", "Barbell Bench Press", 5, 82.5, i % 25, updatedAt = 1_790_000_000_000 + i)
        }
        val workouts = (0 until 208).map { Workout("w$it", 20000L + it, "Push day", updatedAt = 1) }
        val raw = json.encodeToString(Snapshot.serializer(), Snapshot(exportedAt = 1, workouts = workouts, sets = sets)).toByteArray()

        val compressed = compressVerified(raw)

        assertTrue("only ${raw.size / compressed.size}x smaller", compressed.size * 4 < raw.size)
    }
}
