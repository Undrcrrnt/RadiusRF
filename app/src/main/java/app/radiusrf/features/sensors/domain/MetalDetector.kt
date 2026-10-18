package app.radiusrf.features.sensors.domain

import kotlin.math.sqrt

class MetalDetector {

    companion object {
        // Locking the baseline off a single first sample lets one noisy reading skew an entire
        // session -- averaging a short warm-up window instead smooths that out.
        private const val CALIBRATION_SAMPLES = 10
    }

    private var baseline: Float = 0f
    private var calibrated = false
    private val warmupSamples = mutableListOf<Float>()

    fun update(values: FloatArray): MetalDetectorState {
        if (values.size < 3) return MetalDetectorState()
        val magnitude = sqrt(
            values[0] * values[0] +
            values[1] * values[1] +
            values[2] * values[2]
        )
        if (!calibrated) {
            warmupSamples.add(magnitude)
            baseline = warmupSamples.average().toFloat()
            if (warmupSamples.size >= CALIBRATION_SAMPLES) {
                calibrated = true
            }
        }
        return MetalDetectorState(
            isActive = true,
            baseline = baseline,
            currentMagnitude = magnitude,
            deviation = magnitude - baseline
        )
    }

    fun reset() {
        calibrated = false
        baseline = 0f
        warmupSamples.clear()
    }
}
