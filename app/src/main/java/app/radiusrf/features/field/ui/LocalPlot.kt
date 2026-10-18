package app.radiusrf.features.field.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import app.radiusrf.ui.theme.ZdColors
import kotlin.math.cos
import kotlin.math.sin

data class PlotMark(
    val lat: Double,
    val lon: Double,
    val color: Color,
    val joinedTo: Int? = null
)

@Composable
fun LocalPlot(
    lat: Double?,
    lon: Double?,
    marks: List<PlotMark>,
    bearing: Float?,
    drawPath: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth().height(280.dp)
) {
    val you = ZdColors.Accent
    val grid = ZdColors.Border
    Canvas(modifier) {
        drawRect(Color(0xFF10140F))
        val originLat = lat ?: marks.firstOrNull()?.lat
        val originLon = lon ?: marks.firstOrNull()?.lon
        if (originLat == null || originLon == null) return@Canvas
        val projected = marks.map { mark ->
            meters(originLat, originLon, mark.lat, mark.lon)
        }
        val self = if (lat != null && lon != null) meters(originLat, originLon, lat, lon) else 0.0 to 0.0
        var span = 80.0
        (projected + self).forEach { (x, y) ->
            span = maxOf(span, kotlin.math.abs(x), kotlin.math.abs(y))
        }
        span *= 1.3
        fun point(x: Double, y: Double) = Offset(
            (size.width / 2 + (x / span) * size.minDimension / 2).toFloat(),
            (size.height / 2 - (y / span) * size.minDimension / 2).toFloat()
        )
        drawLine(grid, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height))
        drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
        if (drawPath && projected.size > 1) {
            for (i in 1 until projected.size) {
                drawLine(grid, point(projected[i - 1].first, projected[i - 1].second), point(projected[i].first, projected[i].second), strokeWidth = 2f)
            }
        }
        marks.forEachIndexed { index, mark ->
            val at = point(projected[index].first, projected[index].second)
            mark.joinedTo?.let { other ->
                val there = point(projected[other].first, projected[other].second)
                drawLine(mark.color, at, there, strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
            drawCircle(mark.color, radius = 7f, center = at)
        }
        val here = point(self.first, self.second)
        drawCircle(you, radius = 8f, center = here)
        if (bearing != null && lat != null) {
            val rad = Math.toRadians(bearing.toDouble())
            val end = point(sin(rad) * span * 0.85, cos(rad) * span * 0.85)
            drawLine(Color.White, here, end, strokeWidth = 3f)
        }
    }
}

private fun meters(originLat: Double, originLon: Double, lat: Double, lon: Double): Pair<Double, Double> {
    val north = (lat - originLat) * 111_320.0
    val east = (lon - originLon) * 111_320.0 * cos(Math.toRadians(originLat))
    return east to north
}
