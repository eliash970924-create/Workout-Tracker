package com.workouttracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.workouttracker.data.SetWithSession

/** What the progress line plots. */
enum class ProgressMetric(val label: String) {
    TOP_SET("Top set"),
    VOLUME("Volume"),
}

data class ProgressPoint(val epochDay: Long, val value: Double)

/**
 * One point per session, oldest first. Sessions rather than sets, because the
 * question a progress line answers is "is this going up week to week", not
 * "what did each set weigh".
 */
fun progressPoints(sets: List<SetWithSession>, metric: ProgressMetric): List<ProgressPoint> =
    sets.groupBy { it.workoutDate }
        .map { (day, daySets) -> ProgressPoint(day, metric.measure(daySets)) }
        .sortedBy { it.epochDay }

private fun ProgressMetric.measure(sets: List<SetWithSession>): Double = when (this) {
    ProgressMetric.TOP_SET -> sets.maxOf { it.weightKg }
    ProgressMetric.VOLUME -> sets.sumOf { it.reps * it.weightKg }
}

fun ProgressMetric.format(value: Double): String = when (this) {
    ProgressMetric.TOP_SET -> "${formatWeight(value)} kg"
    ProgressMetric.VOLUME -> formatVolume(value)
}

/**
 * A plain line chart of [points]. Requires at least two points; a single
 * session is not a trend, and the caller leaves the chart out entirely then.
 *
 * Points are spaced by date rather than evenly, so a month off shows as a gap
 * instead of looking like steady training.
 */
@Composable
fun ProgressChart(
    points: List<ProgressPoint>,
    metric: ProgressMetric,
    modifier: Modifier = Modifier,
) {
    val lineColour = MaterialTheme.colorScheme.primary
    val lowest = points.minOf { it.value }
    val highest = points.maxOf { it.value }

    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(140.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = 3.dp.toPx()
                // Inset by the dot radius so end points are not half cut off.
                val inset = radius + 1.dp.toPx()
                val width = size.width - inset * 2
                val height = size.height - inset * 2

                val firstDay = points.first().epochDay
                val daySpan = (points.last().epochDay - firstDay).coerceAtLeast(1L).toFloat()
                val valueSpan = (highest - lowest).takeIf { it > 0.0 } ?: 1.0

                val plotted = points.map { point ->
                    Offset(
                        x = inset + width * ((point.epochDay - firstDay).toFloat() / daySpan),
                        y = inset + height * (1f - ((point.value - lowest) / valueSpan).toFloat()),
                    )
                }

                val line = Path().apply {
                    moveTo(plotted.first().x, plotted.first().y)
                    plotted.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(
                    path = line,
                    color = lineColour,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
                plotted.forEach { drawCircle(lineColour, radius = radius, center = it) }
            }

            AxisLabel(metric.format(highest), Modifier.align(Alignment.TopStart))
            // With one value the two labels would sit on top of each other.
            if (highest != lowest) {
                AxisLabel(metric.format(lowest), Modifier.align(Alignment.BottomStart))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AxisLabel(formatDay(points.first().epochDay))
            AxisLabel(formatDay(points.last().epochDay))
        }
    }
}

@Composable
private fun AxisLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
