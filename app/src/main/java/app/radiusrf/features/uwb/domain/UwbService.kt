package app.radiusrf.features.uwb.domain

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

sealed class UwbRangingUpdate {
    data class SessionReady(val config: UwbSessionConfig) : UwbRangingUpdate()
    data object Initialized : UwbRangingUpdate()
    data class Position(val measurement: UwbRangingMeasurement) : UwbRangingUpdate()
    data class PeerDisconnected(val reason: Int) : UwbRangingUpdate()
    data class Failure(val reason: Int) : UwbRangingUpdate()
    data class Error(val message: String) : UwbRangingUpdate()
}

/**
 * Wraps the real Jetpack UWB API (androidx.core.uwb) to perform two-way ranging with a
 * peer device. There is no out-of-band discovery channel (no NFC/BLE handshake) - the
 * Controller generates fresh session parameters and the user copies them into the peer's
 * Controlee inputs by hand, same as this app's own manual-entry UI.
 */
class UwbService(private val context: Context) {

    private val uwbManager: UwbManager by lazy { UwbManager.createInstance(context) }

    val isAvailable: Boolean
        get() = context.packageManager.hasSystemFeature("android.hardware.uwb")

    fun getDeviceInfo(): UwbDeviceInfo {
        val available = isAvailable
        val capabilities = buildList {
            if (available) {
                add("FiRa-compliant ranging (Jetpack androidx.core.uwb)")
                add("Distance measurement (DS-TWR)")
                add("Angle of Arrival (AoA), if supported by chipset")
            }
        }
        return UwbDeviceInfo(
            isAvailable = available,
            chipset = if (available) "UWB radio detected" else "Not available",
            capabilities = capabilities
        )
    }

    /**
     * Opens a controller session and starts ranging against [peerAddressHex]. Emits
     * [UwbRangingUpdate.SessionReady] first with this device's freshly generated session
     * parameters - the caller must display these so the user can copy them into the peer
     * device's Controlee screen before ranging results start arriving.
     */
    suspend fun startControllerRanging(peerAddressHex: String): Flow<UwbRangingUpdate> = flow {
        val scope = uwbManager.controllerSessionScope()
        val sessionId = Random.nextInt(1, Int.MAX_VALUE)
        val sessionKey = ByteArray(8).also { Random.nextBytes(it) }
        val complexChannel = scope.uwbComplexChannel

        emit(
            UwbRangingUpdate.SessionReady(
                UwbSessionConfig(
                    localAddressHex = scope.localAddress.toHex(),
                    sessionId = sessionId,
                    sessionKeyHex = sessionKey.toHex(),
                    channel = complexChannel.channel,
                    preambleIndex = complexChannel.preambleIndex
                )
            )
        )

        val parameters = RangingParameters(
            uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
            sessionId = sessionId,
            subSessionId = 0,
            sessionKeyInfo = sessionKey,
            subSessionKeyInfo = null,
            complexChannel = complexChannel,
            peerDevices = listOf(UwbDevice(peerAddressHex.toUwbAddress())),
            updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC
        )

        scope.prepareSession(parameters).collect { result -> emit(result.toUpdate()) }
    }.catch { e ->
        Log.w(TAG, "Controller ranging session failed", e)
        emit(UwbRangingUpdate.Error(e.message ?: e::class.simpleName ?: "Unknown UWB error"))
    }

    /**
     * Opens a controlee session and starts ranging using session parameters copied
     * out-of-band from a peer device running the controller role (see
     * [startControllerRanging]'s emitted [UwbSessionConfig]).
     */
    suspend fun startControleeRanging(
        peerAddressHex: String,
        sessionId: Int,
        sessionKeyHex: String,
        channel: Int,
        preambleIndex: Int
    ): Flow<UwbRangingUpdate> = flow {
        val scope = uwbManager.controleeSessionScope()

        emit(
            UwbRangingUpdate.SessionReady(
                UwbSessionConfig(
                    localAddressHex = scope.localAddress.toHex(),
                    sessionId = sessionId,
                    sessionKeyHex = sessionKeyHex,
                    channel = channel,
                    preambleIndex = preambleIndex
                )
            )
        )

        val parameters = RangingParameters(
            uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
            sessionId = sessionId,
            subSessionId = 0,
            sessionKeyInfo = sessionKeyHex.hexToBytes(),
            subSessionKeyInfo = null,
            complexChannel = UwbComplexChannel(channel, preambleIndex),
            peerDevices = listOf(UwbDevice(peerAddressHex.toUwbAddress())),
            updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC
        )

        scope.prepareSession(parameters).collect { result -> emit(result.toUpdate()) }
    }.catch { e ->
        Log.w(TAG, "Controlee ranging session failed", e)
        emit(UwbRangingUpdate.Error(e.message ?: e::class.simpleName ?: "Unknown UWB error"))
    }

    private fun RangingResult.toUpdate(): UwbRangingUpdate = when (this) {
        is RangingResult.RangingResultPosition -> UwbRangingUpdate.Position(
            UwbRangingMeasurement(
                distanceMeters = position.distance?.value,
                azimuthDegrees = position.azimuth?.value,
                elevationDegrees = position.elevation?.value
            )
        )
        is RangingResult.RangingResultInitialized -> UwbRangingUpdate.Initialized
        is RangingResult.RangingResultPeerDisconnected -> UwbRangingUpdate.PeerDisconnected(reason)
        is RangingResult.RangingResultFailure -> UwbRangingUpdate.Failure(reason)
    }

    companion object {
        private const val TAG = "UwbService"
    }
}

private fun UwbAddress.toHex(): String = address.toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

private fun String.toUwbAddress(): UwbAddress = UwbAddress(hexToBytes())

private fun String.hexToBytes(): ByteArray {
    val clean = replace(":", "").replace(" ", "")
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
