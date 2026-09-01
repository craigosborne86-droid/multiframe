package dev.multiframe.camera.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "GpuCrossing"

/**
 * What a GPU develop would pay before it did any work at all.
 *
 * The Vulkan plan was dropped once, for a reason the log keeps: the merge was
 * assumed to be dominated by accumulation, the timer was split, and
 * accumulation was 9% of it. The note written then was that the idea was
 * premature rather than wrong, and that `AHardwareBuffer` would matter *when
 * there is something on the GPU worth the crossing*.
 *
 * The develop is now that something. Before anyone writes a GPU develop, this
 * asks the cheap prior question: 25 MB of merged CFA in, 50 MB of RGBA out, and
 * a shader between them chosen to be too cheap to matter. If the round trip
 * alone costs more than the CPU develop, no kernel wins it back.
 *
 * **This is not a claim that a GPU develop would be faster and must never be
 * quoted as one.** The dispatch here is a floor, and any real kernel is added
 * to it.
 */
@RunWith(AndroidJUnit4::class)
class GpuCrossingDeviceTest {

    private val width = 4080
    private val height = 3072

    /**
     * What the GPU is and which routes to it exist, logged before anything is
     * timed.
     *
     * Not an assertion about the answer. A device with no Vulkan, or no memory
     * that is both host-visible and device-local, is a device where the
     * benchmark below reports nothing — and that is a finding rather than a
     * failure. What would be a failure is quietly measuring the staging route
     * under another route's name, which is why [GpuCrossing.probe] builds each
     * one before saying it is available.
     */
    @Test
    fun theGpuSaysWhatRoutesItOffers() {
        val probe = GpuCrossing.probe()
        Log.i(TAG, "probe: $probe")
        DeviceKind.warnIfNotAPhone(TAG)
        assertThat(probe).isNotEmpty()
    }

    /**
     * The harness measured against itself, before it is believed about anything.
     *
     * The same route in both slots. One slot allocates its buffers before the
     * other and submits first in even rounds, so a harness with a thumb on the
     * scale would show it here — and this comparison is more exposed to that
     * than most, since both slots hold 75 MB of GPU-visible memory at once.
     */
    @Test
    fun theHarnessCannotSeparateARouteFromItself() {
        // The route any claim will be about, not whichever the enum lists
        // first. The staging route is both the most expensive and the noisiest
        // here, and an A/A on it would be answering about an instrument nobody
        // is going to quote.
        val route = cheapestRoute() ?: return
        val result = GpuCrossing.bench(width, height, route, route, ROUNDS) ?: return

        Log.i(TAG, "A/A on $route: ${describe(result.a)}")
        Log.i(TAG, "A/A on $route: ${describe(result.b)}")
        val wins = result.a.indices.count { result.b[it].total < result.a[it].total }
        Log.i(TAG, "A/A won $wins of $ROUNDS rounds")
        DeviceKind.warnIfNotAPhone(TAG)

        // A driver that dropped the dispatch would give the fastest figures in
        // this file. Checked before anything is read into them.
        assertThat(result.mismatchesA).isEqualTo(0L)
        assertThat(result.mismatchesB).isEqualTo(0L)

        assertThat(wins).isAtLeast(ROUNDS * 3 / 10)
        assertThat(wins).isAtMost(ROUNDS * 7 / 10)
    }

    /**
     * The crossing, priced against what the CPU develop costs.
     *
     * Reported, not asserted on. What this is for is the size of a prize, and a
     * threshold would only encode today's answer to a question that belongs to
     * whichever phone is running it.
     */
    @Test
    fun theRoundTripIsPricedAgainstTheDevelop() {
        val trips = measureEveryRoute()
        if (trips.isEmpty()) {
            Log.w(TAG, "no GPU route on this device; nothing to price")
            return
        }
        DeviceKind.warnIfNotAPhone(TAG)

        // The comparison the decision rests on, said in one line so nobody has
        // to assemble it from four. The develop's own figure belongs to the
        // same phone and the same session as this one, or it means nothing.
        val best = trips.minBy { it.value }
        Log.i(
            TAG,
            "the cheapest route is ${best.key} at ${best.value}ms of round trip " +
                "before any kernel runs. Read that against this session's develop, " +
                "not against a figure from another day.",
        )
    }

