package app.radiusrf.features.sdr.domain

object NativeHackrf {
    init {
        System.loadLibrary("emptyset_rx")
    }

    external fun open(fd: Int): String?

    /** [lna] is 0–40 in steps of 8. [vga] is 0–62 in steps of 2. [amp] is 0 or 1. */
    external fun configure(freqHz: Long, sampleRate: Int, lna: Int, vga: Int, amp: Int): String?

    external fun read(buffer: ByteArray): Int

    external fun close()
}
