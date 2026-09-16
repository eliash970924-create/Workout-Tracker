package com.workouttracker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

    /** [muscleGroup] null means "work it out from the name". */
    fun addSet(exercise: String, muscleGroup: MuscleGroup? = null) {
        viewModelScope.launch { repository.addSet(workoutId, exercise.trim(), muscleGroup) }
    }

    /** Creates the exercise, then adds a first set of it to this session. */
    fun createExerciseAndAddSet(name: String, muscleGroup: MuscleGroup) {
        viewModelScope.launch {
            val stored = repository.addCustomExercise(name, muscleGroup)
            repository.addSet(workoutId, stored, muscleGroup)
        }
    }

    fun deleteExercise(exercise: String) {
        viewModelScope.launch { repository.deleteExercise(workoutId, exercise) }
    }

    fun moveExercise(exercise: String, delta: Int) {
        viewModelScope.launch { repository.moveExercise(workoutId, exercise, delta) }
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

/**
 * A session: its name, date, notes, and the exercises in it. Each exercise
 * opens onto its own screen -- editing sets inline made every session a wall
 * of text fields, and none of it said what was actually done yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailScreen(
    workoutId: String,
    onBack: () -> Unit,
    onOpenExercise: (String) -> Unit,
) {
    val viewModel = appViewModel(key = workoutId) { app ->
        WorkoutDetailViewModel(app.repository, workoutId)
    }
    val workout by viewModel.workout.collectAsStateWithLifecycle()
    val sets by viewModel.sets.collectAsStateWithLifecycle()
    val customExercises by viewModel.customExercises.collectAsStateWithLifecycle()

    var showDatePicker by remember { mutableStateOf(false) }
    var showAddExercise by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }

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
        bottomBar = { RestTimerBar() },
    ) { padding ->
        if (current == null) {
            // Either still loading, or the workout was just deleted.
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        // Sets grouped by exercise, in the order each exercise first appears.
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
            itemsIndexed(groups, key = { _, group -> group.first }) { index, group ->
                val (exercise, exerciseSets) = group
                ExerciseRow(
                    exercise = exercise,
                    sets = exerciseSets,
                    canMoveUp = index > 0,
                    canMoveDown = index < groups.lastIndex,
                    onOpen = { onOpenExercise(exercise) },
                    onMove = { delta -> viewModel.moveExercise(exercise, delta) },
                    onRemove = { confirmRemove = exercise },
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

    confirmRemove?.let { exercise ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove $exercise?") },
            text = { Text("Its sets in this session will be removed from all your devices.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    viewModel.deleteExercise(exercise)
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("Cancel") }
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

/**
 * One exercise in the session: how far through it you are, and a way in.
 *
 * Reordering and removal sit behind a long press rather than on the row. Both
 * are rare next to "open it", and buttons for them would crowd the only thing
 * you normally came here to tap.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExerciseRow(
    exercise: String,
    sets: List<SetEntry>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val done = sets.count { it.completed }
    val finished = sets.isNotEmpty() && done == sets.size
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Card(
            Modifier.fillMaxWidth().combinedClickable(
                onClick = onOpen,
                onLongClick = { menuOpen = true },
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(exercise, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "$done of ${sets.size} sets",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (finished) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "All sets done",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Move up") },
                enabled = canMoveUp,
                leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onMove(-1)
                },
            )
            DropdownMenuItem(
                text = { Text("Move down") },
                enabled = canMoveDown,
                leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onMove(1)
                },
            )
            DropdownMenuItem(
                text = { Text("Remove from session") },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onRemove()
                },
            )
        }
    }
}
