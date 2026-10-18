package app.radiusrf.features.locate.domain

/**
 * Relative-loudness cues for one radio. RSSI is not a distance and not a bearing.
 * Bluetooth, and a Wi-Fi adapter that hears beacons continuously, compare the last
 * couple of seconds with a few seconds earlier. The phone's Wi-Fi scan compares
 * one sweep of the locked access point with the previous sweep.
 */
enum class LocateRadio { BLE, WIFI }

enum class LocateCue {
    VERY_CLOSE,
    CLOSER,
    FURTHER,
    SAME,
    WAITING,
    QUIET,
    GONE
}

data class LocateSample(val at: Long, val rssi: Int)

object LocateSignals {
    const val RECENT_MS = 2_000L
    const val EARLIER_FROM_MS = 8_000L
    const val EARLIER_TO_MS = 3_500L
    const val STEP_DB = 3.0
    const val QUIET_MS = 8_000L
    const val VERY_CLOSE_DBM = -45.0
    const val TICK_LOUD_DBM = -40
    const val TICK_QUIET_DBM = -90
    const val TICK_FAST_MS = 90L
    const val TICK_SLOW_MS = 1_400L

    fun packets(samples: List<LocateSample>, now: Long, lastSeen: Long, missing: Boolean): LocateCue {
        if (missing) return LocateCue.GONE
        if (lastSeen == 0L) return LocateCue.WAITING
        if (now - lastSeen > QUIET_MS) return LocateCue.QUIET
        val usable = samples.filter { it.rssi > -127 }
        val recent = usable.filter { it.at >= now - RECENT_MS }
        val loud = if (recent.isNotEmpty()) recent.map { it.rssi }.average() else usable.lastOrNull { now - it.at <= QUIET_MS }?.rssi?.toDouble()
        if (loud != null && loud >= VERY_CLOSE_DBM) return LocateCue.VERY_CLOSE
        val earlier = usable.filter { it.at in (now - EARLIER_FROM_MS)..(now - EARLIER_TO_MS) }
        if (recent.size < 2 || earlier.size < 2) return LocateCue.WAITING
        val delta = recent.map { it.rssi }.average() - earlier.map { it.rssi }.average()
        return step(delta)
    }

    fun sweeps(samples: List<LocateSample>, now: Long, lastSeen: Long, missing: Boolean): LocateCue {
        if (missing) return LocateCue.GONE
        if (lastSeen == 0L) return LocateCue.WAITING
        val usable = samples.filter { it.rssi > -127 }
        val interval = if (usable.size >= 2) {
            (usable.last().at - usable[usable.size - 2].at).coerceIn(3_000L, 40_000L)
        } else {
            30_000L
        }
        if (now - lastSeen > interval + 15_000L) return LocateCue.QUIET
        val last = usable.lastOrNull() ?: return LocateCue.WAITING
        if (last.rssi.toDouble() >= VERY_CLOSE_DBM) return LocateCue.VERY_CLOSE
        if (usable.size < 2) return LocateCue.WAITING
        return step((last.rssi - usable[usable.size - 2].rssi).toDouble())
    }

    fun label(cue: LocateCue): String = when (cue) {
        LocateCue.VERY_CLOSE -> "Very Close"
        LocateCue.CLOSER -> "Closer"
        LocateCue.FURTHER -> "Further"
        LocateCue.SAME -> "About the same"
        LocateCue.WAITING -> "Listening…"
        LocateCue.QUIET -> "Quiet"
        LocateCue.GONE -> "Gone"
    }

    fun hint(cue: LocateCue, wifi: Boolean, sweepPace: Boolean): String = when {
        wifi && sweepPace -> wifiSweepHint(cue)
        wifi -> wifiFastHint(cue)
        else -> bleHint(cue)
    }

    fun tickIntervalMs(rssi: Int?, cue: LocateCue): Long? {
        if (cue == LocateCue.QUIET || cue == LocateCue.GONE) return null
        val level = rssi ?: return null
        val span = (TICK_LOUD_DBM - TICK_QUIET_DBM).toDouble()
        val t = ((level - TICK_QUIET_DBM) / span).coerceIn(0.0, 1.0)
        return (TICK_SLOW_MS + (TICK_FAST_MS - TICK_SLOW_MS) * t).toLong()
    }

    fun band(rssi: Int): String = when {
        rssi >= -45 -> "very strong"
        rssi >= -60 -> "strong"
        rssi >= -75 -> "medium"
        rssi >= -88 -> "weak"
        else -> "very weak"
    }

    private fun step(delta: Double): LocateCue = when {
        delta >= STEP_DB -> LocateCue.CLOSER
        delta <= -STEP_DB -> LocateCue.FURTHER
        else -> LocateCue.SAME
    }

    private fun bleHint(cue: LocateCue): String = when (cue) {
        LocateCue.VERY_CLOSE -> "Screaming loud here. Look around — usually in-hand, pocket, or the same bag. Not meters."
        LocateCue.CLOSER -> "Louder than a few seconds ago. Keep walking that way."
        LocateCue.FURTHER -> "Quieter than a few seconds ago. Turn or back up."
        LocateCue.SAME -> "No clear change yet. Slow down; hold the phone still."
        LocateCue.WAITING -> "Need a few seconds of packets to compare."
        LocateCue.QUIET -> "No packet for a few seconds. Silent, or behind a wall."
        LocateCue.GONE -> "Left the live set. Randomized BLE often vanishes mid-locate."
    }

    private fun wifiSweepHint(cue: LocateCue): String = when (cue) {
        LocateCue.VERY_CLOSE -> "Screaming loud on this access point. Look around — usually the same room. Not meters."
        LocateCue.CLOSER -> "Louder than the last sweep. Keep walking that way, then hold still for the next one."
        LocateCue.FURTHER -> "Quieter than the last sweep. Turn or back up, then hold still."
        LocateCue.SAME -> "No clear change since the last sweep. Hold still until the next one."
        LocateCue.WAITING -> "Locked on. Need one more sweep of this access point."
        LocateCue.QUIET -> "Missed the last sweeps. It may be off, or the phone has not reached its channel."
        LocateCue.GONE -> "Left the live set."
    }

    private fun wifiFastHint(cue: LocateCue): String = when (cue) {
        LocateCue.VERY_CLOSE -> "Screaming loud on this access point. Look around — usually the same room. Not meters."
        LocateCue.CLOSER -> "Louder than a few seconds ago. Keep walking that way."
        LocateCue.FURTHER -> "Quieter than a few seconds ago. Turn or back up."
        LocateCue.SAME -> "No clear change yet. Slow down; hold the adapter still."
        LocateCue.WAITING -> "Locked on. Need a few seconds of beacons to compare."
        LocateCue.QUIET -> "No beacon for a few seconds. It may be off, or the adapter has not reached its channel."
        LocateCue.GONE -> "Left the live set."
    }
}
