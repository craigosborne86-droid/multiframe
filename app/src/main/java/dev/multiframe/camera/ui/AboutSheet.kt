package dev.multiframe.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.multiframe.camera.BuildConfig
import dev.multiframe.camera.R

/**
 * Google Play requires the privacy policy to be reachable inside the app, not
 * only from the store listing. The text is embedded rather than linked because
 * the app has no INTERNET permission.
 */
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TitaniumTokens.Chassis)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = TitaniumTokens.GaugeLarge,
                    color = TitaniumTokens.TitaniumM6,
                )
                Text(
                    text = "version ${BuildConfig.VERSION_NAME}",
                    style = TitaniumTokens.Label,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            CloseButton(onDismiss)
        }

        Spacer(Modifier.size(24.dp))

        Section("What this app does", stringResource(R.string.about_what))
        Section("Privacy", stringResource(R.string.privacy_summary))
        Section("Camera permission", stringResource(R.string.privacy_camera))
        Section("Your photographs", stringResource(R.string.privacy_photos))

        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .heightIn(min = TitaniumTokens.MinimumTouch)
                .clip(RoundedCornerShape(TitaniumTokens.RadiusPlate))
                .background(TitaniumTokens.PlateSurface)
                .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder, RoundedCornerShape(TitaniumTokens.RadiusPlate))
                .clickable(onClick = onDismiss)
                .semantics { contentDescription = "Close" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.about_dismiss),
                style = TitaniumTokens.Action,
                color = TitaniumTokens.OpticalAmber,
            )
        }
    }
}

/** The close control, where a close control goes. */
@Composable
private fun CloseButton(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .size(TitaniumTokens.MinimumTouch)
            .clip(RoundedCornerShape(TitaniumTokens.RadiusPlate))
            .background(TitaniumTokens.PlateSurface)
            .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder, RoundedCornerShape(TitaniumTokens.RadiusPlate))
            .clickable(onClick = onDismiss)
            .semantics { contentDescription = "Close about" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "✕",
            style = TitaniumTokens.Control,
            color = TitaniumTokens.TelemetryWhite,
        )
    }
}

@Composable
private fun Section(title: String, body: String) {
    Text(
        text = title.uppercase(),
        style = TitaniumTokens.Section,
        color = TitaniumTokens.OpticalAmber,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    Text(
        text = body,
        style = TitaniumTokens.Spec,
        color = TitaniumTokens.TelemetryWhite,
        modifier = Modifier.padding(bottom = 24.dp),
    )
}
