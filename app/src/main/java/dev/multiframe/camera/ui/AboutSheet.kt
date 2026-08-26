package dev.multiframe.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            .background(Color(0xF2070A0D))
            .clickable(onClick = onDismiss)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            color = Color.White,
            fontSize = 22.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = "version ${BuildConfig.VERSION_NAME}",
            color = Color(0xFF8A97A5),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 2.dp, bottom = 20.dp),
        )

        Section("What this app does", stringResource(R.string.about_what))
        Section("Privacy", stringResource(R.string.privacy_summary))
        Section("Camera permission", stringResource(R.string.privacy_camera))
        Section("Your photographs", stringResource(R.string.privacy_photos))

        Text(
            text = stringResource(R.string.about_dismiss),
            color = Ink.Amber,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(top = 24.dp)
                .fillMaxWidth()
                .background(Color(0xFF161C22), RoundedCornerShape(8.dp))
                .clickable(onClick = onDismiss)
                .padding(14.dp)
                .semantics { contentDescription = "Close" },
        )
    }
}

@Composable
private fun Section(title: String, body: String) {
    Text(
        text = title.uppercase(),
        color = Ink.Amber,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    Text(
        text = body,
        color = Color(0xFFD4DDE6),
        fontSize = 13.sp,
        modifier = Modifier.padding(bottom = 20.dp),
    )
}
