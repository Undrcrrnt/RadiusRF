package app.radiusrf.features.run_compare.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.radiusrf.features.maps.OfflineMapViewModel
import app.radiusrf.features.run_compare.domain.GeoFix
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.ScaleBarOverlay

data class MapLine(val points: List<GeoFix>, val color: Color, val width: Float, val dashed: Boolean = false)
data class MapMark(val fix: GeoFix, val color: Color)

@Composable
fun RunCompareMap(
    lines: List<MapLine>,
    marks: List<MapMark>,
    refitKey: Any,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tiles: OfflineMapViewModel = hiltViewModel()
    val archives by tiles.store.archives.collectAsState()
    val offlineOnly by tiles.settings.offlineTilesOnly.collectAsState()
    val archiveKey = archives.joinToString { it.name }
    val map = remember(archiveKey) {
        tiles.store.configureOsmdroid()
        StableMapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isClickable = true
            isFocusable = true
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            controller.setZoom(3.0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            overlays += CompareOverlay(context.resources.displayMetrics.density)
            overlays += ScaleBarOverlay(this).apply {
                setCentred(true)
                setScaleBarOffset(context.resources.displayMetrics.widthPixels / 2, 14)
            }
            overlays += CopyrightOverlay(context)
            setOnTouchListener { _, event ->
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
    Box(
        modifier
            .fillMaxWidth()
            .height(300.dp)
            .clipToBounds()
    ) {
        AndroidView(
            factory = { map },
            update = { view ->
                view.setUseDataConnection(!offlineOnly)
                view.overlays.filterIsInstance<CompareOverlay>().firstOrNull()?.apply {
                    this.lines = lines
                    this.marks = marks
                }
                view.queueFit(refitKey, lines, marks)
                view.invalidate()
            },
            modifier = Modifier.matchParentSize()
        )
    }
}

/**
 * Pinch and drag change the map scale and center. They do not change the
 * view's place on the screen.
 */
private class StableMapView(context: android.content.Context) : MapView(context) {
    private var placed = false
    private var relayoutPosted = false
    private var fitPosted = false
    private var requestedKey: Any? = null
    private var appliedKey: Any? = null
    private var fitLines: List<MapLine> = emptyList()
    private var fitMarks: List<MapMark> = emptyList()

    fun queueFit(key: Any, lines: List<MapLine>, marks: List<MapMark>) {
        requestedKey = key
        fitLines = lines
        fitMarks = marks
        boundsOf(lines, marks)?.let { box ->
            if (appliedKey != key) setExpectedCenter(centerOf(box))
        }
        tryFit()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val height = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, height)
    }

    override fun requestLayout() {
        if (!placed) {
            super.requestLayout()
            return
        }
        invalidate()
        if (relayoutPosted) return
        relayoutPosted = true
        post {
            relayoutPosted = false
            if (width > 0 && height > 0) {
                try {
                    onLayout(false, left, top, right, bottom)
                } catch (_: RuntimeException) {
                }
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        placed = width > 0 && height > 0
        tryFit()
    }

    private fun tryFit() {
        val key = requestedKey ?: return
        if (appliedKey == key || fitPosted) return
        if (width < 160 || height < 160) return
        val box = boundsOf(fitLines, fitMarks) ?: return
        fitPosted = true
        post {
            fitPosted = false
            if (appliedKey == key || width < 160 || height < 160) return@post
            val current = boundsOf(fitLines, fitMarks) ?: return@post
            try {
                val latSpan = current.latNorth - current.latSouth
                val lngSpan = current.lonEast - current.lonWest
                val center = centerOf(current)
                if (latSpan < 0.0002 && lngSpan < 0.0002) {
                    controller.setZoom(16.0)
                    setExpectedCenter(center)
                } else {
                    zoomToBoundingBox(current, false, 48)
                    setExpectedCenter(center)
                }
                appliedKey = key
                invalidate()
            } catch (_: RuntimeException) {
            }
        }
    }
}

private fun boundsOf(lines: List<MapLine>, marks: List<MapMark>): BoundingBox? {
    var north = Double.NEGATIVE_INFINITY
    var south = Double.POSITIVE_INFINITY
    var east = Double.NEGATIVE_INFINITY
    var west = Double.POSITIVE_INFINITY
    var any = false
    fun consider(lat: Double, lng: Double) {
        if (!lat.isFinite() || !lng.isFinite()) return
        if (lat !in -85.0..85.0 || lng !in -180.0..180.0) return
        if (kotlin.math.abs(lat) < 0.0001 && kotlin.math.abs(lng) < 0.0001) return
        any = true
        if (lat > north) north = lat
        if (lat < south) south = lat
        if (lng > east) east = lng
        if (lng < west) west = lng
    }
    lines.forEach { line -> line.points.forEach { consider(it.lat, it.lng) } }
    marks.forEach { consider(it.fix.lat, it.fix.lng) }
    if (!any || north < south || east < west) return null
    return try {
        BoundingBox(north, east, south, west)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun centerOf(box: BoundingBox) = GeoPoint(
    (box.latNorth + box.latSouth) / 2.0,
    (box.lonEast + box.lonWest) / 2.0
)

private class CompareOverlay(private val density: Float) : Overlay() {
    var lines: List<MapLine> = emptyList()
    var marks: List<MapMark> = emptyList()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = android.graphics.Color.WHITE
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        try {
            drawMarks(canvas, mapView)
        } catch (_: RuntimeException) {
        }
    }

    private fun drawMarks(canvas: Canvas, mapView: MapView) {
        val projection = mapView.projection
        lines.forEach { line ->
            val pixels = line.points.mapNotNull { fix ->
                if (!usable(fix)) return@mapNotNull null
                projection.toPixels(GeoPoint(fix.lat, fix.lng), null)
            }
            if (pixels.size < 2) return@forEach
            stroke.color = line.color.toArgb()
            stroke.strokeWidth = line.width * density
            stroke.pathEffect = if (line.dashed) DashPathEffect(floatArrayOf(14f, 10f), 0f) else null
            for (i in 1 until pixels.size) {
                canvas.drawLine(
                    pixels[i - 1].x.toFloat(),
                    pixels[i - 1].y.toFloat(),
                    pixels[i].x.toFloat(),
                    pixels[i].y.toFloat(),
                    stroke
                )
            }
        }
        marks.forEach { mark ->
            if (!usable(mark.fix)) return@forEach
            val pixel = projection.toPixels(GeoPoint(mark.fix.lat, mark.fix.lng), null)
            fill.color = mark.color.toArgb()
            canvas.drawCircle(pixel.x.toFloat(), pixel.y.toFloat(), 7f * density, fill)
            canvas.drawCircle(pixel.x.toFloat(), pixel.y.toFloat(), 7f * density, ring)
        }
    }
}

private fun usable(fix: GeoFix): Boolean =
    fix.lat.isFinite() && fix.lng.isFinite() && fix.lat in -85.0..85.0 && fix.lng in -180.0..180.0
