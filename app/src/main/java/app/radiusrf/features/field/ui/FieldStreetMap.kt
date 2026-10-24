package app.radiusrf.features.field.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.features.maps.OfflineMapSheet
import app.radiusrf.features.maps.OfflineMapViewModel
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.ScaleBarOverlay
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun FieldStreetMap(
    latitude: Double?,
    longitude: Double?,
    marks: List<PlotMark>,
    bearing: Float?,
    drawPath: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tiles: OfflineMapViewModel = hiltViewModel()
    val archives by tiles.store.archives.collectAsState()
    val offlineOnly by tiles.settings.offlineTilesOnly.collectAsState()
    val active by tiles.settings.activeMapArchive.collectAsState()
    val useStreet = !offlineOnly || archives.isNotEmpty()
    if (!useStreet) {
        LocalPlot(latitude, longitude, marks, bearing, drawPath, modifier.fillMaxWidth().height(360.dp))
        return
    }
    var follow by remember { mutableStateOf(true) }
    var lastCentered by remember { mutableStateOf<GeoPoint?>(null) }
    var showTiles by remember { mutableStateOf(false) }
    val archiveKey = archives.joinToString { it.name }
    val map = remember(archiveKey) {
        tiles.store.configureOsmdroid()
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(17.0)
            overlays += FieldMarksOverlay(context.resources.displayMetrics.density)
            overlays += ScaleBarOverlay(this).apply {
                setCentred(true)
                setScaleBarOffset(context.resources.displayMetrics.widthPixels / 2, 14)
            }
            overlays += CopyrightOverlay(context)
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) follow = false
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                false
            }
        }
    }
    DisposableEffect(map, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            map.onPause()
            map.onDetach()
        }
    }
    Box(modifier.fillMaxWidth().height(360.dp)) {
        AndroidView(
            factory = { map },
            update = { view ->
                view.setUseDataConnection(!offlineOnly)
                view.overlays.filterIsInstance<FieldMarksOverlay>().firstOrNull()?.apply {
                    points = marks
                    path = drawPath
                    heading = bearing
                    current = if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null
                }
                if (follow) {
                    val center = when {
                        latitude != null && longitude != null -> GeoPoint(latitude, longitude)
                        marks.isNotEmpty() -> GeoPoint(marks.last().lat, marks.last().lon)
                        else -> null
                    }
                    center?.let {
                        if (lastCentered == null || lastCentered!!.distanceToAsDouble(it) >= 1.5) {
                            view.controller.animateTo(it)
                            lastCentered = it
                        }
                    }
                }
                view.invalidate()
            },
            modifier = Modifier.matchParentSize()
        )
        Column(
            Modifier.align(Alignment.TopEnd).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledIconButton(onClick = { showTiles = true }) {
                Icon(ZdIcons.Folder, contentDescription = "Offline map tiles")
            }
            FilledIconButton(onClick = { lastCentered = null; follow = true }) {
                Icon(ZdIcons.Crosshair, contentDescription = "Recenter map")
            }
        }
        Text(
            if (offlineOnly) "Offline · ${active ?: "${archives.size} archive(s)"}"
            else if (archives.isNotEmpty()) "Tiles + network"
            else "Online OpenStreetMap",
            style = ZdType.Caption,
            color = ZdColors.Text,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
        )
    }
    if (showTiles) OfflineMapSheet(onDismiss = { showTiles = false }, viewModel = tiles)
}

private class FieldMarksOverlay(private val density: Float) : Overlay() {
    var points: List<PlotMark> = emptyList()
    var current: GeoPoint? = null
    var heading: Float? = null
    var path: Boolean = false

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val projection = mapView.projection
        val pixels = points.map { projection.toPixels(GeoPoint(it.lat, it.lon), null) }
        if (path && pixels.size > 1) {
            stroke.color = Color.argb(180, 159, 201, 255)
            stroke.pathEffect = null
            for (i in 1 until pixels.size) {
                canvas.drawLine(pixels[i - 1].x.toFloat(), pixels[i - 1].y.toFloat(), pixels[i].x.toFloat(), pixels[i].y.toFloat(), stroke)
            }
        }
        points.forEachIndexed { index, mark ->
            mark.joinedTo?.let { other ->
                if (other in pixels.indices) {
                    stroke.color = mark.color.toArgb()
                    stroke.pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
                    canvas.drawLine(
                        pixels[index].x.toFloat(), pixels[index].y.toFloat(),
                        pixels[other].x.toFloat(), pixels[other].y.toFloat(),
                        stroke
                    )
                }
            }
            fill.color = mark.color.toArgb()
            canvas.drawCircle(pixels[index].x.toFloat(), pixels[index].y.toFloat(), 7f * density, fill)
        }
        current?.let { here ->
            val p = projection.toPixels(here, null)
            fill.color = Color.argb(220, 10, 20, 28)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 11f * density, fill)
            fill.color = Color.rgb(159, 201, 255)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 6f * density, fill)
            heading?.let { degrees ->
                val rad = Math.toRadians(degrees.toDouble())
                val reach = 140f * density
                stroke.pathEffect = null
                stroke.color = Color.WHITE
                canvas.drawLine(
                    p.x.toFloat(),
                    p.y.toFloat(),
                    (p.x + sin(rad) * reach).toFloat(),
                    (p.y - cos(rad) * reach).toFloat(),
                    stroke
                )
            }
        }
    }
}
