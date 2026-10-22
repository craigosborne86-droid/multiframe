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
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clipToBounds
import dev.multiframe.camera.ui.revision05Region
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import dev.multiframe.camera.pipeline.returnToAuto
import dev.multiframe.camera.pipeline.restoreManual
import dev.multiframe.camera.pipeline.whiteBalanceLabel
import dev.multiframe.camera.ui.capturePreviewSize
import dev.multiframe.camera.ui.TitaniumTokens
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
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
import dev.multiframe.camera.pipeline.MotionClassifier
import dev.multiframe.camera.pipeline.MotionMeter
import dev.multiframe.camera.pipeline.NightPlan
import dev.multiframe.camera.pipeline.NightPlanner
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
import dev.multiframe.camera.pipeline.LevelGauge
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Brush
import dev.multiframe.camera.ui.Ink
import dev.multiframe.camera.ui.Type
import dev.multiframe.camera.ui.Haptics
import dev.multiframe.camera.ui.GaugeState
import dev.multiframe.camera.ui.AboutSheet
import dev.multiframe.camera.ui.LightboxScreen
import dev.multiframe.camera.ui.GuideMode
import dev.multiframe.camera.ui.Guides
import dev.multiframe.camera.ui.PeakingPreview
import dev.multiframe.camera.ui.CameraXPeakingPreview
import dev.multiframe.camera.ui.FocusAssistInset
import dev.multiframe.camera.ui.MechanicalTurret
import dev.multiframe.camera.ui.RangefinderTab
import dev.multiframe.camera.ui.TitaniumReticle
import dev.multiframe.camera.ui.MechanicalShutter
import dev.multiframe.camera.ui.MergeProcessingIndicator
import dev.multiframe.camera.ui.MergeUiProgress
import dev.multiframe.camera.ui.RangefinderOverlayPolicy
import dev.multiframe.camera.ui.CaptureFeedback
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import dev.multiframe.camera.ui.BackNavigation
import dev.multiframe.camera.ui.CameraLayout
import dev.multiframe.camera.ui.LocalHandRotation
import dev.multiframe.camera.ui.Posture
import dev.multiframe.camera.ui.fitAspect
import dev.multiframe.camera.ui.rotateLayout
import dev.multiframe.camera.ui.UprightInHand
import dev.multiframe.camera.ui.rememberCameraLayout
import dev.multiframe.camera.ui.ControlBar
import dev.multiframe.camera.ui.ControlBarState
import dev.multiframe.camera.ui.SettingsScreen
import dev.multiframe.camera.pipeline.SuperResMachine
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Still results kept for pairing: a sequential frame looks its own up as its image arrives, so a few suffice. */
private const val STILL_RESULTS = 8

/** Side of the focus reticle, in pixels. */
private const val RETICLE_PX = 180f

