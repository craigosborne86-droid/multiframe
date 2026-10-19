package dev.multiframe.camera.ui

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20 as GL
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import androidx.camera.core.SurfaceRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** The camera image and its peaking are one GPU draw from one latched texture. */
@Composable
internal fun PeakingPreview(
    size: Size,
    contentAspect: Float,
    displayRotationDegrees: Int,
    mirrored: Boolean,
    peaking: Boolean,
    zebra: Boolean = false,
    focusInset: FocusAssistInset? = null,
    onSurface: (Surface?) -> Unit,
    onHistogram: ((PreviewRgbHistogram) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val callback = rememberUpdatedState(onSurface)
    AndroidView(
        factory = { context -> PeakingPreviewView(context, size, contentAspect, displayRotationDegrees) { callback.value(it) } },
        modifier = modifier,
        update = {
            it.configure(mirrored, peaking, zebra)
            it.configureFocusInset(focusInset)
            it.histogramCallback = onHistogram
        },
        onRelease = { it.releaseCameraSurface() },
    )
}

/** CameraX's fallback also uses the frame-latched shader, so focus peaking is
 * available when a RAW ring cannot be opened on a particular lens/device. */
@Composable
internal fun CameraXPeakingPreview(
    request: SurfaceRequest,
    contentAspect: Float,
    displayRotationDegrees: Int,
    peaking: Boolean,
    zebra: Boolean,
    focusInset: FocusAssistInset? = null,
    onHistogram: ((PreviewRgbHistogram) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val provided = remember(request) { AtomicBoolean(false) }
    val disposed = remember(request) { AtomicBoolean(false) }
    val invalidated = remember(request) { AtomicBoolean(false) }
    DisposableEffect(request) {
        onDispose {
            disposed.set(true)
            if (!provided.get()) request.willNotProvideSurface()
        }
    }
    key(request) { AndroidView(
        factory = { ctx ->
            PeakingPreviewView(ctx, request.resolution, contentAspect, displayRotationDegrees) { surface ->
                if (surface != null && !disposed.get() && provided.compareAndSet(false, true)) {
                    request.provideSurface(surface, executor) { }
                } else if (surface == null && provided.get() && !disposed.get() && invalidated.compareAndSet(false, true)) {
                    // A recreated GL context needs a fresh SurfaceRequest.
                    request.invalidate()
                }
            }.also { view ->
                request.setTransformationInfoListener(executor) { info ->
                    val rotation = if (info.hasCameraTransform()) displayRotationDegrees
                        else info.rotationDegrees
                    view.configureTransform(rotation, info.cropRect, info.isMirroring)
                }
            }
        },
        modifier = modifier,
        update = {
            it.configureOverlays(peaking, zebra)
            it.configureFocusInset(focusInset)
            it.histogramCallback = onHistogram
        },
        onRelease = { it.releaseCameraSurface() },
    ) }
}

internal class PeakingPreviewView(
    context: Context,
    private val inputSize: Size,
    private val contentAspect: Float = inputSize.width.toFloat() / inputSize.height,
    displayRotationDegrees: Int = 0,
    private val surfaceChanged: (Surface?) -> Unit,
) : GLSurfaceView(context), GLSurfaceView.Renderer {
    @Volatile private var peaking = false
    internal val peakingEnabled: Boolean get() = peaking
    @Volatile private var zebra = false
    val zebraEnabled: Boolean get() = zebra
    @Volatile private var mirrored = false
    @Volatile private var displayRotationDegrees = displayRotationDegrees
    @Volatile private var bufferCrop = floatArrayOf(0f, 0f, 1f, 1f)
    @Volatile private var focusInset: FocusAssistInset? = null
    internal val focusInsetEnabled: Boolean get() = focusInset != null
    @Volatile internal var renderedTimestamp = 0L
        private set
    @Volatile internal var renderedFrames = 0L
        private set
    @Volatile var histogramCallback: ((PreviewRgbHistogram) -> Unit)? = null
    private var histogramFbo = 0
    private var histogramTexture = 0
    private var histogramAtNs = 0L
    private val histogramPixels = ByteBuffer.allocateDirect(96 * 72 * 4)
    private val histogramBins = Array(3) { IntArray(64) }
    private var histogramSamples = 0L
    private var histogramMaxMicros = 0L
    private val pending = AtomicBoolean(false)
    private var texture: SurfaceTexture? = null
    @Volatile private var cameraSurface: Surface? = null
    private var textureId = 0
    private var program = 0
    private var locations = emptyMap<String, Int>()
    private var positionAttribute = 0
    private var uvAttribute = 0
    private var hasFrame = false
    private val textureMatrix = FloatArray(16)
    private val orientation = FloatArray(16)
    private var cropX = 1f
    private var cropY = 1f
    private var viewWidth = 0
    private var viewHeight = 0
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun configure(mirrored: Boolean, peaking: Boolean, zebra: Boolean = false) {
        if (this.mirrored == mirrored && this.peaking == peaking && this.zebra == zebra) return
        this.mirrored = mirrored
        this.peaking = peaking
        this.zebra = zebra
        requestRender()
    }

    fun configureOverlays(peaking: Boolean, zebra: Boolean) = configure(mirrored, peaking, zebra)

    fun configureFocusInset(inset: FocusAssistInset?) {
        if (focusInset == inset) return
        focusInset = inset
        requestRender()
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        releaseInput()
        histogramFbo = 0; histogramTexture = 0
        program = link(VERTEX, FRAGMENT)
        locations = listOf("image", "textureMatrix", "orientation", "crop", "center", "bufferCrop", "texel", "enabled", "zebra", "peakColour")
            .associateWith { GL.glGetUniformLocation(program, it) }
        positionAttribute = GL.glGetAttribLocation(program, "position")
        uvAttribute = GL.glGetAttribLocation(program, "uv")
        val ids = IntArray(1)
        GL.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GL.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GL.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        val stream = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(inputSize.width, inputSize.height)
            setOnFrameAvailableListener {
                pending.set(true)
                requestRender()
            }
        }
        texture = stream
        val surface = Surface(stream)
        cameraSurface = surface
        post { if (cameraSurface === surface && isAttachedToWindow) surfaceChanged(surface) }
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GL.glViewport(0, 0, width, height)
        val fractions = centerCropFraction(contentAspect, width.toFloat() / height.coerceAtLeast(1))
        cropX = fractions.first
        cropY = fractions.second
    }

    override fun onDrawFrame(unused: GL10?) {
        val stream = texture ?: return
        if (pending.getAndSet(false)) {
            // updateTexImage drops superseded buffers rather than queueing old masks.
            stream.updateTexImage()
            stream.getTransformMatrix(textureMatrix)
            renderedTimestamp = stream.timestamp
            hasFrame = true
        }
        GL.glClearColor(0f, 0f, 0f, 1f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT)
        if (!hasFrame) return
        // SurfaceTexture's matrix brings the buffer into natural orientation.
        // Camera2 does not fold display rotation into it, so sample UVs must
        // include the inverse of the display's rotation. Portrait stays at 0.
        Matrix.setIdentityM(orientation, 0)
        Matrix.translateM(orientation, 0, .5f, .5f, 0f)
        Matrix.rotateM(orientation, 0, -displayRotationDegrees.toFloat(), 0f, 0f, 1f)
        if (mirrored) {
            Matrix.scaleM(orientation, 0, -1f, 1f, 1f)
        }
        Matrix.translateM(orientation, 0, -.5f, -.5f, 0f)
        GL.glUseProgram(program)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GL.glUniform1i(locations.getValue("image"), 0)
        GL.glUniformMatrix4fv(locations.getValue("textureMatrix"), 1, false, textureMatrix, 0)
        GL.glUniformMatrix4fv(locations.getValue("orientation"), 1, false, orientation, 0)
        GL.glUniform2f(locations.getValue("crop"), cropX, cropY)
        GL.glUniform2f(locations.getValue("center"), .5f, .5f)
        val valid = bufferCrop
        GL.glUniform4f(locations.getValue("bufferCrop"), valid[0], valid[1], valid[2], valid[3])
        GL.glUniform2f(locations.getValue("texel"), 1f / inputSize.width, 1f / inputSize.height)
        GL.glUniform1f(locations.getValue("enabled"), if (peaking) 1f else 0f)
        GL.glUniform1f(locations.getValue("zebra"), if (zebra) 1f else 0f)
        GL.glUniform3f(locations.getValue("peakColour"), TitaniumTokens.SignalCyan.red, TitaniumTokens.SignalCyan.green, TitaniumTokens.SignalCyan.blue)
        val position = positionAttribute
        val uv = uvAttribute
        vertices.position(0)
        GL.glVertexAttribPointer(position, 2, GL.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GL.glVertexAttribPointer(uv, 2, GL.GL_FLOAT, false, 16, vertices)
        GL.glEnableVertexAttribArray(position)
        GL.glEnableVertexAttribArray(uv)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        sampleHistogram()
        // Both views sample the same latched external texture in this draw.
        // Reusing the shader makes the inset's cyan marks exactly the main
        // peaking cue, at the same sensor timestamp and sharpness threshold.
        focusInset?.takeIf { peaking }?.let { inset ->
            val left = (inset.left * viewWidth).toInt().coerceIn(0, viewWidth)
            val top = (inset.top * viewHeight).toInt().coerceIn(0, viewHeight)
            val width = (inset.width * viewWidth).toInt().coerceIn(0, viewWidth - left)
            val height = (inset.height * viewHeight).toInt().coerceIn(0, viewHeight - top)
            if (width > 0 && height > 0) {
                // Preserve the displayed pixel scale on both axes. A 4:3
                // loupe over a portrait preview must not stretch a 3:4 crop.
                val insetCropX = cropX * width / viewWidth / FOCUS_ASSIST_ZOOM
                val insetCropY = cropY * height / viewHeight / FOCUS_ASSIST_ZOOM
                val centerX = (.5f + (inset.focusX - .5f) * cropX)
                    .coerceIn(.5f - (cropX - insetCropX) / 2f, .5f + (cropX - insetCropX) / 2f)
                // Compose targets count down from the top. The quad's UVs
                // count up from the bottom, before the shared camera transform.
                val centerY = (.5f + (.5f - inset.focusY) * cropY)
                    .coerceIn(.5f - (cropY - insetCropY) / 2f, .5f + (cropY - insetCropY) / 2f)
                val bottom = viewHeight - top - height
                GL.glEnable(GL.GL_SCISSOR_TEST)
                GL.glViewport(left, bottom, width, height)
                GL.glScissor(left, bottom, width, height)
                GL.glClear(GL.GL_COLOR_BUFFER_BIT)
                GL.glUniform2f(locations.getValue("crop"), insetCropX, insetCropY)
                GL.glUniform2f(locations.getValue("center"), centerX, centerY)
                GL.glUniform1f(locations.getValue("zebra"), 0f)
                GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
                GL.glDisable(GL.GL_SCISSOR_TEST)
                GL.glViewport(0, 0, viewWidth, viewHeight)
            }
        }
        renderedFrames++
    }

    fun configureTransform(rotationDegrees: Int, cropRect: Rect, mirror: Boolean) {
        displayRotationDegrees = rotationDegrees
        bufferCrop = floatArrayOf(
            cropRect.left.toFloat() / inputSize.width,
            cropRect.top.toFloat() / inputSize.height,
            cropRect.width().toFloat() / inputSize.width,
            cropRect.height().toFloat() / inputSize.height,
        )
        mirrored = mirror
        requestRender()
    }

    /** Read only 6,912 rendered-preview pixels, at most 4 Hz, on the GL thread.
     * The acquisition thread and full-resolution RAW buffers are untouched.
     * Zebra/peaking/loupe are excluded so the histogram describes the ISP image.
     */
    private fun sampleHistogram() {
        val callback = histogramCallback ?: return
        val now = System.nanoTime()
        if (now - histogramAtNs < 250_000_000L) return
        histogramAtNs = now
        if (histogramFbo == 0) {
            val ids = IntArray(1)
            GL.glGenTextures(1, ids, 0); histogramTexture = ids[0]
            GL.glBindTexture(GL.GL_TEXTURE_2D, histogramTexture)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
            GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, 96, 72, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
            GL.glGenFramebuffers(1, ids, 0); histogramFbo = ids[0]
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, histogramFbo)
            GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, histogramTexture, 0)
            if (GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) != GL.GL_FRAMEBUFFER_COMPLETE) {
                GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0); histogramCallback = null; return
            }
        }
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, histogramFbo)
        GL.glViewport(0, 0, 96, 72)
        GL.glUniform1f(locations.getValue("enabled"), 0f)
        GL.glUniform1f(locations.getValue("zebra"), 0f)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        histogramPixels.clear()
        GL.glReadPixels(0, 0, 96, 72, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, histogramPixels)
        histogramBins.forEach { it.fill(0) }
        histogramPixels.rewind()
        repeat(96 * 72) {
            repeat(3) { c -> histogramBins[c][(histogramPixels.get().toInt() and 255) / 4]++ }
            histogramPixels.get()
        }
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glViewport(0, 0, viewWidth, viewHeight)
        GL.glUniform1f(locations.getValue("enabled"), if (peaking) 1f else 0f)
        GL.glUniform1f(locations.getValue("zebra"), if (zebra) 1f else 0f)
        val micros = (System.nanoTime() - now) / 1000
        histogramSamples++; histogramMaxMicros = maxOf(histogramMaxMicros, micros)
        if (histogramSamples % 20L == 0L) android.util.Log.i("PreviewRGB", "samples=$histogramSamples maxReadbackUs=$histogramMaxMicros frame=$renderedTimestamp")
        callback(PreviewRgbHistogram(histogramBins.map { it.copyOf() }, renderedTimestamp, micros))
    }

    fun releaseCameraSurface() {
        surfaceChanged(null)
        queueEvent {
            releaseInput()
            if (program != 0) GL.glDeleteProgram(program)
            if (textureId != 0) GL.glDeleteTextures(1, intArrayOf(textureId), 0)
            program = 0
            textureId = 0
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseCameraSurface()
        super.surfaceDestroyed(holder)
    }

    private fun releaseInput() {
        if (histogramFbo != 0) GL.glDeleteFramebuffers(1, intArrayOf(histogramFbo), 0)
        if (histogramTexture != 0) GL.glDeleteTextures(1, intArrayOf(histogramTexture), 0)
        histogramFbo = 0; histogramTexture = 0

        texture?.setOnFrameAvailableListener(null)
        cameraSurface?.release()
        cameraSurface = null
        texture?.release()
        texture = null
        pending.set(false)
        hasFrame = false
    }

    private fun link(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GL.glCreateShader(type)
            GL.glShaderSource(shader, source)
            GL.glCompileShader(shader)
            val ok = IntArray(1)
            GL.glGetShaderiv(shader, GL.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GL.glGetShaderInfoLog(shader) }
            return shader
        }
        val v = compile(GL.GL_VERTEX_SHADER, vertex)
        val f = compile(GL.GL_FRAGMENT_SHADER, fragment)
        val result = GL.glCreateProgram()
        GL.glAttachShader(result, v)
        GL.glAttachShader(result, f)
        GL.glLinkProgram(result)
        val ok = IntArray(1)
        GL.glGetProgramiv(result, GL.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { GL.glGetProgramInfoLog(result) }
        GL.glDeleteShader(v)
        GL.glDeleteShader(f)
        return result
    }

    companion object {
        private const val FOCUS_ASSIST_ZOOM = 3f
        private const val VERTEX = """
            attribute vec2 position;
            attribute vec2 uv;
            uniform mat4 textureMatrix;
            uniform mat4 orientation;
            uniform vec2 crop;
            uniform vec2 center;
            uniform vec4 bufferCrop;
            varying vec2 sampleUv;
            void main() {
                gl_Position = vec4(position, 0.0, 1.0);
                vec2 framedUv = (uv - vec2(0.5)) * crop + center;
                vec2 orientedUv = (orientation * vec4(framedUv, 0.0, 1.0)).xy;
                sampleUv = (textureMatrix * vec4(bufferCrop.xy + orientedUv * bufferCrop.zw, 0.0, 1.0)).xy;
            }
        """
        // Contrast alone also marks defocused boundaries. Compare the narrow
        // slope to the wider slope, require curvature and reject dark/noisy
        // responses. No dilation, contour closure, temporal accumulation or
        // separately scheduled mask. This is a sharpness cue, not a depth map.
        internal const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES image;
            uniform vec2 texel;
            uniform float enabled;
            uniform float zebra;
            uniform vec3 peakColour;
            varying vec2 sampleUv;
            float luma(vec2 p) { return dot(texture2D(image, p).rgb, vec3(.2126, .7152, .0722)); }
            void main() {
                vec4 base = texture2D(image, sampleUv);
                // Evaluate sharpness at source texel centres. At 3× display
                // magnification, sub-texel samples can land exactly halfway
                // across an edge: the curvature test then reads zero even
                // though the source edge is sharp. Both views use this mask.
                vec2 peakUv = (floor(sampleUv / texel) + vec2(0.5)) * texel;
                float strength = 0.0;
                if (enabled > .5 && all(greaterThan(peakUv, texel * 4.0)) && all(lessThan(peakUv, vec2(1.0) - texel * 4.0))) {
                    vec2 x = vec2(texel.x, 0.0), y = vec2(0.0, texel.y);
                    float c = luma(peakUv);
                    float l = luma(peakUv-x), r = luma(peakUv+x);
                    float t = luma(peakUv-y), b = luma(peakUv+y);
                    float tl = luma(peakUv-x-y), tr = luma(peakUv+x-y);
                    float bl = luma(peakUv-x+y), br = luma(peakUv+x+y);
                    // Average along the edge to suppress isolated hot/noisy pixels.
                    vec2 fine = abs(vec2((tr-tl)+2.0*(r-l)+(br-bl), (bl-tl)+2.0*(b-t)+(br-tr))) * .25;
                    vec2 broad = abs(vec2(luma(peakUv+3.0*x)-luma(peakUv-3.0*x), luma(peakUv+3.0*y)-luma(peakUv-3.0*y)));
                    float f = max(fine.x, fine.y);
                    float w = fine.x > fine.y ? broad.x : broad.y;
                    float curvature = max(abs(l+r-2.0*c), abs(t+b-2.0*c));
                    float mean = (l+r+t+b+4.0*c) * .125;
                    float compactness = f / max(w, .04);
                    strength = smoothstep(.72, .95, compactness)
                        * smoothstep(.045, .12, f)
                        * smoothstep(.035, .10, curvature)
                        * smoothstep(.035, .10, mean);
                }
                vec3 result = mix(base.rgb, peakColour, .28 * strength);
                if (zebra > .5 && max(max(base.r, base.g), base.b) > .97) {
                    float stripe = step(.5, fract((gl_FragCoord.x + gl_FragCoord.y) / 6.0));
                    // Screen blend: 1 - (1 - base) * (1 - stripe).
                    result = 1.0 - (1.0 - result) * (1.0 - vec3(stripe * .48));
                }
                gl_FragColor = vec4(result, 1.0);
            }
        """
    }
}
