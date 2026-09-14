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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.workouttracker.data.SetEntry
import com.workouttracker.data.WorkoutRepository
import com.workouttracker.rest.RestTimer
import kotlinx.coroutines.flow.SharingStarted
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

class SessionExerciseViewModel(
    private val repository: WorkoutRepository,
    private val restTimer: RestTimer,
    private val workoutId: String,
) : ViewModel() {

    /** Every set in the session, not just this exercise's: the order of the
     * whole list is what decides which exercise comes next. */
    val sets: StateFlow<List<SetEntry>> = repository.observeSets(workoutId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addSet(exercise: String) {
        viewModelScope.launch { repository.addSet(workoutId, exercise) }
    }

    fun setCompleted(id: String, completed: Boolean) {
        viewModelScope.launch {
            repository.setCompleted(id, completed)
            // Ticking a set off is the moment rest begins. Adding one is
            // planning ahead, which should not start anything.
            if (completed) restTimer.startIfEnabled()
        }
    }

    fun updateSet(set: SetEntry, reps: Int = set.reps, weightKg: Double = set.weightKg) {
        viewModelScope.launch { repository.updateSet(set.copy(reps = reps, weightKg = weightKg)) }
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
        SessionExerciseViewModel(app.repository, app.restTimer, workoutId)
    }
    val allSets by viewModel.sets.collectAsStateWithLifecycle()

    val sets = remember(allSets, exercise) { allSets.filter { it.exercise == exercise } }
    val next = remember(allSets, exercise) { nextExercise(allSets, exercise) }
    val finished = sets.isNotEmpty() && sets.all { it.completed }

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
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sets, key = { it.id }) { set ->
                SetRow(
                    set = set,
                    onCompleted = { done -> viewModel.setCompleted(set.id, done) },
                    onUpdate = { reps, weight -> viewModel.updateSet(set, reps, weight) },
                    onDelete = { viewModel.deleteSet(set.id) },
                )
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
}

@Composable
private fun SetRow(
    set: SetEntry,
    onCompleted: (Boolean) -> Unit,
    onUpdate: (reps: Int, weightKg: Double) -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Checkbox(checked = set.completed, onCheckedChange = onCompleted)
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
