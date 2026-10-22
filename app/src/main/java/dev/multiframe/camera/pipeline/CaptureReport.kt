package dev.multiframe.camera.pipeline

import java.util.Locale
import kotlin.math.ln

/**
 * How a photograph was made, carried inside the photograph.
 *
 * ### Why it lives in the file
 *
 * The app's promise is that every pixel is a measurement. A page that says so
 * is worth little if it is the app's own word, held in the app's own storage:
 * the picture leaves the phone and the account of it stays behind. Written
 * into the JPEG's EXIF UserComment, the account travels with the picture --
 * the Lightbox reads it back from the file, and so can anyone else.
 *
 * ### The format
 *
 * One line, `MFR1;key=value;...`, ASCII, short keys. Versioned by the prefix
 * so a later field can be added without breaking an older reader; a reader
 * ignores keys it does not know and a value it cannot parse.
 *
 * ### What the noise figure means
 *
 * Averaging n independent frames divides the noise by √n. The merge does not
 * give every sample full weight -- motion rejection drops what moved -- so the
 * effective count is n times the mean contribution, and the gain in stops is
 * ½·log₂(n·c). It is the photon arithmetic, not a measurement on this picture;
 * the page says "about".
 */
data class CaptureReport(
    /** What kind of picture: [KIND_BURST], [KIND_NIGHT], [KIND_LONG], [KIND_SUPER_RES]. */
    val kind: String,
    val framesRequested: Int,
    val framesMerged: Int,
    /** Mean weight a merged sample kept, 0..1; 1 under the long exposure's rejection-off merge. */
    val meanContribution: Float,
    /** First frame to last, milliseconds. */
    val spanMillis: Long,
    val exposureTimeNs: Long?,
    val iso: Int?,
    val lens: String?,
    /** "aligned" or "tripod" (frames summed where they landed). */
    val alignment: String,
    val localTone: Boolean,
    val hdr: Boolean,
    /** The plan, in words, where one chose the exposure: NIGHT's hand class, a long exposure's span. */
    val plan: String? = null,
    val mergeMillis: Long = 0,
    /** The opt-in, non-generative analysis models that shaped this picture (Labs), in the order they ran. */
    val analysis: List<AnalysisUse> = emptyList(),
) {
    /** Whether a simulated background blur (Labs) was applied: the one use the picture must call simulated. */
    val simulatedBlur: Boolean get() = analysis.any { it.role == AnalysisUse.PORTRAIT_ROLE }

    /** The noise reduction the merge's arithmetic gives, in stops; zero for a single frame. */
    val noiseGainStops: Float
        get() {
            val effective = framesMerged * meanContribution.coerceIn(0f, 1f)
            return if (effective <= 1f) 0f else (0.5 * ln(effective.toDouble()) / ln(2.0)).toFloat()
        }

    fun encode(): String = buildString {
        append(PREFIX)
        fun put(key: String, value: Any?) {
            if (value == null) return
            val text = value.toString().replace(";", ",").replace("=", ":")
            append(';').append(key).append('=').append(text)
        }
        put("k", kind)
        put("fr", framesRequested)
        put("fm", framesMerged)
        put("c", "%.3f".format(Locale.ROOT, meanContribution))
        put("sp", spanMillis)
        put("t", exposureTimeNs)
        put("iso", iso)
        put("l", lens)
        put("a", alignment)
        put("lt", if (localTone) 1 else 0)
        put("hdr", if (hdr) 1 else 0)
        put("p", plan)
        put("mm", mergeMillis)
        put("ml", analysis.takeIf { it.isNotEmpty() }?.joinToString("|") { "${codeOf(it)}~${it.model.replace("|", "/").replace("~", "-")}" })
    }

    /** The rows the Lightbox shows: a label and its value, in reading order. */
    fun rows(locale: Locale = Locale.getDefault()): List<Pair<String, String>> = buildList {
        add("Picture" to when (kind) {
            KIND_NIGHT -> "NIGHT burst"
            KIND_LONG -> "Long exposure"
            KIND_SUPER_RES -> "2× super-resolution"
            KIND_STACK -> "Focus stack (Labs)"
            KIND_ASTRO -> "Astro (night sky)"
            else -> "Burst"
        })
        plan?.let { add("Plan" to it) }
        add("Frames" to if (framesRequested > framesMerged && framesRequested > 0)
            "$framesMerged merged of $framesRequested" else "$framesMerged merged")
        if (kind != KIND_LONG && kind != KIND_STACK) add("Kept" to "%.0f%% of samples (motion rejected the rest)".format(locale, meanContribution * 100))
        if (noiseGainStops > 0f) add("Noise" to "about %.1f stops cleaner than one frame".format(locale, noiseGainStops))
        if (spanMillis > 0) add("Span" to if (spanMillis >= 1000) "%.1f s".format(locale, spanMillis / 1000f) else "$spanMillis ms")
        val shutter = exposureTimeNs?.let { ManualSettings(exposureTimeNs = it).shutterLabel(locale) }
        if (shutter != null || iso != null) add("Each frame" to listOfNotNull(shutter, iso?.let { "ISO $it" }).joinToString(" · "))
        lens?.let { add("Lens" to it) }
        add("Alignment" to if (alignment == ALIGN_TRIPOD) "none: on a tripod, summed in place" else "per tile, even CFA shifts")
        add("Rendering" to listOfNotNull(
            if (localTone) "local tone" else "global tone",
            if (hdr) "HDR gain map" else null,
        ).joinToString(" · "))
        for (use in analysis) {
            if (use.role == AnalysisUse.PORTRAIT_ROLE) add("Background blur" to "simulated (Labs): ${use.model} ${use.role}")
            else add("Analysis model" to "${use.model} ${use.role} (Labs, opt-in); it made no pixels")
        }
        add("Generative AI" to "none: every pixel is a measured sample")
    }

    companion object {
        const val PREFIX = "MFR1"
        const val KIND_BURST = "burst"
        const val KIND_NIGHT = "night"
        const val KIND_LONG = "long"
        const val KIND_SUPER_RES = "2x"
        const val KIND_STACK = "stack"
        const val KIND_ASTRO = "astro"
        const val ALIGN_TILES = "aligned"
        const val ALIGN_TRIPOD = "tripod"

        /** Reads a line [encode] wrote, or null when it is not one. Unknown keys are ignored. */
        fun decode(text: String?): CaptureReport? {
            if (text == null) return null
            val trimmed = text.trim().trimEnd('\u0000')
            if (!trimmed.startsWith("$PREFIX;") && trimmed != PREFIX) return null
            val fields = trimmed.split(';').drop(1).mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
            }.toMap()
            val merged = fields["fm"]?.toIntOrNull() ?: return null
            return CaptureReport(
                kind = fields["k"] ?: KIND_BURST,
                framesRequested = fields["fr"]?.toIntOrNull() ?: merged,
                framesMerged = merged,
                meanContribution = fields["c"]?.toFloatOrNull() ?: 1f,
                spanMillis = fields["sp"]?.toLongOrNull() ?: 0L,
                exposureTimeNs = fields["t"]?.toLongOrNull(),
                iso = fields["iso"]?.toIntOrNull(),
                lens = fields["l"],
                alignment = fields["a"] ?: ALIGN_TILES,
                localTone = fields["lt"] == "1",
                hdr = fields["hdr"] == "1",
                plan = fields["p"],
                mergeMillis = fields["mm"]?.toLongOrNull() ?: 0L,
                analysis = decodeAnalysis(fields["ml"]),
            )
        }

        private const val PORTRAIT_CODE = "portrait"

        private fun codeOf(use: AnalysisUse): String =
            if (use.role == AnalysisUse.PORTRAIT_ROLE) PORTRAIT_CODE else use.task.code

        /** `code~model|code~model`; a bare name is the first version's sky model. */
        private fun decodeAnalysis(text: String?): List<AnalysisUse> {
            if (text.isNullOrEmpty()) return emptyList()
            if (!text.contains('~')) return listOf(AnalysisUse(AnalysisTask.SKY, text))
            return text.split('|').mapNotNull { entry ->
                val at = entry.indexOf('~')
                if (at <= 0) return@mapNotNull null
                val code = entry.substring(0, at)
                val model = entry.substring(at + 1)
                if (code == PORTRAIT_CODE) AnalysisUse(AnalysisTask.PERSON, model, AnalysisUse.PORTRAIT_ROLE)
                else AnalysisTask.ofCode(code)?.let { AnalysisUse(it, model) }
            }
        }

        /** The kind a file's tag implies, from the names `RawBurstCapture` gives its outputs. */
        fun kindForTag(tag: String, superRes: Boolean = false): String = when {
            superRes -> KIND_SUPER_RES
            tag.startsWith("long") -> KIND_LONG
            tag.startsWith("night") -> KIND_NIGHT
            tag.startsWith("stack") -> KIND_STACK
            tag.startsWith("astro") -> KIND_ASTRO
            else -> KIND_BURST
        }
    }
}

/** Reads the report a saved photograph carries in its EXIF, or null when it has none (an older file, a DNG). */
fun readCaptureReport(context: android.content.Context, uri: android.net.Uri): CaptureReport? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        CaptureReport.decode(android.media.ExifInterface(stream).getAttribute(android.media.ExifInterface.TAG_USER_COMMENT))
    }
}.getOrNull()
