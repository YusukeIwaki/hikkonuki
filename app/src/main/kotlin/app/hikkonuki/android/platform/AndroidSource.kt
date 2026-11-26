package app.hikkonuki.android.platform

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.hikkonuki.android.core.RandomAccessSource
import app.hikkonuki.android.core.SourceAccess
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Source ids are either an absolute file path or a content URI string. */
class AndroidSource(private val context: Context) : SourceAccess {
    fun isPath(sourceId: String): Boolean = sourceId.startsWith("/")

    fun uri(sourceId: String): Uri = if (isPath(sourceId)) Uri.fromFile(File(sourceId)) else Uri.parse(sourceId)

    override fun exists(sourceId: String): Boolean {
        if (isPath(sourceId)) return File(sourceId).isFile
        return runCatching { openDescriptor(sourceId).use { true } }.getOrDefault(false)
    }

    override fun open(sourceId: String): InputStream =
        if (isPath(sourceId)) FileInputStream(sourceId)
        else context.contentResolver.openInputStream(Uri.parse(sourceId)) ?: throw FileNotFoundException(sourceId)

    fun openDescriptor(sourceId: String): ParcelFileDescriptor =
        if (isPath(sourceId)) ParcelFileDescriptor.open(File(sourceId), ParcelFileDescriptor.MODE_READ_ONLY)
        else context.contentResolver.openFileDescriptor(Uri.parse(sourceId), "r") ?: throw FileNotFoundException(sourceId)

    fun <T> withRandomAccess(sourceId: String, block: (RandomAccessSource) -> T): T =
        openDescriptor(sourceId).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream -> block(ChannelSource(stream.channel)) }
        }

    fun setDataSource(extractor: MediaExtractor, sourceId: String) {
        if (isPath(sourceId)) extractor.setDataSource(sourceId) else extractor.setDataSource(context, Uri.parse(sourceId), null)
    }

    fun setDataSource(retriever: MediaMetadataRetriever, sourceId: String) {
        if (isPath(sourceId)) retriever.setDataSource(sourceId) else retriever.setDataSource(context, Uri.parse(sourceId))
    }
}

private class ChannelSource(private val channel: FileChannel) : RandomAccessSource {
    override val size: Long get() = channel.size()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        val target = ByteBuffer.wrap(buffer, offset, length)
        var at = position
        while (target.hasRemaining()) {
            val read = channel.read(target, at)
            if (read < 0) throw EOFException()
            at += read
        }
    }
}
