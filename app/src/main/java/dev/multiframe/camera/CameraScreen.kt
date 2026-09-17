package dev.multiframe.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CaptureRequest as Camera2Request
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import android.app.ActivityManager
import android.view.Surface
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.AndroidExternalSurfaceZOrder
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.multiframe.camera.pipeline.BurstBuffer
import dev.multiframe.camera.pipeline.AppSettings
import dev.multiframe.camera.pipeline.AppSettings.Companion.reconcile
import dev.multiframe.camera.pipeline.CameraCapabilities
import dev.multiframe.camera.pipeline.CaptureMode
import dev.multiframe.camera.pipeline.CaptureReadout
import dev.multiframe.camera.pipeline.CaptureModes
import dev.multiframe.camera.pipeline.ColorProfile
import dev.multiframe.camera.pipeline.ColorScience
import dev.multiframe.camera.pipeline.HeifEncoder
import dev.multiframe.camera.pipeline.OutputFormat
import dev.multiframe.camera.pipeline.SceneAnalysis
import dev.multiframe.camera.pipeline.FocusPeaking
import dev.multiframe.camera.pipeline.Plane
import dev.multiframe.camera.pipeline.Lens
import dev.multiframe.camera.pipeline.LensCatalog
import dev.multiframe.camera.pipeline.LensProfile
import dev.multiframe.camera.pipeline.LensProfiles
import dev.multiframe.camera.pipeline.MemoryPressure
import dev.multiframe.camera.pipeline.NativeMerge
import dev.multiframe.camera.pipeline.PressureResponse
import dev.multiframe.camera.pipeline.DcgCalibrationStore
import dev.multiframe.camera.pipeline.DcgProfiler
import dev.multiframe.camera.pipeline.DevelopParams
import dev.multiframe.camera.pipeline.MosaicGuide
import dev.multiframe.camera.pipeline.MotionSensor
import dev.multiframe.camera.pipeline.MosaicGuideView
import dev.multiframe.camera.pipeline.MosaicCapture
import dev.multiframe.camera.pipeline.MosaicPlanner
import dev.multiframe.camera.pipeline.MosaicSession
import dev.multiframe.camera.pipeline.MosaicEvent
import dev.multiframe.camera.pipeline.MosaicPreset
import dev.multiframe.camera.pipeline.ImageSaver
import dev.multiframe.camera.pipeline.ManualSettings
import dev.multiframe.camera.pipeline.MemoryBudget
import dev.multiframe.camera.pipeline.RawBurstCapture
import dev.multiframe.camera.pipeline.RawRingBudget
import dev.multiframe.camera.pipeline.RecentCapture
import dev.multiframe.camera.pipeline.RecentShot
import dev.multiframe.camera.pipeline.ZslCapture
import dev.multiframe.camera.pipeline.ZslDecision
import dev.multiframe.camera.pipeline.ZslPolicy
import dev.multiframe.camera.pipeline.ZslRawStream
import dev.multiframe.camera.pipeline.Merger
import dev.multiframe.camera.pipeline.OrientationTracker
import dev.multiframe.camera.pipeline.Attitude
import dev.multiframe.camera.pipeline.LevelSensor
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Brush
import dev.multiframe.camera.ui.Ink
import dev.multiframe.camera.ui.Type
import dev.multiframe.camera.ui.Haptics
import dev.multiframe.camera.ui.GaugeState
import dev.multiframe.camera.ui.ExposureGauge
import dev.multiframe.camera.ui.HistogramGauge
import dev.multiframe.camera.ui.FocusGauge
import dev.multiframe.camera.ui.ZoomGauge
import dev.multiframe.camera.ui.AboutSheet
import dev.multiframe.camera.ui.GalleryScreen
import dev.multiframe.camera.ui.GuideMode
import dev.multiframe.camera.ui.Guides
import dev.multiframe.camera.ui.Peaking
import dev.multiframe.camera.ui.MosaicPresetPill
import dev.multiframe.camera.ui.MosaicGuidance
import dev.multiframe.camera.ui.ManualPanel
import dev.multiframe.camera.ui.SetOncePanel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import dev.multiframe.camera.ui.ActionStrip
import dev.multiframe.camera.ui.BackNavigation
import dev.multiframe.camera.ui.CameraLayout
import dev.multiframe.camera.ui.LocalHandRotation
import dev.multiframe.camera.ui.Posture
import dev.multiframe.camera.ui.fitAspect
import dev.multiframe.camera.ui.rotateLayout
import dev.multiframe.camera.ui.UprightInHand
import dev.multiframe.camera.ui.rememberCameraLayout
import dev.multiframe.camera.ui.ChromeOpeners
import dev.multiframe.camera.ui.ResolutionPill
import dev.multiframe.camera.ui.ControlBar
import dev.multiframe.camera.ui.ControlBarState
import dev.multiframe.camera.ui.ModeCarousel
import dev.multiframe.camera.ui.SettingsScreen
import dev.multiframe.camera.pipeline.SuperResMachine
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

private const val TAG = "Multiframe"
/** Frames requested per shutter press; the ring shrinks this if the heap cannot hold it. */
private const val DESIRED_BURST = 12

/** How long a refused or released raw ring waits before it is tried again. */
private const val RING_RETRY_MILLIS = 30_000L

/** Side of the focus reticle, in pixels. */
private const val RETICLE_PX = 180f

/**
 * Size of the luma view peaking runs on.
 *
 * Small on purpose: peaking a twelve-megapixel frame several times a second
 * would cost more than the rest of the viewfinder together, and edges survive
 * downscaling perfectly well.
 */
private const val PEAK_W = 320
private const val PEAK_H = 240

