package com.workouttracker.data

import kotlinx.serialization.json.Json

/**
 * The whole database as one file, for exporting and restoring.
 *
 * This is the same [Snapshot] the Drive sync uses, so a backup and a sync are
 * interchangeable by construction rather than by a second format kept in step
 * by hand. It is written pretty-printed, because unlike the sync payload this
 * one lands somewhere a person might open it.
 *
 * Restoring merges rather than replaces: see [WorkoutRepository.merge]. A
 * stale backup therefore cannot quietly undo work done since it was taken.
 */
private val backupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

/** A backup written by a version of the app that knows more than this one. */
class BackupTooNewException(val version: Int) : Exception(
    "this backup was written by a newer version of the app (format $version)"
)

fun encodeBackup(snapshot: Snapshot): String =
    backupJson.encodeToString(Snapshot.serializer(), snapshot)

/**
 * Reads a backup file, refusing one from a future version outright.
 *
 * Unknown fields are ignored everywhere else in this app so that an older
 * install can still read a newer sync, but importing is different: quietly
 * dropping fields would write a lossy copy of the backup into the only log the
 * user has. Better to say so and leave the file alone.
 */
fun decodeBackup(text: String): Snapshot {
    // A file round-tripped through an editor or a cloud provider can come back
    // with a byte order mark on the front, which is not valid JSON.
    val snapshot = backupJson.decodeFromString(
        Snapshot.serializer(),
        text.removePrefix("﻿").trim(),
    )
    if (snapshot.version > Snapshot.CURRENT_VERSION) {
        throw BackupTooNewException(snapshot.version)
    }
    return snapshot
}
