package com.workouttracker.ui

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.workouttracker.data.RestOverride
import com.workouttracker.data.Snapshot
import com.workouttracker.data.WorkoutRepository
import com.workouttracker.data.buildCsv
import com.workouttracker.data.decodeBackup
import com.workouttracker.data.encodeBackup
import com.workouttracker.rest.LiveUpdates
import com.workouttracker.rest.REST_PRESETS
import com.workouttracker.rest.RestPrefs
import com.workouttracker.rest.RestSettings
import com.workouttracker.sync.DriveAuth
import com.workouttracker.sync.SyncInterval
import com.workouttracker.sync.SyncManager
import com.workouttracker.sync.SyncPrefs
import com.workouttracker.sync.SyncScheduler
import com.workouttracker.sync.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate

/** What came of the last export, for the line under the button. */
sealed interface ExportResult {
    data class Saved(val message: String) : ExportResult
    data class Failed(val reason: String) : ExportResult
}

/**
 * Where an import has got to. [Pending] is the pause between reading the file
 * and touching the log: importing writes into the only copy of the user's
 * training history, so it happens on a second tap, not on a file pick.
 */
sealed interface ImportState {
    data class Pending(val snapshot: Snapshot, val sessions: Int, val sets: Int) : ImportState
    data class Merged(val applied: Int) : ImportState
    data class Failed(val reason: String) : ImportState
}

/**
 * A backup is a few hundred kilobytes. This is not a real limit so much as a
 * refusal to read an arbitrary file the picker handed us into memory.
 */
private const val MAX_BACKUP_BYTES = 32 * 1024 * 1024

