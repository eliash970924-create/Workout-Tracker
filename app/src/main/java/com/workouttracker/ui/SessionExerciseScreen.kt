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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.workouttracker.data.ExerciseCatalog
import com.workouttracker.data.ExerciseMetric
import com.workouttracker.data.SetEntry
import com.workouttracker.data.SetWithSession
import com.workouttracker.data.WorkoutRepository
import com.workouttracker.rest.RestPrefs
import com.workouttracker.rest.RestSettings
import com.workouttracker.rest.RestTimer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Exercise names in the order they appear in the session. Sets arrive ordered
 * by position, so first appearance is the session's own order.
 */
fun exerciseOrder(sets: List<SetEntry>): List<String> = sets.map { it.exercise }.distinct()

/** The exercise after [current] in the session, or null when it is the last one. */
fun nextExercise(sets: List<SetEntry>, current: String): String? {
    val order = exerciseOrder(sets)
    val index = order.indexOf(current)
    return if (index < 0 || index == order.lastIndex) null else order[index + 1]
}

/** The most recent earlier session of an exercise, for the "last time" card. */
data class PreviousSession(val date: Long, val sets: List<SetWithSession>)

/**
 * The newest session in [sets], which arrive newest first. Grouping by date
 * rather than taking a fixed count keeps a session whole however many sets it
 * held.
 */
fun previousSession(sets: List<SetWithSession>): PreviousSession? {
    val newest = sets.firstOrNull() ?: return null
    return PreviousSession(
        date = newest.workoutDate,
        sets = sets.takeWhile { it.workoutDate == newest.workoutDate },
    )
}

/**
 * One set in words, read by its own metric: "5 × 100 kg", "12 reps", "1:30",
 * "5.2 km in 25:00".
 */
fun describeSet(set: SetWithSession): String = when (ExerciseMetric.of(set.metric)) {
    ExerciseMetric.WEIGHT_REPS -> "${set.reps} × ${formatWeight(set.weightKg)} kg"
    ExerciseMetric.REPS -> "${set.reps} reps"
    ExerciseMetric.TIME -> formatDuration(set.seconds)
    ExerciseMetric.DISTANCE_TIME ->
        "${formatDistance(set.meters)} in ${formatDuration(set.seconds)}"
}

/** A session's sets in a line, trailing off once it would get long. */
fun describeSets(sets: List<SetWithSession>, limit: Int = 4): String {
    val shown = sets.take(limit).joinToString(", ", transform = ::describeSet)
    return if (sets.size > limit) "$shown, …" else shown
}

