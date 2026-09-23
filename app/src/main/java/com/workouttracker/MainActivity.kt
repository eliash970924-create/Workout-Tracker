package com.workouttracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workouttracker.ui.PendingSession
import com.workouttracker.ui.WorkoutNavHost
import com.workouttracker.ui.theme.WorkoutTrackerTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /**
     * Where a notification asked us to go, until the navigation host has taken
     * it. Held here rather than read straight from the intent because the same
     * intent stays attached to the activity: without clearing it, every
     * recomposition would want to navigate again.
     */
    private val pending = MutableStateFlow<PendingSession?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pending.value = sessionFrom(intent)
        setContent {
            WorkoutTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val openSession by pending.collectAsStateWithLifecycle()
                    WorkoutNavHost(
                        openSession = openSession,
                        onSessionOpened = { pending.value = null },
                    )
                }
            }
        }
    }

    /**
     * A rest notification is tapped while the app is still running far more
     * often than not, and that arrives here rather than at [onCreate].
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pending.value = sessionFrom(intent)
    }

    private fun sessionFrom(intent: Intent?): PendingSession? {
        val workoutId = intent?.getStringExtra(EXTRA_WORKOUT_ID) ?: return null
        return PendingSession(workoutId, intent.getStringExtra(EXTRA_EXERCISE))
    }

    companion object {
        const val EXTRA_WORKOUT_ID = "com.workouttracker.WORKOUT_ID"
        const val EXTRA_EXERCISE = "com.workouttracker.EXERCISE"
    }
}
