package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Assembling a sweep.
 *
 * Simulates one by generating a large scene and cutting overlapping windows out
 * of it, which is exactly what panning a telephoto does. The assembler is then
 * asked to put them back together, and the test checks it recovered the offsets
 * the windows were actually cut at.
 */
class MosaicAssemblerTest {

    private val sceneWidth = 900
    private val sceneHeight = 700
    private val tile = 260
    private val proxyScale = 1f

    /** A large textured scene to cut tiles out of. */
    private val scene: Plane = run {
        val rnd = Random(21)
        val data = ByteArray(sceneWidth * sceneHeight)
        for (y in 0 until sceneHeight) {
            for (x in 0 until sceneWidth) {
                data[y * sceneWidth + x] =
                    (100 + 30 * sin(x * 0.05) * cos(y * 0.04)).toInt()
                        .coerceIn(0, 255).toByte()
            }
        }
        repeat(900) {
            val cx = rnd.nextInt(6, sceneWidth - 6)
            val cy = rnd.nextInt(6, sceneHeight - 6)
            val size = rnd.nextInt(2, 5)
            val value = if (rnd.nextBoolean()) 240 else 20
            for (dy in -size..size) for (dx in -size..size) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until sceneWidth && y in 0 until sceneHeight) {
                    data[y * sceneWidth + x] = value.toByte()
                }
            }
        }
        Plane(sceneWidth, sceneHeight, data)
    }

    /** Cuts a tile-sized window out of the scene at a given offset. */
    private fun window(ox: Int, oy: Int): Plane {
        val data = ByteArray(tile * tile)
        for (y in 0 until tile) {
            for (x in 0 until tile) {
                val sx = (ox + x).coerceIn(0, sceneWidth - 1)
                val sy = (oy + y).coerceIn(0, sceneHeight - 1)
                data[y * tile + x] = scene.data[sy * sceneWidth + sx]
            }
        }
        return Plane(tile, tile, data)
    }

    private fun assembler() = MosaicAssembler(
        canvasWidth = 1400, canvasHeight = 1200,
        tileWidth = tile, tileHeight = tile,
    )

    // ------------------------------------------------------------------

    @Test
    fun `the first frame anchors the mosaic at the centre`() {
        val a = assembler()

        val result = a.offer(window(100, 100), proxyScale)

        assertThat(result).isInstanceOf(OfferResult.Placed::class.java)
        val where = (result as OfferResult.Placed).placement.transform.apply(0f, 0f)
        // Centred, so the mosaic can grow in any direction from the first frame.
        assertThat(where[0]).isWithin(2f).of((1400 - tile) / 2f)
        assertThat(where[1]).isWithin(2f).of((1200 - tile) / 2f)
    }

    @Test
    fun `a panned frame is placed by the amount it was panned`() {
        // The core claim: the assembler recovers the motion between frames
        // rather than assuming a grid.
        val a = assembler()
        a.offer(window(100, 100), proxyScale)

        val result = a.offer(window(160, 100), proxyScale)

        assertThat(result).isInstanceOf(OfferResult.Placed::class.java)
        val placement = (result as OfferResult.Placed).placement
        val origin = placement.transform.apply(0f, 0f)
        // Panning right by 60 puts the new frame 60 further right on the canvas.
        assertThat(origin[0]).isWithin(4f).of((1400 - tile) / 2f + 60f)
        assertThat(origin[1]).isWithin(4f).of((1200 - tile) / 2f)
        println("pan recovered: placed at (%.1f, %.1f), %d inliers"
            .format(origin[0], origin[1], placement.inliers))
    }

    @Test
    fun `a sweep of several frames accumulates in the right direction`() {
        val a = assembler()
        val offsets = listOf(80, 140, 200, 260, 320)

        val placements = offsets.mapNotNull {
            (a.offer(window(it, 120), proxyScale) as? OfferResult.Placed)?.placement
        }

        assertThat(placements).hasSize(offsets.size)
        val xs = placements.map { it.transform.apply(0f, 0f)[0] }
        for (i in 1 until xs.size) {
            assertThat(xs[i]).isGreaterThan(xs[i - 1])
        }
        // Total travel should match the total pan, within accumulated error.
        val travelled = xs.last() - xs.first()
        println("five-frame sweep travelled %.1f against an expected 240".format(travelled))
        assertThat(travelled).isWithin(12f).of(240f)
    }

    @Test
    fun `a two dimensional sweep works as well as a horizontal one`() {
        val a = assembler()
        a.offer(window(100, 100), proxyScale)
        a.offer(window(160, 100), proxyScale)

        val down = a.offer(window(160, 160), proxyScale)

        assertThat(down).isInstanceOf(OfferResult.Placed::class.java)
        val origin = (down as OfferResult.Placed).placement.transform.apply(0f, 0f)
        assertThat(origin[1]).isGreaterThan((1200 - tile) / 2f + 40f)
    }

    // ------------------------------------------------------------------
    // Refusing frames.
    // ------------------------------------------------------------------

    @Test
    fun `a frame that adds nothing new is rejected`() {
        // During a real sweep the camera produces far more frames than the
        // mosaic needs. Compositing every one would cost time and add nothing.
        val a = assembler()
        a.offer(window(100, 100), proxyScale)

        val same = a.offer(window(102, 101), proxyScale)

        assertThat(same).isEqualTo(OfferResult.Rejected(RejectionReason.REDUNDANT))
    }

    @Test
    fun `an unrelated frame is rejected rather than placed somewhere wrong`() {
        // If the user points the phone at something else mid-sweep, placing
        // that frame anywhere would tear the mosaic.
        val a = assembler()
        a.offer(window(100, 100), proxyScale)

        val unrelated = Plane(tile, tile, ByteArray(tile * tile) { 128.toByte() })
        val result = a.offer(unrelated, proxyScale)

        assertThat(result).isEqualTo(OfferResult.Rejected(RejectionReason.UNREGISTRABLE))
    }

    @Test
    fun `a rejected frame does not break the chain`() {
        // The next good frame must still register against the last accepted one
        // rather than against the rejected one.
        val a = assembler()
        a.offer(window(100, 100), proxyScale)
        a.offer(Plane(tile, tile, ByteArray(tile * tile) { 128.toByte() }), proxyScale)

        val good = a.offer(window(160, 100), proxyScale)

        assertThat(good).isInstanceOf(OfferResult.Placed::class.java)
        assertThat(a.placed).hasSize(2)
    }

    // ------------------------------------------------------------------
    // Coverage.
    // ------------------------------------------------------------------

    @Test
    fun `coverage grows as the sweep proceeds`() {
        val a = assembler()
        val before = a.coverage

        a.offer(window(100, 100), proxyScale)
        val afterOne = a.coverage
        a.offer(window(180, 100), proxyScale)
        val afterTwo = a.coverage

        assertThat(before).isEqualTo(0f)
        assertThat(afterOne).isGreaterThan(0f)
        assertThat(afterTwo).isGreaterThan(afterOne)
        println("coverage after one frame %.3f, after two %.3f".format(afterOne, afterTwo))
    }

    @Test
    fun `the coverage grid can be drawn`() {
        // What the capture UI shows the user so they know where to point next.
        val a = assembler()
        a.offer(window(100, 100), proxyScale)

        val grid = a.coverageGrid()

        assertThat(grid).isNotEmpty()
        assertThat(grid.any { row -> row.any { it } }).isTrue()
        assertThat(grid.all { row -> row.all { it } }).isFalse()
    }

    @Test
    fun `resetting clears everything`() {
        val a = assembler()
        a.offer(window(100, 100), proxyScale)
        a.offer(window(160, 100), proxyScale)

        a.reset()

        assertThat(a.placed).isEmpty()
        assertThat(a.coverage).isEqualTo(0f)
        // And the next frame anchors afresh rather than chaining onto the old one.
        val result = a.offer(window(300, 300), proxyScale)
        assertThat(result).isInstanceOf(OfferResult.Placed::class.java)
    }

    @Test
    fun `a raster sweep drifts less than a chain would`() {
        // The reason a frame is registered against every overlapping neighbour
        // rather than only the one before it. In a raster, the tile below the
        // start of the second row has the tile above it as an independent
        // measurement, and using it stops the row starting wherever the first
        // row happened to end up.
        val a = assembler()
        val stepPx = 120

        // First row left to right, second row underneath.
        val offsets = buildList {
            for (col in 0 until 4) add((100 + col * stepPx) to 100)
            for (col in 3 downTo 0) add((100 + col * stepPx) to (100 + stepPx))
        }
        val placed = offsets.mapNotNull {
            (a.offer(window(it.first, it.second), proxyScale) as? OfferResult.Placed)
                ?.placement
        }

        assertThat(placed.size).isAtLeast(6)

        // The tile directly below the first should sit one step down and none
        // across, however far the row travelled in between.
        val first = placed.first().transform.apply(0f, 0f)
        val below = placed.last().transform.apply(0f, 0f)
        println("row wrap: started (%.1f, %.1f), wrapped to (%.1f, %.1f)"
            .format(first[0], first[1], below[0], below[1]))

        assertThat(below[0]).isWithin(14f).of(first[0])
        assertThat(below[1] - first[1]).isWithin(14f).of(stepPx.toFloat())
    }

    @Test
    fun `a proxy scale is applied to the placement`() {
        // Registration runs on downscaled frames; placements must be at full
        // resolution or the whole mosaic assembles at a fraction of its size.
        val full = MosaicAssembler(1400, 1200, tile * 2, tile * 2)

        full.offer(window(100, 100), proxyScale = 2f)
        val second = full.offer(window(160, 100), proxyScale = 2f)

        assertThat(second).isInstanceOf(OfferResult.Placed::class.java)
        val origin = (second as OfferResult.Placed).placement.transform.apply(0f, 0f)
        val anchor = (1400 - tile * 2) / 2f
        // A 60-pixel pan at the proxy is 120 at full resolution.
        assertThat(origin[0]).isWithin(10f).of(anchor + 120f)
        println("scaled placement: %.1f against an expected %.1f".format(origin[0], anchor + 120f))
    }
}
