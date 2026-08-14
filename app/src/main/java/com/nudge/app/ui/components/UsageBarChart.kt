package com.nudge.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nudge.app.ui.theme.UsageBarColors

data class UsageBarData(
    val label: String,
    val minutes: Long,
    val color: Color
)

@Composable
fun UsageBarChart(
    data: List<UsageBarData>,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty()) return

    val maxMinutes = data.maxOf { it.minutes }.coerceAtLeast(1)
    val barHeight = 28.dp
    val barSpacing = 12.dp
    val labelHeight = 18.dp
    val itemHeight = labelHeight + barHeight + barSpacing
    val totalHeight = itemHeight * data.size

    var animationTarget by remember { mutableFloatStateOf(0f) }
    val animatedFraction by animateFloatAsState(
        targetValue = animationTarget,
        animationSpec = tween(durationMillis = 800),
        label = "barAnimation"
    )
    LaunchedEffect(data) {
        animationTarget = 1f
    }

    val textColor = MaterialTheme.colorScheme.onSurface

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight)
            .padding(horizontal = 4.dp)
    ) {
        val canvasWidth = size.width
        val barHeightPx = barHeight.toPx()
        val labelHeightPx = labelHeight.toPx()
        val itemHeightPx = itemHeight.toPx()
        val cornerRadius = CornerRadius(barHeightPx / 2, barHeightPx / 2)

        data.forEachIndexed { index, item ->
            val yOffset = index * itemHeightPx

            // Draw label
            val hours = item.minutes / 60
            val mins = item.minutes % 60
            val timeStr = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
            val labelText = "${item.label}  $timeStr"

            drawContext.canvas.nativeCanvas.drawText(
                labelText,
                0f,
                yOffset + labelHeightPx - 2.dp.toPx(),
                android.graphics.Paint().apply {
                    color = textColor.hashCode()
                    textSize = 13.sp.toPx()
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.SANS_SERIF,
                        android.graphics.Typeface.NORMAL
                    )
                }
            )

            // Draw bar track
            drawRoundRect(
                color = item.color.copy(alpha = 0.12f),
                topLeft = Offset(0f, yOffset + labelHeightPx + 2.dp.toPx()),
                size = Size(canvasWidth, barHeightPx),
                cornerRadius = cornerRadius
            )

            // Draw bar fill
            val barWidth = (item.minutes.toFloat() / maxMinutes) * canvasWidth * animatedFraction
            if (barWidth > 0f) {
                drawRoundRect(
                    color = item.color,
                    topLeft = Offset(0f, yOffset + labelHeightPx + 2.dp.toPx()),
                    size = Size(barWidth.coerceAtLeast(barHeightPx), barHeightPx),
                    cornerRadius = cornerRadius
                )
            }
        }
    }
}

fun getBarColor(index: Int): Color {
    return UsageBarColors[index % UsageBarColors.size]
}

