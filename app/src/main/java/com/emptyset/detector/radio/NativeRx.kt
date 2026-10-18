package com.emptyset.detector.radio

/**
 * JNI bridge onto the receive-only RTL8821AU (Jaguar1) userspace driver.
 * Transmission and injection are not exposed.
 */
object NativeRx {
    @Volatile
    var loaded: Boolean = false
        private set

    init {
        loaded = runCatching { System.loadLibrary("emptyset_rx") }.isSuccess
    }

    fun available(): Boolean = loaded

    @JvmStatic
    external fun nativeStart(fd: Int, lockDir: String, channel: Int, sink: Sink): String?

    @JvmStatic
    external fun nativeSetChannel(channel: Int)

    @JvmStatic
    external fun nativeStop()

    interface Sink {
        fun onFrame(frame: ByteArray, rssi: Int, channel: Int)
        fun onReady()
        fun onNativeError(message: String)
        fun onStatus(message: String) {}
    }
}
