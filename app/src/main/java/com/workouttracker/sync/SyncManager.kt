package com.workouttracker.sync

import android.content.Context
import com.workouttracker.data.Snapshot
import com.workouttracker.data.WorkoutRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * One round of two-way sync with Google Drive:
 *
 *  1. ask Drive for the backup's checksum -- not its content -- and work out
 *     locally whether the log has changed since the last round ([planSync]),
 *  2. if Drive changed, pull the snapshot and merge it in, newest edit per row
 *     winning,
 *  3. if either side changed, push the merged result back.
 *
 * A round where nothing changed costs one small request rather than the whole
 * log each way. Because every row carries an `updatedAt` and deletes are
 * tombstones, running this on two devices in any order converges on the same
 * data.
 */
class SyncManager(
    private val context: Context,
    private val repository: WorkoutRepository,
    private val prefs: SyncPrefs,
    private val drive: DriveClient = DriveClient(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Guards against a manual "Sync now" racing the periodic worker. */
    private val mutex = Mutex()

    sealed interface Outcome {
        data class Success(val mergedRows: Int) : Outcome

        /** The user has never granted Drive access, or it was revoked. */
        data object NotConnected : Outcome

        data class Failed(val error: SyncError, val retryable: Boolean) : Outcome
    }

    suspend fun sync(): Outcome = mutex.withLock {
        prefs.setSyncing(true)
        try {
            val token = when (val auth = DriveAuth.authorize(context)) {
                is DriveAuth.Result.Authorized -> auth.accessToken
                is DriveAuth.Result.ConsentRequired -> {
                    // No UI available from a background worker; wait for the
                    // user to connect from Settings.
                    prefs.clearConnection()
                    return@withLock Outcome.NotConnected
                }
                is DriveAuth.Result.Failed -> return@withLock fail(auth.error, retryable = true)
            }
            prefs.setConnected(true)

            val remote = findRemote(token)
            val local = repository.snapshot()
            val plan = planSync(
                remoteExists = remote != null,
                remoteChecksum = remote?.md5,
                lastRemoteChecksum = prefs.lastRemoteChecksum,
                localFingerprint = snapshotFingerprint(local, json),
                lastLocalFingerprint = prefs.lastLocalFingerprint,
            )

            var merged = 0
            var result = local
            if (plan.download && remote != null) {
                val body = drive.download(token, remote.id)
                // Decompressed only if it is compressed: a backup written before
                // compression is plain JSON, and stays so until the next upload.
                val pulled = runCatching {
                    json.decodeFromString(Snapshot.serializer(), gunzipIfCompressed(body).decodeToString())
                }.getOrNull()
                    ?: return@withLock fail(
                        SyncError(
                            "The backup on Drive is unreadable",
                            "It may have been written by a different app. Sync will keep failing " +
                                "until it is removed.",
                        ),
                        retryable = false,
                    )
                if (pulled.version > Snapshot.CURRENT_VERSION) {
                    // Written by a newer install. Merging would silently drop
                    // whatever fields this version cannot parse, and uploading
                    // would overwrite them, so stop instead.
                    return@withLock fail(
                        SyncError(
                            "The backup was written by a newer version of the app",
                            "Update this device to sync again. Nothing has been overwritten.",
                        ),
                        retryable = false,
                    )
                }
                merged = repository.merge(pulled)
                result = repository.snapshot()
            }

            if (plan.upload) {
                val payload = compressVerified(
                    json.encodeToString(Snapshot.serializer(), result).toByteArray()
                )
                val uploaded = drive.upload(token, remote?.id, payload)
                if (uploaded.id.isNotEmpty()) prefs.backupFileId = uploaded.id
                prefs.lastRemoteChecksum = uploaded.md5
            }
            // Recorded only once the round has succeeded, so a failure part way
            // through leaves the last good state standing and the next round
            // tries again rather than concluding there is nothing to do.
            prefs.lastLocalFingerprint = snapshotFingerprint(result, json)

            prefs.recordSuccess(now())
            Outcome.Success(merged)
        } catch (e: Exception) {
            // Network blips and expired tokens land here and are worth another
            // attempt; a missing backup file is not, since findRemote already
            // re-looked it up and found nothing.
            fail(SyncErrors.fromException(e), retryable = e !is DriveHttpException || e.code != 404)
        } finally {
            prefs.setSyncing(false)
        }
    }

    /**
     * The backup on Drive and its checksum, or null if there isn't one yet.
     * Only metadata: the content is downloaded separately, and only when the
     * checksum says it changed.
     */
    private suspend fun findRemote(token: String): RemoteBackup? {
        val cachedId = prefs.backupFileId
        if (cachedId != null) {
            try {
                return RemoteBackup(cachedId, drive.checksum(token, cachedId))
            } catch (e: DriveHttpException) {
                // The cached id can outlive the file (user cleared app data on
                // Drive, restored a different account). Only a missing file is
                // worth a second look; anything else is a real error.
                if (e.code != 404) throw e
                prefs.backupFileId = null
            }
        }
        val found = drive.findBackup(token) ?: return null
        prefs.backupFileId = found.id
        return found
    }

    private fun fail(error: SyncError, retryable: Boolean): Outcome.Failed {
        prefs.recordError(error)
        return Outcome.Failed(error, retryable)
    }
}
