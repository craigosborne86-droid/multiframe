package dev.multiframe.camera.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlin.math.roundToInt

/**
 * How the screen is arranged for the window it has and the hand it is in.
 *
 * ### Three questions, one answer
 *
 * The window's shape (a phone's tall rectangle, a tablet's wide one, a
 * foldable's square), the fold (half-opened on a table, or flat), and the
 * hand (the phone turned on its side) each want something of the layout,
 * and they are decided together here so that nothing in `CameraScreen`
 * asks any of them twice. [CameraLayouts.decide] is pure -- width, height,
 * the device's turn and the fold in, a [CameraLayout] out -- so every
 * arrangement can be held on the JVM without a phone to turn.
 *
 * ### Why the window stays portrait on a phone and the controls turn
 *
 * `REFERENCE.md` §1.4 asks for a sidebar of controls in landscape. On a
 * phone the activity stays in portrait (the manifest's lock) and the
 * controls *turn* instead: the raw stream, the tap-to-focus mapping, the
 * mosaic guidance and the peaking overlay all speak in the sensor's
 * portrait frame, and rotating the window under them would rebuild the
 * camera session on every turn of the wrist and re-derive every one of
 * those mappings for the display's rotation. Held sideways, the bottom
 * strip of controls *is* a rail along the user's right or left hand -- its
 * rows are columns from where the user stands -- and all that is wrong is
 * that the words in it are on their side. So each chip's content turns to
 * read upright ([UprightInHand], [rotateLayout]) and takes the shape the
 * turned words need, and nothing else moves: the shutter stays under the
 * thumb it was under. A window that is itself wider than it is tall -- a
 * tablet, a foldable open flat in landscape -- gets the sidebar proper
 * ([Posture.SIDEBAR]): the viewfinder in the first two thirds, the controls
 * in a column down the last third. A foldable half-opened on a table
 * ([Posture.TABLETOP]) puts the viewfinder above the hinge and the controls
 * below it, where the camera's own rules would put them.
 */
enum class Posture {
    /** A tall window, the phone upright: controls in a strip along the bottom. */
    PORTRAIT,
    /** A tall window, the phone on its side: the same strip, its contents turned to read upright. */
    HAND_LANDSCAPE,
    /** A wide window: the viewfinder in two thirds, the controls in a rail down the end. */
    SIDEBAR,
    /** A foldable half-opened, hinge horizontal: viewfinder above the hinge, controls below. */
    TABLETOP,
}

/** The width classes of REFERENCE.md §1.4.1, from the window's shorter run of dp. */
enum class WindowClass { PHONE, EXPANDED, WIDE }

/** What the window layout library says about the fold, reduced to what the layout needs. In window pixels. */
data class FoldState(
    val halfOpened: Boolean,
    val horizontalHinge: Boolean,
    val hingeTopPx: Int,
    val hingeBottomPx: Int,
)

data class CameraLayout(
    val posture: Posture,
    val windowClass: WindowClass,
    /** Degrees the phone is turned from upright, 0/90/180/270; the controls' contents turn by minus this. */
    val handRotation: Int,
    /** The controls rail, [Posture.SIDEBAR] only. */
    val railWidth: Dp,
    /** Window pixels from the top to the hinge's top and bottom, [Posture.TABLETOP] only. */
    val paneSplitTopPx: Int,
    val paneSplitBottomPx: Int,
) {
    val turned: Boolean get() = handRotation == 90 || handRotation == 270
}

object CameraLayouts {

    /** §1.4.1's breakpoints, in dp of window width. */
    const val EXPANDED_FROM_DP = 600f
    const val WIDE_FROM_DP = 840f

    /** The rail's share of a wide window: the viewfinder keeps two thirds. */
    const val RAIL_SHARE = 1f / 3f

    fun windowClassFor(widthDp: Float): WindowClass = when {
        widthDp >= WIDE_FROM_DP -> WindowClass.WIDE
        widthDp >= EXPANDED_FROM_DP -> WindowClass.EXPANDED
        else -> WindowClass.PHONE
    }

