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
