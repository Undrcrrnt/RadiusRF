package app.radiusrf.features.wardriving.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.radiusrf.R
import app.radiusrf.features.wardriving.data.WardrivingRepository
import app.radiusrf.features.wardriving.domain.SurveyMode
import app.radiusrf.features.wardriving.domain.WardrivingCollector
import app.radiusrf.features.wardriving.domain.WardrivingSessionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

// Owns the GPS+WiFi collection loop directly so it keeps running independent of whatever screen
// is currently visible — a foreground service that only shows a notification while the real work
// happens in a ViewModel's viewModelScope gets killed the moment that ViewModel is cleared (e.g.
// navigating to any other screen), which defeats the point of "background" logging entirely.
@AndroidEntryPoint
class WardrivingScanService : Service() {

    @Inject lateinit var collector: WardrivingCollector
    @Inject lateinit var repository: WardrivingRepository
    @Inject lateinit var sessionState: WardrivingSessionState

    private val serviceScope = CoroutineScope(SupervisorJob())
    private var collectJob: Job? = null
    private var sessionId: String? = null
    private var surveyMode: SurveyMode = SurveyMode.WIFI

    companion object {
        const val CHANNEL_ID = "wardriving_scan"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_MODE = "mode"
        const val ACTION_STOP = "app.radiusrf.wardriving.STOP"
        const val ACTION_PAUSE = "app.radiusrf.wardriving.PAUSE"
        const val ACTION_RESUME = "app.radiusrf.wardriving.RESUME"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mode = SurveyMode.fromName(intent?.getStringExtra(EXTRA_MODE) ?: surveyMode.name)
        surveyMode = mode
        intent?.getStringExtra(EXTRA_SESSION_ID)?.let { sessionId = it }
        val notification = buildNotification(mode, paused = intent?.action == ACTION_PAUSE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        when (intent?.action) {
            ACTION_PAUSE -> {
                collectJob?.cancel()
                collectJob = null
                sessionState.pause()
                return START_STICKY
            }
            ACTION_RESUME -> {
                sessionState.resume()
                beginCollect()
                return START_STICKY
            }
        }
        if (sessionId != null) beginCollect()
        return START_STICKY
    }

    private fun beginCollect() {
        val id = sessionId ?: return
        collectJob?.cancel()
        collectJob = serviceScope.launch {
            collector.collect(surveyMode)
                .catch { stopSelf() }
                .collect { records -> repository.saveRecords(id, records) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        collectJob?.cancel()
        serviceScope.cancel()
        sessionState.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wardriving Scan",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active wardriving scan in progress"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(mode: SurveyMode, paused: Boolean = false): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (paused) "${mode.title} paused" else "${mode.title} active")
            .setContentText(if (paused) "Log kept · continue from the app" else "Logging ${mode.radiosLabel}")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .addAction(
                0,
                "Stop",
                PendingIntent.getService(
                    this, 0, Intent(this, WardrivingScanService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
    }
}
