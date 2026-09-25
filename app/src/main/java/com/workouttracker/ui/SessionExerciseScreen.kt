package com.workouttracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
import com.workouttracker.rest.RestNext
import com.workouttracker.rest.RestPrefs
import com.workouttracker.rest.RestSettings
import com.workouttracker.rest.RestTimer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Exercise names in the order they appear in the session. Sets arrive ordered
 * by position, so first appearance is the session's own order.
 */
fun exerciseOrder(sets: List<SetEntry>): List<String> = sets.map { it.exercise }.distinct()

/**
 * The session's other exercises that still have a set to do, in the order to
 * suggest them: the ones after [current] first, then round to the ones before.
 *
 * Wrapping round is the point. A busy machine sends you ahead to the next
 * exercise, and when that one is finished the one you skipped is still
 * waiting -- "the one after this" would say the session was over. Exercises
 * already finished are left out, so jumping back to one you did first does not
 * send you to it again.
 */
fun remainingExercises(sets: List<SetEntry>, current: String): List<String> {
    val order = exerciseOrder(sets)
    val unfinished = sets.filter { !it.completed }.map { it.exercise }.toSet()
    val index = order.indexOf(current)
    val rotated = if (index < 0) order else order.drop(index + 1) + order.take(index)
    return rotated.filter { it != current && it in unfinished }
}

/**
 * What the rest after ticking a set of [exercise] leads to, or null when it is
 * simply the next set of the same exercise.
 *
 * Only once the exercise is finished is there anything different to say, and
 * "time for your next set of lateral raises" after the last one was wrong.
 */
fun restNext(sets: List<SetEntry>, exercise: String): RestNext? {
    val block = blockOf(sets, exercise)
    if (sets.any { it.exercise in block.members && !it.completed }) {
        // More of the same. For one exercise the usual "next set of" says so;
        // a superset gets a round, since the next set is of its first member.
        return if (block is Block.Superset) {
            RestNext("Next round: ${block.label}.", exercise = block.members.first())
        } else {
            null
        }
    }
    val following = remainingExercises(sets, exercise).firstOrNull()
        ?: return RestNext("That was the last set of the session.", exercise = null)
    return RestNext("Time for ${blockOf(sets, following).label}.", exercise = following)
}

/**
 * The most recent earlier session of an exercise, for the "last time" card,
 * with whatever you wrote about the exercise then.
 */
data class PreviousSession(
    val date: Long,
    val sets: List<SetWithSession>,
    val workoutId: String = "",
    val note: String? = null,
)

/**
 * The newest session in [sets], which arrive newest first. Grouping by session
 * rather than taking a fixed count keeps a session whole however many sets it
 * held.
 */
fun previousSession(sets: List<SetWithSession>): PreviousSession? {
    val newest = sets.firstOrNull() ?: return null
    return PreviousSession(
        date = newest.workoutDate,
        sets = sets.takeWhile {
            it.workoutDate == newest.workoutDate && it.workoutId == newest.workoutId
        },
        workoutId = newest.workoutId,
    )
}

/**
 * One set in words, read by its own metric: "5 × 100 kg", "12 reps", "1:30",
 * "5.2 km in 25:00".
 */
fun describeSet(set: SetWithSession): String =
    describe(ExerciseMetric.of(set.metric), set.reps, set.weightKg, set.seconds, set.meters)

fun describeSet(set: SetEntry): String =
    describe(ExerciseMetric.of(set.metric), set.reps, set.weightKg, set.seconds, set.meters)

private fun describe(
    metric: ExerciseMetric,
    reps: Int,
    weightKg: Double,
    seconds: Int,
    meters: Double,
): String = when (metric) {
    ExerciseMetric.WEIGHT_REPS -> "$reps × ${formatWeight(weightKg)} kg"
    ExerciseMetric.REPS -> "$reps reps"
    ExerciseMetric.TIME -> formatDuration(seconds)
    ExerciseMetric.DISTANCE_TIME -> "${formatDistance(meters)} in ${formatDuration(seconds)}"
}

