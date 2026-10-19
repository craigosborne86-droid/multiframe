package dev.multiframe.camera.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.multiframe.camera.pipeline.*

/** Declared sensor-free fixture of the production console, never sensor evidence. */
@Composable
internal fun Revision05ConsoleFixture(
    lens: Int = 1, onLens: (Int) -> Unit = {},
    mode: CaptureMode = CaptureMode.PHOTO, onMode: (CaptureMode) -> Unit = {},
    open: Boolean = false, onPro: () -> Unit = {}, enabled: Boolean = true,
    manual: Boolean = false, caps: CameraCapabilities? = null,
    tab: RangefinderTab = RangefinderTab.ISO, onTab: (RangefinderTab) -> Unit = {},
    settings: ManualSettings = ManualSettings(), onSettings: (ManualSettings) -> Unit = {},
    torch: Boolean = false, onTorch: () -> Unit = {}, onSettingsOpen: () -> Unit = {},
    info: Boolean = false, summary: String = "Burst: 8 frames · Camera: test", previewReading: String = "",
    resolutionControl: @Composable () -> Unit = {},
    status: String = "", progress: MergeUiProgress? = null,
    sweep: String? = null, onExitSweep: () -> Unit = {}, onCapture: () -> Unit = {},
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val size = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
        val geometry = with(density) { revision05Geometry(size, .75f, CaptureSafeInsets(),
            48.dp.roundToPx(), 240.dp.roundToPx(), false, TitaniumTokens.ConsoleMinimumHeight.roundToPx()) }
        Box(Modifier.revision05Region(geometry.image).testTag("fixture-preview"))
        Revision05TopDeck(geometry, false, onSettingsOpen, {}, resolutionControl)
        Revision05Console(geometry, false, open, false, info, manual,
            caps?.hasManualSensor == true, mode, enabled, settings.whiteBalanceMode != WhiteBalanceMode.AUTO || settings.manualColor || settings.awbMode != 1, settings.whiteBalanceLabel(), false, false, {},
            0, true, torch, true, false, summary, status, previewReading, progress,
            onPro, {}, {}, {}, onTorch, {}, onMode, {}, {}, {}, false, sweep, onExitSweep,
            turret = { MechanicalTurret(lens, enabled, onLens) },
            shutter = { InstrumentButton("Shutter", "Take photograph", false, enabled,
                "persistent-shutter", onCapture) },
            gallery = { InstrumentButton("Gallery", "Open gallery", false, true, "fixture-gallery", {}) },
            panel = { if (tab == RangefinderTab.COLOR) WhiteBalancePanel(settings, caps, onSettings)
                else Revision05ManualPanel(settings, caps, 8, FrameCountPolicy.MAX_FRAMES,
                tab, onTab, onSettings, {}, false) }, selectedTab = tab, onTab = onTab)
    }
}
