/* Portions adapted from Adobe DNG SDK dng_temperature.cpp.
 * Copyright 2006–2019 Adobe Systems Incorporated. All Rights Reserved.
 * Adobe permits use, modification and distribution under the accompanying SDK
 * licence, reproduced in app/src/main/assets/dng-sdk-LICENSE.txt.
 * This product includes DNG technology under license by Adobe.
 */
package dev.multiframe.camera.pipeline

import android.hardware.camera2.CameraMetadata
import kotlin.math.sqrt

/** WB is a capture parameter independent of exposure/focus and panel visibility. */
enum class WhiteBalanceMode { AUTO, LOCK, MANUAL }
fun ManualSettings.whiteBalanceLabel(): String = when (whiteBalanceMode) {
    WhiteBalanceMode.AUTO -> if (awbMode == CameraMetadata.CONTROL_AWB_MODE_AUTO && !manualColor) "Auto WB" else "WB preset / lock"
    WhiteBalanceMode.LOCK -> "Locked WB"
    WhiteBalanceMode.MANUAL -> "$kelvin K · Tint ${if (tint >= 0) "+" else ""}$tint"
}

object CalibratedWhiteBalance {
    data class Solution(val gains: FloatArray, val transform: Mat3, val neutral: FloatArray)
    /** Only interpolate within the sensor's measured illuminants; no profile extrapolation. */
    fun range(calibration: ColorScience.Calibration?): IntRange? = calibration?.takeIf { it.hasIlluminants }?.let {
        minOf(it.illuminant1Kelvin!!, it.illuminant2Kelvin!!) .. maxOf(it.illuminant1Kelvin!!, it.illuminant2Kelvin!!)
    }
    fun resolve(cal: ColorScience.Calibration?, kelvin: Int, tint: Int): Solution? {
        val c = cal ?: return null
        val supported = range(c) ?: return null
        if (kelvin !in supported || tint !in -100..100) return null
        val (x, y) = chromaticity(kelvin, tint)
        val xyz = floatArrayOf((x / y).toFloat(), 1f, ((1 - x - y) / y).toFloat())
        val blend = ColorScience.blendForTemperature(kelvin, c.illuminant1Kelvin!!, c.illuminant2Kelvin!!)
        val xyzToCamera = c.analogBalance * Mat3.lerp(c.calibration1, c.calibration2, blend) *
            Mat3.lerp(c.colorMatrix1, c.colorMatrix2, blend)
        val neutral = FloatArray(3)
        xyzToCamera.apply(xyz[0], xyz[1], xyz[2], neutral)
        if (neutral.any { !it.isFinite() || it <= 0f }) return null
        val normal = neutral.max()
        val gains = floatArrayOf(normal / neutral[0], normal / neutral[1], normal / neutral[1], normal / neutral[2])
        val matrix = ColorScience.balancedCameraToSrgb(c, neutral, blend, gains)
        if (matrix.m.any { !it.isFinite() }) return null
        return Solution(gains, matrix, neutral)
    }
    /** Robertson uv isotherms; Wyszecki & Stiles Color Science p228.
     * Temperature/tint conversion follows Adobe DNG SDK dng_temperature.cpp
     * LegacyGetXY (2006–2019 Adobe); SDK licence in assets/dng-sdk-LICENSE.txt.
     * DNG Chapter 6 supplies the XYZ → camera neutral and balanced transform.
     */
    fun chromaticity(kelvin: Int, tint: Int): Pair<Double, Double> {
        require(kelvin >= 1667 && kelvin <= 50000 && tint in -100..100)
        val reciprocal = 1e6 / kelvin
        val i = (0 until table.lastIndex).firstOrNull { reciprocal < table[it + 1][0] } ?: table.lastIndex - 1
        val a = table[i]; val b = table[i + 1]
        val f = (b[0] - reciprocal) / (b[0] - a[0])
        val u0 = a[1] * f + b[1] * (1 - f)
        val v0 = a[2] * f + b[2] * (1 - f)
        val n1 = sqrt(1 + a[3] * a[3]); val n2 = sqrt(1 + b[3] * b[3])
        var du = f / n1 + (1 - f) / n2
        var dv = f * a[3] / n1 + (1 - f) * b[3] / n2
        val length = sqrt(du * du + dv * dv); du /= length; dv /= length
        val u = u0 + du * tint / -3000.0
        val v = v0 + dv * tint / -3000.0
        return (1.5 * u / (u - 4 * v + 2)) to (v / (u - 4 * v + 2))
    }
    private val table = arrayOf(
        doubleArrayOf(0.0, 0.18006, 0.26352, -0.24341),
        doubleArrayOf(10.0, 0.18066, 0.26589, -0.25479),
        doubleArrayOf(20.0, 0.18133, 0.26846, -0.26876),
        doubleArrayOf(30.0, 0.18208, 0.27119, -0.28539),
        doubleArrayOf(40.0, 0.18293, 0.27407, -0.30470),
        doubleArrayOf(50.0, 0.18388, 0.27709, -0.32675),
        doubleArrayOf(60.0, 0.18494, 0.28021, -0.35156),
        doubleArrayOf(70.0, 0.18611, 0.28342, -0.37915),
        doubleArrayOf(80.0, 0.18740, 0.28668, -0.40955),
        doubleArrayOf(90.0, 0.18880, 0.28997, -0.44278),
        doubleArrayOf(100.0, 0.19032, 0.29326, -0.47888),
        doubleArrayOf(125.0, 0.19462, 0.30141, -0.58204),
        doubleArrayOf(150.0, 0.19962, 0.30921, -0.70471),
        doubleArrayOf(175.0, 0.20525, 0.31647, -0.84901),
        doubleArrayOf(200.0, 0.21142, 0.32312, -1.0182),
        doubleArrayOf(225.0, 0.21807, 0.32909, -1.2168),
        doubleArrayOf(250.0, 0.22511, 0.33439, -1.4512),
        doubleArrayOf(275.0, 0.23247, 0.33904, -1.7298),
        doubleArrayOf(300.0, 0.24010, 0.34308, -2.0637),
        doubleArrayOf(325.0, 0.24702, 0.34655, -2.4681),
        doubleArrayOf(350.0, 0.25591, 0.34951, -2.9641),
        doubleArrayOf(375.0, 0.26400, 0.35200, -3.5814),
        doubleArrayOf(400.0, 0.27218, 0.35407, -4.3633),
        doubleArrayOf(425.0, 0.28039, 0.35577, -5.3762),
        doubleArrayOf(450.0, 0.28863, 0.35714, -6.7262),
        doubleArrayOf(475.0, 0.29685, 0.35823, -8.5955),
        doubleArrayOf(500.0, 0.30505, 0.35907, -11.324),
        doubleArrayOf(525.0, 0.31320, 0.35968, -15.628),
        doubleArrayOf(550.0, 0.32129, 0.36011, -23.325),
        doubleArrayOf(575.0, 0.32931, 0.36038, -40.770),
        doubleArrayOf(600.0, 0.33724, 0.36051, -116.45),
    )
}

internal fun CalibratedWhiteBalance.Solution.cameraGains() = android.hardware.camera2.params.RggbChannelVector(gains[0], gains[1], gains[2], gains[3])
internal fun CalibratedWhiteBalance.Solution.cameraTransform() = android.hardware.camera2.params.ColorSpaceTransform(
    Array(9) { android.util.Rational((transform.m[it] * 1_000_000).toInt(), 1_000_000) })
