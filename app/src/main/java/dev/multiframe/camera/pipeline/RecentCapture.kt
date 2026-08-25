package dev.multiframe.camera.pipeline

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

private const val TAG = "Multiframe"

/** The most recent photograph this app saved. */
data class RecentShot(
    val uri: Uri,
    val displayName: String,
    val takenAtMillis: Long,
) {
    /** A DNG carries the merged sensor data; a JPEG is the developed version. */
    val isRaw: Boolean get() = RecentCapture.isRaw(displayName)
}

/**
 * Remembers the last photograph taken, for the review thumbnail.
 *
 * ### Why this is not a MediaStore query
 *
 * The obvious implementation asks MediaStore for the newest image in the app's
 * folder. On device that returns nothing, and the reason is worth stating: an
 * app can only see MediaStore entries it owns, and ownership is lost when the
 * app is reinstalled. Making the query work would mean holding
 * READ_MEDIA_IMAGES -- permission to read the user's entire photo library --
 * in order to show a thumbnail of a picture the app had just taken itself.
 *
 * That is a bad trade twice over. It is a large permission for a small feature,
 * and a query over the whole library can surface images the app has no business
 * with: a thumbnail is an invitation to tap, and someone else's photograph
 * behind it is both confusing and a small privacy failure.
 *
 * Recording the URI at the moment of writing needs no permission at all, always
 * shows the right picture, and costs one string.
 */
object RecentCapture {

    /** Where captures are written. */
    const val FOLDER = "Multiframe"

    private const val PREFERENCES = "multiframe.recent"
    private const val KEY_URI = "uri"
    private const val KEY_NAME = "name"
    private const val KEY_TIME = "time"

    /** Records a capture as it is saved. */
    fun remember(context: Context, uri: Uri, displayName: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URI, uri.toString())
            .putString(KEY_NAME, displayName)
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
    }

    /**
     * The last capture, or null when there is none or it has been deleted.
     *
     * Existence is checked rather than assumed: the user may well have deleted
     * the picture from their gallery since, and a thumbnail pointing at nothing
     * would fail when tapped rather than simply not being there.
     */
    fun latest(context: Context): RecentShot? {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_URI, null) ?: return null
        val name = prefs.getString(KEY_NAME, null) ?: return null

        val uri = runCatching { Uri.parse(stored) }.getOrNull() ?: return null
        if (!exists(context, uri)) {
            Log.i(TAG, "last capture no longer exists; forgetting it")
            forget(context)
            return null
        }
        return RecentShot(uri, name, prefs.getLong(KEY_TIME, 0L))
    }

    fun forget(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    private fun exists(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.Images.Media._ID), null, null, null,
        )?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        // A revoked grant or a provider that is simply unavailable. The
        // thumbnail is a convenience; losing it must not lose the app.
        Log.w(TAG, "could not check the last capture", e)
        false
    }

    /**
     * Whether a name is a raw file.
     *
     * A pure function on the name rather than only a property of a shot, so it
     * can be tested without a content provider -- android.net.Uri is a stub in
     * unit tests and constructing one is not possible there.
     */
    fun isRaw(displayName: String): Boolean =
        displayName.endsWith(".dng", ignoreCase = true)

    /**
     * Whether a name looks like one of this app's own captures.
     *
     * Pure, so the convention can be tested without a content provider.
     */
    fun isOurs(displayName: String): Boolean =
        displayName.startsWith("MF_") &&
            (displayName.endsWith(".jpg", true) || displayName.endsWith(".dng", true))

    /** What a name says about how the shot was taken. */
    fun describe(displayName: String): String = when {
        displayName.contains("_mosaic_") -> "mosaic"
        displayName.contains("_zsl_") -> "zero shutter lag"
        displayName.contains("_merged_") -> "merged burst"
        displayName.contains("_raw") -> "single frame"
        displayName.contains("_ab_") -> "A/B comparison"
        else -> "capture"
    }
}
