package app.radiusrf.features.privacy_score.domain

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.MediaDrm
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class ExposureFact(val title: String, val value: String, val why: String)

/** The readings any app can take from this phone, kept on the device. */
object DeviceExposure {

    fun facts(context: Context): List<ExposureFact> = listOf(
        ExposureFact("Time zone", TimeZone.getDefault().id, "A site or app can read this with no prompt."),
        ExposureFact(
            "Languages",
            Locale.getDefault().toLanguageTag() + languages(context),
            "The language list is part of a device fingerprint."
        ),
        ExposureFact("Screen", screen(context), "Width, height, density and refresh rate narrow down the phone."),
        ExposureFact("Battery", battery(context), "Level and charging state are readable by any app."),
        ExposureFact("Storage", storage(), "Free and total space are readable by any app."),
        ExposureFact(
            "Phone",
            "${Build.MANUFACTURER} ${Build.MODEL} · patch ${Build.VERSION.SECURITY_PATCH}",
            "Model and patch level are public to every app."
        ),
        ExposureFact(
            "Android ID",
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "Unavailable",
            "Stays the same when an app is deleted, until the phone is reset."
        ),
        ExposureFact("Advertising ID", advertisingId(context), "A resettable ID many apps read through Play services."),
        ExposureFact("Paired accessories", paired(context), "A paired name often includes a person's name."),
        ExposureFact("Apps with a home-screen icon", apps(context), "An app can see which of these are installed."),
        ExposureFact("Boot count", bootCount(context), "How many times this phone has been started."),
        ExposureFact("Media ID", mediaId(), "A media identifier that can survive deleting an app.")
    )

    private fun languages(context: Context): String {
        val list = context.resources.configuration.locales
        if (list.isEmpty) return ""
        val extra = (0 until list.size()).map { list[it].toLanguageTag() }.distinct()
        return if (extra.size <= 1) "" else " · " + extra.drop(1).joinToString(", ")
    }

    private fun screen(context: Context): String {
        val metrics = context.resources.displayMetrics
        val rate = context.getSystemService(DisplayManager::class.java)?.displays?.firstOrNull()?.refreshRate
        val hz = rate?.let { " · ${it.toInt()} Hz" } ?: ""
        return "${metrics.widthPixels}×${metrics.heightPixels} · ${metrics.densityDpi} dpi$hz"
    }

    private fun battery(context: Context): String {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return "Unavailable"
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = manager.isCharging
        return if (level < 0) "Unavailable" else "$level% · " + if (charging) "charging" else "on battery"
    }

    private fun storage(): String {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        return "${gb(free)} GB free of ${gb(total)} GB"
    }

    private fun gb(bytes: Long): String = "%.1f".format(Locale.US, bytes / 1_000_000_000.0)

    private fun advertisingId(context: Context): String = runCatching {
        val cls = Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient")
        val info = cls.getMethod("getAdvertisingIdInfo", Context::class.java).invoke(null, context)
        val id = info.javaClass.getMethod("getId").invoke(info) as? String
        val limited = info.javaClass.getMethod("isLimitAdTrackingEnabled").invoke(info) as? Boolean ?: false
        when {
            id.isNullOrBlank() -> "Not available"
            limited -> "Limited by the phone's ad setting"
            else -> id
        }
    }.getOrDefault("Not available on this phone")

    @SuppressLint("MissingPermission")
    private fun paired(context: Context): String {
        val names = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.mapNotNull { it.name }
        }.getOrNull().orEmpty()
        return if (names.isEmpty()) "None paired" else names.joinToString(", ")
    }

    private fun apps(context: Context): String {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0)
        }
        val names = resolved.map { it.loadLabel(context.packageManager).toString() }.distinct().sorted()
        if (names.isEmpty()) return "None visible"
        val shown = names.take(12).joinToString(", ")
        val more = names.size - 12
        return "${names.size} · $shown" + if (more > 0) ", and $more more" else ""
    }

    private fun bootCount(context: Context): String =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            .let { if (it < 0) "Unavailable" else it.toString() }

    private fun mediaId(): String = runCatching {
        val drm = MediaDrm(UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"))
        val id = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) drm.close() else @Suppress("DEPRECATION") drm.release()
        id.joinToString("") { "%02x".format(it) }.take(16) + "…"
    }.getOrDefault("Not available")
}
