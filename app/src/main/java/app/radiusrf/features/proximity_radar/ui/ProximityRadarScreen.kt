package app.radiusrf.features.proximity_radar.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.radiusrf.core.lifecycle.HardwareLifecycleEffect
import app.radiusrf.core.permission.PermissionGate
import app.radiusrf.core.permission.PermissionUtils
import app.radiusrf.features.proximity_radar.domain.DeviceCategory
import app.radiusrf.features.proximity_radar.domain.ProximityCalculator
import app.radiusrf.features.proximity_radar.domain.RadarState
import app.radiusrf.features.proximity_radar.viewmodel.ProximityRadarViewModel
import app.radiusrf.ui.theme.BackgroundDark
import app.radiusrf.ui.theme.SurfaceDark
import app.radiusrf.ui.theme.TerminalAmber
import app.radiusrf.ui.theme.TerminalCyan
import app.radiusrf.ui.theme.TerminalGreen
import app.radiusrf.ui.theme.TerminalGreenDim
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import app.radiusrf.core.ui.zd.ZdCard
import app.radiusrf.core.ui.zd.ZdIconButton
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdListCard
import app.radiusrf.core.ui.zd.ZdListRow
import app.radiusrf.core.ui.zd.ZdSectionLabel
import app.radiusrf.core.ui.zd.ZdSignal
import app.radiusrf.core.ui.zd.ZdStatePanel
import app.radiusrf.core.ui.zd.ZdTag
import app.radiusrf.core.ui.zd.ZdToolScanBar
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import java.util.Locale

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

@Composable
fun ProximityRadarScreen(
    viewModel: ProximityRadarViewModel = hiltViewModel()
) {
    val combinedPermissions = (PermissionUtils.blePermissions() + PermissionUtils.wifiPermissions()).distinct()

    PermissionGate(
        permissions = combinedPermissions,
        rationale = "Bluetooth and WiFi permissions are needed to detect nearby wireless devices."
    ) {
        ProximityRadarContent(viewModel = viewModel)
    }
}

// ---------------------------------------------------------------------------
// Main content
// ---------------------------------------------------------------------------

@Composable
private fun ProximityRadarContent(viewModel: ProximityRadarViewModel) {
    val state by viewModel.state.collectAsState()
    var zoomedRadius by remember { mutableStateOf<Float?>(null) }
    val shownRadius = zoomedRadius ?: state.scanRadius
    val zoomBy: (Float) -> Unit = { factor ->
        zoomedRadius = ProximityCalculator.zoomRadius(zoomedRadius ?: state.scanRadius, factor)
    }

    HardwareLifecycleEffect(
        isActive = state.isScanning,
        onPause = viewModel::pauseScan,
        onResume = viewModel::resumeScan,
        onStop = viewModel::stopScan
    )

    Column(Modifier.fillMaxSize()) {
        ZdToolScanBar(
            running = state.isScanning,
            paused = state.isPaused,
            onStart = viewModel::startScan,
            onPause = viewModel::pauseScan,
            onResume = viewModel::resumeScan,
            onStop = viewModel::stopScan,
            runningNote = "BLE + WiFi · ${state.devices.size} devices",
            idleNote = if (state.devices.isEmpty()) "Hold the phone still while it runs" else "Results kept"
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.error?.let { item { ZdFootnote(it, icon = ZdIcons.Warning) } }
            item { RadarView(state = state, shownRadius = shownRadius, onZoom = zoomBy) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ZdTag("BLE ${state.bleCount}", color = ZdColors.Info, background = ZdColors.InfoBg, border = ZdColors.InfoBorder)
                    ZdTag("WiFi ${state.wifiCount}", color = ZdColors.Accent, background = ZdColors.AccentBg, border = ZdColors.AccentBorder)
                    if (zoomedRadius != null) {
                        Text(
                            "Fit",
                            style = ZdType.Path,
                            color = ZdColors.Accent,
                            modifier = Modifier.clickable { zoomedRadius = null }
                        )
                    }
                    Text(
                        "scale ${formatMeters(shownRadius)}",
                        style = ZdType.Path,
                        color = ZdColors.Text3,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End
                    )
                }
            }
            if (state.devices.isEmpty()) {
                item {
                    ZdStatePanel(
                        kicker = if (state.isScanning) "Scanning" else "Ready",
                        icon = ZdIcons.Sweep,
                        iconTint = ZdColors.Accent,
                        iconBackground = ZdColors.AccentBg,
                        title = "What’s close to you right now?",
                        body = "Plots nearby WiFi and Bluetooth devices by estimated distance. The closer to the centre, the stronger the signal.",
                        primaryAction = if (state.isScanning) null else "Start scan" to viewModel::startScan,
                        primaryIcon = ZdIcons.Play,
                        fullScreen = false
                    )
                }
            } else {
                item { ZdSectionLabel("Closest", trailingText = "${state.devices.size}") }
                item {
                    ZdListCard(state.devices.sortedBy { it.estimatedDistanceM }) { device ->
                        ZdListRow(
                            title = device.name,
                            subtitle = "${device.category.label()} · ~${formatMeters(device.estimatedDistanceM)}",
                            leading = { ZdSignal(device.rssi) },
                            trailing = if (device.id == state.nearestDevice?.id) {
                                { ZdTag("NEAREST", color = ZdColors.Accent, background = ZdColors.AccentBg, border = ZdColors.AccentBorder) }
                            } else null
                        )
                    }
                }
            }
            item { ZdFootnote("Angles are fixed per device so dots don’t jump; only distance is measured.") }
        }
    }
}

