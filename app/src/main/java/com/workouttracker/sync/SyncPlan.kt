package com.workouttracker.sync

import com.workouttracker.data.Snapshot
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * What one round of sync needs to move, decided before anything is moved.
 *
 * Every round used to download the whole log and upload it again, changed or
 * not -- a log of a year's training is about 1.6 MB, and a sync every six
 * hours on mobile data made that hundreds of megabytes a month for nothing.
 * Now each side is only transferred when it has something the other lacks.
 */
data class SyncPlan(val download: Boolean, val upload: Boolean) {
    val nothingToDo: Boolean get() = !download && !upload
}

/**
 * Decides a round from two cheap facts: whether the file on Drive is still the
 * one this device last synced with (by the checksum Drive keeps of every file,
 * fetched in a few hundred bytes), and whether the local log still matches the
 * fingerprint it had then (worked out on the phone, without the network).
 *
 *  - Nothing on Drive yet: upload, and there is nothing to download.
 *  - Drive changed: another device synced. Download and merge, then upload the
 *    merged result, since that device has not seen this one's edits.
 *  - Only the phone changed: upload. No download -- the file on Drive is the
 *    one this device wrote or merged last time, so merging it would be a no-op.
 *  - Neither: nothing moves.
 *
 * A missing checksum on either side counts as changed. Being wrong that way
 * costs a transfer; being wrong the other way would lose an edit.
 */
fun planSync(
    remoteExists: Boolean,
    remoteChecksum: String?,
    lastRemoteChecksum: String?,
    localFingerprint: String,
    lastLocalFingerprint: String?,
): SyncPlan {
    if (!remoteExists) return SyncPlan(download = false, upload = true)
    val remoteChanged = remoteChecksum == null || remoteChecksum != lastRemoteChecksum
    if (remoteChanged) return SyncPlan(download = true, upload = true)
    val localChanged = localFingerprint != lastLocalFingerprint
    return SyncPlan(download = false, upload = localChanged)
}

/**
 * A fingerprint of the log's content, for telling whether it has changed since
 * the last sync.
 *
 * Content rather than a "something was edited" flag, so it cannot drift out of
 * step: an edit made mid-sync, a merge, a restore from a backup file -- all of
 * them change the content, whichever path they took to get there. The export
 * timestamp is left out, since it differs on every call and would make every
 * snapshot look new, and rows are put in a fixed order so the database handing
 * them back in a different one does not either.
 */
fun snapshotFingerprint(snapshot: Snapshot, json: Json): String {
    val canonical = snapshot.copy(
        exportedAt = 0,
        workouts = snapshot.workouts.sortedBy { it.id },
        sets = snapshot.sets.sortedBy { it.id },
        customExercises = snapshot.customExercises.sortedBy { it.id },
        exerciseSettings = snapshot.exerciseSettings.sortedBy { it.exercise },
    )
    val bytes = json.encodeToString(Snapshot.serializer(), canonical).toByteArray()
    return MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
