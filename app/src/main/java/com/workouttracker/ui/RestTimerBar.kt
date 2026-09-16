package com.workouttracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workouttracker.WorkoutApp

/**
 * The running rest countdown, for a screen's `bottomBar`. Draws nothing unless
 * a rest is running, so it costs an empty bar the rest of the time.
 *
 * It reads the timer straight off the application container rather than
 * through a ViewModel: one countdown is shared by every screen, and threading
 * it through each screen's own ViewModel only to display it would be wiring
 * for its own sake.
 */
@Composable
fun RestTimerBar() {
    val app = LocalContext.current.applicationContext as WorkoutApp
    val rest by app.restTimer.state.collectAsStateWithLifecycle()

    val seconds = rest?.remainingSeconds ?: return
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Rest", style = MaterialTheme.typography.labelMedium)
            Text(
                formatCountdown(seconds),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { app.restTimer.adjust(-15) }) { Text("−15s") }
            TextButton(onClick = { app.restTimer.adjust(15) }) { Text("+15s") }
            TextButton(onClick = { app.restTimer.stop() }) { Text("Skip") }
        }
    }
}
