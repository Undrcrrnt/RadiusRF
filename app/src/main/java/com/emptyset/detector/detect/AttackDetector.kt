package com.emptyset.detector.detect

/**
 * Port of the Spacehuhn DeauthDetector attack window (MIT):
 * count deauth/disassoc frames, hop channels, and raise an alert when the
 * rate stays above threshold for one full sweep.
 *
 * Source behavior confirmed on the attached ESP8266EX firmware strings:
 * "Started \\o/", "Packets/s:", "ATTACK DETECTED", "ATTACK STOPPED".
 */
class AttackDetector(
    private val packetRateThreshold: Int = 5,
    private val minSweeps: Int = 1
) {
    data class Snapshot(
        val packetsInWindow: Int,
        val attacking: Boolean,
        val channel: Int?,
        val lastEvent: Ieee80211.MgmtEvent?
    )

    private var packetsInWindow = 0
    private var attackCounter = 0
    private var attacking = false
    var channel: Int? = null
        private set
    var lastEvent: Ieee80211.MgmtEvent? = null
        private set

    @Synchronized
    fun onFrame(event: Ieee80211.MgmtEvent) {
        packetsInWindow += 1
        lastEvent = event
        if (event.channelHint != null) channel = event.channelHint
    }

    @Synchronized
    fun onChannel(channel: Int) {
        this.channel = channel
    }

    @Synchronized
    fun onSweepEnd(): SweepResult {
        val rate = packetsInWindow
        packetsInWindow = 0
        if (rate >= packetRateThreshold) {
            attackCounter += 1
        } else {
            val stopped = attacking
            attackCounter = 0
            attacking = false
            return SweepResult(rate, attacking, started = false, stopped = stopped)
        }
        val started = !attacking && attackCounter >= minSweeps
        if (started) attacking = true
        return SweepResult(rate, attacking, started = started, stopped = false)
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(packetsInWindow, attacking, channel, lastEvent)

    data class SweepResult(
        val packetsPerSweep: Int,
        val attacking: Boolean,
        val started: Boolean,
        val stopped: Boolean
    )
}
