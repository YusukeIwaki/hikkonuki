package app.hikkonuki.android.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Positioned reads, so the same parser works on local files and on USB documents. */
interface RandomAccessSource {
    val size: Long
    /** Fills [length] bytes or throws. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int)
}

class FileSource(private val file: RandomAccessFile) : RandomAccessSource {
    override val size: Long get() = file.length()
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        file.seek(position)
        file.readFully(buffer, offset, length)
    }
}

class BytesSource(private val bytes: ByteArray) : RandomAccessSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        if (position < 0 || position + length > bytes.size) throw EOFException()
        System.arraycopy(bytes, position.toInt(), buffer, offset, length)
    }
}

data class Mp4Box(val type: String, val offset: Long, val headerSize: Int, val size: Long) {
    val payloadOffset: Long get() = offset + headerSize
    val end: Long get() = offset + size
}

data class Mp4Metadata(
    /** mvhd creation time, null when unset (1904 epoch) or unreadable. */
    val creationTimeMillis: Long?,
    /** iTunes-style and QuickTime key/value text tags, e.g. "encoder" -> "DJI OsmoPocket4P". */
    val tags: Map<String, String>,
)

object Mp4 {
    /** Seconds between the QuickTime epoch (1904-01-01) and the Unix epoch. */
    const val QT_EPOCH_OFFSET_SECONDS = 2_082_844_800L
    private const val MAX_TEXT_VALUE = 4096

    private fun RandomAccessSource.bytes(position: Long, length: Int): ByteArray =
        ByteArray(length).also { read(position, it, 0, length) }

    private fun RandomAccessSource.u32(position: Long): Long =
        ByteBuffer.wrap(bytes(position, 4)).int.toLong() and 0xFFFFFFFFL

    /** Child boxes in [start, end). Stops quietly at the first malformed header. */
    fun boxes(source: RandomAccessSource, start: Long = 0, end: Long = source.size): List<Mp4Box> {
        val result = ArrayList<Mp4Box>()
        var position = start
        while (position + 8 <= end) {
            val header = source.bytes(position, 8)
            val size32 = ByteBuffer.wrap(header, 0, 4).int.toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8
            val size = when (size32) {
                1L -> {
                    if (position + 16 > end) break
                    headerSize = 16
                    ByteBuffer.wrap(source.bytes(position + 8, 8)).long
                }
                0L -> end - position
                else -> size32
            }
            if (size < headerSize || position + size > end) break
            result += Mp4Box(type, position, headerSize, size)
            position += size
        }
        return result
    }

    private fun List<Mp4Box>.first(type: String): Mp4Box? = firstOrNull { it.type == type }

    /**
     * `meta` exists in two layouts: ISO (4 bytes of version/flags before the children) and
     * QuickTime (children start immediately). Returns the offset of the first child.
     */
    private fun metaChildrenStart(source: RandomAccessSource, meta: Mp4Box): Long? {
        if (meta.size - meta.headerSize < 12) return null
        val head = source.bytes(meta.payloadOffset, 12)
        return when {
            String(head, 4, 4, Charsets.ISO_8859_1) == "hdlr" -> meta.payloadOffset
            String(head, 8, 4, Charsets.ISO_8859_1) == "hdlr" -> meta.payloadOffset + 4
            else -> null
        }
    }

    fun readMetadata(source: RandomAccessSource): Mp4Metadata {
        val moov = boxes(source).first("moov") ?: return Mp4Metadata(null, emptyMap())
        val moovChildren = boxes(source, moov.payloadOffset, moov.end)
        val creation = moovChildren.first("mvhd")?.let { readCreationTime(source, it) }
        val tags = LinkedHashMap<String, String>()
        val udtaMeta = moovChildren.first("udta")
            ?.let { boxes(source, it.payloadOffset, it.end).first("meta") }
        for (meta in listOfNotNull(udtaMeta, moovChildren.first("meta"))) {
            runCatching { readMetaTags(source, meta, tags) }
        }
        return Mp4Metadata(creation, tags)
    }

    private fun readCreationTime(source: RandomAccessSource, mvhd: Mp4Box): Long? {
        val version = source.bytes(mvhd.payloadOffset, 1)[0].toInt()
        val qtSeconds = if (version == 1) {
            ByteBuffer.wrap(source.bytes(mvhd.payloadOffset + 4, 8)).long
        } else {
            source.u32(mvhd.payloadOffset + 4)
        }
        return if (qtSeconds <= 0) null else (qtSeconds - QT_EPOCH_OFFSET_SECONDS) * 1000
    }

    private val itunesNames = mapOf("©too" to "encoder", "©mak" to "make", "©mod" to "model", "©day" to "date")

