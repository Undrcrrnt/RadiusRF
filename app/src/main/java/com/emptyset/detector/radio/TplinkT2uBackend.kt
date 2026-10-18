package com.emptyset.detector.radio

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.emptyset.detector.detect.ChannelPlan
import com.emptyset.detector.detect.HopChannel
import com.emptyset.detector.detect.WifiBand
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * USB host path for the TP-Link Archer T2U Plus (RTL8821AU).
 *
 * Opens the Android USB device, hands the file descriptor to the receive-only
 * Jaguar1 userspace driver, hops 2.4/5 GHz, and never transmits.
 */
class TplinkT2uBackend(
    private val context: Context,
    private val device: UsbDevice,
    private val option: RadioOption = RadioCatalog.option(RadioCatalog.T2U_PLUS),
    private val hop24: Boolean = true,
    private val hop5: Boolean = true,
    private val hop6: Boolean = false,
    private val dwellMs: Long = 140
) : RadioBackend {

    override val info: RadioInfo = RadioInfo(
        kind = option.kind,
        title = option.title,
        detail = "%s  %04x:%04x".format(option.detail, device.vendorId, device.productId),
        device = device,
        canCapture = NativeRx.available()
    )

    @Volatile
    private var running = false
    private var connection: UsbDeviceConnection? = null
    private var deliver: ExecutorService? = null

    override suspend fun start(listener: RadioBackend.Listener) {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usb.hasPermission(device)) {
            usb.requestPermission(device, RadioBackend.usbPermissionIntent(context))
            listener.onError("USB permission requested for ${option.title}. Grant it and start again.")
            return
        }
        running = true
        deliver?.shutdownNow()
        val exec = Executors.newSingleThreadExecutor { task ->
            Thread(task, "t2u-rx").apply { isDaemon = true }
        }
        deliver = exec
        val channels = ChannelPlan.hopsetFor(option.kind, hop24, hop5, hop6)
        if (channels.isEmpty()) {
            listener.onError("No tunable channels for the selected bands")
            return
        }
        if (hop6 && channels.none { it.band == WifiBand.GHZ_6 }) {
            listener.onStatus("6 GHz is selected; this adapter tunes 2.4/5 GHz only")
        }

        if (!NativeRx.available()) {
            listener.onError(
                "Native Jaguar1 RX library is missing from this APK (64-bit build required)."
            )
            running = false
            return
        }

        val opened = usb.openDevice(device)
        if (opened == null) {
            listener.onError("Could not open ${option.title} USB device")
            running = false
            return
        }
        connection = opened
        val fd = opened.fileDescriptor
        val lockDir = context.cacheDir.absolutePath
        listener.onStatus("Loading ${option.title} firmware (receive-only)...")
        val sink = object : NativeRx.Sink {
            override fun onFrame(frame: ByteArray, rssi: Int, channel: Int) {
                if (!running) return
                runCatching {
                    exec.execute {
                        if (!running) return@execute
                        listener.onRawFrame(frame, channel, rssi)
                    }
                }
            }

            override fun onReady() {
                runCatching {
                    exec.execute { listener.onStatus("${option.title} firmware running, starting monitor RX...") }
                }
            }

            override fun onNativeError(message: String) {
                running = false
                runCatching { exec.execute { listener.onError(message) } }
            }
        }
        val err = runCatching {
            NativeRx.nativeStart(fd, lockDir, channels.first().channel, sink)
        }.getOrElse { crash ->
            "Driver failed to start: ${crash.message ?: crash.javaClass.simpleName}"
        }
        if (!err.isNullOrEmpty()) {
            runCatching { NativeRx.nativeStop() }
            releaseInterfaces()
            connection?.close()
            connection = null
            running = false
            listener.onError(err)
            return
        }
        listener.onChannel(channels.first().channel, channels.first().band)
        delay(2800)
        if (!running || !currentCoroutineContext().isActive) {
            stopNative()
            return
        }
        listener.onStatus("${option.title} monitor RX active (receive-only)")
        var index = 0
        while (running && currentCoroutineContext().isActive) {
            val hop = channels[index]
            runCatching { NativeRx.nativeSetChannel(hop.channel) }
            listener.onChannel(hop.channel, hop.band)
            delay(dwellMs)
            index = (index + 1) % channels.size
            yield()
        }
        stopNative()
    }

    private fun releaseInterfaces() {
        val opened = connection ?: return
        for (index in 0 until device.interfaceCount) {
            runCatching { opened.releaseInterface(device.getInterface(index)) }
        }
    }

    @Synchronized
    private fun stopNative() {
        runCatching { NativeRx.nativeStop() }
        releaseInterfaces()
        connection?.close()
        connection = null
    }

    override fun stop() {
        running = false
        stopNative()
        deliver?.shutdownNow()
        deliver = null
    }
}
