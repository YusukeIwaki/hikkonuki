package app.hikkonuki.android.platform

import android.content.Context
import android.media.MediaCodecList
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import app.hikkonuki.android.core.TranscodeSpec
import app.hikkonuki.android.core.VideoTranscoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.resumeWithException

/**
 * Last-resort conversion through Media3's Transformer: 8-bit, bilinear scaling, audio
 * re-encoded. It copes with device codec quirks the direct pipeline may not, and is the
 * path the previous app shipped with.
 */
class Media3VideoTranscoder(private val context: Context, private val source: AndroidSource) : VideoTranscoder {
    override val name: String = "media3"

    override suspend fun transcode(sourceId: String, spec: TranscodeSpec, output: File, onProgress: (Float) -> Unit): String =
        // Transformer must be created and driven from a Looper thread.
        withContext(Dispatchers.Main) {
            coroutineScope {
                val edited = EditedMediaItem.Builder(MediaItem.fromUri(source.uri(sourceId)))
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(spec.width, spec.height, Presentation.LAYOUT_SCALE_TO_FIT))))
                    .build()
                val videoMime = if (hasEncoder(MimeTypes.VIDEO_H265, spec)) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
                // Without this, Transformer quietly shrinks the picture to whatever some
                // encoder accepts. A smaller video than planned must be an error instead.
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setEnableFallback(false)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(spec.bitrateBps).build())
                    .build()
                var transformer: Transformer? = null
                val poller = launch {
                    val holder = ProgressHolder()
                    while (true) {
                        delay(300)
                        if (transformer?.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                    }
                }
                try {
                    suspendCancellableCoroutine { continuation ->
                        val built = Transformer.Builder(context)
                            .setVideoMimeType(videoMime)
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .setEncoderFactory(encoderFactory)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (continuation.isActive) continuation.resume(Unit)
                                }

                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                    if (continuation.isActive) continuation.resumeWithException(exportException)
                                }
                            })
                            .build()
                        transformer = built
                        continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { runCatching { built.cancel() } } }
                        built.start(edited, output.path)
                    }
                } finally {
                    poller.cancel()
                }
                val codec = if (videoMime == MimeTypes.VIDEO_H265) "HEVC" else "H.264"
                String.format(Locale.US, "%dx%d %s 8bit %.0fMbps (media3)", spec.width, spec.height, codec, spec.bitrateBps / 1e6)
            }
        }

    private fun hasEncoder(mime: String, spec: TranscodeSpec): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                runCatching { codec.getCapabilitiesForType(mime).videoCapabilities.isSizeSupported(spec.width, spec.height) }.getOrDefault(false)
        }
}