    private fun readMetaTags(source: RandomAccessSource, meta: Mp4Box, out: MutableMap<String, String>) {
        val start = metaChildrenStart(source, meta) ?: return
        val children = boxes(source, start, meta.end)
        val keys = children.first("keys")?.let { readKeys(source, it) } ?: emptyList()
        val ilst = children.first("ilst") ?: return
        for (item in boxes(source, ilst.payloadOffset, ilst.end)) {
            val name = if (keys.isNotEmpty()) {
                val index = ByteBuffer.wrap(item.type.toByteArray(Charsets.ISO_8859_1)).int
                keys.getOrNull(index - 1) ?: continue
            } else {
                itunesNames[item.type] ?: continue
            }
            // Cover art and other blobs live here too: only small UTF-8 values are tags.
            if (item.size > MAX_TEXT_VALUE) continue
            val data = boxes(source, item.payloadOffset, item.end).first("data") ?: continue
            val payloadLength = (data.size - data.headerSize).toInt()
            if (payloadLength < 8) continue
            val payload = source.bytes(data.payloadOffset, payloadLength)
            if (ByteBuffer.wrap(payload, 0, 4).int != 1) continue
            out.putIfAbsent(name, String(payload, 8, payloadLength - 8, Charsets.UTF_8))
        }
    }

    private fun readKeys(source: RandomAccessSource, keys: Mp4Box): List<String> {
        val payloadLength = (keys.size - keys.headerSize).toInt()
        if (payloadLength < 8 || payloadLength > 1 shl 20) return emptyList()
        val payload = ByteBuffer.wrap(source.bytes(keys.payloadOffset, payloadLength))
        payload.int // version + flags
        val count = payload.int
        val result = ArrayList<String>()
        repeat(count) {
            if (payload.remaining() < 8) return result
            val size = payload.int
            payload.int // namespace, "mdta"
            if (size < 8 || size - 8 > payload.remaining()) return result
            val name = ByteArray(size - 8).also { payload.get(it) }
            result += String(name, Charsets.UTF_8)
        }
        return result
    }
}

/**
 * Writes the capture date (and camera make/model) into a finished MP4.
 *
 * Android's muxer stamps files with the export time, and both the system media scanner
 * and Google Photos take a video's date from the file's own header, not from anything the
 * app tells MediaStore. So the header has to be right before the file is published.
 */
object Mp4Stamper {
    data class Stamp(
        val creationTimeMillis: Long,
        val utcOffsetSeconds: Int = 0,
        val make: String? = null,
        val model: String? = null,
    )

    data class Result(val timesPatched: Int, val tagsWritten: Boolean)

    /**
     * @param writeTags also add the make/model/local-time tags. Pass false for camera
     *   originals, which should stay as the camera wrote them apart from the date fix.
     */
    fun stamp(file: File, stamp: Stamp, writeTags: Boolean = true): Result = RandomAccessFile(file, "rw").use { raf ->
        val source = FileSource(raf)
        val top = Mp4.boxes(source)
        val moov = top.firstOrNull { it.type == "moov" } ?: return Result(0, false)
        val patched = patchTimes(raf, source, moov, stamp.creationTimeMillis / 1000 + Mp4.QT_EPOCH_OFFSET_SECONDS)
        // Sample offsets in the track tables are absolute, so nothing after moov may move.
        // moov can grow freely when it is the last box; otherwise only into padding that
        // directly follows it (Android's muxer leaves a `free` box there for short clips).
        val next = top.getOrNull(top.indexOf(moov) + 1)
        val room = when {
            moov.end == raf.length() -> Long.MAX_VALUE
            next?.type == "free" -> moov.size + next.size
            else -> 0L
        }
        val canRewrite = writeTags && room > 0 && moov.headerSize == 8 && moov.size < 256L * 1024 * 1024
        val tagsWritten = canRewrite && runCatching { rewriteTags(raf, source, moov, stamp, room) }.getOrDefault(false)
        Result(patched, tagsWritten)
    }

    private fun patchTimes(raf: RandomAccessFile, source: RandomAccessSource, parent: Mp4Box, qtSeconds: Long): Int {
        var patched = 0
        for (box in Mp4.boxes(source, parent.payloadOffset, parent.end)) {
            when (box.type) {
                "mvhd", "tkhd", "mdhd" -> {
                    raf.seek(box.payloadOffset)
                    val version = raf.read()
                    raf.seek(box.payloadOffset + 4)
                    if (version == 1) {
                        raf.writeLong(qtSeconds)
                        raf.writeLong(qtSeconds)
                    } else {
                        raf.writeInt(qtSeconds.toInt())
                        raf.writeInt(qtSeconds.toInt())
                    }
                    patched++
                }
                "trak", "mdia" -> patched += patchTimes(raf, source, box, qtSeconds)
            }
        }
        return patched
    }

    private fun tagsOf(stamp: Stamp): Map<String, String> {
        val tags = LinkedHashMap<String, String>()
        val offset = ZoneOffset.ofTotalSeconds(stamp.utcOffsetSeconds)
        val local = Instant.ofEpochMilli(stamp.creationTimeMillis).atOffset(offset)
        tags["com.apple.quicktime.creationdate"] = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxx").format(local)
        stamp.make?.let {
            tags["com.apple.quicktime.make"] = it
            tags["com.android.manufacturer"] = it
        }
        stamp.model?.let {
            tags["com.apple.quicktime.model"] = it
            tags["com.android.model"] = it
        }
        return tags
    }

