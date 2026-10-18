package com.emptyset.detector.radio

import android.content.Context
import android.hardware.usb.UsbManager

object UsbDeviceFinder {
    fun attached(context: Context): List<RadioInfo> {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return usb.deviceList.values.map { device ->
            when (val kind = UsbIds.classify(device)) {
                RadioKind.T2U_PLUS, RadioKind.RTL8812AU, RadioKind.RTL8814AU, RadioKind.MT7612U -> {
                    val option = RadioCatalog.optionFor(kind)
                    RadioInfo(
                        kind = kind,
                        title = option?.title ?: "USB Wi-Fi",
                        detail = "receive-only  %04x:%04x".format(device.vendorId, device.productId),
                        device = device,
                        canCapture = NativeRx.available()
                    )
                }
                RadioKind.PAU0A -> RadioInfo(
                    kind = RadioKind.PAU0A,
                    title = "Panda PAU0A",
                    detail = "MT7610U receive-only  %04x:%04x".format(device.vendorId, device.productId),
                    device = device,
                    canCapture = NativePau0a.available()
                )
                RadioKind.PAU0B -> RadioInfo(
                    kind = RadioKind.PAU0B,
                    title = "Panda PAU0B",
                    detail = "MT7610U receive-only  %04x:%04x".format(device.vendorId, device.productId),
                    device = device,
                    canCapture = NativePau0a.available()
                )
                RadioKind.UNKNOWN -> {
                    RadioInfo(
                        kind = RadioKind.UNKNOWN,
                        title = device.productName ?: "USB device",
                        detail = "%04x:%04x".format(device.vendorId, device.productId),
                        device = device,
                        canCapture = false
                    )
                }
            }
        }
    }
}
