package dev.multiframe.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Everything that is set and left, on a screen of its own.
 *
 * ### Why a screen and not a row
 *
 * The viewfinder carried six settings chips across its top, a PRO panel that
 * slid up over its bottom third, and a strip of developer switches -- ZSL,
 * DCG, A/B -- that a photographer never touches between shots and a
 * developer touches once. All of it was drawn over the photograph. The
 * instrument the app is meant to be has a viewfinder that shows the picture
 * and the three numbers that describe its exposure, and puts what is set once
 * behind one control.
 *
 * So the settings are here, on a solid ground, in the order a person needs
 * them: the capture first, the manual controls under it, and at the end the
 * page that says what any of it means. The chips are the same objects the
 * viewfinder used to draw -- [ControlBar.settings] builds them, and the same
 * gating applies -- so a phone without a raw ring shows no ZSL switch here
 * either.
 */
@Composable
fun SettingsScreen(
    chips: List<ControlSpec>,
    onControl: (String) -> Unit,
    onDismiss: () -> Unit,
    /** The manual controls, supplied by the screen that owns their state. */
    panel: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.PaneSolid)
            // Swallows taps so they cannot reach the viewfinder underneath,
            // which is still composed and still listening for a focus tap.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "SETTINGS", style = Type.Section)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .background(Ink.Pane, RoundedCornerShape(24.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 18.dp)
                        .semantics { contentDescription = "Close settings" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "DONE", style = Type.Control)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Hairline))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding(),
            ) {
                Text(
                    text = "CAPTURE",
                    style = Type.Section,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                )
                SettingsChips(
                    specs = chips,
                    onControl = onControl,
                    modifier = Modifier.padding(horizontal = 13.dp),
                )
                Box(
                    Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Ink.Hairline),
                )
                panel()
            }
        }
    }
}
