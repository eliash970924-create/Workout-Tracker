package com.workouttracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.workouttracker.data.ExerciseMetric
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

    // How this exercise is measured now, taken from its most recent set. An
    // exercise recategorised part-way through has sets of both kinds, and the
    // totals below are only meaningful under one of them.
    val metric = remember(sets) {
        ExerciseMetric.of(sets.firstOrNull()?.metric)
    }
    val measured = remember(sets, metric) { sets.filter { it.metric == metric.name } }

    // The best set, marked wherever in the history it happens to sit. Computed
    // over every metric this exercise has ever used, not just the current one,
    // so a recategorised exercise keeps the best it set under the old one.
    val records = remember(sets) {
        bestSetIds(historyInOrder(sets).map { it.recordCandidate() })
    }

    val options = remember(metric) { ProgressMetric.optionsFor(metric) }
    var chart by remember(options) { mutableStateOf(options.first()) }
    val points = remember(measured, chart) { progressPoints(measured, chart) }

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
                        Stat("Best", bestOf(measured, metric))
                        Stat(totalLabel(metric), totalOf(measured, metric))
                    }
                }
            }
            // One session is a data point, not a trend; the chart earns its
            // space only once there is something to compare against.
            if (points.size >= 2) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                options.forEach { option ->
                                    FilterChip(
                                        selected = chart == option,
                                        onClick = { chart = option },
                                        label = { Text(option.label) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            ProgressChart(points = points, metric = chart)
                        }
                    }
                }
            }
            items(sessions, key = { it.first }) { (date, sessionSets) ->
                SessionCard(
                    date = date,
                    name = sessionSets.first().workoutName,
                    sets = sessionSets,
                    records = records,
                )
            }
        }
    }
}

/** The best single set, phrased in whatever this exercise is measured by. */
private fun bestOf(sets: List<SetWithSession>, metric: ExerciseMetric): String = when {
    sets.isEmpty() -> "—"
    metric == ExerciseMetric.WEIGHT_REPS -> sets.maxBy { it.weightKg }
        .let { "${formatWeight(it.weightKg)} kg × ${it.reps}" }
    metric == ExerciseMetric.REPS -> "${sets.maxOf { it.reps }} reps"
    metric == ExerciseMetric.TIME -> formatDuration(sets.maxOf { it.seconds })
    else -> formatDistance(sets.maxOf { it.meters })
}

private fun totalLabel(metric: ExerciseMetric): String = when (metric) {
    ExerciseMetric.WEIGHT_REPS -> "Volume"
    ExerciseMetric.REPS -> "Reps"
    ExerciseMetric.TIME -> "Time"
    ExerciseMetric.DISTANCE_TIME -> "Distance"
}

private fun totalOf(sets: List<SetWithSession>, metric: ExerciseMetric): String = when (metric) {
    ExerciseMetric.WEIGHT_REPS -> formatVolume(sets.sumOf { it.reps * it.weightKg })
    ExerciseMetric.REPS -> sets.sumOf { it.reps }.toString()
    ExerciseMetric.TIME -> formatDuration(sets.sumOf { it.seconds })
    ExerciseMetric.DISTANCE_TIME -> formatDistance(sets.sumOf { it.meters })
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
private fun SessionCard(
    date: Long,
    name: String,
    sets: List<SetWithSession>,
    records: Set<String>,
) {
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
                    Text(describeSet(set), style = MaterialTheme.typography.bodyMedium)
                    if (set.id in records) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Personal best",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp).align(Alignment.CenterVertically),
                        )
                    }
                }
            }
        }
    }
}
