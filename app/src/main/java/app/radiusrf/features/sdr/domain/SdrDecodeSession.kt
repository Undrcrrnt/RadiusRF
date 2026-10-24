package app.radiusrf.features.sdr.domain

class SdrDecodeSession(private val session: SdrSession) {
    private var tap: IqTap? = null

    suspend fun start(hz: Long, rate: Int, onIq: (ByteArray, Int, Boolean, Int, Long) -> Unit): String? {
        tap?.close()
        val handoff = IqTap(onIq)
        tap = handoff
        session.engine.audioGain = 0f
        val error = session.start()
        if (error != null) {
            handoff.close()
            tap = null
            return error
        }
        // start() stops the engine first, which clears a tap installed before it.
        session.engine.onIq = handoff::offer
        session.engine.decodeOnly = true
        session.engine.sampleRate = SdrBands.nearestRate(session.radio, rate)
        session.tune(hz)
        return null
    }

    fun pause() = session.pause()
    fun resume() = session.resume()

    fun stop() {
        session.engine.onIq = null
        session.engine.decodeOnly = false
        tap?.close()
        tap = null
        session.stop()
    }
}

/**
 * Copies one IQ buffer off the USB thread and drops the next until that copy is decoded.
 * The receiver must not wait on ADS-B, AIS, or ISM parsing.
 */
private class IqTap(
    private val onFrame: (ByteArray, Int, Boolean, Int, Long) -> Unit
) {
    private val lock = Object()
    private var write = ByteArray(0)
    private var read = ByteArray(0)
    private var length = 0
    private var signed = false
    private var rate = 0
    private var hz = 0L
    private var pending = false
    @Volatile private var running = true

    private val thread = Thread({
        while (running) {
            val buf: ByteArray
            val n: Int
            val signedNow: Boolean
            val rateNow: Int
            val hzNow: Long
            synchronized(lock) {
                while (running && !pending) lock.wait()
                if (!running) return@Thread
                pending = false
                val swap = read
                read = write
                write = swap
                buf = read
                n = length
                signedNow = signed
                rateNow = rate
                hzNow = hz
            }
            runCatching { onFrame(buf, n, signedNow, rateNow, hzNow) }
        }
    }, "sdr-decode").also {
        it.isDaemon = true
        it.start()
    }

    fun offer(iq: ByteArray, n: Int, signedIq: Boolean, sampleRate: Int, frequencyHz: Long) {
        if (!running || n <= 0) return
        synchronized(lock) {
            if (!running) return
            if (write.size < n) write = ByteArray(n)
            System.arraycopy(iq, 0, write, 0, n)
            length = n
            signed = signedIq
            rate = sampleRate
            hz = frequencyHz
            pending = true
            lock.notifyAll()
        }
    }

    fun close() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        thread.join(400)
    }
}
