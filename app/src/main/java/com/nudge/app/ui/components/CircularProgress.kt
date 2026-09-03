package com.nudge.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nudge.app.R

@Composable
fun CircularProgress(
    totalMinutes: Long,
    dailyBudgetMinutes: Int,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    strokeWidth: Dp = 14.dp,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    progressColor: Color = MaterialTheme.colorScheme.primary,
    overLimitColor: Color = MaterialTheme.colorScheme.error
) {
    val progress = if (dailyBudgetMinutes > 0) {
        (totalMinutes.toFloat() / dailyBudgetMinutes).coerceIn(0f, 1f)
    } else 0f

    val isOverLimit = totalMinutes > dailyBudgetMinutes
    val arcColor = if (isOverLimit) overLimitColor else progressColor

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 1000),
        label = "progress"
    )

    val hours = totalMinutes / 60
    val mins = totalMinutes % 60
    val timeText = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size)
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val canvasSize = this.size.minDimension
            val stroke = strokeWidth.toPx()
            val arcSize = canvasSize - stroke
            val topLeft = Offset(stroke / 2, stroke / 2)

            // Track (background circle)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(arcSize, arcSize),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            // Progress arc
            drawArc(
                color = arcColor,
                startAngle = -90f,
                sweepAngle = animatedProgress * 360f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(arcSize, arcSize),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = timeText,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = if (hours > 0) 32.sp else 40.sp
                ),
                fontWeight = FontWeight.Bold,
                color = if (isOverLimit) overLimitColor else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.today_budget_progress, dailyBudgetMinutes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