class SettingsViewModel(
    private val context: Context,
    private val prefs: SyncPrefs,
    private val restPrefs: RestPrefs,
    private val syncManager: SyncManager,
    private val repository: WorkoutRepository,
) : ViewModel() {

    val state: StateFlow<SyncState> = prefs.state

    private val _csv = MutableStateFlow<ExportResult?>(null)
    private val _backup = MutableStateFlow<ExportResult?>(null)

    /** Null until the matching export has been tried in this sitting. */
    val csv: StateFlow<ExportResult?> = _csv.asStateFlow()
    val backup: StateFlow<ExportResult?> = _backup.asStateFlow()

    private val _import = MutableStateFlow<ImportState?>(null)

    /** Null until a backup has been opened in this sitting. */
    val importState: StateFlow<ImportState?> = _import.asStateFlow()

    /** The name offered in the file picker, dated so exports do not collide. */
    fun exportFileName(): String = "workout-log-${LocalDate.now()}.csv"

    /**
     * Writes the whole log to [uri], which the system file picker has already
     * created wherever the user chose to put it.
     *
     * Everything is caught: a picker can hand back a location that has since
     * gone away, a full disk, or a provider that refuses the write, and none of
     * those are worth crashing over when the answer is "it did not save".
     */
    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            _csv.value = try {
                val rows = repository.exportRows()
                val csv = buildCsv(rows)
                withContext(Dispatchers.IO) { write(uri, csv) }
                ExportResult.Saved(
                    if (rows.size == 1) "Exported 1 set." else "Exported ${rows.size} sets."
                )
            } catch (e: Exception) {
                ExportResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** The name offered for a backup file, dated like the CSV one. */
    fun backupFileName(): String = "workout-tracker-backup-${LocalDate.now()}.json"

    /** Writes the whole database to [uri] in the format the sync uses. */
    fun backupTo(uri: Uri) {
        viewModelScope.launch {
            _backup.value = try {
                val snapshot = repository.snapshot()
                val text = encodeBackup(snapshot)
                withContext(Dispatchers.IO) { write(uri, text) }
                ExportResult.Saved(
                    "Backed up ${snapshot.workouts.count { !it.deleted }} sessions " +
                        "and ${snapshot.sets.count { !it.deleted }} sets."
                )
            } catch (e: Exception) {
                ExportResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Reads a backup and works out what it holds, without applying any of it.
     * The user confirms from there.
     */
    fun readBackup(uri: Uri) {
        viewModelScope.launch {
            _import.value = try {
                val text = withContext(Dispatchers.IO) { read(uri) }
                val snapshot = decodeBackup(text)
                ImportState.Pending(
                    snapshot = snapshot,
                    sessions = snapshot.workouts.count { !it.deleted },
                    sets = snapshot.sets.count { !it.deleted },
                )
            } catch (e: Exception) {
                ImportState.Failed(e.message ?: "that file is not a backup")
            }
        }
    }

    fun confirmImport() {
        val pending = _import.value as? ImportState.Pending ?: return
        viewModelScope.launch {
            _import.value = try {
                val applied = repository.merge(pending.snapshot)
                // merge writes straight to the database rather than through the
                // sync trigger, so without this an import would sit on the
                // phone until some unrelated edit pushed it.
                if (applied > 0 && prefs.state.value.connected) syncManager.sync()
                ImportState.Merged(applied)
            } catch (e: Exception) {
                ImportState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelImport() {
        _import.value = null
    }

    private fun write(uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("that location could not be opened")
    }

    private fun read(uri: Uri): String {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("that file could not be opened")
        return stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > MAX_BACKUP_BYTES) {
                    throw IOException("that file is too large to be a backup")
                }
                out.write(buffer, 0, count)
            }
            out.toByteArray().decodeToString()
        }
    }

    val rest: StateFlow<RestSettings> = restPrefs.state

    fun setRestEnabled(enabled: Boolean) = restPrefs.setEnabled(enabled)

    fun setRestSeconds(seconds: Int) = restPrefs.setSeconds(seconds)

    /** Exercises that have a rest length of their own, so they can be seen. */
    val restOverrides: StateFlow<List<RestOverride>> = repository.observeRestOverrides()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearRestFor(exercise: String) {
        viewModelScope.launch { repository.setRestSeconds(exercise, null) }
    }

    fun setAutoSync(enabled: Boolean) {
        prefs.setAutoSync(enabled)
        SyncScheduler.applySettings(context, prefs.state.value)
    }

    fun setInterval(interval: SyncInterval) {
        prefs.setInterval(interval)
        SyncScheduler.applySettings(context, prefs.state.value)
    }

    /** Asks for Drive access, surfacing Google's consent screen when needed. */
    fun connect(onConsentRequired: (PendingIntent) -> Unit) {
        viewModelScope.launch {
            when (val result = DriveAuth.authorize(context)) {
                is DriveAuth.Result.Authorized -> {
                    prefs.setConnected(true)
                    syncNow()
                }
                is DriveAuth.Result.ConsentRequired -> onConsentRequired(result.pendingIntent)
                is DriveAuth.Result.Failed -> prefs.recordError(result.error)
            }
        }
    }

    fun onConsentResult(granted: Boolean, data: Intent?) {
        if (granted && DriveAuth.tokenFromConsentResult(context, data) != null) {
            prefs.setConnected(true)
            syncNow()
        } else {
            prefs.recordError(DriveAuth.describeConsentFailure(context, data))
        }
    }

    fun syncNow() {
        viewModelScope.launch { syncManager.sync() }
    }
}

/** A line of feedback under a button, in red when something went wrong. */
@Composable
private fun Note(text: String, error: Boolean = false) {
    Spacer(Modifier.height(8.dp))
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val viewModel = appViewModel { app ->
        SettingsViewModel(app, app.syncPrefs, app.restPrefs, app.syncManager, app.repository)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rest by viewModel.rest.collectAsStateWithLifecycle()
    val csv by viewModel.csv.collectAsStateWithLifecycle()
    val backup by viewModel.backup.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val restOverrides by viewModel.restOverrides.collectAsStateWithLifecycle()
    var showCustomRest by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onConsentResult(result.resultCode == Activity.RESULT_OK, result.data)
    }

    // The system picker rather than a share sheet: the file is written where
    // the user chose to put it -- Drive, Downloads, wherever -- which is the
    // whole point of an export. It also means no FileProvider to configure.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let(viewModel::exportTo) }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::backupTo) }

    // Deliberately unfiltered. A backup that has been through a file manager
    // or a cloud provider can come back tagged text/plain or octet-stream, and
    // a picker that greys out the file you came for is worse than one that
    // shows too much. What was actually picked is checked on read.
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::readBackup) }

    // Asked for when the timer is switched on rather than at launch, so the
    // prompt arrives attached to the feature that needs it. Refusing it costs
    // only the notification: the countdown and the buzz still work.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Rest timer", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Starts counting down as soon as you log a set, and buzzes " +
                                    "when the rest is up.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Switch(
                            checked = rest.enabled,
                            onCheckedChange = { enabled ->
                                viewModel.setRestEnabled(enabled)
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                        )
                    }
                    if (rest.enabled && !LiveUpdates.allowed(context)) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "The countdown can also ride along on your lock screen " +
                                "and in the status bar, but live updates are switched " +
                                "off for this app. Turn them on under Live updates.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(LiveUpdates.settingsIntent(context)) }
                        }) {
                            Text("Open notification settings")
                        }
                    }
                    if (rest.enabled) {
                        Spacer(Modifier.height(12.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            REST_PRESETS.forEach { seconds ->
                                FilterChip(
                                    selected = rest.seconds == seconds,
                                    onClick = { viewModel.setRestSeconds(seconds) },
                                    label = { Text(formatCountdown(seconds)) },
                                )
                            }
                            // Shows the chosen length once it is not a preset,
                            // so the chip row always says what is set.
                            val custom = rest.seconds !in REST_PRESETS
                            FilterChip(
                                selected = custom,
                                onClick = { showCustomRest = true },
                                label = {
                                    Text(
                                        if (custom) formatCountdown(rest.seconds) else "Custom…"
                                    )
                                },
                            )
                        }
                        // Per-exercise rests were only visible from inside the
                        // exercise that had one, which made them easy to set
                        // and then forget about.
                        if (restOverrides.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "These exercises have their own",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            restOverrides.forEach { override ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        override.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        formatCountdown(override.seconds),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    IconButton(
                                        onClick = { viewModel.clearRestFor(override.exercise) },
                                    ) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription =
                                                "Use the default for ${override.name}",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Google Drive", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Your workouts are copied to a private folder in your Drive that " +
                            "only this app can see. Install the app on another phone, connect " +
                            "the same account, and your sessions follow you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (state.connected) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Connected", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Button(onClick = {
                            viewModel.connect { pendingIntent ->
                                consentLauncher.launch(
                                    IntentSenderRequest.Builder(pendingIntent).build()
                                )
                            }
                        }) {
                            Text("Connect Google Drive")
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto sync", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Syncs in the background, and a few minutes after you log something.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Switch(
                            checked = state.autoSyncEnabled,
                            onCheckedChange = viewModel::setAutoSync,
                        )
                    }
                    if (state.autoSyncEnabled) {
                        Spacer(Modifier.height(12.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SyncInterval.entries.forEach { interval ->
                                FilterChip(
                                    selected = state.interval == interval,
                                    onClick = { viewModel.setInterval(interval) },
                                    label = { Text(interval.label) },
                                )
                            }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Your log", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Every set you have logged, as a spreadsheet. The Drive " +
                            "backup lives in a private folder you cannot browse, " +
                            "so this is how to read your own data outside the app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { exportLauncher.launch(viewModel.exportFileName()) },
                    ) {
                        Text("Export as CSV")
                    }
                    when (val result = csv) {
                        null -> Unit
                        is ExportResult.Saved -> Note(result.message)
                        is ExportResult.Failed ->
                            Note("Could not export: ${result.reason}", error = true)
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Backup and restore", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "A backup file is the whole database in the format the " +
                            "sync uses. Nothing to look at — that is what the CSV " +
                            "is for — but it is what a restore reads. Restoring " +
                            "merges it into your log rather than replacing it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { backupLauncher.launch(viewModel.backupFileName()) },
                        ) {
                            Text("Export backup")
                        }
                        OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }) {
                            Text("Restore from backup")
                        }
                    }
                    when (val result = backup) {
                        null -> Unit
                        is ExportResult.Saved -> Note(result.message)
                        is ExportResult.Failed ->
                            Note("Could not export: ${result.reason}", error = true)
                    }
                    when (val result = importState) {
                        null, is ImportState.Pending -> Unit
                        is ImportState.Merged -> Note(
                            if (result.applied == 0) {
                                "Nothing new — your log already had everything in that backup."
                            } else {
                                "Restored ${result.applied} changes into your log."
                            }
                        )
                        is ImportState.Failed -> Note("Could not restore: ${result.reason}", error = true)
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Last synced: ${formatRelativeTime(state.lastSyncAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.lastError?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        // The hint is what makes a setup mistake fixable without
                        // going and looking up what "code 10" means.
                        error.hint?.let { hint ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = viewModel::syncNow,
                        enabled = !state.syncing,
                    ) {
                        if (state.syncing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Syncing…")
                        } else {
                            Text("Sync now")
                        }
                    }
                }
            }
        }
    }

    (importState as? ImportState.Pending)?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "It holds ${pending.sessions} sessions and ${pending.sets} sets.\n\n" +
                        "They are merged into your log rather than replacing it: for " +
                        "each session and set, whichever copy was edited more recently " +
                        "wins. Deletions recorded in the backup count as edits too."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmImport) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelImport) { Text("Cancel") }
            },
        )
    }

    if (showCustomRest) {
        RestLengthDialog(
            title = "Default rest length",
            initialSeconds = rest.seconds,
            onDismiss = { showCustomRest = false },
            onConfirm = { seconds ->
                viewModel.setRestSeconds(seconds)
                showCustomRest = false
            },
        )
    }
}