/** A session's sets in a line, trailing off once it would get long. */
fun describeSets(sets: List<SetWithSession>, limit: Int = 4): String {
    val shown = sets.take(limit).joinToString(", ") { describeSet(it) }
    return if (sets.size > limit) "$shown, …" else shown
}

/** See [SessionExerciseViewModel.bestsOf]. */
data class ExerciseBests(
    val todayIds: Set<String> = emptySet(),
    val best: PersonalBest? = null,
)

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
        combine(
            repository.observePreviousSets(exercise, workoutId),
            repository.observeNotesOf(exercise),
        ) { sets, notes ->
            previousSession(sets)?.let { it.copy(note = notes[it.workoutId]) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** What you have written about this exercise in this session. */
    fun noteOf(exercise: String): StateFlow<String> =
        repository.observeExerciseNote(workoutId, exercise)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setNote(exercise: String, text: String) {
        viewModelScope.launch { repository.setExerciseNote(workoutId, exercise, text) }
    }

    /** This exercise's own rest length, or null when it uses the default. */
    fun restSecondsOf(exercise: String): StateFlow<Int?> =
        repository.observeRestSeconds(exercise)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Your best at this exercise: the set that holds it, if it is one of this
     * session's, and the best itself for the banner.
     *
     * One flow for both so the star and the banner are worked out from the
     * same sets at the same moment and cannot disagree. Judged against
     * everything else logged, so a session that beats nothing badges nothing;
     * the best from two months ago is named in the banner and starred on the
     * history screen, not badged here.
     */
    fun bestsOf(exercise: String): StateFlow<ExerciseBests> =
        combine(
            repository.observePreviousSets(exercise, workoutId),
            repository.observeSets(workoutId),
            repository.observeMetricOverride(exercise),
        ) { earlier, session, override ->
            val today = session.filter { it.exercise == exercise && it.completed }
            val ids = bestSetIds(
                historyInOrder(earlier).map { it.recordCandidate() } +
                    today.map { it.recordCandidate() },
            )
            val metric = override ?: ExerciseCatalog.metricFor(exercise) ?: ExerciseMetric.DEFAULT
            ExerciseBests(
                todayIds = ids.intersect(today.map { it.id }.toSet()),
                best = personalBest(earlier, today, metric),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExerciseBests())

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
                // Read fresh rather than from [sets]: the tick just written may
                // not have come round the flow yet, and whether it was the last
                // set of the exercise is exactly what it decides.
                val session = repository.observeSets(workoutId).first()
                val block = blockOf(session, exercise)
                if (!completesRound(session, block.members, exercise)) {
                    // Mid-round in a superset: straight on to the next exercise,
                    // no rest. One still running from the last round is over,
                    // too -- the round it was resting for has started.
                    restTimer.stop()
                    return@launch
                }
                restTimer.startIfEnabled(
                    seconds = restAfter(block),
                    label = block.label,
                    workoutId = workoutId,
                    next = restNext(session, exercise),
                )
            }
        }
    }

    /**
     * How long to rest after [block]: the exercise's own length, or for a
     * superset the longest of its members' -- it rests once for all of them, so
     * as long as the one that needs it most. Null means the default.
     */
    private suspend fun restAfter(block: Block): Int? {
        if (block.members.size == 1) return repository.restSecondsFor(block.members.first())
        val default = restDefault.value.seconds
        return block.members.maxOf { repository.restSecondsFor(it) ?: default }
    }

    fun updateSet(set: SetEntry) {
        viewModelScope.launch { repository.updateSet(set) }
    }

    fun deleteSet(id: String) {
        viewModelScope.launch { repository.deleteSet(id) }
    }
}

/** What the screen shows about one exercise besides its sets. */
private data class ExerciseDetails(
    val previous: PreviousSession?,
    val restOverride: Int?,
    val metricOverride: ExerciseMetric?,
    val bests: ExerciseBests,
    val note: String,
) {
    /** What a new set of it will ask for. */
    fun metricFor(exercise: String): ExerciseMetric =
        metricOverride ?: ExerciseCatalog.metricFor(exercise) ?: ExerciseMetric.DEFAULT
}

