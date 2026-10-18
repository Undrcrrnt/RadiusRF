package app.radiusrf.core.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class UsbOpenConnection(
    val connection: UsbDeviceConnection,
    val device: UsbDevice,
    val claimedInterface: UsbInterface?
) {
    fun close() {
        claimedInterface?.let { connection.releaseInterface(it) }
        connection.close()
    }
}

sealed class UsbConnectionResult {
    data class Success(val connection: UsbOpenConnection) : UsbConnectionResult()
    data class Failure(val reason: String) : UsbConnectionResult()
}

// Wraps Android's requestPermission-then-openDevice flow (a device stays inaccessible until the
// user grants per-device USB access, separate from the app's runtime permission set) and claims
// the device's first interface so callers get a ready-to-use connection.
class UsbConnectionManager(
    private val context: Context,
    private val usbManager: UsbManager?
) {

    fun findDevice(vendorId: Int, productId: Int): UsbDevice? =
        usbManager?.deviceList?.values?.find { it.vendorId == vendorId && it.productId == productId }

    suspend fun requestPermissionAndOpen(device: UsbDevice, claim: Boolean = true): UsbConnectionResult {
        val manager = usbManager ?: return UsbConnectionResult.Failure("USB host not available")

        if (!manager.hasPermission(device)) {
            val granted = requestPermission(manager, device)
            if (!granted) return UsbConnectionResult.Failure("USB permission denied")
        }

        val connection = manager.openDevice(device)
            ?: return UsbConnectionResult.Failure("Failed to open device")

        if (!claim) {
            return UsbConnectionResult.Success(UsbOpenConnection(connection, device, null))
        }

        val iface = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull()
        if (iface == null) {
            connection.close()
            return UsbConnectionResult.Failure("Device exposes no interfaces")
        }

        if (!connection.claimInterface(iface, true)) {
            connection.close()
            return UsbConnectionResult.Failure("Failed to claim interface")
        }

        return UsbConnectionResult.Success(UsbOpenConnection(connection, device, iface))
    }

    private suspend fun requestPermission(manager: UsbManager, device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val action = "${context.packageName}.USB_PERMISSION"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != action) return
                    try {
                        context.unregisterReceiver(this)
                    } catch (_: IllegalArgumentException) {
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(granted)
                }
            }

            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(action).setPackage(context.packageName),
                flags
            )

            cont.invokeOnCancellation {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: IllegalArgumentException) {
                }
            }

            manager.requestPermission(device, pendingIntent)
        }
}
