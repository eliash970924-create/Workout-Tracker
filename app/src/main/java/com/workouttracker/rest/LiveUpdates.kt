package com.workouttracker.rest

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
     * This app's notification settings, which is where the live updates switch
     * lives. There is a dedicated action for the switch itself, but not one
     * this SDK exposes by name, and guessing at a constant is how you ship a
     * button that crashes.
     */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}
