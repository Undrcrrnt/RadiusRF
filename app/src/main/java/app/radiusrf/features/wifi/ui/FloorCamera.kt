package app.radiusrf.features.wifi.ui

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.radiusrf.features.wifi.domain.FloorArHost
import app.radiusrf.features.wifi.domain.FloorPose
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class FloorCameraCallbacks {
    var onPose: (FloorPose) -> Unit = {}
    var onError: (String) -> Unit = {}
}

@Composable
fun FloorCamera(
    host: FloorArHost,
    callbacks: FloorCameraCallbacks,
    modifier: Modifier = Modifier
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember(host) { arrayOfNulls<FloorGlView>(1) }
    DisposableEffect(lifecycle, host) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    host.resume()?.let(callbacks.onError)
                    view[0]?.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    view[0]?.onPause()
                    host.pause()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            host.resume()?.let(callbacks.onError)
            view[0]?.onResume()
        }
        onDispose {
            lifecycle.removeObserver(observer)
            view[0]?.onPause()
            host.pause()
        }
    }
    AndroidView(
        factory = { context ->
            FloorGlView(context, host, callbacks).also { created ->
                view[0] = created
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) created.onResume()
            }
        },
        modifier = modifier
    )
}

private class FloorGlView(
    context: android.content.Context,
    host: FloorArHost,
    callbacks: FloorCameraCallbacks
) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(FloorRenderer(host, callbacks) { display?.rotation ?: Surface.ROTATION_0 })
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}

private class FloorRenderer(
    private val host: FloorArHost,
    private val callbacks: FloorCameraCallbacks,
    private val rotation: () -> Int
) : GLSurfaceView.Renderer {
    private val main = Handler(Looper.getMainLooper())
    private val quad: FloatBuffer = directFloats(QUAD)
    private val tex: FloatBuffer = directFloats(FloatArray(QUAD.size))
    private var program = 0
    private var textureId = 0
    private var aPosition = -1
    private var aTexCoord = -1
    private var width = 0
    private var height = 0
    private var lastPost = 0L
    private var drawFailed = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        textureId = newExternalTexture()
        host.textureReplaced(textureId)
        program = buildProgram()
        if (program == 0 && !drawFailed) {
            drawFailed = true
            main.post { callbacks.onError("The camera preview could not start.") }
        }
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aTexCoord = GLES20.glGetAttribLocation(program, "a_TexCoord")
        GLES20.glClearColor(0.04f, 0.05f, 0.04f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        GLES20.glViewport(0, 0, width, height)
        host.setViewport(rotation(), width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        host.setViewport(rotation(), width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val pose = host.withFrame { frame ->
            if (program != 0) drawCamera(frame)
        } ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPost < 100L) return
        lastPost = now
        main.post { callbacks.onPose(pose) }
    }

    private fun drawCamera(frame: Frame) {
        rewind(quad)
        rewind(tex)
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            quad,
            Coordinates2d.TEXTURE_NORMALIZED,
            tex
        )
        quad.position(0)
        tex.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "u_Texture"), 0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, tex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    private fun newExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun buildProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        if (vertex == 0 || fragment == 0) return 0
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        return if (linked[0] == GLES20.GL_TRUE) program else 0
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        return if (compiled[0] == GLES20.GL_TRUE) shader else 0
    }

    private fun rewind(buffer: FloatBuffer) {
        buffer.position(0)
        buffer.limit(buffer.capacity())
    }

    private fun directFloats(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    companion object {
        private val QUAD = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        private val VERTEX = """
            attribute vec2 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = vec4(a_Position, 0.0, 1.0);
                v_TexCoord = a_TexCoord;
            }
        """.trimIndent()
        private val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """.trimIndent()
    }
}
