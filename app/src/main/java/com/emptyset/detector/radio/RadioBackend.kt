package com.emptyset.detector.radio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.os.Build
import com.emptyset.detector.detect.WifiBand

enum class RadioKind {
    T2U_PLUS,
    PAU0A,
    PAU0B,
    RTL8812AU,
    RTL8814AU,
    MT7612U,
    UNKNOWN
}

data class RadioInfo(
    val kind: RadioKind,
    val title: String,
    val detail: String,
    val device: UsbDevice?,
    val canCapture: Boolean
)

interface RadioBackend {
    val info: RadioInfo
    suspend fun start(listener: Listener)
    fun stop()

    interface Listener {
        fun onStatus(message: String)
        fun onChannel(channel: Int, band: WifiBand = WifiBand.infer(channel))
        fun onRawFrame(frame: ByteArray, channel: Int?, rssiDbm: Int?)
        fun onError(message: String)
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.emptyset.detector.USB_PERMISSION"

        /**
         * The system fills in the granted device, so the intent must be mutable.
         * Targeting API 34+ throws if that mutable intent is also implicit.
         */
        fun usbPermissionIntent(context: Context): PendingIntent {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            return PendingIntent.getBroadcast(context, 0, intent, flags)
        }
    }
}

object UsbIds {
    const val TPLINK_VID = 0x2357
    const val T2U_PLUS_PID = 0x0120
    const val REALTEK_VID = 0x0BDA
    const val ZEROCD_PID = 0x1A2B
    const val MEDIATEK_VID = 0x0E8D
    const val MT7610U_PID = 0x7610
    const val RALINK_VID = 0x148F

    /** Archer T2U Plus and the Realtek reference RTL8821AU id. */
    private val t2uIds = setOf(
        0x2357 to 0x0120,
        0x2357 to 0x011E,
        0x2357 to 0x0122,
        0x0BDA to 0x0811
    )

    /** Alfa and other RTL8812AU sticks that enumerate as the reference ids. */
    private val rtl8812Ids = setOf(
        0x0BDA to 0x8812,
        0x0BDA to 0x881A
    )

    /** Alfa AWUS1900 and other RTL8814AU sticks. */
    private val rtl8814Ids = setOf(
        0x0BDA to 0x8813
    )

    private val pau0aIds = setOf(
        0x0E8D to 0x7610,
        0x148F to 0x7610
    )

    /**
     * MT7662-MAC adapters. Same pairs as the userspace driver's id table, so a
     * MediaTek stick is not opened as a Realtek one.
     */
    private val mt7612Ids = setOf(
        0x0B05 to 0x1833,
        0x0B05 to 0x17EB,
        0x0B05 to 0x180B,
        0x0E8D to 0x7612,
        0x057C to 0x8503,
        0x7392 to 0xB711,
        0x056E to 0x400A,
        0x0E8D to 0x7632,
        0x0471 to 0x2126,
        0x0471 to 0x7600,
        0x2C4E to 0x0103,
        0x0846 to 0x9014,
        0x0846 to 0x9053,
        0x045E to 0x02E6,
        0x045E to 0x02FE,
        0x2357 to 0x0137
    )

    fun isT2u(device: UsbDevice): Boolean =
        t2uIds.contains(device.vendorId to device.productId)

    fun isRtl8812(device: UsbDevice): Boolean =
        rtl8812Ids.contains(device.vendorId to device.productId)

    fun isRtl8814(device: UsbDevice): Boolean =
        rtl8814Ids.contains(device.vendorId to device.productId)

    fun isMt7612(device: UsbDevice): Boolean =
        mt7612Ids.contains(device.vendorId to device.productId)

    fun isZeroCd(device: UsbDevice): Boolean =
        device.vendorId == REALTEK_VID && device.productId == ZEROCD_PID

    fun isPau0Family(device: UsbDevice): Boolean {
        val name = device.productName.orEmpty().uppercase()
        if (name.contains("PAU0A") || name.contains("PAU0B")) return true
        return pau0aIds.contains(device.vendorId to device.productId)
    }

    fun isPau0a(device: UsbDevice): Boolean = isPau0Family(device)

    fun matches(kind: RadioKind, device: UsbDevice?): Boolean {
        if (device == null) return false
        return when (kind) {
            RadioKind.T2U_PLUS -> isT2u(device)
            RadioKind.RTL8812AU -> isRtl8812(device)
            RadioKind.RTL8814AU -> isRtl8814(device)
            RadioKind.MT7612U -> isMt7612(device)
            RadioKind.PAU0A, RadioKind.PAU0B -> isPau0Family(device)
            RadioKind.UNKNOWN -> false
        }
    }

    fun classify(device: UsbDevice): RadioKind = when {
        isT2u(device) -> RadioKind.T2U_PLUS
        isRtl8812(device) -> RadioKind.RTL8812AU
        isRtl8814(device) -> RadioKind.RTL8814AU
        isPau0Family(device) -> pau0KindFromName(device)
        isMt7612(device) -> RadioKind.MT7612U
        else -> RadioKind.UNKNOWN
    }

    private fun pau0KindFromName(device: UsbDevice): RadioKind {
        val name = device.productName.orEmpty().uppercase()
        return when {
            name.contains("PAU0B") -> RadioKind.PAU0B
            name.contains("PAU0A") -> RadioKind.PAU0A
            else -> RadioKind.PAU0A
        }
    }
}
