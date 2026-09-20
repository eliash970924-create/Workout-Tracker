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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import com.workouttracker.data.WorkoutRepository
import com.workouttracker.data.buildCsv
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate

/** What came of the last export, for the line under the button. */
sealed interface ExportResult {
    data class Saved(val sets: Int) : ExportResult
    data class Failed(val reason: String) : ExportResult
}

class SettingsViewModel(
    private val context: Context,
    private val prefs: SyncPrefs,
    private val restPrefs: RestPrefs,
    private val syncManager: SyncManager,
    private val repository: WorkoutRepository,
) : ViewModel() {

    val state: StateFlow<SyncState> = prefs.state

    private val _export = MutableStateFlow<ExportResult?>(null)

    /** Null until an export has been tried in this sitting. */
    val export: StateFlow<ExportResult?> = _export.asStateFlow()

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
            _export.value = try {
                val rows = repository.exportRows()
                val csv = buildCsv(rows)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(csv.toByteArray(Charsets.UTF_8))
                    } ?: throw IOException("that location could not be opened")
                }
                ExportResult.Saved(rows.size)
            } catch (e: Exception) {
                ExportResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    val rest: StateFlow<RestSettings> = restPrefs.state

    fun setRestEnabled(enabled: Boolean) = restPrefs.setEnabled(enabled)

    fun setRestSeconds(seconds: Int) = restPrefs.setSeconds(seconds)

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val viewModel = appViewModel { app ->
        SettingsViewModel(app, app.syncPrefs, app.restPrefs, app.syncManager, app.repository)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rest by viewModel.rest.collectAsStateWithLifecycle()
    val export by viewModel.export.collectAsStateWithLifecycle()
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
                    when (val result = export) {
                        null -> Unit
                        is ExportResult.Saved -> {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (result.sets == 1) "Exported 1 set."
                                else "Exported ${result.sets} sets.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        is ExportResult.Failed -> {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Could not export: ${result.reason}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
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
