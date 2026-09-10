package dev.multiframe.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The live readings, held apart from the screen that shows them.
 *
 * ### Why this is a class rather than half a dozen `var`s in the screen
 *
 * Everything here changes while the user is doing nothing at all: the sensor
 * reports a new sensitivity, the white balance drifts a hundred kelvin, the
 * histogram is resampled. Held as state in `CameraScreen`, each of those writes
 * invalidates the whole composable — nineteen hundred lines of camera plumbing,
 * a viewfinder surface, two scrims, the control row, the lens strip and the
 * shutter — to change two digits in a corner.
 *
 * At 60 Hz that was survivable. The phones this is aimed at run their panels at
 * 120, which halves the budget for a frame to 8.3 ms, and the readout samples
 * at 5 Hz: five times a second the whole tree was being asked to recompose
 * inside that budget. What the user sees when it does not make it is a
 * viewfinder that stutters while the numbers update, which is the exact
 * opposite of the impression this interface is meant to give.
 *
 * A `@Stable` holder fixes it by moving *where the read happens*. The screen
 * passes this object down; it never reads `.iso` itself. The only composable
 * that reads `.iso` is the one that draws it, so that is the only one Compose
 * invalidates when it changes. The viewfinder is not in that scope and does not
 * recompose at all.
 *
 * The primitive-specialised state (`mutableIntStateOf` and friends) is not
 * micro-optimisation either: the boxing versions allocate on every write, and
 * these are written several times a second for as long as the app is open.
 */
@Stable
class GaugeState {
    /** Sensitivity the sensor actually used. Zero before the first result. */
    var iso by mutableIntStateOf(0)
        internal set

    /** Exposure time the sensor actually used, nanoseconds. */
    var exposureNs by mutableLongStateOf(0L)
        internal set

    /** Correlated colour temperature of the scene, kelvin. Zero when unknown. */
    var cct by mutableIntStateOf(0)
        internal set

    /** Exposure compensation, already formatted. Null when not applicable. */
    var evLabel by mutableStateOf<String?>(null)
        internal set

    /** Whether exposure is under manual control. */
    var manual by mutableStateOf(false)
        internal set

    /** Luma histogram, or null before one has been sampled. */
    var histogram by mutableStateOf<IntArray?>(null)
        internal set

    /** Focus sharpness metric, shown only under manual focus. */
    var focusScore by mutableFloatStateOf(0f)
        internal set

    /** Digital zoom factor. One when the lens is at its native framing. */
    var digitalZoom by mutableFloatStateOf(1f)
        internal set

    /** True once there is an exposure worth showing. */
    val hasExposure: Boolean get() = iso > 0 && exposureNs > 0L

    fun setExposure(iso: Int, exposureNs: Long) {
        this.iso = iso
        this.exposureNs = exposureNs
    }

    fun setColourTemperature(kelvin: Int) {
        cct = kelvin
    }

    fun setEv(label: String?, manual: Boolean) {
        evLabel = label
        this.manual = manual
    }

    fun setHistogram(bins: IntArray?) {
        histogram = bins
    }

    fun setFocusScore(score: Float) {
        focusScore = score
    }

    fun setZoom(factor: Float) {
        digitalZoom = factor
    }

    fun clearExposure() {
        iso = 0
        exposureNs = 0L
        cct = 0
    }
}

/**
 * Shutter speed as a photographer writes it.
 *
 * Fractions above a quarter of a second, decimals below — the point where a
 * camera's own dial stops saying `1/4` and starts saying `0.5"`. Kept out of the
 * composable so the formatting can be tested without one.
 */
fun shutterLabel(exposureNs: Long): String {
    val seconds = exposureNs / 1_000_000_000.0
    return if (seconds >= 0.4) "%.1fs".format(seconds)
    else "1/${(1.0 / seconds).toInt()}"
}

/**
 * The instrument bar: what the sensor is doing, right now.
 *
 * Reads [gauges] inside its own scope rather than taking the values as
 * parameters, which is the whole point of the holder — see [GaugeState]. Every
 * field it reads invalidates this row and nothing above it.
 */
@Composable
fun ExposureGauge(
    gauges: GaugeState,
    modifier: Modifier = Modifier,
) {
    if (!gauges.hasExposure) return

    val iso = gauges.iso
    val shutter = shutterLabel(gauges.exposureNs)
    val cct = gauges.cct
    val evLabel = gauges.evLabel
    val manual = gauges.manual

    Row(
        modifier = modifier
            .background(Ink.Readout, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics {
                contentDescription = buildString {
                    append("ISO $iso, shutter $shutter")
                    if (cct > 0) append(", $cct kelvin")
                    if (evLabel != null) append(", exposure compensation $evLabel")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Reading("ISO", iso.toString())
        Separator()
        Reading(null, shutter)
        if (cct > 0) {
            Separator()
            Reading(null, cct.toString(), unit = "K")
        }
        if (evLabel != null) {
            Separator()
            Reading(null, evLabel, unit = "EV")
        }
        if (manual) {
            Separator()
            Text(text = "MAN", style = Type.Indicator)
        }
    }
}

/** One reading: an optional label, the value in the accent, an optional unit. */
@Composable
private fun Reading(label: String?, value: String, unit: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (label != null) {
            Text(
                text = label,
                style = Type.Label,
                modifier = Modifier.padding(end = 5.dp),
            )
        }
        Text(text = value, style = Type.Gauge)
        if (unit != null) {
            Text(
                text = unit,
                style = Type.Unit,
                modifier = Modifier.padding(start = 3.dp),
            )
        }
    }
}

/** The separator between readings. Indigo's dot, and for the same reason. */
@Composable
private fun Separator() {
    Text(
        text = "·",
        style = Type.Dot,
        modifier = Modifier.padding(horizontal = 9.dp),
    )
}

/**
 * The histogram, on the same ground and radius as the exposure bar so the two
 * read as one instrument rather than two overlays.
 *
 * Takes the holder rather than the bins for the reason [ExposureGauge] does:
 * the array is replaced several times a second, and the read belongs in the
 * scope that draws it.
 */
@Composable
fun HistogramGauge(
    gauges: GaugeState,
    modifier: Modifier = Modifier,
) {
    val bins = gauges.histogram ?: return
    Histogram(
        bins = bins,
        modifier = modifier
            .size(width = 132.dp, height = 40.dp)
            .background(Ink.Readout, RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 5.dp),
    )
}

/**
 * Sharpness under manual focus, which is the only time it answers a question
 * anyone is asking.
 */
@Composable
fun FocusGauge(
    gauges: GaugeState,
    modifier: Modifier = Modifier,
) {
    val score = gauges.focusScore
    if (score <= 0f) return
    Text(
        text = "FOCUS %.0f".format(score * 1000),
        style = Type.Gauge,
        modifier = modifier
            .background(Ink.Readout, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .semantics { contentDescription = "Focus score %.0f".format(score * 1000) },
    )
}

/** Digital zoom, shown only once there is any. */
@Composable
fun ZoomGauge(
    gauges: GaugeState,
    modifier: Modifier = Modifier,
) {
    val zoom = gauges.digitalZoom
    if (zoom <= 1.02f) return
    Text(
        text = "%.1fx".format(zoom),
        style = Type.GaugeBone,
        modifier = modifier
            .background(Ink.Readout, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = "Zoom %.1f times".format(zoom) },
    )
}
