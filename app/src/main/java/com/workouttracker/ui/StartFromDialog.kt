package com.workouttracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.workouttracker.data.SessionTemplate

/**
 * Picks an earlier session to start this one from.
 *
 * Sessions are listed as they happened rather than collapsed by name: two
 * Push days a fortnight apart are different sessions, and the exercise line is
 * what tells them apart. The newest of each is at the top anyway, so the
 * routine you repeat is the one you reach first.
 */
@Composable
fun StartFromDialog(
    sessions: List<SessionTemplate>,
    onDismiss: () -> Unit,
    onPick: (SessionTemplate) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start from") },
        text = {
            if (sessions.isEmpty()) {
                Text(
                    "Nothing to copy yet. Once you have logged a session with " +
                        "some exercises in it, it will show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                // Bounded so a long history cannot push the buttons off screen.
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(sessions, key = { it.id }) { session ->
                        SessionRow(session, onClick = { onPick(session) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SessionRow(session: SessionTemplate, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(session.name, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        Text(
            "${formatDay(session.date)} · ${session.setCount} sets",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            session.exercises.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // The exercises are what identify a session, but not at the cost
            // of a row three lines tall.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
