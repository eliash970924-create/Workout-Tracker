package com.workouttracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.workouttracker.data.ExerciseMetric

/**
 * Picks how an exercise is measured.
 *
 * A list with each option's description rather than chips, because the choice
 * is not obvious from four two-word labels -- "Time" and "Distance & time"
 * differ by what they ask you for, which is worth spelling out.
 */
@Composable
fun MetricDialog(
    title: String,
    initial: ExerciseMetric,
    supporting: String,
    onDismiss: () -> Unit,
    onConfirm: (ExerciseMetric) -> Unit,
    confirmLabel: String = "Save",
    /** Offered only when there is an override to drop. */
    onClear: (() -> Unit)? = null,
) {
    var selected by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                ExerciseMetric.entries.forEach { option ->
                    MetricOption(
                        option = option,
                        selected = selected == option,
                        onSelect = { selected = option },
                    )
                }
                onClear?.let {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = it) { Text("Use the default instead") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MetricOption(option: ExerciseMetric, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row handles the click, so the button itself does not: two
        // targets for one choice makes the row feel half-tappable.
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(option.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                option.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
