package dev.multiframe.camera.pipeline

/** User-selectable burst depth. Hardware may offer fewer frames for a given lens. */
object FrameCountPolicy {
    // Craig confirmed 28: the deepest ring (RawRingBudget.MAX_CAPACITY, 32) less the
    // slots it keeps writable during a merge (RawRingBudget.WRITE_HEADROOM). The ring
    // a device actually gets is sized by its memory, and the shutter refuses a burst
    // deeper than it holds; see RawRingBudget.burstForDepth.
    const val MAX_FRAMES = 28

    val STEPS: List<Int> = listOf(1, 2, 4, 8, 12, 16, 24, MAX_FRAMES)

    fun stepsUpTo(hardwareCeiling: Int): List<Int> =
        STEPS.filter { it <= hardwareCeiling }.ifEmpty { listOf(STEPS.first()) }

    /** Fold older persisted values, including the former 32-frame option, onto a real detent. */
    fun normalize(value: Int): Int = STEPS.lastOrNull { it <= value } ?: STEPS.first()

    fun next(current: Int, hardwareCeiling: Int): Int {
        val steps = stepsUpTo(hardwareCeiling)
        val index = steps.indexOf(current)
        return if (index < 0) steps.first() else steps[(index + 1) % steps.size]
    }
}
