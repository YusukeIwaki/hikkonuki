package app.hikkonuki.android.platform

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.exifinterface.media.ExifInterface
import app.hikkonuki.android.BuildConfig
import app.hikkonuki.android.core.CaptureDateResolver
import app.hikkonuki.android.core.MediaKind
import app.hikkonuki.android.ui.LibrarySnapshot
import app.hikkonuki.android.ui.MediaDetails
import app.hikkonuki.android.ui.MediaItem
import app.hikkonuki.android.ui.MediaLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * Finds photos and videos on a camera attached as USB mass storage.
 *
 * Files are read through a folder the user granted once with the system picker. That is
 * the one route that works for every camera (the Osmo Pocket's composite USB device mounts
 * as a volume MediaStore never indexes) and it needs no storage permission. Grants are per
 * SD card and all of them are kept, so plugging in either camera shows its files without
 * asking again.
 *
 * Listing reads only directory entries. Dates shown in the list come from the file name
 * (both cameras put the capture time there); anything that needs the file opened is left
 * to [details].
 */
class UsbMediaLibrary(private val context: Context, private val source: AndroidSource) : MediaLibrary {
    private val resolver get() = context.contentResolver

    private fun grantedTrees(): List<Uri> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && runCatching { DocumentsContract.isTreeUri(it.uri) }.getOrDefault(false) }
        .sortedByDescending { it.persistedTime }
        .map { it.uri }

    private fun treeReadable(tree: Uri): Boolean = runCatching {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    override suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) { connected() }

    private fun connected(): Boolean {
        val usbAttached = context.getSystemService(UsbManager::class.java)?.deviceList?.isNotEmpty() == true
        val removableMounted = context.getSystemService(StorageManager::class.java)?.storageVolumes
            ?.any { it.isRemovable && it.state == Environment.MEDIA_MOUNTED } == true
        return usbAttached || removableMounted || grantedTrees().any(::treeReadable) || inboxFiles().isNotEmpty()
    }

    override suspend fun load(): LibrarySnapshot = withContext(Dispatchers.IO) {
        val trees = grantedTrees()
        if (!connected()) return@withContext LibrarySnapshot(false, emptyList(), trees.map(Uri::toString))
        val zone = ZoneId.systemDefault()
        val items = LinkedHashMap<String, MediaItem>()
        for (tree in trees.filter(::treeReadable)) {
            runCatching { scanTree(tree, DocumentsContract.getTreeDocumentId(tree), 0, zone, items) }
        }
        inboxFiles().forEach { file -> fileItem(file, "USB (inbox)", zone)?.let { items.putIfAbsent(it.id, it) } }
        // The same file can be reported by more than one route; name + size identifies it.
        val seen = HashSet<String>()
        val unique = items.values.filter { seen.add(it.toRequest().fileKey) }
        LibrarySnapshot(
            connected = true,
            items = unique.sortedWith(compareByDescending<MediaItem> { it.dateTakenMillis ?: 0L }.thenBy { it.displayName }),
            watchedFolders = trees.map(Uri::toString),
        )
    }

    private fun kindOf(name: String): MediaKind? {
        // "._" files are macOS resource forks; LRV are the cameras' low-resolution proxies.
        if (name.startsWith(".") || name.startsWith("LRV_", ignoreCase = true)) return null
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "mov" -> MediaKind.VIDEO
            "jpg", "jpeg", "png", "heic", "dng" -> MediaKind.PHOTO
            else -> null
        }
    }

    private fun dateOf(name: String, lastModified: Long, zone: ZoneId): Long? =
        CaptureDateResolver.fromFileName(name, zone) ?: lastModified.takeIf { it > 0 }

    private fun scanTree(tree: Uri, documentId: String, depth: Int, zone: ZoneId, out: MutableMap<String, MediaItem>) {
        if (depth > MAX_DEPTH) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val folders = ArrayList<String>()
        resolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2).orEmpty()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (!name.startsWith(".")) folders += id
                    continue
                }
                val kind = kindOf(name) ?: continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                out[uri.toString()] = MediaItem(
                    id = uri.toString(), uri = uri, displayName = name, kind = kind,
                    sizeBytes = cursor.getLong(3),
                    dateTakenMillis = dateOf(name, cursor.getLong(4), zone),
                    mimeType = MediaStoreGallery.mimeOf(name, kind),
                    sourceLabel = "USB",
                )
            }
        }
        folders.forEach { scanTree(tree, it, depth + 1, zone, out) }
    }

    private fun fileItem(file: File, label: String, zone: ZoneId): MediaItem? {
        val kind = kindOf(file.name) ?: return null
        return MediaItem(
            id = file.absolutePath, uri = Uri.fromFile(file), displayName = file.name, kind = kind,
            sizeBytes = file.length(), dateTakenMillis = dateOf(file.name, file.lastModified(), zone),
            mimeType = MediaStoreGallery.mimeOf(file.name, kind), sourceLabel = label,
        )
    }

    /**
     * Debug builds treat files pushed to the app's `usb_inbox` folder as a connected camera,
     * so the whole flow can be exercised on an emulator:
     * `adb push clip.mp4 /sdcard/Android/data/<package>/files/usb_inbox/`
     */
    private fun inboxFiles(): List<File> {
        if (!BuildConfig.DEBUG) return emptyList()
        val dir = File(context.getExternalFilesDir(null) ?: return emptyList(), "usb_inbox")
        return dir.listFiles { file -> file.isFile && kindOf(file.name) != null }?.toList().orEmpty()
    }

    override suspend fun details(item: MediaItem): MediaDetails? = withContext(Dispatchers.IO) {
        runCatching {
            if (item.kind == MediaKind.VIDEO) {
                val retriever = MediaMetadataRetriever()
                try {
                    source.setDataSource(retriever, item.id)
                    fun number(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0L
                    var width = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
                    var height = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
                    if (number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 == 90L) width = height.also { height = width }
                    MediaDetails(width, height, number(MediaMetadataRetriever.METADATA_KEY_DURATION).takeIf { it > 0 }, null)
                } finally {
                    retriever.release()
                }
            } else {
                source.open(item.id).use { input ->
                    val exif = ExifInterface(input)
                    var width = exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0).takeIf { it > 0 }
                        ?: exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                    var height = exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0).takeIf { it > 0 }
                        ?: exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                    if (exif.rotationDegrees % 180 == 90) width = height.also { height = width }
                    val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    // EXIF has no zone: it is the camera's wall clock, read here in the phone's zone.
                    val date = raw?.let {
                        runCatching {
                            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }.parse(it)?.time
                        }.getOrNull()
                    }
                    MediaDetails(width, height, null, date)
                }
            }
        }.getOrNull()
    }

    override suspend fun exists(item: MediaItem): Boolean = withContext(Dispatchers.IO) { source.exists(item.id) }

    /**
     * The system folder picker, opened at DCIM on the attached camera so granting access
     * is a single tap on "use this folder".
     */
    override fun folderPickerIntent(): Intent {
        val volume = context.getSystemService(StorageManager::class.java)?.storageVolumes
            ?.firstOrNull { it.isRemovable && it.uuid != null && it.state == Environment.MEDIA_MOUNTED }
        val intent = volume?.let { runCatching { it.createOpenDocumentTreeIntent() }.getOrNull() } ?: Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        return intent.apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if (volume != null) {
                // Must be a document URI: DocumentsUI ignores a bare tree URI here.
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "${volume.uuid}:DCIM"),
                )
            }
        }
    }

    override fun rememberFolder(uri: Uri): Boolean = runCatching {
        check(DocumentsContract.isTreeUri(uri))
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.isSuccess

    private companion object {
        const val MAX_DEPTH = 4
    }
}