class SessionExerciseViewModel(
    private val repository: WorkoutRepository,
    private val restTimer: RestTimer,
    restPrefs: RestPrefs,
    private val workoutId: String,
) : ViewModel() {

    /** The app-wide rest settings, for showing what "the default" currently is. */
    val restDefault: StateFlow<RestSettings> = restPrefs.state

    /** Every set in the session, not just this exercise's: the order of the
     * whole list is what decides which exercise comes next. */
    val sets: StateFlow<List<SetEntry>> = repository.observeSets(workoutId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** What this exercise looked like the last time it was trained. */
    fun previousSessionOf(exercise: String): StateFlow<PreviousSession?> =
        repository.observePreviousSets(exercise, workoutId)
            .map(::previousSession)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** This exercise's own rest length, or null when it uses the default. */
    fun restSecondsOf(exercise: String): StateFlow<Int?> =
        repository.observeRestSeconds(exercise)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The set in this session that is your best for the exercise, if one is.
     *
     * Judged against everything else logged, so a session that beats nothing
     * gets no badge, and a session that does gets exactly one. Only this
     * session's set is returned: the standing best from two months ago is
     * marked on the history screen, where it belongs.
     */
    fun recordsOf(exercise: String): StateFlow<Set<String>> =
        combine(
            repository.observePreviousSets(exercise, workoutId),
            repository.observeSets(workoutId),
        ) { earlier, session ->
            val today = session.filter { it.exercise == exercise && it.completed }
            val ids = bestSetIds(
                historyInOrder(earlier).map { it.recordCandidate() } +
                    today.map { it.recordCandidate() },
            )
            ids.intersect(today.map { it.id }.toSet())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** The metric this exercise was overridden to, or null for the default. */
    fun metricOverrideOf(exercise: String): StateFlow<ExerciseMetric?> =
        repository.observeMetricOverride(exercise)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Changes how this exercise is measured, here and everywhere it is logged
     * from now on. [metric] null goes back to the catalogue's choice.
     *
     * This session's sets are re-measured with it, so the change shows up where
     * it was made; earlier sessions keep the numbers they actually recorded.
     */
    fun setMetric(exercise: String, metric: ExerciseMetric?) {
        viewModelScope.launch { repository.setMetric(exercise, metric, workoutId) }
    }

    /** [seconds] null clears the override and goes back to the default. */
    fun setRestSeconds(exercise: String, seconds: Int?) {
        viewModelScope.launch { repository.setRestSeconds(exercise, seconds) }
    }

    fun addSet(exercise: String) {
        viewModelScope.launch { repository.addSet(workoutId, exercise) }
    }

    fun moveSet(id: String, delta: Int) {
        viewModelScope.launch { repository.moveSet(id, delta) }
    }

    fun reorderSets(exercise: String, orderedIds: List<String>) {
        viewModelScope.launch { repository.reorderSets(workoutId, exercise, orderedIds) }
    }

    fun copyLastSession(exercise: String) {
        viewModelScope.launch { repository.copyLastSession(workoutId, exercise) }
    }

    fun setCompleted(id: String, exercise: String, completed: Boolean) {
        viewModelScope.launch {
            repository.setCompleted(id, completed)
            // Ticking a set off is the moment rest begins. Adding one is
            // planning ahead, which should not start anything. Read the
            // exercise's own length here rather than from a cached flow, so a
            // rest just changed in the dialog applies to this very set.
            if (completed) {
                restTimer.startIfEnabled(repository.restSecondsFor(exercise), exercise)
            }
        }
    }

    fun updateSet(set: SetEntry) {
        viewModelScope.launch { repository.updateSet(set) }
    }

    fun deleteSet(id: String) {
        viewModelScope.launch { repository.deleteSet(id) }
    }
}

/** One exercise of a session: its sets, ticked off as they are done. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionExerciseScreen(
    workoutId: String,
    exercise: String,
    onBack: () -> Unit,
    onOpenHistory: (String) -> Unit,
    onOpenExercise: (String) -> Unit,
) {
    val viewModel = appViewModel(key = workoutId) { app ->
        SessionExerciseViewModel(app.repository, app.restTimer, app.restPrefs, workoutId)
    }
    val allSets by viewModel.sets.collectAsStateWithLifecycle()
    val previous by remember(exercise) { viewModel.previousSessionOf(exercise) }
        .collectAsStateWithLifecycle()
    val restOverride by remember(exercise) { viewModel.restSecondsOf(exercise) }
        .collectAsStateWithLifecycle()
    val restDefault by viewModel.restDefault.collectAsStateWithLifecycle()
    val metricOverride by remember(exercise) { viewModel.metricOverrideOf(exercise) }
        .collectAsStateWithLifecycle()
    val records by remember(exercise) { viewModel.recordsOf(exercise) }
        .collectAsStateWithLifecycle()
    var showRestDialog by remember { mutableStateOf(false) }
    var showMetricDialog by remember { mutableStateOf(false) }

    // What a new set here will ask for. Each existing set still shows the
    // fields it was logged with, which is what its own metric is for.
    val metric = metricOverride ?: ExerciseCatalog.metricFor(exercise) ?: ExerciseMetric.DEFAULT

    val sets = remember(allSets, exercise) { allSets.filter { it.exercise == exercise } }
    val next = remember(allSets, exercise) { nextExercise(allSets, exercise) }
    val finished = sets.isNotEmpty() && sets.all { it.completed }

    // Drawing order, owned here so a drag lands where it was dropped rather
    // than waiting on the write to come back. Re-seeded from the stored order,
    // including once that write lands.
    val byId = remember(sets) { sets.associateBy { it.id } }
    val stored = remember(sets) { sets.map { it.id } }
    var order by remember { mutableStateOf(stored) }
    LaunchedEffect(stored) { order = stored }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        // The sets are the first thing in this list, so the indices line up.
        if (from.index in order.indices && to.index in order.indices) {
            order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
            viewModel.reorderSets(exercise, order)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(exercise) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showMetricDialog = true }) {
                        Icon(
                            Icons.Outlined.Straighten,
                            contentDescription = "How $exercise is measured",
                        )
                    }
                    IconButton(onClick = { showRestDialog = true }) {
                        Icon(Icons.Outlined.Timer, contentDescription = "Rest length for $exercise")
                    }
                    IconButton(onClick = { onOpenHistory(exercise) }) {
                        Icon(Icons.Outlined.History, contentDescription = "History for $exercise")
                    }
                },
            )
        },
        bottomBar = { RestTimerBar() },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            state = listState,
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(order, key = { it }) { id ->
                ReorderableItem(reorderState, key = id) { dragging ->
                    val set = byId[id]
                    if (set != null) {
                        val index = order.indexOf(id)
                        SetRow(
                            set = set,
                            dragging = dragging,
                            record = set.id in records,
                            canMoveUp = index > 0,
                            canMoveDown = index < order.lastIndex,
                            onCompleted = { done ->
                                viewModel.setCompleted(set.id, exercise, done)
                            },
                            onUpdate = viewModel::updateSet,
                            onMove = { delta -> viewModel.moveSet(set.id, delta) },
                            onDelete = { viewModel.deleteSet(set.id) },
                            dragHandle = Modifier.draggableHandle(),
                        )
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { viewModel.addSet(exercise) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add set")
                }
            }
            previous?.let { last ->
                item {
                    LastTimeCard(
                        previous = last,
                        onCopy = { viewModel.copyLastSession(exercise) },
                    )
                }
            }
            if (finished) {
                item {
                    FinishedCard(
                        next = next,
                        onNext = { next?.let(onOpenExercise) },
                        onBack = onBack,
                    )
                }
            }
        }
    }

    if (showRestDialog) {
        RestLengthDialog(
            title = "Rest for $exercise",
            initialSeconds = restOverride ?: restDefault.seconds,
            supporting = if (restOverride == null) {
                "Currently using the default, ${formatCountdown(restDefault.seconds)}."
            } else {
                "Set for this exercise only. Every other exercise uses the " +
                    "default, ${formatCountdown(restDefault.seconds)}."
            },
            onDismiss = { showRestDialog = false },
            onConfirm = { seconds ->
                viewModel.setRestSeconds(exercise, seconds)
                showRestDialog = false
            },
            onClear = restOverride?.let {
                {
                    viewModel.setRestSeconds(exercise, null)
                    showRestDialog = false
                }
            },
            clearLabel = "Use the default instead",
        )
    }

    if (showMetricDialog) {
        MetricDialog(
            title = "How is $exercise measured?",
            initial = metric,
            supporting = "Sets already logged in this session are changed to " +
                "match. Earlier sessions keep what they recorded.",
            onDismiss = { showMetricDialog = false },
            onConfirm = { chosen ->
                viewModel.setMetric(exercise, chosen)
                showMetricDialog = false
            },
            onClear = metricOverride?.let {
                {
                    viewModel.setMetric(exercise, null)
                    showMetricDialog = false
                }
            },
        )
    }
}

@Composable
private fun SetRow(
    set: SetEntry,
    dragging: Boolean,
    record: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onCompleted: (Boolean) -> Unit,
    onUpdate: (SetEntry) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    dragHandle: Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (dragging) 8.dp else 0.dp,
        ),
    ) {
        // Above the fields rather than beside them: the row is already a
        // checkbox, up to three number fields and a menu button, and a cardio
        // set has no width left to give.
        if (record) {
            Row(
                modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Personal best",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Checkbox(checked = set.completed, onCheckedChange = onCompleted)
            SetFields(set = set, onUpdate = onUpdate, modifier = Modifier.weight(1f))
            // Moving and removing share one button: three controls plus two
            // fields do not fit a phone, and removing is no longer the only
            // thing you might want to do to a row.
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = dragHandle) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Set options")
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
                        text = { Text("Remove set") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Close, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/**
 * What you did last time, and a one-tap way to start from it. Copying replaces
 * the sets you have not ticked yet and leaves the ticked ones alone, so it is
 * safe to press mid-exercise.
 */
@Composable
private fun LastTimeCard(previous: PreviousSession, onCopy: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Last time · ${formatDay(previous.date)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                describeSets(previous.sets),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onCopy) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Copy these sets")
            }
        }
    }
}

