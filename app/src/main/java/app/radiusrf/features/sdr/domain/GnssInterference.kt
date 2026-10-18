package app.radiusrf.features.sdr.domain

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.ArrayDeque

/**
 * Phone-GNSS jamming and spoofing hint. A drop in both carrier-to-noise and
 * automatic gain control matches the check described by Android's GNSS docs.
 * A rise in carrier-to-noise with a drop in gain matches the spoofing check.
 */
class GnssInterference(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val cn0 = ArrayDeque<Float>()
    private val agc = ArrayDeque<Float>()
    private var statusCallback: GnssStatus.Callback? = null
    private var measureCallback: GnssMeasurementsEvent.Callback? = null

    fun start(onStatus: (String) -> Unit): String? {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!allowed) return "Allow location to check the phone GNSS receiver"
        val location = manager ?: return "GNSS is not available"
        stop()
        val status = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(gnss: GnssStatus) {
                var sum = 0f
                var n = 0
                var used = 0
                for (i in 0 until gnss.satelliteCount) {
                    val level = gnss.getCn0DbHz(i)
                    if (level > 0f) {
                        sum += level
                        n++
                    }
                    if (gnss.usedInFix(i)) used++
                }
                if (n > 0) push(cn0, sum / n)
                onStatus(describe(used))
            }
        }
        val measure = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                val levels = event.measurements.mapNotNull { reading ->
                    if (Build.VERSION.SDK_INT >= 33 && !reading.hasAutomaticGainControlLevelDb()) return@mapNotNull null
                    val value = reading.automaticGainControlLevelDb
                    if (value.isNaN()) null else value.toFloat()
                }
                if (levels.isNotEmpty()) push(agc, levels.average().toFloat())
            }
        }
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                location.registerGnssStatusCallback(ContextCompat.getMainExecutor(context), status)
            } else {
                @Suppress("DEPRECATION")
                location.registerGnssStatusCallback(status)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                location.registerGnssMeasurementsCallback(ContextCompat.getMainExecutor(context), measure)
            } else {
                @Suppress("DEPRECATION")
                location.registerGnssMeasurementsCallback(measure, Handler(Looper.getMainLooper()))
            }
        }
        if (registered.isFailure) return "GNSS measurements are not available"
        statusCallback = status
        measureCallback = measure
        onStatus("Watching phone GNSS")
        return null
    }

    fun stop() {
        statusCallback?.let { runCatching { manager?.unregisterGnssStatusCallback(it) } }
        measureCallback?.let { runCatching { manager?.unregisterGnssMeasurementsCallback(it) } }
        statusCallback = null
        measureCallback = null
    }

    private fun describe(used: Int): String {
        val cn0Shift = shift(cn0)
        val agcShift = shift(agc)
        return when {
            cn0Shift != null && agcShift != null && cn0Shift < -3f && agcShift < -3f ->
                "Possible GNSS jamming: C/N0 ${cn0Shift.toInt()} dB, AGC ${agcShift.toInt()} dB, $used satellites in the fix"
            cn0Shift != null && agcShift != null && cn0Shift > 3f && agcShift < -3f ->
                "Possible GNSS spoofing: C/N0 +${cn0Shift.toInt()} dB, AGC ${agcShift.toInt()} dB"
            used == 0 && cn0.size >= 10 ->
                "No satellites in the fix"
            else -> "Phone GNSS steady, $used satellites in the fix"
        }
    }

    private fun shift(history: ArrayDeque<Float>): Float? {
        if (history.size < 20) return null
        val recent = history.toList().takeLast(10).average()
        val older = history.toList().dropLast(10).takeLast(10).average()
        return (recent - older).toFloat()
    }

    private fun push(history: ArrayDeque<Float>, value: Float) {
        history.addLast(value)
        while (history.size > 60) history.removeFirst()
    }
}
