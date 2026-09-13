package com.workouttracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.workouttracker.data.SetWithSession
import com.workouttracker.data.WorkoutRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ExerciseHistoryViewModel(
    repository: WorkoutRepository,
    exercise: String,
) : ViewModel() {
    val sets: StateFlow<List<SetWithSession>> = repository.observeSetsForExercise(exercise)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Every set logged for one exercise, newest session first, with totals. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseHistoryScreen(exercise: String, onBack: () -> Unit) {
    val viewModel = appViewModel(key = exercise) { app ->
        ExerciseHistoryViewModel(app.repository, exercise)
    }
    val sets by viewModel.sets.collectAsStateWithLifecycle()

    // Sessions in the order the query returned them (newest first); groupBy
    // preserves first-encounter order, so no re-sorting is needed.
    val sessions = remember(sets) { sets.groupBy { it.workoutDate }.toList() }
    val bestSet = remember(sets) { sets.maxByOrNull { it.weightKg } }
    val totalVolume = remember(sets) { sets.sumOf { it.reps * it.weightKg } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(exercise) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Stat("Sessions", sessions.size.toString())
                        Stat("Sets", sets.size.toString())
                        Stat(
                            "Best set",
                            bestSet?.let { "${formatWeight(it.weightKg)} kg × ${it.reps}" } ?: "—",
                        )
                        Stat("Volume", formatVolume(totalVolume))
                    }
                }
            }
            items(sessions, key = { it.first }) { (date, sessionSets) ->
                SessionCard(date = date, name = sessionSets.first().workoutName, sets = sessionSets)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionCard(date: Long, name: String, sets: List<SetWithSession>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(formatDay(date), style = MaterialTheme.typography.titleMedium)
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            sets.forEachIndexed { index, set ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Text(
                        "${set.reps} × ${formatWeight(set.weightKg)} kg",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
