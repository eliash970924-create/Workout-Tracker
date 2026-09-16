package com.workouttracker.rest

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Android 16's Live Updates: the promotion that puts an ongoing notification on
 * the lock screen, in the status bar chip, and in Samsung's Now Bar.
 *
 * A notification can tick every eligibility box and still not be promoted,
 * because the user has a per-app switch for it that can default to off. That is
 * invisible from inside the app unless we go and ask, which is what this is for.
 */
object LiveUpdates {

    /** Android 16 is where promoted ongoing notifications start existing. */
    private const val ANDROID_16 = 36

    fun supported(): Boolean = Build.VERSION.SDK_INT >= ANDROID_16

    /** False only when the platform supports promotion and the user turned it off. */
    fun allowed(context: Context): Boolean {
        if (!supported()) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        return manager.canPostPromotedNotifications()
    }

    /**
     * Why the countdown is or is not being promoted, as the system sees it.
     *
     * Three separate things have to hold and only the platform can say which
     * one is missing: the OS has to be new enough, the user has to allow it,
     * and the notification itself has to qualify. Working that out by reading
     * the eligibility list and guessing is how an afternoon disappears.
     */
    fun diagnose(context: Context): String {
        if (!supported()) return "Not supported below Android 16."
        val allowed = allowed(context)
        val promotable = runCatching {
            RestNotifications
                .running(context, RestState(60, 90, System.currentTimeMillis() + 60_000, "Sample"))
                .hasPromotableCharacteristics()
        }.fold(onSuccess = { if (it) "yes" else "no" }, onFailure = { "unknown" })
        return "Supported: yes · Allowed by you: ${if (allowed) "yes" else "no"} · " +
            "Notification qualifies: $promotable · Posted now: ${postedState(context)}"
    }

    /**
     * Whether the countdown currently on screen was actually promoted.
     *
     * The check above builds a sample notification; this looks at the real one,
     * which is posted through a foreground service and could differ. The system
     * sets the flag itself, so this is its answer rather than ours.
     */
    private fun postedState(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return "unknown"
        val posted = manager.activeNotifications
            .firstOrNull { it.id == RestNotifications.RUNNING_ID }
            ?: return "no rest running"
        val promoted = posted.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0
        return if (promoted) "promoted" else "posted, NOT promoted"
    }

    /**
     * This app's notification settings, which is where the live updates switch
     * lives. There is a dedicated action for the switch itself, but not one
     * this SDK exposes by name, and guessing at a constant is how you ship a
     * button that crashes.
     */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}
