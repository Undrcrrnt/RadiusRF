package app.radiusrf.features.field.domain

import kotlinx.coroutines.flow.MutableStateFlow

/** Bearing shared by the fox hunt and the on-device map. Degrees clockwise from north. */
object FieldBearing {
    val degrees = MutableStateFlow<Float?>(null)
    val label = MutableStateFlow("No bearing yet")

    fun set(bearing: Float?, text: String) {
        degrees.value = bearing
        label.value = text
    }
}
