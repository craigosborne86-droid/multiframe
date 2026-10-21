package dev.multiframe.camera.bench

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class UltraHdrContainerTest {

    private fun jpeg(image: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()

    @Test fun theIndexPointsAtTheGainMapAndBothImagesStillDecode() {
        val primary = jpeg(BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB))
        val map = jpeg(BufferedImage(16, 12, BufferedImage.TYPE_BYTE_GRAY))
        val file = UltraHdrContainer.assemble(primary, map, 1.5f)
        val at = UltraHdrContainer.gainMapOffset(file)!!
        assertThat(file[at]).isEqualTo(0xFF.toByte())
        assertThat(file[at + 1]).isEqualTo(0xD8.toByte())
        val decodedPrimary = ImageIO.read(ByteArrayInputStream(file))
        assertThat(decodedPrimary.width).isEqualTo(64)
        val decodedMap = ImageIO.read(ByteArrayInputStream(file, at, file.size - at))
        assertThat(decodedMap.width).isEqualTo(16)
        val text = String(file, Charsets.ISO_8859_1)
        assertThat(text).contains("hdrgm:Version=\"1.0\"")
        assertThat(text).contains("hdrgm:GainMapMax=\"1.5\"")
        assertThat(text).contains("Item:Length=\"${file.size - at}\"")
    }
}
