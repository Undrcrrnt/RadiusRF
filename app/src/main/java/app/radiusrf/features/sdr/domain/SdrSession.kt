package app.radiusrf.features.sdr.domain

import app.radiusrf.core.usb.UsbOpenConnection
import javax.inject.Inject
import javax.inject.Singleton

/** SDR listen session that stays open when Fox Hunt is on screen. */
@Singleton
class SdrSession @Inject constructor() {
    val engine = SdrEngine()
    var connection: UsbOpenConnection? = null
    var connectedVidPid: String? = null
    var radio: SdrRadio = SdrRadio.NONE

    fun tune(hz: Long) {
        val hackrf = radio == SdrRadio.HACKRF
        val tuned = hz.coerceIn(
            if (hackrf) 1_000_000L else 0L,
            if (hackrf) 6_000_000_000L else 1_766_000_000L
        )
        engine.frequencyHz = tuned
        engine.offsetHz = 0.0
        SdrLevel.setFrequency(tuned)
    }
}