    /** Replaces moov's QuickTime `meta` box with one holding the old entries plus ours. */
    private fun rewriteTags(raf: RandomAccessFile, source: RandomAccessSource, moov: Mp4Box, stamp: Stamp, room: Long): Boolean {
        val children = Mp4.boxes(source, moov.payloadOffset, moov.end)
        if (children.sumOf { it.size } != moov.size - moov.headerSize) return false
        // key -> complete `data` box bytes, preserving whatever the muxer already wrote.
        val entries = LinkedHashMap<String, ByteArray>()
        children.firstOrNull { it.type == "meta" }?.let { readRawEntries(source, it, entries) }
        for ((key, value) in tagsOf(stamp)) entries[key] = textDataBox(value)

        val meta = buildMeta(entries)
        val kept = children.filter { it.type != "meta" }
        val newSize = 8L + kept.sumOf { it.size } + meta.size
        if (newSize > Int.MAX_VALUE) return false
        // Whatever is left of the padding must itself be a box: 0 bytes, or at least a header.
        val padding = if (room == Long.MAX_VALUE) 0L else room - newSize
        if (padding < 0 || padding in 1..7) return false
        val out = ByteArrayOutputStream(newSize.toInt())
        out.write(ByteBuffer.allocate(8).putInt(newSize.toInt()).put("moov".toByteArray(Charsets.ISO_8859_1)).array())
        for (box in kept) {
            val bytes = ByteArray(box.size.toInt())
            source.read(box.offset, bytes, 0, bytes.size)
            out.write(bytes)
        }
        out.write(meta)
        if (padding > 0) {
            out.write(ByteBuffer.allocate(8).putInt(padding.toInt()).put("free".toByteArray(Charsets.ISO_8859_1)).array())
        }
        raf.seek(moov.offset)
        raf.write(out.toByteArray())
        if (room == Long.MAX_VALUE) raf.setLength(moov.offset + newSize)
        return true
    }

    private fun readRawEntries(source: RandomAccessSource, meta: Mp4Box, out: MutableMap<String, ByteArray>) {
        val head = ByteArray(12)
        if (meta.size - meta.headerSize < 12) return
        source.read(meta.payloadOffset, head, 0, 12)
        val start = when {
            String(head, 4, 4, Charsets.ISO_8859_1) == "hdlr" -> meta.payloadOffset
            String(head, 8, 4, Charsets.ISO_8859_1) == "hdlr" -> meta.payloadOffset + 4
            else -> return
        }
        val children = Mp4.boxes(source, start, meta.end)
        val keysBox = children.firstOrNull { it.type == "keys" } ?: return
        val ilst = children.firstOrNull { it.type == "ilst" } ?: return
        val keys = ByteArray((keysBox.size - keysBox.headerSize).toInt()).also { source.read(keysBox.payloadOffset, it, 0, it.size) }
        val names = ArrayList<String>()
        val buffer = ByteBuffer.wrap(keys)
        buffer.int
        repeat(buffer.int) {
            val size = buffer.int
            buffer.int
            names += String(ByteArray(size - 8).also { buffer.get(it) }, Charsets.UTF_8)
        }
        for (item in Mp4.boxes(source, ilst.payloadOffset, ilst.end)) {
            val index = ByteBuffer.wrap(item.type.toByteArray(Charsets.ISO_8859_1)).int
            val name = names.getOrNull(index - 1) ?: continue
            val data = Mp4.boxes(source, item.payloadOffset, item.end).firstOrNull { it.type == "data" } ?: continue
            out[name] = ByteArray(data.size.toInt()).also { source.read(data.offset, it, 0, it.size) }
        }
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val size = 8 + parts.sumOf { it.size }
        val buffer = ByteBuffer.allocate(size).putInt(size).put(type.toByteArray(Charsets.ISO_8859_1))
        parts.forEach { buffer.put(it) }
        return buffer.array()
    }

    private fun int(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

    private fun textDataBox(value: String): ByteArray =
        box("data", int(1), int(0), value.toByteArray(Charsets.UTF_8))

    private fun buildMeta(entries: Map<String, ByteArray>): ByteArray {
        val hdlr = box(
            "hdlr",
            int(0), int(0), "mdta".toByteArray(Charsets.ISO_8859_1), ByteArray(12), ByteArray(1),
        )
        val keyEntries = entries.keys.map { key ->
            val name = key.toByteArray(Charsets.UTF_8)
            ByteBuffer.allocate(8 + name.size).putInt(8 + name.size).put("mdta".toByteArray(Charsets.ISO_8859_1)).put(name).array()
        }
        val keys = box("keys", int(0), int(entries.size), *keyEntries.toTypedArray())
        val items = entries.values.mapIndexed { index, data ->
            ByteBuffer.allocate(8 + data.size).putInt(8 + data.size).putInt(index + 1).put(data).array()
        }
        val ilst = box("ilst", *items.toTypedArray())
        return box("meta", hdlr, keys, ilst)
    }
}
