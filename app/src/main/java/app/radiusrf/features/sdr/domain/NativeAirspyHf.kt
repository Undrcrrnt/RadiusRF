package app.radiusrf.features.sdr.domain

object NativeAirspyHf {
    init {
        System.loadLibrary("emptyset_airspyhf")
    }

    external fun open(fd: Int): String?

    /** [gainTenths] below 0 turns automatic gain on. Otherwise it is attenuation in tenths of a dB. [amp] is the +6 dB preamp. */
    external fun configure(freqHz: Long, sampleRate: Int, gainTenths: Int, amp: Int, biasTee: Int): String?

    external fun read(buffer: ByteArray): Int

    external fun close()
}
