package app.radiusrf.features.wifi.domain

import android.content.Context
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException

/**
 * Keeps an ARCore session for one floor walk. Camera frames are used to hold
 * position and are not written anywhere.
 */
class FloorArHost(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var session: Session? = null
    private var resumed = false
    private var textureId = 0
    private var boundTexture = 0
    private var rotation = 0
    private var width = 0
    private var height = 0
    private var originX: Double? = null
    private var originZ: Double? = null

    fun resetOrigin() = synchronized(lock) {
        originX = null
        originZ = null
    }

    /** @return a message when the camera could not start, or null when it is running. */
    fun resume(): String? = synchronized(lock) {
        try {
            if (session == null) {
                val created = Session(appContext)
                created.configure(
                    Config(created).apply {
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        planeFindingMode = Config.PlaneFindingMode.DISABLED
                        focusMode = Config.FocusMode.AUTO
                        lightEstimationMode = Config.LightEstimationMode.DISABLED
                    }
                )
                session = created
                boundTexture = 0
            }
            if (!resumed) {
                session?.resume()
                resumed = true
            }
            null
        } catch (_: CameraNotAvailableException) {
            "The camera is in use by another app."
        } catch (_: UnavailableException) {
            "Floor tracking is unavailable on this phone."
        } catch (_: Exception) {
            "The camera could not start."
        }
    }

    fun pause() {
        synchronized(lock) {
            if (!resumed) return@synchronized
            resumed = false
            runCatching { session?.pause() }
        }
    }

    fun close() = synchronized(lock) {
        resumed = false
        originX = null
        originZ = null
        boundTexture = 0
        val current = session
        session = null
        runCatching { current?.pause() }
        runCatching { current?.close() }
    }

    fun setViewport(rotation: Int, width: Int, height: Int) = synchronized(lock) {
        this.rotation = rotation
        this.width = width
        this.height = height
    }

    fun textureReplaced(id: Int) = synchronized(lock) {
        textureId = id
        boundTexture = 0
    }

    /**
     * Runs on the GL thread. [block] draws the camera frame before the next
     * update replaces it. Returns the floor pose for that frame.
     */
    fun withFrame(block: (Frame) -> Unit): FloorPose? = synchronized(lock) {
        val current = session ?: return null
        if (!resumed || textureId == 0 || width <= 0 || height <= 0) return null
        try {
            if (boundTexture != textureId) {
                current.setCameraTextureName(textureId)
                boundTexture = textureId
            }
            current.setDisplayGeometry(rotation, width, height)
            val frame = current.update()
            block(frame)
            poseOf(frame)
        } catch (_: CameraNotAvailableException) {
            resumed = false
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun poseOf(frame: Frame): FloorPose {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            return FloorPose(0.0, 0.0, 0f, false)
        }
        val pose = camera.pose
        val x = pose.tx().toDouble()
        val z = pose.tz().toDouble()
        if (!x.isFinite() || !z.isFinite()) return FloorPose(0.0, 0.0, 0f, false)
        if (originX == null || originZ == null) {
            originX = x
            originZ = z
        }
        return FloorPose(
            x = x - originX!!,
            z = z - originZ!!,
            headingDeg = FloorWalkMath.yawDegrees(pose.qx(), pose.qy(), pose.qz(), pose.qw()),
            tracking = true
        )
    }
}
