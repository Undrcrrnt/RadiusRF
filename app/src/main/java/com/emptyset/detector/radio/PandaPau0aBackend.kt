package com.emptyset.detector.radio

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.emptyset.detector.detect.ChannelPlan
import com.emptyset.detector.detect.WifiBand
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * USB host path for Panda PAU0A / PAU0B (MediaTek MT7610U).
 *
 * Opens the Android USB device, loads mt7610u.bin, hops 2.4/5 GHz, and
 * never transmits 802.11 frames.
 */
class PandaPau0aBackend(
    private val context: Context,
    private val device: UsbDevice,
    private val option: RadioOption = RadioCatalog.option(RadioCatalog.PAU0A),
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
        canCapture = NativePau0a.available()
    )

    @Volatile
    private var running = false
    private val usbBus = Any()
    private var connection: UsbDeviceConnection? = null
    private var deliver: ExecutorService? = null
    private var rxPump: Thread? = null

    override suspend fun start(listener: RadioBackend.Listener) {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usb.hasPermission(device)) {
            usb.requestPermission(device, RadioBackend.usbPermissionIntent(context))
            listener.onError("USB permission requested for ${option.title}. Grant it and start again.")
            return
        }
        val fw = extractFirmware(context)
        if (fw == null) {
            listener.onError("mt7610u.bin is missing from this APK")
            return
        }
        running = true
        deliver?.shutdownNow()
        val exec = Executors.newSingleThreadExecutor { task ->
            Thread(task, "pau0a-rx").apply { isDaemon = true }
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
        if (!NativePau0a.available()) {
            listener.onError("Native MT7610U RX library is missing from this APK (64-bit build required).")
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
        for (index in 0 until device.interfaceCount) {
            runCatching { opened.claimInterface(device.getInterface(index), true) }
        }
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

            override fun onStatus(message: String) {
                runCatching { exec.execute { listener.onStatus(message) } }
            }
        }
        var err = runCatching {
            NativePau0a.nativeStart(opened.fileDescriptor, fw.absolutePath, channels.first().channel, sink)
        }.getOrElse { crash ->
            "Driver failed to start: ${crash.message ?: crash.javaClass.simpleName}"
        }
        if (!err.isNullOrEmpty() && err.contains("MAC did not come ready")) {
            listener.onStatus("${option.title}: MAC not ready, resetting USB and retrying...")
            runCatching { NativePau0a.nativeStop() }
            releaseInterfaces()
            connection?.close()
            connection = null
            delay(400)
            val retry = usb.openDevice(device)
            if (retry == null) {
                running = false
                listener.onError("Could not reopen ${option.title} after MAC reset")
                return
            }
            connection = retry
            for (index in 0 until device.interfaceCount) {
                runCatching { retry.claimInterface(device.getInterface(index), true) }
            }
            err = runCatching {
                NativePau0a.nativeStart(retry.fileDescriptor, fw.absolutePath, channels.first().channel, sink)
            }.getOrElse { crash ->
                "Driver failed to start: ${crash.message ?: crash.javaClass.simpleName}"
            }
        }
        if (!err.isNullOrEmpty()) {
            runCatching { NativePau0a.nativeStop() }
            releaseInterfaces()
            connection?.close()
            connection = null
            running = false
            listener.onError(err)
            return
        }
        val active = connection ?: return
        listener.onChannel(channels.first().channel, channels.first().band)
        startUsbRx(active, listener)
        delay(200)
        if (!running || !currentCoroutineContext().isActive) {
            stopNative()
            return
        }
        listener.onStatus("${option.title} monitor RX active (receive-only)")
        var index = 0
        while (running && currentCoroutineContext().isActive) {
            val hop = channels[index]
            synchronized(usbBus) {
                if (running) NativePau0a.nativeSetChannel(hop.channel)
            }
            listener.onChannel(hop.channel, hop.band)
            if (index % 8 == 0) {
                runCatching { NativePau0a.nativeRxStats() }.getOrNull()?.let { stats ->
                    listener.onStatus(stats)
                }
            }
            delay(dwellMs)
            index = (index + 1) % channels.size
            yield()
        }
        stopNative()
    }

    /** Prefer packet RX 0x84; never use MCU cmd 0x85. */
    private fun findPacketRx(): Pair<UsbInterface, UsbEndpoint>? {
        val prefer = runCatching { NativePau0a.nativeRxEndpoint() and 0xFF }.getOrDefault(0x84)
            .let { if (it == 0x85 || it == 0) 0x84 else it }
        var preferred: Pair<UsbInterface, UsbEndpoint>? = null
        var fallback: Pair<UsbInterface, UsbEndpoint>? = null
        for (index in 0 until device.interfaceCount) {
            val intf = device.getInterface(index)
            for (end in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(end)
                if (ep.direction != UsbConstants.USB_DIR_IN) continue
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.address == 0x85) continue
                when (ep.address) {
                    prefer, 0x84 -> preferred = intf to ep
                    else -> if (fallback == null) fallback = intf to ep
                }
            }
        }
        return preferred ?: fallback
    }

    @Synchronized
    private fun startUsbRx(opened: UsbDeviceConnection, listener: RadioBackend.Listener) {
        if (!running) return
        val found = findPacketRx()
        if (found == null) {
            NativePau0a.nativeStartPump()
            listener.onStatus("${option.title} USB RX using native libusb pump")
            return
        }
        val (intf, ep) = found
        runCatching { opened.claimInterface(intf, false) }
        listener.onStatus("${option.title} USB RX ep=0x%02x".format(ep.address))
        val pump = Thread({
            val buf = ByteArray(4096)
            while (running) {
                val n = runCatching {
                    synchronized(usbBus) {
                        if (!running) -1 else opened.bulkTransfer(ep, buf, buf.size, 40)
                    }
                }.getOrDefault(-1)
                if (!running) break
                if (n > 0) runCatching { NativePau0a.nativePushRx(buf, n) }
                else runCatching { NativePau0a.nativeRxPoll(n) }
            }
        }, "mt7610-usb-rx")
        pump.isDaemon = true
        rxPump = pump
        pump.start()
    }

    private fun releaseInterfaces() {
        val opened = connection ?: return
        for (index in 0 until device.interfaceCount) {
            runCatching { opened.releaseInterface(device.getInterface(index)) }
        }
    }

    @Synchronized
    private fun stopNative() {
        running = false
        val pump = rxPump
        rxPump = null
        if (pump != null && pump !== Thread.currentThread()) {
            runCatching { pump.join() }
        }
        runCatching { NativePau0a.nativeStop() }
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

    companion object {
        fun extractFirmware(context: Context): File? {
            return runCatching {
                val dir = File(context.cacheDir, "firmware")
                if (!dir.exists()) dir.mkdirs()
                val out = File(dir, "mt7610u.bin")
                context.assets.open("firmware/mt7610u.bin").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                if (out.length() < 1024L) null else out
            }.getOrNull()
        }
    }
}
