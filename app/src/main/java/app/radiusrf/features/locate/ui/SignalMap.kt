package app.radiusrf.features.locate.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.ColorUtils
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.features.locate.domain.GeoSignalSample
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
import kotlin.math.roundToInt

/**
 * OpenStreetMap view of where this phone heard a target. Heat colors encode signal strength:
 * blue is weak and red is strongest. No point claims to be the transmitter's position.
 */
@Composable
fun SignalMap(
    samples: List<GeoSignalSample>,
    currentLatitude: Double?,
    currentLongitude: Double?,
    showHeat: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth().height(360.dp)
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tiles: OfflineMapViewModel = hiltViewModel()
    val archives by tiles.store.archives.collectAsState()
    val offlineOnly by tiles.settings.offlineTilesOnly.collectAsState()
    val active by tiles.settings.activeMapArchive.collectAsState()
    var follow by remember { mutableStateOf(true) }
    var lastCentered by remember { mutableStateOf<GeoPoint?>(null) }
    var showTiles by remember { mutableStateOf(false) }
    val archiveKey = archives.joinToString { it.name }
    val map = remember(archiveKey) {
        tiles.store.configureOsmdroid()
        StableMapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(18.0)
            overlays += SignalOverlay(context.resources.displayMetrics.density)
            overlays += ScaleBarOverlay(this).apply {
                setCentred(true)
                setScaleBarOffset(context.resources.displayMetrics.widthPixels / 2, 14)
            }
            overlays += CopyrightOverlay(context)
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        follow = false
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                false
            }
        }
    }
    val frame = remember(map) {
        FrameLayout(context).apply {
            clipChildren = true
            clipToPadding = true
            addView(
                map,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
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
    Box(modifier.clipToBounds()) {
        AndroidView(
            factory = { frame },
            update = {
                map.setUseDataConnection(!offlineOnly)
                map.overlays.filterIsInstance<SignalOverlay>().firstOrNull()?.apply {
                    points = samples
                    heat = showHeat
                    current = if (currentLatitude != null && currentLongitude != null) {
                        GeoPoint(currentLatitude, currentLongitude)
                    } else {
                        null
                    }
                }
                if (follow) {
                    val center = when {
                        currentLatitude != null && currentLongitude != null -> GeoPoint(currentLatitude, currentLongitude)
                        samples.isNotEmpty() -> GeoPoint(samples.last().latitude, samples.last().longitude)
                        else -> null
                    }
                    center?.let {
                        if (lastCentered == null || lastCentered!!.distanceToAsDouble(it) >= 1.5) {
                            map.controller.animateTo(it)
                            lastCentered = it
                        }
                    }
                }
                map.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )
        Column(
            Modifier.align(Alignment.TopEnd).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            FilledIconButton(onClick = { showTiles = true }) {
                Icon(ZdIcons.Folder, contentDescription = "Offline map tiles")
            }
            FilledIconButton(
                onClick = {
                    lastCentered = null
                    follow = true
                }
            ) {
                Icon(ZdIcons.Crosshair, contentDescription = "Recenter map")
            }
        }
        Text(
            tileStatus(archives.size, offlineOnly, active),
            style = ZdType.Caption,
            color = ZdColors.Text,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
        )
    }
    if (showTiles) {
        OfflineMapSheet(onDismiss = { showTiles = false }, viewModel = tiles)
    }
}

private fun tileStatus(count: Int, offlineOnly: Boolean, active: String?): String = when {
    offlineOnly && count == 0 -> "Offline · no tile archive"
    offlineOnly -> "Offline · ${active ?: "$count archive(s)"}"
    count > 0 -> "Tiles + network · ${active ?: "$count archive(s)"}"
    else -> "Online OpenStreetMap"
}

/**
 * Zoom changes the map scale only. The view keeps the size Compose measured for it.
 */
private class StableMapView(context: android.content.Context) : MapView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val height = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, height)
    }
}

private class SignalOverlay(private val density: Float) : Overlay() {
    var points: List<GeoSignalSample> = emptyList()
    var current: GeoPoint? = null
    var heat: Boolean = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 159, 201, 255)
        strokeWidth = 3f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val projection = mapView.projection
        if (points.size > 1) {
            val path = android.graphics.Path()
            points.forEachIndexed { index, sample ->
                val p = projection.toPixels(GeoPoint(sample.latitude, sample.longitude), null)
                if (index == 0) path.moveTo(p.x.toFloat(), p.y.toFloat())
                else path.lineTo(p.x.toFloat(), p.y.toFloat())
            }
            canvas.drawPath(path, pathPaint)
        }
        if (heat && points.isNotEmpty()) {
            val radius = 46f * density
            val stride = (points.size / MAX_HEAT_POINTS).coerceAtLeast(1)
            points.filterIndexed { index, _ -> index % stride == 0 || index == points.lastIndex }.forEach { sample ->
                val p = projection.toPixels(GeoPoint(sample.latitude, sample.longitude), null)
                val strength = ((sample.rssi - MIN_RSSI).toFloat() / (MAX_RSSI - MIN_RSSI)).coerceIn(0f, 1f)
                val color = heatColor(strength)
                for (layer in 4 downTo 1) {
                    paint.color = Color.argb(
                        42,
                        Color.red(color),
                        Color.green(color),
                        Color.blue(color)
                    )
                    canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), radius * layer / 4f, paint)
                }
            }
            val strongest = points.maxByOrNull { it.rssi }
            strongest?.let {
                val p = projection.toPixels(GeoPoint(it.latitude, it.longitude), null)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f * density
                paint.color = Color.WHITE
                canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 9f * density, paint)
                paint.style = Paint.Style.FILL
            }
        }
        current?.let {
            val p = projection.toPixels(it, null)
            markerPaint.color = Color.argb(220, 10, 20, 28)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 11f * density, markerPaint)
            markerPaint.color = Color.rgb(159, 201, 255)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 6f * density, markerPaint)
        }
    }

    private companion object {
        const val MIN_RSSI = -100
        const val MAX_RSSI = -30
        const val MAX_HEAT_POINTS = 250
    }
}

private fun heatColor(strength: Float): Int {
    val stops = intArrayOf(
        Color.rgb(35, 70, 255),
        Color.CYAN,
        Color.rgb(35, 210, 80),
        Color.YELLOW,
        Color.rgb(255, 135, 20),
        Color.RED
    )
    val scaled = strength.coerceIn(0f, 1f) * (stops.size - 1)
    val index = scaled.toInt().coerceIn(0, stops.size - 2)
    val amount = ((scaled - index) * 255).roundToInt()
    return ColorUtils.blendARGB(stops[index], stops[index + 1], amount / 255f)
}
