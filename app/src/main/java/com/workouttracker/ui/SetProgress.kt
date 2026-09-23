package com.workouttracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Past this many sets the slices get too thin to count at a glance, and the
 * gaps between them start to outweigh the slices themselves, so it becomes a
 * plain sweep instead.
 */
private const val MAX_SLICES = 12

/** Gap between slices, in degrees. Enough to count them, not enough to notice. */
private const val GAP_DEGREES = 8f

/**
 * How far through an exercise you are, as a pie cut into one slice per set.
 *
 * Slices rather than a sweep, because the question at a glance is "how many
 * left", and three slices out of five answers it without doing arithmetic on a
 * sixty-per-cent arc. Once every set is done it turns into a filled disc with a
 * tick, which is the moment it is for.
 *
 * Decorative: the row it sits in already says "3 of 5 sets" in words.
 */
@Composable
fun SetProgressPie(done: Int, total: Int, modifier: Modifier = Modifier) {
    val filled = MaterialTheme.colorScheme.primary
    // outlineVariant rather than surfaceVariant: a Card is already drawn in a
    // surface tone, and an empty slice has to show up against it.
    val empty = MaterialTheme.colorScheme.outlineVariant
    val finished = total > 0 && done >= total

    Box(modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            when {
                total <= 0 -> drawCircle(empty)
                finished || total == 1 -> drawCircle(if (done >= total) filled else empty)
                total > MAX_SLICES -> {
                    drawCircle(empty)
                    drawArc(
                        color = filled,
                        startAngle = -90f,
                        sweepAngle = 360f * done / total,
                        useCenter = true,
                    )
                }
                else -> {
                    val each = 360f / total
                    for (slice in 0 until total) {
                        drawArc(
                            color = if (slice < done) filled else empty,
                            // Clockwise from twelve o'clock, like a clock face
                            // and like every other progress ring.
                            startAngle = -90f + slice * each + GAP_DEGREES / 2,
                            sweepAngle = each - GAP_DEGREES,
                            useCenter = true,
                        )
                    }
                }
            }
        }
        if (finished) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
