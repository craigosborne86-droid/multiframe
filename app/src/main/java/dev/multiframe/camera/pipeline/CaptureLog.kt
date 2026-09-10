package dev.multiframe.camera.pipeline

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

private const val TAG = "CaptureLog"

/**
 * One file this app wrote.
 *
 * Holds the URI as the string it was stored as rather than as a parsed [Uri].
 * That is the form the log persists, so nothing is gained by parsing early --
 * and it keeps the pairing free of Android types, which is what lets it be
 * tested on the JVM. `android.net.Uri` is a stub there with a package-private
 * constructor: it cannot be built and it cannot be faked.
 */
data class CaptureFile(
    val uriString: String,
    val displayName: String,
    val takenAtMillis: Long,
) {
    val isRaw: Boolean get() = RecentCapture.isRaw(displayName)

    /** Null for a stored string that no longer parses, which is not fatal. */
    val uri: Uri? get() = runCatching { Uri.parse(uriString) }.getOrNull()
}

/**
 * One photograph, as the user thinks of one.
 *
 * A single press of DNG writes two files. They are not two photographs, and a
 * gallery that shows them as two entries is a gallery that appears to have
 * taken twice as many pictures as it did -- and one where deleting the visible
 * copy silently leaves the other behind, filling storage with orphans nothing
 * in the interface can see.
 */
data class CaptureRecord(
    val baseName: String,
    val jpeg: CaptureFile?,
    val dng: CaptureFile?,
) {
    init {
        require(jpeg != null || dng != null) { "a record with no files is not one" }
    }

    /** What the grid shows, and what a share hands over: the developed version. */
    val preview: CaptureFile get() = jpeg ?: dng!!

    val takenAtMillis: Long get() = preview.takenAtMillis

    val hasRaw: Boolean get() = dng != null

    /** Both files, for deletion -- which has to take the pair or neither. */
    val files: List<CaptureFile> get() = listOfNotNull(jpeg, dng)

    /** How the shot was taken, read off the name the pipeline chose. */
    val description: String get() = RecentCapture.describe(baseName)
}

/**
 * Every photograph this app has written, newest first.
 *
 * ### Why this is not a MediaStore query
 *
 * The same reason [RecentCapture] is not, and it is worth restating because a
 * gallery is exactly where the temptation returns: an app sees only the
 * MediaStore entries it owns, ownership is lost on reinstall, and making the
 * query complete would mean holding READ_MEDIA_IMAGES -- permission to read the
 * user's entire photo library -- to show pictures the app took itself.
 *
 * That is a large permission for a small feature, and a query across the whole
 * library can surface images the app has no business with. Recording each URI
 * as it is written needs no permission at all and cannot show someone else's
 * photograph.
 *
 * The cost is that the log does not survive a reinstall, and pictures taken
 * before this version are not in it. Both are acceptable: the files are still
 * in the user's gallery app, which is where a photo library belongs.
 */
object CaptureLog {

    private const val PREFERENCES = "multiframe.captures"
    private const val KEY_ENTRIES = "entries"

    /**
     * How many files are remembered.
     *
     * Bounded because this is a SharedPreferences string and it is rewritten on
     * every capture. Four hundred files is a couple of hundred photographs,
     * which is more than a session's shooting and far short of where the write
     * cost becomes noticeable.
     */
    const val LIMIT = 400

    /** Field and record separators. Chosen to be illegal in a MediaStore name. */
    private const val FIELD = ""
    private const val RECORD = ""

    /**
     * Records a file as it is written.
     *
     * Called from the same place [RecentCapture.remember] is, so a capture that
     * reaches the gallery is exactly one that reached the thumbnail.
     */
    fun record(context: Context, uri: Uri, displayName: String) {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_ENTRIES, null).orEmpty()
        val line = encode(uri.toString(), displayName, System.currentTimeMillis())

        // Newest first, and trimmed from the tail. Splitting the whole string
        // on every capture is not free, but it happens once per photograph and
        // costs microseconds against a merge that costs a hundred milliseconds.
        val kept = (listOf(line) + existing.split(RECORD).filter { it.isNotBlank() })
            .take(LIMIT)

