package com.workouttracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.workouttracker.rest.MAX_REST_SECONDS
import com.workouttracker.rest.MIN_REST_SECONDS

/**
 * Types a rest length in minutes and seconds.
 *
 * Shared by Settings, where it sets the default, and by an exercise, where it
 * sets that exercise's own length -- [onClear] is what the second case adds, to
 * drop the override and go back to the default.
 */
@Composable
fun RestLengthDialog(
    title: String,
    initialSeconds: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    onClear: (() -> Unit)? = null,
    clearLabel: String = "Use the default",
    supporting: String? = null,
) {
    var minutes by remember { mutableStateOf((initialSeconds / 60).toString()) }
    var seconds by remember { mutableStateOf((initialSeconds % 60).toString()) }
    val total = (minutes.toIntOrNull() ?: 0) * 60 + (seconds.toIntOrNull() ?: 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                supporting?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberBox(
                        value = minutes,
                        label = "min",
                        onChange = { minutes = it },
                        modifier = Modifier.weight(1f),
                    )
                    NumberBox(
                        value = seconds,
                        label = "sec",
                        onChange = { seconds = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Anything from ${formatCountdown(MIN_REST_SECONDS)} to " +
                        "${formatCountdown(MAX_REST_SECONDS)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                onClear?.let { clear ->
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = clear) { Text(clearLabel) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(total) }, enabled = total >= MIN_REST_SECONDS) {
                Text("Set")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NumberBox(
    value: String,
    label: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> onChange(raw.filter(Char::isDigit).take(2)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
