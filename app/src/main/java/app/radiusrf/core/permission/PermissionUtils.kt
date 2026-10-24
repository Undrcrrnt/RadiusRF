package app.radiusrf.core.permission

import android.Manifest
import android.os.Build

object PermissionUtils {

    fun blePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun wifiPermissions(): List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    fun cameraPermissions(): List<String> = listOf(
        Manifest.permission.CAMERA
    )

    fun phonePermissions(): List<String> = listOf(
        Manifest.permission.READ_PHONE_STATE
    )

    fun audioPermissions(): List<String> = listOf(
        Manifest.permission.RECORD_AUDIO
    )

    fun cellTowerPermissions(): List<String> = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun locationPermissions(): List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    fun gpsPermissions(): List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    fun wardrivingPermissions(bluetooth: Boolean = false, cellular: Boolean = false): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (bluetooth) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (cellular) add(Manifest.permission.READ_PHONE_STATE)
    }

    fun wifiDirectPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    fun gpsSpoofPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.READ_PHONE_STATE)
    }

    fun uwbPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.UWB_RANGING)
        }
    }

    fun wifiAwarePermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    fun hiddenCameraPermissions(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    fun privacyScorePermissions(): List<String> = (wifiPermissions() + blePermissions()).distinct()

    /** Runtime permissions the user can grant or revoke. Install-time permissions are omitted. */
    fun toggleablePermissions(): List<RuntimePermission> = buildList {
        add(RuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION, "Precise location", "Wi-Fi scans, Bluetooth on older Android, cell towers, GPS, and fox hunts."))
        add(RuntimePermission(Manifest.permission.ACCESS_COARSE_LOCATION, "Approximate location", "A coarser fix. Precise location includes this."))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(RuntimePermission(Manifest.permission.BLUETOOTH_SCAN, "Bluetooth scan", "Find nearby Bluetooth devices."))
            add(RuntimePermission(Manifest.permission.BLUETOOTH_CONNECT, "Bluetooth connect", "Connect to a Bluetooth device."))
            add(RuntimePermission(Manifest.permission.UWB_RANGING, "Ultra-wideband", "Measure distance with UWB."))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(RuntimePermission(Manifest.permission.NEARBY_WIFI_DEVICES, "Nearby Wi-Fi devices", "Wi-Fi Direct and Wi-Fi Aware."))
            add(RuntimePermission(Manifest.permission.POST_NOTIFICATIONS, "Notifications", "Show an alert while a scan keeps running."))
        }
        add(RuntimePermission(Manifest.permission.CAMERA, "Camera", "QR codes and the camera preview."))
        add(RuntimePermission(Manifest.permission.RECORD_AUDIO, "Microphone", "Ultrasonic and sound measurement. Audio is not saved."))
        add(RuntimePermission(Manifest.permission.READ_PHONE_STATE, "Phone", "Cell tower IDs and signal. Calls and messages stay private."))
    }
}

data class RuntimePermission(
    val name: String,
    val title: String,
    val detail: String
)
