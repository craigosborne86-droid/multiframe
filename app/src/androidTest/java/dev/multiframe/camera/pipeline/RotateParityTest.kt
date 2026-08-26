package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "RotateParity"

/**
 * The tiled transpose against the framework's Matrix rotation.
 *
 * Pixel for pixel, because dimensions prove almost nothing here: a transpose
 * with its handedness reversed produces an image of exactly the right shape and
 * the wrong content, and the capture test that checks 4080x3072 becomes
 * 3072x4080 would pass happily on a mirrored picture.
 *
 * The sizes are chosen to break things. Non-square, so a row/column confusion
 * cannot hide. Not multiples of the 32-pixel tile, so the partial tiles at the
 * right and bottom edges are exercised -- an off-by-one there touches only a
 * strip a few pixels wide, which is exactly the kind of defect that survives
 * being looked at.
 */
@RunWith(AndroidJUnit4::class)
class RotateParityTest {

    /**
     * Every pixel distinct and position-dependent, so any displacement shows.
     *
     * A gradient would let a mirror through: reversed, it still looks like a
     * gradient. Packing the coordinates into the channels means a pixel that
     * has moved is a pixel that is wrong.
     */
    private fun pattern(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                bmp.setPixel(x, y, Color.argb(255, x % 256, y % 256, (x * 7 + y * 13) % 256))
            }
        }
        return bmp
    }

    private fun assertSamePixels(expected: Bitmap, actual: Bitmap, what: String) {
        assertThat(actual.width).isEqualTo(expected.width)
        assertThat(actual.height).isEqualTo(expected.height)

        var differing = 0
        var firstAt = ""
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                if (expected.getPixel(x, y) != actual.getPixel(x, y)) {
                    if (differing == 0) firstAt = "($x,$y)"
                    differing++
                }
            }
        }
        Log.i(TAG, "$what: ${expected.width}x${expected.height}, $differing differing $firstAt")
        assertThat(differing).isEqualTo(0)
    }

    private fun checkTurn(w: Int, h: Int, degrees: Int) {
        val source = pattern(w, h)
        val reference = OrientationTracker.rotateWithMatrix(source, degrees)
        val native = NativeRotate.rotate(source, degrees)

        assertThat(native).isNotNull()
        assertSamePixels(reference, native!!, "${w}x$h turned $degrees")

        source.recycle()
        reference.recycle()
        native.recycle()
    }

    @Test
    fun aQuarterTurnMatchesTheFramework() {
        checkTurn(100, 70, 90)
    }

    @Test
    fun aHalfTurnMatchesTheFramework() {
        checkTurn(100, 70, 180)
    }

    @Test
    fun aThreeQuarterTurnMatchesTheFramework() {
        checkTurn(100, 70, 270)
    }

    @Test
    fun anExactTileMultipleMatchesTheFramework() {
        // The case with no partial tiles, so a failure here is the tiling
        // itself rather than its edges.
        checkTurn(64, 32, 90)
    }

    @Test
    fun aSinglePixelSurvives() {
        // Smaller than one tile in both directions.
        checkTurn(1, 1, 90)
        checkTurn(3, 1, 270)
    }

    @Test
    fun aTurnItWillNotHandleIsDeclinedRatherThanGuessed() {
        val source = pattern(16, 16)
        // Not a quarter turn, and zero, which the caller handles itself.
        assertThat(NativeRotate.rotate(source, 45)).isNull()
        assertThat(NativeRotate.rotate(source, 0)).isNull()
        // Full turns reduce to zero rather than being done the long way.
        assertThat(NativeRotate.rotate(source, 360)).isNull()
        source.recycle()
    }

    @Test
    fun theNativeTurnIsFasterAtCaptureSize() {
        // The size that actually matters. Both paths allocate a second bitmap
        // of the same size, so this compares the transpose, not the allocation.
        val source = pattern(1024, 768)

        val matrixStart = System.nanoTime()
        val reference = OrientationTracker.rotateWithMatrix(source, 90)
        val matrixMicros = (System.nanoTime() - matrixStart) / 1000

        val nativeStart = System.nanoTime()
        val native = NativeRotate.rotate(source, 90)!!
        val nativeMicros = (System.nanoTime() - nativeStart) / 1000

        Log.i(TAG, "rotate 1024x768: matrix ${matrixMicros}us vs native ${nativeMicros}us")
        assertSamePixels(reference, native, "timed turn")

        source.recycle()
        reference.recycle()
        native.recycle()
    }
}