@Composable
private fun rememberDetails(viewModel: SessionExerciseViewModel, exercise: String): ExerciseDetails {
    val previous by remember(exercise) { viewModel.previousSessionOf(exercise) }
        .collectAsStateWithLifecycle()
    val restOverride by remember(exercise) { viewModel.restSecondsOf(exercise) }
        .collectAsStateWithLifecycle()
    val metricOverride by remember(exercise) { viewModel.metricOverrideOf(exercise) }
        .collectAsStateWithLifecycle()
    val bests by remember(exercise) { viewModel.bestsOf(exercise) }
        .collectAsStateWithLifecycle()
    val note by remember(exercise) { viewModel.noteOf(exercise) }
        .collectAsStateWithLifecycle()
    return ExerciseDetails(previous, restOverride, metricOverride, bests, note)
}

/**
 * One exercise of a session: its sets, ticked off as they are done.
 *
 * Or, for an exercise in a superset, the whole superset: each member's sets
 * under its own heading, on one screen, since you go back and forth between
 * them without stopping. Whichever member was opened, the screen is the same.
 */
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
    val restDefault by viewModel.restDefault.collectAsStateWithLifecycle()
    var restDialogFor by remember { mutableStateOf<String?>(null) }
    var metricDialogFor by remember { mutableStateOf<String?>(null) }

    val block = remember(allSets, exercise) { blockOf(allSets, exercise) }
    val members = block.members
    val superset = members.size > 1
    // Keyed by name, so each member keeps its own flows as others come and go.
    val details = members.associateWith { member -> key(member) { rememberDetails(viewModel, member) } }

    val sets = remember(allSets, members) { allSets.filter { it.exercise in members } }
    val finished = sets.isNotEmpty() && sets.all { it.completed }
    // What is left, a superset offered once under its full name.
    val remaining = remember(allSets, exercise) {
        remainingExercises(allSets, exercise)
            .filter { it !in members }
            .map { blockOf(allSets, it) }
            .distinctBy { it.key }
    }

    // Drawing order, owned here so a drag lands where it was dropped rather
    // than waiting on the write to come back. Re-seeded from the stored order,
    // including once that write lands. Per exercise: a set belongs to its
    // exercise, and a drag cannot take it into another's.
    val byId = remember(sets) { sets.associateBy { it.id } }
    val stored = remember(sets, members) {
        members.associateWith { member -> sets.filter { it.exercise == member }.map { it.id } }
    }
    var order by remember { mutableStateOf(stored) }
    LaunchedEffect(stored) { order = stored }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        // By key: the list also holds headings, buttons and cards, and in a
        // superset more than one exercise's sets.
        val member = order.entries.firstOrNull { entry -> entry.value.any { it == from.key } }?.key
        val ids = member?.let { order.getValue(it) }.orEmpty()
        val fromIndex = ids.indexOfFirst { it == from.key }
        val toIndex = ids.indexOfFirst { it == to.key }
        if (member != null && fromIndex >= 0 && toIndex >= 0) {
            val moved = ids.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
            order = order + (member to moved)
            viewModel.reorderSets(member, moved)
        }
    }

    // The single exercise's own, for the top bar; unused in a superset, where
    // each member's heading carries them.
    val own = details.getValue(members.first())

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (superset) {
                        Column {
                            Text("Superset", style = MaterialTheme.typography.labelMedium)
                            Text(
                                block.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    } else {
                        Text(exercise)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!superset) {
                        IconButton(onClick = { metricDialogFor = exercise }) {
                            Icon(
                                Icons.Outlined.Straighten,
                                contentDescription = "How $exercise is measured",
                            )
                        }
                        // The length itself rather than a bare clock: whether
                        // this exercise rests for its own time or the default
                        // used to need opening the dialog to find out.
                        TextButton(
                            onClick = { restDialogFor = exercise },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (own.restOverride != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            ),
                        ) {
                            Icon(
                                Icons.Outlined.Timer,
                                contentDescription = when {
                                    !restDefault.enabled -> "Rest timer is off"
                                    own.restOverride != null -> "Rest for $exercise, set for this exercise"
                                    else -> "Rest for $exercise, using the default"
                                },
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (restDefault.enabled) {
                                    formatCountdown(own.restOverride ?: restDefault.seconds)
                                } else {
                                    "Off"
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        IconButton(onClick = { onOpenHistory(exercise) }) {
                            Icon(Icons.Outlined.History, contentDescription = "History for $exercise")
                        }
                    }
                },
            )
        },
        bottomBar = { RestTimerBar() },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (superset) {
                // Pinned, like the best banner is for one exercise: when the
                // rest comes is the thing about a superset that is different.
                SupersetRestBanner(
                    seconds = if (restDefault.enabled) {
                        members.maxOf { details.getValue(it).restOverride ?: restDefault.seconds }
                    } else {
                        null
                    },
                )
            } else {
                // Above the list rather than inside it: pinned, so the number
                // to beat is still there with the list scrolled.
                own.bests.best?.let { BestBanner(it) }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = listState,
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                members.forEach { member ->
                    val memberDetails = details.getValue(member)
                    val ids = order[member].orEmpty()
                    if (superset) {
                        item(key = "heading:$member") {
                            MemberHeading(
                                exercise = member,
                                details = memberDetails,
                                restDefault = restDefault,
                                onHistory = { onOpenHistory(member) },
                                onMetric = { metricDialogFor = member },
                                onRest = { restDialogFor = member },
                            )
                        }
                    }
                    items(ids, key = { it }) { id ->
                        ReorderableItem(reorderState, key = id) { dragging ->
                            val set = byId[id]
                            if (set != null) {
                                val index = ids.indexOf(id)
                                SetRow(
                                    set = set,
                                    dragging = dragging,
                                    record = set.id in memberDetails.bests.todayIds,
                                    canMoveUp = index > 0,
                                    canMoveDown = index < ids.lastIndex,
                                    onCompleted = { done ->
                                        viewModel.setCompleted(set.id, member, done)
                                    },
                                    onUpdate = viewModel::updateSet,
                                    onMove = { delta -> viewModel.moveSet(set.id, delta) },
                                    onDelete = { viewModel.deleteSet(set.id) },
                                    dragHandle = Modifier.draggableHandle(),
                                )
                            }
                        }
                    }
                    item(key = "add:$member") {
                        OutlinedButton(
                            onClick = { viewModel.addSet(member) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (superset) "Add set of $member" else "Add set")
                        }
                    }
                    item(key = "note:$member") {
                        // The session's own notes are on the session screen;
                        // this is for the exercise, and comes back with it
                        // next time in the "last time" card.
                        DraftTextField(
                            value = memberDetails.note,
                            onValueChange = { viewModel.setNote(member, it) },
                            label = if (superset) "Notes on $member" else "Notes on this exercise",
                            singleLine = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    memberDetails.previous?.let { last ->
                        item(key = "last:$member") {
                            LastTimeCard(
                                previous = last,
                                onCopy = { viewModel.copyLastSession(member) },
                            )
                        }
                    }
                }
                if (finished) {
                    item(key = "finished") {
                        FinishedCard(
                            remaining = remaining,
                            onOpen = onOpenExercise,
                            onBack = onBack,
                        )
                    }
                }
            }
        }
    }

    restDialogFor?.let { target ->
        val restOverride = details[target]?.restOverride
        RestLengthDialog(
            title = "Rest for $target",
            initialSeconds = restOverride ?: restDefault.seconds,
            supporting = when {
                restOverride == null ->
                    "Currently using the default, ${formatCountdown(restDefault.seconds)}."
                else -> "Set for this exercise only. Every other exercise uses the " +
                    "default, ${formatCountdown(restDefault.seconds)}."
            } + if (superset) " A superset rests as long as its longest." else "",
            onDismiss = { restDialogFor = null },
            onConfirm = { seconds ->
                viewModel.setRestSeconds(target, seconds)
                restDialogFor = null
            },
            onClear = restOverride?.let {
                {
                    viewModel.setRestSeconds(target, null)
                    restDialogFor = null
                }
            },
            clearLabel = "Use the default instead",
        )
    }

    metricDialogFor?.let { target ->
        val targetDetails = details[target]
        MetricDialog(
            title = "How is $target measured?",
            initial = targetDetails?.metricFor(target)
                ?: ExerciseCatalog.metricFor(target) ?: ExerciseMetric.DEFAULT,
            supporting = "Sets already logged in this session are changed to " +
                "match. Earlier sessions keep what they recorded.",
            onDismiss = { metricDialogFor = null },
            onConfirm = { chosen ->
                viewModel.setMetric(target, chosen)
                metricDialogFor = null
            },
            onClear = targetDetails?.metricOverride?.let {
                {
                    viewModel.setMetric(target, null)
                    metricDialogFor = null
                }
            },
        )
    }
}

/**
 * One exercise's heading inside a superset: its name, its all-time best, and
 * behind a menu what the top bar offers for an exercise on its own.
 */
@Composable
private fun MemberHeading(
    exercise: String,
    details: ExerciseDetails,
    restDefault: RestSettings,
    onHistory: () -> Unit,
    onMetric: () -> Unit,
    onRest: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(exercise, style = MaterialTheme.typography.titleMedium)
            details.bests.best?.let { best ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "All-time best: ${best.set}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "$exercise options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            when {
                                !restDefault.enabled -> "Rest length…"
                                details.restOverride != null ->
                                    "Rest length (${formatCountdown(details.restOverride)})…"
                                else -> "Rest length (default)…"
                            },
                        )
                    },
                    leadingIcon = { Icon(Icons.Outlined.Timer, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRest()
                    },
                )
                DropdownMenuItem(
                    text = { Text("How it's measured…") },
                    leadingIcon = { Icon(Icons.Outlined.Straighten, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onMetric()
                    },
                )
                DropdownMenuItem(
                    text = { Text("History") },
                    leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onHistory()
                    },
                )
            }
        }
    }
}

