package com.workouttracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.workouttracker.data.RestOverride
import com.workouttracker.data.WorkoutRepository
import com.workouttracker.rest.RestPrefs
import com.workouttracker.rest.RestSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RestOverridesViewModel(
    private val repository: WorkoutRepository,
    restPrefs: RestPrefs,
) : ViewModel() {

    val overrides: StateFlow<List<RestOverride>> = repository.observeRestOverrides()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val restDefault: StateFlow<RestSettings> = restPrefs.state

    /** [seconds] null puts the exercise back on the default. */
    fun setRest(exercise: String, seconds: Int?) {
        viewModelScope.launch { repository.setRestSeconds(exercise, seconds) }
    }
}

/**
 * Every exercise with a rest length of its own, on a screen of its own.
 *
 * It used to be a list inside the rest-timer card in Settings, which was fine
 * at three and would not have been at thirty. Here it can be as long as it
 * needs to be, and a row can be changed as well as cleared: tap one for the
 * same dialog the exercise's own clock button opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestOverridesScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { app -> RestOverridesViewModel(app.repository, app.restPrefs) }
    val overrides by viewModel.overrides.collectAsStateWithLifecycle()
    val restDefault by viewModel.restDefault.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RestOverride?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // One tap on a cross clears a row, which in a long list is also the easiest
    // thing to do by accident -- so it can be taken back.
    fun clear(override: RestOverride) {
        viewModel.setRest(override.exercise, null)
        scope.launch {
            val result = snackbar.showSnackbar(
                message = "${override.name} is back on the default",
                actionLabel = "Undo",
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.setRest(override.exercise, override.seconds)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rest per exercise") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val defaultText = formatCountdown(restDefault.seconds)
        if (overrides.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("None yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Every exercise rests for the default, $defaultText. To give one " +
                        "its own, open it during a session and tap the rest length at " +
                        "the top.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Text(
                    "Tap one to change it. Every other exercise uses the default, $defaultText.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(overrides, key = { it.exercise }) { override ->
                ListItem(
                    headlineContent = { Text(override.name) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                formatCountdown(override.seconds),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            IconButton(onClick = { clear(override) }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "Put ${override.name} back on the default",
                                )
                            }
                        }
                    },
                    modifier = Modifier.clickable { editing = override },
                )
                HorizontalDivider()
            }
        }
    }

    editing?.let { override ->
        RestLengthDialog(
            title = "Rest for ${override.name}",
            initialSeconds = override.seconds,
            supporting = "Every other exercise uses the default, " +
                "${formatCountdown(restDefault.seconds)}.",
            onDismiss = { editing = null },
            onConfirm = { seconds ->
                viewModel.setRest(override.exercise, seconds)
                editing = null
            },
            onClear = {
                editing = null
                clear(override)
            },
            clearLabel = "Use the default instead",
        )
    }
}