/** Offers the next exercise once every set here is ticked off. */
@Composable
private fun FinishedCard(next: String?, onNext: () -> Unit, onBack: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("All sets done", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (next == null) {
                Text(
                    "That was the last exercise in this session.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onBack) { Text("Back to session") }
            } else {
                Text("Next up: $next", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onNext) { Text("Go to $next") }
            }
        }
    }
}

/**
 * The numbers one set is made of, chosen by the set's own metric rather than
 * the exercise's current one. A bench press asks for reps and kilos, a plank
 * for a duration, a bike ride for a distance and a duration.
 *
 * Reading the metric off the row is what lets a session survive being
 * recategorised mid-way: every set still shows the fields its numbers were
 * typed into.
 */
@Composable
private fun SetFields(set: SetEntry, onUpdate: (SetEntry) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val metric = ExerciseMetric.of(set.metric)
        if (metric.usesReps) {
            NumberField(
                initial = set.reps.toString(),
                label = "reps",
                modifier = Modifier.weight(1f),
                onValue = { text -> text.toIntOrNull()?.let { onUpdate(set.copy(reps = it)) } },
            )
        }
        if (metric.usesWeight) {
            NumberField(
                initial = formatWeight(set.weightKg),
                label = "kg",
                decimal = true,
                modifier = Modifier.weight(1f),
                onValue = { text -> text.toDoubleOrNull()?.let { onUpdate(set.copy(weightKg = it)) } },
            )
        }
        if (metric.usesDistance) {
            NumberField(
                initial = formatKilometres(set.meters),
                label = "km",
                decimal = true,
                modifier = Modifier.weight(1f),
                onValue = { text ->
                    text.toDoubleOrNull()?.let {
                        // Stored in metres, so a 0.4 km interval is a round 400.
                        onUpdate(set.copy(meters = Math.round(it * 1000).toDouble()))
                    }
                },
            )
        }
        if (metric.usesSeconds) {
            // Two fields rather than one "mm:ss", which needs its own parser
            // and punishes a typo by silently reading as something else.
            NumberField(
                initial = (set.seconds / 60).toString(),
                label = "min",
                modifier = Modifier.weight(1f),
                onValue = { text ->
                    text.toIntOrNull()?.let {
                        onUpdate(set.copy(seconds = it * 60 + set.seconds % 60))
                    }
                },
            )
            NumberField(
                initial = (set.seconds % 60).toString(),
                label = "sec",
                modifier = Modifier.weight(1f),
                onValue = { text ->
                    text.toIntOrNull()?.let {
                        onUpdate(set.copy(seconds = set.seconds / 60 * 60 + it))
                    }
                },
            )
        }
        // Reps alone would stretch one field across the whole row; keeping it
        // the width it has everywhere else keeps the list a column.
        if (metric == ExerciseMetric.REPS) Spacer(Modifier.weight(1f))
    }
}

/**
 * Numeric field that lets the user clear it mid-edit and only writes back once
 * the text parses. Every number here round-trips through the database, so the
 * buffer has to be held the same way the session name's is.
 */
@Composable
private fun NumberField(
    initial: String,
    label: String,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
    onValue: (String) -> Unit,
) {
    DraftTextField(
        value = initial,
        onValueChange = onValue,
        label = label,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
        transform = { raw -> raw.filter { it.isDigit() || (decimal && it == '.') } },
    )
}
