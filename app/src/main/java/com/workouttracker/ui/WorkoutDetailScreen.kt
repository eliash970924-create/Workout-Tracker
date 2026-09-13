package com.workouttracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.workouttracker.data.CustomExercise
import com.workouttracker.data.MuscleGroup
import com.workouttracker.data.SetEntry
import com.workouttracker.data.Workout
import com.workouttracker.data.WorkoutRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class WorkoutDetailViewModel(
    private val repository: WorkoutRepository,
    private val workoutId: String,
) : ViewModel() {

    val workout: StateFlow<Workout?> = repository.observeWorkout(workoutId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sets: StateFlow<List<SetEntry>> = repository.observeSets(workoutId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val customExercises: StateFlow<List<CustomExercise>> = repository.observeCustomExercises()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun rename(name: String) = edit { it.copy(name = name) }

    fun setNotes(notes: String) = edit { it.copy(notes = notes) }

    fun setDate(date: LocalDate) = edit { it.copy(date = date.toEpochDay()) }

    /** [muscleGroup] null means "work it out from the name" (adding another set). */
    fun addSet(exercise: String, muscleGroup: MuscleGroup? = null) {
        viewModelScope.launch { repository.addSet(workoutId, exercise.trim(), muscleGroup) }
    }

    /** Creates the exercise, then logs a first set of it. */
    fun createExerciseAndAddSet(name: String, muscleGroup: MuscleGroup) {
        viewModelScope.launch {
            val stored = repository.addCustomExercise(name, muscleGroup)
            repository.addSet(workoutId, stored, muscleGroup)
        }
    }

    fun updateSet(set: SetEntry, reps: Int = set.reps, weightKg: Double = set.weightKg) {
        viewModelScope.launch { repository.updateSet(set.copy(reps = reps, weightKg = weightKg)) }
    }

    fun deleteSet(id: String) {
        viewModelScope.launch { repository.deleteSet(id) }
    }

    fun deleteWorkout(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteWorkout(workoutId)
            onDeleted()
        }
    }

    private fun edit(transform: (Workout) -> Workout) {
        val current = workout.value ?: return
        viewModelScope.launch { repository.updateWorkout(transform(current)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailScreen(workoutId: String, onBack: () -> Unit) {
    val viewModel = appViewModel(key = workoutId) { app ->
        WorkoutDetailViewModel(app.repository, workoutId)
    }
    val workout by viewModel.workout.collectAsStateWithLifecycle()
    val sets by viewModel.sets.collectAsStateWithLifecycle()
    val customExercises by viewModel.customExercises.collectAsStateWithLifecycle()

    var showDatePicker by remember { mutableStateOf(false) }
    var showAddExercise by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val current = workout
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete workout")
                    }
                },
            )
        },
    ) { padding ->
        if (current == null) {
            // Either still loading, or the workout was just deleted.
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        // Sets are grouped by exercise, in the order each exercise first appears.
        val groups = remember(sets) { sets.groupBy(SetEntry::exercise).toList() }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = current.name,
                    onValueChange = viewModel::rename,
                    label = { Text("Session") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.DateRange, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(formatDay(current.date))
                }
            }
            items(groups, key = { it.first }) { (exercise, exerciseSets) ->
                ExerciseCard(
                    exercise = exercise,
                    sets = exerciseSets,
                    onAddSet = { viewModel.addSet(exercise) },
                    onUpdate = viewModel::updateSet,
                    onDelete = viewModel::deleteSet,
                )
            }
            item {
                OutlinedButton(
                    onClick = { showAddExercise = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add exercise")
                }
            }
            item {
                OutlinedTextField(
                    value = current.notes,
                    onValueChange = viewModel::setNotes,
                    label = { Text("Notes") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = current?.date?.let { it * MILLIS_PER_DAY },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        // The picker works in UTC; convert back without letting
                        // the local zone shift the day.
                        viewModel.setDate(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        )
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showAddExercise) {
        ExercisePickerDialog(
            customExercises = customExercises,
            onDismiss = { showAddExercise = false },
            onPick = { name, group ->
                viewModel.addSet(name, group)
                showAddExercise = false
            },
            onCreate = { name, group ->
                viewModel.createExerciseAndAddSet(name, group)
                showAddExercise = false
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete workout?") },
            text = { Text("This session and its sets will be removed from all your devices.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteWorkout(onBack)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

private const val MILLIS_PER_DAY = 86_400_000L

@Composable
private fun ExerciseCard(
    exercise: String,
    sets: List<SetEntry>,
    onAddSet: () -> Unit,
    onUpdate: (SetEntry, Int, Double) -> Unit,
    onDelete: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(exercise, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            sets.forEachIndexed { index, set ->
                SetRow(
                    index = index + 1,
                    set = set,
                    onUpdate = { reps, weight -> onUpdate(set, reps, weight) },
                    onDelete = { onDelete(set.id) },
                )
            }
            TextButton(onClick = onAddSet) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add set")
            }
        }
    }
}

@Composable
private fun SetRow(
    index: Int,
    set: SetEntry,
    onUpdate: (reps: Int, weightKg: Double) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "$index",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp),
        )
        NumberField(
            initial = set.reps.toString(),
            label = "reps",
            modifier = Modifier.weight(1f),
            onValue = { text -> text.toIntOrNull()?.let { onUpdate(it, set.weightKg) } },
        )
        NumberField(
            initial = formatWeight(set.weightKg),
            label = "kg",
            decimal = true,
            modifier = Modifier.weight(1f),
            onValue = { text -> text.toDoubleOrNull()?.let { onUpdate(set.reps, it) } },
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Close, contentDescription = "Remove set")
        }
    }
}

/**
 * Numeric field that keeps its own text so the user can clear it mid-edit, and
 * only writes back once the text parses.
 */
@Composable
private fun NumberField(
    initial: String,
    label: String,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
    onValue: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() || (decimal && it == '.') }
            text = filtered
            onValue(filtered)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
        modifier = modifier,
    )
}
