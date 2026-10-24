package app.radiusrf.features.sdr.domain

object NativeHydra {
    init {
        System.loadLibrary("emptyset_hydrasdr")
    }

    external fun open(fd: Int): String?

    /** [gainTenths] below 0 turns automatic gain on. Otherwise it is linearity times 10. */
    external fun configure(freqHz: Long, sampleRate: Int, gainTenths: Int, amp: Int): String?

    external fun read(buffer: ByteArray): Int

    external fun close()
}
