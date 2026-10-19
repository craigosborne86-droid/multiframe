package dev.multiframe.camera.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One palette, type scale and geometry for the Titanium Rangefinder UI. */
object TitaniumTokens {
    val Chassis = Color(0xFF0A0A0C)
    val PlateSurface = Color(0xFF121316)
    val HairlineBorder = Color(0xFF22242B)
    val TitaniumM6 = Color(0xFFC8C2B8)
    val TitaniumM6Dark = Color(0xFF8C867C)
    val TelemetryWhite = Color(0xFFE6E6EB)
    val TelemetryDim = Color(0xFF7A7E8C)
    val OpticalAmber = Color(0xFFFF9E1B)
    val PhosphorGreen = Color(0xFF00E599)
    val SignalCyan = Color(0xFF00D2FF)
    val ProofVelvet = Color(0xFF060708)
    val FrameLine = Color(0xFF3A3E4D)
    val Danger = Color(0xFFE85B5B)
    // The highlight warning on the photograph: a red no amber reading or
    // danger action shares, so a blown sky is never mistaken for a value.
    val ClippingRed = Color(0xFFFF2D2D)

    val ConsoleFinish = Brush.verticalGradient(listOf(TitaniumM6Dark.copy(alpha=.12f),PlateSurface,Chassis))
    val ControlFinish = Brush.verticalGradient(listOf(TitaniumM6.copy(alpha=.10f),PlateSurface,Chassis))
    val PortraitFooter = 112.dp
    val LandscapeFooter = 144.dp
    val Hairline = 1.dp
    val RadiusSmall = 2.dp
    val RadiusPlate = 4.dp
    val MinimumTouch = 48.dp
    val ShutterDiameter = 84.dp
    val ConsoleMinimumHeight = MinimumTouch * 3 + ShutterDiameter
    val CaptureStatusHeight = 24.dp
    val FocusInsetWidth = 128.dp
    val FocusInsetHeight = 96.dp
    val TurretHeight = 45.dp
    val ConsoleHeight = 322.dp
    // The specified Titanium horizon tolerance, shared by the reticle and HUD.
    const val LevelToleranceDegrees = 0.5f

    val Technical = FontFamily.Monospace
    val Readable = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp,
        lineHeight = 19.sp, color = TelemetryWhite)
    val ReadableSmall = Readable.copy(fontSize = 12.sp, lineHeight = 16.sp)
    val SelectedValue = TextStyle(fontFamily = Technical, fontSize = 26.sp,
        fontWeight = FontWeight.Medium, color = OpticalAmber)
    val HistogramRed = Color(0xFFFF9292)
    val HistogramGreen = Color(0xFFA3E5A8)
    val HistogramBlue = Color(0xFF9BC8FF)
    val Tracking = 1.2.sp

    private fun mono(size: Int, weight: FontWeight = FontWeight.Medium, color: Color = TelemetryWhite) =
        TextStyle(fontFamily = Technical, fontSize = size.sp, fontWeight = weight,
            letterSpacing = Tracking, color = color)

    val Gauge = mono(13, color = OpticalAmber)
    val GaugeLarge = mono(26, FontWeight.SemiBold, OpticalAmber)
    val GaugeCompact = mono(17, FontWeight.SemiBold, OpticalAmber)
    val GaugeBone = mono(13)
    val Label = mono(11, color = TelemetryDim)
    val Control = mono(12)
    val Section = mono(10, color = TelemetryDim)
    val Status = mono(12)
    val Countdown = mono(76)
    val Instruction = mono(10, color = TelemetryDim)
    val Action = mono(12, FontWeight.SemiBold)
    val SliderLabel = mono(11, color = TelemetryDim)
    val SliderValue = mono(13, color = OpticalAmber)
    val Unit = mono(11, color = TelemetryDim)
    val Indicator = mono(11, FontWeight.SemiBold)
    val Dot = mono(13, color = TelemetryDim)
    val Spec = mono(11, color = TelemetryDim).copy(lineHeight = 15.sp)
    val IntroBody = mono(16).copy(lineHeight = 24.sp)
    val LensFocal = mono(13)
    val LensZoom = mono(10, color = TelemetryDim)
    val Mode = mono(13, color = TelemetryDim)
    val ModeSelected = mono(13, FontWeight.SemiBold)
    val Badge = mono(10, color = OpticalAmber)
    val Reset = mono(11, color = TelemetryDim)
}