    fun decide(widthDp: Float, heightDp: Float, deviceDegrees: Int, fold: FoldState?): CameraLayout {
        val cls = windowClassFor(widthDp)
        val turn = ((deviceDegrees % 360) + 360) % 360
        if (fold != null && fold.halfOpened && fold.horizontalHinge && fold.hingeTopPx > 0) {
            return CameraLayout(Posture.TABLETOP, cls, 0, 0.dp, fold.hingeTopPx, fold.hingeBottomPx)
        }
        if (widthDp > heightDp) {
            return CameraLayout(Posture.SIDEBAR, cls, 0, (widthDp * RAIL_SHARE).dp, 0, 0)
        }
        val turned = turn == 90 || turn == 270
        return CameraLayout(if (turned) Posture.HAND_LANDSCAPE else Posture.PORTRAIT, cls, if (turned) turn else 0, 0.dp, 0, 0)
    }
}

/**
 * The degrees the phone is turned from upright, for [UprightInHand]. Zero
 * everywhere the layout is not [Posture.HAND_LANDSCAPE].
 */
val LocalHandRotation = compositionLocalOf { 0 }

/**
 * Turns the content by [degrees] *and its layout box with it*: a quarter
 * turn swaps the box's width and height, so a label that was wide is tall
 * and the chip around it takes that shape, where `Modifier.rotate` alone
 * would turn the drawing inside an unchanged box and clip it.
 */
fun Modifier.rotateLayout(degrees: Int): Modifier {
    val turn = ((degrees % 360) + 360) % 360
    if (turn == 0) return this
    val quarter = turn == 90 || turn == 270
    return this.layout { measurable, constraints ->
        val inner = if (quarter) Constraints(
            minWidth = constraints.minHeight, maxWidth = constraints.maxHeight,
            minHeight = constraints.minWidth, maxHeight = constraints.maxWidth,
        ) else constraints
        val placeable = measurable.measure(inner)
        val width = if (quarter) placeable.height else placeable.width
        val height = if (quarter) placeable.width else placeable.height
        layout(width, height) {
            // Rotated about its own centre, then that centre put at the box's.
            placeable.placeWithLayer(
                x = (width - placeable.width) / 2,
                y = (height - placeable.height) / 2,
            ) { rotationZ = turn.toFloat() }
        }
    }
}

/**
 * The content, upright in the hand: turned by minus [LocalHandRotation],
 * box and all. Nothing when the phone is upright.
 */
@Composable
fun UprightInHand(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val rotation = LocalHandRotation.current
    Box(modifier.rotateLayout(-rotation)) { content() }
}

/**
 * The layout for this window and this hand, kept current: the window's dp
 * from the configuration, the fold from [WindowInfoTracker] (an empty flow
 * on a phone, which is the flat case), the turn from the caller's
 * orientation tracker.
 */
@Composable
fun rememberCameraLayout(deviceDegrees: Int): CameraLayout {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val activity = remember(context) { context.findActivity() }
    val fold by produceState<FoldState?>(initialValue = null, activity) {
        val a = activity ?: return@produceState
        WindowInfoTracker.getOrCreate(a).windowLayoutInfo(a).collect { info ->
            val feature = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
            value = feature?.let {
                FoldState(
                    halfOpened = it.state == FoldingFeature.State.HALF_OPENED,
                    horizontalHinge = it.orientation == FoldingFeature.Orientation.HORIZONTAL,
                    hingeTopPx = it.bounds.top,
                    hingeBottomPx = it.bounds.bottom,
                )
            }
        }
    }
    return remember(configuration.screenWidthDp, configuration.screenHeightDp, deviceDegrees, fold, density) {
        CameraLayouts.decide(
            configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat(), deviceDegrees, fold,
        )
    }
}

/**
 * Constrains the content to the largest box of [ratio] (width over height)
 * that fits: a viewfinder that is neither stretched to the window nor
 * spilling out of the region it was given.
 */
fun Modifier.fitAspect(ratio: Float): Modifier = this.layout { measurable, constraints ->
    val maxW = constraints.maxWidth
    val maxH = constraints.maxHeight
    var w = maxW
    var h = (w / ratio).roundToInt()
    if (h > maxH) {
        h = maxH
        w = (h * ratio).roundToInt()
    }
    val placeable = measurable.measure(Constraints.fixed(w.coerceAtLeast(0), h.coerceAtLeast(0)))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
