package dev.multiframe.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** One captured shot's measured merge work. A queued shot gets its own id. */
internal data class MergeUiProgress(
    val shotId: Long,
    val processed: Int,
    val total: Int,
    val finished: Boolean = false,
) {
    val fraction: Float get() = (processed.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    val shutterFraction: Float? get() = if (finished) null else fraction
}

internal object MergeIndicatorTiming {
    // A two-frame merge can finish before the first useful frame of feedback.
    const val SHOW_DELAY_MS = 160L
    const val MIN_VISIBLE_MS = 350L
}

/** A determinate readout in the console header, never over the photograph. */
@Composable
internal fun MergeProcessingIndicator(progress: MergeUiProgress?, modifier: Modifier = Modifier, idleStatus: String = "") {
    var visible by remember { mutableStateOf(false) }
    var displayed by remember { mutableStateOf<MergeUiProgress?>(null) }
    var shownAtMs by remember { mutableLongStateOf(0L) }
    val latest by rememberUpdatedState(progress)

    LaunchedEffect(progress?.shotId, progress?.finished) {
        when {
            progress == null -> {
                visible = false
                displayed = null
            }
            !progress.finished -> {
                visible = false
                delay(MergeIndicatorTiming.SHOW_DELAY_MS)
                if (latest?.shotId == progress.shotId && latest?.finished == false) {
                    displayed = latest
                    shownAtMs = withFrameNanos { it / 1_000_000 }
                    visible = true
                }
            }
            visible -> {
                val nowMs = withFrameNanos { it / 1_000_000 }
                val remaining = MergeIndicatorTiming.MIN_VISIBLE_MS -
                    (nowMs - shownAtMs)
                if (remaining > 0) delay(remaining)
                visible = false
            }
        }
    }

    val shown = progress ?: displayed
    if (visible && shown != null) {
        Column(modifier.fillMaxWidth().height(TitaniumTokens.CaptureStatusHeight).background(TitaniumTokens.Chassis)
            .semantics(mergeDescendants = true) {
                contentDescription = if (shown.finished) "Merge complete, ${shown.total} frames"
                    else "Processing ${shown.processed} of ${shown.total} frames"
                progressBarRangeInfo = ProgressBarRangeInfo(shown.fraction, 0f..1f)
            }
            .testTag("merge-processing-indicator")) {
            Row(Modifier.fillMaxWidth()) {
                Text(if (shown.finished) "MERGED" else "MERGING", style = TitaniumTokens.Section, color = TitaniumTokens.TelemetryDim)
                Text("  ${shown.processed}/${shown.total}", style = TitaniumTokens.Section, color = TitaniumTokens.OpticalAmber)
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(TitaniumTokens.HairlineBorder)) {
                Box(Modifier.fillMaxWidth(shown.fraction).height(2.dp).background(TitaniumTokens.OpticalAmber))
            }
        }
    } else if ((progress == null || progress.finished) && idleStatus.isNotBlank()) {
        Text(idleStatus, style = TitaniumTokens.ReadableSmall, maxLines = 1,
            modifier = modifier.testTag("revision05-save-status"))
    }
}