        prefs.edit().putString(KEY_ENTRIES, kept.joinToString(RECORD)).apply()
    }

    /**
     * Every photograph, newest first, paired and with dead entries dropped.
     *
     * Files the user has deleted from their gallery app are removed from the
     * log as they are noticed, so a stale entry never becomes a grid cell that
     * fails when tapped.
     */
    fun records(context: Context): List<CaptureRecord> {
        val files = files(context)
        val live = files.filter { file ->
            file.uri?.let { exists(context, it) } ?: false
        }

        if (live.size != files.size) {
            Log.i(TAG, "dropping ${files.size - live.size} deleted captures")
            rewrite(context, live)
        }

        return pair(live)
    }

    /**
     * Deletes both halves of a photograph.
     *
     * Returns true when everything it tried to delete is gone. A partial
     * failure -- one file removed and the other refused -- still reports false,
     * because the record the user asked to be rid of is still partly there.
     */
    fun delete(context: Context, record: CaptureRecord): Boolean {
        var allGone = true
        for (file in record.files) {
            val uri = file.uri
            if (uri == null) continue
            val removed = runCatching {
                context.contentResolver.delete(uri, null, null) > 0
            }.getOrElse {
                Log.w(TAG, "could not delete ${file.displayName}", it)
                false
            }
            if (!removed && exists(context, uri)) allGone = false
        }

        val gone = record.files.map { it.uriString }.toSet()
        rewrite(context, files(context).filterNot { it.uriString in gone })

        // The viewfinder thumbnail may have been pointing at what was just
        // deleted, and it checks existence on read -- but only on the next
        // read. Clearing it here means the thumbnail goes at the same moment
        // the grid cell does.
        if (RecentCapture.latest(context)?.uri?.toString() in gone) {
            RecentCapture.forget(context)
        }
        return allGone
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    // --- pure, so the pairing can be tested without a content provider -----

    /**
     * The name a JPEG and a DNG of the same photograph share.
     *
     * Everything up to the final dot. The pipeline names the pair identically
     * apart from the extension -- see `RawBurstCapture` and `RawCapture` -- so
     * this is the whole of the correspondence.
     */
    fun baseName(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        return if (dot > 0) displayName.substring(0, dot) else displayName
    }

    /**
     * Groups files into photographs, newest first.
     *
     * Order within a pair does not matter and order between pairs is by time,
     * so a DNG written a few milliseconds after its JPEG does not sort between
     * two other photographs.
     */
    fun pair(files: List<CaptureFile>): List<CaptureRecord> =
        files.groupBy { baseName(it.displayName) }
            .map { (base, group) ->
                CaptureRecord(
                    baseName = base,
                    jpeg = group.firstOrNull { !it.isRaw },
                    dng = group.firstOrNull { it.isRaw },
                )
            }
            .sortedByDescending { it.takenAtMillis }

    internal fun encode(uri: String, name: String, timeMillis: Long): String =
        listOf(uri, name, timeMillis.toString()).joinToString(FIELD)

    /**
     * Parses one stored line, or null if it is malformed.
     *
     * A line that cannot be read is skipped rather than throwing. The log is a
     * convenience; a single corrupt entry must not cost the user the rest of
     * their gallery.
     */
    internal fun decode(line: String): CaptureFile? {
        val parts = line.split(FIELD)
        if (parts.size < 3) return null
        if (parts[0].isBlank() || parts[1].isBlank()) return null
        val time = parts[2].toLongOrNull() ?: return null
        return CaptureFile(parts[0], parts[1], time)
    }

    // --- storage -----------------------------------------------------------

    private fun files(context: Context): List<CaptureFile> {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return emptyList()
        return stored.split(RECORD)
            .filter { it.isNotBlank() }
            .mapNotNull { decode(it) }
    }

    private fun rewrite(context: Context, files: List<CaptureFile>) {
        val text = files.joinToString(RECORD) {
            encode(it.uriString, it.displayName, it.takenAtMillis)
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENTRIES, text).apply()
    }

    private fun exists(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.Images.Media._ID), null, null, null,
        )?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        Log.w(TAG, "could not check $uri", e)
        false
    }
}
