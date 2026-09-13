package com.workouttracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.workouttracker.WorkoutApp

/**
 * Builds a ViewModel from the app's singleton container. Saves wiring a DI
 * framework in for three screens.
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (WorkoutApp) -> VM,
): VM {
    val app = LocalContext.current.applicationContext as WorkoutApp
    return viewModel(
        key = key,
        factory = viewModelFactory { initializer { create(app) } },
    )
}
