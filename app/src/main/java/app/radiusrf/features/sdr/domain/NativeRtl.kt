package app.radiusrf.features.sdr.domain

object NativeRtl {
    init {
        System.loadLibrary("emptyset_rx")
    }

    /** Null when the dongle is open. */
    external fun open(fd: Int): String?

    /**
     * [gainTenths] below zero turns on the tuner AGC.
     * Frequencies under 24 MHz use direct sampling.
     */
    external fun configure(freqHz: Long, sampleRate: Int, gainTenths: Int, ppm: Int): String?

    /** Bytes written into [buffer], or a negative libusb error. */
    external fun read(buffer: ByteArray): Int

    external fun close()
}
