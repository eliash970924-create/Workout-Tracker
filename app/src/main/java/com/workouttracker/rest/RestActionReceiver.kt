package com.workouttracker.rest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.workouttracker.WorkoutApp

/** The +15s and Skip buttons on the countdown notification. */
class RestActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val timer = (context.applicationContext as? WorkoutApp)?.restTimer ?: return
        when (intent.action) {
            ACTION_EXTEND -> timer.adjust(15)
            ACTION_SKIP -> timer.stop()
        }
    }

    companion object {
        const val ACTION_EXTEND = "com.workouttracker.rest.EXTEND"
        const val ACTION_SKIP = "com.workouttracker.rest.SKIP"
    }
}
