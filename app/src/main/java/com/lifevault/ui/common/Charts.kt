package com.lifevault.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Simple vertical bars drawn in Compose. [labels] are shown under the bars. */
@Composable
fun BarChart(
    values: List<Double>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    maxValue: Double? = null,
    description: String = "Bar chart",
    height: Int = 120,
) {
    val max = (maxValue ?: values.maxOrNull() ?: 0.0).coerceAtLeast(1e-9)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            if (values.isEmpty()) return@Canvas
            val slot = size.width / values.size
            val barW = slot * 0.6f
            values.forEachIndexed { i, v ->
                val x = i * slot + (slot - barW) / 2
                drawRoundRect(track, Offset(x, 0f), Size(barW, size.height), CornerRadius(6f, 6f))
                val h = (v / max).toFloat().coerceIn(0f, 1f) * size.height
                if (h > 0f) drawRoundRect(color, Offset(x, size.height - h), Size(barW, h), CornerRadius(6f, 6f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            for (l in labels) {
                Text(l, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1)
            }
        }
    }
}

/** Line chart for values in [min]..[max]; null points are gaps. Optional dashed [reference] line. */
@Composable
fun LineChart(
    points: List<Double?>,
    min: Double,
    max: Double,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    reference: Double? = null,
    labels: List<String> = emptyList(),
    description: String = "Line chart",
    height: Int = 140,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val refColor = MaterialTheme.colorScheme.tertiary
    Column(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            val n = points.size
            if (n == 0) return@Canvas
            fun y(v: Double) = size.height - ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f) * size.height
            fun x(i: Int) = if (n == 1) size.width / 2 else i * size.width / (n - 1)
            for (k in 0..4) {
                val gy = size.height * k / 4
                drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
            }
            reference?.let {
                drawLine(refColor, Offset(0f, y(it)), Offset(size.width, y(it)), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
            }
            var path: Path? = null
            points.forEachIndexed { i, v ->
                if (v == null) {
                    path?.let { drawPath(it, color, style = Stroke(width = 4f, cap = StrokeCap.Round)) }
                    path = null
                } else {
                    val p = path
                    if (p == null) path = Path().apply { moveTo(x(i), y(v)) } else p.lineTo(x(i), y(v))
                    drawCircle(color, radius = 6f, center = Offset(x(i), y(v)))
                }
            }
            path?.let { drawPath(it, color, style = Stroke(width = 4f, cap = StrokeCap.Round)) }
        }
        if (labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (l in labels) Text(l, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Horizontal progress bar with an optional threshold marker. */
@Composable
fun PercentBar(fraction: Float, color: Color, modifier: Modifier = Modifier, threshold: Float? = null) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val marker = MaterialTheme.colorScheme.onSurface
    Box(modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
        if (threshold != null) {
            Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                val x = size.width * threshold.coerceIn(0f, 1f)
                drawLine(marker, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
            }
        }
    }
}
