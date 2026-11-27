package app.hikkonuki.android.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.hikkonuki.android.R
import androidx.compose.ui.platform.testTag
import app.hikkonuki.android.core.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Shared thumbnail cache + bounded loader parallelism: scrolling must never re-decode
 *  the same item or flood USB/provider I/O with dozens of concurrent retriever sessions. */
object ThumbnailCache {
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 8).toInt()
    private val cache = object : android.util.LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    val dispatcher = Dispatchers.IO.limitedParallelism(4)

    fun get(uri: Uri): Bitmap? = synchronized(cache) { cache.get(uri.toString()) }
    fun put(uri: Uri, bitmap: Bitmap) {
        synchronized(cache) { cache.put(uri.toString(), bitmap) }
    }
}

@Composable
fun MediaThumbnail(uri: Uri, kind: MediaKind, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Cache hits render synchronously on (re)composition, so scrolled-back items don't flash.
    var bitmap by remember(uri) { mutableStateOf(ThumbnailCache.get(uri)) }
    LaunchedEffect(uri) {
        if (bitmap == null) {
            bitmap = withContext(ThumbnailCache.dispatcher) {
                loadThumbnail(context, uri, kind)?.also { ThumbnailCache.put(uri, it) }
            }
        }
    }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current != null) {
            Image(bitmap = current.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(
                imageVector = if (kind == MediaKind.VIDEO) Icons.Filled.Movie else Icons.Filled.Photo,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Detail-screen image: photos are decoded from the original file (sampled only to a
 * screen-sane 2048px cap) instead of upsizing the 320px grid thumbnail. Videos show a
 * full-resolution frame from the retriever.
 */
@Composable
fun DetailMediaImage(uri: Uri, kind: MediaKind, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { loadDetailBitmap(context, uri, kind) }
    }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().testTag("detailBitmap_${current.width}x${current.height}"),
                contentScale = ContentScale.Fit,
            )
        } else {
            Icon(
                imageVector = if (kind == MediaKind.VIDEO) Icons.Filled.Movie else Icons.Filled.Photo,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun loadDetailBitmap(context: android.content.Context, uri: Uri, kind: MediaKind): Bitmap? {
    if (kind == MediaKind.VIDEO) {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                if (uri.scheme == "file") retriever.setDataSource(uri.path) else retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }
    // Photo: decode the original, sampling down only when larger than the detail cap.
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val openStream = {
        if (uri.scheme == "file") java.io.FileInputStream(uri.path) else context.contentResolver.openInputStream(uri)
    }
    runCatching { openStream()?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) } }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return loadThumbnail(context, uri, kind)
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= 2048 || bounds.outHeight / (sampleSize * 2) >= 2048) {
        sampleSize *= 2
    }
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return runCatching { openStream()?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) } }.getOrNull()
        ?: loadThumbnail(context, uri, kind)
}

private const val THUMB_MAX_PX = 480

private fun loadThumbnail(context: android.content.Context, uri: Uri, kind: MediaKind): Bitmap? {
    // 1) ContentResolver thumbnails (MediaStore / SAF providers, API 29+).
    if (Build.VERSION.SDK_INT >= 29 && uri.scheme != "file") {
        runCatching { return context.contentResolver.loadThumbnail(uri, Size(THUMB_MAX_PX, THUMB_MAX_PX), null) }
    }
    // 2) Decode/extract at thumbnail size: full-size frames are the main scroll-jank source.
    val path = if (uri.scheme == "file") uri.path else null
    if (kind == MediaKind.PHOTO) {
        return decodeSampled(context, uri, path, THUMB_MAX_PX)
    }
    return runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            if (path != null) retriever.setDataSource(path) else retriever.setDataSource(context, uri)
            if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_MAX_PX, THUMB_MAX_PX)
            } else {
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } finally {
            retriever.release()
        }
    }.getOrNull()
}

/** Decode a photo with bounds-based sampling so the longest side is at most ~[maxPx]. */
private fun decodeSampled(context: android.content.Context, uri: Uri, path: String?, maxPx: Int): Bitmap? {
    val openStream = {
        if (path != null) java.io.FileInputStream(path) else context.contentResolver.openInputStream(uri)
    }
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        if (path != null) android.graphics.BitmapFactory.decodeFile(path, bounds)
        else openStream()?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= maxPx || bounds.outHeight / (sampleSize * 2) >= maxPx) {
        sampleSize *= 2
    }
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return runCatching {
        if (path != null) android.graphics.BitmapFactory.decodeFile(path, options)
        else openStream()?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()
}

/**
 * Numbers and dates the way the given locale writes them. Kept free of Android resources
 * so they can be called (and tested) anywhere; "unknown" is a null for the caller to word.
 */
object Formats {
    fun date(millis: Long?, locale: Locale, withSeconds: Boolean = false): String? {
        if (millis == null || millis <= 0) return null
        val timeStyle = if (withSeconds) DateFormat.MEDIUM else DateFormat.SHORT
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, timeStyle, locale).format(Date(millis))
    }

    fun size(bytes: Long, locale: Locale): String? {
        if (bytes <= 0) return null
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "$bytes B" else String.format(locale, "%.1f %s", value, units[unit])
    }

    /** "JPEG", "MP4": the name people know a file type by, rather than its MIME type. */
    fun fileType(displayName: String, mimeType: String): String = when (displayName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "JPEG"
        "png" -> "PNG"
        "heic" -> "HEIC"
        "dng" -> "DNG"
        "mp4" -> "MP4"
        "mov" -> "MOV"
        else -> mimeType
    }
}

/** The locale the screen is being shown in: the device's, or the per-app language. */
@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

@Composable
fun formatDate(millis: Long?): String = Formats.date(millis, currentLocale()) ?: stringResource(R.string.unknown_date)

@Composable
fun formatDetailDate(millis: Long?): String =
    Formats.date(millis, currentLocale(), withSeconds = true) ?: stringResource(R.string.unknown_date)

@Composable
fun formatSize(bytes: Long): String = Formats.size(bytes, currentLocale()) ?: stringResource(R.string.unknown_size)

fun formatDuration(millis: Long?): String {
    if (millis == null || millis <= 0) return ""
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}
