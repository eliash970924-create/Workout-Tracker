package com.workouttracker.rest

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.workouttracker.MainActivity
import com.workouttracker.R

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
            .setStyledByProgress(false)
            .setProgress(state.totalSeconds - state.remainingSeconds)
            .setProgressSegments(
                listOf(NotificationCompat.ProgressStyle.Segment(state.totalSeconds))
            )

        return NotificationCompat.Builder(context, RUNNING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Resting")
            .setContentText(state.label ?: "Next set coming up")
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
            .setContentIntent(openApp(context))
            .addAction(0, "+15s", action(context, RestActionReceiver.ACTION_EXTEND, 1))
            .addAction(0, "Skip", action(context, RestActionReceiver.ACTION_SKIP, 2))
            .build()
    }

    /** Fired once, when the rest is up. */
    fun postDone(context: Context, label: String?) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, DONE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Rest over")
            .setContentText(label?.let { "Time for your next set of $it." } ?: "Time for your next set.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
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

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, RestActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