private fun DeviceCategory.label(): String = when (this) {
    DeviceCategory.WIFI_AP -> "WiFi"
    DeviceCategory.BLE_DEVICE -> "BLE"
    DeviceCategory.BLE_BEACON -> "Beacon"
    DeviceCategory.UNKNOWN -> "Unknown"
}

private fun formatMeters(m: Float): String = if (m < 10f) String.format(Locale.US, "%.1f m", m) else "${m.toInt()} m"

// ---------------------------------------------------------------------------
// Radar Canvas view
// ---------------------------------------------------------------------------

@Composable
private fun RadarView(state: RadarState, shownRadius: Float, onZoom: (Float) -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "radarSweep")

    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweepAngle"
    )

    // Pulsing scale for the nearest device dot
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val textMeasurer = rememberTextMeasurer()
    val zoom = rememberUpdatedState(onZoom)
    val canZoomIn = shownRadius > ProximityCalculator.MIN_ZOOM_RADIUS_M
    val canZoomOut = shownRadius < ProximityCalculator.MAX_ZOOM_RADIUS_M

    ZdCard(contentPadding = PaddingValues(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(BackgroundDark, shape = RoundedCornerShape(8.dp))
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, scale, _ ->
                            if (scale != 1f) zoom.value(scale)
                        }
                    }
            ) {
                val canvasCenter = center
                val radius = min(size.width, size.height) / 2f
                val scanRadius = shownRadius

                // -- Background circle -------------------------------------------
                drawCircle(
                    color = SurfaceDark,
                    radius = radius,
                    center = canvasCenter
                )

                // -- Concentric range rings --------------------------------------
                val ringFractions = listOf(0.33f, 0.66f, 1f)
                ringFractions.forEach { fraction ->
                    drawCircle(
                        color = TerminalGreen.copy(alpha = 0.15f),
                        radius = radius * fraction,
                        center = canvasCenter,
                        style = Stroke(width = 1f)
                    )
                }

                // -- Range ring distance labels ----------------------------------
                ringFractions.forEach { fraction ->
                    val distanceLabel = "${(scanRadius * fraction).toInt()}m"
                    val labelStyle = TextStyle(
                        color = TerminalGreenDim.copy(alpha = 0.5f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    val measured = textMeasurer.measure(distanceLabel, labelStyle)
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(
                            canvasCenter.x + 4f,
                            canvasCenter.y - radius * fraction - measured.size.height
                        )
                    )
                }

                // -- Crosshair lines ---------------------------------------------
                val crosshairColor = TerminalGreen.copy(alpha = 0.1f)
                drawLine(
                    color = crosshairColor,
                    start = Offset(canvasCenter.x - radius, canvasCenter.y),
                    end = Offset(canvasCenter.x + radius, canvasCenter.y),
                    strokeWidth = 1f
                )
                drawLine(
                    color = crosshairColor,
                    start = Offset(canvasCenter.x, canvasCenter.y - radius),
                    end = Offset(canvasCenter.x, canvasCenter.y + radius),
                    strokeWidth = 1f
                )

                // -- Sweep line with fading trail --------------------------------
                if (state.isScanning) {
                    // Trail arc
                    rotate(degrees = sweepAngle - 30f, pivot = canvasCenter) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    0.7f to Color.Transparent,
                                    1f to TerminalGreen.copy(alpha = 0.3f)
                                )
                            ),
                            startAngle = 0f,
                            sweepAngle = 30f,
                            useCenter = true,
                            topLeft = Offset(canvasCenter.x - radius, canvasCenter.y - radius),
                            size = Size(radius * 2, radius * 2)
                        )
                    }

                    // Sweep line
                    val sweepRad = Math.toRadians(sweepAngle.toDouble())
                    drawLine(
                        color = TerminalGreen.copy(alpha = 0.8f),
                        start = canvasCenter,
                        end = Offset(
                            canvasCenter.x + radius * cos(sweepRad).toFloat(),
                            canvasCenter.y + radius * sin(sweepRad).toFloat()
                        ),
                        strokeWidth = 2f
                    )
                }

                // -- Device dots -------------------------------------------------
                state.devices.forEach { device ->
                    if (device.estimatedDistanceM > scanRadius) return@forEach
                    val distanceFraction = device.estimatedDistanceM / scanRadius
                    val deviceRadius = radius * distanceFraction
                    val angleRad = Math.toRadians(device.angle.toDouble())
                    val dotX = canvasCenter.x + deviceRadius * cos(angleRad).toFloat()
                    val dotY = canvasCenter.y + deviceRadius * sin(angleRad).toFloat()
                    val isNearest = device.id == state.nearestDevice?.id

                    val dotColor = when {
                        device.estimatedDistanceM < 2f -> TerminalAmber
                        device.category == DeviceCategory.WIFI_AP -> TerminalCyan
                        else -> TerminalGreen
                    }

                    when (device.category) {
                        DeviceCategory.WIFI_AP -> {
                            // WiFi APs: small cyan squares
                            val halfSide = if (isNearest) 6f * pulseScale else 5f
                            drawRect(
                                color = dotColor,
                                topLeft = Offset(dotX - halfSide, dotY - halfSide),
                                size = Size(halfSide * 2, halfSide * 2)
                            )
                            if (isNearest) {
                                drawRect(
                                    color = dotColor.copy(alpha = 0.3f),
                                    topLeft = Offset(dotX - halfSide - 3f, dotY - halfSide - 3f),
                                    size = Size((halfSide + 3f) * 2, (halfSide + 3f) * 2),
                                    style = Stroke(width = 1.5f)
                                )
                            }
                        }
                        else -> {
                            // BLE devices / Beacons: small green circles
                            val dotRadius = if (isNearest) 6f * pulseScale else 4f
                            drawCircle(
                                color = dotColor,
                                radius = dotRadius,
                                center = Offset(dotX, dotY)
                            )
                            if (isNearest) {
                                drawCircle(
                                    color = dotColor.copy(alpha = 0.3f),
                                    radius = dotRadius + 4f,
                                    center = Offset(dotX, dotY),
                                    style = Stroke(width = 1.5f)
                                )
                            }
                        }
                    }
                }

                // -- Center "YOU" label ------------------------------------------
                val youStyle = TextStyle(
                    color = TerminalGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                val youMeasured = textMeasurer.measure("YOU", youStyle)
                drawText(
                    textLayoutResult = youMeasured,
                    topLeft = Offset(
                        canvasCenter.x - youMeasured.size.width / 2f,
                        canvasCenter.y - youMeasured.size.height / 2f
                    )
                )

                // Center dot
                drawCircle(
                    color = TerminalGreen,
                    radius = 3f,
                    center = canvasCenter
                )
            }
            Column(Modifier.align(Alignment.BottomEnd)) {
                ZdIconButton(
                    ZdIcons.Plus,
                    contentDescription = "Zoom in",
                    onClick = { zoom.value(2f) },
                    enabled = canZoomIn,
                    tint = TerminalGreen
                )
                ZdIconButton(
                    ZdIcons.Minus,
                    contentDescription = "Zoom out",
                    onClick = { zoom.value(0.5f) },
                    enabled = canZoomOut,
                    tint = TerminalGreen
                )
            }
        }
    }
}