// Both opt-ins are needed and they are not the same thing: Kotlin's satisfies
// the compiler, and AndroidX's own is what its lint check looks for. With only
// the first, `lintDebug` reported every interop call in this function as an
// UnsafeOptInUsageError.
@OptIn(ExperimentalCamera2Interop::class, ExperimentalLayoutApi::class)
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val displayLocale = LocalResources.current.configuration.locales[0] ?: Locale.ROOT
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
    var showLabsOnOpen by remember { mutableStateOf(false) }
    // Android recreates the Activity on a quarter-turn so CameraX can bind at
    // the new display rotation. Keep the controls the user was adjusting.
    var showPro by rememberSaveable { mutableStateOf(false) }
    var showWhiteBalance by rememberSaveable { mutableStateOf(false) }
    var showCaptureInfo by rememberSaveable { mutableStateOf(false) }
    var manualBurst by remember { mutableStateOf(restored.manualBurst) }
    var rememberedBurst by remember { mutableStateOf(restored.rememberedManualBurst) }
    var torchOn by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) torchOn = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var rangefinderTab by rememberSaveable { mutableStateOf(RangefinderTab.SHUTTER) }
    var zebraOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var longExpSelected by rememberSaveable { mutableStateOf(false) }
    var shutterBeforeLongExp by rememberSaveable { mutableLongStateOf(restored.manual.exposureTimeNs) }
    var flashPulse by remember { mutableIntStateOf(0) }
    var flashVisible by remember { mutableStateOf(false) }
    LaunchedEffect(flashPulse) {
        if (flashPulse > 0) {
            flashVisible = true
            kotlinx.coroutines.delay(110)
            flashVisible = false
        }
    }
    var showAbout by remember { mutableStateOf(false) }
    var showGallery by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showSettings, showGallery, showAbout) {
        if (showSettings || showGallery || showAbout) torchOn = false
    }
    var showHistogram by remember { mutableStateOf(restored.histogram) }
    var previewRgb by remember { mutableStateOf<dev.multiframe.camera.ui.PreviewRgbHistogram?>(null) }
    val histogramHandler: ((dev.multiframe.camera.ui.PreviewRgbHistogram) -> Unit)? = if (showHistogram) { histogram ->
        view.post { previewRgb = histogram }
        Unit
    } else null
    var rawCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var characteristics by remember { mutableStateOf<CameraCharacteristics?>(null) }
    // The DNG writer needs the capture metadata that produced the frame, which
    // CameraX does not surface directly.
    val lastCaptureResult = remember { AtomicReference<TotalCaptureResult?>(null) }
    // Every still capture's result by sensor timestamp, so the sequential
    // burst scales each frame by its own exposure (RawBurstCapture).
    val stillResults = remember { dev.multiframe.camera.pipeline.FrameMetadataStore<TotalCaptureResult>(STILL_RESULTS) }
    var burstFrames by remember { mutableIntStateOf(restored.burstFrames) }
    // Busy is the sequential camera's and the self-timer's: the shutter is
    // theirs until they are done. A ZSL shot is never busy -- its frames
    // are locked at the press and developed behind the viewfinder, see
    // `developing` -- so the next press is taken as soon as it comes.
    var busy by remember { mutableStateOf(false) }
    // Shots taken and not yet written: the number the develop queue holds.
    var developing by remember { mutableIntStateOf(0) }
    var nextProcessingId by remember { mutableLongStateOf(0L) }
    val mergeProgressFlow = remember { MutableStateFlow<MergeUiProgress?>(null) }
    val mergeProgress by mergeProgressFlow.collectAsState()
    /** The read-noise profile in progress, for the shutter to cancel; null otherwise. */
    var profileJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    /** A press waiting for the ring to refill before it locks its frames. One at a time. */
    var lockPending by remember { mutableStateOf(false) }
    // Bumped as each shot is written, for the thumbnail to reload on.
    var shotsWritten by remember { mutableIntStateOf(0) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var caps by remember { mutableStateOf<CameraCapabilities?>(null) }

    // Panel visibility is transient; the explicit capture profile survives it.
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
    var zslSurfaceSize by remember { mutableStateOf<Size?>(null) }
    var zslStream by remember { mutableStateOf<ZslRawStream?>(null) }
    if (BuildConfig.DEBUG) {
        androidx.compose.runtime.SideEffect {
            dev.multiframe.camera.ui.CaptureUiInspection.stream = zslStream
            dev.multiframe.camera.ui.CaptureUiInspection.merge = mergeProgress
            dev.multiframe.camera.ui.CaptureUiInspection.manual = settings
            dev.multiframe.camera.ui.CaptureUiInspection.frames = burstFrames
        }
        DisposableEffect(Unit) {
            onDispose {
                dev.multiframe.camera.ui.CaptureUiInspection.stream = null
                dev.multiframe.camera.ui.CaptureUiInspection.merge = null
                dev.multiframe.camera.ui.CaptureUiInspection.manual = null
            }
        }
    }

    // Every physical lens the device has, not a zoom ratio on one of them. A
    // raw pipeline needs to know which sensor produced a frame, because black
    // level, colour filter arrangement and calibration are all per lens.
    var lenses by remember { mutableStateOf<List<Lens>>(emptyList()) }
    // Optical characterisation per physical camera, keyed by camera id. Empty
    // until the catalogue has been enumerated; a lens with no entry is one the
    // device would not describe, which every consumer has to survive.
    var lensProfiles by remember { mutableStateOf<Map<String, LensProfile>>(emptyMap()) }
    var lens by remember { mutableStateOf<Lens?>(null) }
    LaunchedEffect(lens?.cameraId) { torchOn = false }
    var rearReturnLensId by rememberSaveable { mutableStateOf<String?>(null) }
    var rearReturnZoom by rememberSaveable { mutableFloatStateOf(1f) }

    // Expose for the highlights and let the merge pay for the shadows. On by
    // default: it is the whole reason for capturing a burst, and it costs
    // nothing on a scene that does not need it.
    var highlightGuard by remember { mutableStateOf(restored.highlightGuard) }
    var guardPull by remember { mutableFloatStateOf(0f) }
    var dngEnabled by remember { mutableStateOf(restored.dngEnabled) }
    var localTone by remember { mutableStateOf(restored.localTone) }
    var outputFormat by remember { mutableStateOf(restored.outputFormat) }
    // The gain map beside the JPEG; only offered where the platform writes one.
    var hdrOutput by remember { mutableStateOf(restored.hdrOutput) }
    var contentCredentials by remember { mutableStateOf(restored.contentCredentials) }
    // The sensor's un-binned mode, on a sensor that has one. See the chip.
    var fullResolution by remember { mutableStateOf(restored.fullResolution) }

    // What the user is photographing. The right settings for a night scene and
    // a moving subject are opposites, and only the user knows which it is.
    var captureMode by remember { mutableStateOf(restored.captureMode) }
    // PHOTO starts with clipping warnings visible. A user toggle overrides
    // the mode default until settings are reset.
    val zebraEnabled = zebraOverride ?: (captureMode == CaptureMode.PHOTO)
    var scene by remember { mutableStateOf<SceneAnalysis?>(null) }

    // Self-timer, and the last shot taken.
    var timerSeconds by remember { mutableIntStateOf(restored.timerSeconds) }
    var longExposureSeconds by remember { mutableIntStateOf(restored.longExposureSeconds) }
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
    var focusAssistAnchor by remember { mutableStateOf(Offset(.5f, .5f)) }
    var focusAssistOpen by rememberSaveable { mutableStateOf(false) }
    var focusAssistRect by remember { mutableStateOf<Rect?>(null) }
    var digitalZoom by rememberSaveable { mutableFloatStateOf(1f) }

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
    var sweepReturn by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver(
        save = { snapshot: dev.multiframe.camera.pipeline.CaptureConfigurationSnapshot? ->
            HashMap(snapshot?.encode() ?: emptyMap()) },
        restore = { values: HashMap<String, String> -> dev.multiframe.camera.pipeline.CaptureConfigurationSnapshot.decode(values) },
    )) { mutableStateOf<dev.multiframe.camera.pipeline.CaptureConfigurationSnapshot?>(null) }
    var sweepExitPending by remember { mutableStateOf(false) }
    var mosaicReturnLens by remember { mutableStateOf<Lens?>(null) }
    var brenizerActive by remember { mutableStateOf(false) }
    var mosaicPreset by remember {
        // The drawn-region preset is not offered on the pill; a saved one
        // falls back to the smaller grid.
        mutableStateOf(restored.mosaicPreset.takeIf { it.isMosaic && it != MosaicPreset.CUSTOM } ?: MosaicPreset.DETAIL_BOOST)
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
    val liveFocusDistance = remember { AtomicReference<Float?>(null) }

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

    // NIGHT's plan from the hand and the meter, re-solved every poll while
    // NIGHT is selected (the loop beside the motion sensor below); null where
    // NIGHT is the fixed plan.
    var nightPlan by remember { mutableStateOf<NightPlan?>(null) }

    // What the chosen mode implies for this scene, recomputed as either moves.
    val capturePlan = remember(captureMode, scene, zslStream, burstFrames, manualBurst, digitalZoom, caps, brenizerActive, nightPlan) {
        val stream = zslStream
        if (stream == null) null
        else CaptureModes.plan(
            mode = captureMode,
            analysis = scene ?: SceneAnalysis(0f, 0.5f, 0.1f, 3f),
            availableFrames = stream.maxBurst,
            requestedFrames = burstFrames,
            night = nightPlan,
        ).let { plan ->
            // NIGHT's automatic plan fills the ring. A depth explicitly set
            // in PRO must reach the shutter, while PHOTO's plan retains its
            // independent ten-frame cap and hardware limits still prevail.
            if (manualBurst) plan.copy(frames = minOf(plan.frames, burstFrames)) else plan
        }
    }

    // The develop queue: every ZSL shot, in the order taken, merged and
    // written one at a time on a worker while the viewfinder runs on. One
    // at a time because the merge already spans every core; two at once
    // would only contend. A shot in hand is a photograph owed, so a develop
    // is not cancelled once begun. The merge and render run on Default; the
    // DNG and JPEG writes inside them move to IO (RawBurstCapture and
    // SuperResCapture.finishOutputs).
    val developQueue = remember { Channel<PendingShot>(Channel.UNLIMITED) }
    LaunchedEffect(Unit) {
        for (pending in developQueue) {
            mergeProgressFlow.value = MergeUiProgress(pending.processingId, 0, pending.shot.count)
            val r = try {
                withContext(Dispatchers.Default + NonCancellable) {
                    ZslCapture.develop(
                        context, pending.stream, pending.shot, pending.rotation,
                        lensProfile = pending.lensProfile,
                        outputFormat = pending.outputFormat,
                        superRes = pending.canvas2x,
                        develop = pending.develop,
                        writeDng = pending.writeDng,
                        outputTag = pending.outputTag,
                        plan = pending.plan,
                        onMergeProgress = { progress ->
                            mergeProgressFlow.value = MergeUiProgress(
                                pending.processingId, progress.processed, progress.total, progress.finished,
                            )
                        },
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
            if (mergeProgressFlow.value?.shotId == pending.processingId &&
                mergeProgressFlow.value?.finished == false
            ) mergeProgressFlow.value = null
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
                val saved = dev.multiframe.camera.pipeline.CaptureSaveFeedback.forOutputs(
                    r.savedJpeg != null, r.savedDng != null, pending.writeDng)
                status = saved.message ?: ((if (developing > 0) "$readout · $developing developing" else readout) + decline)
                if (saved.hasSavedOutput) shotsWritten++
            } else {
                status = dev.multiframe.camera.pipeline.CaptureSaveFeedback.forOutputs(false, false, pending.writeDng).message!!
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

    // The console always shows tilt, including when composition guides are
    // hidden. Keep the gravity sensor active only while capture is visible.
    LaunchedEffect(level, deviceDegrees, lifecycleOwner, showSettings, showGallery, showAbout) {
        if (showSettings || showGallery || showAbout) {
            attitude = null
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            level.enable()
            try {
                while (true) {
                    attitude = level.attitude?.let { reading ->
                        reading.copy(rollDegrees = LevelGauge.normalise(reading.rollDegrees - deviceDegrees))
                    }
                    kotlinx.coroutines.delay(60)
                }
            } finally {
                level.disable()
                attitude = null
            }
        }
    }

    LaunchedEffect(lifecycleOwner, zslWanted, lens?.facingBack) {
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
        if (zslWanted && caps != null && zslDecision is ZslDecision.Stream) {
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

        val targetRotation = view.display.rotation
        Log.i(TAG, "CameraX bind displayRotation=$targetRotation")
        val preview = Preview.Builder().setTargetRotation(targetRotation).also { builder ->
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
                            result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                                ?.takeIf { it.isFinite() && it >= 0f }
                                ?.let { liveFocusDistance.set(it) }
                        }
                    }
                )
            }.onFailure { Log.w(TAG, "no live exposure readout on this device", it) }
        }.build().apply {
            setSurfaceProvider { request -> surfaceRequest = request }
        }

        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(targetRotation)
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
                if (lens?.facingBack == false) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                if (lens?.facingBack == false) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA,
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
                    .setTargetRotation(targetRotation)
                Camera2Interop.Extender(b).setSessionCaptureCallback(
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: Camera2Request,
                            result: TotalCaptureResult,
                        ) {
                            lastCaptureResult.set(result)
                            result.get(CaptureResult.SENSOR_TIMESTAMP)?.takeIf { it > 0 }?.let { stillResults.put(it, result) }
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

            // A stalled or absent RAW stream keeps the already bound CameraX
            // session. Handing it to a ring that cannot open leaves no preview
            // surface and prevents the sequential path from ever starting.
            if (zslDecision is ZslDecision.Fallback) zslWanted = false

            // Anything restored has to be checked against this camera: a
            // setting the hardware cannot honour has the whole capture request
            // rejected, taking every other setting with it.
            val reconciled = restored.copy(manual = settings)
                .reconcile(capabilities, lenses).manual
            settings = reconciled

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

    // The preview belongs to the selected physical sensor and pixel mode.
    // The initial CameraX discovery describes the logical 1x camera; leaving
    // that decision in place would reuse its preview size after a lens or
    // 50 MP switch and make the new session stretch or reject the surface.
    LaunchedEffect(lens?.cameraId, fullResolution, caps?.supportsFullResolution) {
        val selected = lens ?: return@LaunchedEffect
        val probed = withContext(Dispatchers.IO) {
            runCatching {
                val ch = context.getSystemService(CameraManager::class.java)
                    .getCameraCharacteristics(selected.cameraId)
                val localCaps = caps?.forLens(ch)
                val configs = ZslRawStream.rawConfigs(ch)
                val requested = fullResolution && localCaps?.supportsFullResolution == true
                val decision = if (requested) {
                    ZslPolicy.evaluateFullResolution(selected.supportsRaw, configs).let { full ->
                        if (full is ZslDecision.Stream) full
                        else ZslPolicy.evaluate(selected.supportsRaw, configs)
                    }
                } else ZslPolicy.evaluate(selected.supportsRaw, configs)
                ch to decision
            }.getOrNull()
        } ?: return@LaunchedEffect
        characteristics = probed.first
        zslDecision = probed.second
    }

    // One effect owns the stream's whole life. Opening needs three things to
    // line up -- the user asking, the hardware allowing, and a preview surface
    // to render into -- and the surface arrives asynchronously, so anything
    // going away has to tear the stream down again through the same path.
    LaunchedEffect(zslWanted, zslSurface, zslSurfaceSize, cameraId, caps, lens, fullResolution) {
        val surface = zslSurface
        val chosen = lens
        // The un-binned mode only where the sensor has it and the setting
        // asks; the chip that sets it is absent otherwise, so this is the
        // reconcile for a setting restored from another device.
        val wantFull = fullResolution && caps?.supportsFullResolution == true

        // Stream configuration and sensor profile both have to come from the
        // lens actually being used, not from whichever camera happened to be
        // probed at startup.
        val ch = chosen?.let {
            runCatching {
                context.getSystemService(CameraManager::class.java)
                    .getCameraCharacteristics(it.cameraId)
            }.getOrNull()
        }
        val sessionFlash = chosen?.let { selected ->
            runCatching {
                context.getSystemService(CameraManager::class.java)
                    .getCameraCharacteristics(selected.openId)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }.getOrDefault(false)
        } ?: false
        val c = if (ch != null) caps?.forLens(ch)?.copy(hasFlashUnit = sessionFlash) else caps
        if (c != null && caps != c) caps = c
        if (c != null && ch != null) {
            val wbUnsupported = settings.whiteBalanceMode == dev.multiframe.camera.pipeline.WhiteBalanceMode.MANUAL &&
                settings.manualWhiteBalance(c) == null ||
                settings.whiteBalanceMode == dev.multiframe.camera.pipeline.WhiteBalanceMode.LOCK && !c.supportsAwbLock
            if (wbUnsupported) {
                settings = settings.copy(whiteBalanceMode = dev.multiframe.camera.pipeline.WhiteBalanceMode.AUTO,
                    manualColor = false, awbMode = android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO)
                status = "WB override unsupported on this camera; Auto WB active, stored values retained"
            }
        }
        val decision = ch?.let {
            val configs = ZslRawStream.rawConfigs(it)
            if (wantFull) ZslPolicy.evaluateFullResolution(chosen.supportsRaw, configs)
                // A sensor that will not stream un-binned falls back to the
                // binned ring rather than to no ring: the mode was a wish.
                .let { full -> if (full is ZslDecision.Stream) full else ZslPolicy.evaluate(chosen.supportsRaw, configs) }
            else ZslPolicy.evaluate(chosen.supportsRaw, configs)
        }

        val requiredPreviewSize = if (decision is ZslDecision.Stream && ch != null)
            capturePreviewSize(ch, decision.config) else null
        val canStream = zslWanted && decision is ZslDecision.Stream &&
            surface != null && zslSurfaceSize == requiredPreviewSize &&
            chosen != null && ch != null && c != null

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
                    if (config.fullResolution)
                        RawRingBudget.fullResolutionCapacity(config.width, config.height)
                    else RawRingBudget.MAX_CAPACITY,
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
                    if (status.startsWith("engaging") || status.startsWith("switching") ||
                        status.startsWith("starting raw stream")) status = ""
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

    LaunchedEffect(torchOn, zslStream, camera, caps, zslWanted, lens?.cameraId) {
        val c = caps ?: return@LaunchedEffect
        val desired = torchOn && c.hasFlashUnit
        if (zslWanted) {
            val stream = zslStream?.takeIf { it.lensId == lens?.cameraId } ?: return@LaunchedEffect
            val accepted = withContext(Dispatchers.IO) { stream.setTorch(desired, c) }
            if (!accepted && desired) torchOn = false
        } else {
            val active = camera ?: return@LaunchedEffect
            if (!c.hasFlashUnit) return@LaunchedEffect
            val operation = active.cameraControl.enableTorch(desired)
            operation.addListener({
                runCatching { operation.get() }.onFailure {
                    Log.w(TAG, "Torch request failed", it)
                    if (desired) torchOn = false
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    // Outside the ZSL path there is no physical-lens selection to be had:
    // CameraX binds a logical camera and offers no way to choose which sensor
    // behind it serves a stream. Zoom ratio is the closest equivalent, and on a
    // multi-camera phone it is what makes the device switch lenses. The raw
    // path does it properly.
    LaunchedEffect(lens, camera, zslStream, digitalZoom, caps) {
        val stream = zslStream
        val c = caps
        if (stream != null && c != null) {
            stream.setZoom(digitalZoom, settings, c)
            return@LaunchedEffect
        }
        val cam = camera ?: return@LaunchedEffect
        if (zslStream != null) return@LaunchedEffect
        val target = if (lens?.facingBack == false) digitalZoom else (lens?.zoomFactor ?: 1f) * digitalZoom
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
    val brenizerOffered = mosaicCaptureLens != null && zslDecision is ZslDecision.Stream && motion.hasAttitude
    val superResOffered = caps?.supportsSuperRes == true && zslDecision is ZslDecision.Stream &&
        (zslStream?.maxBurst ?: 0) >= MosaicPreset.ONE_SHOT_FRAMES

    // The sensors run for as long as the mode is selected, not just during a
    // capture: the rotation vector takes most of a second to converge after
    // it is registered, and a capture that started it would anchor on an
    // attitude still swinging. Armed, they are converged long before the
    // shutter; idle, they are off. And the preview's zoom goes back to one:
    // the dots are computed on the uncropped sensor frame, and a zoomed
    // preview would move under them by the zoom.
    // NIGHT wants the gyroscope too, for as long as it is selected: the
    // window its plan reads is the last half second before the press.
    val nightMetering = captureMode == CaptureMode.NIGHT && zslStream != null &&
        caps?.let { NightPlanner.supported(motion.isAvailable, it) } == true
    LaunchedEffect(superResPhase.selected, brenizerActive, zslStream, nightMetering) {
        if (nightMetering) motion.start()
        if (superResPhase.selected && brenizerActive) {
            motion.start()
            val stream = zslStream
            val c = caps
            if (stream != null && c != null && digitalZoom != 1f) {
                digitalZoom = 1f
                stream.setZoom(1f, settings, c)
            }
        } else if (!nightMetering) {
            motion.stop()
        }
    }

    // NIGHT's shake-o-meter: the hand's rate over the last half second,
    // classified with hysteresis, and the plan solved from it and the meter.
    // A few hundred samples summed every tenth of a second: well inside the
    // main thread's budget. Absent a gyroscope or a manual sensor the plan is
    // null and NIGHT is the fixed plan.
    LaunchedEffect(nightMetering, zslStream) {
        nightPlan = null
        val stream = zslStream ?: return@LaunchedEffect
        val c = caps ?: return@LaunchedEffect
        if (!nightMetering) return@LaunchedEffect
        val isoMin = c.isoMin ?: return@LaunchedEffect
        val isoMax = c.isoMax ?: return@LaunchedEffect
        val exposureMin = c.exposureMinNs ?: return@LaunchedEffect
        val exposureMax = c.exposureMaxNs ?: return@LaunchedEffect
        val pixelsPerRadian = lensProfiles[stream.lensId]?.pixelsPerRadian(stream.config.width)
            ?: MosaicCapture.pixelsPerRadian(stream)
        val classifier = MotionClassifier(pixelsPerRadian)
        while (true) {
            kotlinx.coroutines.delay(NightPlanner.POLL_MS)
            val now = motion.nowNs()
            val trace = motion.gyroTrace(now - MotionMeter.WINDOW_NS) ?: break
            val rate = MotionMeter.rmsRate(trace, now)
            val motionClass = classifier.update(rate, now)
            val (iso, exposureNs) = stream.latestExposure() ?: continue
            nightPlan = NightPlanner.solve(motionClass, rate, pixelsPerRadian, iso, exposureNs,
                isoMin, isoMax, exposureMin, exposureMax, stream.maxBurst)
        }
    }

    // The lens follows the engine while Super Res is armed: a grid is shot
    // on the telephoto, the one-shot on the lens the user came from -- or
    // any lens they pick meanwhile, since the strip stays offered for it.
    LaunchedEffect(mosaicPreset, superResPhase, brenizerActive) {
        if (superResPhase != SuperResMachine.Phase.ARMED) return@LaunchedEffect
        val capture = mosaicCaptureLens
        if (brenizerActive) {
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
    val mosaicPlan = remember(superResPhase, mosaicPreset, brenizerActive, zslStream, lens, mosaicReturnLens) {
        if (!superResPhase.selected || !brenizerActive) null else {
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
        val capture = mosaicCaptureLens
        val w = zslStream?.config?.width ?: return@remember emptyMap<MosaicPreset, Double>()
        val h = zslStream?.config?.height ?: return@remember emptyMap<MosaicPreset, Double>()
        buildMap {
            // The one-shot's canvas is twice the frame each way, on whatever
            // lens is up: this sensor's frame at 2x.
            put(MosaicPreset.ONE_SHOT, MosaicPreset.oneShotMegapixels(w, h))
            for (preset in listOf(MosaicPreset.DETAIL_BOOST, MosaicPreset.LARGE_FORMAT)) {
                capture?.let { MosaicPlanner.planPreset(preset, null, it, w, h) }?.let { put(preset, it.megapixels) }
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
                    !brenizerActive -> "1-SHOT · %.0f MP · hold still, then press the shutter".format(
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

    fun restoreSweep() {
        sweepReturn?.let { saved ->
            superResPhase = SuperResMachine.Phase.IDLE
            brenizerActive = false
            lens = lenses.firstOrNull { it.cameraId == saved.lensId } ?: mosaicReturnLens ?: lens
            digitalZoom = saved.zoom
            settings = saved.settings
            captureMode = saved.mode
            burstFrames = saved.frames
            manualBurst = saved.manualBurst
            timerSeconds = saved.timerSeconds
            showPro = saved.proVisible
            fullResolution = saved.fullResolution
        }
        mosaicReturnLens = null; sweepReturn = null; sweepExitPending = false; mosaicView = null
    }

    // A recreated Activity cannot resume an acquisition job. Restore its saved return point.
    LaunchedEffect(sweepReturn, brenizerActive, lenses) {
        if (sweepReturn != null && !brenizerActive && lenses.isNotEmpty()) {
            restoreSweep()
            status = "Sweep interrupted; previous shooting configuration restored"
        }
    }

    /**
     * Selects a primary mode. Only leaving a Labs sweep restores its prior lens.
     */
    val selectMode: (CaptureMode) -> Unit = { mode ->
        if (superResPhase.controlsEnabled) {
            if (superResPhase == SuperResMachine.Phase.ARMED) {
                superResPhase = SuperResMachine.apply(superResPhase, SuperResMachine.Event.Deselect)
                if (brenizerActive) restoreSweep()
                mosaicReturnLens = null
            }
            brenizerActive = false
            captureMode = mode
            status = ""
        }
    }

    // An armed Super Res whose ground has gone -- the ring refused, the lens
    // list changed, the stream fell back -- is left for AUTO rather than kept
    // armed with nothing to arm. Not while running: the run ends itself when
    // its stream closes, and lands on ARMED, where this then applies.
    LaunchedEffect(superResOffered, brenizerOffered, brenizerActive, zslWanted, superResPhase) {
        if (superResPhase == SuperResMachine.Phase.ARMED && (!(if (brenizerActive) brenizerOffered else superResOffered) || !zslWanted)) {
            selectMode(CaptureMode.PHOTO)
            status = "capture unavailable on this camera"
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
                    if (sweepExitPending) restoreSweep()
                }
            }
        }
    }

    // Written whenever anything worth remembering changes. Cheap: a dozen
    // short strings, and only on an actual change rather than per frame.
    LaunchedEffect(
        settings, burstFrames, lens, highlightGuard, captureMode, dngEnabled,
        timerSeconds, guides, mosaicPreset, localTone, outputFormat, showHistogram,
        mosaicReturnLens, fullResolution, manualBurst, rememberedBurst, hdrOutput, longExposureSeconds,
        contentCredentials,
    ) {
        AppSettings.save(
            context,
            AppSettings(
                manual = settings,
                manualBurst = manualBurst,
                rememberedManualBurst = rememberedBurst,
                burstFrames = burstFrames,
                // What the user chose, not what Super Res is holding for the
                // moment: the app reopens in an ordinary mode on the lens they
                // were on.
                lensId = (mosaicReturnLens ?: lens)?.cameraId,
                highlightGuard = highlightGuard,
                dngEnabled = dngEnabled,
                captureMode = captureMode,
                timerSeconds = timerSeconds,
                longExposureSeconds = longExposureSeconds,
                guides = guides.name,
                mosaicPreset = mosaicPreset,
                localTone = localTone,
                outputFormat = outputFormat,
                histogram = showHistogram,
                fullResolution = fullResolution,
                hdrOutput = hdrOutput,
                contentCredentials = contentCredentials,
            ),
        )
    }

    val previewSize = remember(zslDecision, characteristics) {
        val d = zslDecision
        val ch = characteristics
        if (d is ZslDecision.Stream && ch != null) capturePreviewSize(ch, d.config)
        else null
    }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val cutoutInsets = WindowInsets.displayCutout
    val navigationInsets = WindowInsets.navigationBarsIgnoringVisibility
    val previewFrameAspect = (if (zslWanted) previewSize else surfaceRequest?.resolution)
        ?.previewAspect(caps?.sensorOrientation ?: 90, view.display.rotation * 90, lens?.facingBack != false)
    val captureGeometry = dev.multiframe.camera.ui.revision05Geometry(viewSize, previewFrameAspect ?: .75f,
        dev.multiframe.camera.ui.CaptureSafeInsets(cutoutInsets.getLeft(density, direction), cutoutInsets.getTop(density),
            cutoutInsets.getRight(density, direction), maxOf(cutoutInsets.getBottom(density), navigationInsets.getBottom(density))),
        with(density) { TitaniumTokens.MinimumTouch.roundToPx() }, with(density) { 320.dp.roundToPx() }, view.display.rotation == Surface.ROTATION_270,
        with(density) { TitaniumTokens.ConsoleMinimumHeight.roundToPx() })
    LaunchedEffect(showSettings, showGallery, showAbout) {
        (view.context.activityForCamera() as? MainActivity)?.setShootingUi(!showSettings && !showGallery && !showAbout)
    }
    DisposableEffect(Unit) { onDispose { (view.context.activityForCamera() as? MainActivity)?.setShootingUi(false) } }
    val nextCanvas = dev.multiframe.camera.pipeline.SuperResPolicy.wantsCanvas(captureMode, digitalZoom,
        superResOffered, lens?.zoomFactor ?: 1f)
    val nextFrames = if (nextCanvas) MosaicPreset.ONE_SHOT_FRAMES else capturePlan?.frames ?: burstFrames

    val measuredTilt = attitude?.takeUnless { it.facingUpOrDown }?.rollDegrees
    val measuredLevel = measuredTilt != null && kotlin.math.abs(measuredTilt) <= TitaniumTokens.LevelToleranceDegrees
    val toggleZebra: () -> Unit = {
        zebraOverride = !zebraEnabled
        Haptics.segment(view)
    }
    val focusAssistAvailable = showPro && rangefinderTab == RangefinderTab.FOCUS &&
        settings.manualFocus && caps?.hasManualFocus == true
    val focusInset = if (focusAssistAvailable && focusAssistOpen)
        FocusAssistInset.fromBounds(previewBounds, focusAssistRect, focusAssistAnchor) else null

    // Camera gestures occupy precisely the photo surface, never the console.
    val photoInput = Modifier
            .semantics { contentDescription = "Viewfinder: tap to focus and meter, pinch to zoom" }
            .pointerInput(zslStream, camera, caps, previewFrameAspect, focusAssistAvailable) {
                detectTapGestures { localOffset ->
                    val imageBounds = previewBounds ?: return@detectTapGestures
                    val offset = imageBounds.topLeft + localOffset
                    val c = caps ?: return@detectTapGestures
                    if (viewSize.width == 0 || viewSize.height == 0) return@detectTapGestures
                    // Map the touch through the preview's actual bounds and
                    // fitted image before asking Camera2 to meter that point.
                    val point = dev.multiframe.camera.ui.imagePoint(previewBounds, offset)
                        ?: return@detectTapGestures
                    val nx = point.x
                    val ny = point.y
                    focusAssistAnchor = point
                    // A manual focus tap chooses the detail for the loupe;
                    // AF/AE triggers cannot move a manually held lens/meter.
                    if (focusAssistAvailable) return@detectTapGestures
                    val stream = zslStream
                    if (stream != null) {
                        val tappedSettings = settings
                        val rotation = view.display.rotation * 90
                        scope.launch(Dispatchers.IO) {
                            val submitted = runCatching {
                                stream.focusAt(nx, ny, tappedSettings, c, rotation)
                            }.onFailure { Log.w(TAG, "focus tap failed", it) }.getOrDefault(false)
                            if (submitted) {
                                withContext(Dispatchers.Main) {
                                    if (zslStream === stream) {
                                        focusPoint = offset
                                        focusAtMillis = System.currentTimeMillis()
                                    }
                                }
                            }
                        }
                    } else {
                        // CameraX exposes metering through its own point
                        // factory, which already knows the preview geometry.
                        camera?.let { cam ->
                            runCatching {
                                val factory = androidx.camera.core.DisplayOrientedMeteringPointFactory(
                                    view.display, cam.cameraInfo, 1f, 1f,
                                )
                                cam.cameraControl.startFocusAndMetering(
                                    androidx.camera.core.FocusMeteringAction.Builder(
                                        factory.createPoint(nx, ny),
                                        androidx.camera.core.FocusMeteringAction.FLAG_AF or
                                            androidx.camera.core.FocusMeteringAction.FLAG_AE,
                                    ).build()
                                )
                                focusPoint = offset
                                focusAtMillis = System.currentTimeMillis()
                            }.onFailure { Log.w(TAG, "focus tap failed", it) }
                        }
                    }
                }
            }
            .pointerInput(zslStream, camera, caps, superResPhase.selected, brenizerActive) {
                detectTransformGestures { _, _, gestureZoom, _ ->
                    if (gestureZoom == 1f) return@detectTransformGestures
                    // Not in Super Res: the dots are computed on the sensor's
                    // frame, which no zoom crops, and the preview is what the
                    // zoom crops. The two would part company by the zoom.
                    if (superResPhase.selected && brenizerActive) return@detectTransformGestures
                    val c = caps ?: return@detectTransformGestures
                    val stream = zslStream
                    if (stream != null) {
                        digitalZoom = (digitalZoom * gestureZoom)
                            .coerceIn(1f, stream.maxZoom)
                        stream.setZoom(digitalZoom, settings, c)
                    } else {
                        camera?.let { cam ->
                            val state = cam.cameraInfo.zoomState.value ?: return@let
                            digitalZoom = (digitalZoom * gestureZoom)
                                .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                            runCatching { cam.cameraControl.setZoomRatio(digitalZoom) }
                        }
                    }
                }
            }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TitaniumTokens.Chassis)
            .onSizeChanged { viewSize = it }

    ) {
        // Optical aids share the image bounds; only the focus target accepts drags.
        if (zslWanted) {
            // The GPU owns the preview texture and composites peaking from
            // that same frame; the buffer's transform supplies orientation.
            // Fit the complete stream to sensor aspect. Portrait owns the
            // screen width; controls remain below its measured image bounds.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .testTag("camera-raw-container")
                    // The complete photo, beside a rail or above a hinge on
                    // larger or folded screens.
                    .revision05Region(captureGeometry.image)
                    .fillMaxSize(),
            ) {
                previewSize?.let { size ->
                    androidx.compose.runtime.key(size, view.display.rotation) {
                        Box(Modifier.fillMaxSize().then(photoInput).testTag("camera-metering-region")) {
                        PeakingPreview(
                            size = size,
                            contentAspect = previewFrameAspect ?: 1f,
                            displayRotationDegrees = view.display.rotation * 90,
                            mirrored = lens?.facingBack == false,
                            peaking = settings.manualFocus && RangefinderOverlayPolicy.peaking(showPro, rangefinderTab),
                            zebra = zebraEnabled,
                            focusInset = focusInset,
                            onHistogram = histogramHandler,
                            onSurface = {
                                zslSurfaceSize = if (it != null) size else null
                                zslSurface = it
                            },
                            modifier = Modifier.fillMaxSize()
                                .clip(RoundedCornerShape(dev.multiframe.camera.ui.TitaniumTokens.RadiusSmall))
                                .border(1.dp, dev.multiframe.camera.ui.TitaniumTokens.HairlineBorder)
                                .testTag("camera-preview")
                                .onGloballyPositioned { previewBounds = it.boundsInRoot() },
                        )
                        }
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize().background(TitaniumTokens.Chassis))
            surfaceRequest?.let { request ->
                // CameraX's SurfaceRequest feeds the same-frame shader as the
                // RAW path, so peaking remains available if the ring falls back.
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .revision05Region(captureGeometry.image)
                        .fillMaxSize(),
                ) {
                    Box(Modifier.fillMaxSize().then(photoInput).testTag("camera-metering-region")) {
                    CameraXPeakingPreview(
                        request = request,
                        contentAspect = previewFrameAspect ?: 1f,
                        displayRotationDegrees = view.display.rotation * 90,
                        peaking = settings.manualFocus && RangefinderOverlayPolicy.peaking(showPro, rangefinderTab),
                        zebra = zebraEnabled,
                        focusInset = focusInset,
                            onHistogram = histogramHandler,
                        modifier = Modifier.fillMaxSize()
                            .clip(RoundedCornerShape(dev.multiframe.camera.ui.TitaniumTokens.RadiusSmall))
                            .border(1.dp, dev.multiframe.camera.ui.TitaniumTokens.HairlineBorder)
                            .testTag("camera-preview")
                            .onGloballyPositioned { previewBounds = it.boundsInRoot() },
                    )
                    }
                }
            }
        }


        // Over the picture and only the picture, turned into its orientation.
        previewBounds?.let { b ->
            val density = LocalDensity.current
            // Landscape HUD is an overlay, not part of image measurement. Keep
            // diagnostics below its fixed bounds without reframing the sensor.
            val aidTopClearance = with(density) {
                (captureGeometry.safe.top + TitaniumTokens.MinimumTouch.toPx() - b.top)
                    .coerceAtLeast(0f).toDp()
            }
            val safeTop = WindowInsets.safeDrawing.getTop(density)
            val safeLeft = WindowInsets.safeDrawing.getLeft(density, LocalLayoutDirection.current)
            // TitaniumReticle owns the horizon. Retain the optional grid, but
            // do not draw the legacy horizon across the new level/snap cue.
            Guides(mode = if (guides.showsGrid) GuideMode.GRID else GuideMode.OFF,
                attitude = attitude, modifier = Modifier
                .offset { IntOffset(b.left.toInt(), b.top.toInt()) }
                .size(with(density) { b.width.toDp() }, with(density) { b.height.toDp() }))

            val landscapeAids = b.width > b.height
            val aidsOnLeft = landscapeAids && captureGeometry.controls.left >= b.right
            val histogramBelowLoupe = landscapeAids && settings.manualFocus &&
                focusAssistAvailable && focusAssistOpen
            if (showHistogram) Box(Modifier.offset { IntOffset(b.left.toInt(), b.top.toInt()) }
                .size(with(density) { b.width.toDp() }, with(density) { b.height.toDp() })) {
                // Keep optional aids in the corner opposite the landscape panel.
                // When both are enabled, stack RGB below the loupe rather than
                // drawing either through the other or through manual controls.
                dev.multiframe.camera.ui.PreviewRgbPlot(previewRgb,
                    Modifier.align(if (landscapeAids && !aidsOnLeft) Alignment.TopEnd else Alignment.TopStart)
                        .offset(x = if (landscapeAids && !aidsOnLeft) (-10).dp else 10.dp,
                            y = aidTopClearance + 10.dp + if (histogramBelowLoupe)
                                8.dp + TitaniumTokens.FocusInsetHeight else 0.dp)
                        .testTag("camera-histogram"))
            }

            TitaniumReticle(
                measuredTilt = measuredTilt,
                topClearance = aidTopClearance,
                focusInsetOnLeft = aidsOnLeft,
                manualFocus = settings.manualFocus,
                focusAssistAvailable = focusAssistAvailable,
                focusAssistOpen = focusAssistOpen,
                focusAnchor = focusAssistAnchor,
                onFocusAnchorChange = { focusAssistAnchor = it },
                onFocusInsetBounds = { focusAssistRect = it },
                modifier = Modifier.offset { IntOffset(b.left.toInt(), b.top.toInt()) }
                    .size(with(density) { b.width.toDp() }, with(density) { b.height.toDp() }),
            )
            if (flashVisible) Box(Modifier.offset { IntOffset(b.left.toInt(), b.top.toInt()) }
                .size(with(density) { b.width.toDp() }, with(density) { b.height.toDp() })
                .background(TitaniumTokens.TelemetryWhite.copy(alpha = 0.55f)))

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
            proOpen = showPro || showWhiteBalance || showCaptureInfo,
            aboutOpen = showAbout,
            mode = if (brenizerActive) "BRENIZER" else captureMode.label,
            // Say what AUTO settled on, not just that it is AUTO.
            resolvedMode = resolvedLabel?.takeIf { zslStream != null },
            // Only offered where the hardware said yes. On a camera that stalls
            // on raw the ring is never offered and nothing changes.
            streamOffered = zslDecision is ZslDecision.Stream,
            // The drawer has something to draw where the sensor takes a manual
            // exposure or the lens a manual focus.
            manualOffered = caps?.let { it.hasManualSensor || it.hasManualFocus || it.hasManualColor } == true,
            superResOffered = superResOffered,
            superRes = superResPhase,
        )

        fun setPro(enabled: Boolean) {
            // Visibility is independent of the persistent capture profile.
            showPro = enabled
            showWhiteBalance = false
            Haptics.segment(view)
        }

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
                    manualBurst = true
                    rememberedBurst = true
                }
                ControlBar.DNG -> dngEnabled = !dngEnabled
                ControlBar.PRO -> setPro(!showPro)
                ControlBar.TIMER -> timerSeconds = ControlBar.nextTimer(timerSeconds)
                ControlBar.GUIDES -> guides = guides.next()
                ControlBar.HISTOGRAM -> showHistogram = !showHistogram
                ControlBar.SETTINGS -> showSettings = !showSettings
                ControlBar.ABOUT -> showAbout = true
                ControlBar.STOP -> stopSuperRes()

            }
        }

        CompositionLocalProvider(LocalHandRotation provides layout.handRotation) {

            dev.multiframe.camera.ui.Revision05TopDeck(
                geometry = captureGeometry, histogramVisible = showHistogram,
                onSettings = { showLabsOnOpen = false; showSettings = true },
                onHistogram = { showHistogram = !showHistogram },
                resolutionControl = {
                    caps?.takeIf {
                        it.supportsFullResolution && zslStream != null &&
                            lens?.let { selected -> lensProfiles[selected.cameraId]?.supportsNativeHighRes } == true
                    }?.let { c ->
                        dev.multiframe.camera.ui.TitaniumResolutionSelector(
                            full = fullResolution,
                            binnedMegapixels = c.rawMegapixels,
                            fullMegapixels = c.fullResolutionMegapixels,
                            enabled = !busy && developing == 0 && !superResPhase.selected,
                            onFull = { wanted ->
                                if (wanted != fullResolution) {
                                    Haptics.segment(view)
                                    fullResolution = wanted
                                    status = "switching to %.0f MP…".format(
                                        if (wanted) c.fullResolutionMegapixels else c.rawMegapixels)
                                }
                            },
                        )
                    } ?: Text("Burst: $nextFrames frames\n${if (settings.manualExposure || settings.manualFocus || manualBurst) "Manual" else "Auto"} · DNG: ${if (dngEnabled && caps?.supportsRaw == true) "On" else "Off"}",
                        style = TitaniumTokens.Control, maxLines = 2,
                        color = if (settings.manualExposure || settings.manualFocus || manualBurst) TitaniumTokens.OpticalAmber else TitaniumTokens.TelemetryWhite,
                        modifier = Modifier.testTag("revision05-compact-summary"))
                },            )

        Column(Modifier.fillMaxSize()) {
            val compactConsole = viewSize.width > viewSize.height
            val drawerOpen = showPro && caps != null
            val torchAvailable = caps?.hasFlashUnit == true &&
                (if (zslWanted) zslStream?.lensId == lens?.cameraId else camera != null)
            val rearLenses = remember(lenses) { LensCatalog.rear(lenses) }
            val frontLens = remember(lenses) { lenses.filterNot { it.facingBack }.maxByOrNull { it.sensorArea } }
            val targets = listOf(.5f, 1f, 2f, 5f, 10f)
            val selectedTurret = targets.indices.minByOrNull { index ->
                kotlin.math.abs(targets[index] - (lens?.zoomFactor ?: 1f) * digitalZoom)
            } ?: 1
                // The ZSL shutter, for the ordinary modes and the one-shot
                // alike. The frames are locked in microseconds on this thread
                // -- they were exposed before the press, so there is nothing
                // to wait for -- then the haptic, with the frames a haptic
                // could blur already in the ring's hand, and the shot goes to
                // the develop queue. The settings are read at the press. With
                // a timer the countdown runs first, busy for the countdown
                // only: it is what a second press cancels.
                fun takeZsl(stream: ZslRawStream, frames: Int, label: String, canvas2x: Boolean) {
                    val rot = caps?.let { orientation.captureRotation(it.sensorOrientation, lens?.facingBack != false) } ?: 0
                    val lensProfile = lensProfiles[stream.lensId]
                    val params = DevelopParams(
                        localTone = if (localTone) DevelopParams.LOCAL_TONE_ON else 0f,
                        shadowLift = if (localTone) DevelopParams.SHADOW_LIFT_ON else 0f,
                        hdrHeadroom = if (hdrOutput && caps?.supportsHdrOutput == true) DevelopParams.HDR_HEADROOM else 0f,
                    )
                    val dng = dngEnabled
                    val format = outputFormat

                    val shotTimerSeconds = timerSeconds
                    val currentCaps = caps
                    // NIGHT's plan is a still burst: it starts at the press,
                    // at the planned exposure, never in the repeating
                    // request. PRO's manual exposure outranks it, and the
                    // Super Res canvas is ZSL's.
                    val night = capturePlan?.night?.takeIf {
                        !canvas2x && currentCaps != null && !settings.manualExposureActive(currentCaps)
                    }
                    val stillSettings = when {
                        currentCaps == null -> null
                        night != null -> settings.copy(manualExposure = true, iso = night.iso, exposureTimeNs = night.exposureNs)
                        settings.manualExposureActive(currentCaps) &&
                            settings.effectiveExposureTimeNs(currentCaps) > 33_333_333L -> settings
                        else -> null
                    }
                    // NIGHT's long exposure: the stream's frames for the
                    // chosen span, averaged with rejection off. It merges as
                    // it captures, so it does not go through the develop
                    // queue, and it waits for that queue to be empty: the
                    // accumulator and a queued merge would hold two copies.
                    val longSeconds = longExposureSeconds.takeIf { captureMode == CaptureMode.NIGHT && !canvas2x } ?: 0
                    if (longSeconds > 0) {
                        if (busy || lockPending || developing > 0) {
                            Haptics.reject(view)
                            status = if (developing > 0) "long exposure waits for $developing developing" else "busy — a moment"
                            return
                        }
                        busy = true
                        val steady = capturePlan?.night?.motion == dev.multiframe.camera.pipeline.MotionClass.TRIPOD
                        captureJob = scope.launch {
                            try {
                                for (remaining in shotTimerSeconds downTo 1) {
                                    countdown = remaining; status = "$remaining seconds · timer"
                                    kotlinx.coroutines.delay(1000)
                                }
                                countdown = 0
                                status = "Long exposure · 0 / $longSeconds s"
                                val r = withContext(Dispatchers.Default) {
                                    dev.multiframe.camera.pipeline.LongExposureCapture.capture(
                                        context, stream, longSeconds * 1000L, steady, rot,
                                        lensProfile = lensProfile, outputFormat = format,
                                        develop = params, writeDng = dng,
                                    ) { elapsed, n ->
                                        status = "Long exposure · %.1f / %d s · %d frames".format(
                                            displayLocale, elapsed / 1000f, longSeconds, n)
                                    }
                                }
                                // After the last frame, never during: the motor would be in the picture.
                                Haptics.mechanicalRelease(context)
                                Log.i(TAG, "long exposure result: $r")
                                val saved = dev.multiframe.camera.pipeline.CaptureSaveFeedback.forOutputs(
                                    r.savedJpeg != null, r.savedDng != null, dng)
                                status = saved.message ?: "Long exposure · ${r.framesCaptured} frames · ${r.burstSpanMillis / 1000f} s"
                                if (saved.hasSavedOutput) shotsWritten++
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                status = "Long exposure cancelled"
                                throw e
                            } catch (e: Exception) {
                                status = "Long exposure failed: ${e.message}"
                            } finally { busy = false; countdown = 0; captureJob = null }
                        }
                        return
                    }
                    if (currentCaps != null && stillSettings != null) {
                        if (busy || lockPending) return
                        if (stream.lockedFrames() + frames > stream.ringCapacity) {
                            Haptics.reject(view)
                            status = "ring full: $developing developing — a moment"
                            return
                        }
                        busy = true
                        captureJob = scope.launch {
                            try {
                                for (remaining in shotTimerSeconds downTo 1) {
                                    countdown = remaining; status = "$remaining seconds · timer"
                                    kotlinx.coroutines.delay(1000)
                                }
                                countdown = 0
                                status = if (night != null) "NIGHT: $frames × ${NightPlanner.shutterLabel(night.exposureNs, displayLocale)}"
                                    else "Capturing $frames long exposures"
                                val shot = CaptureFeedback.afterSuspending(
                                    capture = { stream.captureStillBurst(stillSettings, currentCaps, frames) },
                                    successful = { it.count > 0 },
                                    vibrate = { Haptics.mechanicalRelease(context) },
                                )
                                val processingId = ++nextProcessingId
                                if (developing == 0) mergeProgressFlow.value = MergeUiProgress(processingId, 0, shot.count)
                                developing++
                                val queued = developQueue.trySend(PendingShot(processingId, shot, stream, rot, lensProfile, format, canvas2x, params, dng,
                                    plan = night?.reading(displayLocale), outputTag = if (night != null) "night" else "zsl"))
                                if (queued.isFailure) {
                                    shot.close()
                                    developing--
                                    if (mergeProgressFlow.value?.shotId == processingId) mergeProgressFlow.value = null
                                }
                            } catch (e: Exception) {
                                status = "Still capture failed: ${e.message}"
                            } finally { busy = false; countdown = 0; captureJob = null }
                        }
                        return
                    }

                    fun lockAndQueue() {
                        // A press never asks for more than the ring can lock at once.
                        val wanted = minOf(frames, stream.maxBurst)
                        val shot = CaptureFeedback.afterSync(
                            capture = { ZslCapture.take(stream, frames) },
                            successful = { it.count == wanted },
                            vibrate = { Haptics.mechanicalRelease(context) },
                        )
                        // The snapshot takes what is ready, and what is ready
                        // can be less than the press asked for -- a timer that
                        // ran out while the ring was refilling, say. A short
                        // burst is not the photograph the shutter promises.
                        if (shot.count < wanted) {
                            shot.close()
                            Haptics.reject(view)
                            status = if (canvas2x) "super res needs ${MosaicPreset.ONE_SHOT_FRAMES} ready frames"
                                else "ring has %d of %d frames: a moment".format(shot.count, wanted)
                            return
                        }
                        val processingId = ++nextProcessingId
                        if (developing == 0) mergeProgressFlow.value = MergeUiProgress(processingId, 0, shot.count)
                        developing++
                        status = "%s: %d frames".format(label, shot.count)
                        val queued = developQueue.trySend(
                            PendingShot(processingId, shot, stream, rot, lensProfile, format, canvas2x, params, dng),
                        )
                        if (queued.isFailure) {
                            shot.close()
                            developing--
                            if (mergeProgressFlow.value?.shotId == processingId) mergeProgressFlow.value = null
                        }
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
                            if (stream.readyFrames() < frames) {
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
                        for (remaining in shotTimerSeconds downTo 1) {
                            countdown = remaining
                            // A metronome you can feel without watching the
                            // screen, which is the point of a self-timer. The
                            // last second is silent: the frames the timer
                            // locks are exposed in it.
                            if (remaining > 1) Haptics.tick(view)
                            status = "$remaining… tap the shutter to cancel"
                            kotlinx.coroutines.delay(1000)
                        }
                        countdown = 0
                        busy = false
                        lockAndQueue()
                    }
                }


            dev.multiframe.camera.ui.Revision05Console(
                geometry = captureGeometry,
                landscape = viewSize.width > viewSize.height,
                showPro = drawerOpen,
                showWhiteBalance = showWhiteBalance,
                showInfo = showCaptureInfo,
                manual = settings.manualExposure || settings.manualFocus || manualBurst,
                manualAvailable = caps?.hasManualSensor == true,
                mode = captureMode,
                controlsEnabled = !busy && !lockPending && superResPhase.controlsEnabled,
                timerSeconds = timerSeconds,
                torchAvailable = torchAvailable,
                torchOn = torchOn,
                frontAvailable = frontLens != null && rearLenses.isNotEmpty(),
                front = lens?.facingBack == false,
                focusAssistAvailable = focusAssistAvailable,
                focusAssistOpen = focusAssistOpen,
                wbActive = settings.whiteBalanceMode != dev.multiframe.camera.pipeline.WhiteBalanceMode.AUTO || settings.manualColor || settings.awbMode != android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO,
                wbLabel = settings.whiteBalanceLabel(),
                onFocusAssist = { focusAssistOpen = !focusAssistOpen },
                summary = dev.multiframe.camera.pipeline.NextCaptureSummary.of(settings, nextFrames,
                    dngEnabled && caps?.supportsRaw == true, digitalZoom, superResOffered, displayLocale) +
                    (lens?.let { " · Camera: ${it.label} · Sensor crop: %.1f×".format(displayLocale, digitalZoom) } ?: ""),
                status = status,
                nightReading = capturePlan?.night?.takeIf { !settings.manualExposure }?.reading(displayLocale),
                previewReading = "Preview: ISO ${gauges.iso} · ${if(gauges.exposureNs > 0) ManualSettings(exposureTimeNs = gauges.exposureNs).shutterLabel(displayLocale) else "—"} · ${gauges.cct} K",
                mergeProgress = mergeProgress,
                onPro = { showWhiteBalance = false; showCaptureInfo = false; setPro(!showPro) },
                onWhiteBalance = { showWhiteBalance = !showWhiteBalance; showPro = false; showCaptureInfo = false },
                onInfo = { showCaptureInfo = !showCaptureInfo; showPro = false; showWhiteBalance = false },
                onTimer = { handleControl(ControlBar.TIMER) },
                // NIGHT only, and only on a stream: the long exposure is the
                // ring's frames averaged, so it needs the ring.
                longExposureSeconds = longExposureSeconds.takeIf { captureMode == CaptureMode.NIGHT && zslStream != null },
                onLongExposure = {
                    longExposureSeconds = dev.multiframe.camera.pipeline.LongExposureCapture.next(longExposureSeconds)
                    Haptics.segment(view)
                },
                onTorch = { torchOn = !torchOn; Haptics.segment(view) },
                onFront = {
                    val target = if (lens?.facingBack == false)
                        rearLenses.firstOrNull { it.cameraId == rearReturnLensId } ?: LensCatalog.default(rearLenses)
                    else frontLens
                    target?.let { next ->
                        if (lens?.facingBack != false) { rearReturnLensId = lens?.cameraId; rearReturnZoom = digitalZoom }
                        digitalZoom = if (next.facingBack) rearReturnZoom else 1f
                        torchOn = false; lens = next; zslWanted = next.supportsRaw
                    }
                },
                onMode = { selectMode(it) },
                onAuto = { settings = settings.returnToAuto(); manualBurst = false },
                onManual = { settings = settings.restoreManual(caps); manualBurst = rememberedBurst },
                onCancel = {
                    if (superResPhase.inFlight) stopSuperRes()
                    else { captureJob?.cancel(); countdown = 0; busy = false }
                },
                cancellable = countdown > 0 || superResPhase.inFlight || (busy && captureJob?.isActive == true),
                sweep = if (brenizerActive) "Brenizer sweep · ${mosaicView?.let { "Tile ${minOf(it.captured + 1, it.total)} of ${it.total}" } ?: mosaicPreset.label} · ${mosaicView?.let { if (it.steady) "Aligned — hold still" else "Pan toward the target" } ?: "Press shutter to begin"} · Camera: ${lens?.label ?: "—"} · Sensor crop: ${digitalZoom}× · JPEG composite" else null,
                onExitSweep = {
                    val running = superResPhase.inFlight
                    stopSuperRes()
                    if (running) sweepExitPending = true else restoreSweep()
                },
                turret = {
MechanicalTurret(
                selected = selectedTurret,
                available = targets.map { target -> rearLenses.any { it.zoomFactor <= target * 1.22f } },
                frontLabel = lens?.takeUnless { it.facingBack }?.label,
                landscapeCompact = layout.posture == Posture.SIDEBAR,
                enabled = !busy && !lockPending && rearLenses.isNotEmpty(),
                subPixelAvailable = lens?.facingBack == true && zslStream != null && superResOffered,
                superResBadge = zslStream?.takeIf {
                    superResOffered && (selectedTurret == 2 || selectedTurret == 4)
                }?.let { stream ->
                    val stop = if (selectedTurret == 2) "2×" else "10×"
                    // A digital crop does not retain the whole 2x canvas.
                    // Claim megapixels only for a native optical stop with
                    // no additional digital crop; otherwise name the engine.
                    val detail = if (digitalZoom <= 1.001f)
                        "${4L * stream.config.width * stream.config.height / 1_000_000L}MP"
                    else "SR"
                    "$stop✦ $detail"
                },
                onSelect = { index ->
                    val target = targets[index]
                    val nearest = rearLenses.minByOrNull { kotlin.math.abs(it.zoomFactor - target) }
                    val option = nearest?.takeIf { kotlin.math.abs(it.zoomFactor / target - 1f) < .22f }
                        ?: rearLenses.filter { it.zoomFactor <= target }.maxByOrNull { it.zoomFactor }
                    option?.let {
                        if (lens?.cameraId != it.cameraId || kotlin.math.abs(digitalZoom - target / it.zoomFactor) > .05f) {
                            digitalZoom = (target / it.zoomFactor).coerceAtLeast(1f)
                            lens = it
                            zslWanted = it.supportsRaw
                        }
                    }
                },
            )
                },
                shutter = {
MechanicalShutter(
                        enabled = !busy && !lockPending,
                        counting = countdown > 0,
                        frames = nextFrames,
                        captureProgress = mergeProgress?.shutterFraction,
                        onFlash = { flashPulse++ },
                        modifier = Modifier,
                        onPrime = {
                            val activeStream = zslStream
                            val activeCaps = caps
                            val target = focusAssistAnchor
                            if (activeStream != null && activeCaps != null && !busy) {
                                scope.launch(Dispatchers.IO) {
                                    activeStream.focusAt(target.x, target.y, settings, activeCaps,
                                        view.display.rotation * 90)
                                }
                            }
                        },
                        onCapture = {
                            if (countdown > 0) {
                                // Called off before anything was locked.
                                Haptics.reject(view)
                                captureJob?.cancel()
                                captureJob = null
                                countdown = 0
                                busy = false
                                status = "timer cancelled"
                                return@MechanicalShutter
                            }
                            if (busy) return@MechanicalShutter

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
                                if (brenizerActive) {
                                    startSuperRes()
                                } else if (superResPhase == SuperResMachine.Phase.ARMED && stream != null &&
                                    caps?.supportsSuperRes == true
                                ) {
                                    takeZsl(stream, MosaicPreset.ONE_SHOT_FRAMES, "1-SHOT", canvas2x = true)
                                } else {
                                    Haptics.reject(view)
                                }
                                return@MechanicalShutter
                            }

                            val currentCaps = caps
                            if (stream == null && currentCaps != null && settings.manualExposureActive(currentCaps) &&
                                settings.effectiveExposureTimeNs(currentCaps) > dev.multiframe.camera.pipeline.PreviewExposure.FRAME_NS) {
                                zslWanted = lens?.supportsRaw == true
                                status = if (zslWanted) "Reconnecting RAW camera for long exposure" else "Long exposure unavailable on this camera"
                                return@MechanicalShutter
                            }

                            if (stream != null) {
                                val plan = capturePlan
                                val canvas = dev.multiframe.camera.pipeline.SuperResPolicy.wantsCanvas(captureMode, digitalZoom, superResOffered, lens?.zoomFactor ?: 1f)
                                takeZsl(stream, if (canvas) MosaicPreset.ONE_SHOT_FRAMES else plan?.frames ?: burstFrames,
                                    if (canvas) "SUPER RES" else plan?.resolved?.label ?: "ZSL", canvas2x = canvas)
                                return@MechanicalShutter
                            }

                            // Without a ring -- the hardware refused one, or the
                            // memory did -- the sequential path is the camera.
                            // The merge is not a choice here either: a raw
                            // burst through CameraX where the device delivers
                            // raw, and the analysis stream's merge where it
                            // does not.
                            busy = true
                            status = "capturing $burstFrames frames…"
                            val rotation = caps?.let { orientation.captureRotation(it.sensorOrientation, lens?.facingBack != false) } ?: 0
                            val shotFrames = burstFrames
                            val shotLens = lens
                            val shotLensProfile = lens?.let { lensProfiles[it.cameraId] }
                            val shotFormat = outputFormat
                            val shotDevelop = DevelopParams(localTone = if (localTone) DevelopParams.LOCAL_TONE_ON else 0f,
                                shadowLift = if (localTone) DevelopParams.SHADOW_LIFT_ON else 0f,
                                hdrHeadroom = if (hdrOutput && caps?.supportsHdrOutput == true) DevelopParams.HDR_HEADROOM else 0f)
                            val shotDng = dngEnabled
                            val raw = rawCapture
                            val ch = characteristics
                            if (raw != null && ch != null) {
                                scope.launch {
                                    try {
                                    val r = CaptureFeedback.afterSuspending(
                                        capture = { withContext(Dispatchers.Default) {
                                            RawBurstCapture.captureAndMerge(
                                            context = context,
                                            imageCapture = raw,
                                            characteristics = ch,
                                            captureResult = lastCaptureResult.get(),
                                            frameCount = shotFrames,
                                            // The equivalent focal length is the
                                            // catalogue's to know, not the capture
                                            // result's.
                                            lens = shotLens,
                                            lensProfile = shotLensProfile,
                                            outputFormat = shotFormat,
                                            rotationDegrees = rotation,
                                            develop = shotDevelop,
                                            writeDng = shotDng,
                                            frameResults = stillResults,
                                            onProgress = { },
                                            )
                                        } },
                                        successful = { it.stats != null },
                                        vibrate = { Haptics.mechanicalRelease(context) },
                                    )
                                    Log.i(TAG, "raw burst result: $r")
                                    Log.i(
                                        TAG,
                                        ("capture %dms  merge %dms  develop %dms  " +
                                            "write %dms").format(
                                            r.captureMillis, r.mergeMillis,
                                            r.developMillis, r.writeMillis,
                                        ),
                                    )
                                    val saved = dev.multiframe.camera.pipeline.CaptureSaveFeedback.forOutputs(
                                        r.savedJpeg != null, r.savedDng != null, shotDng)
                                    status = saved.message ?: r.stats?.let {
                                        CaptureReadout.of(it.framesMerged, it.meanContribution)
                                    } ?: r.message
                                    if (saved.hasSavedOutput) shotsWritten++
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Sequential capture failed", e)
                                        status = dev.multiframe.camera.pipeline.CaptureSaveFeedback.forOutputs(false,false,shotDng).message!!
                                    } finally { busy = false }
                                }
                                return@MechanicalShutter
                            }

                            scope.launch {
                                try {
                                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                    .format(System.currentTimeMillis())

                                val result = CaptureFeedback.afterSuspending(
                                    capture = { withContext(Dispatchers.Default) {
                                        val frames = buffer.snapshot(shotFrames.coerceAtMost(buffer.capacity))
                                        if (frames.isEmpty()) return@withContext null
                                        val out = Merger.process(frames, mergeEnabled = true)
                                        val bmp = OrientationTracker.rotate(out.bitmap, rotation)
                                        val uri = try {
                                            ImageSaver.saveJpeg(context, bmp, "MF_${stamp}_merged.jpg")
                                        } finally {
                                            bmp.recycle()
                                            if (out.bitmap !== bmp) out.bitmap.recycle()
                                        }
                                        out.stats to uri
                                    } },
                                    successful = { it != null },
                                    vibrate = { Haptics.mechanicalRelease(context) },
                                )

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
                                    val saved = dev.multiframe.camera.pipeline.CaptureSaveFeedback.forOutputs(result.second != null,false,false)
                                    if (saved.hasSavedOutput) shotsWritten++
                                    saved.message ?: CaptureReadout.of(stats.framesUsed, stats.meanContribution)
                                }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.e(TAG,"Sequential image save failed",e)
                                    status = dev.multiframe.camera.pipeline.CaptureSaveFeedback.forOutputs(false,false,false).message!!
                                } finally { busy = false }
                            }
                        },
                    )
                },
                gallery = {
                    LastShotThumb(image = thumbnail, enabled = !busy, modifier = Modifier,
                        onOpen = { Haptics.segment(view); showGallery = true })
                },
                panel = {
                    if (showWhiteBalance || rangefinderTab == RangefinderTab.COLOR) {
                        dev.multiframe.camera.ui.WhiteBalancePanel(settings, caps, onSettings = { settings = it }, previewKelvin = gauges.cct, compact = true)
                    } else {
                        dev.multiframe.camera.ui.Revision05ManualPanel(
                            settings, caps, burstFrames, zslStream?.maxBurst ?: buffer.capacity,
                            rangefinderTab, onTab = { rangefinderTab = it },
                            onSettings = { settings = it }, onFrames = { burstFrames = it; manualBurst = true; rememberedBurst = true },
                            hapticsEnabled = !busy && !lockPending && !superResPhase.inFlight,
                            focusAssistAvailable = focusAssistAvailable,
                            focusAssistOpen = focusAssistOpen,
                            onFocusAssist = { focusAssistOpen = !focusAssistOpen })
                    }
                },
                selectedTab = rangefinderTab,
                onTab = { rangefinderTab = it },
                exposureAvailable = caps?.let { it.hasManualSensor || it.supportsExposureCompensation } == true,
                focusAvailable = caps?.hasManualFocus == true,
            )
        }
        } // LocalHandRotation

        focusPoint?.let { point ->
            var visible by remember(focusAtMillis) { mutableStateOf(true) }
            LaunchedEffect(focusAtMillis) {
                kotlinx.coroutines.delay(1400)
                visible = false
            }
            if (visible) {
                dev.multiframe.camera.ui.TitaniumFocusPointReticle(point)
            }
        }

        if (brenizerActive) mosaicView?.let { v ->
            previewBounds?.let { b ->
                dev.multiframe.camera.ui.Revision05SweepMarker(v, caps?.sensorOrientation ?: 90,
                    zslStream?.zoom ?: 1f, Modifier.offset { IntOffset(b.left.toInt(), b.top.toInt()) }
                        .size(with(density) { b.width.toDp() }, with(density) { b.height.toDp() }))
            }
        }

        if (showSettings) {
            SettingsScreen(
                chips = ControlBar.settings(barState),
                onControl = handleControl,
                openLabsInitially = showLabsOnOpen,
                onDismiss = {
                    Haptics.segment(view)
                    showSettings = false
                    showLabsOnOpen = false
                },
                viewfinder = {
                    dev.multiframe.camera.ui.ServiceRow("Zebra highlights", if(zebraEnabled) "ON" else "OFF",
                        detail = "Near-clipped highlights in the rendered preview; RAW may retain more headroom.",
                        active = zebraEnabled, onClick = toggleZebra)
                },
                labs = {
                    Column {
                        if (brenizerOffered) {
                        dev.multiframe.camera.ui.BrenizerLabs(
                            preset = mosaicPreset,
                            megapixels = presetMegapixels,
                            enabled = !busy && superResPhase.controlsEnabled,
                            onPreset = { mosaicPreset = it },
                            onLaunch = {
                                sweepReturn = dev.multiframe.camera.pipeline.CaptureConfigurationSnapshot(settings,
                                    captureMode, burstFrames, manualBurst, timerSeconds, lens?.cameraId, digitalZoom, showPro, fullResolution)
                                mosaicReturnLens = lens
                                brenizerActive = true
                                superResPhase = SuperResMachine.Phase.ARMED
                                zslWanted = true
                                showSettings = false
                            },
                        )
                        }
                    }
                },
            ) {
                dev.multiframe.camera.ui.ServiceCaptureSettings(
                    caps = caps,
                    highlightGuard = if (zslStream != null) highlightGuard else null,
                    onHighlightGuard = { if (!busy) highlightGuard = it },
                    // Only with a stream to accumulate from: the 2x path is
                    // a property of the ZSL burst.
                    localTone = localTone,
                    onLocalTone = { if (!busy) localTone = it },
                    outputFormat = if (HeifEncoder.isAvailable) outputFormat else null,
                    onOutputFormat = { if (!busy) outputFormat = it },
                    // Absent below API 34, and while the pictures are HEIF,
                    // which is written without a gain map.
                    hdrOutput = if (caps?.supportsHdrOutput == true && outputFormat == OutputFormat.JPEG) hdrOutput else null,
                    onHdrOutput = { if (!busy) hdrOutput = it },
                    contentCredentials = if (outputFormat == OutputFormat.JPEG) contentCredentials else null,
                    onContentCredentials = { if (!busy) contentCredentials = it },
                    onReset = {
                        // Back to what the app ships with. The save effect above
                        // is watching every one of these, so persisting it needs
                        // no separate step.
                        val fresh = AppSettings()
                        showPro = false
                        longExpSelected = false
                        settings = fresh.manual
                        burstFrames = fresh.burstFrames
                        manualBurst = fresh.manualBurst
                        rememberedBurst = fresh.rememberedManualBurst
                        highlightGuard = fresh.highlightGuard
                        dngEnabled = fresh.dngEnabled
                        if (superResPhase.controlsEnabled) selectMode(fresh.captureMode)
                        timerSeconds = fresh.timerSeconds
                        longExposureSeconds = fresh.longExposureSeconds
                        guides = GuideMode.OFF
                        mosaicPreset = fresh.mosaicPreset
                        localTone = fresh.localTone
                        outputFormat = fresh.outputFormat
                        hdrOutput = fresh.hdrOutput
                        contentCredentials = fresh.contentCredentials
                        showHistogram = fresh.histogram
                        fullResolution = fresh.fullResolution
                        zebraOverride = null
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
            LightboxScreen(context = context, onDismiss = { showGallery = false })
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
            proOpen = showPro || showWhiteBalance || showCaptureInfo,
            superRes = superResPhase,
        )
        BackHandler(enabled = BackNavigation.intercepts(backScreen)) {
            when (BackNavigation.resolve(backScreen)) {
                BackNavigation.Action.CLOSE_ABOUT -> showAbout = false
                BackNavigation.Action.CLOSE_GALLERY -> showGallery = false
                BackNavigation.Action.CLOSE_SETTINGS -> showSettings = false
                BackNavigation.Action.CLOSE_PRO -> { setPro(false); showWhiteBalance = false; showCaptureInfo = false }
                BackNavigation.Action.STOP_SUPER_RES -> {
                    Haptics.segment(view)
                    if (brenizerActive) sweepExitPending = true
                    stopSuperRes()
                }
                BackNavigation.Action.LEAVE_SUPER_RES -> {
                    Haptics.segment(view)
                    if (brenizerActive) restoreSweep() else selectMode(CaptureMode.PHOTO)
                }
                BackNavigation.Action.WAIT -> Unit
                BackNavigation.Action.EXIT -> Unit
            }
        }
    }
}

/**
 * The last shot, which is also the way into the gallery.
 *
 * With no recent thumbnail, the same slot shows a proof-sheet glyph. The
 * gallery remains reachable when its last-shot pointer is empty or stale.
 */
@Composable
private fun LastShotThumb(
    image: android.graphics.Bitmap?,
    enabled: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(54.dp)
            .clip(RoundedCornerShape(dev.multiframe.camera.ui.TitaniumTokens.RadiusPlate))
            .background(TitaniumTokens.PlateSurface)
            .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder, RoundedCornerShape(TitaniumTokens.RadiusPlate))
            .clickable(enabled = enabled, role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = if (image == null) "Open gallery" else "Last shot" }
            .testTag("rangefinder-gallery-entry"),
    ) {
        UprightInHand(Modifier.fillMaxSize()) {
            if (image != null) {
                androidx.compose.foundation.Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Canvas(Modifier.fillMaxSize().padding(11.dp)) {
                    val stroke = TitaniumTokens.Hairline.toPx()
                    drawRect(TitaniumTokens.TitaniumM6Dark, style = Stroke(stroke))
                    drawCircle(TitaniumTokens.TitaniumM6, radius = size.minDimension * .09f,
                        center = Offset(size.width * .72f, size.height * .29f))
                    drawLine(TitaniumTokens.TitaniumM6, Offset(size.width * .13f, size.height * .73f),
                        Offset(size.width * .40f, size.height * .45f), stroke)
                    drawLine(TitaniumTokens.TitaniumM6, Offset(size.width * .40f, size.height * .45f),
                        Offset(size.width * .83f, size.height * .82f), stroke)
                }
            }
        }
    }
}

/** The HUD stays over the viewfinder: inset from a rail, never over it. */
private fun Modifier.hudRegion(layout: CameraLayout): Modifier = when (layout.posture) {
    Posture.SIDEBAR -> this.padding(end = layout.railWidth)
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
private fun Size.previewAspect(sensorOrientation: Int, displayRotation: Int, facingBack: Boolean): Float {
    val turn = (sensorOrientation + (if (facingBack) -displayRotation else displayRotation) + 360) % 360
    return if (turn % 180 == 0) width.toFloat() / height else height.toFloat() / width
}

/** Over the frames' own exposure time, how long a press waits for the ring to refill. */
private const val REFILL_GRACE_MILLIS = 150L

private class PendingShot(
    val processingId: Long,
    val shot: ZslCapture.Shot,
    val stream: ZslRawStream,
    val rotation: Int,
    val lensProfile: LensProfile?,
    val outputFormat: OutputFormat,
    val canvas2x: Boolean,
    val develop: DevelopParams,
    val writeDng: Boolean,
    /** The plan that chose the exposure, in words, for the photograph's report: NIGHT's hand. */
    val plan: String? = null,
    /** The file tag: "night" for NIGHT's planned burst, "zsl" otherwise. */
    val outputTag: String = "zsl",
)

private fun android.content.Context.activityForCamera(): android.app.Activity? = when(this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.activityForCamera()
    else -> null
}
