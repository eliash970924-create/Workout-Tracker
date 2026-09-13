package com.workouttracker.data

/** An exercise offered by the app, as opposed to one the user typed in. */
data class CatalogExercise(val name: String, val muscleGroup: MuscleGroup)

/**
 * The built-in exercise list: common lifts for each muscle group.
 *
 * This is static code rather than seeded database rows, which keeps it out of
 * the Drive snapshot — every install has the same catalogue, so syncing it
 * would be a hundred rows of pure duplication. Only exercises the user creates
 * are stored and synced.
 *
 * Names are the identity used by set rows and history, so changing one orphans
 * the history logged under the old spelling. Add freely; rename with care.
 */
object ExerciseCatalog {

    val all: List<CatalogExercise> = listOf(
        // Chest
        e("Barbell Bench Press", MuscleGroup.CHEST),
        e("Incline Barbell Bench Press", MuscleGroup.CHEST),
        e("Decline Barbell Bench Press", MuscleGroup.CHEST),
        e("Dumbbell Bench Press", MuscleGroup.CHEST),
        e("Incline Dumbbell Press", MuscleGroup.CHEST),
        e("Dumbbell Fly", MuscleGroup.CHEST),
        e("Cable Fly", MuscleGroup.CHEST),
        e("Chest Press Machine", MuscleGroup.CHEST),
        e("Pec Deck", MuscleGroup.CHEST),
        e("Push-Up", MuscleGroup.CHEST),
        e("Chest Dip", MuscleGroup.CHEST),

        // Back
        e("Deadlift", MuscleGroup.BACK),
        e("Barbell Row", MuscleGroup.BACK),
        e("Pendlay Row", MuscleGroup.BACK),
        e("Dumbbell Row", MuscleGroup.BACK),
        e("Pull-Up", MuscleGroup.BACK),
        e("Chin-Up", MuscleGroup.BACK),
        e("Lat Pulldown", MuscleGroup.BACK),
        e("Seated Cable Row", MuscleGroup.BACK),
        e("T-Bar Row", MuscleGroup.BACK),
        e("Chest-Supported Row", MuscleGroup.BACK),
        e("Straight-Arm Pulldown", MuscleGroup.BACK),
        e("Face Pull", MuscleGroup.BACK),
        e("Back Extension", MuscleGroup.BACK),

        // Shoulders
        e("Overhead Press", MuscleGroup.SHOULDERS),
        e("Dumbbell Shoulder Press", MuscleGroup.SHOULDERS),
        e("Arnold Press", MuscleGroup.SHOULDERS),
        e("Push Press", MuscleGroup.SHOULDERS),
        e("Lateral Raise", MuscleGroup.SHOULDERS),
        e("Cable Lateral Raise", MuscleGroup.SHOULDERS),
        e("Front Raise", MuscleGroup.SHOULDERS),
        e("Rear Delt Fly", MuscleGroup.SHOULDERS),
        e("Upright Row", MuscleGroup.SHOULDERS),
        e("Barbell Shrug", MuscleGroup.SHOULDERS),
        e("Dumbbell Shrug", MuscleGroup.SHOULDERS),

        // Biceps
        e("Barbell Curl", MuscleGroup.BICEPS),
        e("EZ-Bar Curl", MuscleGroup.BICEPS),
        e("Dumbbell Curl", MuscleGroup.BICEPS),
        e("Hammer Curl", MuscleGroup.BICEPS),
        e("Incline Dumbbell Curl", MuscleGroup.BICEPS),
        e("Preacher Curl", MuscleGroup.BICEPS),
        e("Cable Curl", MuscleGroup.BICEPS),
        e("Concentration Curl", MuscleGroup.BICEPS),

        // Triceps
        e("Close-Grip Bench Press", MuscleGroup.TRICEPS),
        e("Triceps Pushdown", MuscleGroup.TRICEPS),
        e("Rope Pushdown", MuscleGroup.TRICEPS),
        e("Overhead Triceps Extension", MuscleGroup.TRICEPS),
        e("Skull Crusher", MuscleGroup.TRICEPS),
        e("Triceps Dip", MuscleGroup.TRICEPS),
        e("Triceps Kickback", MuscleGroup.TRICEPS),

        // Quads
        e("Back Squat", MuscleGroup.QUADS),
        e("Front Squat", MuscleGroup.QUADS),
        e("Goblet Squat", MuscleGroup.QUADS),
        e("Hack Squat", MuscleGroup.QUADS),
        e("Leg Press", MuscleGroup.QUADS),
        e("Bulgarian Split Squat", MuscleGroup.QUADS),
        e("Walking Lunge", MuscleGroup.QUADS),
        e("Leg Extension", MuscleGroup.QUADS),
        e("Step-Up", MuscleGroup.QUADS),

        // Hamstrings
        e("Romanian Deadlift", MuscleGroup.HAMSTRINGS),
        e("Stiff-Leg Deadlift", MuscleGroup.HAMSTRINGS),
        e("Lying Leg Curl", MuscleGroup.HAMSTRINGS),
        e("Seated Leg Curl", MuscleGroup.HAMSTRINGS),
        e("Good Morning", MuscleGroup.HAMSTRINGS),
        e("Nordic Curl", MuscleGroup.HAMSTRINGS),

        // Glutes
        e("Hip Thrust", MuscleGroup.GLUTES),
        e("Glute Bridge", MuscleGroup.GLUTES),
        e("Sumo Deadlift", MuscleGroup.GLUTES),
        e("Cable Kickback", MuscleGroup.GLUTES),
        e("Hip Abduction", MuscleGroup.GLUTES),

        // Calves
        e("Standing Calf Raise", MuscleGroup.CALVES),
        e("Seated Calf Raise", MuscleGroup.CALVES),
        e("Leg Press Calf Raise", MuscleGroup.CALVES),

        // Core
        e("Plank", MuscleGroup.CORE),
        e("Hanging Leg Raise", MuscleGroup.CORE),
        e("Cable Crunch", MuscleGroup.CORE),
        e("Crunch", MuscleGroup.CORE),
        e("Sit-Up", MuscleGroup.CORE),
        e("Russian Twist", MuscleGroup.CORE),
        e("Ab Wheel Rollout", MuscleGroup.CORE),
        e("Dead Bug", MuscleGroup.CORE),
        e("Side Plank", MuscleGroup.CORE),

        // Forearms
        e("Wrist Curl", MuscleGroup.FOREARMS),
        e("Reverse Wrist Curl", MuscleGroup.FOREARMS),
        e("Reverse Curl", MuscleGroup.FOREARMS),
        e("Farmer's Walk", MuscleGroup.FOREARMS),

        // Full body
        e("Power Clean", MuscleGroup.FULL_BODY),
        e("Clean and Jerk", MuscleGroup.FULL_BODY),
        e("Snatch", MuscleGroup.FULL_BODY),
        e("Thruster", MuscleGroup.FULL_BODY),
        e("Kettlebell Swing", MuscleGroup.FULL_BODY),
        e("Turkish Get-Up", MuscleGroup.FULL_BODY),
        e("Burpee", MuscleGroup.FULL_BODY),
    )

    /** Lower-cased name to muscle group, for classifying already-logged sets. */
    private val byLowercaseName: Map<String, MuscleGroup> =
        all.associate { it.name.lowercase() to it.muscleGroup }

    fun muscleGroupFor(exerciseName: String): MuscleGroup? =
        byLowercaseName[exerciseName.trim().lowercase()]

    private fun e(name: String, muscleGroup: MuscleGroup) = CatalogExercise(name, muscleGroup)
}
