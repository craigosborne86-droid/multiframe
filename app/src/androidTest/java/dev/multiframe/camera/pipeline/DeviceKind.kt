package dev.multiframe.camera.pipeline

import android.os.Build
import android.util.Log

/**
 * Whether this is a phone or an emulator, for the benefit of anything timed.
 *
 * The rule in this project is that no timing is ever taken from the emulator.
 * The reason is usually assumed to be that an emulator is slow, and it is worth
 * recording that on this setup it is the opposite -- an arm64 image on Apple
 * silicon has the host's cores and the host's memory bandwidth, and reports:
 *
 *     sharpen at 4080x3072   emulator 6 ms      phone 37-100 ms
 *     jpeg strips at q95     emulator 8 ms      phone 48-107 ms
 *     develop at 4080x3072   emulator 95 ms     phone 227-400 ms
 *
 * Ten times faster on the pass that matters most, which is the dangerous
 * direction: a regression that doubled a phone's cost would still look healthy
 * here. So the harnesses say plainly, in the same log line as the figure, when
 * the figure came from a machine that is not a phone.
 */
object DeviceKind {

    val isEmulator: Boolean by lazy {
        Build.HARDWARE in setOf("goldfish", "ranchu", "gce_x86", "cutf_cvm") ||
            Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("vbox") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.startsWith("sdk_") ||
            Build.PRODUCT.startsWith("sdk_")
    }

    /**
     * Says so, loudly, when a timing did not come from a phone.
     *
     * Deliberately not a skip. These harnesses are worth running here for what
     * they check -- that the pass runs, returns, and produces a picture -- and
     * only the number is meaningless.
     */
    fun warnIfNotAPhone(tag: String) {
        if (isEmulator) {
            Log.w(
                tag,
                "THIS TIMING IS NOT A PHONE'S. ${Build.MODEL} is an emulator, and on " +
                    "this setup it runs the imaging passes several times faster than " +
                    "the device does. The figure above says the pass ran; it says " +
                    "nothing about what a capture costs.",
            )
        }
    }
}