/** When a superset rests: after each round, for the longest of its members' rests. */
@Composable
private fun SupersetRestBanner(seconds: Int?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Timer,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (seconds == null) {
                    "No rest between exercises. The rest timer is off."
                } else {
                    "No rest between exercises. ${formatCountdown(seconds)} after each round."
                },
                style = MaterialTheme.typography.titleSmall,
            )
        }
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
 * Your best at this exercise, pinned above the sets: the number to beat, and
 * when you set it. Updates the moment a ticked set beats it, alongside the
 * badge on that set.
 */
@Composable
private fun BestBanner(best: PersonalBest) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                // "All-time" spelled out: plain "Best" next to a date reads as
                // the best of that session rather than of every session.
                "All-time best: ${best.set}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                // A session can be dated other than today, so "this session"
                // rather than "today" for a best set in the one in hand.
                best.date?.let { formatDay(it) } ?: "This session",
                style = MaterialTheme.typography.labelMedium,
            )
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
            previous.note?.let { note ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "Note: $note",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onCopy) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Copy these sets")
            }
        }
    }
}

/**
 * Once every set here is ticked off: the obvious next exercise as the big
 * button, and behind a second one a menu of everything still left -- the
 * obvious one included, in case you change your mind -- for when it is not the
 * one you are going to do: a machine is taken, or you would rather.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinishedCard(remaining: List<Block>, onOpen: (String) -> Unit, onBack: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("All sets done", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            val next = remaining.firstOrNull()
            if (next == null) {
                Text(
                    "That's everything in this session.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onBack) { Text("Back to session") }
            } else {
                Text("Next exercise: ${next.label}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { onOpen(next.members.first()) }) { Text("Go to ${next.label}") }
                    // With only one left, the menu would offer the same thing
                    // as the button beside it.
                    if (remaining.size > 1) {
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { menuOpen = true }) {
                                Text("Other exercise")
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                remaining.forEach { block ->
                                    DropdownMenuItem(
                                        text = { Text(block.label) },
                                        onClick = {
                                            menuOpen = false
                                            onOpen(block.members.first())
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
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
