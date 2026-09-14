package com.workouttracker.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

object Routes {
    const val WORKOUTS = "workouts"
    const val HISTORY = "history"
    const val SETTINGS = "settings"

    const val WORKOUT_DETAIL = "workout/{workoutId}"
    const val SESSION_EXERCISE = "workout/{workoutId}/exercise/{exercise}"
    const val EXERCISE_HISTORY = "history/{exercise}"

    fun workout(id: String) = "workout/$id"

    fun sessionExercise(workoutId: String, exercise: String) =
        "workout/$workoutId/exercise/${Uri.encode(exercise)}"

    /** Exercise names contain spaces and punctuation, so they must be encoded. */
    fun exerciseHistory(exercise: String) = "history/${Uri.encode(exercise)}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.WORKOUTS, "Workouts", Icons.Outlined.FitnessCenter),
    Tab(Routes.HISTORY, "History", Icons.Outlined.History),
    Tab(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
)

@Composable
fun WorkoutNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            // Only on the top-level tabs; detail screens get the full height.
            if (currentRoute in tabs.map { it.route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navController.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.WORKOUTS,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.WORKOUTS) {
                WorkoutListScreen(
                    onOpenWorkout = { id -> navController.navigate(Routes.workout(id)) },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    onOpenExercise = { exercise ->
                        navController.navigate(Routes.exerciseHistory(exercise))
                    },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen()
            }
            composable(
                route = Routes.WORKOUT_DETAIL,
                arguments = listOf(navArgument("workoutId") { type = NavType.StringType }),
            ) { entry ->
                val workoutId = entry.arguments?.getString("workoutId").orEmpty()
                WorkoutDetailScreen(
                    workoutId = workoutId,
                    onBack = { navController.popBackStack() },
                    onOpenExercise = { exercise ->
                        navController.navigate(Routes.sessionExercise(workoutId, exercise))
                    },
                )
            }
            composable(
                route = Routes.SESSION_EXERCISE,
                arguments = listOf(
                    navArgument("workoutId") { type = NavType.StringType },
                    navArgument("exercise") { type = NavType.StringType },
                ),
            ) { entry ->
                val workoutId = entry.arguments?.getString("workoutId").orEmpty()
                SessionExerciseScreen(
                    workoutId = workoutId,
                    exercise = entry.arguments?.getString("exercise").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenHistory = { exercise ->
                        navController.navigate(Routes.exerciseHistory(exercise))
                    },
                    onOpenExercise = { exercise ->
                        // Replace rather than stack, so backing out of the last
                        // exercise returns to the session and not through every
                        // exercise you worked through to get here.
                        navController.navigate(Routes.sessionExercise(workoutId, exercise)) {
                            popUpTo(Routes.SESSION_EXERCISE) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route = Routes.EXERCISE_HISTORY,
                arguments = listOf(navArgument("exercise") { type = NavType.StringType }),
            ) { entry ->
                ExerciseHistoryScreen(
                    exercise = entry.arguments?.getString("exercise").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/** Standard tab behaviour: one entry per tab, state preserved, no back stack pile-up. */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
