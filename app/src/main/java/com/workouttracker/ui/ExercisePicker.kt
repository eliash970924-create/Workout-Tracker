package com.workouttracker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.workouttracker.data.CustomExercise
import com.workouttracker.data.ExerciseCatalog
import com.workouttracker.data.ExerciseMetric
import com.workouttracker.data.MuscleGroup

/** One row in the picker: built-in or user-created. */
private data class PickerItem(
    val name: String,
    val muscleGroup: MuscleGroup,
    val metric: ExerciseMetric,
    val isCustom: Boolean,
)

/**
 * Full-screen exercise picker: the built-in catalogue plus the user's own
 * exercises, grouped by muscle group, searchable, with a way to add a new one.
 *
 * A dialog rather than a navigation destination so the selection comes straight
 * back through [onPick] instead of going via the back stack.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ExercisePickerDialog(
    customExercises: List<CustomExercise>,
    /** Metric overrides, keyed by lower-cased exercise name. */
    metricOverrides: Map<String, ExerciseMetric>,
    onDismiss: () -> Unit,
    onPick: (name: String, muscleGroup: MuscleGroup) -> Unit,
    onCreate: (name: String, muscleGroup: MuscleGroup, metric: ExerciseMetric) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var groupFilter by remember { mutableStateOf<MuscleGroup?>(null) }
    var creating by remember { mutableStateOf<String?>(null) }

    val items = remember(customExercises, metricOverrides) {
        fun metricOf(name: String, fallback: ExerciseMetric) =
            metricOverrides[name.lowercase()] ?: fallback
        val catalog = ExerciseCatalog.all.map {
            PickerItem(it.name, it.muscleGroup, metricOf(it.name, it.metric), isCustom = false)
        }
        val custom = customExercises.map {
            PickerItem(
                it.name,
                MuscleGroup.of(it.muscleGroup),
                metricOf(it.name, ExerciseMetric.DEFAULT),
                isCustom = true,
            )
        }
        // Custom entries win a name clash, so renaming a built-in effectively
        // overrides it rather than showing the exercise twice.
        (custom + catalog)
            .distinctBy { it.name.lowercase() }
            .sortedWith(compareBy({ it.muscleGroup.ordinal }, { it.name }))
    }

    val visible = remember(items, query, groupFilter) {
        items.filter { item ->
            (groupFilter == null || item.muscleGroup == groupFilter) &&
                (query.isBlank() || item.name.contains(query.trim(), ignoreCase = true))
        }
    }
    val grouped = remember(visible) { visible.groupBy { it.muscleGroup } }

    val exactMatch = remember(visible, query) {
        query.isNotBlank() && visible.any { it.name.equals(query.trim(), ignoreCase = true) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Add exercise") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Outlined.Close, contentDescription = "Close")
                            }
                        },
                    )
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = groupFilter == null,
                            onClick = { groupFilter = null },
                            label = { Text("All") },
                        )
                        MuscleGroup.entries.forEach { group ->
                            // Only offer groups that actually contain something.
                            if (items.any { it.muscleGroup == group }) {
                                FilterChip(
                                    selected = groupFilter == group,
                                    onClick = { groupFilter = if (groupFilter == group) null else group },
                                    label = { Text(group.displayName) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 32.dp),
                    ) {
                        if (query.isNotBlank() && !exactMatch) {
                            item {
                                CreateRow(name = query.trim(), onClick = { creating = query.trim() })
                            }
                        }
                        grouped.forEach { (group, groupItems) ->
                            stickyHeader(key = "header-${group.name}") {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Text(
                                        group.displayName,
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    )
                                }
                            }
                            items(groupItems, key = { "${group.name}-${it.name}" }) { item ->
                                ExerciseRow(item) { onPick(item.name, item.muscleGroup) }
                            }
                        }
                        if (grouped.isEmpty() && query.isBlank()) {
                            item {
                                Text(
                                    "Nothing matches that filter.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    creating?.let { name ->
        NewExerciseDialog(
            exerciseName = name,
            onDismiss = { creating = null },
            onConfirm = { group, metric ->
                creating = null
                onCreate(name, group, metric)
            },
        )
    }
}

@Composable
private fun ExerciseRow(item: PickerItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // Said only when it is not the usual thing, so the list stays a list
        // of names rather than a table.
        if (item.metric != ExerciseMetric.DEFAULT) {
            Text(
                item.metric.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
        }
        if (item.isCustom) {
            Text(
                "Yours",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CreateRow(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            "Create \"$name\"",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Asks what a newly created exercise is: which muscle group it works, and
 * which numbers logging it should ask for.
 *
 * Two steps rather than one crowded dialog, because the muscle group narrows
 * the metric -- pick Cardio and distance and time is the obvious next answer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewExerciseDialog(
    exerciseName: String,
    onDismiss: () -> Unit,
    onConfirm: (MuscleGroup, ExerciseMetric) -> Unit,
) {
    var selected by remember { mutableStateOf(MuscleGroup.OTHER) }
    var group by remember { mutableStateOf<MuscleGroup?>(null) }

    val chosen = group
    if (chosen != null) {
        MetricDialog(
            title = "How is it measured?",
            // Cardio is almost never reps and kilos, so start somewhere useful.
            initial = if (chosen == MuscleGroup.CARDIO) {
                ExerciseMetric.DISTANCE_TIME
            } else {
                ExerciseMetric.DEFAULT
            },
            supporting = "\"$exerciseName\" will ask for these when you log it. " +
                "You can change it later.",
            confirmLabel = "Add",
            // Dismissing goes back to the muscle group rather than out of the
            // flow entirely, so a mis-tap does not lose the name typed.
            onDismiss = { group = null },
            onConfirm = { metric -> onConfirm(chosen, metric) },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Muscle group") },
        text = {
            Column {
                Text(
                    "Which muscle group does \"$exerciseName\" work?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MuscleGroup.entries.forEach { option ->
                        FilterChip(
                            selected = selected == option,
                            onClick = { selected = option },
                            label = { Text(option.displayName) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { group = selected }) { Text("Next") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
