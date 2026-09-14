package dev.multiframe.camera.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The design system, held to the rules it is written under.
 *
 * These are not tests that the palette is pretty. They are tests of the four
 * rules in CLAUDE.md that make the interface coherent, each of which is easy to
 * break by adding one plausible-looking token: the accent is spent only on
 * readings, monospace is spent only on numbers, a value and its label are told
 * apart, and the semantic colours stay off the photograph.
 *
 * A future session adding a fifth kind of text or a second accent has to change
 * a test here to do it, which is the point.
 */
class ThemeTest {

    /** Every style that carries a live sensor reading. */
    private val gauges = listOf(
        Type.Gauge, Type.GaugeLarge, Type.GaugeBone, Type.SliderValue,
        Type.LensFocal, Type.LensZoom, Type.Countdown, Type.Status, Type.Badge,
    )

    /** Every style that carries words rather than numbers. */
    private val prose = listOf(
        Type.Label, Type.Control, Type.Section, Type.Instruction,
        Type.Action, Type.SliderLabel, Type.Unit, Type.Indicator,
        Type.Spec, Type.Reset, Type.Mode, Type.ModeSelected,
    )

    // --- the monospace rule ------------------------------------------------

    /**
     * Monospace is for things read as numbers. It is what makes a changing ISO
     * stop jittering sideways as its digits change width, and it is the
     * convention every instrument that has ever had a display follows.
     */
    @Test
    fun everyReadingIsMonospaced() {
        for (style in gauges) {
            assertThat(style.fontFamily).isEqualTo(FontFamily.Monospace)
        }
    }

    /**
     * And prose is not. A label set in monospace at a readable size is wider
     * than the panel it has to fit in -- which is exactly the wrap the
     * capability summary was fixed for.
     */
    @Test
    fun noLabelIsMonospaced() {
        for (style in prose) {
            assertThat(style.fontFamily).isNotEqualTo(FontFamily.Monospace)
        }
    }

    // --- the accent rule ---------------------------------------------------

    /**
     * The single accent, spent where an instrument has always spent it.
     *
     * The failure this prevents is a plausible one: colouring a label amber to
     * "make it match" the number beside it, which destroys the distinction the
     * accent exists to draw.
     */
    @Test
    fun theAccentIsNeverSpentOnALabel() {
        for (style in prose) {
            assertThat(style.color).isNotEqualTo(Ink.Amber)
        }
    }

    @Test
    fun theSlidersAndTheChipsAgreeOnWhatAValueLooksLike() {
        // Both readings, so both amber, so a number reads the same wherever it
        // appears. These drifted apart once already.
        assertThat(Type.Gauge.color).isEqualTo(Ink.Amber)
        assertThat(Type.SliderValue.color).isEqualTo(Ink.Amber)
        assertThat(Type.GaugeLarge.color).isEqualTo(Ink.Amber)
    }

    @Test
    fun aValueAndItsLabelAreDistinguishable() {
        // The whole reason ISO 400 reads as an instrument rather than as a
        // phrase: the two halves do not look the same.
        assertThat(Type.Gauge.color).isNotEqualTo(Type.Label.color)
        assertThat(Type.Gauge.fontFamily).isNotEqualTo(Type.Label.fontFamily)
        assertThat(Type.SliderValue.color).isNotEqualTo(Type.SliderLabel.color)
    }

    // --- the photograph rule -----------------------------------------------

    /**
     * Nothing coloured sits on the photograph, and the two documented
     * exceptions are the only two.
     *
     * `Danger` and `Confirm` were added for the gallery and the sweep reticle.
     * The risk they carry is that a later session reaches for one to mark a
     * state in the viewfinder, which is the thing the palette forbids. Naming
     * them here as distinct from the two sanctioned exceptions is the record of
     * that.
     */
    @Test
    fun theSemanticColoursAreNotTheViewfinderExceptions() {
        assertThat(Ink.Danger).isNotEqualTo(Ink.Clipping)
        assertThat(Ink.Confirm).isNotEqualTo(Ink.Level)
        // The peaking hue is one no scene has, and none of the other three.
        assertThat(Ink.Peaking).isNotEqualTo(Ink.Level)
        assertThat(Ink.Peaking).isNotEqualTo(Ink.Clipping)
        assertThat(Ink.Peaking.blue).isGreaterThan(0.9f)
        assertThat(Ink.Peaking.green).isGreaterThan(0.9f)
        assertThat(Ink.Peaking.red).isLessThan(0.3f)
    }