    /**
     * Every route, timed the same way, with the median round trip kept.
     *
     * Shared between the pricing test and the A/A, so that the A/A is run on
     * the route the pricing actually quotes.
     */
    private fun measureEveryRoute(): Map<GpuCrossing.Route, Long> {
        val routes = availableRoutes()
        Log.i(TAG, "routes available: $routes")
        val trips = LinkedHashMap<GpuCrossing.Route, Long>()
        for (route in routes) {
            val result = GpuCrossing.bench(width, height, route, route, ROUNDS) ?: continue
            if (result.mismatchesA != 0L) {
                // Reported rather than thrown: a route that comes back wrong is
                // a fact about the driver, and one the log should carry.
                Log.w(TAG, "$route returned ${result.mismatchesA} wrong pixels; not priced")
                continue
            }
            val roundTrips = result.a.map { it.roundTrip }.sorted()
            val uploads = result.a.map { it.upload }.sorted()
            val downloads = result.a.map { it.download }.sorted()
            val dispatches = result.a.map { it.dispatch }.sorted()
            trips[route] = roundTrips[roundTrips.size / 2] / 1000
            Log.i(
                TAG,
                ("%s: setup %dms; upload %dms, dispatch %dms, download %dms; " +
                    "round trip %dms (range %d-%d)").format(
                    route, result.setupMicros / 1000,
                    uploads[uploads.size / 2] / 1000,
                    dispatches[dispatches.size / 2] / 1000,
                    downloads[downloads.size / 2] / 1000,
                    roundTrips[roundTrips.size / 2] / 1000,
                    roundTrips.first() / 1000, roundTrips.last() / 1000,
                ),
            )
        }
        return trips
    }

    /**
     * Whether owning the memory removes the last copy, which is RingBuffer.h's
     * claim and the only reason to change how the ring allocates.
     *
     * Skipped rather than failed where the extension is absent: that is a fact
     * about the driver, and one worth reading in the log rather than in a red
     * test.
     */
    @Test
    fun importingAHardwareBufferIsComparedWithCopyingIntoOne() {
        val routes = availableRoutes()
        if (GpuCrossing.Route.IMPORTED !in routes || GpuCrossing.Route.SHARED !in routes) {
            Log.w(TAG, "not both routes here (have $routes); nothing to compare")
            return
        }
        val result = GpuCrossing.bench(
            width, height, GpuCrossing.Route.SHARED, GpuCrossing.Route.IMPORTED, ROUNDS,
        ) ?: return

        val shared = result.a.map { it.roundTrip }
        val imported = result.b.map { it.roundTrip }
        val wins = shared.indices.count { imported[it] < shared[it] }
        val ratios = shared.indices.map { shared[it].toDouble() / imported[it] }.sorted()
        Log.i(TAG, "shared:   ${describe(result.a)}")
        Log.i(TAG, "imported: ${describe(result.b)}")
        Log.i(
            TAG,
            "importing is %.2fx of copying in, %d of %d rounds".format(
                ratios[ratios.size / 2], wins, ROUNDS,
            ),
        )
        DeviceKind.warnIfNotAPhone(TAG)
        assertThat(result.mismatchesA).isEqualTo(0L)

        // The imported route is kept locked for the whole benchmark rather than
        // locked per crossing, and its memory need not be coherent. If this
        // ever reports mismatches, that is the finding: the route works but
        // costs a lock round trip a real pipeline would have to pay.
        Log.i(TAG, "imported mismatches: ${result.mismatchesB}")
    }

    private fun availableRoutes(): List<GpuCrossing.Route> {
        val probe = GpuCrossing.probe()
        return GpuCrossing.Route.entries.filter {
            probe.contains("${it.name.lowercase()} yes")
        }
    }

    /** The route with the lowest round trip, which is the one worth A/A-ing. */
    private fun cheapestRoute(): GpuCrossing.Route? =
        measureEveryRoute().minByOrNull { it.value }?.key.also {
            if (it == null) Log.w(TAG, "no GPU route on this device")
        }

    private fun describe(v: List<GpuCrossing.Crossing>): String {
        val trips = v.map { it.roundTrip }.sorted()
        return "round trip median ${trips[trips.size / 2] / 1000}ms, " +
            "range ${trips.first() / 1000}-${trips.last() / 1000}ms"
    }

    private companion object {
        /**
         * Forty, for the reason the rest of this project uses forty: at twenty
         * rounds the 30-70% band an A/A is held to is outside a fair coin's
         * range 4.1% of the time. Each round moves 75 MB twice, which makes
         * this the most expensive harness here and still worth the rounds.
         */
        const val ROUNDS = 40
    }
}
