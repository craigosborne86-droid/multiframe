package dev.multiframe.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

/** A flush instrument plate; it never draws on the photo underneath. */
@Composable
fun TitaniumTelemetryDeck(
    gauges: GaugeState,
    apertureF: Float?,
    rawDng: Boolean,
    onLabs: () -> Unit,
    status: String,
    modifier: Modifier = Modifier,
    landscapeCompact: Boolean = false,
    totalZoom: Float = 1f,
    resolutionControl: @Composable () -> Unit = {},
) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
        val iso = if (gauges.iso > 0) gauges.iso.toString() else "—"
        val shutter = if (gauges.exposureNs > 0L) shutterLabel(gauges.exposureNs, locale) else "—"
        val aperture = apertureF?.let { "f/%.2f".format(locale, it) } ?: "f/—"
        val ev = gauges.evLabel?.let { "${it}EV" } ?: "±%.1fEV".format(locale, 0f)
        val kelvin = if (gauges.cct > 0) "${gauges.cct}K" else "—K"
        val reading = "ISO $iso · $shutter · $aperture · $ev · $kelvin"
    Column(modifier.fillMaxWidth()
        .background(TitaniumTokens.PlateSurface)
        .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder)
        .statusBarsPadding()
        .padding(horizontal = 10.dp, vertical = 3.dp)
        .testTag("titanium-telemetry-deck")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(if (rawDng) "[ RAW+DNG ]" else "[ JPEG ]",
                    style = TitaniumTokens.Section, color = TitaniumTokens.TelemetryWhite,
                    modifier = Modifier.testTag("titanium-raw-badge"))
                Text("ZOOM / %.1f×".format(locale, totalZoom), style = TitaniumTokens.Section,
                    color = if (totalZoom >= 2f) TitaniumTokens.OpticalAmber else TitaniumTokens.TelemetryDim,
                    modifier = Modifier.testTag("titanium-zoom-readout"))
            }
            if (landscapeCompact) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { resolutionControl() }
            else Column(Modifier.weight(1f).height(48.dp).padding(horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                resolutionControl()
                Text(reading, style = TitaniumTokens.Section,
                    color = if (gauges.manual) TitaniumTokens.OpticalAmber else TitaniumTokens.TelemetryWhite,
                    maxLines = 2, modifier = Modifier.testTag("titanium-exposure-strip"))
                Text(status.ifEmpty { " " }, style = TitaniumTokens.Section,
                    color = TitaniumTokens.TelemetryDim, maxLines = 1,
                    modifier = Modifier.testTag("titanium-status"))
            }
            Box(Modifier.heightIn(min = TitaniumTokens.MinimumTouch)
                .background(TitaniumTokens.Chassis, RoundedCornerShape(TitaniumTokens.RadiusSmall))
                .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder,
                    RoundedCornerShape(TitaniumTokens.RadiusSmall))
                .clickable(onClick = onLabs).padding(horizontal = 10.dp)
                .semantics { contentDescription = "Open Labs and service settings" }
                .testTag("titanium-labs-button"), contentAlignment = Alignment.Center) {
                Text("⚲ LABS", style = TitaniumTokens.Section, color = TitaniumTokens.OpticalAmber)
            }
        }

        if (landscapeCompact) Text(reading, style = TitaniumTokens.Section,
            color = if (gauges.manual) TitaniumTokens.OpticalAmber else TitaniumTokens.TelemetryWhite,
            maxLines = 1, modifier = Modifier.align(Alignment.CenterHorizontally)
                .testTag("titanium-exposure-strip"))

    }
}

/** Segmented resolution selector replacing legacy pill control. */
@Composable
fun TitaniumResolutionSelector(
    full: Boolean,
    binnedMegapixels: Float,
    fullMegapixels: Float,
    enabled: Boolean,
    onFull: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(TitaniumTokens.Chassis, RoundedCornerShape(TitaniumTokens.RadiusSmall))
            .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder, RoundedCornerShape(TitaniumTokens.RadiusSmall))
            .padding(1.dp)
            .testTag("titanium-resolution-selector"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(false, true).forEach { option ->
            val selected = option == full
            val label = "%.0fMP".format(if (option) fullMegapixels else binnedMegapixels)
            Box(
                modifier = Modifier
                    .heightIn(min = TitaniumTokens.MinimumTouch)
                    .widthIn(min = TitaniumTokens.MinimumTouch)
                    .background(
                        if (selected) TitaniumTokens.PlateSurface else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(TitaniumTokens.RadiusSmall),
                    )
                    .then(
                        if (selected) Modifier.border(
                            TitaniumTokens.Hairline,
                            TitaniumTokens.OpticalAmber,
                            RoundedCornerShape(TitaniumTokens.RadiusSmall),
                        ) else Modifier
                    )
                    .clickable(enabled = enabled, role = Role.Tab) { onFull(option) }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .semantics { contentDescription = "Resolution $label" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = TitaniumTokens.Badge,
                    color = if (selected) TitaniumTokens.OpticalAmber else TitaniumTokens.TelemetryDim,
                )
            }
        }
    }
}
