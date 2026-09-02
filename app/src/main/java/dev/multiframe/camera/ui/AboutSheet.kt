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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.multiframe.camera.BuildConfig
import dev.multiframe.camera.R

/**
 * Google Play requires the privacy policy to be reachable inside the app, not
 * only from the store listing. The text is embedded rather than linked because
 * the app has no INTERNET permission.
 *
 * ### Two things that were wrong with it
 *
 * The ground was 95% opaque, which is not opaque: the control row and the lens
 * strip ghosted through four paragraphs of body text, and the result read as a
 * compositing bug rather than as a sheet. It is now [Ink.Sheet], which is.
 *
 * And the entire column was one click target that dismissed the sheet. On a
 * page you have to scroll to read, every tap that was not quite a drag threw it
 * away, and a screen reader announced the whole document as a button. The
 * dismiss is now two explicit controls -- one at the top where a close control
 * belongs, one at the end of the text for a thumb that has just finished
 * reading.
 */
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.Sheet)
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
                    color = Ink.Bone,
                    fontSize = 22.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "version ${BuildConfig.VERSION_NAME}",
                    color = Ink.SheetMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
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
                .heightIn(min = 48.dp)
                .background(Ink.SheetCode, RoundedCornerShape(12.dp))
                .clickable(onClick = onDismiss)
                .semantics { contentDescription = "Close" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.about_dismiss),
                color = Ink.Amber,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** The close control, where a close control goes. */
@Composable
private fun CloseButton(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .border(1.dp, Ink.Hairline, CircleShape)
            .clickable(onClick = onDismiss)
            .semantics { contentDescription = "Close about" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "✕",
            color = Ink.SheetBody,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun Section(title: String, body: String) {
    Text(
        text = title.uppercase(),
        color = Ink.Amber,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    Text(
        text = body,
        color = Ink.SheetBody,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        modifier = Modifier.padding(bottom = 24.dp),
    )
}
