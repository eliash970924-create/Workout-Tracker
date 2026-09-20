package com.workouttracker.data

/**
 * Muscle groups exercises are filed under.
 *
 * Stored by [name], never by ordinal or display text, so the list can be
 * reordered or relabelled without rewriting stored rows or breaking sync with a
 * device on an older version.
 */
enum class MuscleGroup(val displayName: String) {
    CHEST("Chest"),
    BACK("Back"),
    SHOULDERS("Shoulders"),
    BICEPS("Biceps"),
    TRICEPS("Triceps"),
    QUADS("Quads"),
    HAMSTRINGS("Hamstrings"),
    GLUTES("Glutes"),
    CALVES("Calves"),
    CORE("Core"),
    FOREARMS("Forearms"),
    FULL_BODY("Full body"),
    CARDIO("Cardio"),
    OTHER("Other");

    companion object {
        /** Unknown names fall back to [OTHER] rather than throwing. */
        fun of(stored: String?): MuscleGroup =
            entries.firstOrNull { it.name == stored } ?: OTHER
    }
}
