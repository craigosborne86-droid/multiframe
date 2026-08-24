package dev.multiframe.camera.pipeline

import androidx.camera.core.ImageProxy

/** A single 8-bit plane with tightly packed rows. */
class Plane(val width: Int, val height: Int, val data: ByteArray)

/**
 * One burst frame as tightly packed 4:2:0 planes.
 *
 * ImageProxy buffers are recycled by CameraX the moment the analyzer returns,
 * so a burst has to own its own copy of every frame.
 */
class YuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val timestampNs: Long,
) {
    val chromaWidth = (width + 1) / 2
    val chromaHeight = (height + 1) / 2

    fun lumaPlane() = Plane(width, height, y)

    fun deepCopy(): YuvFrame =
        YuvFrame(width, height, y.copyOf(), u.copyOf(), v.copyOf(), timestampNs)

    companion object {
        /** Preallocates a frame for reuse by the ring buffer. */
        fun allocate(width: Int, height: Int): YuvFrame {
            val cw = (width + 1) / 2
            val ch = (height + 1) / 2
            return YuvFrame(width, height, ByteArray(width * height), ByteArray(cw * ch), ByteArray(cw * ch), 0L)
        }

        /** Copies into an existing frame, avoiding per-callback allocation. */
        fun copyInto(image: ImageProxy, dest: YuvFrame): YuvFrame {
            val w = image.width
            val h = image.height
            val cw = (w + 1) / 2
            val ch = (h + 1) / 2
            copyPlane(image.planes[0], w, h, dest.y)
            copyPlane(image.planes[1], cw, ch, dest.u)
            copyPlane(image.planes[2], cw, ch, dest.v)
            return YuvFrame(w, h, dest.y, dest.u, dest.v, image.imageInfo.timestamp)
        }

        /** Copies an [ImageProxy], honouring row and pixel strides. */
        fun copyFrom(image: ImageProxy): YuvFrame {
            val w = image.width
            val h = image.height
            val cw = (w + 1) / 2
            val ch = (h + 1) / 2

            val y = ByteArray(w * h)
            val u = ByteArray(cw * ch)
            val v = ByteArray(cw * ch)

            copyPlane(image.planes[0], w, h, y)
            copyPlane(image.planes[1], cw, ch, u)
            copyPlane(image.planes[2], cw, ch, v)

            return YuvFrame(w, h, y, u, v, image.imageInfo.timestamp)
        }

        private fun copyPlane(
            plane: ImageProxy.PlaneProxy,
            width: Int,
            height: Int,
            out: ByteArray,
        ) {
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            if (pixelStride == 1 && rowStride == width) {
                // Tightly packed already.
                buffer.get(out, 0, minOf(out.size, buffer.remaining()))
                return
            }

            val row = ByteArray(rowStride)
            var outPos = 0
            for (r in 0 until height) {
                val remaining = buffer.remaining()
                if (remaining <= 0) break
                val toRead = minOf(rowStride, remaining)
                buffer.get(row, 0, toRead)

                if (pixelStride == 1) {
                    System.arraycopy(row, 0, out, outPos, minOf(width, toRead))
                } else {
                    var src = 0
                    var col = 0
                    while (col < width && src < toRead) {
                        out[outPos + col] = row[src]
                        src += pixelStride
                        col++
                    }
                }
                outPos += width
            }
        }
    }
}
