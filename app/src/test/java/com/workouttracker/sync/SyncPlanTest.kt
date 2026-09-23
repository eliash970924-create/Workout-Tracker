package com.workouttracker.sync

import com.workouttracker.data.SetEntry
import com.workouttracker.data.Snapshot
import com.workouttracker.data.Workout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deciding what a sync round moves.
 *
 * The saving is in the rounds where nothing happens; the risk is in the rounds
 * where something did and the plan says it did not. So the cases that must
 * never skip are pinned down as carefully as the one that should.
 */
class SyncPlanTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun plan(
        remoteExists: Boolean = true,
        remote: String? = "md5-A",
        lastRemote: String? = "md5-A",
        local: String = "fp-1",
        lastLocal: String? = "fp-1",
    ) = planSync(remoteExists, remote, lastRemote, local, lastLocal)

    @Test
    fun `when nothing changed on either side, nothing moves`() {
        // The round that used to download and upload the whole log for nothing.
        assertTrue(plan().nothingToDo)
    }

    @Test
    fun `an edit on the phone is uploaded without downloading anything`() {
        assertEquals(SyncPlan(download = false, upload = true), plan(local = "fp-2"))
    }

    @Test
    fun `another device having synced means download, merge and upload`() {
        // Upload too: that device has not seen this one's edits.
        assertEquals(SyncPlan(download = true, upload = true), plan(remote = "md5-B"))
    }

    @Test
    fun `the first sync ever uploads, with nothing to download`() {
        assertEquals(
            SyncPlan(download = false, upload = true),
            plan(remoteExists = false, remote = null, lastRemote = null, lastLocal = null),
        )
    }

    @Test
    fun `the first sync after this change downloads once, whatever the phone holds`() {
        // Nothing was recorded by the old code, so there is no telling what is
        // on Drive: treat it as changed rather than guess.
        assertEquals(
            SyncPlan(download = true, upload = true),
            plan(lastRemote = null, lastLocal = null),
        )
    }

    @Test
    fun `a checksum Drive did not report counts as changed`() {
        assertEquals(SyncPlan(download = true, upload = true), plan(remote = null))
    }

    private fun snapshot(
        exportedAt: Long = 1,
        reps: Int = 5,
        reversed: Boolean = false,
    ): Snapshot {
        val workouts = listOf(
            Workout("w1", 20000, "Push", updatedAt = 1),
            Workout("w2", 20001, "Pull", updatedAt = 1),
        )
        val sets = listOf(
            SetEntry("s1", "w1", "Barbell Bench Press", reps, 80.0, 0, updatedAt = 1),
            SetEntry("s2", "w2", "Barbell Row", 8, 60.0, 0, updatedAt = 1),
        )
        return Snapshot(
            exportedAt = exportedAt,
            workouts = if (reversed) workouts.reversed() else workouts,
            sets = if (reversed) sets.reversed() else sets,
        )
    }

    @Test
    fun `the fingerprint ignores when the snapshot was taken`() {
        // Otherwise every round would look like a change and upload again.
        assertEquals(
            snapshotFingerprint(snapshot(exportedAt = 1), json),
            snapshotFingerprint(snapshot(exportedAt = 999_999), json),
        )
    }

    @Test
    fun `the fingerprint ignores the order rows come back in`() {
        assertEquals(
            snapshotFingerprint(snapshot(), json),
            snapshotFingerprint(snapshot(reversed = true), json),
        )
    }

    @Test
    fun `the fingerprint changes when the log does`() {
        // One rep on one set is enough; missing this is the one way to lose an
        // edit, since the plan would then decide there is nothing to upload.
        assertNotEquals(
            snapshotFingerprint(snapshot(reps = 5), json),
            snapshotFingerprint(snapshot(reps = 6), json),
        )
    }

    @Test
    fun `a tombstone is a change too`() {
        val live = snapshot()
        val deleted = live.copy(sets = live.sets.map { if (it.id == "s1") it.copy(deleted = true) else it })

        assertNotEquals(snapshotFingerprint(live, json), snapshotFingerprint(deleted, json))
    }
}
