package dev.multiframe.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/** Failure-injection protocol tests; no JNI, image-quality or physical fallback claim. */
class SubPixelSubmissionTest {
    private val alternates = 4

    private data class Run(
        val output: String,
        val events: List<String>,
        val finishCalls: Int,
        val developCalls: Int,
        val closeCalls: Int,
        val failure: String?,
    )

    private fun capture(rejectReference: Boolean = false, rejectAlternate: Int? = null): Run {
        val events = ArrayList<String>()
        var finishCalls = 0
        var developCalls = 0
        var closeCalls = 0
        var failure: String? = null
        val output = try {
            submitSubPixelFrames(
                alternateCount = alternates,
                setReference = {
                    events.add("reference")
                    !rejectReference
                },
                addFrame = { i ->
                    events.add("placing ${i + 2}/${alternates + 1}")
                    events.add("add[$i]")
                    rejectAlternate != i
                },
            )
            events.add("resolving")
            finishCalls++
            // The remaining backend deliberately reports acceptable coverage.
            // Coverage must never rescue a rejected frame submission.
            events.add("finish:accepted")
            developCalls++
            events.add("develop")
            "sr"
        } catch (e: IllegalStateException) {
            failure = e.message
            closeCalls++
            events.add("close")
            events.add("native-fallback")
            "native"
        }
        println("rejectReference=$rejectReference rejectAlternate=$rejectAlternate output=$output finish=$finishCalls develop=$developCalls events=$events")
        return Run(output, events, finishCalls, developCalls, closeCalls, failure)
    }

    private fun submittedAlternates(count: Int): List<String> = buildList {
        for (i in 0 until count) {
            add("placing ${i + 2}/${alternates + 1}")
            add("add[$i]")
        }
    }

    private fun assertRejected(run: Run, submitted: Int, message: String) {
        assertEquals("a rejected submission selects native fallback", "native", run.output)
        assertEquals("finish is not reached", 0, run.finishCalls)
        assertEquals("develop is not reached", 0, run.developCalls)
        assertEquals("failed attempt closes once", 1, run.closeCalls)
        assertEquals("explicit rejection reason", message, run.failure)
        assertEquals("reference, progress and submissions stop at first rejection",
            listOf("reference") + submittedAlternates(submitted) + listOf("close", "native-fallback"), run.events)
    }

    @Test fun aRejectedReferenceStopsBeforeAnyAlternateOrFinish() {
        assertRejected(capture(rejectReference = true), 0, "2x reference submission rejected")
    }

    @Test fun aRejectedFirstAlternateStopsBeforeTheRemainingFramesOrFinish() {
        assertRejected(capture(rejectAlternate = 0), 1, "2x frame 2/5 submission rejected")
    }

    @Test fun aRejectedMiddleAlternateStopsBeforeTheRemainingFramesOrFinish() {
        assertRejected(capture(rejectAlternate = 2), 3, "2x frame 4/5 submission rejected")
    }

    @Test fun aRejectedLastAlternateStopsBeforeFinishEvenIfRemainingCoverageWouldPass() {
        assertRejected(capture(rejectAlternate = 3), 4, "2x frame 5/5 submission rejected")
    }

    @Test fun validSubmissionsKeepOrderProgressAndSuccessfulDevelop() {
        val run = capture()
        assertEquals("sr", run.output)
        assertEquals(1, run.finishCalls)
        assertEquals(1, run.developCalls)
        assertEquals(0, run.closeCalls)
        assertEquals(null, run.failure)
        assertEquals(listOf("reference") + submittedAlternates(4) + listOf("resolving", "finish:accepted", "develop"), run.events)
    }
}
