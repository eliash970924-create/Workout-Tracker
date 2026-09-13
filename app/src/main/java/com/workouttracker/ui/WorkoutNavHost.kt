package com.workouttracker.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

object Routes {
    const val LIST = "workouts"
    const val SETTINGS = "settings"
    const val WORKOUT = "workout/{workoutId}"
    fun workout(id: String) = "workout/$id"
}

@Composable
fun WorkoutNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.LIST) {
        composable(Routes.LIST) {
            WorkoutListScreen(
                onOpenWorkout = { id -> navController.navigate(Routes.workout(id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.WORKOUT,
            arguments = listOf(navArgument("workoutId") { type = NavType.StringType }),
        ) { entry ->
            val workoutId = entry.arguments?.getString("workoutId").orEmpty()
            WorkoutDetailScreen(
                workoutId = workoutId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
