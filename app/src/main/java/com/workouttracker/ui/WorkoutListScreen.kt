package com.workouttracker.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.workouttracker.data.WorkoutRepository
import com.workouttracker.data.defaultWorkoutName
import com.workouttracker.data.WorkoutSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class WorkoutListViewModel(private val repository: WorkoutRepository) : ViewModel() {

    val workouts: StateFlow<List<WorkoutSummary>> = repository.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Creates an empty session for today and hands back its id to navigate to. */
    fun createWorkout(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val today = LocalDate.now()
            // The name lives in the data layer now, because starting a session
            // from an earlier one has to know whether the name it would
            // overwrite was chosen by anyone.
            onCreated(repository.createWorkout(name = defaultWorkoutName(today), date = today))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutListScreen(onOpenWorkout: (String) -> Unit) {
    val viewModel = appViewModel { app -> WorkoutListViewModel(app.repository) }
    val workouts by viewModel.workouts.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Workouts") })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Log workout") },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = { viewModel.createWorkout(onOpenWorkout) },
            )
        },
    ) { padding ->
        if (workouts.isEmpty()) {
            EmptyState(Modifier.padding(padding))
        } else {
            // Recomputed whenever the list changes, which is often enough:
            // the only way it goes stale is the app being left open across a
            // Sunday midnight with nothing logged.
            val week = remember(workouts) { weekReview(workouts) }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { WeekCard(week) }
                items(workouts, key = { it.id }) { workout ->
                    WorkoutCard(workout, onClick = { onOpenWorkout(workout.id) })
                }
            }
        }
    }
}

/**
 * How the week is going, above the sessions themselves.
 *
 * Last week sits underneath because one number on its own says nothing: three
 * sessions is either a good week or a slow one depending on what came before.
 */
@Composable
private fun WeekCard(review: WeekReview) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "This week",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            if (review.thisWeek.isEmpty) {
                Text("Nothing logged yet.", style = MaterialTheme.typography.titleMedium)
            } else {
                Text(
                    describeSessions(review.thisWeek.sessions),
                    style = MaterialTheme.typography.titleMedium,
                )
                val totals = describeTotals(
                    review.thisWeek.volume,
                    review.thisWeek.meters,
                    review.thisWeek.seconds,
                )
                if (totals.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        totals,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!review.lastWeek.isEmpty) {
                Spacer(Modifier.height(8.dp))
                val totals = describeTotals(
                    review.lastWeek.volume,
                    review.lastWeek.meters,
                    review.lastWeek.seconds,
                )
                Text(
                    "Last week: " + describeSessions(review.lastWeek.sessions) +
                        if (totals.isEmpty()) "" else " · $totals",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WorkoutCard(workout: WorkoutSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(workout.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    formatDay(workout.date),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (workout.setCount == 1) "1 set" else "${workout.setCount} sets",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val totals = describeTotals(
                    workout.volume,
                    workout.totalMeters,
                    workout.totalSeconds,
                )
                if (totals.isNotEmpty()) {
                    Text(
                        totals,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No workouts yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap Log workout to start your first session.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
