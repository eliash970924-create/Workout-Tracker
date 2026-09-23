package com.workouttracker.rest

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.workouttracker.MainActivity
import com.workouttracker.R
import com.workouttracker.ui.formatCountdown

/**
 * The two notifications the rest timer posts: one that counts down while you
 * rest, and one that fires when it is over.
 *
 * They sit on separate channels on purpose. The countdown is a quiet, ongoing
 * thing you glance at; the "rest over" is the alert you actually want to
 * interrupt you. One channel could not be both without either buzzing at the
 * start of every rest or staying silent at the end of one.
 */
object RestNotifications {

    const val RUNNING_ID = 2
    private const val DONE_ID = 1

    private const val RUNNING_CHANNEL = "rest_running"
    private const val DONE_CHANNEL = "rest_timer"

    // Request codes 1 and 2 belong to the +15s and Skip actions.
    private const val RUNNING_INTENT = 3
    private const val DONE_INTENT = 4

    /**
     * The countdown, as an ongoing notification.
     *
     * The seconds are ticked down by the system rather than by us: setting
     * `when` to the finish time and turning on the count-down chronometer means
     * the shade updates every second on its own. We still repost once a second
     * to move the progress bar, which is what a Live Update surface such as
     * Samsung's Now Bar draws.
     */
    fun running(context: Context, state: RestState): Notification {
        ensureChannels(context)

        val progress = NotificationCompat.ProgressStyle()
            // Styled by progress is what draws the part of the bar past the
            // current value faded. With it off, the whole bar is one colour
            // whatever the value, and a countdown bar that never changes says
            // nothing -- which is how this looked until it was turned back on.
            .setStyledByProgress(true)
            // The time left, not the time gone, so the bar drains as the
            // countdown next to it does rather than filling against it.
            .setProgress(state.remainingSeconds)
            .setProgressSegments(
                listOf(
                    NotificationCompat.ProgressStyle.Segment(state.totalSeconds)
                        .setColor(ContextCompat.getColor(context, R.color.dumbbell_bar)),
                )
            )

        val left = formatCountdown(state.remainingSeconds)

        return NotificationCompat.Builder(context, RUNNING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            // The exercise is the useful half; "resting" is obvious from the
            // countdown next to it.
            .setContentTitle(state.label?.let { "Resting · $it" } ?: "Resting")
            // Spelled out in the text as well as the chronometer, because One UI
            // does not always render the chronometer in the shade.
            .setContentText("$left left")
            // What the status bar chip shows when the notification is not open.
            .setShortCriticalText(left)
            .setStyle(progress)
            // Android 16 and up can promote this to a Live Update: on the lock
            // screen, in the status bar chip, and in Now Bar on Samsung. Older
            // versions ignore it and show an ordinary ongoing notification.
            .setRequestPromotedOngoing(true)
            .setWhen(state.endsAtMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            // Without this every repost would re-alert, once a second.
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openSession(context, state.workoutId, state.label, RUNNING_INTENT))
            .addAction(0, "+15s", action(context, RestActionReceiver.ACTION_EXTEND, 1))
            .addAction(0, "Skip", action(context, RestActionReceiver.ACTION_SKIP, 2))
            .build()
    }

    /** Fired once, when the rest is up. */
    fun postDone(context: Context, label: String?, workoutId: String?) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, DONE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Rest over")
            .setContentText(label?.let { "Time for your next set of $it." } ?: "Time for your next set.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openSession(context, workoutId, label, DONE_INTENT))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(DONE_ID, notification)
        } catch (_: SecurityException) {
            // Notification permission was refused. The buzz, and the countdown
            // on screen, are still the signal -- not worth crashing for.
        }
    }

    private fun ensureChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            // Deliberately LOW rather than MIN: a MIN channel is not eligible
            // to be promoted to a Live Update.
            NotificationChannelCompat.Builder(RUNNING_CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Rest countdown")
                .setDescription("The quiet countdown shown while you rest.")
                .build()
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(DONE_CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Rest timer")
                .setDescription("Tells you when a rest between sets is up.")
                .build()
        )
    }

    /**
     * Opens the exercise the rest belongs to, rather than wherever the app
     * happens to start. A rest notification is read mid-session, and landing on
     * the workouts list means two taps back to the set you were about to do.
     *
     * Falls back to simply launching the app when the rest was started before
     * this carried a session, or by something that is not a session.
     */
    private fun openSession(
        context: Context,
        workoutId: String?,
        exercise: String?,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            // SINGLE_TOP so tapping it reuses the activity that is already
            // running, which is the usual case, rather than starting a second.
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (workoutId != null) {
            intent.putExtra(MainActivity.EXTRA_WORKOUT_ID, workoutId)
            intent.putExtra(MainActivity.EXTRA_EXERCISE, exercise)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            // Two intents that differ only in their extras count as the same
            // one, so without UPDATE_CURRENT every rest after the first would
            // reuse the first one's destination.
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun action(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, RestActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
