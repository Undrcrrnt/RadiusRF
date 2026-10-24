package app.radiusrf.features.sdr.domain

object NativeRtl {
    init {
        System.loadLibrary("emptyset_rx")
    }

    /** Null when the dongle is open. */
    external fun open(fd: Int): String?

    /**
     * [gainTenths] below zero turns on the tuner AGC.
     * [directSampling] is 0 off, 1 I, 2 Q, or -1 automatic below 24 MHz.
     */
    external fun configure(
        freqHz: Long,
        sampleRate: Int,
        gainTenths: Int,
        ppm: Int,
        biasTee: Int,
        directSampling: Int
    ): String?

    /** Bytes written into [buffer], or a negative libusb error. */
    external fun read(buffer: ByteArray): Int

    external fun close()
}
