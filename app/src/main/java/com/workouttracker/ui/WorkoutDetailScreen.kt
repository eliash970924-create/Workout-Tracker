package com.workouttracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.workouttracker.data.CustomExercise
import com.workouttracker.data.ExerciseMetric
import com.workouttracker.data.MuscleGroup
import com.workouttracker.data.SessionTemplate
import com.workouttracker.data.SetEntry
import com.workouttracker.data.Workout
import com.workouttracker.data.WorkoutRepository
import com.workouttracker.data.isDefaultWorkoutName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
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

    private val _startFrom = MutableStateFlow<List<SessionTemplate>>(emptyList())

    /** Earlier sessions offered as a starting point; empty until asked for. */
    val startFrom: StateFlow<List<SessionTemplate>> = _startFrom.asStateFlow()

    /** Exercises the user has re-measured, so the picker can say so. */
    val metricOverrides: StateFlow<Map<String, ExerciseMetric>> =
        repository.observeMetricOverrides()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun rename(name: String) = edit { it.copy(name = name) }

    fun setNotes(notes: String) = edit { it.copy(notes = notes) }

    fun setDate(date: LocalDate) = edit { it.copy(date = date.toEpochDay()) }

    /** [muscleGroup] null means "work it out from the name". */
    fun addSet(exercise: String, muscleGroup: MuscleGroup? = null) {
        viewModelScope.launch { repository.addSet(workoutId, exercise.trim(), muscleGroup) }
    }

    /** Creates the exercise, then adds a first set of it to this session. */
    fun createExerciseAndAddSet(name: String, muscleGroup: MuscleGroup, metric: ExerciseMetric) {
        viewModelScope.launch {
            val stored = repository.addCustomExercise(name, muscleGroup)
            // Recorded as an override, the same place a built-in's would go, so
            // there is one answer to "how is this measured" and not two.
            if (metric != ExerciseMetric.DEFAULT) repository.setMetric(stored, metric)
            repository.addSet(workoutId, stored, muscleGroup)
        }
    }

    fun deleteExercise(exercise: String) {
        viewModelScope.launch { repository.deleteExercise(workoutId, exercise) }
    }

    /** Puts the session's blocks in [order]; a superset moves as one. */
    fun reorderBlocks(order: List<Block>) {
        viewModelScope.launch { repository.reorderExercises(workoutId, exercisesOf(order)) }
    }

    /** [exercise] joins [partner]'s superset, or the two start one. */
    fun supersetWith(exercise: String, partner: String) {
        viewModelScope.launch { repository.supersetWith(workoutId, exercise, partner) }
    }

    fun splitSuperset(supersetId: String) {
        viewModelScope.launch { repository.splitSuperset(workoutId, supersetId) }
    }

    fun loadStartFrom() {
        viewModelScope.launch { _startFrom.value = repository.recentSessions(workoutId) }
    }

    /**
     * Fills this session with [source]'s exercises and sets, and takes its name
     * too -- but only while this session is still called whatever it was
     * created as. A name typed by hand is never overwritten.
     */
    fun startFrom(source: SessionTemplate) {
        viewModelScope.launch {
            repository.copySession(workoutId, source.id)
            val current = workout.value ?: return@launch
            if (isDefaultWorkoutName(current.name)) {
                repository.updateWorkout(current.copy(name = source.name))
            }
        }
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
    val metricOverrides by viewModel.metricOverrides.collectAsStateWithLifecycle()

    var showDatePicker by remember { mutableStateOf(false) }
    var showAddExercise by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var supersetFor by remember { mutableStateOf<String?>(null) }
    var addToSuperset by remember { mutableStateOf<Block.Superset?>(null) }
    var showStartFrom by remember { mutableStateOf(false) }
    val startFromSessions by viewModel.startFrom.collectAsStateWithLifecycle()

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

        // Sets grouped by exercise, and exercises into blocks: one exercise, or a
        // superset of several. Blocks are what the list draws, drags and moves,
        // which is what keeps a superset in one piece.
        val byExercise = remember(sets) { sets.groupBy(SetEntry::exercise) }
        val stored = remember(sets) { blocksOf(sets) }

        // The order the list draws in. A drag rewrites this immediately and
        // persists afterwards; waiting for the database to come back would drop
        // the row where it started. Re-seeded whenever the stored order changes,
        // which includes the write this drag just made.
        var order by remember { mutableStateOf(stored) }
        LaunchedEffect(stored) { order = stored }

        fun moveBlock(block: Block, delta: Int) {
            val from = order.indexOf(block)
            val to = from + delta
            if (from < 0 || to !in order.indices) return
            order = order.toMutableList().apply { add(to, removeAt(from)) }
            viewModel.reorderBlocks(order)
        }

        val listState = rememberLazyListState()
        val reorderState = rememberReorderableLazyListState(listState) { from, to ->
            // By key rather than index: the list also holds the name field, the
            // date and the buttons, and a drag onto one of those is no move.
            val fromIndex = order.indexOfFirst { it.key == from.key }
            val toIndex = order.indexOfFirst { it.key == to.key }
            if (fromIndex >= 0 && toIndex >= 0) {
                order = order.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
                viewModel.reorderBlocks(order)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            state = listState,
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DraftTextField(
                    value = current.name,
                    onValueChange = viewModel::rename,
                    label = "Session",
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
            items(order, key = { it.key }) { block ->
                ReorderableItem(reorderState, key = block.key) { dragging ->
                    val index = order.indexOf(block)
                    when (block) {
                        is Block.Single -> ExerciseRow(
                            exercise = block.exercise,
                            sets = byExercise[block.exercise].orEmpty(),
                            dragging = dragging,
                            canMoveUp = index > 0,
                            canMoveDown = index < order.lastIndex,
                            canSuperset = order.size > 1,
                            onOpen = { onOpenExercise(block.exercise) },
                            onMove = { delta -> moveBlock(block, delta) },
                            onSuperset = { supersetFor = block.exercise },
                            onRemove = { confirmRemove = block.exercise },
                            dragHandle = Modifier.draggableHandle(),
                        )
                        is Block.Superset -> SupersetRow(
                            block = block,
                            byExercise = byExercise,
                            dragging = dragging,
                            canMoveUp = index > 0,
                            canMoveDown = index < order.lastIndex,
                            canAdd = order.any { it is Block.Single },
                            onOpen = { onOpenExercise(block.members.first()) },
                            onMove = { delta -> moveBlock(block, delta) },
                            onAdd = { addToSuperset = block },
                            onSplit = { viewModel.splitSuperset(block.id) },
                            dragHandle = Modifier.draggableHandle(),
                        )
                    }
                }
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
            // Offered while the session is still empty, which is when building
            // it from scratch is the thing you were about to do. Once there is
            // something here, copying a whole session on top of it is not.
            if (sets.isEmpty()) {
                item {
                    OutlinedButton(
                        onClick = {
                            viewModel.loadStartFrom()
                            showStartFrom = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start from a previous session")
                    }
                }
            }
            item {
                DraftTextField(
                    value = current.notes,
                    onValueChange = viewModel::setNotes,
                    label = "Notes",
                    singleLine = false,
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
            metricOverrides = metricOverrides,
            onDismiss = { showAddExercise = false },
            onPick = { name, group ->
                viewModel.addSet(name, group)
                showAddExercise = false
            },
            onCreate = { name, group, metric ->
                viewModel.createExerciseAndAddSet(name, group, metric)
                showAddExercise = false
            },
        )
    }

    if (showStartFrom) {
        StartFromDialog(
            sessions = startFromSessions,
            onDismiss = { showStartFrom = false },
            onPick = { session ->
                viewModel.startFrom(session)
                showStartFrom = false
            },
        )
    }

    supersetFor?.let { exercise ->
        // Anything else in the session: an exercise to start a superset with,
        // or a superset to join.
        val options = blocksOf(sets).filter { exercise !in it.members }.map { block ->
            block.members.first() to
                if (block is Block.Superset) "${block.label} (superset)" else block.label
        }
        SupersetPickerDialog(
            title = "Superset $exercise with",
            options = options,
            onDismiss = { supersetFor = null },
            onPick = { partner ->
                viewModel.supersetWith(exercise, partner)
                supersetFor = null
            },
        )
    }

    addToSuperset?.let { block ->
        // Exercises on their own only. Folding one superset into another is
        // two supersets' worth of rounds changing at once; split one first.
        val options = blocksOf(sets).filterIsInstance<Block.Single>()
            .map { it.exercise to it.exercise }
        SupersetPickerDialog(
            title = "Add to ${block.label}",
            options = options,
            onDismiss = { addToSuperset = null },
            onPick = { exercise ->
                viewModel.supersetWith(exercise, block.members.first())
                addToSuperset = null
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
 * The overflow button is also the drag handle -- tap it for the menu, drag it
 * to move the exercise. Long press used to open the menu, but drag wants that
 * gesture and a row this narrow has no width for a fourth control.
 */
@Composable
private fun ExerciseRow(
    exercise: String,
    sets: List<SetEntry>,
    dragging: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canSuperset: Boolean,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
    onSuperset: () -> Unit,
    onRemove: () -> Unit,
    dragHandle: Modifier,
) {
    val done = sets.count { it.completed }
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (dragging) 8.dp else 0.dp,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
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
            if (sets.isNotEmpty()) SetProgressPie(done = done, total = sets.size)
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = dragHandle) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Exercise options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Move up") },
                        enabled = canMoveUp,
                        leadingIcon = {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onMove(-1)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Move down") },
                        enabled = canMoveDown,
                        leadingIcon = {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onMove(1)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Superset with…") },
                        enabled = canSuperset,
                        leadingIcon = {
                            Icon(Icons.Outlined.Link, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onSuperset()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove from session") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Delete, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

/**
 * A superset in the session: its exercises in one card, each with how far
 * through it you are, and the lot opening, dragging and moving as one.
 *
 * Removing an exercise from the session is not offered here -- split the
 * superset first, and each exercise gets its own menu back.
 */
@Composable
private fun SupersetRow(
    block: Block.Superset,
    byExercise: Map<String, List<SetEntry>>,
    dragging: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canAdd: Boolean,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
    onAdd: () -> Unit,
    onSplit: () -> Unit,
    dragHandle: Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (dragging) 8.dp else 0.dp,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Superset",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                block.members.forEach { exercise ->
                    val sets = byExercise[exercise].orEmpty()
                    val done = sets.count { it.completed }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(exercise, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "$done of ${sets.size} sets",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (sets.isNotEmpty()) SetProgressPie(done = done, total = sets.size)
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = dragHandle) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Superset options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Move up") },
                        enabled = canMoveUp,
                        leadingIcon = {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onMove(-1)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Move down") },
                        enabled = canMoveDown,
                        leadingIcon = {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onMove(1)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Add exercise…") },
                        enabled = canAdd,
                        leadingIcon = {
                            Icon(Icons.Default.Add, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onAdd()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Split superset") },
                        leadingIcon = {
                            Icon(Icons.Outlined.LinkOff, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onSplit()
                        },
                    )
                }
            }
        }
    }
}

/** Picks one entry from the session, for starting or growing a superset. */
@Composable
private fun SupersetPickerDialog(
    title: String,
    /** The exercise each choice stands for, and how it is shown. */
    options: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (options.isEmpty()) {
                Text("There is nothing else in this session to put with it yet.")
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(options, key = { it.first }) { (exercise, label) ->
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(exercise) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
