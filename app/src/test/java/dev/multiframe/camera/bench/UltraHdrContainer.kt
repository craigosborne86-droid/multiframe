package dev.multiframe.camera.bench

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An Ultra HDR JPEG assembled by hand, for the desk alone.
 *
 * On the phone the platform writes the container (`UltraHdr`, `Bitmap.
 * compress` with a `Gainmap`). The desk has no platform, so the bench joins
 * two JPEGs the way it does: the primary picture with the `hdrgm` XMP and an
 * MPF index naming a second image, and behind its end the gain map with its
 * own `hdrgm` XMP carrying the ratio range. That is the Ultra HDR v1 layout,
 * which Chrome, Android 14+ and Lightroom read; the ISO 21496-1 binary block
 * that Android 15 adds beside it is not written here.
 */
object UltraHdrContainer {

    private const val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val MPF_SEGMENT = 90

    /** Joins [primary] and [gainMap] (each a complete JPEG); [maxLog2] is log2 of the map's top ratio. */
    fun assemble(primary: ByteArray, gainMap: ByteArray, maxLog2: Float): ByteArray {
        val map = insertAfterHeader(gainMap, app1(gainMapXmp(maxLog2)))
        val xmp = app1(primaryXmp(map.size))
        val head = headerEnd(primary)
        val primarySize = primary.size + xmp.size + MPF_SEGMENT
        // The MPF offsets count from its TIFF header: 4 bytes of marker and
        // length and 4 of "MPF\0" into the segment.
        val tiffStart = head + xmp.size + 8
        val mpf = mpf(primarySize, map.size, primarySize - tiffStart)
        check(mpf.size == MPF_SEGMENT)
        return ByteArrayOutputStream(primarySize + map.size).apply {
            write(primary, 0, head)
            write(xmp)
            write(mpf)
            write(primary, head, primary.size - head)
            write(map)
        }.toByteArray()
    }

    private fun primaryXmp(gainMapLength: Int) = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Multiframe">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0">
              <Container:Directory>
                <rdf:Seq>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/>
                  </rdf:li>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="$gainMapLength"/>
                  </rdf:li>
                </rdf:Seq>
              </Container:Directory>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    // The same parameters UltraHdr gives the platform: ratio 1 to the max, gamma 1, no offsets.
    private fun gainMapXmp(maxLog2: Float) = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Multiframe">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0" hdrgm:GainMapMin="0" hdrgm:GainMapMax="$maxLog2" hdrgm:Gamma="1" hdrgm:OffsetSDR="0" hdrgm:OffsetHDR="0" hdrgm:HDRCapacityMin="0" hdrgm:HDRCapacityMax="$maxLog2" hdrgm:BaseRenditionIsHDR="False"/>
          </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    private fun app1(xmp: String): ByteArray {
        val payload = (XMP_ID + xmp).toByteArray(Charsets.UTF_8)
        require(payload.size + 2 <= 0xFFFF) { "XMP too long for one segment" }
        return ByteBuffer.allocate(payload.size + 4).order(ByteOrder.BIG_ENDIAN)
            .put(0xFF.toByte()).put(0xE1.toByte()).putShort((payload.size + 2).toShort()).put(payload).array()
    }

    /** CIPA DC-007: the index, big-endian, with the primary at 0 and the map at [mapOffset]. */
    private fun mpf(primarySize: Int, mapSize: Int, mapOffset: Int): ByteArray {
        val b = ByteBuffer.allocate(MPF_SEGMENT).order(ByteOrder.BIG_ENDIAN)
        b.put(0xFF.toByte()).put(0xE2.toByte()).putShort((MPF_SEGMENT - 2).toShort())
        b.put("MPF\u0000".toByteArray(Charsets.US_ASCII))
        b.put("MM".toByteArray(Charsets.US_ASCII)).putShort(0x2A).putInt(8)
        b.putShort(3)
        b.putShort(0xB000.toShort()).putShort(7).putInt(4).put("0100".toByteArray(Charsets.US_ASCII))
        b.putShort(0xB001.toShort()).putShort(4).putInt(1).putInt(2)
        b.putShort(0xB002.toShort()).putShort(7).putInt(32).putInt(8 + 2 + 3 * 12 + 4)
        b.putInt(0)
        // Primary: baseline JPEG, primary image. Then the map: a JPEG, nothing more.
        b.putInt(0x030000).putInt(primarySize).putInt(0).putShort(0).putShort(0)
        b.putInt(0).putInt(mapSize).putInt(mapOffset).putShort(0).putShort(0)
        return b.array()
    }

    private fun insertAfterHeader(jpeg: ByteArray, segment: ByteArray): ByteArray {
        val at = headerEnd(jpeg)
        return jpeg.copyOfRange(0, at) + segment + jpeg.copyOfRange(at, jpeg.size)
    }

    /** After SOI and a JFIF APP0, where one is: the place for the segments that follow it. */
    private fun headerEnd(jpeg: ByteArray): Int {
        require(jpeg.size > 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a JPEG" }
        if (jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte()) {
            val length = ((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF)
            return 4 + length
        }
        return 2
    }

    /** The second image's offset from the MPF index, read back: for the test that the file can be read. */
    fun gainMapOffset(file: ByteArray): Int? {
        var i = 2
        while (i + 4 < file.size && file[i] == 0xFF.toByte()) {
            val marker = file[i + 1].toInt() and 0xFF
            val length = ((file[i + 2].toInt() and 0xFF) shl 8) or (file[i + 3].toInt() and 0xFF)
            if (marker == 0xE2 && String(file, i + 4, 4, Charsets.US_ASCII) == "MPF\u0000") {
                val tiff = i + 8
                val b = ByteBuffer.wrap(file).order(ByteOrder.BIG_ENDIAN)
                val entries = tiff + b.getInt(tiff + 8 + 2 + 2 * 12 + 8)
                return tiff + b.getInt(entries + 16 + 8)
            }
            if (marker == 0xDA) return null
            i += 2 + length
        }
        return null
    }
}