    @Test
    fun theGroundIsOpaqueAndTheOverlaysAreNot() {
        assertThat(Ink.Ground.alpha).isEqualTo(1f)
        assertThat(Ink.PaneSolid.alpha).isEqualTo(1f)
        // A pane over a photograph has to let some of it through, or it is a
        // bar across the frame rather than a control on top of one.
        assertThat(Ink.Pane.alpha).isLessThan(1f)
        assertThat(Ink.Readout.alpha).isLessThan(1f)
        assertThat(Ink.Hairline.alpha).isLessThan(1f)
    }

    /**
     * The dimmed variants are the same hue at less presence, not a second
     * colour that happens to look similar.
     */
    @Test
    fun theDimVariantsAreTheirParentsAtLowerAlpha() {
        assertThat(Color(Ink.BoneDim.red, Ink.BoneDim.green, Ink.BoneDim.blue))
            .isEqualTo(Color(Ink.Bone.red, Ink.Bone.green, Ink.Bone.blue))
        assertThat(Ink.BoneDim.alpha).isLessThan(Ink.Bone.alpha)

        assertThat(Color(Ink.AmberDim.red, Ink.AmberDim.green, Ink.AmberDim.blue))
            .isEqualTo(Color(Ink.Amber.red, Ink.Amber.green, Ink.Amber.blue))
        assertThat(Ink.AmberDim.alpha).isLessThan(Ink.Amber.alpha)
    }

    /**
     * Bone is warm. Pure white next to a photograph reads as a light leak; the
     * warmth is what makes it read as paper.
     */
    @Test
    fun boneIsWarmRatherThanWhite() {
        assertThat(Ink.Bone.red).isGreaterThan(Ink.Bone.blue)
        assertThat(Ink.Bone).isNotEqualTo(Color.White)
    }

    // --- legibility --------------------------------------------------------

    /**
     * Text on a bone fill has to be dark, and text on the ground has to be
     * light. Inverting one of these produces a control that is invisible
     * exactly when it is selected.
     */
    @Test
    fun contrastRunsTheRightWayOnBothGrounds() {
        val onBoneLuma = luminance(Ink.OnBone)
        val boneLuma = luminance(Ink.Bone)
        assertThat(onBoneLuma).isLessThan(boneLuma)

        assertThat(luminance(Ink.Bone)).isGreaterThan(luminance(Ink.Ground))
        assertThat(luminance(Ink.Muted)).isGreaterThan(luminance(Ink.Ground))
    }

    /**
     * Secondary text is quieter than primary and still legible. `Muted` sits
     * between the ground and `Bone` rather than against either.
     */
    @Test
    fun secondaryTextIsQuieterThanPrimaryWithoutVanishing() {
        val muted = luminance(Ink.Muted)
        assertThat(muted).isLessThan(luminance(Ink.Bone))
        assertThat(muted).isGreaterThan(0.2f)
    }

    private fun luminance(c: Color): Float =
        0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    // --- the scale ---------------------------------------------------------

    /**
     * Every size is deliberate, which mostly means: nothing is so small it
     * cannot be read at arm's length in sunlight, and the prominent things are
     * larger than the quiet ones.
     */
    @Test
    fun nothingIsTooSmallToRead() {
        for (style in gauges + prose) {
            assertThat(style.fontSize.value).isAtLeast(10f)
        }
    }

    @Test
    fun theScaleIsOrderedByProminence() {
        assertThat(Type.Countdown.fontSize.value)
            .isGreaterThan(Type.GaugeLarge.fontSize.value)
        assertThat(Type.GaugeLarge.fontSize.value)
            .isGreaterThan(Type.Gauge.fontSize.value)
        assertThat(Type.Gauge.fontSize.value)
            .isGreaterThan(Type.Label.fontSize.value)
        assertThat(Type.Control.fontSize.value)
            .isAtLeast(Type.Section.fontSize.value)
    }

    /**
     * The two styles that shout are tracked out, which is what makes a short
     * uppercase run legible rather than a block.
     */
    @Test
    fun theUppercaseStylesAreTracked() {
        assertThat(Type.Section.letterSpacing.value).isAtLeast(1f)
        assertThat(Type.Instruction.letterSpacing.value).isAtLeast(1f)
    }
}
