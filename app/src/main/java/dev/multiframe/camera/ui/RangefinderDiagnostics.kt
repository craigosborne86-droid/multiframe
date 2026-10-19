package dev.multiframe.camera.ui

import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver

// Render-state observables for deterministic Compose animation tests. They
// describe the same state the Canvas/graphics layers draw, not a second model.
internal val DialTravelPx = SemanticsPropertyKey<Float>("DialTravelPx")
internal var SemanticsPropertyReceiver.dialTravelPx by DialTravelPx
internal val DialOffsetPx = SemanticsPropertyKey<Float>("DialOffsetPx")
internal var SemanticsPropertyReceiver.dialOffsetPx by DialOffsetPx
internal val DialTickStepPx = SemanticsPropertyKey<Float>("DialTickStepPx")
internal var SemanticsPropertyReceiver.dialTickStepPx by DialTickStepPx
internal val DialWidestTickLabelPx = SemanticsPropertyKey<Float>("DialWidestTickLabelPx")
internal var SemanticsPropertyReceiver.dialWidestTickLabelPx by DialWidestTickLabelPx

internal val TurretHighlightIndex = SemanticsPropertyKey<Float>("TurretHighlightIndex")
internal var SemanticsPropertyReceiver.turretHighlightIndex by TurretHighlightIndex
internal val ModeIndicatorIndex = SemanticsPropertyKey<Float>("ModeIndicatorIndex")
internal var SemanticsPropertyReceiver.modeIndicatorIndex by ModeIndicatorIndex

internal val ShutterScale = SemanticsPropertyKey<Float>("ShutterScale")
internal var SemanticsPropertyReceiver.shutterScale by ShutterScale
internal val ShutterProgress = SemanticsPropertyKey<Float>("ShutterProgress")
internal var SemanticsPropertyReceiver.shutterProgress by ShutterProgress
internal val ShutterDurationMs = SemanticsPropertyKey<Int>("ShutterDurationMs")
internal var SemanticsPropertyReceiver.shutterDurationMs by ShutterDurationMs

internal val ReticleTilt = SemanticsPropertyKey<Float>("ReticleTilt")
internal var SemanticsPropertyReceiver.reticleTilt by ReticleTilt
internal val GalleryScale = SemanticsPropertyKey<Float>("GalleryScale")
internal var SemanticsPropertyReceiver.galleryScale by GalleryScale
internal val GalleryOffset = SemanticsPropertyKey<androidx.compose.ui.geometry.Offset>("GalleryOffset")
internal var SemanticsPropertyReceiver.galleryOffset by GalleryOffset
internal val GalleryFrameSize = SemanticsPropertyKey<androidx.compose.ui.unit.IntSize>("GalleryFrameSize")
internal var SemanticsPropertyReceiver.galleryFrameSize by GalleryFrameSize
internal val GalleryImageSize = SemanticsPropertyKey<androidx.compose.ui.unit.IntSize>("GalleryImageSize")
internal var SemanticsPropertyReceiver.galleryImageSize by GalleryImageSize

internal object RangefinderOverlayPolicy {
    fun peaking(drawerOpen: Boolean, tab: RangefinderTab): Boolean =
        drawerOpen && tab == RangefinderTab.FOCUS
}

/** Read-only production-screen observations used by debug device acceptance.
 * These reference the real camera results and merge state, not simulated controls.
 * Release builds never populate them, and disposal releases the camera reference.
 */
internal object CaptureUiInspection {
    @Volatile var stream: dev.multiframe.camera.pipeline.ZslRawStream? = null
        internal set
    @Volatile var merge: MergeUiProgress? = null
        internal set
    @Volatile var manual: dev.multiframe.camera.pipeline.ManualSettings? = null
        internal set
    @Volatile var frames: Int = 0
        internal set
}
