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
 *  1. pull the snapshot stored in the app data folder (if any),
 *  2. merge it into the local database, newest edit per row wins,
 *  3. push the merged result back.
 *
 * Because every row carries an `updatedAt` and deletes are tombstones, running
 * this on two devices in any order converges on the same data.
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

            val pulled = pullRemote(token)
            var merged = 0
            if (pulled != null) {
                val remote = runCatching { json.decodeFromString(Snapshot.serializer(), pulled.body) }.getOrNull()
                    ?: return@withLock fail(
                        SyncError(
                            "The backup on Drive is unreadable",
                            "It may have been written by a different app. Sync will keep failing " +
                                "until it is removed.",
                        ),
                        retryable = false,
                    )
                if (remote.version > Snapshot.CURRENT_VERSION) {
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
                merged = repository.merge(remote)
            }

            val payload = json.encodeToString(Snapshot.serializer(), repository.snapshot())
            val uploadedId = drive.upload(token, pulled?.fileId, payload)
            if (uploadedId.isNotEmpty()) prefs.backupFileId = uploadedId

            prefs.recordSuccess(now())
            Outcome.Success(merged)
        } catch (e: Exception) {
            // Network blips and expired tokens land here and are worth another
            // attempt; a missing backup file is not, since pullRemote already
            // re-looked it up and found nothing.
            fail(SyncErrors.fromException(e), retryable = e !is DriveHttpException || e.code != 404)
        } finally {
            prefs.setSyncing(false)
        }
    }

    private class Pulled(val fileId: String, val body: String)

    /** Downloads the existing backup, or returns null if there isn't one yet. */
    private suspend fun pullRemote(token: String): Pulled? {
        val cachedId = prefs.backupFileId
        if (cachedId != null) {
            try {
                return Pulled(cachedId, drive.download(token, cachedId))
            } catch (e: DriveHttpException) {
                // The cached id can outlive the file (user cleared app data on
                // Drive, restored a different account). Only a missing file is
                // worth a second look; anything else is a real error.
                if (e.code != 404) throw e
                prefs.backupFileId = null
            }
        }
        val foundId = drive.findBackupId(token) ?: return null
        prefs.backupFileId = foundId
        return Pulled(foundId, drive.download(token, foundId))
    }

    private fun fail(error: SyncError, retryable: Boolean): Outcome.Failed {
        prefs.recordError(error)
        return Outcome.Failed(error, retryable)
    }
}
