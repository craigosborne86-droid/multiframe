package dev.multiframe.camera.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.multiframe.camera.pipeline.CaptureLog
import dev.multiframe.camera.pipeline.CaptureRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "Gallery"

/** Thumbnails are decoded to about this, which is ample for a grid cell. */
private const val THUMB_PX = 320

/** How far into a picture the reviewer may go. */
private const val MAX_ZOOM = 8f

/**
 * The photographs this app has taken.
 *
 * ### Why it decodes its own thumbnails
 *
 * The obvious route is a `MediaStore` thumbnail, which the system has already
 * generated and caches. It is also the route that needs the app to be able to
 * query the library, and the whole reason [CaptureLog] exists is to avoid
 * asking for that permission. Decoding with `inSampleSize` from the URI the app
 * recorded itself needs no permission beyond the grant it already holds on a
 * file it wrote.
 *
 * The cost is a decode per cell. `inSampleSize` makes that cheap -- a twelve
 * megapixel JPEG subsampled by eight is decoded at 1/64 the pixels -- and it is
 * done off the main thread and cached for as long as the grid is open.
 */
@Composable
fun GalleryScreen(
    context: Context,
    onDismiss: () -> Unit,
) {
    val view = LocalView.current
    var records by remember { mutableStateOf<List<CaptureRecord>?>(null) }
    var opened by remember { mutableStateOf<CaptureRecord?>(null) }
    var confirming by remember { mutableStateOf<CaptureRecord?>(null) }

    // Read once when the grid opens. Deliberately not on every recomposition:
    // it checks every recorded URI against the content provider, which is a
    // query per file.
    LaunchedEffect(Unit) {
        records = withContext(Dispatchers.IO) { CaptureLog.records(context) }
    }

    val cache = remember { ThumbnailCache() }
    DisposableEffect(Unit) { onDispose { cache.clear() } }

    // The back gesture, from the top down: a delete being confirmed is
    // called off, a picture being reviewed is put back in the grid, and only
    // from the grid does back close the gallery. Before this the gesture
    // reached the activity and quit the app from inside the gallery.
    BackHandler {
        when {
            confirming != null -> confirming = null
            opened != null -> opened = null
            else -> onDismiss()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.PaneSolid)
            // Swallows taps so they cannot reach the viewfinder underneath,
            // which is still composed and still listening for a focus tap.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            GalleryHeader(
                count = records?.size ?: 0,
                loaded = records != null,
                onDismiss = {
                    Haptics.segment(view)
                    onDismiss()
                },
            )

            val list = records
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyGallery()
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 116.dp),
                    contentPadding = PaddingValues(
                        start = 3.dp, end = 3.dp, top = 3.dp, bottom = 24.dp,
                    ),
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                ) {
                    items(list, key = { it.baseName }) { record ->
                        GalleryCell(
                            context = context,
                            record = record,
                            cache = cache,
                        ) {
                            Haptics.segment(view)
                            opened = record
                        }
                    }
                }
            }
        }
    }

    opened?.let { record ->
        ReviewOverlay(
            context = context,
            record = record,
            onDismiss = { opened = null },
            onDelete = { confirming = record },
        )
    }

    confirming?.let { record ->
        DeleteConfirmation(
            record = record,
            onCancel = { confirming = null },
            onConfirm = {
                Haptics.confirm(view)
                confirming = null
                opened = null
                records = records?.filterNot { it.baseName == record.baseName }
                cache.forget(record.baseName)
                // Fire and forget: the grid has already dropped the cell, and
                // a provider that refuses leaves the file for the next read of
                // the log to notice.
                Thread { CaptureLog.delete(context, record) }.start()
            },
        )
    }
}

@Composable
private fun GalleryHeader(count: Int, loaded: Boolean, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = "GALLERY", style = Type.Section)
            if (loaded && count > 0) {
                Text(
                    text = count.toString(),
                    style = Type.Gauge,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, Ink.Hairline, RoundedCornerShape(24.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 18.dp)
                .semantics { contentDescription = "Close gallery" },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "CLOSE", style = Type.Control)
        }
    }
}

@Composable
private fun EmptyGallery() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "NOTHING SHOT YET",
            style = Type.Instruction,
            modifier = Modifier.padding(bottom = 80.dp),
        )
    }
}

