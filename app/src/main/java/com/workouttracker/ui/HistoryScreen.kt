package com.workouttracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.workouttracker.data.ExerciseHistoryEntry
import com.workouttracker.data.ExerciseMetric
import com.workouttracker.data.MuscleGroup
import com.workouttracker.data.WorkoutRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(repository: WorkoutRepository) : ViewModel() {
    val history: StateFlow<List<ExerciseHistoryEntry>> = repository.observeExerciseHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Every exercise the user has logged, filterable by muscle group. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(onOpenExercise: (String) -> Unit) {
    val viewModel = appViewModel { app -> HistoryViewModel(app.repository) }
    val history by viewModel.history.collectAsStateWithLifecycle()
    var groupFilter by remember { mutableStateOf<MuscleGroup?>(null) }

    // Only groups the user has actually trained are worth offering as filters.
    val presentGroups = remember(history) {
        history.map { MuscleGroup.of(it.muscleGroup) }.distinct().sortedBy { it.ordinal }
    }
    val visible = remember(history, groupFilter) {
        history.filter { groupFilter == null || MuscleGroup.of(it.muscleGroup) == groupFilter }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("History") }) }) { padding ->
        if (history.isEmpty()) {
            EmptyHistory(Modifier.padding(padding))
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (presentGroups.size > 1) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = groupFilter == null,
                        onClick = { groupFilter = null },
                        label = { Text("All") },
                    )
                    presentGroups.forEach { group ->
                        FilterChip(
                            selected = groupFilter == group,
                            onClick = { groupFilter = if (groupFilter == group) null else group },
                            label = { Text(group.displayName) },
                        )
                    }
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.exercise }) { entry ->
                    HistoryRow(entry, onClick = { onOpenExercise(entry.exercise) })
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: ExerciseHistoryEntry, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.exercise, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${MuscleGroup.of(entry.muscleGroup).displayName} · last ${formatDay(entry.lastPerformed)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                bestOf(entry)?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    if (entry.setCount == 1) "1 set" else "${entry.setCount} sets",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The one number worth putting on the row, read by the metric this exercise is
 * measured by now. Null when there is nothing to show -- a lift logged only at
 * bodyweight has no best weight.
 */
private fun bestOf(entry: ExerciseHistoryEntry): String? =
    when (ExerciseMetric.of(entry.metric)) {
        ExerciseMetric.WEIGHT_REPS ->
            entry.bestWeight.takeIf { it > 0 }?.let { "${formatWeight(it)} kg" }
        ExerciseMetric.REPS -> entry.bestReps.takeIf { it > 0 }?.let { "$it reps" }
        ExerciseMetric.TIME -> entry.bestSeconds.takeIf { it > 0 }?.let(::formatDuration)
        ExerciseMetric.DISTANCE_TIME ->
            entry.bestMeters.takeIf { it > 0 }?.let(::formatDistance)
    }

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No history yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Log a few sets and every exercise you train will show up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
