package app.radiusrf.core.lifecycle

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.radiusrf.MainActivity
import app.radiusrf.R

/**
 * Keeps a scan in the foreground while the phone is locked or the app is minimized.
 * The scan itself stays in its screen; this service only holds the notification and a wake lock.
 */
class ScanKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SCAN) {
            ScanBackground.requestStop(this)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val label = intent?.getStringExtra(EXTRA_LABEL)?.ifBlank { null } ?: "Scan"
        val mic = intent?.getBooleanExtra(EXTRA_MIC, false) == true &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ensureChannel()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(label), types(mic))
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun types(mic: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (mic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    private fun notification(label: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ScanKeepAliveService::class.java).setAction(ACTION_STOP_SCAN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$label running")
            .setContentText("Continues with the screen off. Tap to return.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Background scans", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a scan keeps running with the screen off"
            }
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadiusRF:scan").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_MS)
        }
    }

    companion object {
        const val ACTION_STOP = "app.radiusrf.scan.KEEPALIVE_STOP"
        const val ACTION_STOP_SCAN = "app.radiusrf.scan.KEEPALIVE_STOP_SCAN"
        const val EXTRA_LABEL = "label"
        const val EXTRA_MIC = "mic"
        private const val CHANNEL_ID = "radiusrf_background_scan"
        private const val NOTIFICATION_ID = 2003
        private const val MAX_WAKE_MS = 6 * 60 * 60 * 1000L
    }
}
