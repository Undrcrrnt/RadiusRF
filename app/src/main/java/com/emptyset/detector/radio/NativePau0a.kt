package com.emptyset.detector.radio

/**
 * JNI bridge onto the receive-only MT7610U (Panda PAU0A) userspace driver.
 * Transmission and injection are not exposed.
 */
object NativePau0a {
    @Volatile
    var loaded: Boolean = false
        private set

    init {
        loaded = NativeRx.available()
    }

    fun available(): Boolean = loaded

    @JvmStatic
    external fun nativeStart(fd: Int, fwPath: String, channel: Int, sink: NativeRx.Sink): String?

    @JvmStatic
    external fun nativeSetChannel(channel: Int)

    @JvmStatic
    external fun nativeStartPump()

    @JvmStatic
    external fun nativePushRx(data: ByteArray, length: Int)

    @JvmStatic
    external fun nativeRxPoll(lastRc: Int)

    @JvmStatic
    external fun nativeKickRx()

    @JvmStatic
    external fun nativeRxStats(): String

    /** Bulk IN address the native driver selected for 802.11 RX (e.g. 0x84). */
    @JvmStatic
    external fun nativeRxEndpoint(): Int

    @JvmStatic
    external fun nativeStop()
}