// Both opt-ins are needed and they are not the same thing: Kotlin's satisfies
// the compiler, and AndroidX's own is what its lint check looks for. With only
// the first, `lintDebug` reported every interop call in this function as an
// UnsafeOptInUsageError.
@OptIn(ExperimentalCamera2Interop::class, ExperimentalLayoutApi::class)
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    // The view the haptics are performed against. See `Haptics` for the
    // vocabulary and for why nothing fires during a burst.
    val view = LocalView.current

    // Restored before any state that depends on it, so the first composition
    // already shows what the user left the app in rather than the defaults.
    val restored = remember { AppSettings.load(context) }

    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var status by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showPro by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var showHistogram by remember { mutableStateOf(restored.histogram) }
    var rawCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var characteristics by remember { mutableStateOf<CameraCharacteristics?>(null) }
    // DngCreator needs the capture metadata that produced the frame, which
    // CameraX does not surface directly.
    val lastCaptureResult = remember { AtomicReference<TotalCaptureResult?>(null) }
    var burstFrames by remember { mutableIntStateOf(restored.burstFrames) }
    // Busy is the sequential camera's and the self-timer's: the shutter is
    // theirs until they are done. A ZSL shot is never busy -- its frames
    // are locked at the press and developed behind the viewfinder, see
    // `developing` -- so the next press is taken as soon as it comes.
    var busy by remember { mutableStateOf(false) }
    // Shots taken and not yet written: the number the develop queue holds.
    var developing by remember { mutableIntStateOf(0) }
    /** The read-noise profile in progress, for the shutter to cancel; null otherwise. */
    var profileJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    /** A press waiting for the ring to refill before it locks its frames. One at a time. */
    var lockPending by remember { mutableStateOf(false) }
    // Bumped as each shot is written, for the thumbnail to reload on.
    var shotsWritten by remember { mutableIntStateOf(0) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var caps by remember { mutableStateOf<CameraCapabilities?>(null) }

    var settings by remember { mutableStateOf(restored.manual) }

    // Zero-shutter-lag raw streaming. The decision is taken from what the
    // hardware reports, not from what this device happens to do, so a camera
    // that stalls on raw simply never offers the mode.
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraId by remember { mutableStateOf<String?>(null) }
    var zslDecision by remember { mutableStateOf<ZslDecision?>(null) }
    // The ring is the engine, not a setting: wanted from the first frame, on
    // every mode, and false only while the hardware or the memory has refused
    // it -- in which case the sequential path is the camera until it is tried
    // again. There is no switch for it anywhere a user can reach.
    var zslWanted by remember { mutableStateOf(true) }
    var zslSurface by remember { mutableStateOf<Surface?>(null) }
    var zslStream by remember { mutableStateOf<ZslRawStream?>(null) }

    // Every physical lens the device has, not a zoom ratio on one of them. A
    // raw pipeline needs to know which sensor produced a frame, because black
    // level, colour filter arrangement and calibration are all per lens.
    var lenses by remember { mutableStateOf<List<Lens>>(emptyList()) }
    // Optical characterisation per physical camera, keyed by camera id. Empty
    // until the catalogue has been enumerated; a lens with no entry is one the
    // device would not describe, which every consumer has to survive.
    var lensProfiles by remember { mutableStateOf<Map<String, LensProfile>>(emptyMap()) }
    var lens by remember { mutableStateOf<Lens?>(null) }

    // Expose for the highlights and let the merge pay for the shadows. On by
    // default: it is the whole reason for capturing a burst, and it costs
    // nothing on a scene that does not need it.
    var highlightGuard by remember { mutableStateOf(restored.highlightGuard) }
    var guardPull by remember { mutableFloatStateOf(0f) }
    var dngEnabled by remember { mutableStateOf(restored.dngEnabled) }
    var localTone by remember { mutableStateOf(restored.localTone) }
    var outputFormat by remember { mutableStateOf(restored.outputFormat) }
    // The sensor's un-binned mode, on a sensor that has one. See the chip.
    var fullResolution by remember { mutableStateOf(restored.fullResolution) }

    // What the user is photographing. The right settings for a night scene and
    // a moving subject are opposites, and only the user knows which it is.
    var captureMode by remember { mutableStateOf(restored.captureMode) }
    var scene by remember { mutableStateOf<SceneAnalysis?>(null) }

    // Self-timer, and the last shot taken.
    var timerSeconds by remember { mutableIntStateOf(restored.timerSeconds) }
    var countdown by remember { mutableIntStateOf(0) }
    // Held so the self-timer can be called off. Only the countdown is
    // cancellable: once frames are captured the work is a few long native
    // calls with nowhere to suspend, and a control that appears to do nothing
    // for several seconds is worse than not offering one.
    var captureJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var lastShot by remember { mutableStateOf<RecentShot?>(null) }
    var thumbnail by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    // Tap to focus and pinch to zoom. Table stakes for a camera: without them
    // the app cannot be pointed at a subject that is not in the middle.
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // Where the picture actually is, which is no longer the whole window.
    // The preview is letterboxed to the sensor's aspect, so a tap has to be
    // normalised against this rectangle rather than against the screen.
    var previewBounds by remember { mutableStateOf<Rect?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusAtMillis by remember { mutableLongStateOf(0L) }
    var digitalZoom by remember { mutableFloatStateOf(1f) }

    // Super Res: a grid of telephoto tiles, each held still, composed into
    // one picture. Its life is `SuperResMachine`'s: selected in the carousel
    // it is ARMED -- the capture lens is up, the outline of the picture and
    // its nodes are on the viewfinder, the 2x2/3x3 choice is above the
    // shutter, nothing has been taken; the shutter makes it RUNNING, and STOP
    // or the back gesture STOPPING until the run lands. The run is a job,
    // not an effect: see `startSuperRes`.
    var superResPhase by remember { mutableStateOf(SuperResMachine.Phase.IDLE) }
    var superResJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var mosaicView by remember { mutableStateOf<MosaicGuideView?>(null) }
    // The lens the user was on when they chose the mode: the framing the
    // mosaic records when it is wider than the capture lens, and the lens to
    // return to on leaving.
    var mosaicReturnLens by remember { mutableStateOf<Lens?>(null) }
    var mosaicPreset by remember {
        // The drawn-region preset is not offered on the pill; a saved one
        // falls back to the smaller grid.
        mutableStateOf(restored.mosaicPreset.takeIf { it != MosaicPreset.CUSTOM } ?: MosaicPreset.DETAIL_BOOST)
    }
    // A failed capture flashes its dot for a beat; this is the beat.
    var mosaicFlashUntil by remember { mutableLongStateOf(0L) }
    // Until when a finished capture's summary owns the status line: the
    // return to ARMED would otherwise overwrite "complete: 9 of 9 tiles" with
    // "aim, then press the shutter" on the next frame, and the result would
    // never be read.
    var superResSummaryUntil by remember { mutableLongStateOf(0L) }

    // Composition aids. One control cycles them rather than several toggles.
    var guides by remember {
        mutableStateOf(
            GuideMode.entries.firstOrNull { it.name == restored.guides } ?: GuideMode.OFF
        )
    }
    val level = remember { LevelSensor(context) }
    var attitude by remember { mutableStateOf<Attitude?>(null) }

    // Every live reading, held in one object that the screen writes and only
    // the gauges read. See `GaugeState`: at 120Hz, hoisting these here meant
    // five whole-tree recompositions a second to change two digits.
    val gauges = remember { GaugeState() }

    // What the sensor actually used, as opposed to what was asked for. In
    // auto-exposure those are different numbers and only the first is worth
    // showing, so it is read off the repeating request rather than inferred
    // from `settings`.
    //
    // Written from a camera callback at frame rate and sampled at 5Hz: driving
    // Compose state directly from that callback would recompose the whole
    // screen sixty times a second to change two digits.
    val liveExposure = remember { AtomicReference(0 to 0L) }

    LaunchedEffect(zslStream, characteristics) {
        val stream = zslStream
        // The sensor's own colour characterisation, read once. Without it a
        // temperature cannot be computed at all -- the gains are camera-space
        // and mean nothing until this takes them to XYZ.
        val calibration = characteristics?.let {
            runCatching { ColorProfile.calibrationFrom(it) }.getOrNull()
        }
        while (true) {
            // Whichever path is actually driving the sensor.
            //
            // CameraX reports through the preview's repeating request, and the
            // raw ring keeps results of its own -- and because the ring can
            // only run with CameraX unbound, the preview callback goes quiet
            // at exactly the moment the ring takes over. Reading the stream
            // first rather than falling back to a value that stopped being
            // true when the handover happened.
            val reading = stream?.latestExposure() ?: liveExposure.get()
            if (reading.first > 0 && reading.second > 0L) {
                gauges.setExposure(reading.first, reading.second)
            } else {
                gauges.clearExposure()
            }

            // Temperature comes from the gains the camera settled on, not from
            // the AWB mode the user picked: under AUTO those are different
            // answers and only the first is a reading.
            if (calibration != null) {
                val gains = stream?.latestAwbGains()
                val kelvin = gains?.let { ColorScience.cctFromGains(calibration, it) }
                gauges.setColourTemperature(kelvin ?: 0)
            }

            kotlinx.coroutines.delay(200)
        }
    }

    // Focus peaking, shown only while manual focus is engaged, since that is
    // the only time it answers a question the user is asking.
    var peakingMask by remember { mutableStateOf<ByteArray?>(null) }
    var focusScore by remember { mutableFloatStateOf(0f) }
    // The surface the running stream was built against. A SurfaceView is
    // recreated when its fixed size is applied, so "a surface exists" is not
    // the same question as "the session is targeting the live one".
    var zslOpenSurface by remember { mutableStateOf<Surface?>(null) }
    // Opening is slow and must not overlap itself: the effect relaunches when
    // the surface is replaced, and two concurrent opens would leave a second
    // camera session and a second pool behind the first.
    val zslLock = remember { Mutex() }

    val buffer = remember { BurstBuffer(DESIRED_BURST) }
    // Analysis resolution is chosen from the heap this process was actually
    // granted, not assumed from the development device.
    val analysisSize = remember {
        MemoryBudget.recommendedAnalysisSize(Runtime.getRuntime().maxMemory())
    }
    val orientation = remember { OrientationTracker(context) }
    // The phone's quarter turn, as state, for the layout that turns the
    // controls' contents with the hand (ui/WindowLayout.kt).
    var deviceDegrees by remember { mutableIntStateOf(0) }
    val layout = rememberCameraLayout(deviceDegrees)

    DisposableEffect(Unit) {
        orientation.onQuarterTurn = { deviceDegrees = it }
        orientation.enable()
        onDispose {
            orientation.onQuarterTurn = null
            orientation.disable()
        }
    }

    // The last capture, refreshed whenever one completes -- and whenever the
    // gallery closes, because what it pointed at may have been deleted there.
    LaunchedEffect(busy, showGallery, shotsWritten) {
        if (busy || showGallery) return@LaunchedEffect
        val shot = withContext(Dispatchers.IO) { RecentCapture.latest(context) }
        lastShot = shot
        thumbnail = if (shot == null) null else withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(
                    shot.uri, android.util.Size(160, 160), null,
                )
            }.onFailure { Log.w(TAG, "could not load the last shot", it) }.getOrNull()
        }
    }

    // What the chosen mode implies for this scene, recomputed as either moves.
    val capturePlan = remember(captureMode, scene, zslStream, burstFrames) {
        val stream = zslStream
        if (stream == null) null
        else CaptureModes.plan(
            mode = captureMode,
            analysis = scene ?: SceneAnalysis(0f, 0.5f, 0.1f, 3f),
            availableFrames = stream.maxBurst,
            requestedFrames = burstFrames,
        )
    }

    // The develop queue: every ZSL shot, in the order taken, merged and
    // written one at a time on a worker while the viewfinder runs on. One
    // at a time because the merge already spans every core; two at once
    // would only contend. A shot in hand is a photograph owed, so a develop
    // is not cancelled once begun.
    val developQueue = remember { Channel<PendingShot>(Channel.UNLIMITED) }
    LaunchedEffect(Unit) {
        for (pending in developQueue) {
            val r = try {
                withContext(Dispatchers.Default + NonCancellable) {
                    ZslCapture.develop(
                        context, pending.stream, pending.shot, pending.rotation,
                        lensProfile = pending.lensProfile,
                        outputFormat = pending.outputFormat,
                        superRes = pending.canvas2x,
                        develop = pending.develop,
                        writeDng = pending.writeDng,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The screen is leaving; the shot in hand was finished under
                // NonCancellable, and what remains queued is closed below.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "develop failed", e)
                null
            }
            developing = (developing - 1).coerceAtLeast(0)
            if (r != null) {
                Log.i(TAG, "ZSL result: $r")
                r.streamStats?.let { Log.i(TAG, "stream health: $it") }
                Log.i(
                    TAG,
                    ("handover %.2fms  span %dms  merge %dms  " +
                        "develop %dms  write %dms").format(
                        r.handoverMicros / 1000.0, r.burstSpanMillis,
                        r.mergeMillis, r.developMillis, r.writeMillis,
                    ),
                )
                val readout = r.stats?.let {
                    CaptureReadout.of(
                        it.framesMerged, it.meanContribution,
                        dcgActive = r.dcgActive,
                        superRes = r.superRes,
                    )
                } ?: r.message
                // A 2x that declined says why, in the words the develop
                // used: the photograph was saved at the sensor's size, and
                // the photographer should not have to guess which.
                val decline = CaptureReadout.superResDecline(r.superRes)
                    ?.let { " · saved at 1x: $it" } ?: ""
                status = (if (developing > 0) "$readout · $developing developing" else readout) + decline
                shotsWritten++
            }
        }
    }
    DisposableEffect(developQueue) {
        onDispose {
            // Whatever is still queued gives its frames back; the mapping
            // they held is freed with the last of them.
            developQueue.close()
            while (true) {
                val pending = developQueue.tryReceive().getOrNull() ?: break
                pending.shot.close()
            }
        }
    }

    // The ring is the largest allocation in the app by a wide margin, and
    // Android does not warn twice about holding it: it kills the process. Giving
    // it back voluntarily degrades to sequential capture, which still takes
    // photographs, and is reversible the moment pressure lifts.
    DisposableEffect(context) {
        val callbacks = object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                when (MemoryPressure.responseTo(level)) {
                    PressureResponse.NONE -> Unit
                    PressureResponse.RELEASE_RING,
                    PressureResponse.RELEASE_ALL -> {
                        // The develop scratch is held between captures so its
                        // pages are faulted once rather than per shot. It is
                        // not worth holding while the system is struggling.
                        NativeMerge.releaseScratch()
                        if (zslWanted) {
                            // Said in the log, not on the viewfinder: what the
                            // user sees is a camera that keeps working, and it
                            // tries the ring again shortly.
                            Log.w(TAG, "memory pressure $level: releasing the raw ring")
                            zslWanted = false
                        }
                    }
                }
            }

            override fun onConfigurationChanged(config: android.content.res.Configuration) = Unit

            @Deprecated("required by the interface")
            override fun onLowMemory() {
                onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            }
        }
        context.registerComponentCallbacks(callbacks)
        onDispose { context.unregisterComponentCallbacks(callbacks) }
    }

    // The accelerometer only runs while the level is being shown; leaving it
    // registered would drain the battery for a display nobody asked for.
    DisposableEffect(guides) {
        if (guides.showsLevel) level.enable()
        onDispose { level.disable() }
    }

    LaunchedEffect(guides) {
        if (!guides.showsLevel) {
            attitude = null
            return@LaunchedEffect
        }
        while (true) {
            attitude = level.attitude
            kotlinx.coroutines.delay(60)
        }
    }

    // Focus peaking, from the sensor rather than the preview: the preview has
    // been through the ISP's sharpening and would show edges the optics never
    // produced.
    LaunchedEffect(zslStream, settings.manualFocus) {
        val stream = zslStream
        if (stream == null || !settings.manualFocus) {
            peakingMask = null
            gauges.setFocusScore(0f)
            return@LaunchedEffect
        }
        val luma = ByteArray(PEAK_W * PEAK_H)
        val mask = ByteArray(PEAK_W * PEAK_H)
        while (true) {
            val ready = withContext(Dispatchers.Default) {
                if (!stream.luma(luma, PEAK_W, PEAK_H)) return@withContext false
                val plane = Plane(PEAK_W, PEAK_H, luma)
                FocusPeaking.detect(plane, mask)
                gauges.setFocusScore(FocusPeaking.focusScore(plane))
                true
            }
            peakingMask = if (ready) mask.copyOf() else null
            kotlinx.coroutines.delay(120)
        }
    }

    // Live histogram, read in place from the ring so it costs no frames.
    // Gated on the raw stream only. It used to be gated on the guides as
    // well, which meant the one instrument that tells you whether the
    // highlights are gone was off unless you had also asked for a grid --
    // two unrelated things behind one control.
    LaunchedEffect(zslStream) {
        val stream = zslStream
        if (stream == null) {
            gauges.setHistogram(null)
            return@LaunchedEffect
        }
        val bins = IntArray(48)
        while (true) {
            val ok = withContext(Dispatchers.Default) { stream.histogram(bins) }
            gauges.setHistogram(if (ok) bins.copyOf() else null)
            kotlinx.coroutines.delay(250)
        }
    }

    LaunchedEffect(lifecycleOwner, zslWanted) {
        // The camera admits one client. Engaging the raw ring means handing
        // the device over to the Camera2 session, so CameraX is unbound first.
        //
        // **Unless nothing has discovered the camera yet.** Everything this
        // screen knows -- the capability set, the lens catalogue, whether a
        // zero-stall raw stream exists at all -- is read below, after CameraX
        // has bound. Handing the device away before that has happened is a
        // deadlock: the raw ring cannot open without `caps`, `lens` and
        // `cameraId`, and only this path ever sets them.
        //
        // It is reachable from every cold start, because the ring is always
        // wanted -- and before it was always wanted it was a setting that
        // defaulted on, and a fresh install hit exactly this: a black
        // viewfinder, no lens strip and no way out, since the control that
        // would have switched the ring off was itself gated on capabilities
        // that were never read.
        //
        // So the first pass always binds, discovers, and hands the camera over
        // at the end. It costs one bind and unbind on a launch that wanted the
        // ring, which is a few hundred milliseconds of a path that was
        // previously infinite.
        if (zslWanted && caps != null) {
            runCatching { provider?.unbindAll() }
            surfaceRequest = null
            camera = null
            return@LaunchedEffect
        }

        // Handing the camera back the other way. Both effects can close the
        // stream, but only this ordering is guaranteed: CameraX cannot open a
        // device the Camera2 session still holds, and effect launch order is
        // not a strong enough guarantee to rely on.
        zslStream?.close()
        zslStream = null

        val preview = Preview.Builder().also { builder ->
            // The repeating request reports what the sensor settled on for
            // every frame, which is the only honest source for the readout
            // while auto-exposure is running. Wrapped because a device that
            // rejects the interop extension must still get a viewfinder.
            runCatching {
                Camera2Interop.Extender(builder).setSessionCaptureCallback(
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: Camera2Request,
                            result: TotalCaptureResult,
                        ) {
                            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                            val ns = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                            if (iso != null && ns != null) liveExposure.set(iso to ns)
                        }
                    }
                )
            }.onFailure { Log.w(TAG, "no live exposure readout on this device", it) }
        }.build().apply {
            setSurfaceProvider { request -> surfaceRequest = request }
        }

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(analysisSize.width, analysisSize.height),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .apply {
                setAnalyzer(ContextCompat.getMainExecutor(context)) { image ->
                    try {
                        buffer.offer(image)
                    } finally {
                        image.close()
                    }
                }
            }

        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context)
                .await(ContextCompat.getMainExecutor(context))
            provider = cameraProvider

            // Not every Android device has a rear camera; some tablets and
            // desktop-class devices only expose a front one.
            val selector = listOf(
                CameraSelector.DEFAULT_BACK_CAMERA,
                CameraSelector.DEFAULT_FRONT_CAMERA,
            ).firstOrNull { runCatching { cameraProvider.hasCamera(it) }.getOrDefault(false) }

            if (selector == null) {
                status = "No camera available on this device"
                return@LaunchedEffect
            }

            cameraProvider.unbindAll()

            // RAW+JPEG adds a third stream, which not every camera can run
            // alongside preview and analysis. Try it, and fall back rather than
            // losing the camera entirely.
            val dngCapture = runCatching {
                val b = ImageCapture.Builder()
                    .setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                Camera2Interop.Extender(b).setSessionCaptureCallback(
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: Camera2Request,
                            result: TotalCaptureResult,
                        ) {
                            lastCaptureResult.set(result)
                        }
                    }
                )
                b.build()
            }.getOrNull()

            val bound = runCatching {
                requireNotNull(dngCapture)
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, selector, preview, analysis, dngCapture,
                ).also { rawCapture = dngCapture }
            }.getOrElse { e ->
                Log.w(TAG, "RAW+JPEG stream unavailable, continuing without it", e)
                rawCapture = null
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            }
            camera = bound

            val capabilities = CameraCapabilities.from(bound.cameraInfo)
            caps = capabilities
            Log.i(TAG, "Capabilities: ${capabilities.summary()}")
            Log.i(
                TAG,
                "Heap max ${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB, " +
                    "analysis ${analysisSize.width}x${analysisSize.height}, " +
                    "outputFormats=${capabilities.supportedOutputFormats}, " +
                    "dng=${capabilities.supportsDng}, rawStream=${rawCapture != null}",
            )
            if (!capabilities.supportsDng) rawCapture = null

            // What raw actually costs to stream decides whether a continuous
            // zero-shutter-lag ring is feasible or a fantasy. A non-zero stall
            // duration means a raw frame blocks the other streams, so the ring
            // is never offered and the sequential path stays in charge.
            runCatching {
                val id = Camera2CameraInfo.from(bound.cameraInfo).cameraId
                val ch = context.getSystemService(CameraManager::class.java)
                    .getCameraCharacteristics(id)
                cameraId = id
                characteristics = ch

                val catalog = LensCatalog.enumerate(
                    context.getSystemService(CameraManager::class.java)
                )
                lenses = catalog
                if (lens == null) {
                    // A stored lens can name hardware this device does not
                    // have, so it is checked against the catalog rather than
                    // trusted.
                    lens = restored.lensId?.let { id ->
                        catalog.firstOrNull { it.cameraId == id }
                    } ?: LensCatalog.default(catalog)
                }
                Log.i(TAG, "lenses: " + LensCatalog.rear(catalog).joinToString {
                    "${it.label}/${it.zoomLabel}"
                })

                // The optical characterisation, which is what the distortion
                // correction and the mosaic stitching will need and what the
                // DCG bracket asks about its sensitivity range. Probed off the
                // catalogue so the same optic is not characterised three times,
                // and cheap enough to do inline: it is metadata reads, no
                // capture and no allocation of consequence.
                lensProfiles = LensProfiles.probe(
                    context.getSystemService(CameraManager::class.java), catalog,
                )

                val configs = ZslRawStream.rawConfigs(ch)
                configs.forEach { Log.i(TAG, "RAW stream $it") }
                val decision = ZslPolicy.evaluate(capabilities.supportsRaw, configs)
                zslDecision = decision
                Log.i(TAG, "ZSL decision: ${decision.describe}")
            }.onFailure {
                Log.w(TAG, "raw stream probe failed", it)
                zslDecision = ZslDecision.Fallback("raw capabilities unreadable")
            }

            // Anything restored has to be checked against this camera: a
            // setting the hardware cannot honour has the whole capture request
            // rejected, taking every other setting with it.
            settings = restored.copy(manual = settings)
                .reconcile(capabilities, lenses)
                .manual

            // Start about a stop under, so highlights stay off the clip point.
            if (restored.manual == ManualSettings() && capabilities.supportsExposureCompensation) {
                val index = (-1.0f / capabilities.evStep).roundToInt()
                    .coerceIn(capabilities.evMin, capabilities.evMax)
                settings = settings.copy(evIndex = index)
            }

            // Discovery is done, so the camera can go where it was always
            // meant to go. This is the tail of the first pass described at the
            // top of this effect: the bind existed only to find out what the
            // hardware is, and the ring is what was actually asked for.
            if (zslWanted) {
                Log.i(TAG, "discovery complete, handing the camera to the raw ring")
                runCatching { cameraProvider.unbindAll() }
                surfaceRequest = null
                camera = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            status = "Camera unavailable: ${e.message}"
        }
    }

    // One effect owns the stream's whole life. Opening needs three things to
    // line up -- the user asking, the hardware allowing, and a preview surface
    // to render into -- and the surface arrives asynchronously, so anything
    // going away has to tear the stream down again through the same path.
    LaunchedEffect(zslWanted, zslSurface, cameraId, caps, lens, fullResolution) {
        val surface = zslSurface
        val chosen = lens
        val c = caps
        // The un-binned mode only where the sensor has it and the setting
        // asks; the chip that sets it is absent otherwise, so this is the
        // reconcile for a setting restored from another device.
        val wantFull = fullResolution && c?.supportsFullResolution == true

        // Stream configuration and sensor profile both have to come from the
        // lens actually being used, not from whichever camera happened to be
        // probed at startup.
        val ch = chosen?.let {
            runCatching {
                context.getSystemService(CameraManager::class.java)
                    .getCameraCharacteristics(it.cameraId)
            }.getOrNull()
        }
        val decision = ch?.let {
            val configs = ZslRawStream.rawConfigs(it)
            if (wantFull) ZslPolicy.evaluateFullResolution(chosen.supportsRaw, configs)
                // A sensor that will not stream un-binned falls back to the
                // binned ring rather than to no ring: the mode was a wish.
                .let { full -> if (full is ZslDecision.Stream) full else ZslPolicy.evaluate(chosen.supportsRaw, configs) }
            else ZslPolicy.evaluate(chosen.supportsRaw, configs)
        }

        val canStream = zslWanted && decision is ZslDecision.Stream &&
            surface != null && chosen != null && ch != null && c != null

        // Neither opening nor closing may be abandoned half way: a cancellation
        // between the camera opening and the handle being stored would leave
        // the device held by nothing that can close it.
        withContext(NonCancellable) {
            zslLock.withLock {
                val existing = zslStream

                if (!canStream) {
                    // Covers the user switching it off and the surface being
                    // destroyed underneath a running stream, which must not
                    // keep a dead Surface as a capture target.
                    existing?.close()
                    zslStream = null
                    zslOpenSurface = null
                    return@withLock
                }
                if (existing != null && zslOpenSurface === surface &&
                    existing.lensId == chosen!!.cameraId &&
                    existing.config.fullResolution == (decision as ZslDecision.Stream).config.fullResolution
                ) {
                    return@withLock
                }

                // Reached with a live stream only when the surface was replaced.
                // The old session is still targeting the dead one, so it goes
                // before the new session is built.
                existing?.close()
                zslStream = null
                zslOpenSurface = null

                status = "starting raw stream…"
                // The pool is native, so the Dalvik cap does not bound it --
                // physical memory does. Depth comes from what is actually free.
                val memory = ActivityManager.MemoryInfo().also {
                    context.getSystemService(ActivityManager::class.java)
                        .getMemoryInfo(it)
                }
                val config = (decision as ZslDecision.Stream).config
                // Sized for the deepest ring the device can afford rather than
                // for the burst currently selected, so changing the frame count
                // is a UI choice rather than a camera session rebuild.
                // A full-resolution ring is sized down to a burst and its
                // headroom: at 50 MP a slot is 100 MB.
                val depth = RawRingBudget.capacityFor(
                    config.width, config.height,
                    memory.availMem, memory.totalMem,
                    if (config.fullResolution) RawRingBudget.FULL_RESOLUTION_CAPACITY else RawRingBudget.MAX_CAPACITY,
                )
                // Checked before allocating rather than only after failing: an
        // allocation that succeeds and then gets the process killed is worse
        // than one that was never attempted.
        val required = RawRingBudget.frameBytes(config.width, config.height) * depth
        if (depth >= RawRingBudget.MIN_CAPACITY &&
            !MemoryPressure.canAffordRing(memory.availMem, required)
        ) {
            // Refused, in the log; the viewfinder shows a camera that works,
            // and the ring is tried again shortly.
            zslWanted = false
            Log.w(
                TAG,
                "ZSL declined: ${memory.availMem / (1024 * 1024)}MB free, " +
                    "ring needs ${required / (1024 * 1024)}MB",
            )
            return@withLock
        }
        if (depth < RawRingBudget.MIN_CAPACITY) {
                    zslWanted = false
                    Log.w(TAG, "ZSL declined: ${memory.availMem / (1024 * 1024)}MB free")
                    return@withLock
                }

                val opened = ZslRawStream.open(
                    manager = context.getSystemService(CameraManager::class.java),
                    lens = chosen!!,
                    characteristics = ch!!,
                    previewSurface = surface!!,
                    config = config,
                    ringDepth = depth,
                    settings = settings,
                    caps = c!!,
                )

                if (opened == null) {
                    // Falling back is a decision, not a crash: the sequential
                    // path is still a working camera, and says nothing about it.
                    zslWanted = false
                    Log.w(TAG, "raw stream unavailable, back to sequential capture")
                } else {
                    zslStream = opened
                    zslOpenSurface = surface
                    // The ring's shape is the log's business, not the viewfinder's.
                    Log.i(
                        TAG,
                        "%s ZSL %s, ring %d (%.0f MB), burst %d".format(
                            chosen.label, config, opened.ringCapacity,
                            opened.ringReservedBytes / (1024.0 * 1024.0), opened.maxBurst,
                        ),
                    )
                    if (status.startsWith("engaging") || status.startsWith("switching")) status = ""
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            zslStream?.close()
            zslStream = null
        }
    }

    // Push manual settings whenever they change. Camera2Interop.Extender only
    // applies at use-case build time, so live updates go through
    // Camera2CameraControl instead.
    LaunchedEffect(settings, camera, caps, zslStream) {
        val c = caps ?: return@LaunchedEffect
        zslStream?.let { stream -> stream.applySettings(settings, c) }
        val cam = camera ?: return@LaunchedEffect
        try {
            Camera2CameraControl.from(cam.cameraControl)
                .setCaptureRequestOptions(settings.toCaptureRequestOptions(c))
            if (settings.evActive && c.supportsExposureCompensation) {
                cam.cameraControl.setExposureCompensationIndex(settings.effectiveEvIndex(c))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Applying manual settings failed", e)
        }
    }

    // Outside the ZSL path there is no physical-lens selection to be had:
    // CameraX binds a logical camera and offers no way to choose which sensor
    // behind it serves a stream. Zoom ratio is the closest equivalent, and on a
    // multi-camera phone it is what makes the device switch lenses. The raw
    // path does it properly.
    LaunchedEffect(lens, camera, zslStream) {
        val cam = camera ?: return@LaunchedEffect
        if (zslStream != null) return@LaunchedEffect
        val target = lens?.zoomFactor ?: return@LaunchedEffect
        runCatching {
            val range = cam.cameraInfo.zoomState.value
            val clamped = target.coerceIn(
                range?.minZoomRatio ?: target,
                range?.maxZoomRatio ?: target,
            )
            cam.cameraControl.setZoomRatio(clamped)
            Log.i(TAG, "CameraX zoom set to %.2fx for ${lens?.label}".format(clamped))
        }.onFailure { Log.w(TAG, "zoom for lens selection failed", it) }
    }

    // The guard has to run continuously. In a zero-shutter-lag camera the
    // frames already exist when the shutter is pressed, so an exposure decision
    // taken then would apply to the next photograph rather than this one.
    LaunchedEffect(zslStream, highlightGuard, burstFrames, caps, captureMode) {
        val stream = zslStream ?: return@LaunchedEffect
        val c = caps ?: return@LaunchedEffect
        // Night mode deliberately does not protect highlights: a dark scene has
        // none worth protecting, and pulling exposure would spend the shadow
        // detail the mode exists to gather.
        val modeAllows = capturePlan?.protectHighlights ?: true
        if (!highlightGuard || !modeAllows) {
            stream.clearHighlightProtection(c, settings)
            guardPull = 0f
            return@LaunchedEffect
        }
        while (true) {
            withContext(Dispatchers.Default) {
                stream.protectHighlights(burstFrames, c, settings)
            }
            guardPull = stream.pullStops
            // Slower than the frame rate on purpose: auto-exposure needs time
            // to settle after a change, and chasing it faster oscillates.
            kotlinx.coroutines.delay(700)
        }
    }

    // The read-noise profile of REFERENCE.md §2.3, once per camera, in the
    // first seconds of a fresh stream: what is on record is applied at once
    // (the DCG bracket straddles a measured gain switch), and a camera with
    // no record is measured -- a ladder of sensitivities at constant
    // brightness, a second or two, the viewfinder live throughout -- unless
    // the photographer is already shooting. A press during the ladder
    // cancels it; the camera goes back to its settings and the measurement
    // waits for another launch. The store keeps a verdict, and a run the
    // light did not allow keeps nothing and is tried again.
    LaunchedEffect(zslStream, caps) {
        val stream = zslStream ?: return@LaunchedEffect
        val c = caps ?: return@LaunchedEffect
        val id = stream.lensId
        stream.setGainThreshold(DcgCalibrationStore.thresholdFor(context, id), settings, c)
        if (DcgCalibrationStore.hasProfiled(context, id)) {
            Log.i(TAG, "read-noise profile on record for $id: ${DcgCalibrationStore.thresholdFor(context, id) ?: "no gain switch"}")
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(1500)
        if (developing > 0 || busy || superResPhase.selected || settings.manualExposureActive(c)) {
            Log.i(TAG, "read-noise profile deferred to another launch: developing=$developing busy=$busy superRes=${superResPhase.selected} manual=${settings.manualExposureActive(c)}")
            return@LaunchedEffect
        }
        profileJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        try {
            val result = withContext(Dispatchers.IO) {
                DcgProfiler.profile(context, stream, c, settings, id)
            }
            if (result != null) stream.setGainThreshold(result.thresholdIso, settings, c)
        } finally {
            profileJob = null
        }
    }

    // The shutter cap: the longest frame the mode allows under auto-exposure.
    // AUTO's is 1/60 s, ACTION's 1/250 s, NIGHT's longer than the frame and
    // so no cap. The stream holds the meter to it; see `ShutterCap`.
    LaunchedEffect(zslStream, capturePlan?.maxExposureNs, caps) {
        val stream = zslStream ?: return@LaunchedEffect
        val c = caps ?: return@LaunchedEffect
        stream.setExposureCap(capturePlan?.maxExposureNs ?: 0L, settings, c)
    }

    // The ring, tried again. A ring the memory refused, or one released
    // under pressure, is not refused forever: half a minute on, the
    // sequential path hands back to it, and the attempt is cheap where the
    // memory is still short -- the budget is checked before anything is
    // allocated. Nothing about this reaches the viewfinder.
    LaunchedEffect(zslWanted, zslDecision) {
        if (zslWanted || zslDecision !is ZslDecision.Stream) return@LaunchedEffect
        kotlinx.coroutines.delay(RING_RETRY_MILLIS)
        Log.i(TAG, "trying the raw ring again")
        zslWanted = true
    }

    // The lens Super Res shoots its tiles with: the longest raw rear lens.
    val mosaicCaptureLens = remember(lenses) { MosaicPlanner.captureLens(lenses) }
    // The inertial sensors, for the guidance and the gate. Opened once; the
    // mode is not offered on a phone whose attitude cannot be read.
    val motion = remember { MotionSensor(context) }
    DisposableEffect(motion) { onDispose { motion.close() } }
    val superResOffered = mosaicCaptureLens != null && zslDecision is ZslDecision.Stream && motion.hasAttitude

    // The sensors run for as long as the mode is selected, not just during a
    // capture: the rotation vector takes most of a second to converge after
    // it is registered, and a capture that started it would anchor on an
    // attitude still swinging. Armed, they are converged long before the
    // shutter; idle, they are off. And the preview's zoom goes back to one:
    // the dots are computed on the uncropped sensor frame, and a zoomed
    // preview would move under them by the zoom.
    LaunchedEffect(superResPhase.selected, zslStream) {
        if (superResPhase.selected) {
            motion.start()
            val stream = zslStream
            val c = caps
            if (stream != null && c != null && digitalZoom != 1f) {
                digitalZoom = 1f
                gauges.setZoom(1f)
                stream.setZoom(1f, settings, c)
            }
        } else {
            motion.stop()
        }
    }

    // The lens follows the engine while Super Res is armed: a grid is shot
    // on the telephoto, the one-shot on the lens the user came from -- or
    // any lens they pick meanwhile, since the strip stays offered for it.
    LaunchedEffect(mosaicPreset, superResPhase) {
        if (superResPhase != SuperResMachine.Phase.ARMED) return@LaunchedEffect
        val capture = mosaicCaptureLens
        if (mosaicPreset.isMosaic) {
            if (capture != null && lens?.cameraId != capture.cameraId) {
                lens = capture
                status = "switching to ${capture.label}…"
            }
        } else if (lens?.cameraId == capture?.cameraId && mosaicReturnLens != null &&
            mosaicReturnLens?.cameraId != capture?.cameraId
        ) {
            lens = mosaicReturnLens
        }
    }

    // The plan for the armed mosaic: the preset's grid on the capture lens,
    // with the lens the user came from as the framing where that is wider.
    // Null until the stream is up on the capture lens, and null for the
    // one-shot, which is not a mosaic.
    val mosaicPlan = remember(superResPhase, mosaicPreset, zslStream, lens, mosaicReturnLens) {
        if (!superResPhase.selected) null else {
            val stream = zslStream
            if (stream == null || stream.lensId != lens?.cameraId) null
            else MosaicPlanner.planPreset(
                preset = mosaicPreset,
                target = mosaicReturnLens,
                capture = stream.lens,
                tileWidth = stream.config.width,
                tileHeight = stream.config.height,
            )
        }
    }

    // The megapixel badge for each grid on the pill: what the canvas would be
    // on this lens, from the same planner the capture uses.
    val presetMegapixels = remember(zslStream, mosaicCaptureLens) {
        val capture = mosaicCaptureLens ?: return@remember emptyMap<MosaicPreset, Double>()
        val w = zslStream?.config?.width ?: return@remember emptyMap<MosaicPreset, Double>()
        val h = zslStream?.config?.height ?: return@remember emptyMap<MosaicPreset, Double>()
        buildMap {
            // The one-shot's canvas is twice the frame each way, on whatever
            // lens is up: this sensor's frame at 2x.
            put(MosaicPreset.ONE_SHOT, MosaicPreset.oneShotMegapixels(w, h))
            for (preset in listOf(MosaicPreset.DETAIL_BOOST, MosaicPreset.LARGE_FORMAT)) {
                MosaicPlanner.planPreset(preset, null, capture, w, h)?.let { put(preset, it.megapixels) }
            }
        }
    }

    // Armed and not yet running: the outline and the nodes as the anchor would
    // place them, so the user sees the picture they are about to compose.
    LaunchedEffect(superResPhase, mosaicPlan) {
        if (superResPhase == SuperResMachine.Phase.ARMED) {
            mosaicView = mosaicPlan?.let { MosaicCapture.armedView(it, it.captureLens.label) }
            if (System.currentTimeMillis() >= superResSummaryUntil) {
                status = when {
                    !mosaicPreset.isMosaic -> "1-SHOT · %.0f MP · hold still, then press the shutter".format(
                        presetMegapixels[MosaicPreset.ONE_SHOT] ?: 0.0,
                    )
                    mosaicPlan == null -> "switching to the telephoto…"
                    else -> "%s · %d tiles · aim, then press the shutter".format(
                        mosaicPlan.captureLens.label, mosaicPlan.tileCount,
                    )
                }
            }
        } else if (superResPhase == SuperResMachine.Phase.IDLE) {
            mosaicView = null
        }
    }

    /**
     * Leaves Super Res for [mode], or enters it. Entering switches the lens to
     * the telephoto; leaving puts it back.
     */
    val selectMode: (CaptureMode) -> Unit = { mode ->
        if (mode == CaptureMode.SUPER_RES) {
            if (superResOffered && superResPhase == SuperResMachine.Phase.IDLE) {
                superResPhase = SuperResMachine.apply(superResPhase, SuperResMachine.Event.Select)
                captureMode = mode
                val capture = mosaicCaptureLens
                mosaicReturnLens = lens
                // The ring is the engine; if it was released, this is the moment to have it back.
                zslWanted = true
                // The grids shoot on the telephoto; the one-shot shoots on
                // whatever lens the user is on, since it is for any distance.
                if (mosaicPreset.isMosaic && capture != null && lens?.cameraId != capture.cameraId) {
                    lens = capture
                    status = "switching to ${capture.label}…"
                }
            }
        } else if (superResPhase.controlsEnabled) {
            if (superResPhase == SuperResMachine.Phase.ARMED) {
                superResPhase = SuperResMachine.apply(superResPhase, SuperResMachine.Event.Deselect)
                mosaicReturnLens?.let { lens = it }
                mosaicReturnLens = null
            }
            captureMode = mode
            status = ""
        }
    }

    // An armed Super Res whose ground has gone -- the ring refused, the lens
    // list changed, the stream fell back -- is left for AUTO rather than kept
    // armed with nothing to arm. Not while running: the run ends itself when
    // its stream closes, and lands on ARMED, where this then applies.
    LaunchedEffect(superResOffered, zslWanted, superResPhase) {
        if (superResPhase == SuperResMachine.Phase.ARMED && (!superResOffered || !zslWanted)) {
            selectMode(CaptureMode.AUTO)
            status = "super res needs the raw ring and the telephoto"
        }
    }

    /** Asks a running Super Res to stop, keeping what it has. The run's finally does the rest. */
    val stopSuperRes: () -> Unit = {
        superResPhase = SuperResMachine.apply(superResPhase, SuperResMachine.Event.Stop)
    }

    /**
     * Runs the mosaic, as a job.
     *
     * A job and not a `LaunchedEffect` keyed on the running flag, which is
     * what it was. Flipping that flag to stop it *cancelled the effect*, and
     * with it the lines after the run that reset the state and re-enabled the
     * controls; the screen stayed busy and armed with nothing left that could
     * reach it. Here the loop is stopped by [SuperResMachine.Phase.shouldContinue]
     * reading false and returning normally, and everything the screen must
     * have back -- the controls, the lens, the view -- is in a `finally` that
     * runs whether the mosaic completed, was stopped, failed, or the stream
     * went away under it. The stream's own lock and the sensors are released
     * in the runner's `finally`, and the canvas in `session.use`.
     */
    val startSuperRes: () -> Unit = {
        val stream = zslStream
        val c = caps
        val plan = mosaicPlan
        if (superResPhase != SuperResMachine.Phase.ARMED || stream == null || c == null || plan == null ||
            stream.lensId != lens?.cameraId
        ) {
            Haptics.reject(view)
        } else {
            Haptics.shutter(view)
            superResPhase = SuperResMachine.apply(superResPhase, SuperResMachine.Event.Shutter)
            busy = true
            status = "hold still to anchor"
            superResJob = scope.launch {
                var summary = ""
                try {
                    val rot = orientation.captureRotation(c.sensorOrientation)
                    val session = MosaicSession.start(plan, stream.config.width, stream.config.height, rot)
                    if (session == null) {
                        summary = "not enough memory for a %.0f MP canvas".format(plan.megapixels)
                    } else {
                        val guide = MosaicGuide(plan)
                        val result = withContext(MosaicCapture.dispatcher) {
                            session.use {
                                MosaicCapture.run(
                                    context = context,
                                    stream = stream,
                                    session = it,
                                    guide = guide,
                                    settings = settings,
                                    caps = c,
                                    frames = burstFrames,
                                    lensProfile = lensProfiles[stream.lensId],
                                    motion = motion,
                                    onView = { v -> mosaicView = v },
                                    onEvent = { event ->
                                        // Haptics from the main thread, and only
                                        // after the frames a haptic could blur have
                                        // already been taken: Locked fires once the
                                        // burst is snapshotted from the ring.
                                        scope.launch {
                                            when (event) {
                                                is MosaicEvent.Anchored -> {
                                                    Haptics.segment(view)
                                                    status = "follow the arrow to the dot and hold"
                                                }
                                                // The photograph is taken here: the
                                                // node is done, and the confirmation
                                                // is for that, not for the canvas.
                                                is MosaicEvent.Locked -> Haptics.confirm(view)
                                                // The canvas caught up: a light tick,
                                                // and nothing said about which tier
                                                // placed it -- the log has that.
                                                is MosaicEvent.Placed -> Haptics.tick(view)
                                                is MosaicEvent.Rejected -> {
                                                    Haptics.reject(view)
                                                    mosaicFlashUntil = System.currentTimeMillis() + 600
                                                    status = "tile ${event.node.index + 1}: ${event.reason}"
                                                }
                                                is MosaicEvent.TooFast -> {
                                                    val now = System.currentTimeMillis()
                                                    if (now > mosaicFlashUntil) {
                                                        Haptics.reject(view)
                                                        mosaicFlashUntil = now + 800
                                                    }
                                                }
                                                is MosaicEvent.Complete -> Haptics.confirm(view)
                                            }
                                        }
                                    },
                                    // Stopped by the phase, and by the stream
                                    // closing under it -- the activity stopping
                                    // -- so a run never outlives its camera.
                                    shouldContinue = { superResPhase.shouldContinue && !stream.isClosed },
                                )
                            }
                        }
                        Log.i(TAG, "super res result: $result")
                        summary = "%s  %.0f MP  %.1fs".format(
                            result.message, result.megapixels, result.elapsedMillis / 1000.0,
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    summary = "super res cancelled"
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "super res failed", e)
                    summary = "super res failed: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    // Guaranteed, whatever happened above: the machine back to
                    // ARMED, the controls free, the armed outline back on the
                    // viewfinder. The lens stays on the telephoto because the
                    // mode is still selected; leaving the mode returns it.
                    if (summary.isNotEmpty()) {
                        status = summary
                        superResSummaryUntil = System.currentTimeMillis() + 6_000L
                    }
                    superResPhase = SuperResMachine.apply(superResPhase, SuperResMachine.Event.Finished)
                    busy = false
                    superResJob = null
                }
            }
        }
    }

    // Written whenever anything worth remembering changes. Cheap: a dozen
    // short strings, and only on an actual change rather than per frame.
    LaunchedEffect(
        settings, burstFrames, lens, highlightGuard, captureMode, dngEnabled,
        timerSeconds, guides, mosaicPreset, localTone, outputFormat, showHistogram,
        mosaicReturnLens, fullResolution,
    ) {
        AppSettings.save(
            context,
            AppSettings(
                manual = settings,
                burstFrames = burstFrames,
                // What the user chose, not what Super Res is holding for the
                // moment: the app reopens in an ordinary mode on the lens they
                // were on.
                lensId = (mosaicReturnLens ?: lens)?.cameraId,
                highlightGuard = highlightGuard,
                dngEnabled = dngEnabled,
                captureMode = if (captureMode == CaptureMode.SUPER_RES) CaptureMode.AUTO else captureMode,
                timerSeconds = timerSeconds,
                guides = guides.name,
                mosaicPreset = mosaicPreset,
                localTone = localTone,
                outputFormat = outputFormat,
                histogram = showHistogram,
                fullResolution = fullResolution,
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Ink.Ground)
            .onSizeChanged { viewSize = it }
            .pointerInput(zslStream, camera, caps) {
                detectTapGestures { offset ->
                    val c = caps ?: return@detectTapGestures
                    if (viewSize.width == 0 || viewSize.height == 0) return@detectTapGestures
                    // Against the image, not the window. With the viewfinder
                    // letterboxed these differ by the size of the bars, and
                    // normalising against the window would put the metering
                    // point steadily further from the finger the nearer the
                    // top or bottom of the frame it went.
                    val bounds = previewBounds
                    val nx: Float
                    val ny: Float
                    if (bounds != null && bounds.width > 0f && bounds.height > 0f) {
                        // A tap on a letterbox bar is not a tap on anything.
                        if (!bounds.contains(offset)) return@detectTapGestures
                        nx = (offset.x - bounds.left) / bounds.width
                        ny = (offset.y - bounds.top) / bounds.height
                    } else {
                        nx = offset.x / viewSize.width
                        ny = offset.y / viewSize.height
                    }
                    focusPoint = offset
                    focusAtMillis = System.currentTimeMillis()

                    val stream = zslStream
                    if (stream != null) {
                        stream.focusAt(nx, ny, settings, c)
                    } else {
                        // CameraX exposes metering through its own point
                        // factory, which already knows the preview geometry.
                        camera?.let { cam ->
                            runCatching {
                                val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(
                                    viewSize.width.toFloat(), viewSize.height.toFloat(),
                                )
                                cam.cameraControl.startFocusAndMetering(
                                    androidx.camera.core.FocusMeteringAction.Builder(
                                        factory.createPoint(offset.x, offset.y)
                                    ).build()
                                )
                            }.onFailure { Log.w(TAG, "focus tap failed", it) }
                        }
                    }
                }
            }
            .pointerInput(zslStream, camera, caps, superResPhase.selected) {
                detectTransformGestures { _, _, gestureZoom, _ ->
                    if (gestureZoom == 1f) return@detectTransformGestures
                    // Not in Super Res: the dots are computed on the sensor's
                    // frame, which no zoom crops, and the preview is what the
                    // zoom crops. The two would part company by the zoom.
                    if (superResPhase.selected) return@detectTransformGestures
                    val c = caps ?: return@detectTransformGestures
                    val stream = zslStream
                    if (stream != null) {
                        digitalZoom = (digitalZoom * gestureZoom)
                            .coerceIn(1f, stream.maxZoom)
                        gauges.setZoom(digitalZoom)
                        stream.setZoom(digitalZoom, settings, c)
                    } else {
                        camera?.let { cam ->
                            val state = cam.cameraInfo.zoomState.value ?: return@let
                            digitalZoom = (digitalZoom * gestureZoom)
                                .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                            gauges.setZoom(digitalZoom)
                            runCatching { cam.cameraControl.setZoomRatio(digitalZoom) }
                        }
                    }
                }
            }
    ) {
        if (zslWanted) {
            // SurfaceView, not TextureView. Camera2 tags preview buffers with
            // the sensor-to-display rotation and SurfaceFlinger honours that
            // hint for a SurfaceView; a TextureView ignores it and would show
            // the viewfinder on its side until given an explicit matrix.
            val previewSize = remember(zslDecision, characteristics) {
                val d = zslDecision
                val ch = characteristics
                if (d is ZslDecision.Stream && ch != null) {
                    ZslRawStream.previewSizeFor(ch, d.config)
                } else null
            }
            // `surfaceSize` sets the buffer's dimensions; it does not size the
            // view. Left to fill the window, a 4:3 stream was being stretched
            // to the phone's ~19.5:9 -- faces widened, and the frame shown was
            // not the frame captured. The view is constrained to the stream's
            // own shape instead, and the bars are the app's ground.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    // Inside the region the layout leaves the viewfinder --
                    // beside a rail, above a hinge -- and the largest box
                    // of the stream's shape that fits it.
                    .previewRegion(layout)
                    .then(
                        previewSize?.let { Modifier.fitAspect(it.portraitAspect()) }
                            ?: Modifier.fillMaxSize()
                    )
                    .onGloballyPositioned { previewBounds = it.boundsInParent() },
            ) {
                AndroidExternalSurface(
                    modifier = Modifier.fillMaxSize(),
                    surfaceSize = previewSize
                        ?.let { IntSize(it.width, it.height) } ?: IntSize.Zero,
                    zOrder = AndroidExternalSurfaceZOrder.Behind,
                ) {
                    onSurface { surface, _, _ ->
                        zslSurface = surface
                        surface.onDestroyed { zslSurface = null }
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black))
            surfaceRequest?.let { request ->
                // CameraXViewfinder does not stretch -- it centre-crops to fill
                // whatever it is given, which is its own kind of wrong here: the
                // sides of a 4:3 frame were simply not on screen. Giving it a
                // box of the stream's own shape means its crop has nothing left
                // to take.
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .previewRegion(layout)
                        .fitAspect(request.resolution.portraitAspect())
                        .onGloballyPositioned { previewBounds = it.boundsInParent() },
                ) {
                    CameraXViewfinder(
                        surfaceRequest = request,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Guides(mode = guides, attitude = attitude)

        // Over the picture and only the picture, turned into its orientation.
        previewBounds?.let { b ->
            val density = LocalDensity.current
            Peaking(
                mask = peakingMask,
                maskWidth = PEAK_W,
                maskHeight = PEAK_H,
                sensorOrientation = caps?.sensorOrientation ?: 90,
                modifier = Modifier
                    .offset { IntOffset(b.left.toInt(), b.top.toInt()) }
                    .size(with(density) { b.width.toDp() }, with(density) { b.height.toDp() }),
            )
        }

        if (settings.manualFocus) {
            FocusGauge(
                gauges = gauges,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp),
            )
        }

        // Ground for the controls, and only for the controls. Chips floating
        // directly on the photograph are unreadable over a bright sky and read
        // as a debug overlay over anything else; a gradient gives the row
        // somewhere to sit without putting a bar across the frame. It also
        // makes the chip that runs off the edge look deliberate, which it is --
        // the row scrolls.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(156.dp)
                .background(
                    Brush.verticalGradient(listOf(Ink.ScrimTop, Color.Transparent)),
                ),
        )
        if (layout.posture == Posture.SIDEBAR) {
            // The rail's ground, down the end of the window.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(layout.railWidth)
                    .background(Ink.Panel),
            )
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(232.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Ink.ScrimBottom)),
                    ),
            )
        }

        // The lens the mosaic shoots with: the longest that delivers raw. On
        // any lens, including that one -- the first version offered the mode
        // only from a wider lens and hid it on the telephoto, which is the
        // lens it uses.

        // What the controls are is decided in one place and what they do in
        // another, joined by an id. That is what lets `ControlBar` be plain
        // data -- and so lets a phone without raw support, which this one is
        // not, be tested on the JVM instead of held in the hand.
        val resolvedLabel = capturePlan?.resolved?.label
        val barState = ControlBarState(
            busy = busy,
            burstFrames = burstFrames,
            timerSeconds = timerSeconds,
            guidesLabel = guides.valueLabel,
            guidesOn = guides != GuideMode.OFF,
            histogramOn = showHistogram,
            dngOn = dngEnabled,
            settingsOpen = showSettings,
            proOpen = showPro,
            aboutOpen = showAbout,
            mode = captureMode.label,
            // Say what AUTO settled on, not just that it is AUTO.
            resolvedMode = resolvedLabel?.takeIf { zslStream != null },
            // Only offered where the hardware said yes. On a camera that stalls
            // on raw the ring is never offered and nothing changes.
            streamOffered = zslDecision is ZslDecision.Stream,
            // The drawer has something to draw where the sensor takes a manual
            // exposure or the lens a manual focus.
            manualOffered = caps?.let { it.hasManualSensor || it.hasManualFocus } == true,
            superResOffered = superResOffered,
            superRes = superResPhase,
        )

        val handleControl: (String) -> Unit = handle@{ id ->
            // Every control in the row and the strip is a detent except the two
            // openers, which change nothing and whose panel animation is its own
            // feedback. The actions get one too: the tick is acknowledging the
            // press, and the capture it starts is asynchronous -- nothing is
            // vibrating while the sensor is integrating.
            if (id != ControlBar.SETTINGS && id != ControlBar.ABOUT && id != ControlBar.PRO) {
                Haptics.segment(view)
            }
            ControlBar.modeOf(id)?.let { mode ->
                selectMode(mode)
                return@handle
            }
            when (id) {
                ControlBar.FRAMES -> {
                    // Only counts the heap-sized ring can actually hold.
                    val ceiling = zslStream?.maxBurst ?: buffer.capacity
                    burstFrames = ControlBar.nextBurst(burstFrames, ceiling)
                }
                ControlBar.DNG -> dngEnabled = !dngEnabled
                ControlBar.PRO -> showPro = !showPro
                ControlBar.TIMER -> timerSeconds = ControlBar.nextTimer(timerSeconds)
                ControlBar.GUIDES -> guides = guides.next()
                ControlBar.HISTOGRAM -> showHistogram = !showHistogram
                ControlBar.SETTINGS -> showSettings = !showSettings
                ControlBar.ABOUT -> showAbout = true
                ControlBar.STOP -> stopSuperRes()

            }
        }

        CompositionLocalProvider(LocalHandRotation provides layout.handRotation) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                // Over the viewfinder's region only: not over a rail.
                .hudRegion(layout)
                // Ignoring visibility, not `statusBarsPadding()`.
                //
                // MainActivity hides the status bar deliberately -- a clock and
                // a battery meter inside a viewfinder are telling you about the
                // phone while you are trying to look at the picture -- and
                // brings it back transiently on a swipe. But a hidden bar
                // reports a zero inset, so `statusBarsPadding()` reserved
                // nothing and the control row sat in the space the bar returns
                // to. Swipe it down, or leave another app in picture-in-picture,
                // and the clock lands on top of `MERGE ON` with the battery
                // icon inside the `INFO` chip.
                //
                // This reserves the height whether the bar is showing or not,
                // which costs a few dp of viewfinder and is the only version
                // that is never wrong.
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The EV label and the manual flag are settings, not sensor
            // readings: they change when the user changes them and not
            // otherwise, so they are pushed into the holder from here rather
            // than sampled alongside the exposure.
            val evLabel = caps
                ?.takeIf { it.supportsExposureCompensation && settings.evActive }
                ?.let { c -> "%+.1f".format(settings.effectiveEvIndex(c) * c.evStep) }
            val manualExposure = caps?.let { settings.manualExposureActive(it) } == true
            LaunchedEffect(evLabel, manualExposure) {
                gauges.setEv(evLabel, manualExposure)
            }

            // The top of the viewfinder: the exposure gauge, and one opener.
            //
            // It was a row of six settings chips with the gauge under it. The
            // chips are behind the opener now, on a screen of their own, and
            // the gauge -- ISO, shutter, EV, monospaced -- is the only text
            // over the top of the photograph. See `ControlBar.chrome`.
            Box(modifier = Modifier.fillMaxWidth()) {
                ExposureGauge(
                    gauges = gauges,
                    modifier = Modifier.align(Alignment.Center),
                )
                // The sensor's readout, opposite the opener, on a sensor
                // with an un-binned mode and the memory for it. Neither
                // development device has one; the chip is absent there.
                caps?.takeIf { it.supportsFullResolution && zslStream != null }?.let { c ->
                    ResolutionPill(
                        full = fullResolution,
                        binnedMegapixels = c.rawMegapixels,
                        fullMegapixels = c.fullResolutionMegapixels,
                        enabled = !busy && developing == 0 && !superResPhase.selected,
                        onFull = { wanted ->
                            if (wanted != fullResolution) {
                                Haptics.segment(view)
                                fullResolution = wanted
                                status = "switching to %.0f MP…".format(if (wanted) c.fullResolutionMegapixels else c.rawMegapixels)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 12.dp),
                    )
                }
                ChromeOpeners(
                    specs = ControlBar.chrome(barState),
                    onControl = handleControl,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp),
                )
            }

            // With the exposure readout, not pinned to the opposite corner.
            //
            // It used to sit bottom-left at a fixed `bottom = 130.dp`, which
            // put it straight under the action strip: its ground ran beneath
            // `SUPER RES` and the right half of the plot was covered. A fixed
            // offset into a cluster whose height depends on how many lenses
            // and actions the hardware offers was never going to hold.
            //
            // In the flow instead, and next to the numbers it belongs with --
            // a histogram and a shutter speed answer the same question, and
            // the reference keeps them together in the top chrome for that
            // reason. Same ground and radius as the readout, so the two read
            // as one instrument rather than two overlays.
            if (showHistogram) {
                HistogramGauge(gauges = gauges, modifier = Modifier.padding(top = 8.dp))
            }

            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    style = Type.Status,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .rotateLayout(-layout.handRotation)
                        .background(Ink.Readout, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        Column(
            // The bottom strip, or the rail down the end of a wide window,
            // or the pane under the hinge: `controlsRegion`. Held sideways,
            // the strip's rows are the user's columns and each chip's words
            // turn to read upright (LocalHandRotation, UprightInHand).
            modifier = Modifier
                .align(if (layout.posture == Posture.SIDEBAR) Alignment.CenterEnd else Alignment.BottomCenter)
                .controlsRegion(layout),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (layout.posture == Posture.SIDEBAR) Arrangement.Center else Arrangement.Bottom,
        ) {
            // Lens strip. Labelled in millimetres, because that is what tells a
            // photographer what the frame will look like; the zoom factor is
            // there for anyone who thinks in phone terms.
            // Not with Super Res selected: the telephoto is doing the taking,
            // and the strip would offer a choice the mode has already made.
            // The one-shot is the exception: it shoots on any lens, so the
            // strip stays while it is armed.
            val rearLenses = remember(lenses) { LensCatalog.rear(lenses) }
            val lensChoice = !superResPhase.selected ||
                (superResPhase == SuperResMachine.Phase.ARMED && !mosaicPreset.isMosaic)
            if (rearLenses.size > 1 && lensChoice) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    rearLenses.forEach { option ->
                        LensChip(
                            lens = option,
                            selected = option.cameraId == lens?.cameraId,
                            enabled = !busy,
                        ) {
                            Haptics.segment(view)
                            lens = option
                        }
                    }
                }
            }

            // The manual drawer: exposure, focus and white balance, slid up
            // over the bottom bar with the picture left whole above it. Its
            // own ground, with the shutter row's, so the two read as one
            // surface the controls stand on; nothing of it is drawn until
            // PRO is touched.
            val drawerOpen = showPro && caps != null
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (drawerOpen) Modifier.background(Ink.Panel, PanelShape) else Modifier)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedVisibility(
                    visible = drawerOpen,
                    enter = expandVertically(expandFrom = Alignment.Bottom),
                    exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
                ) {
                    ManualPanel(
                        settings = settings,
                        caps = caps,
                        onChange = { settings = it },
                    )
                }

                // The one action that is not the shutter: STOP, while a Super
                // Res runs. Squared off rather than pilled, because it ends a
                // capture the moment it is touched.
                ActionStrip(
                    specs = ControlBar.actions(barState),
                    onControl = handleControl,
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                // The grid, directly above the shutter while Super Res is
                // armed, where the outline it changes is in view and the
                // thumb that will press the shutter is already. Each grid
                // carries what the canvas would be, in megapixels. Not while
                // running: the nodes are laid out and some are taken.
                if (superResPhase == SuperResMachine.Phase.ARMED) {
                    // What each engine is for, in a word, over the pill: the
                    // grids assume a scene far enough that the tiles share a
                    // plane; the one-shot assumes nothing but a still hand.
                    Text(
                        text = if (mosaicPreset.isMosaic) "BEST FOR SCENES > 3 M" else "ANY DISTANCE · HOLD STILL",
                        style = Type.Label,
                        color = Ink.Muted,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .rotateLayout(-layout.handRotation)
                            .semantics { contentDescription = "super res hint" },
                    )
                    MosaicPresetPill(
                        preset = mosaicPreset,
                        customOffered = false,
                        oneShotOffered = caps?.supportsSuperRes == true,
                        megapixels = presetMegapixels,
                        onPreset = {
                            Haptics.segment(view)
                            mosaicPreset = it
                        },
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }

                // The ZSL shutter, for the ordinary modes and the one-shot
                // alike. The frames are locked in microseconds on this thread
                // -- they were exposed before the press, so there is nothing
                // to wait for -- then the haptic, with the frames a haptic
                // could blur already in the ring's hand, and the shot goes to
                // the develop queue. The settings are read at the press. With
                // a timer the countdown runs first, busy for the countdown
                // only: it is what a second press cancels.
                fun takeZsl(stream: ZslRawStream, frames: Int, label: String, canvas2x: Boolean) {
                    val rot = caps?.let { orientation.captureRotation(it.sensorOrientation) } ?: 0
                    val lensProfile = lensProfiles[stream.lensId]
                    val params = DevelopParams(
                        localTone = if (localTone) DevelopParams.LOCAL_TONE_ON else 0f,
                        shadowLift = if (localTone) DevelopParams.SHADOW_LIFT_ON else 0f,
                    )
                    val dng = dngEnabled
                    val format = outputFormat

                    fun lockAndQueue() {
                        val shot = ZslCapture.take(stream, frames)
                        if (shot.count == 0) {
                            shot.close()
                            Haptics.reject(view)
                            status = "ring empty: no frames buffered yet"
                            return
                        }
                        Haptics.shutter(view)
                        developing++
                        status = "%s: %d frames".format(label, shot.count)
                        developQueue.trySend(
                            PendingShot(shot, stream, rot, lensProfile, format, canvas2x, params, dng),
                        )
                    }

                    // The ring is finite and a shot in the queue holds its
                    // frames until the merge has read them. Presses that
                    // outran the merge used to take whatever was left --
                    // two frames, then one -- and a single-frame photograph
                    // is not what the shutter promises. A press the ring
                    // cannot fit is refused, plainly, and the ring says so.
                    if (lockPending || stream.lockedFrames() + frames > stream.ringCapacity) {
                        Haptics.reject(view)
                        status = "ring full: $developing developing — a moment"
                        return
                    }

                    if (timerSeconds <= 0) {
                        // The frames the press wants are in the ring already
                        // -- that is what zero shutter lag means -- unless the
                        // last press took them a moment ago and the camera is
                        // still refilling. Then the press waits for its
                        // frames, up to the time they take to expose, rather
                        // than taking a short burst: the haptic comes when
                        // they are locked, never before.
                        if (stream.readyFrames() >= frames) {
                            lockAndQueue()
                            return
                        }
                        lockPending = true
                        scope.launch {
                            try {
                                val intervalMs = (stream.config.minFrameDurationNs / 1_000_000L).coerceAtLeast(16L)
                                val deadline = System.currentTimeMillis() + frames * intervalMs + REFILL_GRACE_MILLIS
                                while (stream.readyFrames() < frames && System.currentTimeMillis() < deadline) {
                                    status = "ring refilling %d/%d".format(stream.readyFrames(), frames)
                                    kotlinx.coroutines.delay(intervalMs)
                                }
                            } finally {
                                lockPending = false
                            }
                            if (zslStream !== stream) return@launch
                            if (stream.readyFrames() < MIN_BURST_FRAMES.coerceAtMost(frames)) {
                                // The camera did not refill in the time the
                                // frames take: the ring is dropping. Not a
                                // photograph; say so.
                                Haptics.reject(view)
                                status = "ring did not refill: %d of %d frames".format(stream.readyFrames(), frames)
                                return@launch
                            }
                            lockAndQueue()
                        }
                        return
                    }
                    busy = true
                    captureJob = scope.launch {
                        for (remaining in timerSeconds downTo 1) {
                            countdown = remaining
                            // A metronome you can feel without watching the
                            // screen, which is the point of a self-timer.
                            Haptics.tick(view)
                            status = "$remaining… tap the shutter to cancel"
                            kotlinx.coroutines.delay(1000)
                        }
                        countdown = 0
                        busy = false
                        lockAndQueue()
                    }
                }

                // Thumbnail, shutter and the space opposite share one row, so
                // they share one centre line. The thumbnail was positioned against
                // the bottom of the screen on its own, independently of the column
                // the shutter sits in, which left the two about 19dp out of
                // alignment -- the kind of thing that reads as carelessness
                // without ever being nameable.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LastShotThumb(
                        image = thumbnail,
                        enabled = !busy,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 28.dp),
                        onOpen = {
                            // The app's own gallery, rather than handing the
                            // user to another app. Reviewing a merge means
                            // looking at it at pixel level and comparing it
                            // against what was expected, and a general-purpose
                            // viewer does not know the difference between the
                            // JPEG and the DNG beside it.
                            Haptics.segment(view)
                            showGallery = true
                        },
                    )
                    ShutterButton(
                        busy = busy,
                        counting = countdown > 0,
                        onClick = {
                            if (countdown > 0) {
                                // Called off before anything was locked.
                                Haptics.reject(view)
                                captureJob?.cancel()
                                captureJob = null
                                countdown = 0
                                busy = false
                                status = "timer cancelled"
                                return@ShutterButton
                            }
                            if (busy) return@ShutterButton

                            // A press is a photograph; a profile in progress
                            // gives the camera back and waits for another launch.
                            profileJob?.cancel()

                            // Zero shutter lag: the frames already exist, so this press
                            // locks them rather than starting a capture -- and
                            // returns. The merge, the render and the write are
                            // the develop queue's, behind the viewfinder; the
                            // next press is taken whenever it comes.
                            val stream = zslStream

                            // Super Res. A grid: the shutter anchors it where the
                            // reticle points and the guidance takes over; the
                            // stream has to be on the capture lens already. The
                            // one-shot: a sixteen-frame burst from the ring on
                            // this lens onto the 2x canvas, through the same
                            // queue as any shot.
                            if (superResPhase.selected) {
                                if (mosaicPreset.isMosaic) {
                                    startSuperRes()
                                } else if (superResPhase == SuperResMachine.Phase.ARMED && stream != null &&
                                    caps?.supportsSuperRes == true
                                ) {
                                    takeZsl(stream, MosaicPreset.ONE_SHOT_FRAMES, "1-SHOT", canvas2x = true)
                                } else {
                                    Haptics.reject(view)
                                }
                                return@ShutterButton
                            }

                            if (stream != null) {
                                val plan = capturePlan
                                takeZsl(stream, plan?.frames ?: burstFrames, plan?.resolved?.label ?: "ZSL", canvas2x = false)
                                return@ShutterButton
                            }

                            // Fires here, at the press, and nowhere later: the
                            // burst has not started yet, so the motor is still
                            // by the time the sensor is integrating. A haptic
                            // during the burst would be motion blur.
                            Haptics.shutter(view)

                            // Without a ring -- the hardware refused one, or the
                            // memory did -- the sequential path is the camera.
                            // The merge is not a choice here either: a raw
                            // burst through CameraX where the device delivers
                            // raw, and the analysis stream's merge where it
                            // does not.
                            busy = true
                            status = "capturing $burstFrames frames…"
                            val rotation = caps?.let { orientation.captureRotation(it.sensorOrientation) } ?: 0
                            val raw = rawCapture
                            val ch = characteristics
                            if (raw != null && ch != null) {
                                scope.launch {
                                    val r = withContext(Dispatchers.Default) {
                                        RawBurstCapture.captureAndMerge(
                                            context = context,
                                            imageCapture = raw,
                                            characteristics = ch,
                                            captureResult = lastCaptureResult.get(),
                                            frameCount = burstFrames,
                                            // The equivalent focal length is the
                                            // catalogue's to know, not the capture
                                            // result's.
                                            lens = lens,
                                            lensProfile = lens?.let { lensProfiles[it.cameraId] },
                                            outputFormat = outputFormat,
                                            rotationDegrees = rotation,
                                            develop = DevelopParams(
                                                localTone = if (localTone) DevelopParams.LOCAL_TONE_ON else 0f,
                                                shadowLift = if (localTone) DevelopParams.SHADOW_LIFT_ON else 0f,
                                            ),
                                            writeDng = dngEnabled,
                                            onProgress = { },
                                        )
                                    }
                                    Log.i(TAG, "raw burst result: $r")
                                    Log.i(
                                        TAG,
                                        ("capture %dms  merge %dms  develop %dms  " +
                                            "write %dms").format(
                                            r.captureMillis, r.mergeMillis,
                                            r.developMillis, r.writeMillis,
                                        ),
                                    )
                                    status = r.stats?.let {
                                        CaptureReadout.of(it.framesMerged, it.meanContribution)
                                    } ?: r.message
                                    busy = false
                                }
                                return@ShutterButton
                            }

                            scope.launch {
                                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                    .format(System.currentTimeMillis())

                                val result = withContext(Dispatchers.Default) {
                                    val frames = buffer.snapshot(burstFrames.coerceAtMost(buffer.capacity))
                                    if (frames.isEmpty()) return@withContext null
                                    val out = Merger.process(frames, mergeEnabled = true)
                                    val bmp = OrientationTracker.rotate(out.bitmap, rotation)
                                    val uri = ImageSaver.saveJpeg(context, bmp, "MF_${stamp}_merged.jpg")
                                    bmp.recycle()
                                    out.stats to uri
                                }

                                status = if (result == null) {
                                    "No frames buffered yet"
                                } else {
                                    val stats = result.first
                                    // The engineering detail stays in the log, where it
                                    // has always been read from. What reaches the screen
                                    // is what the merge bought, which is the one thing
                                    // this camera does that the phone's own does not --
                                    // and which until now only logcat ever saw.
                                    Log.i(
                                        TAG,
                                        "Saved ${result.second}  $stats  rot=$rotation  " +
                                            "align ${stats.alignMillis}ms merge ${stats.mergeMillis}ms",
                                    )
                                    CaptureReadout.of(stats.framesUsed, stats.meanContribution)
                                }
                                busy = false
                            }
                        },
                    )
                    // PRO, opposite the thumbnail: the manual drawer's opener,
                    // beside the shutter where a camera puts its manual dial.
                    ChromeOpeners(
                        specs = ControlBar.pro(barState),
                        onControl = handleControl,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 24.dp),
                    )
                }

                // The modes, under the shutter: one tap to any of them, the
                // selected one the heavy one. Drawn only where there is a
                // choice; a phone with no ring and no telephoto has AUTO and
                // no dial. See `ControlBar.carousel`.
                ModeCarousel(
                    specs = ControlBar.carousel(barState),
                    onControl = handleControl,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }
        } // LocalHandRotation

        focusPoint?.let { point ->
            var visible by remember(focusAtMillis) { mutableStateOf(true) }
            LaunchedEffect(focusAtMillis) {
                kotlinx.coroutines.delay(1400)
                visible = false
            }
            if (visible) {
                with(androidx.compose.ui.platform.LocalDensity.current) {
                    Box(
                        modifier = Modifier
                            .offset(
                                x = (point.x - RETICLE_PX / 2).toDp(),
                                y = (point.y - RETICLE_PX / 2).toDp(),
                            )
                            .size(RETICLE_PX.toDp())
                            .border(1.5.dp, Ink.Amber, RoundedCornerShape(4.dp))
                            .semantics { contentDescription = "Focus point" },
                    )
                }
            }
        }

        ZoomGauge(
            gauges = gauges,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
        )

        mosaicView?.let { v ->
            val orientation = caps?.sensorOrientation ?: 90
            // The outline, the dots and the reticle, over the preview and
            // only the preview: a dot on a letterbox bar would point at
            // nothing.
            previewBounds?.let { b ->
                val density = LocalDensity.current
                val boundsW = with(density) { b.width.toDp() }
                val boundsH = with(density) { b.height.toDp() }
                MosaicGuidance(
                    view = v,
                    sensorOrientation = orientation,
                    // The preview's own zoom, so a dot moves with the scene it
                    // is drawn over and not with the uncropped frame behind it.
                    previewZoom = zslStream?.zoom ?: 1f,
                    modifier = Modifier
                        .offset { IntOffset(b.left.toInt(), b.top.toInt()) }
                        .size(boundsW, boundsH),
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The count, small and out of the way at the top, with the
                // lens doing the taking. The number is the reading and takes
                // the accent; the nouns are not.
                Row(
                    modifier = Modifier
                        .background(Ink.Readout, RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = v.captureLensLabel.uppercase(),
                        style = Type.Label,
                        color = Ink.Muted,
                        modifier = Modifier.padding(bottom = 3.dp, end = 10.dp),
                    )
                    Text(text = "${v.captured}", style = Type.GaugeLarge)
                    Text(
                        text = " of ${v.total} nodes",
                        style = Type.Control,
                        color = Ink.Muted,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                    if (v.anchored && !v.located) {
                        Text(
                            text = "  ·  FINDING",
                            style = Type.Label,
                            color = Ink.Muted,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                }
                // The hold's hint: what the gate is waiting for -- and,
                // once the hold is confirmed, the exposure it is held for.
                val hint = when {
                    v.exposeProgress in 0f..1f -> "EXPOSING · HOLD"
                    v.unsteady == MosaicGuide.Unsteady.TURNING -> "HOLD STILL"
                    v.unsteady == MosaicGuide.Unsteady.DRIFTING -> "HOLD STEADY ON THE DOT"
                    else -> null
                }
                hint?.let { text ->
                    Text(
                        text = text,
                        style = Type.Instruction,
                        color = Ink.Muted,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .background(Ink.Readout, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }

        if (countdown > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(132.dp)
                    .background(Ink.Readout, CircleShape)
                    .semantics { contentDescription = "Timer $countdown" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = countdown.toString(),
                    style = Type.Countdown,
                )
            }
        }

        // Everything set once, on a screen of its own over the viewfinder:
        // the capture chips and the rendering choices. The manual controls
        // are not here; they are in the drawer, where the picture is.
        if (showSettings) {
            SettingsScreen(
                chips = ControlBar.settings(barState),
                onControl = handleControl,
                onDismiss = {
                    Haptics.segment(view)
                    showSettings = false
                },
            ) {
                SetOncePanel(
                    caps = caps,
                    highlightGuard = if (zslStream != null) highlightGuard else null,
                    onHighlightGuard = { if (!busy) highlightGuard = it },
                    // Only with a stream to accumulate from: the 2x path is
                    // a property of the ZSL burst.
                    localTone = localTone,
                    onLocalTone = { if (!busy) localTone = it },
                    outputFormat = if (HeifEncoder.isAvailable) outputFormat else null,
                    onOutputFormat = { if (!busy) outputFormat = it },
                    onReset = {
                        // Back to what the app ships with. The save effect above
                        // is watching every one of these, so persisting it needs
                        // no separate step.
                        val fresh = AppSettings()
                        settings = fresh.manual
                        burstFrames = fresh.burstFrames
                        highlightGuard = fresh.highlightGuard
                        dngEnabled = fresh.dngEnabled
                        if (superResPhase.controlsEnabled) selectMode(fresh.captureMode)
                        timerSeconds = fresh.timerSeconds
                        guides = GuideMode.OFF
                        mosaicPreset = fresh.mosaicPreset
                        localTone = fresh.localTone
                        outputFormat = fresh.outputFormat
                        showHistogram = fresh.histogram
                        fullResolution = fresh.fullResolution
                        status = "settings reset"
                    },
                )
            }
        }

        if (showAbout) {
            AboutSheet(onDismiss = { showAbout = false })
        }

        // Above everything, including the About sheet: it is a place rather
        // than a panel, and the viewfinder underneath is still composed.
        if (showGallery) {
            // Closing it re-reads the last capture, which may have been
            // deleted in there -- see the refresh effect above.
            GalleryScreen(context = context, onDismiss = { showGallery = false })
        }

        // The system back gesture, from the top of the stack down: a sheet,
        // the gallery, the settings, then Super Res -- stopped if running,
        // left if armed -- and only on a bare viewfinder in an ordinary mode
        // does it reach the system and leave. The gallery's own review
        // overlay closes itself first, with its own handler. The order is
        // `BackNavigation`'s, and is tested there.
        val backScreen = BackNavigation.Screen(
            aboutOpen = showAbout,
            galleryOpen = showGallery,
            settingsOpen = showSettings,
            proOpen = showPro,
            superRes = superResPhase,
        )
        BackHandler(enabled = BackNavigation.intercepts(backScreen)) {
            when (BackNavigation.resolve(backScreen)) {
                BackNavigation.Action.CLOSE_ABOUT -> showAbout = false
                BackNavigation.Action.CLOSE_GALLERY -> showGallery = false
                BackNavigation.Action.CLOSE_SETTINGS -> showSettings = false
                BackNavigation.Action.CLOSE_PRO -> showPro = false
                BackNavigation.Action.STOP_SUPER_RES -> {
                    Haptics.segment(view)
                    stopSuperRes()
                }
                BackNavigation.Action.LEAVE_SUPER_RES -> {
                    Haptics.segment(view)
                    selectMode(CaptureMode.AUTO)
                }
                BackNavigation.Action.WAIT -> Unit
                BackNavigation.Action.EXIT -> Unit
            }
        }
    }
}

/** Every lens chip is this circle. See [LensChip]. */
private val LensChipSize = 72.dp

/** The manual drawer's top corners, and the only place the radius is stated. */
private val PanelShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

/**
 * The last shot, which is also the way into the gallery.
 *
 * Draws nothing at all before there is one. An earlier version held an empty
 * bordered square as a placeholder, on the theory that the shutter would
 * otherwise move once the first photograph was taken -- which was simply
 * wrong: the shutter is centred in the row and the thumbnail is aligned to the
 * start, so neither depends on the other. What the placeholder actually did
 * was put an empty outlined box on a fresh install, which reads as a picture
 * that failed to load.
 */
@Composable
private fun LastShotThumb(
    image: android.graphics.Bitmap?,
    enabled: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (image == null) return
    Box(
        modifier = modifier
            .size(54.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Ink.Edge, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onOpen)
            .semantics { contentDescription = "Last shot" },
    ) {
        UprightInHand(Modifier.fillMaxSize()) {
            androidx.compose.foundation.Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The region the layout leaves the viewfinder: the window less a rail, or
 * the pane above a hinge; the whole window otherwise. Composed with
 * `Modifier.align(Center)` on the preview box, which then centres the
 * fitted stream inside this region.
 */
private fun Modifier.previewRegion(layout: CameraLayout): Modifier = when (layout.posture) {
    Posture.SIDEBAR -> this.fillMaxSize().padding(end = layout.railWidth)
        .wrapContentSize(Alignment.Center)
    Posture.TABLETOP -> this.fillMaxSize()
        .layout { measurable, constraints ->
            // The pane above the hinge, in the window's own pixels.
            val paneHeight = layout.paneSplitTopPx.coerceIn(0, constraints.maxHeight)
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = paneHeight))
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place((constraints.maxWidth - placeable.width) / 2, (paneHeight - placeable.height) / 2)
            }
        }
    else -> this
}

/** The HUD stays over the viewfinder: inset from a rail, never over it. */
private fun Modifier.hudRegion(layout: CameraLayout): Modifier = when (layout.posture) {
    Posture.SIDEBAR -> this.padding(end = layout.railWidth)
    else -> this
}

/**
 * Where the controls stand: the strip along the bottom, the rail down the
 * end of a wide window, or the pane under a foldable's hinge.
 */
private fun Modifier.controlsRegion(layout: CameraLayout): Modifier = when (layout.posture) {
    Posture.SIDEBAR -> this.fillMaxHeight().width(layout.railWidth)
    Posture.TABLETOP -> this.layout { measurable, constraints ->
        val top = layout.paneSplitBottomPx.coerceIn(0, constraints.maxHeight)
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = constraints.maxHeight - top))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place((constraints.maxWidth - placeable.width) / 2, constraints.maxHeight - placeable.height)
        }
    }
    else -> this
}

/**
 * The shape this stream makes on a portrait screen.
 *
 * Camera streams are quoted landscape -- 1440x1080 for a 4:3 sensor -- and the
 * display rotation is applied downstream of the buffer, so the ratio a portrait
 * viewfinder needs is the short side over the long one whichever way round the
 * numbers arrive.
 */
private fun Size.portraitAspect(): Float =
    minOf(width, height).toFloat() / maxOf(width, height).toFloat()

/**
 * One lens in the strip.
 *
 * Shows the equivalent focal length above the zoom factor: the first is what a
 * photographer reasons about, the second is what phone cameras have trained
 * everyone to look for.
 */
@Composable
private fun LensChip(
    lens: Lens,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // A lens is a setting, so it is a pill -- the same shape as everything
    // else that changes what the next capture will be. It was an 18dp rounded
    // square, which put a third shape language on a screen whose whole scheme
    // is that there are two: pill for reversible, squared for a shutter.
    // Every lens is the same circle.
    //
    // A 50%-rounded rectangle made the short labels ("12mm") into circles and
    // the long ones ("220mm") into ellipses -- one shape or the other would
    // have been a decision, and both together read as neither. A fixed circle
    // also matches what the reference does with its zoom chips, and it means
    // the strip has an even rhythm rather than one that stretches with the
    // number of digits in a focal length.
    Column(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .size(LensChipSize)
            .background(if (selected) Ink.Bone else Ink.Pane, CircleShape)
            .then(
                if (selected) Modifier
                else Modifier.border(1.dp, Ink.Hairline, CircleShape)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "${lens.label} lens" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // A circle is the one shape a turn does not change; only the words in it turn.
        UprightInHand {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = lens.label,
                    style = Type.LensFocal,
                    color = if (selected) Ink.OnBone else Ink.Bone,
                )
                Text(
                    text = lens.zoomLabel,
                    // Was 9sp at 60% white, which over a lit road was not so much
                    // low-contrast as absent. The zoom factor is the half of this
                    // chip most people actually read.
                    style = Type.LensZoom,
                    color = if (selected) Ink.OnBoneMuted else Ink.Muted,
                )
            }
        }
    }
}

/**
 * A ZSL shot the shutter took and the develop queue owes: the locked burst
 * and everything the press knew that the develop needs. The settings are
 * read at the press, so a chip changed while the shot develops changes the
 * next one and not this.
 */
/** Over the frames' own exposure time, how long a press waits for the ring to refill. */
private const val REFILL_GRACE_MILLIS = 150L

/** The fewest frames a press takes after waiting for the ring; under this it refuses instead. */
private const val MIN_BURST_FRAMES = 4

private class PendingShot(
    val shot: ZslCapture.Shot,
    val stream: ZslRawStream,
    val rotation: Int,
    val lensProfile: LensProfile?,
    val outputFormat: OutputFormat,
    val canvas2x: Boolean,
    val develop: DevelopParams,
    val writeDng: Boolean,
)

@Composable
private fun ShutterButton(
    busy: Boolean,
    modifier: Modifier = Modifier,
    counting: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(76.dp)
            .border(
                width = 3.dp,
                color = if (busy) Ink.Amber else Ink.Bone,
                shape = CircleShape,
            )
            .padding(6.dp)
            .clip(CircleShape)
            .background(if (busy) Ink.Amber else Ink.Bone)
            // Live during a countdown, which is the one moment a press means
            // stop rather than go.
            .clickable(enabled = !busy || counting, onClick = onClick)
            .semantics { contentDescription = if (counting) "Cancel timer" else "Shutter" },
    )
}