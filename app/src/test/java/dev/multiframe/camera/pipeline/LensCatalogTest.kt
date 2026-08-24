package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Lens identification, tested against real sensor geometry from phones this
 * development device is not.
 */
class LensCatalogTest {

    private fun lens(
        id: String,
        equivalent: Int,
        area: Float,
        back: Boolean = true,
        raw: Boolean = true,
    ) = Lens(id, back, 1f, equivalent, 1f, raw, 1.7f, area)

    @Test
    fun `a full frame lens is its own equivalent`() {
        // 36x24mm is the reference, so the conversion must be the identity here.
        assertThat(LensCatalog.equivalent35mm(50f, 36f, 24f)).isEqualTo(50)
    }

    @Test
    fun `phone sensor focal lengths convert to recognisable numbers`() {
        // Pixel-class main camera: about 6.9mm on a 1/1.3" sensor reads as the
        // 24mm everyone expects a phone's main camera to be.
        val main = LensCatalog.equivalent35mm(6.9f, 9.8f, 7.3f)
        assertThat(main).isIn(22..26)

        // An ultra-wide on a smaller sensor lands near 13mm.
        val wide = LensCatalog.equivalent35mm(2.2f, 5.6f, 4.2f)
        assertThat(wide).isIn(11..18)
    }

    @Test
    fun `degenerate geometry does not produce a nonsense focal length`() {
        assertThat(LensCatalog.equivalent35mm(0f, 9.8f, 7.3f)).isEqualTo(0)
        assertThat(LensCatalog.equivalent35mm(6.9f, 0f, 0f)).isEqualTo(0)
    }

    @Test
    fun `zoom is measured from the largest rear sensor`() {
        // On every multi-camera phone the primary has the biggest sensor, and
        // the wide and tele are built around smaller ones. That is what makes
        // it derivable rather than something to hard-code.
        val lenses = listOf(
            lens("2", equivalent = 13, area = 20f),     // ultra-wide
            lens("0", equivalent = 24, area = 70f),     // main, largest sensor
            lens("3", equivalent = 120, area = 25f),    // telephoto
        )

        val scaled = LensCatalog.assignZoomFactors(lenses)

        assertThat(scaled.first { it.cameraId == "0" }.zoomFactor).isWithin(1e-3f).of(1f)
        assertThat(scaled.first { it.cameraId == "2" }.zoomFactor).isWithin(0.01f).of(13f / 24f)
        assertThat(scaled.first { it.cameraId == "3" }.zoomFactor).isWithin(0.01f).of(5f)
    }

    @Test
    fun `a front camera does not become the zoom reference`() {
        // A selfie camera can have a large sensor. Measuring rear zoom from it
        // would label the main camera as something other than 1x.
        val lenses = listOf(
            lens("1", equivalent = 20, area = 90f, back = false),
            lens("0", equivalent = 24, area = 70f),
        )

        val scaled = LensCatalog.assignZoomFactors(lenses)

        assertThat(scaled.first { it.cameraId == "0" }.zoomFactor).isWithin(1e-3f).of(1f)
    }

    @Test
    fun `a single camera device still works`() {
        val scaled = LensCatalog.assignZoomFactors(listOf(lens("0", 26, 50f)))

        assertThat(scaled).hasSize(1)
        assertThat(scaled[0].zoomFactor).isWithin(1e-3f).of(1f)
    }

    @Test
    fun `no cameras at all is not a crash`() {
        assertThat(LensCatalog.assignZoomFactors(emptyList())).isEmpty()
        assertThat(LensCatalog.default(emptyList())).isNull()
        assertThat(LensCatalog.rear(emptyList())).isEmpty()
    }

    @Test
    fun `the default lens is the main rear camera`() {
        val lenses = LensCatalog.assignZoomFactors(
            listOf(
                lens("2", 13, 20f),
                lens("0", 24, 70f),
                lens("3", 120, 25f),
                lens("1", 20, 90f, back = false),
            )
        )

        assertThat(LensCatalog.default(lenses)?.cameraId).isEqualTo("0")
    }

    @Test
    fun `lenses are labelled in millimetres, which is the photographer's unit`() {
        // "24mm" and "120mm" describe what the frame will look like. "1x" and
        // "5x" only describe a ratio between two parts of this particular phone.
        val scaled = LensCatalog.assignZoomFactors(
            listOf(lens("0", 24, 70f), lens("3", 120, 25f))
        )

        assertThat(scaled.first { it.cameraId == "0" }.label).isEqualTo("24mm")
        assertThat(scaled.first { it.cameraId == "3" }.label).isEqualTo("120mm")
        assertThat(scaled.first { it.cameraId == "3" }.zoomLabel).isEqualTo("5x")
    }

    @Test
    fun `the same optic advertised several times is shown once`() {
        // A real device reports its main camera three times: standalone, as a
        // member of one logical group, and as a member of another. The lens
        // strip has to show one 24mm, not three.
        val duplicates = listOf(
            lens("0", 24, 70f),
            lens("2", 24, 70f).copy(logicalId = "0"),
            lens("9", 24, 68f).copy(logicalId = "0"),
            lens("3", 12, 20f).copy(logicalId = "0"),
        )

        val unique = LensCatalog.deduplicate(duplicates)

        assertThat(unique.map { it.equivalent35mm }.sorted()).containsExactly(12, 24)
        // The one that can be opened without the logical-camera machinery wins.
        assertThat(unique.first { it.equivalent35mm == 24 }.cameraId).isEqualTo("0")
    }

    @Test
    fun `nearly equal focal lengths are treated as one lens`() {
        // A real device reports the same main camera as both 24mm and 25mm.
        // Exact matching would leave a visible duplicate in the lens strip.
        val nearlySame = listOf(
            lens("0", 24, 70f),
            lens("9", 25, 68f).copy(logicalId = "0"),
        )

        assertThat(LensCatalog.deduplicate(nearlySame)).hasSize(1)
    }

    @Test
    fun `genuinely different optics are never merged`() {
        // The gaps between real lenses are factors of two, so the clustering
        // has a lot of room before it could collapse two actual lenses.
        val real = listOf(
            lens("3", 12, 20f),
            lens("0", 24, 70f),
            lens("5", 49, 60f),
            lens("4", 110, 25f),
            lens("6", 220, 24f),
        )

        assertThat(LensCatalog.deduplicate(real)).hasSize(5)
    }

    @Test
    fun `a front and rear lens of the same focal length are both kept`() {
        // Deduplication is by facing as well as focal length, or a 20mm selfie
        // camera would silently remove a 20mm rear lens.
        val both = listOf(
            lens("0", 20, 70f),
            lens("1", 20, 40f, back = false),
        )

        assertThat(LensCatalog.deduplicate(both)).hasSize(2)
    }

    @Test
    fun `a fractional zoom keeps one decimal`() {
        val scaled = LensCatalog.assignZoomFactors(
            listOf(lens("0", 24, 70f), lens("2", 13, 20f))
        )

        assertThat(scaled.first { it.cameraId == "2" }.zoomLabel).isEqualTo("0.5x")
    }
}
