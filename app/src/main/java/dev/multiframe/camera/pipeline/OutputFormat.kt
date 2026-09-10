package dev.multiframe.camera.pipeline

/**
 * What a developed photograph is written as.
 *
 * The DNG is unaffected either way. This is only about the viewable file that
 * sits beside it -- the merged raw is always written as a DNG, because the
 * whole point of keeping it is that it opens in a raw converter.
 */
enum class OutputFormat {
    /**
     * The default, and the one that opens everywhere.
     *
     * Encoded through the vendored libjpeg-turbo across every core. Larger
     * files than HEIF at matched quality, and a decade of software that can
     * read them.
     */
    JPEG,

    /**
     * Roughly half the size at matched quality, encoded on dedicated silicon.
     *
     * Not the default, because a photographer who cannot open their pictures
     * has been failed regardless of how small the files are. Falls back to
     * [JPEG] whenever the hardware encoder declines -- see [HeifEncoder] for
     * what makes it decline, none of which is exceptional.
     */
    HEIF;

    val label: String get() = if (this == JPEG) "JPEG" else "HEIF"

    fun next(): OutputFormat = entries[(ordinal + 1) % entries.size]

    companion object {
        fun named(name: String?): OutputFormat =
            entries.firstOrNull { it.name == name } ?: JPEG
    }
}