/**
 * One photograph in the grid.
 *
 * Square, because a grid of mixed aspect ratios reads as a broken layout, and
 * the cell is a way in rather than a way to judge a picture.
 */
@Composable
private fun GalleryCell(
    context: Context,
    record: CaptureRecord,
    cache: ThumbnailCache,
    onOpen: () -> Unit,
) {
    val thumb by produceState<Bitmap?>(cache.get(record.baseName), record.baseName) {
        if (value != null) return@produceState
        val uri = record.preview.uri ?: return@produceState
        value = withContext(Dispatchers.IO) {
            decodeSampled(context, uri, THUMB_PX)
        }?.also { cache.put(record.baseName, it) }
    }

    Box(
        modifier = Modifier
            .padding(3.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Ink.Pane)
            .clickable(onClick = onOpen)
            .semantics {
                contentDescription = buildString {
                    append(record.description)
                    if (record.hasRaw) append(", raw available")
                }
            },
    ) {
        thumb?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // The badges say what a thumbnail cannot: that a DNG exists beside
        // the JPEG, and that the JPEG is a 2x accumulation -- a picture whose
        // resolution is a claim about the burst rather than the sensor, and
        // which a cell this size cannot show. Weight, not hue: same fill as
        // every other readout on a photograph.
        if (record.isSuperRes) {
            Text(
                text = "SUPER-RES 2X",
                style = Type.Label,
                color = Ink.Bone,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .background(Ink.Readout, RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        if (record.hasRaw) {
            Text(
                text = "DNG",
                style = Type.Label,
                color = Ink.Bone,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .background(Ink.Readout, RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * One photograph, full screen, with pinch to 1:1 and beyond.
 *
 * The point of the zoom is pixel peeping -- checking whether the merge actually
 * resolved what it claimed to -- so it goes well past fit-to-screen and the
 * image is decoded at full resolution rather than at display size.
 */
@Composable
private fun ReviewOverlay(
    context: Context,
    record: CaptureRecord,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val view = LocalView.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var frame by remember { mutableStateOf(IntSize.Zero) }

    val full by produceState<Bitmap?>(null, record.baseName) {
        val uri = record.preview.uri ?: return@produceState
        value = withContext(Dispatchers.IO) {
            // Full resolution, subsampled only if it would not fit in memory.
            // A twelve megapixel ARGB bitmap is 48MB, which is affordable for
            // one image; a sixty megapixel mosaic is not.
            decodeSampled(context, uri, 4096)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.Ground)
            // The frame the pan is clamped against, measured on the container
            // the picture is drawn into rather than on the picture, which is
            // being transformed and would report its own scaled size.
            .onSizeChanged { frame = it }
            .pointerInput(record.baseName) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                    offset = if (next <= 1f) {
                        // Back at fit-to-screen there is nowhere to pan to, and
                        // leaving a stale offset would strand the picture off
                        // centre with no way to recover it.
                        Offset.Zero
                    } else {
                        // Zoom about the fingers rather than about the middle,
                        // so the detail under them stays under them.
                        val factor = next / scale
                        clampOffset(
                            (offset - centroid) * factor + centroid + pan,
                            next, frame,
                        )
                    }
                    scale = next
                }
            }
            .pointerInput(record.baseName) {
                detectTapGestures(
                    onDoubleTap = {
                        Haptics.tick(view)
                        // A shortcut to pixel level and back, which is the
                        // comparison anyone reviewing a merge actually wants.
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 4f
                            offset = Offset.Zero
                        }
                    },
                )
            },
    ) {
        full?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = record.description,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }

        ReviewChrome(
            record = record,
            scale = scale,
            onClose = {
                Haptics.segment(view)
                onDismiss()
            },
            onDelete = {
                Haptics.segment(view)
                onDelete()
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ReviewChrome(
    record: CaptureRecord,
    scale: Float,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChromeButton("CLOSE", onClose)
            if (scale > 1.02f) {
                Text(
                    text = "%.1fx".format(scale),
                    style = Type.Gauge,
                    modifier = Modifier
                        .background(Ink.Readout, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            ChromeButton("DELETE", onDelete, tint = Ink.Danger)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = record.description.uppercase(),
                style = Type.Instruction,
                modifier = Modifier
                    .background(Ink.Readout, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            if (record.hasRaw || record.isSuperRes) {
                Text(
                    text = buildString {
                        if (record.isSuperRes) append("SUPER-RES 2X")
                        if (record.hasRaw) {
                            if (isNotEmpty()) append(" · ")
                            append("JPEG + DNG")
                        }
                    },
                    style = Type.Label,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ChromeButton(
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = Ink.Bone,
) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Ink.Readout)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = Type.Control, color = tint)
    }
}

/**
 * Deleting is the one destructive thing in this app, so it asks.
 *
 * It also says what will go: a photograph with a DNG beside it is two files,
 * and someone who has been shooting raw all afternoon should know that pressing
 * this removes both rather than leaving the negative behind.
 */
@Composable
private fun DeleteConfirmation(
    record: CaptureRecord,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.Readout)
            .pointerInput(Unit) { detectTapGestures { onCancel() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .background(Ink.Panel, RoundedCornerShape(20.dp))
                .border(1.dp, Ink.Hairline, RoundedCornerShape(20.dp))
                .padding(horizontal = 24.dp, vertical = 22.dp)
                .pointerInput(Unit) { detectTapGestures { } },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "DELETE THIS PHOTOGRAPH", style = Type.Section)
            Text(
                text = if (record.hasRaw) "The JPEG and the DNG both go."
                else "This cannot be undone.",
                style = Type.Spec,
                modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
            )
            Row {
                ChromeButton("CANCEL", onCancel)
                Box(Modifier.size(width = 10.dp, height = 1.dp))
                ChromeButton("DELETE", onConfirm, tint = Ink.Danger)
            }
        }
    }
}

/**
 * Keeps decoded thumbnails for as long as the grid is open.
 *
 * Not an LRU and not bounded by bytes: the grid holds at most a few hundred
 * cells, each subsampled to about 320 pixels square, and Compose only asks for
 * the ones on screen. What this prevents is re-decoding on every scroll, which
 * is what makes an uncached grid stutter.
 */
private class ThumbnailCache {
    private val entries = HashMap<String, Bitmap>()

    fun get(key: String): Bitmap? = entries[key]

    fun put(key: String, bitmap: Bitmap) {
        entries[key] = bitmap
    }

    fun forget(key: String) {
        entries.remove(key)?.recycle()
    }

    fun clear() {
        entries.values.forEach { it.recycle() }
        entries.clear()
    }
}

/**
 * Decodes at no more than [maxPx] on the long side.
 *
 * Two passes: the first reads the header only, to learn the real size without
 * allocating for it, and the second decodes with the subsampling that implies.
 * Decoding a twelve megapixel JPEG to fill a 116dp cell without this allocates
 * 48MB to throw away 47 of them.
 *
 * Returns null for a file that cannot be read, which for a DNG is the usual
 * case: `BitmapFactory` does not decode raw, and a record whose only file is a
 * DNG simply has no thumbnail.
 */
private fun decodeSampled(context: Context, uri: Uri, maxPx: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }

    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) {
        null
    } else {
        var sample = 1
        while (longest / sample > maxPx) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }
} catch (e: Exception) {
    Log.w(TAG, "could not decode $uri", e)
    null
} catch (e: OutOfMemoryError) {
    // A mosaic can be sixty megapixels. Failing to show it is better than
    // taking the app down with it.
    Log.w(TAG, "not enough memory to decode $uri", e)
    null
}

/**
 * Holds a zoomed image so its edges cannot be dragged inside the frame.
 *
 * Without it a picture can be panned off screen entirely and there is no
 * gesture that brings it back, which reads as the review having lost the
 * photograph.
 */
internal fun clampOffset(offset: Offset, scale: Float, frame: IntSize): Offset {
    // Nothing measured yet. Clamping against a zero frame would pin the picture
    // at centre and make the first gesture after opening do nothing.
    if (frame.width == 0 || frame.height == 0) return offset

    // At or below fit-to-screen the picture does not overhang, so there is no
    // travel and the only valid position is centred. Said here rather than
    // falling out of a zero-width coerce, which returns a negative zero for a
    // negative input -- harmless as a translation, but a wart to reason about.
    if (scale <= 1f) return Offset.Zero

    val maxX = (frame.width * (scale - 1f)) / 2f
    val maxY = (frame.height * (scale - 1f)) / 2f
    return Offset(
        offset.x.coerceIn(-maxX, maxX),
        offset.y.coerceIn(-maxY, maxY),
    )
}
