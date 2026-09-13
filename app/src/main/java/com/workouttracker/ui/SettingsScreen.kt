package com.workouttracker.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.workouttracker.sync.DriveAuth
import com.workouttracker.sync.SyncInterval
import com.workouttracker.sync.SyncManager
import com.workouttracker.sync.SyncPrefs
import com.workouttracker.sync.SyncScheduler
import com.workouttracker.sync.SyncState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val context: Context,
    private val prefs: SyncPrefs,
    private val syncManager: SyncManager,
) : ViewModel() {

    val state: StateFlow<SyncState> = prefs.state

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
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { app ->
        SettingsViewModel(app, app.syncPrefs, app.syncManager)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onConsentResult(result.resultCode == Activity.RESULT_OK, result.data)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & sync") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
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
}
