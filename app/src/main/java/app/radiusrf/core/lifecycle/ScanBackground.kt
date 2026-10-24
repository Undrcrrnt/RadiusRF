package app.radiusrf.core.lifecycle

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * When this is on, a running scan is not paused just because the app is minimized
 * or the phone is locked. [ScanKeepAliveService] holds a foreground notification
 * so Android keeps the radios awake.
 */
object ScanBackground {
    var enabled by mutableStateOf(false)
        private set

    private val stops = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stopRequests = stops.asSharedFlow()

    private val holders = linkedMapOf<String, String>()
    private var needsMic = false

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        if (!on) releaseAll(context)
    }

    /** Notification action: drop background mode and ask the open scan to stop. */
    fun requestStop(context: Context) {
        enabled = false
        releaseAll(context)
        stops.tryEmit(Unit)
    }

    fun hold(context: Context, id: String, label: String, microphone: Boolean) {
        holders[id] = label
        if (microphone) needsMic = true
        startService(context)
    }

    fun release(context: Context, id: String) {
        if (!holders.containsKey(id)) return
        holders.remove(id)
        if (holders.isEmpty()) {
            needsMic = false
            context.stopService(Intent(context, ScanKeepAliveService::class.java))
        } else {
            startService(context)
        }
    }

    fun releaseAll(context: Context) {
        holders.clear()
        needsMic = false
        context.stopService(Intent(context, ScanKeepAliveService::class.java))
    }

    private fun startService(context: Context) {
        val label = holders.values.distinct().joinToString().ifBlank { "Scan" }
        val intent = Intent(context, ScanKeepAliveService::class.java)
            .putExtra(ScanKeepAliveService.EXTRA_LABEL, label)
            .putExtra(ScanKeepAliveService.EXTRA_MIC, needsMic)
        try {
            context.startForegroundService(intent)
        } catch (_: Exception) {
            enabled = false
            holders.clear()
            needsMic = false
        }
    }
}
