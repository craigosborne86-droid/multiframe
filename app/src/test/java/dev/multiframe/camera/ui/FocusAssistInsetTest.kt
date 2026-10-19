package dev.multiframe.camera.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FocusAssistInsetTest {
    @Test fun insetUsesPreviewCoordinatesAndClipsToTheImage() {
        val inset = FocusAssistInset.fromBounds(
            preview = Rect(10f, 20f, 210f, 220f),
            inset = Rect(150f, 40f, 230f, 120f),
            focus = Offset(1.4f, -.2f),
        )!!
        assertThat(inset.left).isWithin(1e-6f).of(.7f)
        assertThat(inset.top).isWithin(1e-6f).of(.1f)
        assertThat(inset.width).isWithin(1e-6f).of(.3f)
        assertThat(inset.height).isWithin(1e-6f).of(.4f)
        assertThat(inset.focusX).isEqualTo(1f)
        assertThat(inset.focusY).isEqualTo(0f)
        assertThat(FocusAssistInset.fromBounds(Rect(0f, 0f, 100f, 100f),
            Rect(120f, 120f, 140f, 140f), Offset(.5f, .5f))).isNull()
    }
}
